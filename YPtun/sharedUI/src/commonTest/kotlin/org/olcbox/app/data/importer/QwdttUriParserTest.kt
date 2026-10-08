package org.olcbox.app.data.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QwdttUriParserTest {

    @Test
    fun parsesUserExampleLink() {
        val uri = "qwdtt://config?name=Finland%20qWDTT&peer=185.174.40.127&hashes=xqHRj13--vSen_gJFj2dZ6Gbn60BVH2R0edBRXH13q8&workers=27&port=9000&pass=Elite1337%21"
        val parsed = QwdttUriParser.parseLine(uri)
        assertNotNull(parsed)
        assertEquals("Finland qWDTT", parsed.name)
        assertEquals("185.174.40.127", parsed.peer)
        assertEquals(56000, parsed.dtlsPort)
        assertEquals("xqHRj13--vSen_gJFj2dZ6Gbn60BVH2R0edBRXH13q8", parsed.hashes)
        assertEquals(27, parsed.workers)
        assertEquals(9000, parsed.listenPort)
        assertEquals("Elite1337!", parsed.password)
    }

    @Test
    fun parsesUserExampleLinkWithMarkdown() {
        val uri = "qwdtt://config?name=Finland%20qWDTT&peer=[185.174.40.127](https://185.174.40.127/)&hashes=xqHRj13--vSen_gJFj2dZ6Gbn60BVH2R0edBRXH13q8&workers=27&port=9000&pass=Elite1337%21"
        val parsed = QwdttUriParser.parseLine(uri)
        assertNotNull(parsed)
        assertEquals("Finland qWDTT", parsed.name)
        assertEquals("185.174.40.127", parsed.peer)
        assertEquals(56000, parsed.dtlsPort)
        assertEquals("Elite1337!", parsed.password)
    }

    @Test
    fun parsesQwdttUri() {
        val uri = "qwdtt://config?name=My+Server&peer=203.0.113.10&hashes=vkhash123&workers=9&port=9000&pass=secretpass"
        val parsed = QwdttUriParser.parseLine(uri)
        assertNotNull(parsed)
        assertEquals("My Server", parsed.name)
        assertEquals("203.0.113.10", parsed.peer)
        assertEquals(56000, parsed.dtlsPort)
        assertEquals("vkhash123", parsed.hashes)
        assertEquals(9, parsed.workers)
        assertEquals(9000, parsed.listenPort)
        assertEquals("secretpass", parsed.password)
    }

    @Test
    fun parsesQwdttUriWithMultipleHashes() {
        val uri = "qwdtt://config?peer=203.0.113.10&hashes=hash1,hash2,hash3,hash4&pass=p123"
        val parsed = QwdttUriParser.parseLine(uri)
        assertNotNull(parsed)
        assertEquals("hash1\nhash2\nhash3\nhash4", parsed.hashes)
    }

    @Test
    fun parsesQwdttUriWithHostPortAndDtlsPort() {
        val uri = "qwdtt://config?peer=vpn.example.com:56005&dtls_port=56005&pass=p123"
        val parsed = QwdttUriParser.parseLine(uri)
        assertNotNull(parsed)
        assertEquals("vpn.example.com", parsed.peer)
        assertEquals(56005, parsed.dtlsPort)
        assertEquals("p123", parsed.password)
    }

    @Test
    fun parsesWdttLegacyUri() {
        val uri = "wdtt://203.0.113.50:56000:56001:9000:myPass:callHashPart1:callHashPart2"
        val parsed = QwdttUriParser.parseLine(uri)
        assertNotNull(parsed)
        assertEquals("WDTT 203.0.113.50", parsed.name)
        assertEquals("203.0.113.50", parsed.peer)
        assertEquals(56000, parsed.dtlsPort)
        assertEquals(9000, parsed.listenPort)
        assertEquals("myPass", parsed.password)
        assertEquals("callHashPart1:callHashPart2", parsed.hashes)
    }

    @Test
    fun parsesWdttQueryUri() {
        val uri = "wdtt://config?name=Finland+WDTT&peer=185.174.40.127&hashes=hash123&pass=pass321"
        val parsed = QwdttUriParser.parseLine(uri)
        assertNotNull(parsed)
        assertEquals("Finland WDTT", parsed.name)
        assertEquals("185.174.40.127", parsed.peer)
        assertEquals("hash123", parsed.hashes)
        assertEquals("pass321", parsed.password)
    }

    @Test
    fun parsesQwdttJsonObject() {
        val json = """
            {
              "name": "qWDTT - 203.0.113.88",
              "peer": "203.0.113.88",
              "vkHashes": "entry_hash_abc",
              "workersPerHash": 9,
              "listenPort": 9000,
              "password": "bot_generated_pass"
            }
        """.trimIndent()
        val list = QwdttUriParser.parseJson(json)
        assertEquals(1, list.size)
        val parsed = list[0]
        assertEquals("qWDTT - 203.0.113.88", parsed.name)
        assertEquals("203.0.113.88", parsed.peer)
        assertEquals("entry_hash_abc", parsed.hashes)
        assertEquals(9, parsed.workers)
        assertEquals(9000, parsed.listenPort)
        assertEquals("bot_generated_pass", parsed.password)
    }

    @Test
    fun parsesQwdttSubscriptionEnvelope() {
        val json = """
            {
              "subscriptionName": "Best qWDTT",
              "profiles": [
                {
                  "name": "Node 1",
                  "peer": "198.51.100.1",
                  "hashes": "h1",
                  "password": "p1"
                },
                {
                  "name": "Node 2",
                  "peer": "198.51.100.2",
                  "hashes": "h2",
                  "password": "p2"
                }
              ]
            }
        """.trimIndent()
        val list = QwdttUriParser.parseJson(json)
        assertEquals(2, list.size)
        assertEquals("Node 1", list[0].name)
        assertEquals("198.51.100.1", list[0].peer)
        assertEquals("h1", list[0].hashes)
        assertEquals("Node 2", list[1].name)
        assertEquals("198.51.100.2", list[1].peer)
    }

    @Test
    fun rejectsNonQwdtt() {
        assertNull(QwdttUriParser.parseLine("vless://whatever"))
        assertNull(QwdttUriParser.parseLine("freeturn://vk?tcp<mode=udp>@1.2.3.4:56000#key$name"))
        assertTrue(QwdttUriParser.parseJson("""{"outbounds": []}""").isEmpty())
    }
}
