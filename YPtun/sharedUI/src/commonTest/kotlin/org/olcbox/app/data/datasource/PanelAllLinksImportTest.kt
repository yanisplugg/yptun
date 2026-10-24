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

    /** The panel writes `#desc:<node name>=<text>` with the SAME name it puts in the link, for every family. */
    @Test
    fun hostDescriptionReachesEveryFamily() = runTest {
        val lost = mutableListOf<String>()
        for ((family, link) in links) {
            val plain = FakeSource().also { LocationsRepositoryImpl(it).importText(link) }
            val name = assertNotNull(plain.stored).locations.single().name
            val source = FakeSource()
            val enc = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            LocationsRepositoryImpl(source).importText("$link\n#desc:$enc=desc-$family\n")
            val got = assertNotNull(source.stored).locations.single().description
            if (got != "desc-$family") lost += "$family(name='$name', description='$got')"
        }
        assertTrue(lost.isEmpty(), "description lost: $lost")
    }

    /** The cold-start list is painted from the light index first: it must carry the description too. */
    @Test
    fun coldStartIndexKeepsDescription() = runTest {
        val source = FakeSource()
        LocationsRepositoryImpl(source).importText(links.getValue("snolc") + "\n#desc:Stealth%20NL-snolc=Fast\n")
        val index = org.olcbox.app.data.model.LocationViewIndex.from(assertNotNull(source.stored))
        assertEquals("Fast", index.items.single().description)
    }

    @Test
    fun orderLinesRestorePanelOrderAcrossFamilies() = runTest {
        val body = links.getValue("masterdns") + "\n" + links.getValue("snolc") + "\n#order:Stealth%20NL-snolc\n#order:Stealth%20NL-dns\n"
        val withOrder = FakeSource().also { LocationsRepositoryImpl(it).importText(body) }
        assertEquals(listOf(EngineType.Snolc, EngineType.MasterDns), assertNotNull(withOrder.stored).locations.map { it.location.engine })
        val without = FakeSource().also { LocationsRepositoryImpl(it).importText(body.substringBefore("#order")) }
        assertEquals(listOf(EngineType.MasterDns, EngineType.Snolc), assertNotNull(without.stored).locations.map { it.location.engine })
    }

    @Test
    fun cyrillicNamesSurviveInEveryFamily() = runTest {
        val cyr = "Амстердам-1"
        val bad = mutableListOf<String>()
        val enc = java.net.URLEncoder.encode(cyr, "UTF-8")
        for ((family, link) in links) {
            val renamed = link.replace("Stealth%20", enc + "%20").replace("Stealth ", "$cyr ")
            if (renamed == link) continue // the family carries its name elsewhere (qwdtt/csqtt query)
            val source = FakeSource()
            LocationsRepositoryImpl(source).importText(renamed)
            val name = assertNotNull(source.stored).locations.single().name
            if (!name.startsWith(cyr)) bad += "$family='$name'"
        }
        assertTrue(bad.isEmpty(), "names mangled: $bad")
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

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    @Test
    fun trustTunnelNameInsideTheBlobBecomesTheLocationName() = runTest {
        fun tlv(tag: Int, v: String) = v.encodeToByteArray().let { byteArrayOf(tag.toByte(), it.size.toByte()) + it }
        val blob = tlv(1, "h.example.com") + tlv(2, "203.0.113.7:443") + tlv(12, "🇷🇺Москва (TT)")
        val link = "tt://?" + kotlin.io.encoding.Base64.UrlSafe.encode(blob).trimEnd('=')
        val body = links.getValue("snolc") + "\n" + link + "\n"
        val source = FakeSource()
        LocationsRepositoryImpl(source).importText(body)
        val names = assertNotNull(source.stored).locations.map { it.name }
        assertTrue("🇷🇺Москва (TT)" in names, "names: $names")
    }

    /** Encrypted subscription (panel "зашифрованная подписка"): lines are `yptun-enc://` + the same links, produced by app/subcrypt.py. */
    @Test
    fun encryptedSubscriptionImportsLocked() = runTest {
        val enc = listOf(
            "yptun-enc://v1/Do26vXS1jq3CxJ-6XGVBoB_4jbqnlre-nqdQuMD5T7aviLWfI7bNoZiPOuhAr-chTP5UP-B3JokCD1jKM-dQueHVwjHfACHsaTgui-gVVWyxDmPeuvnK_WftJUJ4MoPcVcAXg7dHBqgl5nHTtmvJhk321hM2IHAE7EIJlpjOviH7ZmV_OBQSVoC2kmG238V413NwI1-RLht0rshtEE_pK3unOouIVyEP1A2pn3-jlBUevA_OhDfAtcWqbLYbUSdQvqprt2Qw8fmI0vQQ41bE2izr923TjxGHHLtGSkDviu6B3r2S22ccIdjaLb65kQ",
            "yptun-enc://v1/--RFO6lshrJ0RXSzEVdsGgyBexlKV-uMANoWEuemt0hUYRbTOtlhGy5TDczqWFNBr7wOFBpOTo-MqkmZ0HXUMXDBKR4Ge4lcDnyEf1MtXIEltN3f64zn1f9IReo3sleNAbMxm18vO0FfECq16Z2jzdlJ62v1KAkAHs73rmUPARQ",
        )
        val locked = FakeSource()
        LocationsRepositoryImpl(locked).importText(enc.joinToString("\n") + "\n")
        val stored = assertNotNull(locked.stored)
        assertEquals(setOf(EngineType.OpenFlux, EngineType.Snolc), stored.locations.map { it.location.engine }.toSet())
        assertTrue(stored.locations.all { it.metadata?.locked == true })

        val open = FakeSource()
        LocationsRepositoryImpl(open).importText(links.getValue("openflux") + "\n" + links.getValue("snolc") + "\n")
        assertTrue(assertNotNull(open.stored).locations.none { it.metadata?.locked == true })

        val backup = LocationsRepositoryImpl(locked).exportBundle()
        assertTrue("cloud.mail.ru" !in backup && "locations\":[]" in backup.replace(" ", ""), "locked locations must stay out of the backup")
    }

    /** A locked VK-TURN location lets the user set their own VK call link; it is stored as an override. */
    @Test
    fun lockedVkTurnLocationAcceptsOwnCallLink() = runTest {
        val source = FakeSource()
        val repo = LocationsRepositoryImpl(source)
        repo.importText("yptun-enc://v1/JVBUZ7U1iOvnfPq-QWNzPehb8T-2iuhOFoaaaChf9K3oITd7cVxM5XGBVXsnX2fqcTy4CIhfD1uWyb1robI-XUa7A4C7v-cHJ8sgOss0fEMfUKK4VG32GKuNKzQc6wVAPutWCRMzMIGrHYzr4W7YmV6sPLI1XNmAQrxQJ0K8Z0Dmw5X-YY-nI1tMHcAEuHZBcC7YKYNwhEPJ1TuEBS6DLfB9Q0ev3Ipg6lfK9EsyCzhfDMgyy8_fGQ_Bke0qwQqotm0zNm8PU51uBOCdmG9ABkXrj63ap2JPXmuPS6WcWDHAquxX2sOyqOOAvxyXdc7lnjokdi5BeUm57rsH3V-5HJfAFlKObJawlIacSL6Ex69BcqLfb7HUfdc4Y2w00ctLWEecHX3zSRvC2UO2\n")
        val entry = assertNotNull(source.stored).locations.single()
        assertEquals(true, entry.metadata?.locked)
        repo.setLocationVkLink(entry.storageId, " https://vk.com/call/join/abc ")
        val after = assertNotNull(source.stored).locations.single()
        assertEquals("https://vk.com/call/join/abc", after.location.vkturn?.vkLink)
        assertEquals("https://vk.com/call/join/abc", after.metadata?.vkLinkOverride)
        assertEquals(true, after.metadata?.locked)
    }
}
