package org.olcbox.app.vpn.singbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.data.model.RoutingRules
import org.olcbox.app.data.model.TrafficSettings

/**
 * Builds a sing-box (1.11+) JSON configuration from a [ProxyProfile].
 *
 * The generated config exposes a single SOCKS5 inbound on [listenPort] (consumed by the
 * existing TUN→SOCKS bridge) and a proxy outbound for the profile. When [olcrtcChainPort]
 * is provided the proxy outbound dials through olcRTC's local SOCKS (the "chain" engine):
 * a normal VLESS connection wrapped inside the WebRTC stealth tunnel.
 */
object SingBoxConfig {

    private const val PROXY_TAG = "proxy"
    private const val OLCRTC_TAG = "olcrtc-out"
    private const val SOCKS_IN_TAG = "socks-in"

    private val json = Json { prettyPrint = true }

    fun build(
        profile: ProxyProfile,
        listenPort: Int,
        listenHost: String = "127.0.0.1",
        socksUsername: String = "",
        socksPassword: String = "",
        dns: String = "1.1.1.1",
        olcrtcChainPort: Int? = null,
        olcrtcChainUser: String = "",
        olcrtcChainPass: String = "",
        logLevel: String = "warn",
        // On Android we bind the whole process to the upstream network (like olcRTC), so
        // sing-box must not try to detect/bind an interface itself. Desktop can enable it.
        autoDetectInterface: Boolean = false,
        routing: RoutingRules = RoutingRules(),
        traffic: TrafficSettings = TrafficSettings(),
    ): String {
        val config = buildJsonObject {
            putJsonObject("log") {
                put("level", logLevel)
                put("timestamp", true)
            }

            putJsonObject("dns") {
                putJsonArray("servers") {
                    // App traffic resolves through the proxy (no DNS leak).
                    addJsonObject {
                        put("tag", "remote")
                        put("address", traffic.remoteDns)
                        put("detour", PROXY_TAG)
                    }
                    // Bootstrap: resolve the proxy server's own domain directly.
                    addJsonObject {
                        put("tag", "direct")
                        put("address", traffic.directDns)
                        put("detour", "direct")
                    }
                }
                putJsonArray("rules") {
                    addJsonObject {
                        put("outbound", "any")
                        put("server", "direct")
                    }
                }
                put("final", "remote")
                put("strategy", traffic.domainStrategy)
            }

            putJsonArray("inbounds") {
                addJsonObject {
                    put("type", "socks")
                    put("tag", SOCKS_IN_TAG)
                    put("listen", listenHost)
                    put("listen_port", listenPort)
                    if (socksUsername.isNotBlank()) {
                        putJsonArray("users") {
                            addJsonObject {
                                put("username", socksUsername)
                                put("password", socksPassword)
                            }
                        }
                    }
                }
            }

            putJsonArray("outbounds") {
                add(buildProxyOutbound(profile, chained = olcrtcChainPort != null, traffic = traffic))
                if (olcrtcChainPort != null) {
                    addJsonObject {
                        put("type", "socks")
                        put("tag", OLCRTC_TAG)
                        put("server", "127.0.0.1")
                        put("server_port", olcrtcChainPort)
                        put("version", "5")
                        if (olcrtcChainUser.isNotBlank()) {
                            put("username", olcrtcChainUser)
                            put("password", olcrtcChainPass)
                        }
                    }
                }
                addJsonObject {
                    put("type", "direct")
                    put("tag", "direct")
                }
            }

            putJsonObject("route") {
                put("final", PROXY_TAG)
                put("auto_detect_interface", autoDetectInterface)

                putJsonArray("rules") {
                    // Sniff destination domain so domain rules match.
                    addJsonObject { put("action", "sniff") }
                    if (routing.bypassLan) {
                        addJsonObject {
                            put("ip_is_private", true)
                            put("outbound", "direct")
                        }
                    }
                    if (routing.blockDomains.isNotEmpty()) {
                        addJsonObject {
                            putJsonArray("domain_suffix") { routing.blockDomains.forEach { add(it) } }
                            put("action", "reject")
                        }
                    }
                    if (routing.directDomains.isNotEmpty()) {
                        addJsonObject {
                            putJsonArray("domain_suffix") { routing.directDomains.forEach { add(it) } }
                            put("outbound", "direct")
                        }
                    }
                    if (routing.blockAds) {
                        addJsonObject {
                            put("rule_set", "geosite-ads")
                            put("action", "reject")
                        }
                    }
                    if (routing.bypassRussia) {
                        addJsonObject {
                            putJsonArray("rule_set") { add("geoip-ru"); add("geosite-ru") }
                            put("outbound", "direct")
                        }
                    }
                }

                if (routing.blockAds || routing.bypassRussia) {
                    putJsonArray("rule_set") {
                        if (routing.blockAds) {
                            addJsonObject {
                                put("type", "remote")
                                put("tag", "geosite-ads")
                                put("format", "binary")
                                put("url", "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ads-all.srs")
                                put("download_detour", "direct")
                            }
                        }
                        if (routing.bypassRussia) {
                            addJsonObject {
                                put("type", "remote")
                                put("tag", "geoip-ru")
                                put("format", "binary")
                                put("url", "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-ru.srs")
                                put("download_detour", "direct")
                            }
                            addJsonObject {
                                put("type", "remote")
                                put("tag", "geosite-ru")
                                put("format", "binary")
                                put("url", "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ru.srs")
                                put("download_detour", "direct")
                            }
                        }
                    }
                }
            }
        }
        return json.encodeToString(config)
    }

    private fun buildProxyOutbound(
        profile: ProxyProfile,
        chained: Boolean,
        traffic: TrafficSettings = TrafficSettings()
    ): JsonObject {
        // Catch-all: a raw sing-box outbound is used verbatim (tag/detour injected).
        profile.rawOutbound?.takeIf { it.isNotBlank() }?.let { raw ->
            val rawObj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            if (rawObj != null) {
                return buildJsonObject {
                    rawObj.forEach { (k, v) -> if (k != "tag" && k != "detour") put(k, v) }
                    put("tag", PROXY_TAG)
                    if (chained) put("detour", OLCRTC_TAG)
                    if (raw.indexOf("multiplex") < 0) buildMultiplex(traffic)?.let { put("multiplex", it) }
                }
            }
        }

        return buildJsonObject {
            put("type", profile.type)
            put("tag", PROXY_TAG)
            put("server", profile.server)
            put("server_port", profile.serverPort)

            when (profile.type) {
                ProxyProfile.TYPE_VLESS -> {
                    put("uuid", profile.uuid)
                    if (profile.flow.isNotBlank()) {
                        put("flow", profile.flow)
                    } else {
                        put("packet_encoding", "xudp")
                    }
                }

                ProxyProfile.TYPE_VMESS -> {
                    put("uuid", profile.uuid)
                    put("alter_id", profile.alterId)
                    put("security", profile.cipher.ifBlank { "auto" })
                }

                ProxyProfile.TYPE_TROJAN -> {
                    put("password", profile.password)
                }

                ProxyProfile.TYPE_SHADOWSOCKS -> {
                    put("method", profile.method)
                    put("password", profile.password)
                }
            }

            // TLS/transport apply to vless/vmess/trojan; shadowsocks ignores them.
            if (profile.type != ProxyProfile.TYPE_SHADOWSOCKS) {
                buildTls(profile)?.let { put("tls", it) }
                buildTransport(profile)?.let { put("transport", it) }
            }

            if (chained) put("detour", OLCRTC_TAG)
            buildMultiplex(traffic)?.let { put("multiplex", it) }
        }
    }

    private fun buildMultiplex(traffic: TrafficSettings): JsonObject? {
        if (!traffic.muxEnabled) return null
        return buildJsonObject {
            put("enabled", true)
            put("protocol", traffic.muxProtocol)
            put("max_connections", traffic.muxMaxConnections)
        }
    }

    private fun buildTls(profile: ProxyProfile) = when (profile.security) {
        ProxyProfile.SECURITY_TLS, ProxyProfile.SECURITY_REALITY -> buildJsonObject {
            put("enabled", true)
            put("server_name", profile.sni.ifBlank { profile.server })
            put("insecure", profile.allowInsecure)
            if (profile.alpn.isNotEmpty()) {
                putJsonArray("alpn") { profile.alpn.forEach { add(it) } }
            }
            if (profile.fingerprint.isNotBlank()) {
                putJsonObject("utls") {
                    put("enabled", true)
                    put("fingerprint", profile.fingerprint)
                }
            }
            if (profile.security == ProxyProfile.SECURITY_REALITY &&
                profile.realityPublicKey.isNotBlank()
            ) {
                putJsonObject("reality") {
                    put("enabled", true)
                    put("public_key", profile.realityPublicKey)
                    put("short_id", profile.realityShortId)
                }
            }
        }

        else -> null
    }

    private fun buildTransport(profile: ProxyProfile) = when (profile.network) {
        ProxyProfile.NETWORK_WS -> buildJsonObject {
            put("type", "ws")
            if (profile.path.isNotBlank()) put("path", profile.path)
            if (profile.host.isNotBlank()) {
                putJsonObject("headers") { put("Host", profile.host) }
            }
        }

        ProxyProfile.NETWORK_HTTPUPGRADE -> buildJsonObject {
            put("type", "httpupgrade")
            if (profile.path.isNotBlank()) put("path", profile.path)
            if (profile.host.isNotBlank()) put("host", profile.host)
        }

        ProxyProfile.NETWORK_GRPC -> buildJsonObject {
            put("type", "grpc")
            if (profile.path.isNotBlank()) put("service_name", profile.path)
        }

        ProxyProfile.NETWORK_HTTP -> buildJsonObject {
            put("type", "http")
            if (profile.path.isNotBlank()) put("path", profile.path)
            if (profile.host.isNotBlank()) {
                putJsonArray("host") { add(profile.host) }
            }
        }

        ProxyProfile.NETWORK_XHTTP -> throw IllegalArgumentException(
            "xhttp/splithttp transport requires the Xray core, which is not supported yet"
        )

        else -> null // tcp: no transport block
    }
}
