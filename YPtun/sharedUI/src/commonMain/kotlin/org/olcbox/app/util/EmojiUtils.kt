package org.olcbox.app.util

/**
 * Splits a leading emoji (if any) from the rest of the name.
 *
 * Handles multi-codepoint emoji correctly — especially country flags, which are a pair of
 * regional-indicator symbols (e.g. 🇫🇮 = U+1F1EB U+1F1EE). Also consumes ZWJ sequences,
 * variation selectors, skin-tone modifiers and keycaps so the whole glyph stays together.
 */
fun parseEmojiAndName(rawName: String, defaultEmoji: String = ""): Pair<String, String> {
    val trimmed = rawName.trim()
    if (trimmed.isEmpty()) return defaultEmoji to ""

    if (!isEmojiCodePoint(codePointAt(trimmed, 0))) {
        return defaultEmoji to ""
    }

    var end = 0
    val length = trimmed.length
    while (end < length) {
        val cp = codePointAt(trimmed, end)
        if (!isEmojiCodePoint(cp) && !isEmojiJoiner(cp)) break
        end += if (cp >= 0x10000) 2 else 1
    }

    if (end == 0) return defaultEmoji to ""
    val emoji = trimmed.substring(0, end)
    val name = trimmed.substring(end).trim()
    return emoji to name
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
