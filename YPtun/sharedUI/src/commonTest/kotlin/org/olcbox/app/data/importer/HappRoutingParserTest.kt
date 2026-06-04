package org.olcbox.app.data.importer

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HappRoutingParserTest {

    @OptIn(ExperimentalEncodingApi::class)
    private fun happLink(jsonBody: String): String {
        // Happ uses url-safe base64, often unpadded.
        val b64 = Base64.UrlSafe.encode(jsonBody.encodeToByteArray()).trimEnd('=')
        return "happ://routing/add/$b64"
    }

    @Test
    fun parsesHappRoutingJson() {
        val body = """
            {
              "name": "RuNet",
              "blocksites": ["geosite:category-ads-all"],
              "directip": ["geoip:ru", "10.0.0.0/8"],
              "directsites": ["geosite:ru", "domain:vk.com", "domain:yandex.ru"],
              "dnshosts": {"cloudflare-dns.com": "1.1.1.1"},
              "domainstrategy": "IPIfNonMatch",
              "geoipurl": "https://example.com/geoip.dat",
              "geositeurl": "https://example.com/geosite.dat",
              "globalproxy": true,
              "routeorder": "block-direct-proxy"
            }
        """.trimIndent()

        val p = HappRoutingParser.parse(happLink(body))!!
        assertEquals("RuNet", p.name)
        assertEquals(listOf("geosite:category-ads-all"), p.blockSites)
        assertEquals(listOf("geoip:ru", "10.0.0.0/8"), p.directIp)
        assertTrue(p.directSites.contains("domain:vk.com"))
        assertEquals("1.1.1.1", p.dnsHosts["cloudflare-dns.com"])
        assertEquals("IPIfNonMatch", p.domainStrategy)
        assertEquals("https://example.com/geoip.dat", p.geoipUrl)
        assertEquals("block-direct-proxy", p.routeOrder)
        assertTrue(p.globalProxy)
        assertTrue(p.needsGeoFiles())
    }

    @Test
    fun ignoresUnknownKeysAndDefaults() {
        val p = HappRoutingParser.parse(happLink("""{"name":"Minimal","somethingNew":42}"""))!!
        assertEquals("Minimal", p.name)
        assertTrue(p.directSites.isEmpty())
        // No geo: selectors → no geo files needed.
        assertEquals(false, p.needsGeoFiles())
    }

    @Test
    fun rejectsNonHappLinks() {
        assertNull(HappRoutingParser.parse("https://example.com"))
        assertNull(HappRoutingParser.parse("vless://uuid@host:443"))
        assertNull(HappRoutingParser.parse("happ://routing/add/"))
        assertEquals(false, HappRoutingParser.isHappRoutingLink("happ://something/else"))
        assertTrue(HappRoutingParser.isHappRoutingLink("happ://routing/add/abc"))
    }
}
