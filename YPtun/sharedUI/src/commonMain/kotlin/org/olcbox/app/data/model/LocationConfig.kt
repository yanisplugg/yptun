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
import kotlinx.serialization.json.contentOrNull
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
    /** WDTT TLS fingerprint for the VK auth flow: chrome/safari/ios/android/firefox (blank → chrome). */
    @SerialName("wdtt_fingerprint")
    val wdttFingerprint: String = "",
    /** WDTT worker count; 0 → core default. Clamped to [9,108] and rounded to a multiple of 9 in-core. */
    @SerialName("wdtt_workers")
    val wdttWorkers: Int = 0,
) {
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
 * dnstt (DNS tunnel) transport parameters for [EngineType.Dnstt]. The dnstt client raises a local
 * listener that transparently forwards each TCP connection through DNS TXT queries to the
 * dnstt-server, which relays to its upstream (typically a SOCKS5) — so the local port behaves as
 * that SOCKS5 and the TUN bridge consumes it directly. KCP transport + Noise_NK encryption.
 */
@Serializable
data class DnsttConfig(
    /** Tunnel domain delegated to the dnstt-server, e.g. `t.example.com`. */
    @SerialName("domain")
    val domain: String = "",
    /** Server Noise public key in hex (required — the Noise_NK responder key). */
    @SerialName("pubkey")
    val pubKey: String = "",
    /**
     * UDP DNS resolver that reaches the tunnel domain's authoritative server, e.g. `1.1.1.1:53`,
     * `8.8.8.8`, or a local/ISP resolver. Port defaults to 53 if omitted. The mobile dnstt client
     * speaks plain UDP DNS (no DoH/DoT), so this must be a UDP resolver address.
     */
    @SerialName("resolver")
    val resolver: String = "",
) {
    /** True when the dnstt tunnel has everything it needs to connect. */
    fun isComplete(): Boolean =
        domain.isNotBlank() && pubKey.isNotBlank() && resolver.isNotBlank()

    fun normalized(): DnsttConfig = DnsttConfig(
        domain = domain.trim(),
        pubKey = pubKey.trim(),
        resolver = resolver.trim(),
    )
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
    val id: String = "",
    val key: String = "",
    @SerialName("bypass_provider")
    val bypassProvider: String = DEFAULT_BYPASS_PROVIDER,
    val transport: String = DEFAULT_TRANSPORT,
    @SerialName("vp8_fps")
    val vp8Fps: Int = DEFAULT_VP8_FPS,
    @SerialName("vp8_batch")
    val vp8Batch: Int = DEFAULT_VP8_BATCH,
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
    /** dnstt (DNS tunnel) transport for the [EngineType.Dnstt] engine. Null for other engines. */
    val dnstt: DnsttConfig? = null,
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
            id = id.trim(),
            key = key.trim(),
            bypassProvider = provider,
            transport = normalizedTransport,
            vp8Fps = sanitizeVp8Fps(vp8Fps),
            vp8Batch = sanitizeVp8Batch(vp8Batch),
            engine = engine,
            proxy = proxy,
            proxy2 = proxy2,
            core = core,
            vkturn = vkturn,
            dnstt = dnstt?.normalized(),
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
        // dnstt needs the tunnel domain, server public key and a DNS resolver.
        EngineType.Dnstt -> dnstt?.isComplete() == true
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

    companion object {
        const val PROVIDER_JAZZ = "jazz"
        const val PROVIDER_TELEMOST = "telemost"
        const val PROVIDER_WB_STREAM = "wbstream"
        const val PROVIDER_JITSI = "jitsi"
        const val DEFAULT_BYPASS_PROVIDER = PROVIDER_WB_STREAM

        const val TRANSPORT_DATACHANNEL = "datachannel"
        const val TRANSPORT_VP8CHANNEL = "vp8channel"
        const val TRANSPORT_SEICHANNEL = "seichannel"
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
            TRANSPORT_SEICHANNEL
        )

        fun supportedTransportsForProvider(provider: String): List<String> {
            return when (normalizeProvider(provider)) {
                PROVIDER_TELEMOST -> listOf(TRANSPORT_VP8CHANNEL, TRANSPORT_SEICHANNEL)
                // The olcRTC core registers transports GLOBALLY and the auth provider is independent, so
                // Jitsi can carry any of them — offer all three (datachannel stays the default/known-good).
                PROVIDER_JITSI -> listOf(TRANSPORT_DATACHANNEL, TRANSPORT_VP8CHANNEL, TRANSPORT_SEICHANNEL)
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
                else -> "VP8"
            }
        }

        fun sanitizeVp8Fps(value: Int): Int = value.coerceIn(1, 120)

        fun sanitizeVp8Batch(value: Int): Int = value.coerceIn(1, 64)
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
    val vp8: Vp8TransportConfig? = null
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
                }
            )
        }
    }
}

@Serializable
private data class LocationTransportConfigSurrogate(
    val type: String = LocationConfig.DEFAULT_TRANSPORT,
    val vp8: Vp8TransportConfig? = null
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
                    vp8 = surrogate.vp8
                )
            }
            else -> LocationTransportConfig()
        }
    }

    override fun serialize(encoder: Encoder, value: LocationTransportConfig) {
        val jsonEncoder = encoder as? JsonEncoder
        val surrogate = LocationTransportConfigSurrogate(
            type = value.type,
            vp8 = value.vp8
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
    val dnstt: DnsttConfig? = null,
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
                engine = engine ?: EngineType.Stealth,
                proxy = proxy,
                proxy2 = proxy2,
                proxyEnabled = proxyEnabled,
                core = core ?: ProxyCore.Auto,
                vkturn = vkturn,
                dnstt = dnstt,
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
            dnstt = config.dnstt,
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
                dnstt = config.dnstt,
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
