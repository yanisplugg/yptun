package org.olcbox.app.data.model

import org.olcbox.app.data.importer.HappRoutingParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutingProfileLinkTest {

    @Test
    fun happLinkRoundTrips() {
        val original = RoutingProfile(
            name = "RuNet",
            directSites = listOf("geosite:ru", "domain:vk.com"),
            directIp = listOf("geoip:ru", "10.0.0.0/8"),
            blockSites = listOf("geosite:category-ads-all"),
            domainStrategy = "IPIfNonMatch",
            routeOrder = "block-direct-proxy",
            globalProxy = false,
            geoipUrl = "https://example.com/geoip.dat",
            id = "local-only",
        )
        val link = original.toHappLink()
        assertTrue(HappRoutingParser.isHappRoutingLink(link))

        val parsed = HappRoutingParser.parse(link)!!
        // The local-only id is intentionally dropped from the exported link.
        assertEquals("", parsed.id)
        assertEquals(original.copy(id = ""), parsed)
    }

    @Test
    fun needsGeoFilesDetectsGeoSelectors() {
        assertTrue(RoutingProfile(directSites = listOf("geosite:ru")).needsGeoFiles())
        assertTrue(RoutingProfile(blockIp = listOf("geoip:cn")).needsGeoFiles())
        assertEquals(false, RoutingProfile(directSites = listOf("domain:vk.com")).needsGeoFiles())
    }

    @Test
    fun resolveHonoursOverrideAndGlobal() {
        val a = RoutingProfile(name = "A", id = "a")
        val b = RoutingProfile(name = "B", id = "b")
        val state = RoutingProfilesState(profiles = listOf(a, b), globalProfileId = "a")
        // Blank → global.
        assertEquals("a", state.resolve("")?.id)
        // Explicit id wins.
        assertEquals("b", state.resolve("b")?.id)
        // NONE sentinel → no profile despite a global being set.
        assertEquals(null, state.resolve(RoutingProfile.NONE_ID))
    }
}
