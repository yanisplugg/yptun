package org.olcbox.app.data.share

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry

object ConfigShareService {
    fun olcRtcUri(entry: LocationEntry): String = olcRtcUri(entry.location)

    fun olcRtcUri(config: LocationConfig): String {
        val normalized = config.normalized()
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
                    locationCount = subscriptionEntries.size
                )
            }
    }
}

data class SubscriptionShareItem(
    val url: String,
    val name: String,
    val updateIntervalHours: Int?,
    val lastRefreshAtEpochMs: Long?,
    val locationCount: Int
)
