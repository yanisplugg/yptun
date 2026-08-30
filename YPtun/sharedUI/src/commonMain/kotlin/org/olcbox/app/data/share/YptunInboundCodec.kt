package org.olcbox.app.data.share

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import org.olcbox.app.data.model.LocationConfig

/**
 * The app's own universal share format for a single inbound/location:
 *
 *     yptun://inbound?v=1&d=<base64url(JSON of the full LocationConfig)>
 *
 * Unlike [ConfigShareService.olcRtcUri] / [ShareLinkComposer] (which emit engine-specific links that
 * only carry the bare proxy), this round-trips the ENTIRE [LocationConfig] — engine, transport, the
 * proxy/AWG/VK-TURN outbound, and every per-location toggle — so a shared location is restored
 * byte-for-byte. Parsed back by [parse] (wired into the importer) for paste / QR-scan / file import.
 */
object YptunInboundCodec {
    const val PREFIX = "yptun://inbound"

    // encodeDefaults=false keeps the link short (omitted fields fall back to their defaults on
    // decode); ignoreUnknownKeys lets a newer sender's extra fields be dropped instead of failing.
    private val json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
        isLenient = true
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun compose(config: LocationConfig): String {
        val payload = json.encodeToString(LocationConfig.serializer(), config.normalized())
            .encodeToByteArray()
        // Prefer a DEFLATE-compressed link (`c=`) when it's actually smaller — keeps large configs
        // (e.g. an AmneziaWG INI) short enough to fit in a QR code. Falls back to the plain `d=` form
        // when compression isn't available (Apple targets) or doesn't help.
        val compressed = deflateOrNull(payload)
        if (compressed != null && compressed.size < payload.size) {
            val c = Base64.UrlSafe.encode(compressed).trimEnd('=')
            return "$PREFIX?v=2&c=$c"
        }
        val b64 = Base64.UrlSafe.encode(payload).trimEnd('=')
        return "$PREFIX?v=1&d=$b64"
    }

    /** Decodes a single `yptun://inbound…` link back into a [LocationConfig], or null if it isn't one. */
    @OptIn(ExperimentalEncodingApi::class)
    fun parse(uri: String): LocationConfig? {
        val t = uri.trim()
        if (!t.startsWith(PREFIX)) return null
        val extracted = extractData(t) ?: return null
        // A long link routinely arrives wrapped: chat clients, QR overlays and the app's own share
        // sheet all break it across lines, and Base64 decoding throws on the embedded whitespace.
        val payload = extracted.value.filterNot { it.isWhitespace() }
        val raw = runCatching { Base64.UrlSafe.decode(repad(payload)) }.getOrNull() ?: return null
        val bytes = if (extracted.compressed) inflateOrNull(raw) ?: return null else raw
        val text = bytes.decodeToString()
        return runCatching { json.decodeFromString(LocationConfig.serializer(), text) }
            .getOrNull()
            ?.normalized()
    }

    /** A base64 payload plus whether it is DEFLATE-compressed (the `c=` param) or plain (`d=`). */
    private data class ExtractedData(val value: String, val compressed: Boolean)

    /** Pulls the base64 payload from the `c=` (compressed), `d`/`data` (plain) params, or `#<payload>`. */
    private fun extractData(uri: String): ExtractedData? {
        val afterScheme = uri.removePrefix(PREFIX)
        // Query form: ?v=2&c=XXXX or ?v=1&d=XXXX  (params split on & / ;)
        val q = afterScheme.substringAfter('?', "").substringBefore('#')
        if (q.isNotEmpty()) {
            q.split('&', ';').forEach { pair ->
                val k = pair.substringBefore('=')
                val v = pair.substringAfter('=', "")
                if (k == "c" && v.isNotBlank()) return ExtractedData(v, compressed = true)
                if ((k == "d" || k == "data") && v.isNotBlank()) return ExtractedData(v, compressed = false)
            }
        }
        // Fragment fallback: #XXXX (always plain)
        val frag = afterScheme.substringAfter('#', "")
        return frag.takeIf { it.isNotBlank() }?.let { ExtractedData(it, compressed = false) }
    }

    /** Restore base64 padding so both padded and unpadded payloads decode. */
    private fun repad(s: String): String {
        val rem = s.length % 4
        return if (rem == 0) s else s + "=".repeat(4 - rem)
    }
}
