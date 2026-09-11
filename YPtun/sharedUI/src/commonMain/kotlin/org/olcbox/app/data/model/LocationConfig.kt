package org.olcbox.app.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * VK-TURN (freeturn) transport parameters for [EngineType.VkTurn]. [uri] is the
 * full freeturn:// share link issued by the panel (carries transport/obf params,
 * the WG config is extracted out of it into ProxyProfile.rawOutbound); [vkLink]
 * is the per-client VK Calls join link the user pastes; [listenPort] is the local
 * port the freeturn client raises and the WG Endpoint dials.
 */
@Serializable
data class VkTurnConfig(
    val uri: String = "",
    @SerialName("vk_link")
    val vkLink: String = "",
    @SerialName("listen_port")
    val listenPort: Int = LocationConfig.DEFAULT_FREETURN_PORT,
    /** Parallel TURN relay streams (freeturn -n); 0 keeps the client default (10). */
    val streams: Int = 0,
    /**
     * Optional proxy share link (vless/vmess/trojan/ss) chained ON TOP of the WG-over-VK tunnel:
     * the proxy server is dialed THROUGH WireGuard (sing-box detour). Blank = plain WG only.
     */
    @SerialName("chain_proxy_link")
    val chainProxyLink: String = "",
    /**
     * What rides the VK tunnel and exits to the internet:
     * - [OUTBOUND_WIREGUARD] / [OUTBOUND_AMNEZIAWG]: a UDP WireGuard(-like) tunnel whose Endpoint is
     *   the local freeturn listener — requires the freeturn payload mode to be `udp` (udprelay).
     * - [OUTBOUND_PROXY]: a TCP proxy (vless/vmess/trojan/ss) whose server is dialled THROUGH the
     *   local freeturn TCP listener — requires the freeturn payload mode to be `tcp` (tcpfwd).
     * The concrete outbound lives in [LocationConfig.proxy] (WG/proxy → rawOutbound, AWG → awgConfig).
     */
    @SerialName("outbound")
    val outbound: String = OUTBOUND_WIREGUARD,
    /** Verbatim exit-proxy share link kept for editing when [outbound] == [OUTBOUND_PROXY]. */
    @SerialName("outbound_proxy_link")
    val outboundProxyLink: String = "",
    /**
     * Which core runs the exit/chain proxy (same choice as the Standard engine). [ProxyCore.Auto]
     * picks Xray for xhttp/splithttp (sing-box can't serve it over VK), otherwise sing-box.
     */
    @SerialName("proxy_core")
    val proxyCore: ProxyCore = ProxyCore.Auto,
    /**
     * Which VK-TURN transport CORE raises the local listener the WG outbound dials:
     * - [CORE_FREETURN]: the free-turn-proxy client (driven by [uri], the freeturn:// link).
     * - [CORE_WDTT]: the WDTT (wg-turn-client) core — chunk-affinity dispatch across VK call-links so a
     *   single WG flow actually aggregates. Driven by [wdttPeer]/[wdttPassword]/[vkLink] (call links =
     *   the VK hashes); the wg= keys still come from [LocationConfig.proxy] (or the server's GETCONF).
     */
    @SerialName("core")
    val core: String = CORE_FREETURN,
    /** WDTT server IP (or host) the core dials over VK TURN (the wdtt-server / Peer). Port = [wdttPort]. */
    @SerialName("wdtt_peer")
    val wdttPeer: String = "",
    /** WDTT server port; 0 → [DEFAULT_WDTT_PORT] (56000). Ignored if [wdttPeer] already carries ":port". */
    @SerialName("wdtt_port")
    val wdttPort: Int = 0,
    /** WDTT connection password — the WRAP key is HKDF-derived from it server-side and client-side. */
    @SerialName("wdtt_password")
    val wdttPassword: String = "",
    /** Unused since qWDTT (it always uses its Chrome fingerprint); kept so stored locations load. */
    @SerialName("wdtt_fingerprint")
    val wdttFingerprint: String = "",
    /** WDTT worker count; 0 → core default. Clamped to [9,108] and rounded to a multiple of 9 in-core. */
    @SerialName("wdtt_workers")
    val wdttWorkers: Int = 0,
    /** The qWDTT core's advanced knobs (TURN over TCP, camouflage, DNS for VK). */
    @SerialName("wdtt_plus")
    val wdttPlus: WdttPlusOptions = WdttPlusOptions(),
    /**
     * Master switch for multi-server freeturn. When off, only the primary [uri] is used (today's exact
     * single-server behaviour) even if [extraFreeturnUris] is non-empty. When on, the extra servers
     * run alongside the primary and traffic is load-balanced across them.
     */
    @SerialName("freeturn_multi_server")
    val freeturnMultiServer: Boolean = false,
    /**
     * Additional freeturn:// servers (beyond the primary [uri]) to run AT THE SAME TIME for
     * per-connection load-balancing — the servers' bandwidth aggregates. Each link is a full
     * freeturn:// (carries its own peer/obf/embedded wg=). The location's VK call links are PARTITIONED
     * across the servers (so each VPS handles a share, not all of them). Up to 5 extra (6 total). Only
     * honoured for the freeturn core with a WireGuard exit (not WDTT / AmneziaWG / proxy exits).
     */
    @SerialName("extra_freeturn_uris")
    val extraFreeturnUris: List<String> = emptyList(),
) {
    /**
     * All freeturn servers to front at once: the primary [uri] first, then the valid [extraFreeturnUris]
     * — but ONLY when [freeturnMultiServer] is on. Off ⇒ just the primary, so the single-server path
     * stays byte-identical.
     */
    fun allFreeturnUris(): List<String> {
        val primary = listOf(uri)
        val all = if (freeturnMultiServer) primary + extraFreeturnUris else primary
        return all.map { it.trim() }.filter { it.startsWith("freeturn://", ignoreCase = true) }
    }
    /** True when the WDTT transport core is selected (vs. the default freeturn core). */
    fun usesWdtt(): Boolean = core.equals(CORE_WDTT, ignoreCase = true)

    /** The wdtt-server "host:port": uses [wdttPeer] verbatim if it already has a port, else appends
     *  [wdttPort] (or [DEFAULT_WDTT_PORT]). */
    fun wdttPeerAddr(): String {
        val host = wdttPeer.trim()
        if (host.isEmpty()) return ""
        if (host.substringAfterLast(':', "").toIntOrNull() != null && host.contains(':')) return host
        val port = wdttPort.takeIf { it in 1..65535 } ?: DEFAULT_WDTT_PORT
        return "$host:$port"
    }

    /** Where the core dials: the raw port in Raw mode (same host, `-listen-raw`), else [wdttPeerAddr]. */
    fun wdttDialAddr(): String {
        if (!wdttPlus.rawMode) return wdttPeerAddr()
        val host = wdttPeerAddr().substringBeforeLast(':')
        return if (host.isEmpty()) "" else "$host:${wdttPlus.rawPortOrDefault()}"
    }

    fun isComplete(): Boolean =
        isStorable() && vkLink.isNotBlank()

    /** UDP payload (WireGuard/AmneziaWG) needs udprelay; TCP proxy needs tcpfwd. */
    fun requiredMode(): String = if (outbound == OUTBOUND_PROXY) "tcp" else "udp"

    /**
     * Resolves [proxyCore]==Auto to a concrete backend for the given exit/chain [profile], honoring
     * the app-wide [globalCore] default. Precedence mirrors [LocationConfig.resolvedCore]: an explicit
     * per-location [proxyCore] wins, then a raw-Xray/xhttp transport forces Xray, then the global
     * default, then sing-box. [globalCore]==Auto keeps the original behaviour.
     */
    fun resolvedProxyCore(profile: ProxyProfile?, globalCore: ProxyCore = ProxyCore.Auto): ProxyCore = when {
        proxyCore != ProxyCore.Auto -> proxyCore
        !profile?.rawXrayConfig.isNullOrBlank() -> ProxyCore.Xray
        profile?.network == ProxyProfile.NETWORK_XHTTP -> ProxyCore.Xray
        globalCore != ProxyCore.Auto -> globalCore
        else -> ProxyCore.SingBox
    }

    companion object {
        const val OUTBOUND_WIREGUARD = "wireguard"
        const val OUTBOUND_AMNEZIAWG = "amneziawg"
        const val OUTBOUND_PROXY = "proxy"

        const val CORE_FREETURN = "freeturn"
        const val CORE_WDTT = "wdtt"

        /** Default WDTT server port (matches the wdtt-server default). */
        const val DEFAULT_WDTT_PORT = 56000
    }

    /**
     * True when the transport core's required artefacts + WG transport are present. The per-client
     * [vkLink] is filled in by the user via the location settings after import, so a location is
     * storable (and shown in the list) before [isComplete] is satisfied. For the WDTT core there is no
     * freeturn:// link — the [wdttPeer] (wdtt-server addr) is the gating artefact instead.
     */
    fun isStorable(): Boolean = listenPort in 1..65535 && when {
        usesWdtt() -> wdttPeer.isNotBlank()
        else -> uri.startsWith("freeturn://")
    }

    /**
     * The qWDTT core options (wdttmobile.Options JSON) for this location — ONE builder for both the
     * Android gomobile binding and the desktop/iOS core. [listen] is the local UDP address WireGuard dials
     * — or, in Raw mode, the local SOCKS5 (TCP) the core serves the tunnel on.
     */
    fun wdttCoreOptionsJson(listen: String, deviceId: String): String {
        val p = wdttPlus
        return buildJsonObject {
            put("peer", wdttDialAddr())
            put("raw", p.rawMode)
            put("vk_hashes", vkLink)
            put("password", wdttPassword)
            put("listen", listen)
            put("workers", wdttWorkers)
            put("device_id", deviceId)
            put("captcha_mode", "auto")
            put("turn_host", p.turnHost.trim())
            put("turn_port", p.turnPort.trim())
            put("turn_tcp", p.rtNetworkMode)
            put("obfs", if (p.obfsVideo) "video" else "audio")
            put("go_dns", p.goDns.trim())
            put("vk_anon_path", if (p.vkAnonLegacy) "legacy" else "vkcalls")
        }.toString()
    }
}

/**
 * Advanced options of the qWDTT VK-TURN core (github.com/SpaceNeuroX/proxy-turn-vk-android). The name is
 * left over from the WDTT Plus core it replaced, so stored locations keep loading (its old fields are
 * simply ignored). Defaults reproduce qWDTT's own defaults.
 */
@Serializable
data class WdttPlusOptions(
    /**
     * TURN relay over TCP instead of UDP — for networks that throttle or cut UDP to VK (Rostelecom and
     * the like). Same stored key as WDTT Plus's «Сеть РТ», which served the same purpose.
     */
    @SerialName("rt_network_mode")
    val rtNetworkMode: Boolean = false,
    /** RTP camouflage as a video stream instead of audio. */
    @SerialName("obfs_video")
    val obfsVideo: Boolean = false,
    /** DNS the core resolves VK with: yandex/cloudflare/google, doh-*, custom:IP, doh:URL. Blank = yandex. */
    @SerialName("go_dns")
    val goDns: String = "",
    /** The older anonymous TURN credential path (`legacy`) instead of VK Calls. */
    @SerialName("vk_anon_legacy")
    val vkAnonLegacy: Boolean = false,
    /** TURN server IP / port override (blank = the ones VK hands out). */
    @SerialName("turn_host")
    val turnHost: String = "",
    @SerialName("turn_port")
    val turnPort: String = "",
    /**
     * qWDTT 1.4 «Raw»: raw IP packets without WireGuard — faster, but the server must run `-listen-raw`
     * (the auto-install enables it on [rawPort]). Off = the WireGuard mode, compatible with every server.
     */
    @SerialName("raw_mode")
    val rawMode: Boolean = false,
    /** The server's raw port; 0 → [DEFAULT_RAW_PORT] (qWDTT's own default). */
    @SerialName("raw_port")
    val rawPort: Int = 0,
) {
    fun rawPortOrDefault(): Int = rawPort.takeIf { it in 1..65535 } ?: DEFAULT_RAW_PORT

    companion object {
        const val DEFAULT_RAW_PORT = 56003
    }
}

/**
 * Advanced per-location options for the sing-box / Xray proxy core (shown in the editor only when a
 * specific core is chosen, not Auto). Mux multiplexes many streams over one connection; TCP Fast
 * Open, destination sniffing and TLS record fragmentation are anti-DPI / performance knobs.
 */
@Serializable
data class AdvancedCoreConfig(
    @SerialName("mux_enabled") val muxEnabled: Boolean = false,
    /** sing-box: smux | yamux | h2mux; Xray ignores the value (single mux). */
    @SerialName("mux_protocol") val muxProtocol: String = "h2mux",
    @SerialName("mux_max_streams") val muxMaxStreams: Int = 8,
    @SerialName("tcp_fast_open") val tcpFastOpen: Boolean = false,
    @SerialName("sniff") val sniff: Boolean = true,
    @SerialName("tls_fragment") val tlsFragment: Boolean = false,
)

/**
 * FakeDNS plumbing extracted from an imported Xray config so the sing-box core can reproduce it
 * natively (sing-box `dns.fakeip`). Handing out synthetic IPs from [inet4Range]/[inet6Range] means
 * apps never see the real address; the sniffed domain is resolved behind the proxy. [blockRegex]
 * carries the config's `dns.hosts` regex entries that mapped a domain to `0.0.0.0` (a blackhole),
 * reproduced as sing-box `domain_regex → reject` route rules.
 */
@Serializable
data class FakeDnsSpec(
    @SerialName("inet4_range") val inet4Range: String = "198.18.0.0/15",
    @SerialName("inet6_range") val inet6Range: String = "fc00::/18",
    @SerialName("block_regex") val blockRegex: List<String> = emptyList(),
)

/**
 * MasterDNS (DNS tunnel) transport parameters for [EngineType.MasterDns]. The client carries TCP
 * inside ordinary DNS queries to the MasterDnsVPN server and serves the result as a local SOCKS5,
 * which the TUN bridge consumes directly. Custom ARQ transport, multi-resolver with duplication and
 * per-resolver health checks — see github.com/masterking32/MasterDnsVPN.
 */
@Serializable
data class MasterDnsConfig(
    /**
     * Tunnel domain(s) delegated to the MasterDnsVPN server, e.g. `v.example.com`. Comma or newline
     * separated; every one of them must belong to the same server.
     */
    @SerialName("domains")
    val domains: String = "",
    /** Shared secret — the contents of the server's `encrypt_key.txt`. Must match exactly. */
    @SerialName("key")
    val encryptionKey: String = "",
    /**
     * Payload cipher, must match the server's DATA_ENCRYPTION_METHOD:
     * 0=none, 1=XOR, 2=ChaCha20, 3=AES-128-GCM, 4=AES-192-GCM, 5=AES-256-GCM.
     */
    @SerialName("encryption")
    val encryptionMethod: Int = DEFAULT_ENCRYPTION_METHOD,
    /**
     * DNS resolvers that reach the tunnel domain, comma or newline separated, each `ip` or `ip:port`
     * (53 when omitted). Several resolvers are the point: the client spreads traffic over all of them
     * and drops the ones that stop answering. In direct mode this is simply the VPS itself.
     */
    @SerialName("resolvers")
    val resolvers: String = "",
    /** How resolvers are picked (RESOLVER_BALANCING_STRATEGY 0..8); 0 = upstream default. */
    @SerialName("balancing")
    val balancingStrategy: Int = 0,
    /** Copies of each outgoing packet — more survives a lossy link at the cost of traffic; 0 = default. */
    @SerialName("duplication")
    val packetDuplication: Int = 0,
    /**
     * Optional proxy share link (vless/vmess/trojan/ss) chained ON TOP of the tunnel: the proxy server
     * is dialed THROUGH the local MasterDNS SOCKS, so the public exit is the proxy, not the
     * MasterDnsVPN server. Blank = exit straight through the server itself.
     */
    @SerialName("proxy_link")
    val proxyLink: String = "",
    /**
     * Which core runs the over-tunnel proxy (same choice as the Standard engine). [ProxyCore.Auto]
     * picks Xray for raw-Xray/xhttp transports, otherwise sing-box.
     */
    @SerialName("proxy_core")
    val proxyCore: ProxyCore = ProxyCore.Auto,
) {
    /** True when the tunnel has everything it needs to connect. */
    fun isComplete(): Boolean =
        domainList().isNotEmpty() && encryptionKey.isNotBlank() && resolverList().isNotEmpty()

    /** True when a proxy is chained on top of the tunnel. */
    fun hasProxy(): Boolean = proxyLink.isNotBlank()

    /** [domains] split into individual entries. */
    fun domainList(): List<String> = splitEntries(domains)

    /** [resolvers] split into individual entries. */
    fun resolverList(): List<String> = splitEntries(resolvers)

    /** Resolver host parts only — what the desktop TUN must route around the tunnel. */
    fun resolverHosts(): List<String> = resolverList().map { entry ->
        // "1.2.3.4", "1.2.3.4:5300", "[2001:db8::1]:53" — the bracketed IPv6 form is the only one
        // where a colon is not the port separator.
        when {
            entry.startsWith("[") -> entry.substringAfter('[').substringBefore(']')
            entry.count { it == ':' } == 1 -> entry.substringBefore(':')
            else -> entry
        }
    }.filter { it.isNotBlank() }

    /** Resolves [proxyCore]==Auto to a concrete backend for the over-tunnel [profile]. Mirrors
     *  [VkTurnConfig.resolvedProxyCore], but defaults to Xray: chaining the exit over the tunnel's
     *  SOCKS needs socket-level dialerProxy chaining (Xray) to keep a vless reality/xtls-vision
     *  transport intact — other paths reset it. An explicit per-location or global core still wins. */
    fun resolvedProxyCore(profile: ProxyProfile?, globalCore: ProxyCore = ProxyCore.Auto): ProxyCore =
        overTunnelProxyCore(proxyCore, profile, globalCore)

    fun normalized(): MasterDnsConfig = MasterDnsConfig(
        domains = domainList().joinToString(","),
        encryptionKey = encryptionKey.trim(),
        encryptionMethod = encryptionMethod.coerceIn(0, 5),
        resolvers = resolverList().joinToString(","),
        balancingStrategy = balancingStrategy.coerceIn(0, 8),
        packetDuplication = packetDuplication.coerceIn(0, 10),
        proxyLink = proxyLink.trim(),
        proxyCore = proxyCore,
    )

    companion object {
        /** XOR — the upstream default: cheapest, and the auto-installed server is set to match. */
        const val DEFAULT_ENCRYPTION_METHOD = 1

        /** Human labels for [encryptionMethod], indexed by value. */
        val ENCRYPTION_LABELS = listOf(
            "Без шифрования", "XOR", "ChaCha20", "AES-128-GCM", "AES-192-GCM", "AES-256-GCM"
        )

        private fun splitEntries(value: String): List<String> =
            value.split(SEPARATORS)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()

        /** Comma, semicolon or any whitespace all read as "next entry" in the list fields. */
        private val SEPARATORS = Regex("[,;\\s]+")
    }
}

/**
 * Core for a proxy chained OVER a tunnel's local SOCKS (MasterDNS, OpenFlux). Like
 * [VkTurnConfig.resolvedProxyCore], but Auto means Xray: chaining through the tunnel's SOCKS needs
 * socket-level dialerProxy chaining to keep a vless reality/xtls-vision transport intact — other paths
 * reset it. An explicit per-location or global core still wins.
 */
internal fun overTunnelProxyCore(chosen: ProxyCore, profile: ProxyProfile?, globalCore: ProxyCore): ProxyCore = when {
    chosen != ProxyCore.Auto -> chosen
    !profile?.rawXrayConfig.isNullOrBlank() -> ProxyCore.Xray
    profile?.network == ProxyProfile.NETWORK_XHTTP -> ProxyCore.Xray
    globalCore != ProxyCore.Auto -> globalCore
    else -> ProxyCore.Xray
}

/**
 * OpenFlux (github.com/p1neappleXpress/OpenFlux) transport for [EngineType.OpenFlux]: a TCP tunnel that
 * carries IP packets through a carrier service to the user's own exit node on a VPS. The client serves a
 * local SOCKS5 the TUN bridge consumes. Two carriers:
 * - [TRANSPORT_YANDEX]: cursor messages of a Yandex Docs document ([docUrl], the legacy editor), shared
 *   by the client and the exit node;
 * - [TRANSPORT_VYANDEX]: the same over the NEW Yandex Docs editor (Volga, volga.yandex.ru relay);
 * - [TRANSPORT_MAX]: a WebRTC DataChannel of a MAX call — the client logs in with [maxToken] and calls
 *   the exit node's account [maxUid] (the exit node runs with ITS OWN MAX token).
 */
@Serializable
data class OpenFluxConfig(
    @SerialName("transport")
    val transport: String = TRANSPORT_YANDEX,
    /** Yandex Docs document URL (legacy editor), the same one the exit node uses. */
    @SerialName("doc_url")
    val docUrl: String = "",
    /** The CLIENT's MAX web token ([TRANSPORT_MAX]). */
    @SerialName("max_token")
    val maxToken: String = "",
    /** MAX user id of the EXIT NODE's account — the client calls it ([TRANSPORT_MAX]). */
    @SerialName("max_uid")
    val maxUid: String = "",
    /**
     * DNS server reached THROUGH the tunnel for domain lookups (`ip:port`). The device DNS would leak
     * every name to the ISP and, for blocked sites, answer with spoofed addresses. Blank = device DNS.
     */
    @SerialName("dns")
    val dnsServer: String = DEFAULT_DNS,
    /** Verbose client log (upstream `--debug`): every SOCKS CONNECT and transport event. */
    @SerialName("debug")
    val debug: Boolean = false,
    /**
     * Optional proxy share link (vless/vmess/trojan/ss) chained ON TOP of the tunnel: dialled THROUGH the
     * OpenFlux SOCKS, so the public exit is the proxy — and the traffic is encrypted end to end, which
     * OpenFlux alone doesn't do. Blank = exit straight through the exit node.
     */
    @SerialName("proxy_link")
    val proxyLink: String = "",
    @SerialName("proxy_core")
    val proxyCore: ProxyCore = ProxyCore.Auto,
) {
    fun usesMax(): Boolean = transport == TRANSPORT_MAX

    fun hasProxy(): Boolean = proxyLink.isNotBlank()

    fun resolvedProxyCore(profile: ProxyProfile?, globalCore: ProxyCore = ProxyCore.Auto): ProxyCore =
        overTunnelProxyCore(proxyCore, profile, globalCore)

    fun isComplete(): Boolean = when (transport) {
        TRANSPORT_MAX -> maxToken.isNotBlank() && maxUid.trim().toLongOrNull() != null
        else -> docUrl.trim().startsWith("http", ignoreCase = true)
    }

    fun normalized(): OpenFluxConfig = copy(
        transport = transport.takeIf { it in TRANSPORTS } ?: TRANSPORT_YANDEX,
        docUrl = docUrl.trim(),
        maxToken = maxToken.trim(),
        maxUid = maxUid.trim(),
        dnsServer = dnsServer.trim(),
        proxyLink = proxyLink.trim(),
    )

    /** One-line summary for the location list. */
    fun summary(): String = when (transport) {
        TRANSPORT_MAX -> "MAX · звонок $maxUid"
        TRANSPORT_VYANDEX -> "Яндекс Документы (новый редактор)"
        else -> "Яндекс Документы"
    }

    companion object {
        const val TRANSPORT_YANDEX = "yandex"
        /** Upstream's "vyandex": Yandex Docs in the new Volga editor. */
        const val TRANSPORT_VYANDEX = "vyandex"
        /** Upstream calls the MAX transport "oneme". */
        const val TRANSPORT_MAX = "oneme"
        val TRANSPORTS = listOf(TRANSPORT_YANDEX, TRANSPORT_VYANDEX, TRANSPORT_MAX)
        const val DEFAULT_DNS = "1.1.1.1:53"
    }
}

/** One additional olcRTC room for the multi-room (aggregation) feature. */
@Serializable
data class ExtraRoom(
    @SerialName("provider") val provider: String = "",
    @SerialName("transport") val transport: String = "",
    @SerialName("room") val room: String = "",
    @SerialName("key") val key: String = "",
)

@Serializable
data class LocationConfig(
    val name: String = "",
    /**
     * Optional human-readable description shown under the location name. Populated from a subscription
     * when the source carries one (Happ-style configs put it in `meta.serverDescription`); blank when
     * the source has none. Display-only — does not affect routing.
     */
    val description: String = "",
    val id: String = "",
    val key: String = "",
    @SerialName("bypass_provider")
    val bypassProvider: String = DEFAULT_BYPASS_PROVIDER,
    val transport: String = DEFAULT_TRANSPORT,
    @SerialName("vp8_fps")
    val vp8Fps: Int = DEFAULT_VP8_FPS,
    @SerialName("vp8_batch")
    val vp8Batch: Int = DEFAULT_VP8_BATCH,
    /**
     * Transport parameters exactly as the olcRTC URI carries them in `<key=value&…>` (docs/uri.md):
     * seichannel `fps`/`batch`/`frag`/`ack-ms`, videochannel `video-w`/`video-h`/`video-fps`/
     * `video-codec`/`video-qr-size`/`video-qr-recovery`/`video-tile-module`/`video-tile-rs`. Kept raw so a
     * share link round-trips; the cores read them through [seiOptions] / [videoOptions].
     */
    @SerialName("transport_options")
    val transportOptions: Map<String, String> = emptyMap(),
    /** Which core serves the local SOCKS5: olcRTC (Stealth), sing-box (Standard) or both (Chain). */
    val engine: EngineType = EngineType.Stealth,
    /** Main proxy server for the sing-box engine (Standard/Chain) — the primary outbound, ALWAYS
     *  applied. Null for pure Stealth. For Chain it rides inside the olcRTC tunnel. */
    val proxy: ProxyProfile? = null,
    /**
     * Optional SECOND proxy chained ON TOP of [proxy] (a cascade): traffic exits via this proxy, which
     * dials through the main one — client → [olcRTC] → main → second → internet. Null = single hop
     * (main only). Toggled in the editor; cleared when the toggle is off.
     */
    @SerialName("proxy2")
    val proxy2: ProxyProfile? = null,
    /**
     * Deprecated/vestigial. Previously gated whether [proxy] was applied (off = direct). The main proxy
     * is now ALWAYS applied; the editor toggle controls [proxy2] instead. Kept only for back-compat
     * parsing of older saved locations.
     */
    @SerialName("proxy_enabled")
    val proxyEnabled: Boolean = true,
    /** Proxy backend for Standard/Chain: Auto, sing-box or Xray. */
    val core: ProxyCore = ProxyCore.Auto,
    /**
     * VK-TURN transport for the [EngineType.VkTurn] engine. Holds the freeturn://
     * share link and the per-client VK call link; the WireGuard outbound carried
     * inside the link lives in [proxy].rawOutbound. Null for other engines.
     */
    val vkturn: VkTurnConfig? = null,
    /** MasterDNS (DNS tunnel) transport for the [EngineType.MasterDns] engine. Null for other engines. */
    @SerialName("masterdns")
    val masterDns: MasterDnsConfig? = null,
    /** OpenFlux transport for the [EngineType.OpenFlux] engine. Null for other engines. */
    @SerialName("openflux")
    val openFlux: OpenFluxConfig? = null,
    /** Per-location advanced core options, surfaced only when [core] is not Auto. Null = defaults. */
    val advanced: AdvancedCoreConfig? = null,
    /**
     * Routing profile applied to this location: a [RoutingProfile.id]; blank = use the global profile;
     * [RoutingProfile.NONE_ID] = explicitly no profile. Resolved by [RoutingProfilesState.resolve].
     */
    @SerialName("routing_profile_id")
    val routingProfileId: String = "",
    /**
     * FakeDNS spec translated from an imported Xray config (fakeip pool + dns.hosts blackholes). When
     * non-null, the sing-box core enables native fakeip with these ranges and reject rules, so FakeDNS
     * works on sing-box too — not only on xray-core. Null = no FakeDNS for this location.
     */
    @SerialName("fake_dns")
    val fakeDns: FakeDnsSpec? = null,
    /**
     * Multi-room (Stealth/Chain): when true and [extraRooms] is non-empty, the client raises the main
     * room PLUS each extra room as an independent olcRTC instance and round-robins connections across
     * them so bandwidth aggregates (up to [MAX_EXTRA_ROOMS]+1 rooms total).
     */
    @SerialName("multi_room")
    val multiRoomEnabled: Boolean = false,
    /** Additional olcRTC rooms raised alongside the main one when [multiRoomEnabled]. */
    @SerialName("extra_rooms")
    val extraRooms: List<ExtraRoom> = emptyList(),
    /**
     * Stage-2 bonding (Chain only): when true, the Chain→VLESS flow is BONDED across the rooms (one
     * stream striped over all room lanes and reassembled in order on the server) instead of round-robined
     * per-connection. This lets a SINGLE flow aggregate bandwidth ("many→single→vless"). Requires the
     * bond reassembler running on the olcRTC server at [bondPort]. Ignored for Stealth (per-connection
     * round-robin already aggregates parallel app flows there).
     */
    @SerialName("multi_room_bond")
    val multiRoomBond: Boolean = false,
    /** Server-side bond reassembler port the rooms' SOCKS dials (127.0.0.1:bondPort); 0 → [DEFAULT_BOND_PORT]. */
    @SerialName("bond_port")
    val bondPort: Int = 0,
) {
    /** Effective list of rooms to raise for multi-room: the main room + the [extraRooms] (capped). */
    fun multiRoomSpecs(): List<ExtraRoom> = buildList {
        add(ExtraRoom(provider = bypassProvider, transport = transport, room = id, key = key))
        extraRooms.forEach { add(it) }
    }.filter { it.room.isNotBlank() && it.key.isNotBlank() }.take(MAX_EXTRA_ROOMS + 1)

    /** True when multi-room is on AND there's at least one valid EXTRA room (so it's worth fanning out). */
    fun usesMultiRoom(): Boolean =
        multiRoomEnabled && extraRooms.any { it.room.isNotBlank() && it.key.isNotBlank() }

    /** True when the Chain flow should be BONDED across the rooms (Stage-2). Bond needs multiple rooms. */
    fun usesMultiRoomBond(): Boolean = multiRoomBond && usesMultiRoom()

    /** Effective server bond reassembler port. */
    fun effectiveBondPort(): Int = bondPort.takeIf { it in 1..65535 } ?: DEFAULT_BOND_PORT

    fun normalized(): LocationConfig {
        val provider = normalizeProvider(bypassProvider)
        val normalizedTransport = normalizeTransport(transport, provider)
        return copy(
            name = name.trim(),
            description = description.trim(),
            id = id.trim(),
            key = key.trim(),
            bypassProvider = provider,
            transport = normalizedTransport,
            vp8Fps = sanitizeVp8Fps(vp8Fps),
            vp8Batch = sanitizeVp8Batch(vp8Batch),
            transportOptions = normalizeTransportOptions(transportOptions),
            engine = engine,
            proxy = proxy,
            proxy2 = proxy2,
            core = core,
            vkturn = vkturn,
            masterDns = masterDns?.normalized(),
            openFlux = openFlux?.normalized(),
            routingProfileId = routingProfileId.trim(),
            fakeDns = fakeDns,
        )
    }

    /**
     * True when the imported config can ONLY be served by xray-core: an xhttp/splithttp transport
     * (sing-box can't serve it over VK). FakeDNS is NOT here — it now runs on either core (sing-box via
     * its native fakeip, see [fakeDns]). When true, [resolvedCore] forces Xray and the editor blocks
     * the sing-box choice.
     */
    fun requiresXray(): Boolean = listOfNotNull(proxy, proxy2).any { p ->
        p.network == ProxyProfile.NETWORK_XHTTP
    }

    /**
     * Resolves [ProxyCore.Auto] to a concrete backend based on the proxy transport and the app-wide
     * [globalCore] default. Precedence (highest first): a raw Xray config (Xray-only) → the explicit
     * per-location [core] → a transport only Xray can serve (xhttp) → the global default → sing-box.
     * So a per-location choice always wins over the global one, which in turn wins over the sing-box
     * default. [globalCore]==Auto (the historical caller) keeps the original behaviour exactly.
     */
    fun resolvedCore(globalCore: ProxyCore = ProxyCore.Auto): ProxyCore = when {
        // A full raw Xray config can only be run by xray-core, regardless of the stored choice.
        !proxy?.rawXrayConfig.isNullOrBlank() -> ProxyCore.Xray
        // Explicit per-location override beats the global default.
        core != ProxyCore.Auto -> core
        // xhttp/splithttp can only be served by xray-core.
        proxy?.network == ProxyProfile.NETWORK_XHTTP -> ProxyCore.Xray
        // App-wide engine preference (ranks below the per-location setting).
        globalCore != ProxyCore.Auto -> globalCore
        else -> ProxyCore.SingBox
    }

    /**
     * The VK-TURN exit artifact required by the chosen [VkTurnConfig.outbound] is present. The exit
     * lives in [proxy] but in DIFFERENT fields per outbound, so a single `rawOutbound` check wrongly
     * rejected the proxy/AmneziaWG exits (rawOutbound is only set for plain WireGuard) — that made a
     * "Proxy (tcp) + bonding" VK-TURN location fail isStorable and VANISH on save.
     */
    private fun vkTurnExitPresent(): Boolean = when {
        // WDTT connects purely by the wdtt-server IP[:port]; the WireGuard config is fetched FROM the
        // server at runtime (GETCONF/OnConfig), so no stored exit artifact is required here.
        vkturn?.usesWdtt() == true -> true
        else -> vkTurnExitPresentFreeturn()
    }

    private fun vkTurnExitPresentFreeturn(): Boolean = when (vkturn?.outbound) {
        // TCP proxy exit (vless/vmess/trojan/ss) dialled through the freeturn tcp listener — a normal
        // ProxyProfile (server/uuid/…), NOT a rawOutbound blob.
        VkTurnConfig.OUTBOUND_PROXY -> proxy?.isComplete() == true
        // AmneziaWG exit keeps its wg-quick INI in awgConfig (not rawOutbound).
        VkTurnConfig.OUTBOUND_AMNEZIAWG -> !proxy?.awgConfig.isNullOrBlank()
        // Plain WireGuard exit: the sing-box outbound JSON in rawOutbound.
        else -> !proxy?.rawOutbound.isNullOrBlank()
    }

    /** True when this config has everything its [engine] needs to connect. */
    fun isComplete(): Boolean = when (engine) {
        // olcRTC needs a room id + key.
        EngineType.Stealth -> id.isNotBlank() && key.isNotBlank()
        // sing-box needs a valid main proxy server (always the primary outbound). [proxy2] is optional.
        EngineType.Standard -> proxy?.isComplete() == true
        // Chain needs the olcRTC stealth tunnel plus a valid main proxy. [proxy2] is optional.
        EngineType.Chain -> proxy?.isComplete() == true && id.isNotBlank() && key.isNotBlank()
        // VK-TURN needs the freeturn link, the per-client VK call link and the chosen exit artifact.
        EngineType.VkTurn -> vkturn?.isComplete() == true && vkTurnExitPresent()
        // MasterDNS needs the tunnel domain(s), the shared encryption key and at least one resolver.
        EngineType.MasterDns -> masterDns?.isComplete() == true
        // OpenFlux needs the carrier's coordinates: the Yandex Docs URL, or the MAX token + callee id.
        EngineType.OpenFlux -> openFlux?.isComplete() == true
    }

    /**
     * True when this config has enough to persist in the location list. Matches
     * [isComplete] for every engine except VK-TURN, where the per-client VK call
     * link is filled in after import, so the location is kept (and shown) without it.
     */
    fun isStorable(): Boolean = when (engine) {
        EngineType.VkTurn -> vkturn?.isStorable() == true && vkTurnExitPresent()
        else -> isComplete()
    }

    /**
     * Identity used to detect duplicate locations: the connection-defining fields only. The display
     * [name] AND the proxy display [ProxyProfile.tag] are blanked, because the same server is commonly
     * saved under different names/remarks (e.g. imported twice with a different label) — those are
     * still duplicates. Suitable as a map/set key — all nested types are value (data) classes, so
     * structural equality holds.
     */
    fun dedupKey(): LocationConfig {
        val n = normalized()
        return n.copy(
            name = "",
            proxy = n.proxy?.dedupNormalized(),
            proxy2 = n.proxy2?.dedupNormalized()
        )
    }

    fun displayName(): String = name.ifBlank { id }

    fun providerName(): String = providerDisplayName(bypassProvider)

    fun transportName(): String = transportDisplayName(transport)

    private fun optionInt(key: String): Int? = transportOptions[key]?.trim()?.toIntOrNull()

    /** seichannel settings: the URI's payload over the olcRTC defaults (docs/settings.md). */
    fun seiOptions(): SeiOptions = SeiOptions(
        fps = (optionInt("fps") ?: SeiOptions.DEFAULT_FPS).coerceIn(1, 120),
        batch = (optionInt("batch") ?: SeiOptions.DEFAULT_BATCH).coerceAtLeast(1),
        fragmentSize = (optionInt("frag") ?: SeiOptions.DEFAULT_FRAGMENT).coerceAtLeast(1),
        ackTimeoutMs = (optionInt("ack-ms") ?: SeiOptions.DEFAULT_ACK_MS).coerceAtLeast(1),
    )

    /** videochannel settings: the URI's payload over the olcRTC defaults (docs/settings.md). */
    fun videoOptions(): VideoOptions {
        val codec = transportOptions["video-codec"]?.trim()?.lowercase()
            ?.takeIf { it == VideoOptions.CODEC_QRCODE || it == VideoOptions.CODEC_TILE }
            ?: VideoOptions.CODEC_QRCODE
        // The tile codec only runs at exactly 1080x1080 — the core rejects anything else.
        val tile = codec == VideoOptions.CODEC_TILE
        return VideoOptions(
            width = if (tile) 1080 else (optionInt("video-w") ?: VideoOptions.DEFAULT_WIDTH).coerceAtLeast(1),
            height = if (tile) 1080 else (optionInt("video-h") ?: VideoOptions.DEFAULT_HEIGHT).coerceAtLeast(1),
            fps = (optionInt("video-fps") ?: VideoOptions.DEFAULT_FPS).coerceIn(1, 120),
            qrSize = (optionInt("video-qr-size") ?: 0).coerceAtLeast(0),
            qrRecovery = transportOptions["video-qr-recovery"]?.trim()?.lowercase()
                ?.takeIf { it in VideoOptions.QR_RECOVERY_LEVELS } ?: "low",
            codec = codec,
            tileModule = (optionInt("video-tile-module") ?: 4).coerceIn(1, 270),
            tileRs = (optionInt("video-tile-rs") ?: 0).coerceIn(0, 200),
        )
    }

    companion object {
        const val PROVIDER_JAZZ = "jazz"
        const val PROVIDER_TELEMOST = "telemost"
        const val PROVIDER_WB_STREAM = "wbstream"
        const val PROVIDER_JITSI = "jitsi"
        const val DEFAULT_BYPASS_PROVIDER = PROVIDER_WB_STREAM

        const val TRANSPORT_DATACHANNEL = "datachannel"
        const val TRANSPORT_VP8CHANNEL = "vp8channel"
        const val TRANSPORT_SEICHANNEL = "seichannel"
        const val TRANSPORT_VIDEOCHANNEL = "videochannel"
        const val DEFAULT_TRANSPORT = TRANSPORT_VP8CHANNEL

        const val DEFAULT_VP8_FPS = 60
        const val DEFAULT_VP8_BATCH = 64

        /** Local port the freeturn client raises; must match the Endpoint baked into the WG config. */
        const val DEFAULT_FREETURN_PORT = 9000
        /** Max ADDITIONAL multi-room rooms (so up to MAX_EXTRA_ROOMS+1 = 5 rooms run at once). */
        const val MAX_EXTRA_ROOMS = 4

        /** Default server-side bond reassembler port (must match the bond-server on the olcRTC host). */
        const val DEFAULT_BOND_PORT = 7700

        val supportedBypassProviders = listOf(
            PROVIDER_JAZZ,
            PROVIDER_TELEMOST,
            PROVIDER_WB_STREAM,
            PROVIDER_JITSI
        )

        val supportedTransports = listOf(
            TRANSPORT_DATACHANNEL,
            TRANSPORT_VP8CHANNEL,
            TRANSPORT_SEICHANNEL,
            TRANSPORT_VIDEOCHANNEL
        )

        /**
         * Транспорты ядро olcRTC регистрирует ГЛОБАЛЬНО, провайдер авторизации к ним отношения не
         * имеет — доступны все и каждому. Порядок здесь = «лучшее первым» по матрице
         * `docs/settings.md`, то есть ПОДСКАЗКА, а не запрет: панель (olcrtc-inbound) давно даёт
         * выбирать любую пару, и сервер спокойно ставится, например, в telemost+datachannel.
         * Раньше клиент вырезал такие пары из списка, и поднятый на VPS сервер было нечем открыть.
         */
        fun supportedTransportsForProvider(provider: String): List<String> {
            return when (normalizeProvider(provider)) {
                PROVIDER_TELEMOST ->
                    listOf(TRANSPORT_VP8CHANNEL, TRANSPORT_VIDEOCHANNEL, TRANSPORT_SEICHANNEL, TRANSPORT_DATACHANNEL)
                PROVIDER_WB_STREAM ->
                    listOf(TRANSPORT_VP8CHANNEL, TRANSPORT_SEICHANNEL, TRANSPORT_VIDEOCHANNEL, TRANSPORT_DATACHANNEL)
                PROVIDER_JITSI, PROVIDER_JAZZ ->
                    listOf(TRANSPORT_DATACHANNEL, TRANSPORT_VP8CHANNEL, TRANSPORT_SEICHANNEL, TRANSPORT_VIDEOCHANNEL)
                else -> supportedTransports
            }
        }

        fun normalizeProvider(value: String): String {
            return when (value.trim().lowercase()) {
                PROVIDER_JAZZ, "sberjazz", "sber_jazz" -> PROVIDER_JAZZ
                PROVIDER_TELEMOST, "yandex", "yandex_telemost" -> PROVIDER_TELEMOST
                PROVIDER_WB_STREAM, "wbstream", "wb-stream", "wildberries" -> PROVIDER_WB_STREAM
                PROVIDER_JITSI, "jitsi-meet", "jitsi_meet", "meet" -> PROVIDER_JITSI
                else -> DEFAULT_BYPASS_PROVIDER
            }
        }

        fun normalizeTransport(value: String, provider: String = DEFAULT_BYPASS_PROVIDER): String {
            val normalized = when (value.trim().lowercase()) {
                TRANSPORT_DATACHANNEL, "data", "dc" -> TRANSPORT_DATACHANNEL
                TRANSPORT_VP8CHANNEL, "vp8", "video_vp8", "video-vp8" -> TRANSPORT_VP8CHANNEL
                TRANSPORT_SEICHANNEL, "sei", "sei_channel", "sei-channel", "h264_sei" -> TRANSPORT_SEICHANNEL
                TRANSPORT_VIDEOCHANNEL, "video", "video_channel", "video-channel" -> TRANSPORT_VIDEOCHANNEL
                else -> DEFAULT_TRANSPORT
            }
            val supported = supportedTransportsForProvider(provider)
            return normalized.takeIf { it in supported }
                ?: supported.firstOrNull()
                ?: DEFAULT_TRANSPORT
        }

        fun providerDisplayName(provider: String): String {
            return when (normalizeProvider(provider)) {
                PROVIDER_JAZZ -> "Jazz"
                PROVIDER_TELEMOST -> "Telemost"
                PROVIDER_WB_STREAM -> "WB Stream"
                PROVIDER_JITSI -> "Jitsi"
                else -> "WB Stream"
            }
        }

        fun transportDisplayName(transport: String): String {
            return when (normalizeTransport(transport)) {
                TRANSPORT_DATACHANNEL -> "DataChannel"
                TRANSPORT_VP8CHANNEL -> "VP8"
                TRANSPORT_SEICHANNEL -> "SEI"
                TRANSPORT_VIDEOCHANNEL -> "Video"
                else -> "VP8"
            }
        }

        fun sanitizeVp8Fps(value: Int): Int = value.coerceIn(1, 120)

        fun sanitizeVp8Batch(value: Int): Int = value.coerceIn(1, 64)

        /** Lower-cased keys, trimmed values, no blanks — the payload of a `<key=value&…>` block. */
        fun normalizeTransportOptions(options: Map<String, String>): Map<String, String> =
            options.entries
                .map { (k, v) -> k.trim().lowercase() to v.trim() }
                .filter { (k, v) -> k.isNotEmpty() && v.isNotEmpty() }
                .toMap()
    }
}

/** seichannel parameters handed to the olcRTC core (`sei.*` in its YAML). */
data class SeiOptions(val fps: Int, val batch: Int, val fragmentSize: Int, val ackTimeoutMs: Int) {
    companion object {
        // 60 like this app's VP8 default (and what the desktop always sent); olcRTC's own default is 30.
        const val DEFAULT_FPS = 60
        const val DEFAULT_BATCH = 64
        const val DEFAULT_FRAGMENT = 900
        const val DEFAULT_ACK_MS = 2000
    }
}

/** videochannel parameters handed to the olcRTC core (`video.*` in its YAML). */
data class VideoOptions(
    val width: Int,
    val height: Int,
    val fps: Int,
    val qrSize: Int,
    val qrRecovery: String,
    val codec: String,
    val tileModule: Int,
    val tileRs: Int,
) {
    companion object {
        const val CODEC_QRCODE = "qrcode"
        const val CODEC_TILE = "tile"
        const val DEFAULT_WIDTH = 1920
        const val DEFAULT_HEIGHT = 1080
        const val DEFAULT_FPS = 30
        val QR_RECOVERY_LEVELS = setOf("low", "medium", "high", "highest")
    }
}

@Serializable
data class Vp8TransportConfig(
    val fps: Int = LocationConfig.DEFAULT_VP8_FPS,
    val batch: Int = LocationConfig.DEFAULT_VP8_BATCH
) {
    fun normalized(): Vp8TransportConfig {
        return copy(
            fps = LocationConfig.sanitizeVp8Fps(fps),
            batch = LocationConfig.sanitizeVp8Batch(batch)
        )
    }

    companion object {
        fun from(config: LocationConfig): Vp8TransportConfig {
            return Vp8TransportConfig(config.vp8Fps, config.vp8Batch).normalized()
        }
    }
}

@Serializable(with = LocationTransportConfigSerializer::class)
data class LocationTransportConfig(
    val type: String = LocationConfig.DEFAULT_TRANSPORT,
    val vp8: Vp8TransportConfig? = null,
    /** See [LocationConfig.transportOptions]. */
    val options: Map<String, String> = emptyMap()
) {
    fun normalized(provider: String): LocationTransportConfig {
        val normalizedType = LocationConfig.normalizeTransport(type, provider)
        return copy(
            type = normalizedType,
            vp8 = if (normalizedType == LocationConfig.TRANSPORT_VP8CHANNEL) {
                (vp8 ?: Vp8TransportConfig()).normalized()
            } else {
                null
            }
        )
    }

    companion object {
        fun from(config: LocationConfig): LocationTransportConfig {
            val normalized = config.normalized()
            return LocationTransportConfig(
                type = normalized.transport,
                vp8 = if (normalized.transport == LocationConfig.TRANSPORT_VP8CHANNEL) {
                    Vp8TransportConfig.from(normalized)
                } else {
                    null
                },
                options = normalized.transportOptions
            )
        }
    }
}

@Serializable
private data class LocationTransportConfigSurrogate(
    val type: String = LocationConfig.DEFAULT_TRANSPORT,
    val vp8: Vp8TransportConfig? = null,
    val options: Map<String, String> = emptyMap()
)

object LocationTransportConfigSerializer : KSerializer<LocationTransportConfig> {
    override val descriptor: SerialDescriptor = LocationTransportConfigSurrogate.serializer().descriptor

    override fun deserialize(decoder: Decoder): LocationTransportConfig {
        val jsonDecoder = decoder as? JsonDecoder ?: return LocationTransportConfig()
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> LocationTransportConfig(type = element.contentOrNull.orEmpty())
            is JsonObject -> {
                val surrogate = jsonDecoder.json.decodeFromJsonElement(
                    LocationTransportConfigSurrogate.serializer(),
                    element
                )
                LocationTransportConfig(
                    type = surrogate.type,
                    vp8 = surrogate.vp8,
                    options = surrogate.options
                )
            }
            else -> LocationTransportConfig()
        }
    }

    override fun serialize(encoder: Encoder, value: LocationTransportConfig) {
        val jsonEncoder = encoder as? JsonEncoder
        val surrogate = LocationTransportConfigSurrogate(
            type = value.type,
            vp8 = value.vp8,
            options = value.options
        )
        if (jsonEncoder != null) {
            jsonEncoder.encodeJsonElement(
                jsonEncoder.json.encodeToJsonElement(
                    LocationTransportConfigSurrogate.serializer(),
                    surrogate
                )
            )
        } else {
            encoder.encodeSerializableValue(LocationTransportConfigSurrogate.serializer(), surrogate)
        }
    }
}

@Serializable
data class LocationEndpointConfig(
    @SerialName("room_id")
    val roomId: String = "",
    val key: String = "",
    @SerialName("client_id")
    val legacyClientId: String? = null
)

@Serializable
data class SubscriptionMetadata(
    val name: String? = null,
    val update: String? = null,
    val refresh: String? = null,
    val color: String? = null,
    val icon: String? = null,
    val used: String? = null,
    val available: String? = null,
    @SerialName("update_interval_hours")
    val updateIntervalHours: Int? = null,
    @SerialName("last_refresh_at_epoch_ms")
    val lastRefreshAtEpochMs: Long? = null,
    /**
     * Subscription expiry as wall-clock epoch-ms, parsed from the panel response (`user.expiresAt`
     * ISO date in the body, or the `expire=<unix>` field of the `subscription-userinfo` header).
     * Drives the "end date" / days-left shown on the subscription group. Null when the panel doesn't
     * report one (or it's unlimited).
     */
    @SerialName("expires_at_epoch_ms")
    val expiresAtEpochMs: Long? = null,
    /**
     * Wall-clock epoch-ms of the last refresh ATTEMPT (success or failure), distinct from
     * [lastRefreshAtEpochMs] (last SUCCESS, shown as "last updated"). The due-check schedules the next
     * attempt off this, so a failed fetch is retried only after the update interval elapses again —
     * "retry after the hours indicated" — instead of hammering a down panel every poll.
     */
    @SerialName("last_attempt_at_epoch_ms")
    val lastAttemptAtEpochMs: Long? = null,
    /**
     * Per-subscription auto-update switch. When false, the periodic/launch due-check skips this
     * subscription entirely (a manual refresh still works). Defaults to true so existing
     * subscriptions keep auto-updating.
     */
    @SerialName("auto_update_enabled")
    val autoUpdateEnabled: Boolean = true,
    /**
     * Support link the panel advertises (Remnawave `support-url` header). Shown as a "support"
     * action on the subscription so the user can reach the panel operator. Null when absent.
     */
    @SerialName("support_url")
    val supportUrl: String? = null,
    /**
     * The panel's subscription web page (Remnawave `profile-web-page-url` header) — the human page
     * where the user manages the subscription / sees plans. Opened in a browser. Null when absent.
     */
    @SerialName("web_page_url")
    val webPageUrl: String? = null,
    /**
     * Announcement / notice the panel broadcasts (Remnawave `announce` header, may be `base64:`).
     * Shown to the user on the subscription. Null when absent.
     */
    @SerialName("announce")
    val announce: String? = null,
    /**
     * Provider ID (Happ/Remnawave `providerid` response header) — a tracking identifier the panel
     * attaches to the subscription. Stored and reported daily to the Happ provider-check endpoint, the
     * same behaviour as Happ. Null when the panel doesn't set it.
     */
    @SerialName("provider_id")
    val providerId: String? = null
) {
    fun normalized(): SubscriptionMetadata {
        return copy(
            name = name.cleanMetadataValue(),
            update = update.cleanMetadataValue(),
            refresh = refresh.cleanMetadataValue(),
            color = color.cleanMetadataValue(),
            icon = icon.cleanMetadataValue(),
            used = used.cleanMetadataValue(),
            available = available.cleanMetadataValue(),
            updateIntervalHours = updateIntervalHours?.coerceIn(MIN_UPDATE_INTERVAL_HOURS, MAX_UPDATE_INTERVAL_HOURS),
            lastRefreshAtEpochMs = lastRefreshAtEpochMs?.takeIf { it > 0 },
            expiresAtEpochMs = expiresAtEpochMs?.takeIf { it > 0 },
            lastAttemptAtEpochMs = lastAttemptAtEpochMs?.takeIf { it > 0 },
            supportUrl = supportUrl.cleanMetadataValue(),
            webPageUrl = webPageUrl.cleanMetadataValue(),
            announce = announce.cleanMetadataValue(),
            providerId = providerId.cleanMetadataValue()
        )
    }

    fun isEmpty(): Boolean {
        return name.isNullOrBlank() &&
                update.isNullOrBlank() &&
                refresh.isNullOrBlank() &&
                color.isNullOrBlank() &&
                icon.isNullOrBlank() &&
                used.isNullOrBlank() &&
                available.isNullOrBlank() &&
                updateIntervalHours == null &&
                lastRefreshAtEpochMs == null &&
                expiresAtEpochMs == null &&
                lastAttemptAtEpochMs == null &&
                autoUpdateEnabled &&
                supportUrl.isNullOrBlank() &&
                webPageUrl.isNullOrBlank() &&
                announce.isNullOrBlank() &&
                providerId.isNullOrBlank()
    }

    companion object {
        const val DEFAULT_UPDATE_INTERVAL_HOURS = 24
        const val MIN_UPDATE_INTERVAL_HOURS = 1
        const val MAX_UPDATE_INTERVAL_HOURS = 720
    }
}

@Serializable
data class LocationMetadata(
    val name: String? = null,
    val color: String? = null,
    val icon: String? = null,
    val used: String? = null,
    val available: String? = null,
    val ip: String? = null,
    val comment: String? = null,
    val mimo: String? = null,
    val subscription: SubscriptionMetadata? = null
) {
    fun normalized(): LocationMetadata {
        val normalizedSubscription = subscription
            ?.normalized()
            ?.takeUnless { it.isEmpty() }
        return copy(
            name = name.cleanMetadataValue(),
            color = color.cleanMetadataValue(),
            icon = icon.cleanMetadataValue(),
            used = used.cleanMetadataValue(),
            available = available.cleanMetadataValue(),
            ip = ip.cleanMetadataValue(),
            comment = comment.cleanMetadataValue(),
            mimo = mimo.cleanMetadataValue(),
            subscription = normalizedSubscription
        )
    }

    fun isEmpty(): Boolean {
        return name.isNullOrBlank() &&
                color.isNullOrBlank() &&
                icon.isNullOrBlank() &&
                used.isNullOrBlank() &&
                available.isNullOrBlank() &&
                ip.isNullOrBlank() &&
                comment.isNullOrBlank() &&
                mimo.isNullOrBlank() &&
                (subscription == null || subscription.isEmpty())
    }
}

@Serializable
data class LocationEntry(
    @SerialName("storage_id")
    val storageId: String,
    val name: String = "",
    /** Display-only description (e.g. subscription `meta.serverDescription`). See [LocationConfig.description]. */
    val description: String = "",
    @SerialName("subscription_url")
    val subscriptionUrl: String? = null,
    val endpoint: LocationEndpointConfig? = null,
    val engine: EngineType? = null,
    val proxy: ProxyProfile? = null,
    /** Optional second proxy chained on top of [proxy] (cascade). See [LocationConfig.proxy2]. */
    @SerialName("proxy2")
    val proxy2: ProxyProfile? = null,
    /** Vestigial; the main proxy is always applied now. Kept for back-compat parsing. */
    @SerialName("proxy_enabled")
    val proxyEnabled: Boolean = true,
    val core: ProxyCore? = null,
    val vkturn: VkTurnConfig? = null,
    @SerialName("masterdns")
    val masterDns: MasterDnsConfig? = null,
    @SerialName("openflux")
    val openFlux: OpenFluxConfig? = null,
    val advanced: AdvancedCoreConfig? = null,
    @SerialName("fake_dns")
    val fakeDns: FakeDnsSpec? = null,
    @SerialName("routing_profile_id")
    val routingProfileId: String? = null,
    @SerialName("auth_provider")
    val authProvider: String? = null,
    @SerialName("carrier")
    val legacyCarrier: String? = null,
    val transport: LocationTransportConfig? = null,
    val metadata: LocationMetadata? = null,
    @SerialName("subscriptionUrl")
    val legacySubscriptionUrl: String? = null,
    @SerialName("id")
    val legacyId: String? = null,
    @SerialName("room_id")
    val legacyRoomId: String? = null,
    @SerialName("server")
    val legacyServer: String? = null,
    @SerialName("client_id")
    val legacyClientId: String? = null,
    @SerialName("clientId")
    val legacyClientIdCamel: String? = null,
    @SerialName("key")
    val legacyKey: String? = null,
    @SerialName("password")
    val legacyPassword: String? = null,
    @SerialName("bypass_provider")
    val legacyBypassProvider: String? = null,
    @SerialName("bypassProvider")
    val legacyBypassProviderCamel: String? = null,
    @SerialName("provider")
    val legacyProvider: String? = null,
    @SerialName("vp8_fps")
    val legacyVp8Fps: Int? = null,
    @SerialName("vp8Fps")
    val legacyVp8FpsCamel: Int? = null,
    @SerialName("vp8_batch")
    val legacyVp8Batch: Int? = null,
    @SerialName("vp8Batch")
    val legacyVp8BatchCamel: Int? = null,
    @SerialName("multi_room")
    val multiRoomEnabled: Boolean = false,
    @SerialName("extra_rooms")
    val extraRooms: List<ExtraRoom> = emptyList(),
    @SerialName("multi_room_bond")
    val multiRoomBond: Boolean = false,
    @SerialName("bond_port")
    val bondPort: Int = 0
) {
    val location: LocationConfig
        get() {
            val provider = firstNotBlank(
                authProvider,
                legacyCarrier,
                legacyBypassProvider,
                legacyBypassProviderCamel,
                legacyProvider
            )
            val transportConfig = transport ?: LocationTransportConfig()
            val vp8Options = transportConfig.vp8
            return LocationConfig(
                name = name,
                description = description,
                id = firstNotBlank(endpoint?.roomId, legacyId, legacyRoomId, legacyServer),
                key = firstNotBlank(endpoint?.key, legacyKey, legacyPassword),
                bypassProvider = provider,
                transport = transportConfig.type,
                vp8Fps = vp8Options?.fps
                    ?: legacyVp8Fps
                    ?: legacyVp8FpsCamel
                    ?: LocationConfig.DEFAULT_VP8_FPS,
                vp8Batch = vp8Options?.batch
                    ?: legacyVp8Batch
                    ?: legacyVp8BatchCamel
                    ?: LocationConfig.DEFAULT_VP8_BATCH,
                transportOptions = transportConfig.options,
                engine = engine ?: EngineType.Stealth,
                proxy = proxy,
                proxy2 = proxy2,
                proxyEnabled = proxyEnabled,
                core = core ?: ProxyCore.Auto,
                vkturn = vkturn,
                masterDns = masterDns,
                openFlux = openFlux,
                advanced = advanced,
                fakeDns = fakeDns,
                routingProfileId = routingProfileId.orEmpty(),
                multiRoomEnabled = multiRoomEnabled,
                extraRooms = extraRooms,
                multiRoomBond = multiRoomBond,
                bondPort = bondPort,
            ).normalized()
        }

    val bypassProvider: String
        get() = location.bypassProvider

    fun normalized(): LocationEntry {
        val config = location
        return LocationEntry(
            storageId = storageId.trim(),
            name = config.name,
            description = config.description,
            subscriptionUrl = firstNotBlank(subscriptionUrl, legacySubscriptionUrl).ifBlank { null },
            endpoint = LocationEndpointConfig(
                roomId = config.id,
                key = config.key
            ),
            engine = config.engine,
            proxy = config.proxy,
            proxy2 = config.proxy2,
            proxyEnabled = config.proxyEnabled,
            core = config.core,
            vkturn = config.vkturn,
            masterDns = config.masterDns,
            openFlux = config.openFlux,
            advanced = config.advanced,
            fakeDns = config.fakeDns,
            routingProfileId = config.routingProfileId.ifBlank { null },
            authProvider = config.bypassProvider,
            transport = LocationTransportConfig.from(config),
            multiRoomEnabled = config.multiRoomEnabled,
            extraRooms = config.extraRooms,
            multiRoomBond = config.multiRoomBond,
            bondPort = config.bondPort,
            metadata = metadata
                ?.normalized()
                ?.takeUnless { it.isEmpty() }
        )
    }

    companion object {
        fun from(
            storageId: String,
            location: LocationConfig,
            subscriptionUrl: String? = null,
            metadata: LocationMetadata? = null
        ): LocationEntry {
            val config = location.normalized()
            return LocationEntry(
                storageId = storageId,
                name = config.name,
                description = config.description,
                subscriptionUrl = subscriptionUrl,
                endpoint = LocationEndpointConfig(
                    roomId = config.id,
                    key = config.key
                ),
                engine = config.engine,
                proxy = config.proxy,
                proxy2 = config.proxy2,
                proxyEnabled = config.proxyEnabled,
                core = config.core,
                vkturn = config.vkturn,
                masterDns = config.masterDns,
                openFlux = config.openFlux,
                advanced = config.advanced,
                fakeDns = config.fakeDns,
                routingProfileId = config.routingProfileId.ifBlank { null },
                authProvider = config.bypassProvider,
                transport = LocationTransportConfig.from(config),
                multiRoomEnabled = config.multiRoomEnabled,
                extraRooms = config.extraRooms,
                multiRoomBond = config.multiRoomBond,
                bondPort = config.bondPort,
                metadata = metadata
            ).normalized()
        }

        private fun firstNotBlank(vararg values: String?): String {
            return values.firstOrNull { !it.isNullOrBlank() } ?: ""
        }
    }
}

private fun String?.cleanMetadataValue(): String? {
    return this?.trim()?.takeIf { it.isNotEmpty() }
}

@Serializable
data class LocationBundleV4(
    val version: Int = 5,
    @SerialName("active_location_id")
    val activeLocationId: String? = null,
    val locations: List<LocationEntry> = emptyList()
) {
    fun normalized(): LocationBundleV4 {
        val normalizedLocations = locations
            .map { it.normalized() }
            .filter { it.storageId.isNotBlank() && it.location.isStorable() }
            .distinctBy { it.storageId }

        val active = activeLocationId
            ?.takeIf { id -> normalizedLocations.any { it.storageId == id } }
            ?: normalizedLocations.firstOrNull()?.storageId

        return copy(
            version = CURRENT_VERSION,
            activeLocationId = active,
            locations = normalizedLocations
        )
    }

    companion object {
        const val CURRENT_VERSION = 5
    }
}
