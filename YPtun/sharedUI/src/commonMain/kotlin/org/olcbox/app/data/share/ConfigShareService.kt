package org.olcbox.app.data.share

import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry

object ConfigShareService {
    fun olcRtcUri(entry: LocationEntry): String = olcRtcUri(entry.location)

    fun olcRtcUri(config: LocationConfig): String {
        val normalized = config.normalized()
        // VK-TURN locations share their freeturn:// link verbatim (it carries the WG config).
        if (normalized.engine == EngineType.VkTurn) {
            return normalized.vkturn?.uri.orEmpty()
        }
        // Standard/Chain run a sing-box/Xray proxy — share the matching proxy link (vless/…/awg),
        // NOT an olcrtc:// link (which only makes sense for the Stealth olcRTC engine).
        if (normalized.engine == EngineType.Standard || normalized.engine == EngineType.Chain) {
            normalized.proxy?.let { ShareLinkComposer.compose(it) }
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        val transport = when (normalized.transport) {
            LocationConfig.TRANSPORT_VP8CHANNEL -> {
                "vp8channel<vp8-fps=${normalized.vp8Fps}&vp8-batch=${normalized.vp8Batch}>"
            }
            LocationConfig.TRANSPORT_SEICHANNEL -> {
                "seichannel<fps=60&batch=64&frag=900&ack-ms=2000>"
            }
            else -> normalized.transport
        }

        return buildString {
            append("olcrtc://")
            append(normalized.bypassProvider)
            append('?')
            append(transport)
            append('@')
            append(normalized.id)
            append('#')
            append(normalized.key)
            val name = normalized.displayName()
            if (name.isNotBlank() && name != normalized.id) {
                append('$')
                append(name)
            }
        }
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
