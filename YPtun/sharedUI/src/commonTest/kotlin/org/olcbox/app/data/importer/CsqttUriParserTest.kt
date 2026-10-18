package org.olcbox.app.data.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.olcbox.app.data.model.VkTurnConfig

class CsqttUriParserTest {

    @Test
    fun parseV2LinkWithFragmentName() {
        val uri = "csqtt://connect?v=2&host=198.51.100.1&peer=46000&password=secret123&hashes=h1+h2#Frankfurt"
        val link = assertNotNull(CsqttUriParser.parse(uri))
        assertEquals("198.51.100.1", link.host)
        assertEquals(46000, link.port)
        assertEquals("secret123", link.password)
        assertEquals("h1\nh2", link.hashes)
        assertEquals("Frankfurt", link.name)
    }

    @Test
    fun parseV2LinkWithNameParam() {
        val uri = "csqtt://connect?v=2&host=198.51.100.2&peer=46000&password=secret123&name=Amsterdam"
        val link = assertNotNull(CsqttUriParser.parse(uri))
        assertEquals("198.51.100.2", link.host)
        assertEquals(46000, link.port)
        assertEquals("secret123", link.password)
        assertEquals("Amsterdam", link.name)
    }

    @Test
    fun parseLegacyLinkWithFragment() {
        val uri = "csqtt://secret123@198.51.100.3:46000#London"
        val link = assertNotNull(CsqttUriParser.parse(uri))
        assertEquals("198.51.100.3", link.host)
        assertEquals(46000, link.port)
        assertEquals("secret123", link.password)
        assertEquals("London", link.name)
    }

    @Test
    fun extractSubscriptionUrl() {
        val direct = "csqtt://https://example.com/sub/csqtt.txt"
        assertEquals("https://example.com/sub/csqtt.txt", CsqttUriParser.extractSubscriptionUrl(direct))

        val subQuery = "csqtt://sub?url=https%3A%2F%2Fexample.com%2Fsub%2Fcsqtt"
        assertEquals("https://example.com/sub/csqtt", CsqttUriParser.extractSubscriptionUrl(subQuery))

        val normalLink = "csqtt://connect?v=2&host=1.2.3.4&peer=46000&password=pass"
        assertNull(CsqttUriParser.extractSubscriptionUrl(normalLink))
    }

    @Test
    fun composeRoundTrip() {
        val vk = VkTurnConfig(
            core = VkTurnConfig.CORE_CSQTT,
            csqttPeer = "203.0.113.10",
            csqttPort = 46000,
            csqttPassword = "mypassword",
            vkLink = "hash_alpha\nhash_beta"
        )
        val composed = CsqttUriParser.compose(vk, "My Server")
        val parsed = assertNotNull(CsqttUriParser.parse(composed))
        assertEquals("203.0.113.10", parsed.host)
        assertEquals(46000, parsed.port)
        assertEquals("mypassword", parsed.password)
        assertEquals("hash_alpha\nhash_beta", parsed.hashes)
        assertEquals("My Server", parsed.name)
    }
}
