package org.olcbox.app.data.importer

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.olcbox.app.data.model.ProxyProfile

class VlessUriParserTest {

    @Test
    fun parsesRealityVisionLink() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@example.com:443" +
            "?type=tcp&security=reality&sni=www.microsoft.com&fp=chrome" +
            "&pbk=ABCDEF&sid=0123&flow=xtls-rprx-vision#My%20Server"

        val p = VlessUriParser.parse(uri)!!

        assertEquals("My Server", p.tag)
        assertEquals("example.com", p.server)
        assertEquals(443, p.serverPort)
        assertEquals("11111111-2222-3333-4444-555555555555", p.uuid)
        assertEquals(ProxyProfile.NETWORK_TCP, p.network)
        assertEquals(ProxyProfile.SECURITY_REALITY, p.security)
        assertEquals("www.microsoft.com", p.sni)
        assertEquals("chrome", p.fingerprint)
        assertEquals("ABCDEF", p.realityPublicKey)
        assertEquals("0123", p.realityShortId)
        assertEquals("xtls-rprx-vision", p.flow)
        assertTrue(p.isComplete())
    }

    @Test
    fun parsesWebsocketTlsLink() {
        val uri = "vless://aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee@1.2.3.4:8443" +
            "?type=ws&security=tls&sni=cdn.example.com&host=cdn.example.com&path=%2Fwss#WS"

        val p = VlessUriParser.parse(uri)!!

        assertEquals(ProxyProfile.NETWORK_WS, p.network)
        assertEquals(ProxyProfile.SECURITY_TLS, p.security)
        assertEquals("/wss", p.path)
        assertEquals("cdn.example.com", p.host)
        assertEquals("cdn.example.com", p.sni)
    }

    @Test
    fun parsesIpv6Host() {
        val uri = "vless://uuid-uuid@[2001:db8::1]:443?security=tls#v6"
        val p = VlessUriParser.parse(uri)!!
        assertEquals("2001:db8::1", p.server)
        assertEquals(443, p.serverPort)
    }

    @Test
    fun rejectsNonVlessScheme() {
        assertNull(VlessUriParser.parse("vmess://something"))
        assertNull(VlessUriParser.parse("https://example.com"))
    }

    @Test
    fun parsesPlainSubscriptionList() {
        val body = """
            vless://uuid-a@a.com:443?security=tls#A
            vless://uuid-b@b.com:8443?type=ws&security=tls&path=/x#B
            trojan://ignored@c.com:443#C
        """.trimIndent()

        val profiles = VlessUriParser.parseSubscription(body)

        assertEquals(2, profiles.size)
        assertEquals("a.com", profiles[0].server)
        assertEquals("b.com", profiles[1].server)
    }

    @Test
    fun parsesRealVisionTcpLink() {
        val uri = "vless://fa4a1163-ace3-457a-9b5a-57f9d19d2c24@vbn.azz.su:443" +
            "?encryption=none&flow=xtls-rprx-vision&type=tcp&security=tls" +
            "&sni=vbn.azz.su&fp=firefox&alpn=http%2F1.1#%F0%9F%87%AB%F0%9F%87%AEfinland"

        val p = VlessUriParser.parse(uri)!!

        assertEquals("fa4a1163-ace3-457a-9b5a-57f9d19d2c24", p.uuid)
        assertEquals("vbn.azz.su", p.server)
        assertEquals(443, p.serverPort)
        assertEquals(ProxyProfile.NETWORK_TCP, p.network)
        assertEquals(ProxyProfile.SECURITY_TLS, p.security)
        assertEquals("vbn.azz.su", p.sni)
        assertEquals("firefox", p.fingerprint)
        assertEquals("xtls-rprx-vision", p.flow)
        assertEquals(listOf("http/1.1"), p.alpn)
        assertEquals("🇫🇮finland", p.tag)
        assertTrue(p.isComplete())
    }

    @Test
    fun parsesJsonSubscriptionWithLinksArray() {
        val body = """
            {"isFound":true,"user":{"shortUuid":"abc"},
             "links":[
               "vless://uuid-a@vbn.azz.su:443?security=tls&flow=xtls-rprx-vision#A",
               "vless://uuid-b@vbn2.azz.su:443?type=ws&security=tls&path=/x#B"
             ],
             "subscriptionUrl":"https://example"}
        """.trimIndent()

        val profiles = VlessUriParser.parseSubscription(body)

        assertEquals(2, profiles.size)
        assertEquals("vbn.azz.su", profiles[0].server)
        assertEquals("xtls-rprx-vision", profiles[0].flow)
        assertEquals("vbn2.azz.su", profiles[1].server)
        assertEquals(ProxyProfile.NETWORK_WS, profiles[1].network)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun parsesBase64Subscription() {
        val raw = "vless://uuid-a@a.com:443?security=tls#A\n" +
            "vless://uuid-b@b.com:8443?security=tls#B"
        val encoded = Base64.Default.encode(raw.encodeToByteArray())

        val profiles = VlessUriParser.parseSubscription(encoded)

        assertEquals(2, profiles.size)
        assertEquals("a.com", profiles[0].server)
        assertEquals("b.com", profiles[1].server)
    }
}
