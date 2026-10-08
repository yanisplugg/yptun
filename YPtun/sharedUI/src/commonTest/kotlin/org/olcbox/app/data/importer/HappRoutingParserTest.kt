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

    @OptIn(ExperimentalEncodingApi::class)
    private fun b64(jsonBody: String): String =
        Base64.UrlSafe.encode(jsonBody.encodeToByteArray()).trimEnd('=')

    @Test
    fun parsesRoutingScheme() {
        val body = """{"name":"RoutingScheme","directsites":["domain:ru"]}"""
        val payload = b64(body)
        // All three routing:// forms decode identically to the Happ link.
        listOf(
            "routing://routing/add/$payload",
            "routing://add/$payload",
            "routing://$payload",
        ).forEach { link ->
            assertTrue(HappRoutingParser.isHappRoutingLink(link), "should recognise $link")
            val p = HappRoutingParser.parseAny(link)!!
            assertEquals("RoutingScheme", p.name)
            assertTrue(p.directSites.contains("domain:ru"))
        }
    }

    @Test
    fun parsesPascalCaseHappRoutingJsonWithStringBooleans() {
        val userLink = "happ://routing/add/eyJOYW1lIjoiTm9kZWxlc3MgUlUiLCJHbG9iYWxQcm94eSI6InRydWUiLCJSb3V0ZU9yZGVyIjoiYmxvY2stcHJveHktZGlyZWN0IiwiUmVtb3RlRE5TVHlwZSI6IkRvSCIsIlJlbW90ZUROU0RvbWFpbiI6Imh0dHBzOi8vZG5zLmdvb2dsZS9kbnMtcXVlcnkiLCJSZW1vdGVETlNJUCI6IiIsIkRvbWVzdGljRE5TVHlwZSI6IkRvVSIsIkRvbWVzdGljRE5TRG9tYWluIjoiIiwiRG9tZXN0aWNETlNJUCI6Ijc3Ljg4LjguOCIsIkdlb2lwdXJsIjoiaHR0cHM6Ly9yYXcuZ2l0aHVidXNlcmNvbnRlbnQuY29tL3J1bmV0ZnJlZWRvbS9ydXNzaWEtdjJyYXktcnVsZXMtZGF0L3JlbGVhc2UvZ2VvaXAuZGF0IiwiR2Vvc2l0ZXVybCI6Imh0dHBzOi8vcmF3LmdpdGh1YnVzZXJjb250ZW50LmNvbS9ydXNzaWEtdjJyYXktcnVsZXMtZGF0L3JlbGVhc2UvZ2Vvc2l0ZS5kYXQiLCJMYXN0VXBkYXRlZCI6IiIsIkRuc0hvc3RzIjp7fSwiRGlyZWN0U2l0ZXMiOlsia2Vvc2l0ZTpydS1hdmFpbGFibGUtb25seS1pbnNpZGUiLCJnZW9zaXRlOnlhbmRleCJdLCJEaXJlY3RJcCI6WyJnZW9pcDlydSIsIjE5Mi4xNjguMC4wLzE2Il0sIlByb3h5U2l0ZXMiOltdLCJQcm94eUlwIjpbXSwiQmxvY2tTaXRlcyI6W10sIkJsb2NrSXAiOltdLCJEb21haW5TdHJhdGVneSI6IklQSWZOb25NYXRjaCIsIkZha2VETlMiOiJmYWxzZSIsIlVzZUNodW5rRmlsZXMiOiJ0cnVlIn0="
        val p = HappRoutingParser.parse(userLink)
        assertNotNull(p)
        assertEquals("Nodeless RU", p.name)
        assertTrue(p.globalProxy)
        assertEquals(false, p.fakeDns)
        assertEquals("block-proxy-direct", p.routeOrder)
        assertEquals(listOf("geosite:ru-available-only-inside", "geosite:yandex"), p.directSites)
        assertEquals(listOf("geoip:ru", "192.168.0.0/16"), p.directIp)
        assertEquals(4, p.ruleCount())
    }

    @Test
    fun rejectsNonHappLinks() {
        assertNull(HappRoutingParser.parse("https://example.com"))
        assertNull(HappRoutingParser.parse("vless://uuid@host:443"))
        assertNull(HappRoutingParser.parse("happ://routing/add/"))
        assertEquals(false, HappRoutingParser.isHappRoutingLink("happ://something/else"))
        assertTrue(HappRoutingParser.isHappRoutingLink("happ://routing/add/abc"))
        assertTrue(HappRoutingParser.isHappRoutingLink("happ://routing/onadd/abc"))
    }
}
