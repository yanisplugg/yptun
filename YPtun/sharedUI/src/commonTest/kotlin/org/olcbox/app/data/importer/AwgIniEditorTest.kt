package org.olcbox.app.data.importer

import org.olcbox.app.data.importer.AwgIniEditor.Section
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AwgIniEditorTest {

    private val ini = """
        [Interface]
        PrivateKey = AAA=
        Address = 10.0.0.2/32
        jc = 4
        # note
        S1 = 0

        [Peer]
        PublicKey = BBB=
        Endpoint = 1.2.3.4:51820
        AllowedIPs = 0.0.0.0/0
    """.trimIndent()

    @Test
    fun readsValuesCaseInsensitivelyPerSection() {
        assertEquals("4", AwgIniEditor.get(ini, "Jc"))
        assertEquals("0", AwgIniEditor.get(ini, "S1"))
        assertEquals("", AwgIniEditor.get(ini, "Jmax"))
        // The same key name in the other section is a different parameter.
        assertEquals("", AwgIniEditor.get(ini, "Endpoint"))
        assertEquals("1.2.3.4:51820", AwgIniEditor.get(ini, "Endpoint", Section.Peer))
    }

    @Test
    fun replacingKeepsTheKeyCaseAndEverythingElse() {
        val out = AwgIniEditor.set(ini, "Jc", "9")
        assertEquals("9", AwgIniEditor.get(out, "Jc"))
        assertEquals(ini.replace("jc = 4", "jc = 9"), out)
    }

    @Test
    fun newKeyGoesToTheEndOfItsSectionBeforeTheBlankLine() {
        val out = AwgIniEditor.set(ini, "Jmax", "70")
        val lines = out.split("\n")
        val at = lines.indexOf("Jmax = 70")
        assertEquals("S1 = 0", lines[at - 1])
        assertEquals("", lines[at + 1])
        assertEquals("[Peer]", lines[at + 2])
        val peer = AwgIniEditor.set(ini, "PersistentKeepalive", "25", Section.Peer)
        assertEquals("25", AwgIniEditor.get(peer, "PersistentKeepalive", Section.Peer))
        assertEquals("", AwgIniEditor.get(peer, "PersistentKeepalive"))
    }

    @Test
    fun blankValueRemovesTheLineAndMissingKeyStaysUntouched() {
        val out = AwgIniEditor.set(ini, "S1", "")
        assertEquals("", AwgIniEditor.get(out, "S1"))
        assertEquals(ini.replace("S1 = 0\n", ""), out)
        assertEquals(ini, AwgIniEditor.set(ini, "H1", ""))
    }

    @Test
    fun addsAMissingSectionAndNeverBreaksTheLine() {
        val out = AwgIniEditor.set("[Interface]\nPrivateKey = A=", "PersistentKeepalive", "25", Section.Peer)
        assertEquals("25", AwgIniEditor.get(out, "PersistentKeepalive", Section.Peer))
        val multi = AwgIniEditor.set(ini, "I1", "<b 0x01>\n<r 8>")
        assertEquals("<b 0x01><r 8>", AwgIniEditor.get(multi, "I1"))
    }

    @Test
    fun protocolIsAwgOnlyWhenAnObfuscationParameterIsSet() {
        assertTrue(AwgIniEditor.isAwg(ini))
        val plain = AwgIniEditor.toWireGuard(ini)
        assertFalse(AwgIniEditor.isAwg(plain))
        assertEquals("0.0.0.0/0", AwgIniEditor.get(plain, "AllowedIPs", Section.Peer))
        assertEquals("AAA=", AwgIniEditor.get(plain, "PrivateKey"))
        // Back to AmneziaWG: the seed parameters appear again.
        assertTrue(AwgIniEditor.isAwg(AwgIniEditor.toAmneziaWg(plain)))
        assertEquals(listOf("Jc" to "4", "S1" to "0"), AwgIniEditor.obfuscation(ini).take(2))
    }

    @Test
    fun templateNeedsKeysAndEndpointBeforeItIsUsable() {
        assertEquals(listOf("PrivateKey", "PublicKey", "Endpoint"), AwgIniEditor.missing(AwgIniEditor.skeleton(awg = false)))
        assertFalse(AwgIniEditor.isAwg(AwgIniEditor.skeleton(awg = false)))
        assertTrue(AwgIniEditor.isAwg(AwgIniEditor.skeleton(awg = true)))
        assertEquals(emptyList(), AwgIniEditor.missing(ini.replace("Address = 10.0.0.2/32", "Address = 10.0.0.2/32").let {
            AwgIniEditor.set(it, "PublicKey", "BBB=", Section.Peer)
        }))
    }

    @Test
    fun endpointSplitsHostAndPort() {
        assertEquals("1.2.3.4" to 51820, AwgIniEditor.endpoint(ini))
        assertEquals("2001:db8::1" to 443, AwgIniEditor.endpoint("[Peer]\nEndpoint = [2001:db8::1]:443"))
        assertEquals("" to 0, AwgIniEditor.endpoint("[Interface]\nPrivateKey = A="))
    }

    @Test
    fun fieldsFilterWhatTheyAccept() {
        assertEquals("120", AwgIniEditor.filter("1a2-0", AwgIniEditor.Kind.Number))
        assertEquals("1-2", AwgIniEditor.filter("1a-2", AwgIniEditor.Kind.Range))
        assertEquals("<b 0x1>", AwgIniEditor.filter("<b 0x1>\n", AwgIniEditor.Kind.Text))
    }

    @Test
    fun switchesAreWrittenOnAndGoWithTheObfuscation() {
        val on = AwgIniEditor.set(ini, "RandomTrailers", "on")
        assertEquals("on", AwgIniEditor.get(on, "RandomTrailers"))
        assertTrue(AwgIniEditor.isAwg(on))
        assertEquals("", AwgIniEditor.get(AwgIniEditor.set(on, "RandomTrailers", ""), "RandomTrailers"))
        assertEquals("", AwgIniEditor.get(AwgIniEditor.toWireGuard(AwgIniEditor.set(on, "DisableCookies", "on")), "DisableCookies"))
    }
}
