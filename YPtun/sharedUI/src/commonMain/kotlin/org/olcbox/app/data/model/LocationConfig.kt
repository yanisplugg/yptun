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
) {
    fun isComplete(): Boolean =
        isStorable() && vkLink.isNotBlank()

    /** UDP payload (WireGuard/AmneziaWG) needs udprelay; TCP proxy needs tcpfwd. */
    fun requiredMode(): String = if (outbound == OUTBOUND_PROXY) "tcp" else "udp"

    /** Resolves [proxyCore]==Auto to a concrete backend for the given exit/chain [profile]. */
    fun resolvedProxyCore(profile: ProxyProfile?): ProxyCore = when {
        proxyCore != ProxyCore.Auto -> proxyCore
        !profile?.rawXrayConfig.isNullOrBlank() -> ProxyCore.Xray
        profile?.network == ProxyProfile.NETWORK_XHTTP -> ProxyCore.Xray
        else -> ProxyCore.SingBox
    }

    companion object {
        const val OUTBOUND_WIREGUARD = "wireguard"
        const val OUTBOUND_AMNEZIAWG = "amneziawg"
        const val OUTBOUND_PROXY = "proxy"
    }

    /**
     * True when the freeturn link + WG transport are present. The per-client [vkLink]
     * is filled in by the user via the location settings after import, so a location
     * is storable (and shown in the list) before [isComplete] is satisfied.
     */
    fun isStorable(): Boolean =
        uri.startsWith("freeturn://") && listenPort in 1..65535
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
) {
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

    /** Resolves [ProxyCore.Auto] to a concrete backend based on the proxy transport. */
    fun resolvedCore(): ProxyCore = when {
        // A full raw Xray config can only be run by xray-core, regardless of the stored choice.
        !proxy?.rawXrayConfig.isNullOrBlank() -> ProxyCore.Xray
        core == ProxyCore.Auto ->
            if (proxy?.network == ProxyProfile.NETWORK_XHTTP) ProxyCore.Xray else ProxyCore.SingBox
        else -> core
    }

    /** True when this config has everything its [engine] needs to connect. */
    fun isComplete(): Boolean = when (engine) {
        // olcRTC needs a room id + key.
        EngineType.Stealth -> id.isNotBlank() && key.isNotBlank()
        // sing-box needs a valid main proxy server (always the primary outbound). [proxy2] is optional.
        EngineType.Standard -> proxy?.isComplete() == true
        // Chain needs the olcRTC stealth tunnel plus a valid main proxy. [proxy2] is optional.
        EngineType.Chain -> proxy?.isComplete() == true && id.isNotBlank() && key.isNotBlank()
        // VK-TURN needs the freeturn link, the per-client VK call link and the WireGuard outbound.
        EngineType.VkTurn -> vkturn?.isComplete() == true && !proxy?.rawOutbound.isNullOrBlank()
    }

    /**
     * True when this config has enough to persist in the location list. Matches
     * [isComplete] for every engine except VK-TURN, where the per-client VK call
     * link is filled in after import, so the location is kept (and shown) without it.
     */
    fun isStorable(): Boolean = when (engine) {
        EngineType.VkTurn -> vkturn?.isStorable() == true && !proxy?.rawOutbound.isNullOrBlank()
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
                PROVIDER_JITSI -> listOf(TRANSPORT_DATACHANNEL)
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
    val lastAttemptAtEpochMs: Long? = null
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
            lastAttemptAtEpochMs = lastAttemptAtEpochMs?.takeIf { it > 0 }
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
                lastAttemptAtEpochMs == null
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
    val advanced: AdvancedCoreConfig? = null,
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
    val legacyVp8BatchCamel: Int? = null
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
                advanced = advanced,
                routingProfileId = routingProfileId.orEmpty(),
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
            advanced = config.advanced,
            routingProfileId = config.routingProfileId.ifBlank { null },
            authProvider = config.bypassProvider,
            transport = LocationTransportConfig.from(config),
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
                advanced = config.advanced,
                routingProfileId = config.routingProfileId.ifBlank { null },
                authProvider = config.bypassProvider,
                transport = LocationTransportConfig.from(config),
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
