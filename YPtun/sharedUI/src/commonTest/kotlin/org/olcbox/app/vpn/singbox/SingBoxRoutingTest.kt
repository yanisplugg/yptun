package org.olcbox.app.vpn.singbox

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.olcbox.app.data.model.RoutingProfile
import org.olcbox.app.data.model.SingBoxRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SingBoxRoutingTest {

    private fun List<JsonObject>.firstWith(field: String) = firstOrNull { it.containsKey(field) }

    @Test
    fun bucketsMapToSingleMatcherRules() {
        val p = RoutingProfile(
            name = "RuNet",
            blockSites = listOf("geosite:category-ads-all"),
            directSites = listOf("geosite:ru", "domain:vk.com", "full:exact.example"),
            directIp = listOf("geoip:ru", "10.0.0.0/8"),
            routeOrder = "block-direct-proxy",
            globalProxy = true,
        )
        val rules = SingBoxRouting.rules(p).map { it.jsonObject }

        // block: one combined rule-set rule (reject). direct: rule_set(geosite+geoip) + domain_suffix
        // + domain + ip_cidr. proxy: empty.
        val block = rules.first { it["action"]?.jsonPrimitive?.content == "reject" }
        assertEquals(
            "geosite-category-ads-all",
            block["rule_set"]!!.jsonArray[0].jsonPrimitive.content,
        )

        val directs = rules.filter { it["outbound"]?.jsonPrimitive?.content == "direct" }
        // geosite:ru + geoip:ru combine into one rule_set rule (OR-matched, proven shipped form).
        val ruleSetRule = directs.firstWith("rule_set")!!
        val tags = ruleSetRule["rule_set"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(tags.contains("geosite-ru"))
        assertTrue(tags.contains("geoip-ru"))

        // domain:vk.com → dotted suffix ".vk.com" (matches *.vk.com, never "evilvk.com").
        val suffixRule = directs.firstWith("domain_suffix")!!
        val suffixes = suffixRule["domain_suffix"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(suffixes.contains(".vk.com"))

        // exact bucket has both the domain:vk.com label itself and the full:exact.example value.
        val exactRule = directs.firstWith("domain")!!
        val exacts = exactRule["domain"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(exacts.contains("vk.com"))
        assertTrue(exacts.contains("exact.example"))

        val ipRule = directs.firstWith("ip_cidr")!!
        assertEquals("10.0.0.0/8", ipRule["ip_cidr"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun bareIpBecomesCidr() {
        val p = RoutingProfile(directIp = listOf("1.2.3.4", "2606:4700::1111"), routeOrder = "direct")
        val ipFields = SingBoxRouting.rules(p).map { it.jsonObject }
            .first { it.containsKey("ip_cidr") }["ip_cidr"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertTrue(ipFields.contains("1.2.3.4/32"))
        assertTrue(ipFields.contains("2606:4700::1111/128"))
    }

    @Test
    fun ruleSetsAreRemoteSrsForGeoSelectors() {
        val p = RoutingProfile(
            directSites = listOf("geosite:ru"),
            blockIp = listOf("geoip:cn"),
        )
        val sets = SingBoxRouting.ruleSets(p).map { it.jsonObject }
        assertEquals(2, sets.size)
        val geosite = sets.first { it["tag"]!!.jsonPrimitive.content == "geosite-ru" }
        assertEquals("remote", geosite["type"]!!.jsonPrimitive.content)
        assertEquals("direct", geosite["download_detour"]!!.jsonPrimitive.content)
        assertTrue(geosite["url"]!!.jsonPrimitive.content.endsWith("geosite-ru.srs"))
        val geoip = sets.first { it["tag"]!!.jsonPrimitive.content == "geoip-cn" }
        assertTrue(geoip["url"]!!.jsonPrimitive.content.endsWith("geoip-cn.srs"))
    }

    @Test
    fun customRuleSetBaseIsHonoured() {
        val p = RoutingProfile(directSites = listOf("geosite:ru"))
        val set = SingBoxRouting.ruleSets(p, geositeBase = "https://example.com/rs").first().jsonObject
        assertEquals("https://example.com/rs/geosite-ru.srs", set["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun noGeoSelectorsMeansNoRuleSets() {
        val p = RoutingProfile(directSites = listOf("domain:vk.com"), directIp = listOf("10.0.0.0/8"))
        assertTrue(SingBoxRouting.ruleSets(p).isEmpty())
    }

    @Test
    fun finalOutboundFollowsGlobalProxy() {
        assertEquals("proxy", SingBoxRouting.finalOutbound(RoutingProfile(globalProxy = true)))
        assertEquals("direct", SingBoxRouting.finalOutbound(RoutingProfile(globalProxy = false)))
    }

    @Test
    fun emptyProfileProducesNoRules() {
        assertTrue(SingBoxRouting.rules(RoutingProfile()).isEmpty())
    }

    // --- v2rayNG-style manual rules ---

    @Test
    fun manualRuleCombinesAllFieldsIntoOneObject() {
        val rule = SingBoxRule(
            outbound = SingBoxRule.OUT_DIRECT,
            domains = listOf("geosite:ru", "domain:vk.com", "full:exact.example"),
            ip = listOf("geoip:ru", "10.0.0.0/8"),
            port = "443, 8000:9000",
            network = "tcp",
            protocol = listOf("tls", "quic"),
        )
        val out = SingBoxRouting.manualRules(listOf(rule)).map { it.jsonObject }
        assertEquals(1, out.size) // ONE route rule per SingBoxRule (AND across fields)
        val r = out.first()
        assertEquals("direct", r["outbound"]!!.jsonPrimitive.content)
        val ruleSets = r["rule_set"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(ruleSets.contains("geosite-ru"))
        assertTrue(ruleSets.contains("geoip-ru"))
        assertTrue(r["domain_suffix"]!!.jsonArray.map { it.jsonPrimitive.content }.contains(".vk.com"))
        assertTrue(r["domain"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("exact.example"))
        assertTrue(r["ip_cidr"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("10.0.0.0/8"))
        assertTrue(r["port"]!!.jsonArray.map { it.jsonPrimitive.int }.contains(443))
        assertEquals("8000:9000", r["port_range"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("tcp", r["network"]!!.jsonPrimitive.content)
        assertTrue(r["protocol"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("quic"))
    }

    @Test
    fun manualBlockRuleUsesRejectAction() {
        val r = SingBoxRouting.manualRules(
            listOf(SingBoxRule(outbound = SingBoxRule.OUT_BLOCK, domains = listOf("domain:ads.example")))
        ).first().jsonObject
        assertEquals("reject", r["action"]!!.jsonPrimitive.content)
        assertTrue(!r.containsKey("outbound"))
    }

    @Test
    fun disabledOrEmptyManualRulesAreSkipped() {
        val rules = listOf(
            SingBoxRule(outbound = SingBoxRule.OUT_PROXY, domains = listOf("domain:a.com"), enabled = false),
            SingBoxRule(), // no matcher
        )
        assertTrue(SingBoxRouting.manualRules(rules).isEmpty())
        assertTrue(SingBoxRouting.manualRuleSets(rules).isEmpty())
    }

    @Test
    fun manualRuleSetsEmittedForGeoSelectors() {
        val rules = listOf(SingBoxRule(domains = listOf("geosite:cn"), ip = listOf("geoip:cn")))
        val sets = SingBoxRouting.manualRuleSets(rules).map { it.jsonObject }
        assertEquals(2, sets.size)
        assertTrue(sets.any { it["tag"]!!.jsonPrimitive.content == "geosite-cn" })
        assertTrue(sets.any { it["tag"]!!.jsonPrimitive.content == "geoip-cn" })
    }
}
