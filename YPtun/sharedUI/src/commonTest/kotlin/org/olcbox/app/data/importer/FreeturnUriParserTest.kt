package org.olcbox.app.data.importer

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FreeturnUriParserTest {

    private val wgConf = """
        [Interface]
        PrivateKey = QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVoxMjM0NTY3OD0=
        Address = 10.7.3.2/32
        DNS = 1.1.1.1
        MTU = 1280

        [Peer]
        PublicKey = cGVlcl9wdWJsaWNfa2V5X2Jhc2U2NF8zMl9ieXRlc19vaz0=
        Endpoint = 127.0.0.1:9000
        AllowedIPs = 0.0.0.0/0
        PersistentKeepalive = 25
    """.trimIndent()

    @OptIn(ExperimentalEncodingApi::class)
    private fun link(): String {
        val wg = Base64.UrlSafe.encode(wgConf.encodeToByteArray()).trimEnd('=')
        // freeturn://vk?<transport><mode=..&obf-profile=..&wg=..>@<ip:port>#<obfkey>$<name>
        return "freeturn://vk?tcp<mode=udp&obf-profile=rtpopus&wg=$wg>@203.0.113.7:56000#deadbeef\$Demo VK-TURN"
    }

    @Test
    fun parsesPeerAndEmbeddedWireGuard() {
        val parsed = FreeturnUriParser.parse(link())!!

        assertEquals("203.0.113.7", parsed.serverIp)
        assertEquals(56000, parsed.serverPort)
        // listen port mirrors the WireGuard Endpoint baked into the config.
        assertEquals(9000, parsed.listenPort)
        assertEquals("Demo VK-TURN", parsed.comment)

        val wg = parsed.wgOutboundJson
        assertTrue(wg.contains("\"type\":\"wireguard\""), wg)
        assertTrue(wg.contains("\"server\":\"127.0.0.1\""), wg)
        assertTrue(wg.contains("\"server_port\":9000"), wg)
        assertTrue(wg.contains("10.7.3.2/32"), wg)
        assertTrue(wg.contains("\"private_key\""), wg)
        assertTrue(wg.contains("\"peer_public_key\""), wg)
        assertTrue(wg.contains("\"mtu\":1280"), wg)
    }

    @Test
    fun rejectsNonFreeturnAndMissingWg() {
        assertNull(FreeturnUriParser.parse("vless://whatever@host:443"))
        // freeturn link without an embedded wg= cannot raise a WireGuard tunnel.
        assertNull(FreeturnUriParser.parse("freeturn://vk?tcp<mode=udp>@203.0.113.7:56000\$NoWG"))
    }
}
