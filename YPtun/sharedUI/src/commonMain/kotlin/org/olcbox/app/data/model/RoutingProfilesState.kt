package org.olcbox.app.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Persisted state for the Happ-style routing-profile system: the user's saved [profiles], which one
 * is applied globally ([globalProfileId], blank = none), and the configurable geo-database sources
 * plus the timestamp of the last successful `.dat` download.
 *
 * A location may override the global choice via [LocationConfig.routingProfileId]; resolution order
 * is: per-location id → [globalProfileId] → none.
 */
@Serializable
data class RoutingProfilesState(
    val profiles: List<RoutingProfile> = emptyList(),
    /** Id of the profile applied to every connection by default; blank = no global profile. */
    val globalProfileId: String = "",
    /** Source URL for the xray-core `geoip.dat` (v2ray format). */
    val geoipUrl: String = RoutingProfile.DEFAULT_GEOIP_URL,
    /** Source URL for the xray-core `geosite.dat` (v2ray format). */
    val geositeUrl: String = RoutingProfile.DEFAULT_GEOSITE_URL,
    /** Base for sing-box `.srs` geosite rule-sets (geo selectors map onto `<base>geosite-<cat>.srs`). */
    val singboxGeositeBase: String = "",
    /** Base for sing-box `.srs` geoip rule-sets. */
    val singboxGeoipBase: String = "",
    /** epoch-ms of the last successful geo `.dat` download (0 = never). */
    val geoLastUpdated: Long = 0L,
) {
    /** The profile resolved for a location id, honouring the explicit "none" sentinel and the global default. */
    fun resolve(locationProfileId: String?): RoutingProfile? {
        val id = locationProfileId?.trim().orEmpty().ifBlank { globalProfileId }
        if (id.isBlank() || id == RoutingProfile.NONE_ID) return null
        return profiles.firstOrNull { it.id == id }
    }

    fun profileById(id: String?): RoutingProfile? =
        id?.takeIf { it.isNotBlank() }?.let { pid -> profiles.firstOrNull { it.id == pid } }

    /** True when at least one applicable profile references geo selectors needing the `.dat` files. */
    fun anyNeedsGeoFiles(): Boolean = profiles.any { it.needsGeoFiles() }

    /**
     * Encodes the WHOLE routing setup (every profile + global choice + geo sources) into a single
     * `yptun://routing/<base64url-json>` link for one-tap export/import. Round-trips via [fromRoutingLink].
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun toRoutingLink(): String {
        val json = exportJson.encodeToString(serializer(), this)
        val payload = Base64.UrlSafe.encode(json.encodeToByteArray()).trimEnd('=')
        return "$ROUTING_LINK_PREFIX$payload"
    }

    companion object {
        const val ROUTING_LINK_PREFIX = "yptun://routing/"

        private val exportJson = Json { encodeDefaults = false }
        private val importJson = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Parses a `yptun://routing/<base64url-json>` link back into a full state, or null if invalid. */
        @OptIn(ExperimentalEncodingApi::class)
        fun fromRoutingLink(link: String): RoutingProfilesState? {
            val t = link.trim()
            if (!t.startsWith(ROUTING_LINK_PREFIX, ignoreCase = true)) return null
            val payload = t.removePrefix(ROUTING_LINK_PREFIX)
                .substringBefore('#').substringBefore('?').trim()
            if (payload.isBlank()) return null
            val padded = payload.padEnd((payload.length + 3) / 4 * 4, '=')
            val bytes = runCatching { Base64.UrlSafe.decode(padded) }.getOrNull() ?: return null
            return runCatching {
                importJson.decodeFromString(serializer(), bytes.decodeToString())
            }.getOrNull()
        }
    }
}
