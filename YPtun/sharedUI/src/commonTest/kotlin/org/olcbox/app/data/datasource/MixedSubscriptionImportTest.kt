package org.olcbox.app.data.datasource

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.test.runTest
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.LocationBundleV4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #39: a subscription that mixes link families (olcrtc:// next to vless://) imported only one
 * of them, because the parsers were tried in order and the first match won the whole body.
 */
class MixedSubscriptionImportTest {

    private class FakeLocationsDataSource(var stored: LocationBundleV4? = null) : LocationsDataSource {
        override suspend fun loadLocationBundle(): LocationBundleV4? = stored

        override suspend fun saveLocationBundle(bundle: LocationBundleV4) {
            stored = bundle
        }

        override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()

        override suspend fun loadLegacyActiveLocationId(): String? = null
    }

    private val olcRtcLink =
        "olcrtc://wbstream?seichannel@room-01#${"a".repeat(64)}%android-01${'$'}OLC room"

    private val vlessLink =
        "vless://11111111-2222-3333-4444-555555555555@example.com:443" +
            "?security=tls&type=tcp#Amsterdam"

    private val secondVlessLink =
        "vless://22222222-3333-4444-5555-666666666666@example.org:8443" +
            "?security=tls&type=tcp#Berlin"

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
    private fun freeturnLink(ip: String, name: String): String {
        val wg = Base64.UrlSafe.encode(wgConf.encodeToByteArray()).trimEnd('=')
        return "freeturn://vk?tcp<mode=udp&obf-profile=rtpopus&wg=$wg>@$ip:56000#deadbeef${'$'}$name"
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun base64Body(vararg links: String): String =
        Base64.Default.encode(links.joinToString("\n").encodeToByteArray())

    @Test
    fun importsBothOlcRtcAndVlessFromOneSubscription() = runTest {
        val source = FakeLocationsDataSource()

        LocationsRepositoryImpl(source).importText("$olcRtcLink\n$vlessLink\n$secondVlessLink")

        val imported = source.stored
        assertNotNull(imported)
        assertEquals(3, imported.locations.size, "every link must be imported, not just olcrtc://")
        val names = imported.locations.map { it.location.displayName() }
        assertTrue("OLC room" in names, names.toString())
        assertTrue("Amsterdam" in names, names.toString())
        assertTrue("Berlin" in names, names.toString())
        assertEquals(
            2,
            imported.locations.count { it.location.engine == EngineType.Standard },
            "both vless links become Standard locations"
        )
    }

    @Test
    fun importsBothFamiliesFromABase64Subscription() = runTest {
        val source = FakeLocationsDataSource()

        // A base64 body hid olcrtc:// links from their parser entirely (it looks for the scheme in
        // the raw text), so the mirror image of the bug: only the vless:// servers landed.
        LocationsRepositoryImpl(source).importText(base64Body(olcRtcLink, vlessLink))

        val imported = source.stored
        assertNotNull(imported)
        assertEquals(2, imported.locations.size)
        val names = imported.locations.map { it.location.displayName() }
        assertTrue("OLC room" in names, names.toString())
        assertTrue("Amsterdam" in names, names.toString())
    }

    @Test
    fun importsEveryFreeturnLinkNotJustTheFirst() = runTest {
        val source = FakeLocationsDataSource()

        LocationsRepositoryImpl(source).importText(
            "${freeturnLink("203.0.113.7", "VK one")}\n${freeturnLink("203.0.113.8", "VK two")}"
        )

        val imported = source.stored
        assertNotNull(imported)
        assertEquals(2, imported.locations.size)
        assertTrue(imported.locations.all { it.location.engine == EngineType.VkTurn })
        assertEquals(
            2,
            imported.locations.map { it.storageId }.distinct().size,
            "storage ids must stay unique across entries"
        )
    }

    @Test
    fun singleFamilySubscriptionIsUnchanged() = runTest {
        val source = FakeLocationsDataSource()

        LocationsRepositoryImpl(source).importText("$vlessLink\n$secondVlessLink")

        val imported = source.stored
        assertNotNull(imported)
        assertEquals(2, imported.locations.size)
        assertEquals(imported.locations.first().storageId, imported.activeLocationId)
    }
}
