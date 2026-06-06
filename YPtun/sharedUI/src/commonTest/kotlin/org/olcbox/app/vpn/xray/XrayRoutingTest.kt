package org.olcbox.app.vpn.xray

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.RoutingProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XrayRoutingTest {

    @Test
    fun bucketsBecomeRulesInRouteOrder() {
        val p = RoutingProfile(
            name = "RuNet",
            blockSites = listOf("geosite:category-ads-all"),
            directSites = listOf("geosite:ru", "domain:vk.com"),
            directIp = listOf("geoip:ru", "10.0.0.0/8"),
            domainStrategy = "IPIfNonMatch",
            routeOrder = "block-direct-proxy",
            globalProxy = true,
        )
        val routing = XrayRouting.routingObject(p)
        assertEquals("IPIfNonMatch", routing["domainStrategy"]!!.jsonPrimitive.content)

        val rules = routing["rules"]!!.jsonArray
        // block (ads domain) then direct split into a domain rule + an ip rule (Xray AND-matches
        // domain+ip in one rule, so they MUST be separate to OR them). No proxy bucket, no fallthrough.
        assertEquals(3, rules.size)

        val block = rules[0].jsonObject
        assertEquals("block", block["outboundTag"]!!.jsonPrimitive.content)
        assertEquals("geosite:category-ads-all", block["domain"]!!.jsonArray[0].jsonPrimitive.content)

        val directDomainRule = rules[1].jsonObject
        assertEquals("direct", directDomainRule["outboundTag"]!!.jsonPrimitive.content)
        val directDomains = directDomainRule["domain"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(directDomains.contains("geosite:ru"))
        assertTrue(directDomains.contains("domain:vk.com"))
        // The domain rule must NOT also carry ip (that would AND them and drop .ru on non-RU IPs).
        assertTrue(directDomainRule["ip"] == null)

        val directIpRule = rules[2].jsonObject
        assertEquals("direct", directIpRule["outboundTag"]!!.jsonPrimitive.content)
        val directIps = directIpRule["ip"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(directIps.contains("geoip:ru"))
        assertTrue(directIps.contains("10.0.0.0/8"))
        assertTrue(directIpRule["domain"] == null)
    }

    @Test
    fun nonGlobalProxyAddsDirectFallthrough() {
        val p = RoutingProfile(
            name = "Selective",
            proxySites = listOf("domain:openai.com"),
            globalProxy = false,
            routeOrder = "proxy",
        )
        val rules = XrayRouting.rules(p)
        // proxy rule + the !globalProxy direct fallthrough.
        assertEquals(2, rules.size)
        assertEquals("proxy", rules[0].jsonObject["outboundTag"]!!.jsonPrimitive.content)
        assertEquals("direct", rules[1].jsonObject["outboundTag"]!!.jsonPrimitive.content)
    }

    @Test
    fun unknownDomainStrategyFallsBackToAsIs() {
        assertEquals("AsIs", XrayRouting.domainStrategy(RoutingProfile(domainStrategy = "weird")))
        assertEquals("IPIfNonMatch", XrayRouting.domainStrategy(RoutingProfile(domainStrategy = "ipv4_only")))
    }
}
