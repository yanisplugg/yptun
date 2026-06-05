package org.olcbox.app.vpn.xray

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.data.model.TrafficSettings

/**
 * Builds an Xray-core JSON configuration from a [ProxyProfile]. Mirrors [org.olcbox.app.vpn.singbox.SingBoxConfig]
 * but in Xray's schema, and additionally supports the xhttp transport (which sing-box lacks).
 *
 * Produces a SOCKS inbound on [listenPort] (consumed by the TUN bridge) and a proxy outbound.
 * When [olcrtcChainPort] is set, the proxy outbound is chained through olcRTC's local SOCKS.
 */
object XrayConfig {

    private const val PROXY_TAG = "proxy"
    private const val OLCRTC_TAG = "olcrtc-out"
    private const val WG_BASE_TAG = "wireguard-base"

    private val json = Json { prettyPrint = true }

    fun build(
        profile: ProxyProfile,
        listenPort: Int,
        listenHost: String = "127.0.0.1",
        socksUsername: String = "",
        socksPassword: String = "",
        olcrtcChainPort: Int? = null,
        olcrtcChainUser: String = "",
        olcrtcChainPass: String = "",
        logLevel: String = "debug",
        traffic: TrafficSettings = TrafficSettings(),
        // VK-TURN chain: when set, [profile] dials its server THROUGH this WireGuard outbound (the
        // WG-over-VK base). Mirrors SingBoxConfig.wireguardBase. The ProxyProfile carries the
        // sing-box-format WG outbound in [ProxyProfile.rawOutbound]; we convert it to Xray schema.
        wireguardBase: ProxyProfile? = null,
        // When set, the `routing` block is generated from this profile (direct/block/proxy buckets
        // with geosite:/geoip:/domain: selectors) and its dns.hosts are merged. The referenced
        // geoip.dat/geosite.dat must be present in XRAY_LOCATION_ASSET.
        routingProfile: org.olcbox.app.data.model.RoutingProfile? = null,
        // Block QUIC (UDP/443) so clients fall back to TCP. MUST be false for UDP-capable tunnels
        // (VK-TURN / WireGuard) which carry QUIC natively — blocking it there breaks those engines.
        blockQuic: Boolean = true,
    ): String {
        val config = buildJsonObject {
            putJsonObject("log") { put("loglevel", logLevel) }

            putJsonObject("dns") {
                val profileHosts = routingProfile?.dnsHosts ?: emptyMap()
                if (traffic.blockRuDomains || profileHosts.isNotEmpty()) {
                    putJsonObject("hosts") {
                        if (traffic.blockRuDomains) RuBlocklist.hostRegexps.forEach { put(it, "0.0.0.0") }
                        profileHosts.forEach { (k, v) -> put(k, v) }
                    }
                }
                putJsonArray("servers") {
                    add(traffic.remoteDns)
                    add(traffic.directDns)
                }
                put("queryStrategy", traffic.xrayQueryStrategy())
            }

            putJsonArray("inbounds") {
                addJsonObject {
                    put("tag", "socks-in")
                    put("listen", listenHost)
                    put("port", listenPort)
                    put("protocol", "socks")
                    putJsonObject("settings") {
                        put("udp", true)
                        if (socksUsername.isNotBlank()) {
                            put("auth", "password")
                            putJsonArray("accounts") {
                                addJsonObject {
                                    put("user", socksUsername)
                                    put("pass", socksPassword)
                                }
                            }
                        } else {
                            put("auth", "noauth")
                        }
                    }
                    putJsonObject("sniffing") {
                        put("enabled", true)
                        putJsonArray("destOverride") { add("http"); add("tls"); add("quic") }
                    }
                }
            }

            val wgBaseOutbound = wireguardBase?.let { buildWireguardBaseOutbound(it) }
            putJsonArray("outbounds") {
                add(
                    buildProxyOutbound(
                        profile,
                        chained = olcrtcChainPort != null,
                        traffic = traffic,
                        detourTagOverride = if (wgBaseOutbound != null) WG_BASE_TAG else null,
                    )
                )
                if (wgBaseOutbound != null) add(wgBaseOutbound)
                addJsonObject {
                    put("tag", "direct")
                    put("protocol", "freedom")
                }
                // Always present: needed by the RU blocklist, profile block buckets AND the QUIC
                // blackhole rule below.
                addJsonObject {
                    put("tag", "block")
                    put("protocol", "blackhole")
                }
                // TLS fragmentation outbound (DPI evasion); proxy dials through it via dialerProxy.
                if (traffic.fragmentEnabled && olcrtcChainPort == null) {
                    addJsonObject {
                        put("tag", "fragment")
                        put("protocol", "freedom")
                        putJsonObject("settings") {
                            putJsonObject("fragment") {
                                put("packets", traffic.fragmentPackets)
                                put("length", traffic.fragmentLength)
                                put("interval", traffic.fragmentInterval)
                            }
                        }
                    }
                }
                if (olcrtcChainPort != null) {
                    addJsonObject {
                        put("tag", OLCRTC_TAG)
                        put("protocol", "socks")
                        putJsonObject("settings") {
                            putJsonArray("servers") {
                                addJsonObject {
                                    put("address", "127.0.0.1")
                                    put("port", olcrtcChainPort)
                                    if (olcrtcChainUser.isNotBlank()) {
                                        putJsonArray("users") {
                                            addJsonObject {
                                                put("user", olcrtcChainUser)
                                                put("pass", olcrtcChainPass)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // QUIC (HTTP/3) over a TCP-only transport (xhttp/reality/ws) can't pass → ERR_QUIC_PROTOCOL
            // on Telemost/Wildberries/Google. Blackhole UDP/443 so clients fall back to TCP/HTTP2.
            val quicBlockRule = buildJsonObject {
                put("type", "field")
                put("network", "udp")
                put("port", 443)
                put("outboundTag", "block")
            }
            // Blocked RU hosts resolve to 0.0.0.0 (dns.hosts above); blackhole anything aimed there.
            val blockZeroRule = buildJsonObject {
                put("type", "field")
                putJsonArray("ip") { add("0.0.0.0") }
                put("outboundTag", "block")
            }
            // Domain-strategy enforcement: blackhole the opposite IP family so ipv4_only / ipv6_only
            // forces ALL traffic onto the chosen family (even DoH apps that resolve the other one).
            val familyBlockRule = when (traffic.domainStrategy) {
                "ipv4_only" -> buildJsonObject {
                    put("type", "field"); putJsonArray("ip") { add("::/0") }; put("outboundTag", "block")
                }
                "ipv6_only" -> buildJsonObject {
                    put("type", "field"); putJsonArray("ip") { add("0.0.0.0/0") }; put("outboundTag", "block")
                }
                else -> null
            }
            // When forcing a family (ipv4_only/ipv6_only) we must resolve domains for routing so the
            // opposite-family blackhole below actually matches domain destinations (with AsIs it only
            // catches raw IP literals, and the remote proxy is then free to pick AAAA → IPv6 leak on
            // 2ip.io). IPIfNonMatch + queryStrategy=UseIPv4 makes Xray resolve to the chosen family.
            val forceFamily = familyBlockRule != null
            putJsonObject("routing") {
                if (routingProfile != null) {
                    // Profile routing (direct/block/proxy buckets) COMBINED with QUIC block + RU
                    // blocklist (item 5): the toggles run alongside the profile, not instead of it.
                    val base = XrayRouting.routingObject(routingProfile)
                    val baseStrategy = base["domainStrategy"] ?: JsonPrimitive("AsIs")
                    put("domainStrategy", if (forceFamily) JsonPrimitive("IPIfNonMatch") else baseStrategy)
                    putJsonArray("rules") {
                        if (blockQuic) add(quicBlockRule)
                        familyBlockRule?.let { add(it) }
                        if (traffic.blockRuDomains) add(blockZeroRule)
                        (base["rules"] as? JsonArray)?.forEach { add(it) }
                    }
                } else {
                    put("domainStrategy", if (forceFamily) "IPIfNonMatch" else "AsIs")
                    putJsonArray("rules") {
                        if (blockQuic) add(quicBlockRule)
                        familyBlockRule?.let { add(it) }
                        if (traffic.blockRuDomains) add(blockZeroRule)
                    }
                }
            }
        }
        return json.encodeToString(config)
    }

    /**
     * Prepares a user-supplied full Xray JSON config to run verbatim through xray-core: the only
     * thing we rewrite is the SOCKS inbound, so it listens on the bridge's [listenHost]:[listenPort]
     * with the expected auth. Everything else (dns.hosts, routing.rules, fakedns, outbounds…) is
     * preserved exactly as the user authored it. Returns the original text unchanged if it can't be
     * parsed (xray-core will then surface the error on start).
     */
    fun prepareRaw(
        rawConfigJson: String,
        listenPort: Int,
        listenHost: String = "127.0.0.1",
        socksUsername: String = "",
        socksPassword: String = "",
    ): String {
        val root = runCatching { Json.parseToJsonElement(rawConfigJson).jsonObject }.getOrNull()
            ?: return rawConfigJson

        // Reuse the user's existing socks inbound (to keep its sniffing/fakedns) when present.
        val existingInbounds = (root["inbounds"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val templateSocks = existingInbounds.firstOrNull {
            it["protocol"]?.jsonPrimitive?.contentOrNull == "socks"
        }
        val nonSocks = existingInbounds.filter {
            it["protocol"]?.jsonPrimitive?.contentOrNull != "socks"
        }

        val socksInbound = buildJsonObject {
            put("tag", "socks-in")
            put("listen", listenHost)
            put("port", listenPort)
            put("protocol", "socks")
            putJsonObject("settings") {
                put("udp", true)
                if (socksUsername.isNotBlank()) {
                    put("auth", "password")
                    putJsonArray("accounts") {
                        addJsonObject {
                            put("user", socksUsername)
                            put("pass", socksPassword)
                        }
                    }
                } else {
                    put("auth", "noauth")
                }
            }
            // Preserve the user's sniffing block (carries fakedns/destOverride needed for hosts blocking).
            val sniffing = templateSocks?.get("sniffing")
            if (sniffing != null) {
                put("sniffing", sniffing)
            } else {
                putJsonObject("sniffing") {
                    put("enabled", true)
                    putJsonArray("destOverride") { add("http"); add("tls"); add("quic") }
                }
            }
        }

        val newRoot = buildJsonObject {
            root.forEach { (key, value) ->
                if (key != "inbounds") put(key, value)
            }
            putJsonArray("inbounds") {
                add(socksInbound)
                nonSocks.forEach { add(it) }
            }
        }
        return json.encodeToString(newRoot)
    }

    /**
     * Converts the sing-box WireGuard outbound stored in [profile].rawOutbound into an Xray
     * `wireguard` outbound (tag [WG_BASE_TAG]) so a chained proxy can dial through the VK tunnel.
     */
    private fun buildWireguardBaseOutbound(profile: ProxyProfile): JsonObject? {
        val raw = profile.rawOutbound?.takeIf { it.isNotBlank() } ?: return null
        val o = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val secret = o["private_key"]?.jsonPrimitive?.contentOrNull ?: return null
        val peerPub = o["peer_public_key"]?.jsonPrimitive?.contentOrNull ?: return null
        val server = o["server"]?.jsonPrimitive?.contentOrNull ?: "127.0.0.1"
        val port = o["server_port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return null
        val addresses = (o["local_address"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val mtu = o["mtu"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val keepalive = o["persistent_keepalive_interval"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: o["persistent_keepalive"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: 25
        return buildJsonObject {
            put("tag", WG_BASE_TAG)
            put("protocol", "wireguard")
            putJsonObject("settings") {
                put("secretKey", secret)
                putJsonArray("address") { addresses.forEach { add(it) } }
                // The WG-over-VK tunnel is IPv4-only; resolve the chained proxy's server to IPv4 so it
                // doesn't dial an AAAA address with no route through the tunnel (→ connection reset).
                put("domainStrategy", "ForceIPv4")
                putJsonArray("peers") {
                    addJsonObject {
                        put("publicKey", peerPub)
                        put("endpoint", "$server:$port")
                        putJsonArray("allowedIPs") { add("0.0.0.0/0"); add("::/0") }
                        // Keep the tunnel alive through the TURN relay / NAT (matches wg-quick clients).
                        put("keepAlive", keepalive)
                    }
                }
                if (mtu != null) put("mtu", mtu)
            }
        }
    }

    private fun buildProxyOutbound(
        profile: ProxyProfile,
        chained: Boolean,
        traffic: TrafficSettings = TrafficSettings(),
        // When set, the proxy dials through this outbound tag (e.g. the VK-TURN WireGuard base);
        // takes precedence over the olcRTC chain detour.
        detourTagOverride: String? = null,
    ) = buildJsonObject {
        val detourTag = detourTagOverride ?: if (chained) OLCRTC_TAG else null
        put("tag", PROXY_TAG)
        put("protocol", profile.type)

        putJsonObject("settings") {
            when (profile.type) {
                ProxyProfile.TYPE_VLESS, ProxyProfile.TYPE_VMESS -> {
                    putJsonArray("vnext") {
                        addJsonObject {
                            put("address", profile.server)
                            put("port", profile.serverPort)
                            putJsonArray("users") {
                                addJsonObject {
                                    put("id", profile.uuid)
                                    if (profile.type == ProxyProfile.TYPE_VLESS) {
                                        put("encryption", "none")
                                        if (profile.flow.isNotBlank()) put("flow", profile.flow)
                                    } else {
                                        put("alterId", profile.alterId)
                                        put("security", profile.cipher.ifBlank { "auto" })
                                    }
                                }
                            }
                        }
                    }
                }

                ProxyProfile.TYPE_TROJAN -> {
                    putJsonArray("servers") {
                        addJsonObject {
                            put("address", profile.server)
                            put("port", profile.serverPort)
                            put("password", profile.password)
                        }
                    }
                }

                ProxyProfile.TYPE_SHADOWSOCKS -> {
                    putJsonArray("servers") {
                        addJsonObject {
                            put("address", profile.server)
                            put("port", profile.serverPort)
                            put("method", profile.method)
                            put("password", profile.password)
                        }
                    }
                }
            }
        }

        put("streamSettings", buildStreamSettings(profile, fragmentDialer = traffic.fragmentEnabled && detourTag == null))

        if (detourTag != null) {
            putJsonObject("proxySettings") { put("tag", detourTag) }
        }

        if (traffic.muxEnabled) {
            putJsonObject("mux") {
                put("enabled", true)
                put("concurrency", traffic.muxMaxConnections)
            }
        }
    }

    private fun buildStreamSettings(profile: ProxyProfile, fragmentDialer: Boolean = false) = buildJsonObject {
        if (fragmentDialer) {
            putJsonObject("sockopt") { put("dialerProxy", "fragment") }
        }
        val network = when (profile.network) {
            ProxyProfile.NETWORK_WS -> "ws"
            ProxyProfile.NETWORK_GRPC -> "grpc"
            ProxyProfile.NETWORK_HTTP -> "http"
            ProxyProfile.NETWORK_HTTPUPGRADE -> "httpupgrade"
            ProxyProfile.NETWORK_XHTTP -> "xhttp"
            else -> "tcp"
        }
        put("network", network)

        when (profile.security) {
            ProxyProfile.SECURITY_TLS -> {
                put("security", "tls")
                putJsonObject("tlsSettings") {
                    put("serverName", profile.sni.ifBlank { profile.server })
                    put("allowInsecure", profile.allowInsecure)
                    if (profile.fingerprint.isNotBlank()) put("fingerprint", profile.fingerprint)
                    if (profile.alpn.isNotEmpty()) {
                        putJsonArray("alpn") { profile.alpn.forEach { add(it) } }
                    }
                }
            }

            ProxyProfile.SECURITY_REALITY -> {
                put("security", "reality")
                putJsonObject("realitySettings") {
                    put("serverName", profile.sni.ifBlank { profile.server })
                    if (profile.fingerprint.isNotBlank()) put("fingerprint", profile.fingerprint)
                    put("publicKey", profile.realityPublicKey)
                    put("shortId", profile.realityShortId)
                }
            }

            else -> put("security", "none")
        }

        when (network) {
            "ws" -> putJsonObject("wsSettings") {
                if (profile.path.isNotBlank()) put("path", profile.path)
                if (profile.host.isNotBlank()) putJsonObject("headers") { put("Host", profile.host) }
            }

            "grpc" -> putJsonObject("grpcSettings") {
                if (profile.path.isNotBlank()) put("serviceName", profile.path)
            }

            "xhttp" -> putJsonObject("xhttpSettings") {
                if (profile.path.isNotBlank()) put("path", profile.path)
                if (profile.host.isNotBlank()) put("host", profile.host)
                put("mode", "auto")
            }

            "httpupgrade" -> putJsonObject("httpupgradeSettings") {
                if (profile.path.isNotBlank()) put("path", profile.path)
                if (profile.host.isNotBlank()) put("host", profile.host)
            }

            "http" -> putJsonObject("httpSettings") {
                if (profile.path.isNotBlank()) put("path", profile.path)
                if (profile.host.isNotBlank()) putJsonArray("host") { add(profile.host) }
            }
        }
    }
}
