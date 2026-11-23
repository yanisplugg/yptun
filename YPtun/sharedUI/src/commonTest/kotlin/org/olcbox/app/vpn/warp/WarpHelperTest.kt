package org.olcbox.app.vpn.warp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class WarpHelperTest {

    @Test
    fun testParseWarpIniToProxyProfile() {
        val ini = """
            [Interface]
            PrivateKey = aaaaaa=
            Address = 172.16.0.2/32, 2606:4700:110:8735:e228:a54:4c32:ae20/128
            DNS = 1.1.1.1
            MTU = 1280
            Jc = 4
            Jmin = 40
            Jmax = 70
            S1 = 0
            S2 = 0
            H1 = 1
            H2 = 2
            H3 = 3
            H4 = 4
            I1 = <b 0xce00...>

            [Peer]
            PublicKey = bbbbbb=
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = engage.cloudflareclient.com:2408
            PersistentKeepalive = 25
        """.trimIndent()

        val profile = WarpHelper.parseWarpIniToProxyProfile(ini, tag = "WARP")
        assertNotNull(profile)
        assertEquals("WARP", profile.tag)
        assertEquals("wireguard", profile.type)
        assertEquals("162.159.192.1", profile.server)
        assertEquals(2408, profile.serverPort)

        val raw = profile.rawOutbound
        assertNotNull(raw)
        val json = Json.parseToJsonElement(raw).jsonObject
        assertEquals("wireguard", json["type"]?.jsonPrimitive?.content)
        assertEquals("162.159.192.1", json["server"]?.jsonPrimitive?.content)
        assertEquals("aaaaaa=", json["private_key"]?.jsonPrimitive?.content)
        assertEquals("bbbbbb=", json["peer_public_key"]?.jsonPrimitive?.content)
    }

    @Test
    fun cachedAccountRequiresUsableReservedAndOptionalI1() {
        assertEquals(false, WarpHelper.isUsableCachedAccount("Reserved = 0, 0, 0", requireI1 = false))
        assertEquals(false, WarpHelper.isUsableCachedAccount("Reserved = 1, 2, 256", requireI1 = false))
        assertEquals(false, WarpHelper.isUsableCachedAccount("client_id = AQID", requireI1 = true))
        assertEquals(true, WarpHelper.isUsableCachedAccount("client_id = AQID", requireI1 = false))
        assertEquals(true, WarpHelper.isUsableCachedAccount("Reserved = 1, 2, 3\nI1 = <b 0xce>", requireI1 = true))
    }

    @Test
    fun exitByIpPreservesXhttpHostAndTlsServerName() {
        val profile = org.olcbox.app.data.model.ProxyProfile(
            server = "node.example", network = "xhttp", security = "tls",
        )
        val implicit = WarpHelper.exitByIp(profile, "192.0.2.1")
        assertEquals("192.0.2.1", implicit.server)
        assertEquals("node.example", implicit.host)
        assertEquals("node.example", implicit.sni)
        val cdn = WarpHelper.exitByIp(profile.copy(host = "front.example"), "192.0.2.1")
        assertEquals("front.example", cdn.host)
        assertEquals("front.example", cdn.sni)
    }

    @Test
    fun clientAwgKeepsThe373WireFormat() {
        val ini = """
            [Interface]
            client_id = AQID
            I1 = <b 0xce>
            I2 = <b 0x01>
            [Peer]
            Endpoint = engage.cloudflareclient.com:894
            PersistentKeepalive = 15
        """.trimIndent()
        val prepared = WarpHelper.clientAwgIni(ini)
        assertEquals(true, prepared.contains("I1 = <b 0xce>"))
        assertEquals(true, prepared.contains("I2 = <b 0x01>"))
        assertEquals(false, prepared.contains("client_id"))
        assertEquals("162.159.192.1" to 2408, WarpHelper.endpointOf(prepared))
        assertEquals(true, prepared.contains("PersistentKeepalive = 25"))
        assertEquals(true, WarpHelper.clientAwgIni(ini.replace("I1 = <b 0xce>", "")).contains("I1 = $WARP_I1"))
    }

    @Test
    fun clientAwgTestsBothPortsWithAndWithoutReserved() {
        val candidates = WarpHelper.clientAwgCandidates(
            "[Interface]\nReserved = 1, 2, 3\nI1 = <b 0xce>\n[Peer]\nEndpoint = 162.159.192.1:2408"
        )
        assertEquals(listOf(3476, 3476, 2408, 2408, 894, 894), candidates.map { WarpHelper.endpointOf(it).second })
        assertEquals("188.114.99.4" to 3476, WarpHelper.endpointOf(candidates.first()))
        assertEquals(listOf(false, true, false, true, false, true), candidates.map { it.contains("Reserved = 1, 2, 3") })
        assertEquals(true, candidates.all { it.contains("I1 = <b 0xce>") })
        assertEquals(3, WarpHelper.clientAwgCandidates(candidates.first { WarpHelper.endpointOf(it).second == 2408 }).size)
        assertEquals(1, WarpHelper.clientAwgCandidates(candidates.first()).size)
    }

    @Test
    fun engineIniKeepsReservedPinsEndpointAndOnlyAwgKeepsObfuscation() {
        val ini = """
            [Interface]
            PrivateKey = a=
            Reserved = 1, 2, 3
            Jc = 4
            S1 = 0
            I1 = <b 0xce>
            [Peer]
            PublicKey = b=
            Endpoint = engage.cloudflareclient.com:2408
        """.trimIndent()
        val awg = WarpHelper.engineIni(ini, awg = true, initPackets = true)
        assertEquals(true, awg.contains("Reserved"))
        // AmneziaWG keeps Jc/S1/I1 obfuscation, pins IP endpoint, and maps blocked 2408 to 894
        assertEquals(true, awg.contains("I1 = <b 0xce>") && awg.contains("Jc = 4") && awg.contains("Endpoint = 162.159.192.1:894"))
        val withoutI1 = WarpHelper.engineIni(ini.replace("I1 = <b 0xce>", ""), awg = true)
        assertEquals(true, withoutI1.contains("I1 = $WARP_I1"))
        val server = WarpHelper.engineIni(ini, awg = true, initPackets = false)
        assertEquals(true, !server.contains("I1") && server.contains("Jc = 4"))
        val wg = WarpHelper.engineIni(ini, awg = false)
        assertEquals(false, wg.contains("Jc") || wg.contains("S1") || wg.contains("I1"))
        assertEquals(true, wg.contains("Reserved = 1, 2, 3"))
        assertEquals("1.2.3.4", WarpHelper.pinnedHost("1.2.3.4"))
    }
}
