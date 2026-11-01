package org.olcbox.app.data.importer

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.olcbox.app.data.model.ProxyProfile

class ShareLinkParserTest {

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun parsesVmessBase64Json() {
        val json = """
            {"v":"2","ps":"node","add":"vm.example.com","port":"443","id":"11111111-2222-3333-4444-555555555555",
             "aid":"0","scy":"auto","net":"ws","host":"vm.example.com","path":"/ray","tls":"tls","sni":"vm.example.com"}
        """.trimIndent()
        val uri = "vmess://" + Base64.Default.encode(json.encodeToByteArray())

        val p = ShareLinkParser.parse(uri)!!

        assertEquals(ProxyProfile.TYPE_VMESS, p.type)
        assertEquals("vm.example.com", p.server)
        assertEquals(443, p.serverPort)
        assertEquals("11111111-2222-3333-4444-555555555555", p.uuid)
        assertEquals(ProxyProfile.NETWORK_WS, p.network)
        assertEquals(ProxyProfile.SECURITY_TLS, p.security)
        assertEquals("/ray", p.path)
        assertEquals("node", p.tag)
        assertTrue(p.isComplete())
    }

    @Test
    fun parsesTrojanLink() {
        val uri = "trojan://secretpass@tj.example.com:443?sni=tj.example.com&type=ws&path=/tj#Trojan"

        val p = ShareLinkParser.parse(uri)!!

        assertEquals(ProxyProfile.TYPE_TROJAN, p.type)
        assertEquals("tj.example.com", p.server)
        assertEquals(443, p.serverPort)
        assertEquals("secretpass", p.password)
        assertEquals(ProxyProfile.SECURITY_TLS, p.security) // trojan implies TLS
        assertEquals(ProxyProfile.NETWORK_WS, p.network)
        assertEquals("/tj", p.path)
        assertTrue(p.isComplete())
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun parsesShadowsocksSip002() {
        val userInfo = Base64.UrlSafe.encode("aes-128-gcm:ss-password".encodeToByteArray()).trimEnd('=')
        val uri = "ss://$userInfo@ss.example.com:8388#SS"

        val p = ShareLinkParser.parse(uri)!!

        assertEquals(ProxyProfile.TYPE_SHADOWSOCKS, p.type)
        assertEquals("ss.example.com", p.server)
        assertEquals(8388, p.serverPort)
        assertEquals("aes-128-gcm", p.method)
        assertEquals("ss-password", p.password)
        assertTrue(p.isComplete())
    }

    @Test
    fun parsesMixedProtocolSubscription() {
        val body = """
            vless://uuid-a@a.com:443?security=tls&flow=xtls-rprx-vision#A
            trojan://pw@b.com:443?sni=b.com#B
            ss://YWVzLTEyOC1nY206cHc@c.com:8388#C
            hysteria2://pw@d.com:443?sni=d.com&obfs=salamander&obfs-password=op#D
        """.trimIndent()

        val profiles = ShareLinkParser.parseSubscription(body)

        assertEquals(4, profiles.size)
        assertEquals(ProxyProfile.TYPE_VLESS, profiles[0].type)
        assertEquals(ProxyProfile.TYPE_TROJAN, profiles[1].type)
        assertEquals(ProxyProfile.TYPE_SHADOWSOCKS, profiles[2].type)
        assertEquals(ProxyProfile.TYPE_HYSTERIA2, profiles[3].type)
        assertEquals("d.com", profiles[3].server)
        assertEquals("salamander", profiles[3].hy2Obfs)
    }

    @Test
    fun parsesHysteria2PortHoppingInAuthority() {
        val p = ShareLinkParser.parse("hy2://pw@d.com:443,5000-6000/?insecure=1#D")!!
        assertEquals("d.com", p.server)
        assertEquals(443, p.serverPort)
        assertEquals("443,5000-6000", p.hy2Ports)
        assertTrue(p.allowInsecure)

        val range = ShareLinkParser.parse("hysteria2://pw@d.com:20000-30000?sni=x.com")!!
        assertEquals("d.com", range.server)
        assertEquals(20000, range.serverPort)
        assertEquals("20000-30000", range.hy2Ports)
    }

    @Test
    fun rawOutboundProfileIsComplete() {
        val raw = """{"type":"hysteria2","server":"h.example.com","server_port":443,"password":"x"}"""
        val p = ProxyProfile(tag = "raw", rawOutbound = raw)
        assertTrue(p.isComplete())
    }
}
