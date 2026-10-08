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
                if (chunk != null && (chunk.contains("://") || chunk.trim().startsWith("{") || chunk.trim().startsWith("["))) {
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
            val decoded = runCatching {
                val bytes = codec.decode(pad(compact))
                bytes.decodeToString(throwOnInvalidSequence = true)
            }.getOrNull()
            if (!decoded.isNullOrBlank() && decoded.none { it == '\uFFFD' }) {
                return decoded
            }
        }
        return null
    }

    /**
     * If [text] is encoded in base64 (either `base64:<payload>` or raw base64 chars),
     * decodes it into a UTF-8 string; otherwise returns [text] untouched.
     *
     * A bare (unprefixed) value is only taken as base64 when the result reads like a real title or
     * announce ([looksLikeHumanText]): plain Latin names such as "Cloud195" or "Ultra142" are valid
     * base64 too and used to turn into garbage ("Z.w_y", "R[kk^6") — about 1 name in 500.
     */
    fun decodeIfBase64(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed
        val prefixed = trimmed.startsWith("base64:", ignoreCase = true)
        val payload = if (prefixed) {
            trimmed.substring(7).trim()
        } else {
            trimmed
        }
        val isPadded = payload.endsWith("=")
        val isB64Candidate = prefixed ||
            (payload.length >= 4 && payload.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '=' || it == '-' || it == '_' || it.isWhitespace() })
        if (isB64Candidate) {
            val decoded = decodeBase64Chunk(payload)
            if (decoded != null && decoded.isNotBlank() && decoded.all { it.code >= 32 || it == '\n' || it == '\r' || it == '\t' } &&
                (prefixed || isPadded || looksLikeHumanText(decoded))
            ) {
                return decoded.trim()
            }
        }
        return trimmed
    }

    /**
     * Whether [text], decoded from a bare base64-looking value, is a plausible subscription title or
     * announce. Panels base64 a header so non-Latin-1 text survives HTTP, so real input carries
     * spaces, two or more letters of ONE non-Latin script (Cyrillic, CJK, …) or an emoji; the
     * accidental decode of a Latin word is short, unspaced and mixes odd scripts and symbols.
     */
    private fun looksLikeHumanText(text: String): Boolean {
        var hasSpace = false
        var script = 0
        var letters = 0
        var symbols = 0
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                val cp = 0x10000 + ((ch.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00)
                if (cp !in 0x1F000..0x1FAFF) return false // only emoji live up there in a title
                symbols++
                i += 2
                continue
            }
            i++
            val code = ch.code
            when {
                ch == '\n' || ch == '\r' || ch == '\t' -> Unit
                code < 32 || code in 0x7F..0x9F -> return false
                ch == ' ' -> hasSpace = true
                code < 0x80 -> if (!ch.isLetterOrDigit() && ch !in HUMAN_PUNCTUATION) return false
                ch.isLetter() -> {
                    val group = scriptGroup(code)
                    if (group == 0 || (script != 0 && script != group)) return false
                    script = group
                    letters++
                }
                ch.isDigit() || ch in HUMAN_PUNCTUATION -> Unit
                code >= 0x2000 && ch.category == CharCategory.OTHER_SYMBOL -> symbols++
                code >= 0x2000 && ch.category == CharCategory.CURRENCY_SYMBOL -> Unit
                code == 0xFE0F || code == 0x200D || code == 0x20E3 -> Unit // emoji presentation / ZWJ / keycap
                else -> return false
            }
        }
        return hasSpace || letters >= 2 || symbols >= 1
    }

    private fun scriptGroup(code: Int): Int = when (code) {
        in 0x00C0..0x00FF -> 1 // Latin-1 letters (é, ü)
        in 0x0370..0x03FF -> 2 // Greek
        in 0x0400..0x052F -> 3 // Cyrillic
        in 0x0600..0x06FF -> 4 // Arabic
        in 0x3040..0x30FF, in 0x3400..0x9FFF, in 0xAC00..0xD7AF -> 5 // kana, CJK, Hangul
        else -> 0
    }

    private const val HUMAN_PUNCTUATION = " .,!?-_()'\":;/@#&+%*|~=«»—–№…"

    private fun pad(value: String): String {
        val remainder = value.length % 4
        return if (remainder == 0) value else value + "=".repeat(4 - remainder)
    }
}
