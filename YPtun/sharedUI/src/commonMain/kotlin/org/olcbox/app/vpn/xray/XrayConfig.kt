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
        logLevel: String = "warning",
        traffic: TrafficSettings = TrafficSettings(),
    ): String {
        val config = buildJsonObject {
            putJsonObject("log") { put("loglevel", logLevel) }

            putJsonObject("dns") {
                if (traffic.blockRuDomains) {
                    putJsonObject("hosts") {
                        RuBlocklist.hostRegexps.forEach { put(it, "0.0.0.0") }
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

            putJsonArray("outbounds") {
                add(buildProxyOutbound(profile, chained = olcrtcChainPort != null, traffic = traffic))
                addJsonObject {
                    put("tag", "direct")
                    put("protocol", "freedom")
                }
                if (traffic.blockRuDomains) {
                    addJsonObject {
                        put("tag", "block")
                        put("protocol", "blackhole")
                    }
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

            putJsonObject("routing") {
                put("domainStrategy", "AsIs")
                putJsonArray("rules") {
                    if (traffic.blockRuDomains) {
                        // Blocked hosts resolve to 0.0.0.0 (above); blackhole anything aimed there.
                        addJsonObject {
                            put("type", "field")
                            putJsonArray("ip") { add("0.0.0.0") }
                            put("outboundTag", "block")
                        }
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

    private fun buildProxyOutbound(
        profile: ProxyProfile,
        chained: Boolean,
        traffic: TrafficSettings = TrafficSettings()
    ) = buildJsonObject {
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

        put("streamSettings", buildStreamSettings(profile, fragmentDialer = traffic.fragmentEnabled && !chained))

        if (chained) {
            putJsonObject("proxySettings") { put("tag", OLCRTC_TAG) }
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
