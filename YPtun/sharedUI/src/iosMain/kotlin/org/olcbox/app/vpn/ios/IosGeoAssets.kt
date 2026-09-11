package org.olcbox.app.vpn.ios

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToFile

/**
 * xray-core's geoip.dat / geosite.dat in the App Group container (iOS twin of JvmGeoAssets /
 * Android's GeoAssetManager). Called from the tunnel extension before the tunnel is up, so the
 * download goes over the real network.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosGeoAssets {
    private const val GEOIP_FILE = "geoip.dat"
    private const val GEOSITE_FILE = "geosite.dat"
    private const val MIN_DAT_BYTES = 64L * 1024

    private const val DEFAULT_GEOIP_URL =
        "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat"
    private const val DEFAULT_GEOSITE_URL =
        "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat"

    val assetDir: String by lazy {
        IosSharedStore.path("geo").also {
            NSFileManager.defaultManager.createDirectoryAtPath(it, true, null, null)
        }
    }

    private fun sizeOf(path: String): Long =
        (NSFileManager.defaultManager.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)
            ?.longLongValue ?: 0L

    fun hasAssets(): Boolean =
        sizeOf("$assetDir/$GEOIP_FILE") >= MIN_DAT_BYTES && sizeOf("$assetDir/$GEOSITE_FILE") >= MIN_DAT_BYTES

    /** True when both .dat files are present, downloading any that are missing. */
    fun ensureAssets(geoipUrl: String, geositeUrl: String): Boolean {
        if (hasAssets()) return true
        download(geoipUrl.ifBlank { DEFAULT_GEOIP_URL }, "$assetDir/$GEOIP_FILE")
        download(geositeUrl.ifBlank { DEFAULT_GEOSITE_URL }, "$assetDir/$GEOSITE_FILE")
        return hasAssets()
    }

    // NSData follows redirects (GitHub's latest/download → objects host) on its own.
    private fun download(url: String, target: String) {
        if (sizeOf(target) >= MIN_DAT_BYTES) return
        val data = NSURL.URLWithString(url)?.let { NSData.dataWithContentsOfURL(it) } ?: return
        data.writeToFile(target, true)
    }
}
