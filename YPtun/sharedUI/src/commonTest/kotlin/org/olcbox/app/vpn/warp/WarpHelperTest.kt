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
    fun engineIniDropsReservedPinsEndpointAndOnlyAwgKeepsObfuscation() {
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
        val awg = WarpHelper.engineIni(ini, awg = true)
        assertEquals(false, awg.contains("Reserved"))
        // I1 is the DPI-evasion init packet: losing it makes DPI networks cut all data after the handshake.
        assertEquals(true, awg.contains("I1 = <b 0xce>") && awg.contains("Jc = 4") && awg.contains("Endpoint = 162.159.192.1:2408"))
        val wg = WarpHelper.engineIni(ini, awg = false)
        assertEquals(false, wg.contains("Jc") || wg.contains("S1") || wg.contains("Reserved"))
        assertEquals("1.2.3.4", WarpHelper.pinnedHost("1.2.3.4"))
    }
}
