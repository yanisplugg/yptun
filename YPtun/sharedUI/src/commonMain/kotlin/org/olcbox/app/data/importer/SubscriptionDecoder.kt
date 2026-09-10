package org.olcbox.app.data.importer

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns a subscription body into a flat list of share-link strings. Accepts:
 *  - a JSON object exposing a "links"/"ssConfLinks" string array (e.g. Remnawave panels),
 *  - a base64-encoded blob of newline-delimited links (standard or url-safe),
 *  - a raw newline-delimited list.
 */
internal object SubscriptionDecoder {

    /**
     * The body as PLAIN link text: a base64 blob is decoded and a JSON "links" array is flattened to
     * one link per line; a plain body is returned untouched (its `#`/`##` metadata lines matter to the
     * olcrtc parser). Every link family must look at the same text, otherwise a base64 subscription
     * hides olcrtc:// links from the parser that checks for them and a plain one hides nothing —
     * which is exactly how mixed subscriptions lost half their servers.
     */
    fun toLinkText(body: String): String {
        val trimmed = body.trim()
        extractJsonLinks(trimmed)?.let { return it.joinToString(separator = "\n") }
        return maybeBase64Decode(trimmed) ?: body
    }

    fun toLinks(body: String): List<String> {
        val trimmed = body.trim()
        val raw = extractJsonLinks(trimmed)
            ?: (maybeBase64Decode(trimmed) ?: trimmed).split('\n', '\r')
        return raw.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun extractJsonLinks(body: String): List<String>? {
        if (!body.startsWith("{")) return null
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val result = mutableListOf<String>()
        for (key in listOf("links", "ssConfLinks")) {
            (root[key] as? JsonArray)?.forEach { element ->
                runCatching { element.jsonPrimitive.content }.getOrNull()?.let { result.add(it) }
            }
        }
        return result.takeIf { it.isNotEmpty() }
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun maybeBase64Decode(value: String): String? {
        if (value.contains("://")) return null
        val compact = value.filterNot { it == '\n' || it == '\r' || it == ' ' }
        if (compact.length < 8) return null
        for (codec in listOf(Base64.Default, Base64.UrlSafe, Base64.Mime)) {
            val text = runCatching { codec.decode(pad(compact)).decodeToString() }.getOrNull()
            if (text != null && text.contains("://")) return text
        }
        return null
    }

    /** Decode a (possibly unpadded, possibly url-safe) base64 chunk to a string, or null. */
    @OptIn(ExperimentalEncodingApi::class)
    fun decodeBase64Chunk(value: String): String? {
        val compact = value.trim().filterNot { it == '\n' || it == '\r' || it == ' ' }
        if (compact.isEmpty()) return null
        for (codec in listOf(Base64.UrlSafe, Base64.Default, Base64.Mime)) {
            runCatching { codec.decode(pad(compact)).decodeToString() }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun pad(value: String): String {
        val remainder = value.length % 4
        return if (remainder == 0) value else value + "=".repeat(4 - remainder)
    }
}
