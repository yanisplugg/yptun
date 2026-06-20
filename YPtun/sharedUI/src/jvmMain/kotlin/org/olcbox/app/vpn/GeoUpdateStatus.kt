package org.olcbox.app.vpn

/** Transient state of an on-demand geo-database (`geoip.dat`/`geosite.dat`) download. */
sealed interface GeoUpdateStatus {
    /** A download is in progress. */
    data object Running : GeoUpdateStatus

    /** The databases were refreshed at [timestampMs]; [bytes] total fetched. */
    data class Success(val timestampMs: Long, val bytes: Long) : GeoUpdateStatus

    /** The download failed with [message]. */
    data class Failed(val message: String) : GeoUpdateStatus
}
