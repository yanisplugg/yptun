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
        val b64 = Base64.UrlSafe.encode(payload.encodeToByteArray()).trimEnd('=')
        return "$PREFIX?v=1&d=$b64"
    }

    /** Decodes a single `yptun://inbound…` link back into a [LocationConfig], or null if it isn't one. */
    @OptIn(ExperimentalEncodingApi::class)
    fun parse(uri: String): LocationConfig? {
        val t = uri.trim()
        if (!t.startsWith(PREFIX)) return null
        val data = extractData(t) ?: return null
        val bytes = runCatching { Base64.UrlSafe.decode(repad(data)) }.getOrNull() ?: return null
        val text = bytes.decodeToString()
        return runCatching { json.decodeFromString(LocationConfig.serializer(), text) }
            .getOrNull()
            ?.normalized()
    }

    /** Pulls the base64 payload from the `d`/`data` query param, or a bare `#<payload>` fragment. */
    private fun extractData(uri: String): String? {
        val afterScheme = uri.removePrefix(PREFIX)
        // Query form: ?v=1&d=XXXX  (params split on & / ;)
        val q = afterScheme.substringAfter('?', "").substringBefore('#')
        if (q.isNotEmpty()) {
            q.split('&', ';').forEach { pair ->
                val k = pair.substringBefore('=')
                val v = pair.substringAfter('=', "")
                if ((k == "d" || k == "data") && v.isNotBlank()) return v
            }
        }
        // Fragment fallback: #XXXX
        val frag = afterScheme.substringAfter('#', "")
        return frag.takeIf { it.isNotBlank() }
    }

    /** Restore base64 padding so both padded and unpadded payloads decode. */
    private fun repad(s: String): String {
        val rem = s.length % 4
        return if (rem == 0) s else s + "=".repeat(4 - rem)
    }
}
