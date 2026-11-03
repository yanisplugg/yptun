package org.olcbox.app.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.olcbox.app.ui.i18n.EnStrings
import org.olcbox.app.ui.i18n.RuStrings

class SettingsSearchTest {
    private val ru = SettingsSearch.index(RuStrings, experimentalUnlocked = false)
    private val en = SettingsSearch.index(EnStrings, experimentalUnlocked = true)

    private fun top(entries: List<SettingsSearchEntry>, q: String) = SettingsSearch.search(entries, q).firstOrNull()?.title

    @Test
    fun findsByTitleStartInsideWordAndKeyword() {
        assertEquals(RuStrings.useMux, top(ru, "mux"))
        assertEquals(RuStrings.energySaverTitle, top(ru, "батарея"))
        assertEquals(RuStrings.dynamicTheme, top(ru, "monet"))
        assertEquals(EnStrings.useFragment, top(en, "fragment"))
    }

    @Test
    fun toleratesATypoAndIgnoresWordOrderAndYo() {
        assertEquals(RuStrings.useMux, top(ru, "мультиплекс"))
        assertEquals(RuStrings.notifPingTitle, top(ru, "пингг"))
        assertTrue(SettingsSearch.search(ru, "темная").any { it.title == RuStrings.lightTheme })
        assertTrue(SettingsSearch.search(ru, "тёмная").any { it.title == RuStrings.lightTheme })
    }

    @Test
    fun everyWordMustMatchAndNothingMatchesGibberish() {
        assertTrue(SettingsSearch.search(ru, "ззззззз").isEmpty())
        assertTrue(SettingsSearch.search(ru, "   ").isEmpty())
        assertTrue(SettingsSearch.search(ru, "mux telegram").isEmpty())
    }

    @Test
    fun experimentalPageOnlyWhenUnlocked() {
        assertTrue(ru.none { it.page == "Experimental" })
        assertTrue(en.any { it.page == "Experimental" })
    }
}
