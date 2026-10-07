package org.olcbox.app.data.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.VkTurnConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CsqttUriParserTest {

    @Test
    fun parsesTheWebPanelLinkWithHashes() {
        val link = CsqttUriParser.parse(
            "csqtt://connect?v=2&host=203.0.113.7&peer=46000&password=p%40ss&hashes=aaa+bbb+ccc"
        )!!
        assertEquals("203.0.113.7", link.host)
        assertEquals(46000, link.port)
        assertEquals("p@ss", link.password)
        assertEquals("aaa\nbbb\nccc", link.hashes)
    }

    @Test
    fun parsesTheLinkWithoutHashesAndIgnoresHtmlEscapedAmpersands() {
        val link = CsqttUriParser.parse("csqtt://connect?v=2&amp;host=vpn.example.com&amp;peer=47000&amp;password=secret")!!
        assertEquals("vpn.example.com", link.host)
        assertEquals(47000, link.port)
        assertEquals("", link.hashes)
    }

    @Test
    fun parsesTheLegacyLink() {
        val link = CsqttUriParser.parse("csqtt://secret@203.0.113.7:46001")!!
        assertEquals("203.0.113.7", link.host)
        assertEquals(46001, link.port)
        assertEquals("secret", link.password)
    }

    @Test
    fun rejectsIncompleteAndForeignLinks() {
        assertNull(CsqttUriParser.parse("csqtt://connect?v=2&host=h&peer=46000"))             // no password
        assertNull(CsqttUriParser.parse("csqtt://connect?v=1&host=h&peer=46000&password=p"))  // unknown version
        assertNull(CsqttUriParser.parse("csqtt://connect?v=2&host=h&peer=99999&password=p"))  // bad port
        assertNull(CsqttUriParser.parse("csqtt://connect?v=2&host=h&peer=46000&password=p&hashes="))
        assertNull(CsqttUriParser.parse("qwdtt://config?peer=h&pass=p"))
    }

    @Test
    fun composeRoundTripsAndTheLocationDialsTheRightAddress() {
        val vk = VkTurnConfig(
            core = VkTurnConfig.CORE_CSQTT,
            csqttPeer = "203.0.113.7",
            csqttPort = 46005,
            csqttPassword = "p@ss word".replace(" ", "_"),
            vkLink = "aaa\nbbb",
        )
        val uri = CsqttUriParser.compose(vk)
        val back = CsqttUriParser.parse(uri)!!
        assertEquals("203.0.113.7", back.host)
        assertEquals(46005, back.port)
        assertEquals("p@ss_word", back.password)
        assertEquals("aaa\nbbb", back.hashes)
        assertEquals("203.0.113.7:46005", vk.csqttPeerAddr())
    }

    @Test
    fun peerAddressHandlesPortsAndIpv6() {
        fun addr(peer: String, port: Int = 0) = VkTurnConfig(csqttPeer = peer, csqttPort = port).csqttPeerAddr()
        assertEquals("1.2.3.4:46000", addr("1.2.3.4"))
        assertEquals("1.2.3.4:5000", addr("1.2.3.4:5000"))
        assertEquals("1.2.3.4:6000", addr("1.2.3.4", 6000))
        assertEquals("host.example:46000", addr("host.example"))
        assertEquals("[2001:db8::1]:46000", addr("2001:db8::1"))
        assertEquals("[2001:db8::1]:7000", addr("[2001:db8::1]:7000"))
        assertEquals("", addr(""))
    }

    @Test
    fun coreOptionsCarryEverythingTheBridgeNeeds() {
        val vk = VkTurnConfig(
            core = VkTurnConfig.CORE_CSQTT,
            csqttPeer = "203.0.113.7",
            csqttPassword = "pw",
            vkLink = "aaa\nbbb ccc,ddd",
        )
        val json = Json.parseToJsonElement(vk.csqttCoreOptionsJson("/lib/libcsqtt.so", "127.0.0.1:10810", "dev1")).jsonObject
        fun s(k: String) = json[k]!!.jsonPrimitive.content
        assertEquals("/lib/libcsqtt.so", s("client"))
        assertEquals("127.0.0.1:10810", s("listen"))
        assertEquals("203.0.113.7:46000", s("peer"))
        assertEquals("aaa,bbb,ccc,ddd", s("vk_hashes"))
        assertEquals("72", s("workers")) // 4 hashes × 27, capped at 72
        assertEquals("video", s("obfs"))
        assertEquals("firefox", s("fingerprint"))
        assertEquals("vkcalls", s("vk_auth_mode"))
        assertEquals("dev1", s("device_id"))
    }
}
