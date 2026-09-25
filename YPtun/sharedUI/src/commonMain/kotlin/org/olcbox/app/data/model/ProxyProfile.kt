package org.olcbox.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull

/**
 * Engine that backs the local SOCKS5 listener consumed by the TUN bridge.
 *
 * - [Stealth] uses the olcRTC core (carrier/room/key), the original olcbox behaviour.
 * - [Standard] uses sing-box with a single proxy outbound (e.g. VLESS).
 * - [Chain] runs sing-box whose outbound dials through olcRTC's SOCKS, i.e. a
 *   normal proxy wrapped inside the WebRTC stealth tunnel.
 * - [VkTurn] runs the free-turn-proxy client (a local WireGuard entry listener
 *   tunnelling through VK TURN) and sing-box with a WireGuard outbound pointed
 *   at that local listener — the panel's VK-TURN inbound consumed on the client.
 * - [MasterDns] runs the MasterDnsVPN client (github.com/masterking32/MasterDnsVPN): a DNS
 *   tunnel with a custom ARQ transport, several resolvers at once and packet duplication.
 *   It serves a local SOCKS5 whose traffic rides inside ordinary DNS queries to the
 *   MasterDnsVPN server, and the TUN bridge consumes that port directly.
 */
@Serializable
enum class EngineType {
    @SerialName("stealth")
    Stealth,

    @SerialName("standard")
    Standard,

    @SerialName("chain")
    Chain,

    @SerialName("vkturn")
    VkTurn,

    @SerialName("masterdns")
    MasterDns,

    @SerialName("openflux")
    OpenFlux;

    companion object {
        fun fromValue(value: String?): EngineType = when (value?.trim()?.lowercase()) {
            "standard", "singbox", "sing-box", "vless" -> Standard
            "chain", "stealth_chain", "stealth+vless" -> Chain
            "vkturn", "vk-turn", "freeturn" -> VkTurn
            "masterdns", "master-dns", "masterdnsvpn", "dnstt", "dns-tt", "dnstunnel" -> MasterDns
            "openflux", "open-flux" -> OpenFlux
            else -> Stealth
        }
    }
}

/**
 * Which proxy backend runs the [ProxyProfile] for Standard/Chain engines.
 * [Auto] picks Xray when the transport requires it (xhttp), otherwise sing-box.
 */
@Serializable
enum class ProxyCore {
    @SerialName("auto")
    Auto,

    @SerialName("singbox")
    SingBox,

    @SerialName("xray")
    Xray;

    companion object {
        fun fromValue(value: String?): ProxyCore = when (value?.trim()?.lowercase()) {
            "singbox", "sing-box" -> SingBox
            "xray" -> Xray
            else -> Auto
        }
    }
}

/**
 * A single proxy server parsed from a share link / subscription.
 * Currently models VLESS (the protocol the user's subscription uses); the shape is
 * deliberately close to sing-box's vless outbound so [org.olcbox.app.vpn.singbox.SingBoxConfig]
 * can map it directly.
 */
@Serializable
data class ProxyProfile(
    val tag: String = "",
    val type: String = TYPE_VLESS,
    val server: String = "",
    @SerialName("server_port")
    val serverPort: Int = 0,
    /** VLESS/VMess user id. */
    val uuid: String = "",
    /** Trojan/Shadowsocks/Naive password. */
    val password: String = "",
    /** Naive (NaïveProxy) username; empty for the other protocols. */
    val username: String = "",
    /** Shadowsocks method (cipher), e.g. "aes-128-gcm", "2022-blake3-aes-128-gcm". */
    val method: String = "",
    /** VMess alterId (0 for AEAD). */
    val alterId: Int = 0,
    /** VMess cipher: auto, aes-128-gcm, chacha20-poly1305, none. */
    val cipher: String = "auto",
    /** xtls flow, e.g. "xtls-rprx-vision"; empty = none. */
    val flow: String = "",
    /** stream network: tcp, ws, grpc, http, httpupgrade. */
    val network: String = NETWORK_TCP,
    /** none, tls, reality. */
    val security: String = SECURITY_NONE,
    val sni: String = "",
    val alpn: List<String> = emptyList(),
    /** uTLS fingerprint, e.g. "chrome". */
    val fingerprint: String = "",
    val allowInsecure: Boolean = false,
    /** REALITY public key (pbk). */
    val realityPublicKey: String = "",
    /** REALITY short id (sid). */
    val realityShortId: String = "",
    /** ws/httpupgrade path or grpc serviceName. */
    val path: String = "",
    /** ws/http Host header. */
    val host: String = "",
    /**
     * Raw sing-box outbound JSON. When set, it is used verbatim (with tag/detour injected),
     * bypassing the typed fields — the catch-all for protocols without a dedicated parser
     * (Hysteria2, TUIC, WireGuard, ShadowTLS, …).
     */
    val rawOutbound: String? = null,
    /**
     * A full raw Xray-core JSON config (dns + routing + inbounds + outbounds). When set, the app
     * runs it verbatim through xray-core (only rewriting the SOCKS inbound to the bridge port),
     * so custom dns.hosts / routing.rules / fakedns from the user's config are honored as-is.
     * Implies the Xray core. [server]/[serverPort]/[tag] are kept only for display & dedup.
     */
    @SerialName("raw_xray_config")
    val rawXrayConfig: String? = null,
    /**
     * The `route` block of a full sing-box JSON subscription, normalized at import time: every rule's
     * outbound is already rewritten to this app's own tags ("proxy"/"direct", or an `action: reject`),
     * and `rule_set` definitions come along. When set, [SingBoxConfig] uses THESE rules instead of the
     * app's routing profile/toggles — a JSON subscription's own routing takes precedence, the same way
     * [rawXrayConfig] wins on the Xray core. Null = the config shipped no routing of its own.
     */
    @SerialName("raw_singbox_route")
    val rawSingBoxRoute: String? = null,
    /**
     * AmneziaWG wg-quick INI (with the Jc/Jmin/Jmax/S1/S2/H1..H4 obfuscation knobs) for
     * [TYPE_AMNEZIAWG]. The awgproxy module raises a local SOCKS5 from it that the proxy is
     * routed through — works as a standalone outbound and as a chain hop.
     */
    @SerialName("awg_config")
    val awgConfig: String = "",
    /**
     * Trust Tunnel ([TYPE_TRUSTTUNNEL]) `tt://` deep-link for the AdGuard TrustTunnel client. The
     * vendored trusttunnel AAR decodes it (DeepLink.decode) into a `[endpoint]` TOML at connect time;
     * the client raises a local SOCKS5 (VpnClient in SOCKS-only mode) that the proxy routes through,
     * mirroring [awgConfig]/AmneziaWG. [server]/[serverPort]/[tag] are kept for display & dedup only.
     */
    @SerialName("tt_config")
    val ttConfig: String = "",
    /**
     * Hysteria2 ([TYPE_HYSTERIA2]) parameters. Auth uses [password]; server/[serverPort], [sni],
     * [alpn] and [allowInsecure] are reused. The hysteria2proxy module raises a local SOCKS5 from
     * these (like AmneziaWG). Obfs is Salamander when [hy2Obfs] == "salamander"; [hy2Ports] is an
     * optional port-hopping spec ("443,1000-2000"); up/down are bandwidth hints in Mbps (0 = auto).
     */
    @SerialName("hy2_obfs")
    val hy2Obfs: String = "",
    @SerialName("hy2_obfs_password")
    val hy2ObfsPassword: String = "",
    @SerialName("hy2_up_mbps")
    val hy2UpMbps: Int = 0,
    @SerialName("hy2_down_mbps")
    val hy2DownMbps: Int = 0,
    @SerialName("hy2_ports")
    val hy2Ports: String = "",
    /**
     * Naive ([TYPE_NAIVE]) over QUIC instead of HTTPS/H2 — from a `naive+quic://` link. Auth uses
     * [username]/[password]; TLS is mandatory (sni from the link), served natively by sing-box's
     * cronet-based naive outbound (with_naive_outbound build).
     */
    @SerialName("naive_quic")
    val naiveQuic: Boolean = false,
) {
    fun isComplete(): Boolean {
        if (type == TYPE_HYSTERIA2) return server.isNotBlank() && serverPort in 1..65535
        if (type == TYPE_NAIVE) return server.isNotBlank() && serverPort in 1..65535
        if (type == TYPE_AMNEZIAWG) return awgConfig.isNotBlank()
        if (type == TYPE_TRUSTTUNNEL) return ttConfig.isNotBlank()
        if (!rawXrayConfig.isNullOrBlank()) return true
        if (!rawOutbound.isNullOrBlank()) return true
        if (server.isBlank() || serverPort !in 1..65535) return false
        return when (type) {
            TYPE_VLESS, TYPE_VMESS -> uuid.isNotBlank()
            TYPE_TROJAN -> password.isNotBlank()
            TYPE_SHADOWSOCKS -> password.isNotBlank() && method.isNotBlank()
            else -> false
        }
    }

    fun displayName(): String = tag.ifBlank { "$server:$serverPort" }

    /**
     * Identity used for duplicate detection: blanks the display [tag] and canonicalises [awgConfig]
     * (drops `#` comment lines — where the AmneziaWG name is stored — and normalises line endings /
     * whitespace), so the same server saved under different labels compares equal. Without this, two
     * identical AmneziaWG configs that only differ by their `# Name` header were not seen as duplicates
     * (VLESS/etc. already worked because their name lives in [tag], not the structured fields).
     */
    fun dedupNormalized(): ProxyProfile = copy(
        tag = "",
        awgConfig = awgConfig
            .split('\n')
            .map { it.replace("\r", "").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .joinToString("\n")
    )

    /**
     * True when [other] points at the SAME node as this one — used to stop a user chaining their own
     * main proxy into the second/cascade slot (a proxy-into-itself, which loops/can't work). Matches on
     * any of: full dedup-equality (the same link pasted twice), an identical raw Xray/sing-box config,
     * or the same server:port (the same endpoint reached via a differently-formatted link).
     */
    fun isSameNodeAs(other: ProxyProfile): Boolean {
        if (dedupNormalized() == other.dedupNormalized()) return true
        if (!rawXrayConfig.isNullOrBlank() && rawXrayConfig == other.rawXrayConfig) return true
        if (!rawOutbound.isNullOrBlank() && rawOutbound == other.rawOutbound) return true
        if (server.isNotBlank() && serverPort in 1..65535 &&
            server.equals(other.server, ignoreCase = true) && serverPort == other.serverPort
        ) return true
        return false
    }

    /**
     * If this profile carries a raw Xray config or raw sing-box outbound (e.g. imported from a rich
     * JSON subscription or shared/pasted from JSON) but has missing credentials (blank UUID, default
     * security/network, etc.), this restores the concrete proxy parameters (uuid, flow, network,
     * security, sni, alpn, fingerprint, reality keys, path, host, password, method).
     */
    fun enrichedFromRaw(): ProxyProfile {
        var p = this
        val rawX = p.rawXrayConfig
        if (!rawX.isNullOrBlank()) {
            val fromXray = runCatching { parseFromXray(rawX) }.getOrNull()
            if (fromXray != null) {
                p = p.mergeFrom(fromXray)
            }
        }
        val rawOut = p.rawOutbound
        if (!rawOut.isNullOrBlank()) {
            val fromSb = runCatching { parseFromSingBox(rawOut) }.getOrNull()
            if (fromSb != null) {
                p = p.mergeFrom(fromSb)
            }
        }
        return p
    }

    private fun mergeFrom(other: ProxyProfile): ProxyProfile = copy(
        type = if (type.isNotBlank() && type != TYPE_VLESS) type else other.type,
        server = server.ifBlank { other.server },
        serverPort = if (serverPort in 1..65535) serverPort else other.serverPort,
        uuid = uuid.ifBlank { other.uuid },
        password = password.ifBlank { other.password },
        username = username.ifBlank { other.username },
        method = method.ifBlank { other.method },
        alterId = if (alterId != 0) alterId else other.alterId,
        cipher = if (cipher.isNotBlank() && cipher != "auto") cipher else other.cipher,
        flow = flow.ifBlank { other.flow },
        network = if (network != NETWORK_TCP) network else other.network,
        security = if (security != SECURITY_NONE) security else other.security,
        sni = sni.ifBlank { other.sni },
        alpn = alpn.ifEmpty { other.alpn },
        fingerprint = fingerprint.ifBlank { other.fingerprint },
        allowInsecure = allowInsecure || other.allowInsecure,
        realityPublicKey = realityPublicKey.ifBlank { other.realityPublicKey },
        realityShortId = realityShortId.ifBlank { other.realityShortId },
        path = path.ifBlank { other.path },
        host = host.ifBlank { other.host },
    )

    companion object {
        const val TYPE_VLESS = "vless"
        const val TYPE_VMESS = "vmess"
        const val TYPE_TROJAN = "trojan"
        const val TYPE_SHADOWSOCKS = "shadowsocks"
        const val TYPE_AMNEZIAWG = "amneziawg"
        const val TYPE_TRUSTTUNNEL = "trusttunnel"
        const val TYPE_HYSTERIA2 = "hysteria2"
        const val TYPE_NAIVE = "naive"
        const val TYPE_SOCKS = "socks"

        const val NETWORK_TCP = "tcp"
        const val NETWORK_WS = "ws"
        const val NETWORK_GRPC = "grpc"
        const val NETWORK_HTTP = "http"
        const val NETWORK_HTTPUPGRADE = "httpupgrade"

        /** Xray-only transport (xhttp/splithttp). Not supported by the sing-box core. */
        const val NETWORK_XHTTP = "xhttp"

        const val SECURITY_NONE = "none"
        const val SECURITY_TLS = "tls"
        const val SECURITY_REALITY = "reality"

        fun parseFromXray(jsonText: String): ProxyProfile? {
            val elem = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(jsonText.trim())
            }.getOrNull() ?: return null
            val root = when (elem) {
                is kotlinx.serialization.json.JsonArray -> elem.firstOrNull() as? kotlinx.serialization.json.JsonObject ?: return null
                is kotlinx.serialization.json.JsonObject -> elem
                else -> return null
            }
            val outbounds = (root["outbounds"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? kotlinx.serialization.json.JsonObject }
            val proxyOutbound = outbounds?.firstOrNull {
                val proto = (it["protocol"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.lowercase()
                proto in setOf("vless", "vmess", "trojan", "shadowsocks") ||
                    (it["tag"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull == "proxy"
            } ?: outbounds?.firstOrNull() ?: root

            val protocol = (proxyOutbound["protocol"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.lowercase() ?: TYPE_VLESS
            val settings = proxyOutbound["settings"] as? kotlinx.serialization.json.JsonObject
            val stream = proxyOutbound["streamSettings"] as? kotlinx.serialization.json.JsonObject

            val xrayNet = (stream?.get("network") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.lowercase() ?: "tcp"
            val network = when (xrayNet) {
                "ws", "websocket" -> NETWORK_WS
                "grpc", "gun" -> NETWORK_GRPC
                "h2", "http" -> NETWORK_HTTP
                "httpupgrade" -> NETWORK_HTTPUPGRADE
                "xhttp", "splithttp" -> NETWORK_XHTTP
                else -> NETWORK_TCP
            }

            val security = when ((stream?.get("security") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.lowercase()) {
                "reality" -> SECURITY_REALITY
                "tls", "xtls" -> SECURITY_TLS
                else -> SECURITY_NONE
            }

            val vnextUser = (settings?.get("vnext") as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.let { it as? kotlinx.serialization.json.JsonObject }
                ?.get("users")?.let { it as? kotlinx.serialization.json.JsonArray }?.firstOrNull()?.let { it as? kotlinx.serialization.json.JsonObject }
            val vnextServer = (settings?.get("vnext") as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.let { it as? kotlinx.serialization.json.JsonObject }
            val ssServer = (settings?.get("servers") as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.let { it as? kotlinx.serialization.json.JsonObject }

            val server = (vnextServer?.get("address") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                ?: (ssServer?.get("address") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                ?: ""
            val port = (vnextServer?.get("port") as? kotlinx.serialization.json.JsonPrimitive)?.let { runCatching { it.content.toInt() }.getOrNull() }
                ?: (ssServer?.get("port") as? kotlinx.serialization.json.JsonPrimitive)?.let { runCatching { it.content.toInt() }.getOrNull() }
                ?: 0

            val tls = stream?.get("realitySettings") as? kotlinx.serialization.json.JsonObject
                ?: stream?.get("tlsSettings") as? kotlinx.serialization.json.JsonObject
            val sni = (tls?.get("serverName") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: ""
            val fingerprint = (tls?.get("fingerprint") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: ""
            val alpn = (tls?.get("alpn") as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull } ?: emptyList()
            val allowInsecure = (tls?.get("allowInsecure") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull == "true"
            val realityPbk = (tls?.get("publicKey") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: ""
            val realityShortId = (tls?.get("shortId") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: ""

            val wsLike = stream?.get("wsSettings") as? kotlinx.serialization.json.JsonObject
                ?: stream?.get("httpupgradeSettings") as? kotlinx.serialization.json.JsonObject
            val grpc = stream?.get("grpcSettings") as? kotlinx.serialization.json.JsonObject
            val xhttp = stream?.get("xhttpSettings") as? kotlinx.serialization.json.JsonObject
                ?: stream?.get("splithttpSettings") as? kotlinx.serialization.json.JsonObject

            val path = when (network) {
                NETWORK_GRPC -> (grpc?.get("serviceName") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: ""
                NETWORK_XHTTP -> (xhttp?.get("path") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: ""
                else -> (wsLike?.get("path") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: ""
            }
            val host = when (network) {
                NETWORK_XHTTP -> (xhttp?.get("host") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: ""
                else -> (wsLike?.get("host") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                    ?: (wsLike?.get("headers") as? kotlinx.serialization.json.JsonObject)?.get("Host")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
                    ?: ""
            }

            val uuid = (vnextUser?.get("id") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                ?: (vnextUser?.get("uuid") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                ?: ""
            val flow = (vnextUser?.get("flow") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: ""
            val alterId = (vnextUser?.get("alterId") as? kotlinx.serialization.json.JsonPrimitive)?.let { runCatching { it.content.toInt() }.getOrNull() } ?: 0
            val cipher = (vnextUser?.get("security") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: "auto"
            val pass = (ssServer?.get("password") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: ""
            val method = (ssServer?.get("method") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: ""

            return ProxyProfile(
                tag = (proxyOutbound["tag"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: "",
                type = protocol,
                server = server,
                serverPort = port,
                uuid = uuid,
                password = pass,
                method = method,
                alterId = alterId,
                cipher = cipher,
                flow = flow,
                network = network,
                security = security,
                sni = sni,
                alpn = alpn,
                fingerprint = fingerprint,
                allowInsecure = allowInsecure,
                realityPublicKey = realityPbk,
                realityShortId = realityShortId,
                path = path,
                host = host,
                rawXrayConfig = if (root.containsKey("outbounds")) jsonText else null
            )
        }

        fun parseFromSingBox(jsonText: String): ProxyProfile? {
            val root = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(jsonText.trim()) as? kotlinx.serialization.json.JsonObject }.getOrNull() ?: return null
            val server = (root["server"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: return null
            val port = (root["server_port"] as? kotlinx.serialization.json.JsonPrimitive)?.let { runCatching { it.content.toInt() }.getOrNull() } ?: return null
            val type = (root["type"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: TYPE_VLESS
            val uuid = (root["uuid"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()
            val password = (root["password"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()
            val flow = (root["flow"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()
            val network = when ((root["network"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.lowercase()) {
                "ws" -> NETWORK_WS
                "grpc" -> NETWORK_GRPC
                "http", "h2" -> NETWORK_HTTP
                "httpupgrade" -> NETWORK_HTTPUPGRADE
                "xhttp", "splithttp" -> NETWORK_XHTTP
                else -> NETWORK_TCP
            }
            val tls = root["tls"] as? kotlinx.serialization.json.JsonObject
            val security = when {
                tls?.get("reality") != null -> SECURITY_REALITY
                (tls?.get("enabled") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull == "true" ||
                    (root["security"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.lowercase() == "tls" -> SECURITY_TLS
                else -> SECURITY_NONE
            }
            val reality = tls?.get("reality") as? kotlinx.serialization.json.JsonObject
            val sni = (tls?.get("server_name") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                ?: (root["sni"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                ?: ""
            val realityPbk = (reality?.get("public_key") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()
            val realityShortId = (reality?.get("short_id") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()

            return ProxyProfile(
                tag = (root["tag"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: server,
                type = type,
                server = server,
                serverPort = port,
                uuid = uuid,
                password = password,
                flow = flow,
                network = network,
                security = security,
                sni = sni,
                realityPublicKey = realityPbk,
                realityShortId = realityShortId,
                rawOutbound = jsonText
            )
        }
    }
}
