package org.olcbox.app.data.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.ProxyProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenVpnParserTest {
    private val inlineProfile = """
        client
        dev tun
        proto udp
        remote vpn.example.com 1194 udp
        remote backup.example.com 443 tcp
        remote-random
        auth SHA256
        cipher AES-256-CBC
        data-ciphers AES-256-GCM:AES-128-GCM
        remote-cert-tls server
        verify-x509-name vpn.example.com name
        keepalive 10 60
        reneg-sec 0
        pull-filter ignore "redirect-gateway ipv6"
        <ca>
        -----BEGIN CERTIFICATE-----
        TEST-CA
        -----END CERTIFICATE-----
        </ca>
        <cert>
        -----BEGIN CERTIFICATE-----
        TEST-CLIENT
        -----END CERTIFICATE-----
        </cert>
        <key>
        -----BEGIN PRIVATE KEY-----
        TEST-KEY
        -----END PRIVATE KEY-----
        </key>
        <tls-crypt>
        -----BEGIN OpenVPN Static key V1-----
        TEST-CONTROL-KEY
        -----END OpenVPN Static key V1-----
        </tls-crypt>
        <auth-user-pass>
        alice
        correct horse battery staple
        </auth-user-pass>
    """.trimIndent()

    @Test
    fun convertsInlineOvpnToOpenVpnEndpoint() {
        assertTrue(OpenVpnParser.looksLikeOpenVpn(inlineProfile))
        val profile = assertNotNull(OpenVpnParser.parse(inlineProfile))
        assertEquals(ProxyProfile.TYPE_OPENVPN, profile.type)
        assertEquals("vpn.example.com", profile.server)
        assertEquals(1194, profile.serverPort)

        val endpoint = Json.parseToJsonElement(assertNotNull(profile.rawOutbound)).jsonObject
        assertEquals("openvpn-client", endpoint["type"]!!.jsonPrimitive.content)
        assertEquals(2, endpoint["servers"]!!.jsonArray.size)
        assertEquals("tcp", endpoint["servers"]!!.jsonArray[1].jsonObject["network"]!!.jsonPrimitive.content)
        assertEquals("alice", endpoint["username"]!!.jsonPrimitive.content)
        assertEquals("correct horse battery staple", endpoint["password"]!!.jsonPrimitive.content)
        assertEquals("AES-256-CBC", endpoint["data_ciphers_fallback"]!!.jsonPrimitive.content)
        assertEquals("60s", endpoint["ping_restart"]!!.jsonPrimitive.content)
        assertTrue(endpoint["renegotiate_disabled"]!!.jsonPrimitive.content.toBoolean())
        val tls = endpoint["tls"]!!.jsonObject
        assertTrue(tls["certificate"]!!.jsonArray.single().jsonPrimitive.content.contains("TEST-CA"))
        assertTrue(tls["client_certificate"]!!.jsonArray.single().jsonPrimitive.content.contains("TEST-CLIENT"))
        assertEquals("tls_crypt", tls["control_wrap"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun refusesProfilesWhoseMaterialWouldDisappearAfterFileImport() {
        val external = """
            client
            remote vpn.example.com 1194
            ca ca.crt
            cert client.crt
            key client.key
        """.trimIndent()
        assertTrue(OpenVpnParser.looksLikeOpenVpn(external))
        assertNull(OpenVpnParser.parse(external))

        val promptOnly = """
            client
            remote vpn.example.com 1194
            auth-user-pass
            <ca>CA</ca>
        """.trimIndent()
        assertFalse(OpenVpnParser.looksLikeOpenVpn("vless://example"))
        assertNull(OpenVpnParser.parse(promptOnly))
    }

    @Test
    fun staticKeyModeUsesCipherAndTunnelAddresses() {
        val profile = assertNotNull(OpenVpnParser.parse("""
            dev tun
            proto udp
            remote 198.51.100.20 1194
            ifconfig 10.8.0.2 10.8.0.1
            cipher AES-256-CBC
            <secret>
            -----BEGIN OpenVPN Static key V1-----
            TEST-STATIC-KEY
            -----END OpenVPN Static key V1-----
            </secret>
        """.trimIndent()))
        val endpoint = Json.parseToJsonElement(assertNotNull(profile.rawOutbound)).jsonObject
        assertEquals("static_key", endpoint["mode"]!!.jsonPrimitive.content)
        assertEquals("AES-256-CBC", endpoint["cipher"]!!.jsonPrimitive.content)
        assertTrue(endpoint["data_ciphers_fallback"] == null)
        assertEquals("10.8.0.2/32", endpoint["address"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("10.8.0.1", endpoint["peer_address"]!!.jsonPrimitive.content)
    }
}
