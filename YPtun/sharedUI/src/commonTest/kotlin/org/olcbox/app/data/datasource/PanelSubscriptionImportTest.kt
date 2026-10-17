package org.olcbox.app.data.datasource

import kotlinx.coroutines.test.runTest
import org.olcbox.app.data.importer.MasterDnsUriParser
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.LocationBundleV4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Personal subscription of the YPtun panel: one URL, a list of per-protocol links. masterdns:// and
 * snolc:// used to be dropped from such a list; every family must come through next to the others.
 */
class PanelSubscriptionImportTest {

    private class FakeSource(var stored: LocationBundleV4? = null) : LocationsDataSource {
        override suspend fun loadLocationBundle(): LocationBundleV4? = stored
        override suspend fun saveLocationBundle(bundle: LocationBundleV4) { stored = bundle }
        override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()
        override suspend fun loadLegacyActiveLocationId(): String? = null
    }

    private val masterDns =
        "masterdns://v1?domains=v.example.com,w.example.com&key=s3cret%2B&enc=1&resolvers=1.1.1.1,8.8.8.8:53&bal=2#YPtun%20MasterDNS"
    private val snolc = "snolc://203.0.113.5:443?key=${"ab".repeat(32)}#YPtun%20SNOLC"
    private val vless = "vless://11111111-2222-3333-4444-555555555555@example.com:443?security=tls&type=tcp#Plain"
    private val qwdtt = "qwdtt://config?name=Q&peer=203.0.113.9&hashes=h1,h2&workers=9&port=9000&pass=p"
    private val csqtt = "csqtt://connect?v=2&host=203.0.113.10&peer=46000&password=p&hashes=a+b"

    @Test
    fun masterDnsLinkParsesAllFields() {
        val link = assertNotNull(MasterDnsUriParser.parse(masterDns))
        assertEquals("YPtun MasterDNS", link.name)
        assertEquals(listOf("v.example.com", "w.example.com"), link.config.domainList())
        assertEquals("s3cret+", link.config.encryptionKey)
        assertEquals(listOf("1.1.1.1", "8.8.8.8:53"), link.config.resolverList())
        assertEquals(2, link.config.balancingStrategy)
    }

    @Test
    fun unknownVersionOrMissingFieldsAreRejected() {
        assertNull(MasterDnsUriParser.parse("masterdns://v2?domains=a&key=k&resolvers=1.1.1.1"))
        assertNull(MasterDnsUriParser.parse("masterdns://v1?domains=a&resolvers=1.1.1.1"))
    }

    @Test
    fun mixedPanelSubscriptionKeepsEveryFamily() = runTest {
        val source = FakeSource()
        LocationsRepositoryImpl(source).importText(listOf(vless, masterDns, snolc, qwdtt, csqtt).joinToString("\n"))
        val engines = assertNotNull(source.stored).locations.map { it.location.engine }
        assertEquals(1, engines.count { it == EngineType.MasterDns })
        assertEquals(1, engines.count { it == EngineType.Snolc })
        assertEquals(1, engines.count { it == EngineType.Standard })
        assertEquals(2, engines.count { it == EngineType.VkTurn })
    }
}
