package org.olcbox.app.vpn.singbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.olcbox.app.data.model.AdvancedCoreConfig
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
    private const val WG_BASE_TAG = "wireguard-base"
    private const val SOCKS_IN_TAG = "socks-in"

    /** Raw-outbound types that do not support sing-box smux and must not get a multiplex block. */
    private val RAW_OUTBOUND_NO_MUX = setOf("wireguard", "hysteria2", "hysteria", "tuic", "endpoint", "socks")

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
        // VK-TURN chain: when set, [profile] is the chained proxy and this WireGuard profile is
        // added as the base outbound; the proxy dials its server THROUGH WireGuard (detour).
        wireguardBase: ProxyProfile? = null,
        // Overrides the DNS resolution strategy (e.g. "ipv4_only" for an IPv4-only WG tunnel).
        dnsStrategyOverride: String? = null,
        // Per-location advanced core options (mux / tcp_fast_open / sniff). Null = defaults.
        advanced: AdvancedCoreConfig? = null,
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
                // ipv4_only override (VK-TURN): the WireGuard tunnel is IPv4-only, so resolving
                // AAAA would make dual-stack sites attempt IPv6 → "no route to host". Forcing A-only
                // keeps all traffic on IPv4 through the tunnel.
                put("strategy", dnsStrategyOverride ?: traffic.domainStrategy)
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
                val wgBaseOutbound = wireguardBase?.let { buildWireguardBaseOutbound(it) }
                add(
                    buildProxyOutbound(
                        profile,
                        chained = olcrtcChainPort != null,
                        traffic = traffic,
                        detourTagOverride = if (wgBaseOutbound != null) WG_BASE_TAG else null,
                        advanced = advanced
                    )
                )
                if (wgBaseOutbound != null) {
                    add(wgBaseOutbound)
                }
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
                    // Sniff destination domain so domain rules match (can be disabled in advanced).
                    if (advanced?.sniff != false) {
                        addJsonObject { put("action", "sniff") }
                    }
                    // Advanced: verbatim user route.rules take precedence over the toggle-based rules.
                    parseJsonArray(routing.customRulesJson).forEach { add(it) }
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

                val customRuleSets = parseJsonArray(routing.customRuleSetsJson)
                if (routing.blockAds || routing.bypassRussia || customRuleSets.isNotEmpty()) {
                    putJsonArray("rule_set") {
                        customRuleSets.forEach { add(it) }
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

    /** Parses a verbatim JSON array string into its elements; returns empty on blank/invalid input. */
    private fun parseJsonArray(raw: String): List<kotlinx.serialization.json.JsonElement> {
        if (raw.isBlank()) return emptyList()
        return runCatching { Json.parseToJsonElement(raw).jsonArray.toList() }.getOrDefault(emptyList())
    }

    private fun buildProxyOutbound(
        profile: ProxyProfile,
        chained: Boolean,
        traffic: TrafficSettings = TrafficSettings(),
        // When set, the proxy is chained over this outbound tag (e.g. the WireGuard base for
        // VK-TURN). Takes precedence over the olcRTC [chained] detour.
        detourTagOverride: String? = null,
        advanced: AdvancedCoreConfig? = null
    ): JsonObject {
        val detourTag = detourTagOverride ?: if (chained) OLCRTC_TAG else null
        val tfo = advanced?.tcpFastOpen == true
        // Catch-all: a raw sing-box outbound is used verbatim (tag/detour injected).
        profile.rawOutbound?.takeIf { it.isNotBlank() }?.let { raw ->
            val rawObj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            if (rawObj != null) {
                // Transports without sing-box smux support (wireguard, hysteria2, tuic…) must
                // not get a multiplex block injected — it would fail config parsing.
                val rawType = rawObj["type"]?.jsonPrimitive?.contentOrNull
                val muxUnsupported = rawType in RAW_OUTBOUND_NO_MUX
                return buildJsonObject {
                    rawObj.forEach { (k, v) -> if (k != "tag" && k != "detour") put(k, v) }
                    put("tag", PROXY_TAG)
                    if (detourTag != null) put("detour", detourTag)
                    if (tfo && !muxUnsupported && raw.indexOf("tcp_fast_open") < 0) put("tcp_fast_open", true)
                    if (!muxUnsupported && raw.indexOf("multiplex") < 0) {
                        buildMultiplex(traffic, advanced)?.let { put("multiplex", it) }
                    }
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

            if (detourTag != null) put("detour", detourTag)
            if (tfo) put("tcp_fast_open", true)
            buildMultiplex(traffic, advanced)?.let { put("multiplex", it) }
        }
    }

    /** A raw WireGuard outbound used as a chain base (tagged [WG_BASE_TAG], no mux/detour). */
    private fun buildWireguardBaseOutbound(profile: ProxyProfile): JsonObject? {
        val raw = profile.rawOutbound?.takeIf { it.isNotBlank() } ?: return null
        val rawObj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        return buildJsonObject {
            rawObj.forEach { (k, v) -> if (k != "tag" && k != "detour") put(k, v) }
            put("tag", WG_BASE_TAG)
        }
    }

    private fun buildMultiplex(traffic: TrafficSettings, advanced: AdvancedCoreConfig?): JsonObject? {
        // Per-location advanced mux overrides the global traffic setting when present.
        if (advanced != null) {
            if (!advanced.muxEnabled) return null
            return buildJsonObject {
                put("enabled", true)
                put("protocol", advanced.muxProtocol)
                put("max_streams", advanced.muxMaxStreams)
            }
        }
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
