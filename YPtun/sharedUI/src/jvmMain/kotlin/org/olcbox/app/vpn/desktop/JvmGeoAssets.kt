package org.olcbox.app.vpn.desktop

import org.olcbox.app.desktop.DesktopPaths
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

/**
 * Desktop port of Android's GeoAssetManager: makes sure xray-core's geoip.dat / geosite.dat are
 * present under `<appData>/geo/`, downloading them (following redirects) when missing.
 */
internal object JvmGeoAssets {

    private const val GEOIP_FILE = "geoip.dat"
    private const val GEOSITE_FILE = "geosite.dat"
    private const val MIN_DAT_BYTES = 64L * 1024
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val MAX_REDIRECTS = 5

    const val DEFAULT_GEOIP_URL =
        "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat"
    const val DEFAULT_GEOSITE_URL =
        "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat"

    fun assetDir(): File =
        DesktopPaths.appDataDir().resolve("geo").toFile().apply { if (!exists()) mkdirs() }

    fun hasAssets(): Boolean {
        val ip = File(assetDir(), GEOIP_FILE)
        val site = File(assetDir(), GEOSITE_FILE)
        return ip.isFile && ip.length() >= MIN_DAT_BYTES && site.isFile && site.length() >= MIN_DAT_BYTES
    }

    /** True when both .dat files are present (downloading any that are missing). */
    fun ensureAssets(geoipUrl: String, geositeUrl: String): Boolean {
        if (hasAssets()) return true
        return runCatching {
            downloadTo(geoipUrl.ifBlank { DEFAULT_GEOIP_URL }, File(assetDir(), GEOIP_FILE))
            downloadTo(geositeUrl.ifBlank { DEFAULT_GEOSITE_URL }, File(assetDir(), GEOSITE_FILE))
            hasAssets()
        }.getOrDefault(false)
    }

    private fun downloadTo(urlString: String, target: File) {
        var current = urlString
        var redirects = 0
        while (true) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val next = conn.getHeaderField("Location")
                conn.disconnect()
                require(!next.isNullOrBlank() && redirects++ < MAX_REDIRECTS) { "bad redirect from $current" }
                current = next
                continue
            }
            require(code in 200..299) { "HTTP $code for $current" }
            val tmp = Files.createTempFile(target.parentFile.toPath(), target.name, ".part")
            conn.inputStream.use { input ->
                Files.newOutputStream(tmp).use { output -> input.copyTo(output) }
            }
            conn.disconnect()
            Files.move(
                tmp, target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
            return
        }
    }
}
