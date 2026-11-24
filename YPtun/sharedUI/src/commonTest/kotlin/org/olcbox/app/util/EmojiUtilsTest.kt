package org.olcbox.app.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EmojiUtilsTest {

    @Test
    fun countryCodeToEmojiFlagConvertsIsoAlpha2() {
        assertEquals("🇺🇸", countryCodeToEmojiFlag("US"))
        assertEquals("🇺🇸", countryCodeToEmojiFlag("us"))
        assertEquals("🇷🇺", countryCodeToEmojiFlag("RU"))
        assertEquals("🇫🇮", countryCodeToEmojiFlag("FI"))
        assertEquals("🇩🇪", countryCodeToEmojiFlag("DE"))
        assertEquals("🇳🇱", countryCodeToEmojiFlag("NL"))
        assertNull(countryCodeToEmojiFlag(null))
        assertNull(countryCodeToEmojiFlag(""))
        assertNull(countryCodeToEmojiFlag("U"))
        assertNull(countryCodeToEmojiFlag("USA"))
        assertNull(countryCodeToEmojiFlag("12"))
    }

    @Test
    fun parseEmojiAndNameExtractsFlagAndName() {
        val (emoji1, name1) = parseEmojiAndName("🇺🇸 New York")
        assertEquals("🇺🇸", emoji1)
        assertEquals("New York", name1)

        val (emoji2, name2) = parseEmojiAndName("🇫🇮Helsinki")
        assertEquals("🇫🇮", emoji2)
        assertEquals("Helsinki", name2)
    }

    @Test
    fun parseEmojiAndNameExtractsCountryPrefixTags() {
        val (emoji1, name1) = parseEmojiAndName("[US] Chicago Server")
        assertEquals("🇺🇸", emoji1)
        assertEquals("Chicago Server", name1)

        val (emoji2, name2) = parseEmojiAndName("NL - Amsterdam")
        assertEquals("🇳🇱", emoji2)
        assertEquals("Amsterdam", name2)

        val (emoji3, name3) = parseEmojiAndName("DE: Frankfurt")
        assertEquals("🇩🇪", emoji3)
        assertEquals("Frankfurt", name3)
    }

    @Test
    fun parseEmojiAndNamePreservesPlainName() {
        val (emoji1, name1) = parseEmojiAndName("Plain Server")
        assertEquals("", emoji1)
        assertEquals("Plain Server", name1)

        val (emoji2, name2) = parseEmojiAndName("Plain Server", defaultEmoji = "🌐")
        assertEquals("🌐", emoji2)
        assertEquals("Plain Server", name2)
    }
}
