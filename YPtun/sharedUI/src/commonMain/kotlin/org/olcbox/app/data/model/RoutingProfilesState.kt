package org.olcbox.app.data.model

import kotlinx.serialization.Serializable

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
}
