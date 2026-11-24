package org.olcbox.app.util

/**
 * Converts a 2-letter ISO 3166-1 alpha-2 country code (e.g. "US", "RU", "DE", "FI")
 * into a standard 2-codepoint regional-indicator emoji flag string (🇺🇸, 🇷🇺, 🇩🇪, 🇫🇮).
 */
fun countryCodeToEmojiFlag(countryCode: String?): String? {
    if (countryCode == null) return null
    val clean = countryCode.trim().uppercase()
    if (clean.length != 2 || !clean.all { it in 'A'..'Z' }) return null
    val hi1 = 0xD83C.toChar()
    val lo1 = (0xDDE6 + (clean[0] - 'A')).toChar()
    val hi2 = 0xD83C.toChar()
    val lo2 = (0xDDE6 + (clean[1] - 'A')).toChar()
    return "$hi1$lo1$hi2$lo2"
}

private val BRACKET_COUNTRY_REGEX = Regex("""^[\[\(]([A-Za-z]{2})[\]\)]\s*(.*)$""")
private val PREFIX_COUNTRY_REGEX = Regex("""^([A-Za-z]{2})\s*[-|:]\s*(.*)$""")

/**
 * Splits a leading emoji (if any) or leading country tag (e.g. `[US]`, `FI - `) from the rest of the name.
 *
 * Handles multi-codepoint emoji correctly — especially country flags, which are a pair of
 * regional-indicator symbols (e.g. 🇫🇮 = U+1F1EB U+1F1EE). Also consumes ZWJ sequences,
 * variation selectors, skin-tone modifiers and keycaps so the whole glyph stays together.
 */
fun parseEmojiAndName(rawName: String, defaultEmoji: String = ""): Pair<String, String> {
    val trimmed = rawName.trim()
    if (trimmed.isEmpty()) return defaultEmoji to ""

    if (!isEmojiCodePoint(codePointAt(trimmed, 0))) {
        val bracketMatch = BRACKET_COUNTRY_REGEX.find(trimmed)
        if (bracketMatch != null) {
            val flag = countryCodeToEmojiFlag(bracketMatch.groupValues[1])
            if (flag != null) {
                val rest = bracketMatch.groupValues[2].trim()
                return flag to rest.ifBlank { trimmed }
            }
        }
        val prefixMatch = PREFIX_COUNTRY_REGEX.find(trimmed)
        if (prefixMatch != null) {
            val flag = countryCodeToEmojiFlag(prefixMatch.groupValues[1])
            if (flag != null) {
                val rest = prefixMatch.groupValues[2].trim()
                return flag to rest.ifBlank { trimmed }
            }
        }
        return defaultEmoji to trimmed
    }

    var end = 0
    val length = trimmed.length
    while (end < length) {
        val cp = codePointAt(trimmed, end)
        if (!isEmojiCodePoint(cp) && !isEmojiJoiner(cp)) break
        end += if (cp >= 0x10000) 2 else 1
    }

    if (end == 0) return defaultEmoji to trimmed
    val emoji = trimmed.substring(0, end)
    val name = trimmed.substring(end).trim()
    return emoji to (name.ifBlank { trimmed })
}


// String.codePointAt is JVM-only; this is its surrogate-pair logic for every target.
private fun codePointAt(s: String, i: Int): Int {
    val hi = s[i]
    if (hi.isHighSurrogate() && i + 1 < s.length) {
        val lo = s[i + 1]
        if (lo.isLowSurrogate()) return ((hi.code - 0xD800) shl 10) + (lo.code - 0xDC00) + 0x10000
    }
    return hi.code
}

private fun isEmojiCodePoint(cp: Int): Boolean {
    return cp in 0x1F1E6..0x1F1FF || // regional indicators (flags)
        cp in 0x1F300..0x1FAFF ||    // main emoji blocks
        cp in 0x2600..0x27BF ||      // misc symbols + dingbats
        cp in 0x2B00..0x2BFF ||      // stars, arrows
        cp in 0x1F000..0x1F0FF ||    // mahjong/dominoes/cards
        cp == 0x2122 || cp == 0x2139 ||
        cp in 0x2190..0x21FF ||      // arrows
        cp in 0x2300..0x23FF         // technical (⌚⏰ etc.)
}

private fun isEmojiJoiner(cp: Int): Boolean {
    return cp == 0x200D ||           // zero-width joiner
        cp == 0xFE0F || cp == 0xFE0E || // variation selectors
        cp == 0x20E3 ||              // combining keycap
        cp in 0x1F3FB..0x1F3FF       // skin-tone modifiers
}
