package org.olcbox.app.data.datasource

import kotlinx.coroutines.test.runTest
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.ProxyCore
import org.olcbox.app.data.model.ProxyProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Formats accepted by the large field in Home → `+`; this is its actual repository import path. */
class MainPasteImportFormatsTest {
    @Test
    fun importsFullXrayJsonArrayWithLeastLoadBalancer() = runTest {
        val source = FakeDataSource()
        val text = """
            [{
              "remarks": "QFP ES",
              "outbounds": [
                { "tag": "es-ru", "protocol": "vless", "settings": { "vnext": [{
                  "address": "edge-a.example", "port": 8446,
                  "users": [{ "id": "11111111-2222-3333-4444-555555555555", "encryption": "none" }]
                }] }, "streamSettings": { "network": "tcp", "security": "tls" } },
                { "tag": "es-ru-2", "protocol": "vless", "settings": { "vnext": [{
                  "address": "edge-b.example", "port": 8446,
                  "users": [{ "id": "11111111-2222-3333-4444-555555555555", "encryption": "none" }]
                }] }, "streamSettings": { "network": "tcp", "security": "tls" } },
                { "tag": "block", "protocol": "blackhole" }
              ],
              "routing": {
                "rules": [{ "type": "field", "network": "tcp,udp", "balancerTag": "QFP_ES_AUTO" }],
                "balancers": [{ "tag": "QFP_ES_AUTO", "selector": ["es-ru"],
                  "fallbackTag": "block", "strategy": { "type": "leastLoad" } }]
              },
              "burstObservatory": { "subjectSelector": ["es-ru"], "pingConfig": {
                "timeout": "10s", "interval": "15s", "sampling": 3,
                "destination": "https://speed.cloudflare.com/__down?bytes=32768"
              } }
            }]
        """.trimIndent()

        assertTrue(LocationsRepositoryImpl(source).importText(text, null))
        val location = assertNotNull(source.stored).locations.single().location
        assertEquals(ProxyCore.Xray, location.core)
        val raw = assertNotNull(location.proxy?.rawXrayConfig)
        assertTrue(raw.contains("QFP_ES_AUTO"))
        assertTrue(raw.contains("leastLoad"))
        assertTrue(raw.contains("burstObservatory"))
    }

    @Test
    fun importsFullSingBoxJson() = runTest {
        val source = FakeDataSource()
        val text = """
            {
              "outbounds": [
                { "tag": "proxy", "type": "vless", "server": "sb.example", "server_port": 443,
                  "uuid": "11111111-2222-3333-4444-555555555555" },
                { "tag": "direct", "type": "direct" }
              ],
              "route": { "final": "proxy", "rules": [
                { "domain_suffix": ["ru"], "outbound": "direct" }
              ] }
            }
        """.trimIndent()

        assertTrue(LocationsRepositoryImpl(source).importText(text, null))
        val profile = assertNotNull(source.stored).locations.single().location.proxy
        assertEquals("sb.example", profile?.server)
        assertTrue(profile?.rawOutbound.orEmpty().contains("\"type\":\"vless\""))
        assertTrue(profile?.rawSingBoxRoute.orEmpty().contains("\"final\":\"proxy\""))
    }

    @Test
    fun importsAmneziaWgIni() = runTest {
        val source = FakeDataSource()
        val text = """
            # AWG pasted into main add field
            [Interface]
            PrivateKey = private
            Address = 10.8.0.2/32
            Jc = 4
            Jmin = 40
            Jmax = 70
            S1 = 0
            S2 = 0
            H1 = 1
            H2 = 2
            H3 = 3
            H4 = 4
            [Peer]
            PublicKey = public
            Endpoint = 203.0.113.7:51820
            AllowedIPs = 0.0.0.0/0
        """.trimIndent()

        assertTrue(LocationsRepositoryImpl(source).importText(text, null))
        val profile = assertNotNull(source.stored).locations.single().location.proxy
        assertEquals(ProxyProfile.TYPE_AMNEZIAWG, profile?.type)
        assertEquals("203.0.113.7", profile?.server)
        assertTrue(profile?.awgConfig.orEmpty().contains("Jc = 4"))
    }

    @Test
    fun importsInlineOpenVpnAndLocksItToSingBox() = runTest {
        val source = FakeDataSource()
        val text = """
            client
            dev tun
            proto udp
            remote vpn.example.com 1194
            remote-cert-tls server
            <ca>
            -----BEGIN CERTIFICATE-----
            TEST-CA
            -----END CERTIFICATE-----
            </ca>
            <auth-user-pass>
            alice
            secret
            </auth-user-pass>
        """.trimIndent()

        assertTrue(LocationsRepositoryImpl(source).importText(text, null))
        val location = assertNotNull(source.stored).locations.single().location
        val profile = assertNotNull(location.proxy)
        assertEquals(ProxyProfile.TYPE_OPENVPN, profile.type)
        assertEquals(ProxyCore.SingBox, location.core)
        assertEquals(ProxyCore.SingBox, location.copy(core = ProxyCore.Xray).resolvedCore())
        assertTrue(profile.rawOutbound.orEmpty().contains("\"type\":\"openvpn-client\""))
    }

    @Test
    fun importsOpenVpnEndpointFromFullSingBoxJson() = runTest {
        val source = FakeDataSource()
        val text = """
            {
              "endpoints": [{
                "type": "openvpn-client", "tag": "office-vpn",
                "server": "office.example", "server_port": 1194, "network": "udp",
                "tls": { "certificate": ["TEST-CA"] }
              }],
              "outbounds": [{ "type": "direct", "tag": "direct" }],
              "route": { "final": "office-vpn", "rules": [
                { "domain_suffix": ["lan.example"], "outbound": "direct" }
              ] }
            }
        """.trimIndent()

        assertTrue(LocationsRepositoryImpl(source).importText(text, null))
        val location = assertNotNull(source.stored).locations.single().location
        assertEquals(ProxyProfile.TYPE_OPENVPN, location.proxy?.type)
        assertEquals("office.example", location.proxy?.server)
        assertEquals(ProxyCore.SingBox, location.core)
        assertTrue(location.proxy?.rawSingBoxRoute.orEmpty().contains("lan.example"))
    }

    private class FakeDataSource(var stored: LocationBundleV4? = null) : LocationsDataSource {
        override suspend fun loadLocationBundle(): LocationBundleV4? = stored
        override suspend fun saveLocationBundle(bundle: LocationBundleV4) { stored = bundle }
        override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()
        override suspend fun loadLegacyActiveLocationId(): String? = null
    }
}
