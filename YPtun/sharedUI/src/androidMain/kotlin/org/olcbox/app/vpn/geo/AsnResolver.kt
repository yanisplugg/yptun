package org.olcbox.app.vpn.geo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves ASN routing selectors (`asn:13335`) to the operator's CIDR list at config-build time, so
 * the cores only ever see ordinary IP ranges (see [org.olcbox.app.data.model.Asn]). Prefixes are
 * fetched on demand per ASN from the ipverse `asn-ip` dataset (one tiny aggregated file per ASN, not
 * a giant bundled database) and cached to a private app dir, so each ASN is downloaded at most once.
 *
 * Every failure degrades gracefully: an ASN that can't be fetched is simply omitted from the returned
 * map, so [org.olcbox.app.data.model.Asn.expand] drops it and the rest of the rule still applies — the
 * connection is never blocked on this.
 */
object AsnResolver {

    private const val TAG = "AsnResolver"
    private const val DIR = "asn-cache"

    /** ipverse aggregated per-ASN prefix lists: `<base>/as/<n>/ipv4-aggregated.txt` (+ ipv6). */
    const val DEFAULT_BASE = "https://raw.githubusercontent.com/ipverse/asn-ip/master"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 5
    /** Don't let a pathological ASN file blow up memory/the config. */
    private const val MAX_CIDRS_PER_ASN = 20_000

    // Process-lifetime memo so repeated build passes in one session never re-read disk or refetch.
    private val memory = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    private fun cacheDir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /**
     * Returns a map ASN-number → CIDRs for every entry in [asns] that resolves (cache → disk →
     * network). ASNs that don't resolve are absent from the result. Runs on IO.
     */
    suspend fun ensure(
        context: Context,
        asns: Set<String>,
        baseUrl: String = DEFAULT_BASE,
    ): Map<String, List<String>> = withContext(Dispatchers.IO) {
        if (asns.isEmpty()) return@withContext emptyMap()
        val base = baseUrl.ifBlank { DEFAULT_BASE }.trimEnd('/')
        val dir = cacheDir(context)
        val out = HashMap<String, List<String>>()
        for (raw in asns) {
            val n = raw.trim().filter(Char::isDigit)
            if (n.isEmpty()) continue
            memory[n]?.let { out[n] = it; continue }

            val cacheFile = File(dir, "asn-$n.txt")
            val cached = runCatching {
                if (cacheFile.isFile && cacheFile.length() > 0) cacheFile.readLines().mapNotNull(::cleanCidr) else null
            }.getOrNull()?.takeIf { it.isNotEmpty() }

            val cidrs = cached ?: runCatching { fetch(base, n) }.getOrNull()?.also { fetched ->
                if (fetched.isNotEmpty()) runCatching { cacheFile.writeText(fetched.joinToString("\n")) }
            }

            if (!cidrs.isNullOrEmpty()) {
                val capped = cidrs.take(MAX_CIDRS_PER_ASN)
                memory[n] = capped
                out[n] = capped
            } else {
                Log.w(TAG, "ASN $n: no prefixes resolved — skipping (rule still applies without it)")
            }
        }
        out
    }

    /** Fetches IPv4 + IPv6 aggregated prefixes for ASN [n]; either may be empty. */
    private fun fetch(base: String, n: String): List<String> {
        val v4 = runCatching { download("$base/as/$n/ipv4-aggregated.txt") }.getOrDefault(emptyList())
        val v6 = runCatching { download("$base/as/$n/ipv6-aggregated.txt") }.getOrDefault(emptyList())
        val all = (v4 + v6).distinct()
        if (all.isNotEmpty()) Log.i(TAG, "ASN $n resolved to ${all.size} prefixes")
        return all
    }

    /** Downloads a newline-separated prefix list (following redirects) and returns the clean CIDRs. */
    private fun download(urlString: String): List<String> {
        var current = urlString
        var redirects = 0
        while (true) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "olcbox-vpn")
            }
            val code = conn.responseCode
            if (code in intArrayOf(
                    HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER, 307, 308,
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
            return conn.inputStream.use { it.bufferedReader().readLines() }.mapNotNull(::cleanCidr)
        }
    }

    /** Keeps only plausible CIDR lines (skips blanks and `#` comments in the source files). */
    private fun cleanCidr(line: String): String? {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#")) return null
        // A CIDR has a slash and an IPv4 dot or IPv6 colon.
        return if (t.contains('/') && (t.contains('.') || t.contains(':'))) t else null
    }
}
