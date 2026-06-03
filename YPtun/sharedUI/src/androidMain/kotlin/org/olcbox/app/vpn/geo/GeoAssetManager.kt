package org.olcbox.app.vpn.geo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and stores the v2ray-format `geoip.dat` / `geosite.dat` that xray-core needs to resolve
 * `geoip:`/`geosite:` routing selectors. The files live in a private app directory whose path is
 * handed to xray-core via `xray.location.asset` (see [org.olcbox.app.vpn.xray.XrayEngine.start]).
 *
 * Downloads are atomic (temp file → rename) so a half-finished or failed download never replaces a
 * good database. Sources are configurable per [org.olcbox.app.data.model.RoutingProfilesState].
 */
object GeoAssetManager {

    private const val TAG = "GeoAssetManager"
    private const val DIR = "xray-assets"
    const val GEOIP_FILE = "geoip.dat"
    const val GEOSITE_FILE = "geosite.dat"

    /** Minimum plausible size of a real .dat (guards against an HTML error page being saved). */
    private const val MIN_DAT_BYTES = 50_000L
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val MAX_REDIRECTS = 5

    fun assetDir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    fun geoipFile(context: Context): File = File(assetDir(context), GEOIP_FILE)
    fun geositeFile(context: Context): File = File(assetDir(context), GEOSITE_FILE)

    /** True when both databases are present and non-trivial in size. */
    fun hasAssets(context: Context): Boolean {
        val ip = geoipFile(context)
        val site = geositeFile(context)
        return ip.isFile && ip.length() >= MIN_DAT_BYTES && site.isFile && site.length() >= MIN_DAT_BYTES
    }

    /** Result of a (re)download attempt; [bytes] is the total fetched on success. */
    data class DownloadResult(val success: Boolean, val bytes: Long = 0L, val error: String? = null)

    /**
     * Downloads both databases from [geoipUrl] / [geositeUrl]. Blank URLs fall back to the defaults.
     * Runs on the IO dispatcher. On any failure the previously stored files are left untouched.
     */
    suspend fun download(context: Context, geoipUrl: String, geositeUrl: String): DownloadResult =
        withContext(Dispatchers.IO) {
            val dir = assetDir(context)
            val ip = geoipUrl.ifBlank { org.olcbox.app.data.model.RoutingProfile.DEFAULT_GEOIP_URL }
            val site = geositeUrl.ifBlank { org.olcbox.app.data.model.RoutingProfile.DEFAULT_GEOSITE_URL }
            try {
                val ipBytes = downloadTo(ip, File(dir, GEOIP_FILE))
                val siteBytes = downloadTo(site, File(dir, GEOSITE_FILE))
                Log.i(TAG, "geo assets updated: geoip=$ipBytes geosite=$siteBytes bytes")
                DownloadResult(success = true, bytes = ipBytes + siteBytes)
            } catch (e: Exception) {
                Log.w(TAG, "geo asset download failed", e)
                DownloadResult(success = false, error = e.message ?: "download failed")
            }
        }

    /**
     * Ensures both databases exist, downloading them only if missing. Used on the connect path so a
     * profile that needs geo data works on first use without an explicit manual update. Returns true
     * if the assets are present afterwards.
     */
    suspend fun ensureAssets(context: Context, geoipUrl: String, geositeUrl: String): Boolean {
        if (hasAssets(context)) return true
        return download(context, geoipUrl, geositeUrl).success
    }

    /** Downloads [urlString] (following redirects) to a temp file, then atomically replaces [target]. */
    private fun downloadTo(urlString: String, target: File): Long {
        var current = urlString
        var conn: HttpURLConnection? = null
        var redirects = 0
        while (true) {
            conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "olcbox-vpn")
                setRequestProperty("Accept", "application/octet-stream")
            }
            val code = conn.responseCode
            if (code in intArrayOf(
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307, 308,
                )
            ) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank() || ++redirects > MAX_REDIRECTS) {
                    throw IllegalStateException("too many redirects for $urlString")
                }
                current = URL(URL(current), location).toString()
                continue
            }
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                throw IllegalStateException("HTTP $code for $current")
            }
            break
        }

        val c = requireNotNull(conn)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        var total = 0L
        try {
            c.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        total += n
                    }
                    output.flush()
                }
            }
        } finally {
            c.disconnect()
        }
        if (total < MIN_DAT_BYTES) {
            tmp.delete()
            throw IllegalStateException("downloaded file too small ($total bytes) — not a .dat?")
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            // Cross-filesystem fallback (same dir, so unlikely): copy then drop the temp.
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return total
    }
}
