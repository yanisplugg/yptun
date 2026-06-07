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

    // FakeDNS synthetic-IP pool (matches the v2rayNG/Happ default).
    private const val FAKEDNS_SERVER = "fakedns"
    private const val FAKEDNS_POOL = "198.18.0.0/15"
    private const val FAKEDNS_POOL_SIZE = 65535

    private val json = Json { prettyPrint = true }

    /**
     * `domainStrategy` for the `direct` (freedom) outbound. CRITICAL on Android: with the default
     * `AsIs`, freedom hands the bare hostname to Go's system resolver, which can't resolve on Android
     * (no usable /etc/resolv.conf, and the process is unbound from any network once connected) — so
     * `domain:ru → direct` "did nothing" on xray (RU sites never resolved) while sing-box worked.
     * Forcing `UseIP*` makes xray resolve direct destinations through its own `dns` block (queried over
     * protected sockets) before dialing, mirroring sing-box's direct outbound. Family-matched so it
     * stays consistent with the ipv4_only/ipv6_only enforcement.
     */
    private fun directDomainStrategy(traffic: TrafficSettings): String = when (traffic.domainStrategy) {
        "ipv4_only" -> "UseIPv4"
        "ipv6_only" -> "UseIPv6"
        else -> "UseIP"
    }

    // --- FakeDNS building blocks (shared by build() and prepareRaw()) ---

    /** Routing rules that hijack DNS (UDP/53, TCP/853 DoT) to the `dns-out` outbound. */
    private fun dnsHijackRules(): List<JsonObject> = listOf(
        buildJsonObject { put("type", "field"); put("network", "udp"); put("port", 53); put("outboundTag", "dns-out") },
        buildJsonObject { put("type", "field"); put("network", "tcp"); put("port", 853); put("outboundTag", "dns-out") },
    )

    /** The `dns` protocol outbound that answers hijacked queries (tag `dns-out`). */
    private fun dnsOutOutbound(): JsonObject = buildJsonObject {
        put("tag", "dns-out")
        put("protocol", "dns")
        putJsonObject("settings") { put("nonIPQuery", "skip") }
    }

    /** A single synthetic-IP pool entry for the top-level `fakedns` array. */
    private fun fakeDnsPool(): JsonObject = buildJsonObject {
        put("ipPool", FAKEDNS_POOL)
        put("poolSize", FAKEDNS_POOL_SIZE)
    }

    /** Returns [sniffing] with `fakedns` ensured in destOverride (building a default block if null). */
    private fun sniffingWithFakeDns(sniffing: JsonObject?): JsonObject = buildJsonObject {
        val existing = (sniffing?.get("destOverride") as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: listOf("http", "tls", "quic")
        sniffing?.forEach { (k, v) -> if (k != "destOverride" && k != "enabled") put(k, v) }
        put("enabled", true)
        putJsonArray("destOverride") {
            existing.forEach { add(it) }
            if ("fakedns" !in existing) add("fakedns")
        }
    }

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
        // When false, the proxy is not applied: in Chain the [PROXY_TAG] exit becomes olcRTC's SOCKS so
        // traffic still rides the stealth tunnel (the "main outbound"); otherwise (Standard, no chain
        // base) it becomes a `freedom` (direct) outbound. The proxy config is kept either way, just not dialed.
        proxyEnabled: Boolean = true,
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
                    // FakeDNS first so sniffed domains get a synthetic IP before the real resolvers.
                    if (traffic.fakeDnsEnabled) add(FAKEDNS_SERVER)
                    add(traffic.remoteDns)
                    add(traffic.directDns)
                }
                put("queryStrategy", traffic.xrayQueryStrategy())
            }

            // Synthetic-IP pool for FakeDNS (apps see 198.18.x.x; the domain is recovered via the
            // socks inbound's `fakedns` sniffing and resolved behind the proxy).
            if (traffic.fakeDnsEnabled) {
                putJsonArray("fakedns") { add(fakeDnsPool()) }
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
                        // Expert mode can disable sniffing or switch it to route-only (sniff for routing
                        // but keep dialing the original destination). Sniffing ON is required for
                        // `domain:` rules to match by SNI/Host — the usual reason domain:ru "does nothing".
                        val expert = routingProfile?.expertEnabled == true
                        put("enabled", if (expert) routingProfile!!.xraySniffing else true)
                        putJsonArray("destOverride") {
                            add("http"); add("tls"); add("quic")
                            // Recover the real domain from a FakeDNS synthetic-IP connection.
                            if (traffic.fakeDnsEnabled) add("fakedns")
                        }
                        if (expert && routingProfile!!.xrayRouteOnly) put("routeOnly", true)
                    }
                }
            }

            val wgBaseOutbound = wireguardBase?.let { buildWireguardBaseOutbound(it) }
            putJsonArray("outbounds") {
                if (proxyEnabled) {
                    add(
                        buildProxyOutbound(
                            profile,
                            chained = olcrtcChainPort != null,
                            traffic = traffic,
                            detourTagOverride = if (wgBaseOutbound != null) WG_BASE_TAG else null,
                        )
                    )
                    if (wgBaseOutbound != null) add(wgBaseOutbound)
                } else if (olcrtcChainPort != null) {
                    // Proxy disabled in Chain: the default outbound becomes olcRTC's local SOCKS so traffic
                    // still rides the stealth tunnel (the "main outbound"). Turning the proxy off must only
                    // drop the extra proxy hop — NOT fall back to a direct, no-VPN exit.
                    add(olcrtcSocksOutbound(PROXY_TAG, olcrtcChainPort, olcrtcChainUser, olcrtcChainPass))
                } else {
                    // Proxy disabled with no chain base (Standard): exit directly (the proxy was the only hop).
                    addJsonObject {
                        put("tag", PROXY_TAG)
                        put("protocol", "freedom")
                        putJsonObject("settings") {
                            put("domainStrategy", directDomainStrategy(traffic))
                        }
                    }
                }
                addJsonObject {
                    put("tag", "direct")
                    put("protocol", "freedom")
                    putJsonObject("settings") {
                        // Resolve direct destinations via xray's own DNS, not Go's (broken on Android).
                        put("domainStrategy", directDomainStrategy(traffic))
                    }
                }
                // Always present: needed by the RU blocklist, profile block buckets AND the QUIC
                // blackhole rule below.
                addJsonObject {
                    put("tag", "block")
                    put("protocol", "blackhole")
                }
                // FakeDNS: DNS queries (port 53/853) are hijacked to this dns outbound so the core
                // answers them from the fake pool / upstream instead of leaking them out.
                if (traffic.fakeDnsEnabled) add(dnsOutOutbound())
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
                // olcRTC chain detour — only while the proxy is enabled (the proxy outbound dials through
                // this tag). With the proxy off, PROXY_TAG already IS the olcRTC SOCKS above.
                if (olcrtcChainPort != null && proxyEnabled) {
                    add(olcrtcSocksOutbound(OLCRTC_TAG, olcrtcChainPort, olcrtcChainUser, olcrtcChainPass))
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
            // FakeDNS: route DNS queries to the dns outbound so they're answered from the fake pool.
            val dnsOutRules = if (traffic.fakeDnsEnabled) dnsHijackRules() else emptyList()
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
                        // DNS hijack first so port-53/853 queries reach dns-out before any other rule.
                        dnsOutRules.forEach { add(it) }
                        if (blockQuic) add(quicBlockRule)
                        familyBlockRule?.let { add(it) }
                        if (traffic.blockRuDomains) add(blockZeroRule)
                        (base["rules"] as? JsonArray)?.forEach { add(it) }
                    }
                } else {
                    put("domainStrategy", if (forceFamily) "IPIfNonMatch" else "AsIs")
                    putJsonArray("rules") {
                        dnsOutRules.forEach { add(it) }
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
        // When set, the profile's routing buckets (e.g. domain:ru → direct) are MERGED into the user's
        // verbatim config. Without this the routing profile was silently ignored for full custom Xray
        // configs (common with server-side cascade setups), so domain:ru never went direct on vless.
        routingProfile: org.olcbox.app.data.model.RoutingProfile? = null,
        // Global FakeDNS toggle. When on AND the raw config doesn't already define `fakedns`, we inject
        // the fakedns plumbing (pool + dns server + sniffing + dns-out). A config that ALREADY carries
        // its own `fakedns` (e.g. a Remnawave/Happ subscription) is honored verbatim — never overwritten.
        fakeDnsEnabled: Boolean = false,
    ): String {
        val root = runCatching { Json.parseToJsonElement(rawConfigJson).jsonObject }.getOrNull()
            ?: return rawConfigJson

        // Only inject when the user's config doesn't already opt into FakeDNS (honor wins).
        val injectFake = fakeDnsEnabled && root["fakedns"] == null

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
            val sniffing = templateSocks?.get("sniffing") as? JsonObject
            when {
                // Injecting FakeDNS: ensure the sniffer recovers the domain from a synthetic IP.
                injectFake -> put("sniffing", sniffingWithFakeDns(sniffing))
                sniffing != null -> put("sniffing", sniffing)
                else -> putJsonObject("sniffing") {
                    put("enabled", true)
                    putJsonArray("destOverride") { add("http"); add("tls"); add("quic") }
                }
            }
        }

        // Merge profile routing (direct/block buckets) into the user's config, if requested.
        val userOutbounds = (root["outbounds"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val userOutboundTags = userOutbounds.mapNotNull { it["tag"]?.jsonPrimitive?.contentOrNull }.toSet()
        val userRouting = root["routing"] as? JsonObject
        val mergedRouting: JsonObject? = routingProfile?.let { rp ->
            buildJsonObject {
                // Keep the user's domainStrategy if they set one; else the profile's.
                val ds = userRouting?.get("domainStrategy")?.jsonPrimitive?.contentOrNull
                    ?: XrayRouting.domainStrategy(rp)
                put("domainStrategy", ds)
                putJsonArray("rules") {
                    // Profile buckets FIRST so domain:ru → direct wins over the user's catch-all proxy.
                    XrayRouting.bucketRules(rp).forEach { add(it) }
                    // Then the user's own rules (their proxy default / custom splits).
                    (userRouting?.get("rules") as? JsonArray)?.forEach { add(it) }
                    // Selective-proxy fall-through (only when not a global-proxy profile).
                    XrayRouting.catchAllDirectRule(rp)?.let { add(it) }
                }
            }
        }

        // FakeDNS injection (only when the raw config doesn't already define it): augment `dns` with a
        // `fakedns` server, add the synthetic-IP pool, and hijack DNS ports to a `dns-out` outbound.
        val rawDns = root["dns"] as? JsonObject
        val augmentedDns = if (injectFake) buildJsonObject {
            rawDns?.forEach { (k, v) -> if (k != "servers") put(k, v) }
            putJsonArray("servers") {
                add("fakedns")
                val servers = rawDns?.get("servers") as? JsonArray
                if (servers != null) servers.forEach { add(it) } else add("1.1.1.1")
            }
            if (rawDns?.get("queryStrategy") == null) put("queryStrategy", "UseIP")
        } else null

        // Build the final routing object, prepending the DNS-hijack rules when injecting FakeDNS.
        val baseRouting = mergedRouting ?: userRouting
        val finalRouting: JsonObject? = when {
            injectFake -> buildJsonObject {
                baseRouting?.forEach { (k, v) -> if (k != "rules") put(k, v) }
                if (baseRouting?.get("domainStrategy") == null) put("domainStrategy", "AsIs")
                putJsonArray("rules") {
                    dnsHijackRules().forEach { add(it) }
                    (baseRouting?.get("rules") as? JsonArray)?.forEach { add(it) }
                }
            }
            mergedRouting != null -> mergedRouting
            userRouting != null -> userRouting
            else -> null
        }

        val newRoot = buildJsonObject {
            root.forEach { (key, value) ->
                if (key != "inbounds" && key != "outbounds" && key != "routing" &&
                    !(injectFake && (key == "dns" || key == "fakedns"))
                ) {
                    put(key, value)
                }
            }
            if (augmentedDns != null) put("dns", augmentedDns)
            if (injectFake) putJsonArray("fakedns") { add(fakeDnsPool()) }
            putJsonArray("inbounds") {
                add(socksInbound)
                nonSocks.forEach { add(it) }
            }
            putJsonArray("outbounds") {
                userOutbounds.forEach { add(it) }
                // The profile rules reference direct/block tags — make sure they exist.
                if (mergedRouting != null && "direct" !in userOutboundTags) {
                    addJsonObject {
                        put("tag", "direct")
                        put("protocol", "freedom")
                        // Resolve via xray's DNS (Go's resolver can't on Android) so direct rules work.
                        putJsonObject("settings") { put("domainStrategy", "UseIP") }
                    }
                }
                if (mergedRouting != null && "block" !in userOutboundTags) {
                    addJsonObject { put("tag", "block"); put("protocol", "blackhole") }
                }
                // FakeDNS dns outbound for the hijacked queries.
                if (injectFake && "dns-out" !in userOutboundTags) add(dnsOutOutbound())
            }
            if (finalRouting != null) put("routing", finalRouting)
        }
        return json.encodeToString(newRoot)
    }

    /**
     * Converts the sing-box WireGuard outbound stored in [profile].rawOutbound into an Xray
     * `wireguard` outbound (tag [WG_BASE_TAG]) so a chained proxy can dial through the VK tunnel.
     */
    /**
     * A SOCKS outbound pointing at olcRTC's local listener on [port], in Xray schema. Used as the
     * chain detour ([OLCRTC_TAG]) and, when the proxy is disabled, as the default exit ([PROXY_TAG])
     * so Chain traffic keeps riding the stealth tunnel instead of leaking out directly.
     */
    private fun olcrtcSocksOutbound(tag: String, port: Int, user: String, pass: String): JsonObject =
        buildJsonObject {
            put("tag", tag)
            put("protocol", "socks")
            putJsonObject("settings") {
                putJsonArray("servers") {
                    addJsonObject {
                        put("address", "127.0.0.1")
                        put("port", port)
                        if (user.isNotBlank()) {
                            putJsonArray("users") {
                                addJsonObject {
                                    put("user", user)
                                    put("pass", pass)
                                }
                            }
                        }
                    }
                }
            }
        }

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
