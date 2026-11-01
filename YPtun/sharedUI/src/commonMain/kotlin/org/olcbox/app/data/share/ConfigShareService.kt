package org.olcbox.app.data.share

import org.olcbox.app.data.importer.CsqttUriParser
import org.olcbox.app.data.importer.QwdttUriParser
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry

object ConfigShareService {
    /**
     * The link the "Share" button shows. [universal] = yptun:// (carries the whole inbound); otherwise the
     * engine's own protocol link. Engines with no link of their own (MasterDNS/OpenFlux/Snolc) fall back
     * to yptun:// — [olcRtcUri] would emit a bogus olcrtc:// link for them.
     */
    fun shareLink(config: LocationConfig, universal: Boolean): String {
        val hasOwnLink = when (config.engine) {
            EngineType.MasterDns, EngineType.OpenFlux, EngineType.Snolc -> false
            else -> true
        }
        return if (universal || !hasOwnLink) YptunInboundCodec.compose(config) else olcRtcUri(config)
    }

    fun olcRtcUri(entry: LocationEntry): String = olcRtcUri(entry.location)

    fun olcRtcUri(config: LocationConfig): String {
        val normalized = config.normalized()
        // VK-TURN locations share their freeturn:// link verbatim (it carries the WG config); the WDTT
        // core has no freeturn:// link, so re-emit the wdtt-server's qwdtt:// quick link instead.
        if (normalized.engine == EngineType.VkTurn) {
            val vk = normalized.vkturn
            if (vk?.usesWdtt() == true) return QwdttUriParser.compose(normalized.name, vk)
            if (vk?.usesCsqtt() == true) return CsqttUriParser.compose(vk, normalized.name)
            return vk?.uri.orEmpty()
        }
        // Standard/Chain run a sing-box/Xray proxy — share the matching proxy link (vless/…/awg),
        // NOT an olcrtc:// link (which only makes sense for the Stealth olcRTC engine).
        if (normalized.engine == EngineType.Standard || normalized.engine == EngineType.Chain) {
            normalized.proxy?.let { ShareLinkComposer.compose(it) }
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        val main = olcRtcRoomLink(normalized, normalized.bypassProvider, normalized.transport, normalized.id, normalized.key)
        return buildString {
            append("olcrtc://")
            append(main)
            // Multi-room: the extra rooms ride in the same link, `!`-separated, so copying it keeps them all.
            if (normalized.usesMultiRoom()) {
                normalized.extraRooms
                    .filter { it.room.isNotBlank() && it.key.isNotBlank() }
                    .take(LocationConfig.MAX_EXTRA_ROOMS)
                    .forEach {
                        append('!')
                        append(
                            olcRtcRoomLink(
                                normalized,
                                it.provider.ifBlank { normalized.bypassProvider },
                                it.transport.ifBlank { normalized.transport },
                                it.room,
                                it.key
                            )
                        )
                    }
            }
            val name = normalized.displayName()
            if (name.isNotBlank() && name != normalized.id) {
                append('$')
                append(name)
            }
        }
    }

    /** `provider?transport@room#key` — one room of an olcrtc:// link. */
    private fun olcRtcRoomLink(config: LocationConfig, provider: String, transportName: String, room: String, key: String): String {
        val transport = when (transportName) {
            LocationConfig.TRANSPORT_VP8CHANNEL -> {
                "vp8channel<vp8-fps=${config.vp8Fps}&vp8-batch=${config.vp8Batch}>"
            }
            // The location's own parameters (docs/uri.md payload), so the link opens the same way elsewhere.
            LocationConfig.TRANSPORT_SEICHANNEL -> config.seiOptions().let {
                "seichannel<fps=${it.fps}&batch=${it.batch}&frag=${it.fragmentSize}&ack-ms=${it.ackTimeoutMs}>"
            }
            LocationConfig.TRANSPORT_VIDEOCHANNEL -> config.videoOptions().let {
                "videochannel<video-w=${it.width}&video-h=${it.height}&video-fps=${it.fps}&video-codec=${it.codec}" +
                    "&video-qr-size=${it.qrSize}&video-qr-recovery=${it.qrRecovery}" +
                    "&video-tile-module=${it.tileModule}&video-tile-rs=${it.tileRs}>"
            }
            else -> transportName
        }
        return "$provider?$transport@$room#$key"
    }

    fun subscriptionQrText(subscriptionUrl: String): String = subscriptionUrl.trim()

    fun subscriptionShareItems(entries: List<LocationEntry>): List<SubscriptionShareItem> {
        return entries
            .mapNotNull { entry ->
                val url = entry.subscriptionUrl
                    ?.trim()
                    ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                    ?: return@mapNotNull null
                url to entry
            }
            .groupBy({ it.first }, { it.second })
            .entries
            .sortedBy { it.key }
            .map { (url, subscriptionEntries) ->
                val first = subscriptionEntries.first()
                val metadata = subscriptionEntries.firstNotNullOfOrNull { it.metadata?.subscription }
                SubscriptionShareItem(
                    url = url,
                    name = metadata?.name
                        ?.takeIf { it.isNotBlank() }
                        ?: first.name.takeIf { it.isNotBlank() }
                        ?: first.location.displayName(),
                    updateIntervalHours = metadata?.updateIntervalHours,
                    lastRefreshAtEpochMs = metadata?.lastRefreshAtEpochMs,
                    locationCount = subscriptionEntries.size,
                    supportUrl = metadata?.supportUrl,
                    webPageUrl = metadata?.webPageUrl,
                    announce = metadata?.announce
                )
            }
    }
}

data class SubscriptionShareItem(
    val url: String,
    val name: String,
    val updateIntervalHours: Int?,
    val lastRefreshAtEpochMs: Long?,
    val locationCount: Int,
    /** Remnawave/Happ `support-url` header — panel support link, if advertised. */
    val supportUrl: String? = null,
    /** Remnawave/Happ `profile-web-page-url` header — subscription management page, if advertised. */
    val webPageUrl: String? = null,
    /** Remnawave/Happ `announce` header — panel announcement to show the user, if any. */
    val announce: String? = null
)
