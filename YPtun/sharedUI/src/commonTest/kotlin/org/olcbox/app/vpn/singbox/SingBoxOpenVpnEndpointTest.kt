package org.olcbox.app.vpn.singbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.importer.OpenVpnParser
import org.olcbox.app.data.model.ProxyProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SingBoxOpenVpnEndpointTest {
    private fun profile() = assertNotNull(OpenVpnParser.parse("""
        client
        proto udp
        remote vpn.example.com 1194
        remote-cert-tls server
        <ca>
        -----BEGIN CERTIFICATE-----
        TEST-CA
        -----END CERTIFICATE-----
        </ca>
    """.trimIndent()))

    @Test
    fun openVpnIsEmittedOnlyAsTopLevelEndpoint() {
        val profile = profile()

        val root = Json.parseToJsonElement(
            SingBoxConfig.build(profile = profile, listenPort = 10808, blockQuic = false)
        ).jsonObject
        val endpoint = root["endpoints"]!!.jsonArray.single().jsonObject
        assertEquals("openvpn-client", endpoint["type"]!!.jsonPrimitive.content)
        assertEquals("proxy", endpoint["tag"]!!.jsonPrimitive.content)
        assertEquals("false", endpoint["system"]!!.jsonPrimitive.content)
        assertTrue(root["outbounds"]!!.jsonArray.none {
            it.jsonObject["type"]?.jsonPrimitive?.content == "openvpn-client"
        })
        assertEquals("proxy", root["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
        assertTrue(root["route"]!!.jsonObject["rules"]!!.jsonArray.none {
            it.jsonObject["protocol"]?.jsonArray?.any { p -> p.jsonPrimitive.content == "quic" } == true
        })
    }

    @Test
    fun openVpnCanBeTheSecondCascadeEndpoint() {
        val main = ProxyProfile(
            type = ProxyProfile.TYPE_VLESS,
            server = "main.example",
            serverPort = 443,
            uuid = "11111111-2222-3333-4444-555555555555",
            security = ProxyProfile.SECURITY_TLS,
        )
        val root = Json.parseToJsonElement(
            SingBoxConfig.build(
                profile = main,
                secondProfile = profile(),
                listenPort = 10808,
                blockQuic = false,
            )
        ).jsonObject
        val endpoint = root["endpoints"]!!.jsonArray.single().jsonObject
        assertEquals("proxy", endpoint["tag"]!!.jsonPrimitive.content)
        assertEquals("proxy-base", endpoint["detour"]!!.jsonPrimitive.content)
        val mainOutbound = root["outbounds"]!!.jsonArray.map { it.jsonObject }
            .single { it["type"]?.jsonPrimitive?.content == ProxyProfile.TYPE_VLESS }
        assertEquals("proxy-base", mainOutbound["tag"]!!.jsonPrimitive.content)
    }
}
