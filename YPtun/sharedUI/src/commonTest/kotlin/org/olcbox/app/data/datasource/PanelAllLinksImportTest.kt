package org.olcbox.app.data.datasource

import kotlinx.coroutines.test.runTest
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.LocationBundleV4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every link family that the QuofortpostProtocols panel puts into a personal subscription must import,
 * alone and in one mixed list. The strings are produced by the panel's own link builders
 * (panel/app/protocols.py), so a format drift on either side fails here.
 */
class PanelAllLinksImportTest {

    private class FakeSource(var stored: LocationBundleV4? = null) : LocationsDataSource {
        override suspend fun loadLocationBundle(): LocationBundleV4? = stored
        override suspend fun saveLocationBundle(bundle: LocationBundleV4) { stored = bundle }
        override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()
        override suspend fun loadLegacyActiveLocationId(): String? = null
    }

    private val links = linkedMapOf(
        "trusttunnel" to "tt://AQIDBAUGBwg#Stealth%20NL-tt",
        "amneziawg" to "amneziawg://W0ludGVyZmFjZV0KUHJpdmF0ZUtleSA9IGlIUnRGN0poYXpYOExaSXd5dkV0TVJWU3pjM0xRbUFQMzhqMGozaW5LWGs9CkFkZHJlc3MgPSAxMC44LjAuMi8zMgpETlMgPSAxLjEuMS4xCk1UVSA9IDEyODAKSmMgPSA0CkptaW4gPSA0MApKbWF4ID0gNzAKUzEgPSAwClMyID0gMApIMSA9IDEKSDIgPSAyCkgzID0gMwpINCA9IDQKCltQZWVyXQpQdWJsaWNLZXkgPSA4UTZoUzlFMG0zYjBjV2wzbTBiNm8wdVl5MHI4YnE3eEo0Y1gzbTJrWjFvPQpFbmRwb2ludCA9IDIwMy4wLjExMy43OjUxODIwCkFsbG93ZWRJUHMgPSAwLjAuMC4wLzAsIDo6LzAKUGVyc2lzdGVudEtlZXBhbGl2ZSA9IDI1#Stealth%20FI-awg",
        "openflux" to "openflux://v1/JYrNCsIwEAZfRRZ66w_orSAiqC_gUTxsk60NtklItrah9N3dKuxh55tZwOJAUMOdCXvudpdr4VrIwdLLsUEW12IfKQflNCkpA07iI6lALBhhU5Zp3ih5Hq0sg8SC8nFAG70LHKF-LMDJbyKh1TSLHkMv2DH7WFeVdiqWf1eG8YfVx9B0kuyYsNAmvgs_Nr1R2eGc7W9y2ChYn-sX",
        "vkturn" to "freeturn://vk?tcp<mode=tcp&obf-profile=rtpopus&bond=1&vless=dmxlc3M6Ly8xMTExMTExMS0yMjIyLTMzMzMtNDQ0NC01NTU1NTU1NTU1NTVAZXhhbXBsZS5jb206NDQzP3NlY3VyaXR5PXRscyZ0eXBlPXRjcCNleGl0>@203.0.113.9:56000#abababababababababababababababab\$Stealth DE-vk",
        "freeturn" to "freeturn://vk?tcp<mode=udp&obf-profile=rtpopus&wg=W0ludGVyZmFjZV0KUHJpdmF0ZUtleSA9IGFhYWEKQWRkcmVzcyA9IDEwLjcuMi4yLzMyCgpbUGVlcl0KUHVibGljS2V5ID0gYmJiYgpFbmRwb2ludCA9IDEyNy4wLjAuMTo5MDAwCkFsbG93ZWRJUHMgPSAwLjAuMC4wLzAK>@203.0.113.9:56001#cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd\$Stealth DE-ft",
        "masterdns" to "masterdns://v1?domains=v.example.com,w.example.com&key=s3cret%2B&enc=1&resolvers=203.0.113.5%3A53&bal=2#Stealth%20NL-dns",
        "qwdtt" to "qwdtt://config?name=Stealth%20NL-wd&peer=203.0.113.5&hashes=h1%2Ch2&workers=9&port=9000&pass=pw",
        "csqtt" to "csqtt://connect?v=2&host=203.0.113.5&peer=46000&password=pw&hashes=a+b",
        "olcrtc" to "olcrtc://wbstream?seichannel@room123#efefefefefefefefefefefefefefefefefefefefefefefefefefefefefefefef%b7856bde\$Stealth NL-olc",
        "snolc" to "snolc://203.0.113.5:8444?key=abababababababababababababababababababababababababababababababab#Stealth%20NL-snolc"
    )

    /** Engine each family lands on (trusttunnel/amneziawg are Standard locations with a special proxy). */
    private val engine = mapOf(
        "trusttunnel" to EngineType.Standard, "amneziawg" to EngineType.Standard, "openflux" to EngineType.OpenFlux,
        "vkturn" to EngineType.VkTurn, "freeturn" to EngineType.VkTurn, "masterdns" to EngineType.MasterDns,
        "qwdtt" to EngineType.VkTurn, "csqtt" to EngineType.VkTurn, "olcrtc" to EngineType.Stealth, "snolc" to EngineType.Snolc
    )

    @Test
    fun everyFamilyImportsOnItsOwn() = runTest {
        for ((family, link) in links) {
            val source = FakeSource()
            LocationsRepositoryImpl(source).importText(link)
            val stored = assertNotNull(source.stored, "$family: nothing imported")
            assertEquals(1, stored.locations.size, "$family: expected exactly one location")
            assertEquals(engine.getValue(family), stored.locations.single().location.engine, "$family: wrong engine")
        }
    }

    @Test
    fun hostDescriptionLineReachesTheLocation() = runTest {
        val source = FakeSource()
        val body = links.getValue("snolc") + "\n#desc:Stealth%20NL-snolc=%D0%91%D1%8B%D1%81%D1%82%D1%80%D1%8B%D0%B9%20UDP\n" + links.getValue("masterdns")
        LocationsRepositoryImpl(source).importText(body)
        val byEngine = assertNotNull(source.stored).locations.associateBy { it.location.engine }
        assertEquals("Быстрый UDP", byEngine.getValue(EngineType.Snolc).description)
        assertEquals("", byEngine.getValue(EngineType.MasterDns).description)
    }

    @Test
    fun wholePanelSubscriptionKeepsEveryFamily() = runTest {
        val source = FakeSource()
        // A panel list may carry comment lines (# note) next to the links; they must be ignored.
        LocationsRepositoryImpl(source).importText("# QuofortpostProtocols\n" + links.values.joinToString("\n") + "\n")
        val engines = assertNotNull(source.stored).locations.map { it.location.engine }
        assertEquals(1, engines.count { it == EngineType.MasterDns })
        assertEquals(1, engines.count { it == EngineType.Snolc })
        assertEquals(1, engines.count { it == EngineType.OpenFlux })
        assertEquals(1, engines.count { it == EngineType.Stealth })
        assertEquals(4, engines.count { it == EngineType.VkTurn })
        assertTrue(engines.count { it == EngineType.Standard } >= 1, "trusttunnel/amneziawg must survive: $engines")
    }
}
