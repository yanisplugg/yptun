package org.olcbox.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    VkTurn;

    companion object {
        fun fromValue(value: String?): EngineType = when (value?.trim()?.lowercase()) {
            "standard", "singbox", "sing-box", "vless" -> Standard
            "chain", "stealth_chain", "stealth+vless" -> Chain
            "vkturn", "vk-turn", "freeturn" -> VkTurn
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
    /** Trojan/Shadowsocks password. */
    val password: String = "",
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
     * AmneziaWG wg-quick INI (with the Jc/Jmin/Jmax/S1/S2/H1..H4 obfuscation knobs) for
     * [TYPE_AMNEZIAWG]. The awgproxy module raises a local SOCKS5 from it that the proxy is
     * routed through — works as a standalone outbound and as a chain hop.
     */
    @SerialName("awg_config")
    val awgConfig: String = "",
) {
    fun isComplete(): Boolean {
        if (type == TYPE_AMNEZIAWG) return awgConfig.isNotBlank()
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

    companion object {
        const val TYPE_VLESS = "vless"
        const val TYPE_VMESS = "vmess"
        const val TYPE_TROJAN = "trojan"
        const val TYPE_SHADOWSOCKS = "shadowsocks"
        const val TYPE_AMNEZIAWG = "amneziawg"

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
    }
}
