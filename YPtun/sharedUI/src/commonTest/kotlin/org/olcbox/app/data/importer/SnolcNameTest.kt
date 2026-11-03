package org.olcbox.app.data.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import org.olcbox.app.data.model.SnolcConfig

class SnolcNameTest {
    private val key = "ab".repeat(32)

    @Test
    fun flagAndCyrillicNameSurvivesTheFragment() {
        val enc = "%F0%9F%87%B3%F0%9F%87%B1%D0%90%D0%BC%D1%81%D1%82%D0%B5%D1%80%D0%B4%D0%B0%D0%BC%20%28SNOLC%29"
        val parsed = SnolcConfig.parseUri("snolc://195.133.76.133:8444?key=$key#$enc")
        assertEquals("🇳🇱Амстердам (SNOLC)", parsed?.second)
    }
}
