package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.desktop.DesktopPaths
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Desktop port of Android's `AsnResolver`: turns `asn:13335` routing selectors into the operator's
 * CIDR list at config-build time, so the cores only ever see ordinary IP ranges.
 *
 * Without this the desktop silently DROPPED every `asn:` selector — both cores filter out selectors
 * they can't parse, so an ASN-based rule simply never fired and the profile looked half-applied.
 *
 * Prefixes come from the ipverse `asn-ip` dataset (one small aggregated file per ASN) and are cached
 * under `<appData>/asn-cache/`, so each ASN is fetched at most once. Every failure degrades
 * gracefully: an unresolvable ASN is omitted, the rest of the rule still applies, and a connect is
 * never blocked on this.
 */
internal object JvmAsnResolver {

    private const val DIR = "asn-cache"

    /** ipverse aggregated per-ASN prefix lists: `<base>/as/<n>/ipv4-aggregated.txt` (+ ipv6). */
    const val DEFAULT_BASE = "https://raw.githubusercontent.com/ipverse/asn-ip/master"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 5

    /** Don't let a pathological ASN file blow up memory / the config. */
    private const val MAX_CIDRS_PER_ASN = 20_000

    // Process-lifetime memo so repeated build passes in one session never re-read disk or refetch.
    private val memory = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    private fun cacheDir(): File =
        DesktopPaths.appDataDir().resolve(DIR).toFile().apply { if (!exists()) mkdirs() }

    /** ASN-number → CIDRs for every entry in [asns] that resolves (memo → disk → network). */
    suspend fun ensure(
        asns: Set<String>,
        baseUrl: String = DEFAULT_BASE,
    ): Map<String, List<String>> = withContext(Dispatchers.IO) {
        if (asns.isEmpty()) return@withContext emptyMap()
        val base = baseUrl.ifBlank { DEFAULT_BASE }.trimEnd('/')
        val dir = cacheDir()
        val out = HashMap<String, List<String>>()
        for (raw in asns) {
            val n = raw.trim().filter(Char::isDigit)
            if (n.isEmpty()) continue
            memory[n]?.let { out[n] = it; continue }

            val cacheFile = File(dir, "asn-$n.txt")
            val cached = runCatching {
                if (cacheFile.isFile && cacheFile.length() > 0) {
                    cacheFile.readLines().mapNotNull(::cleanCidr)
                } else {
                    null
                }
            }.getOrNull()?.takeIf { it.isNotEmpty() }

            val cidrs = cached ?: runCatching { fetch(base, n) }.getOrNull()?.also { fetched ->
                if (fetched.isNotEmpty()) runCatching { cacheFile.writeText(fetched.joinToString("\n")) }
            }

            if (!cidrs.isNullOrEmpty()) {
                val capped = cidrs.take(MAX_CIDRS_PER_ASN)
                memory[n] = capped
                out[n] = capped
            }
        }
        out
    }

    /** Fetches IPv4 + IPv6 aggregated prefixes for ASN [n]; either may be empty. */
    private fun fetch(base: String, n: String): List<String> {
        val v4 = runCatching { download("$base/as/$n/ipv4-aggregated.txt") }.getOrDefault(emptyList())
        val v6 = runCatching { download("$base/as/$n/ipv6-aggregated.txt") }.getOrDefault(emptyList())
        return (v4 + v6).distinct()
    }

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
            if (code in 300..399) {
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
        return if (t.contains('/') && (t.contains('.') || t.contains(':'))) t else null
    }
}
