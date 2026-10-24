package org.olcbox.app.data.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubscriptionCryptoTest {
    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** RFC 8439 section 2.4.2 (counter 1). */
    @Test
    fun chacha20MatchesRfc8439() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = "000000000000004a00000000".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val text = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."
        assertEquals(
            "6e2e359a2568f98041ba0728dd0d6981e97e7aec1d4360c20a27afccfd9fae0bf91b65c5524733ab8f593dabcd62b3571639d624e65152ab" +
                "8f530c359f0861d807ca0dbf500d6a6156a38e088a22b65e52bc514d16ccf806818ce91ab77937365af90bbf74a35be6b40b8eedf2785e42874d",
            hex(SubscriptionCrypto.chacha20(key, nonce, 1u, text.encodeToByteArray())),
        )
    }

    /** The string comes from the panel's own subcrypt.encrypt (tests/test_panel.py checks the same value). */
    @Test
    fun decryptsWhatThePanelProduces() {
        val enc = "yptun-enc://v1/iQNHVid_1bM4ILvOVmPtCjZCobO8WBANIqvem3QJJKmnRw"
        assertEquals("openflux://v1/test", SubscriptionCrypto.decrypt(enc))
        assertNull(SubscriptionCrypto.decrypt("yptun-enc://v1/AAAA"))
        assertNull(SubscriptionCrypto.decrypt("vless://x"))
    }

    @Test
    fun decryptLinesKeepsComments() {
        val body = "yptun-enc://v1/iQNHVid_1bM4ILvOVmPtCjZCobO8WBANIqvem3QJJKmnRw\n#order:A\n"
        val d = SubscriptionCrypto.decryptLines(body)
        assertTrue(d.encrypted)
        assertEquals("openflux://v1/test\n#order:A\n", d.text)
        val plain = SubscriptionCrypto.decryptLines("vless://x\n")
        assertFalse(plain.encrypted)
        assertEquals("vless://x\n", plain.text)
    }
}
