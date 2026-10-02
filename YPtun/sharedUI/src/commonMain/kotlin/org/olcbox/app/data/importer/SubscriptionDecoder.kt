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
        val decoded = maybeBase64Decode(trimmed)
        if (decoded != null) return decoded
        val links = toLinks(trimmed)
        if (links.isNotEmpty() && (links.size > 1 || links.first() != trimmed)) {
            return links.joinToString(separator = "\n")
        }
        return body
    }

    fun toLinks(body: String): List<String> {
        val trimmed = body.trim()
        extractJsonLinks(trimmed)?.let { return it }

        val decodedBody = maybeBase64Decode(trimmed)
        val sourceText = decodedBody ?: trimmed

        val rawLines = sourceText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        val result = mutableListOf<String>()
        for (line in rawLines) {
            if (line.startsWith("#") || line.startsWith("//")) continue
            if (line.contains("://")) {
                result.add(line)
            } else {
                val decodedChunk = decodeBase64Chunk(line)
                if (decodedChunk != null && decodedChunk.contains("://")) {
                    decodedChunk.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
                        .forEach { result.add(it) }
                } else if (decodedChunk != null && (decodedChunk.trim().startsWith("{") || decodedChunk.trim().startsWith("["))) {
                    result.add(decodedChunk.trim())
                } else {
                    result.add(line)
                }
            }
        }
        return if (result.isNotEmpty()) result else rawLines
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
        val trimmed = value.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return null

        val nonCommentLines = trimmed.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }

        if (nonCommentLines.isEmpty()) return null

        // If every line is already a known scheme link, no need to decode
        if (nonCommentLines.all { it.contains("://") }) return null

        val compact = nonCommentLines.joinToString("").filterNot { it.isWhitespace() }
        if (compact.length >= 8) {
            for (codec in listOf(Base64.Default, Base64.UrlSafe, Base64.Mime)) {
                val text = runCatching { codec.decode(pad(compact)).decodeToString() }.getOrNull()
                if (text != null && (text.contains("://") || text.trim().startsWith("{") || text.trim().startsWith("["))) {
                    return text
                }
            }

            // In case concatenating introduced inner '=' padding from per-line base64 encoding:
            val decodedLines = mutableListOf<String>()
            var anyDecoded = false
            for (line in nonCommentLines) {
                val chunk = decodeBase64Chunk(line)
                if (chunk != null) {
                    decodedLines.add(chunk)
                    anyDecoded = true
                } else if (line.contains("://")) {
                    decodedLines.add(line)
                }
            }
            if (anyDecoded && decodedLines.isNotEmpty()) {
                val joined = decodedLines.joinToString("\n")
                if (joined.contains("://") || joined.trim().startsWith("{") || joined.trim().startsWith("[")) {
                    return joined
                }
            }
        }
        return null
    }

    /** Decode a (possibly unpadded, possibly url-safe) base64 chunk to a string, or null. */
    @OptIn(ExperimentalEncodingApi::class)
    fun decodeBase64Chunk(value: String): String? {
        val compact = value.filterNot { it.isWhitespace() }
        if (compact.length < 4) return null
        for (codec in listOf(Base64.Default, Base64.UrlSafe, Base64.Mime)) {
            val decoded = runCatching { codec.decode(pad(compact)).decodeToString() }.getOrNull()
            if (decoded != null && (decoded.contains("://") || decoded.trim().startsWith("{") || decoded.trim().startsWith("["))) {
                return decoded
            }
        }
        return null
    }

    private fun pad(value: String): String {
        val remainder = value.length % 4
        return if (remainder == 0) value else value + "=".repeat(4 - remainder)
    }
}
