package org.olcbox.app.data.datasource

import kotlinx.coroutines.test.runTest
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.ProxyCore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A JSON subscription's OWN routing/DNS must win over the app's settings.
 *
 * The proxy outbound below (vless + tcp + reality) IS translatable into a typed profile, and that
 * used to happen unconditionally — which silently threw away the config's `routing.rules` and left
 * the app's routing profile in charge. Configs that bring their own routing must stay verbatim
 * (rawXrayConfig + core=Xray), which is what makes XrayConfig.prepareRaw honor them.
 */
class JsonSubscriptionRoutingTest {

    private val proxyOutbound = """
        {
          "protocol": "vless",
          "tag": "proxy",
          "settings": { "vnext": [ { "address": "1.2.3.4", "port": 443,
            "users": [ { "id": "11111111-2222-3333-4444-555555555555", "encryption": "none",
                         "flow": "xtls-rprx-vision" } ] } ] },
          "streamSettings": { "network": "tcp", "security": "reality",
            "realitySettings": { "serverName": "google.com", "publicKey": "PUB", "shortId": "ab",
                                 "fingerprint": "chrome" } }
        }
    """.trimIndent()

    private fun config(extra: String) = """
        { "remarks": "JSON node",
          $extra
          "outbounds": [ $proxyOutbound, { "protocol": "freedom", "tag": "direct" } ] }
    """.trimIndent()

    private suspend fun importOne(text: String): LocationBundleV4 {
        val source = FakeDataSource()
        assertTrue(LocationsRepositoryImpl(source).importText(text, null), "import failed")
        return assertNotNull(source.stored)
    }

    @Test
    fun ownRoutingKeepsTheConfigVerbatimOnXray() = runTest {
        val withRouting = config(
            """"routing": { "domainStrategy": "IPIfNonMatch", "rules": [
                 { "type": "field", "domain": ["domain:ru"], "outboundTag": "direct" } ] },"""
        )
        val location = importOne(withRouting).locations.single().location
        assertEquals(ProxyCore.Xray, location.core, "a config with its own routing must run on Xray")
        val raw = assertNotNull(location.proxy?.rawXrayConfig, "the whole template must be kept")
        assertTrue(raw.contains("domain:ru"), "the config's own routing rule must survive the import")
    }

    @Test
    fun scopedDnsAlsoKeepsTheConfigVerbatim() = runTest {
        val withScopedDns = config(
            """"dns": { "servers": [ "1.1.1.1",
                 { "address": "77.88.8.8", "domains": ["domain:ru"] } ] },"""
        )
        val location = importOne(withScopedDns).locations.single().location
        assertEquals(ProxyCore.Xray, location.core, "per-domain DNS can't be translated — keep verbatim")
        assertTrue(
            assertNotNull(location.proxy?.rawXrayConfig).contains("77.88.8.8"),
            "the config's own resolver must survive the import"
        )
    }

    /** No routing of its own → the typed path stays, so the location can still run on either core. */
    @Test
    fun withoutOwnRoutingTheNodeStaysTyped() = runTest {
        val location = importOne(config("")).locations.single().location
        assertEquals(ProxyCore.Auto, location.core)
        assertNull(location.proxy?.rawXrayConfig)
        assertEquals("1.2.3.4", location.proxy?.server)
    }

    private class FakeDataSource(var stored: LocationBundleV4? = null) : LocationsDataSource {
        override suspend fun loadLocationBundle(): LocationBundleV4? = stored
        override suspend fun saveLocationBundle(bundle: LocationBundleV4) { stored = bundle }
        override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()
        override suspend fun loadLegacyActiveLocationId(): String? = null
    }
}
