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
        // Through the tunnel, not direct — the .srs host is censored where this app is used, and a
        // failed initial fetch aborts the whole core. See SingBoxRuleSetDetourTest.
        assertEquals(SingBoxRouting.PROXY_TAG, geosite["download_detour"]!!.jsonPrimitive.content)
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

    /** Every editor field (2-10) must emit its real sing-box route-rule key. */
    @Test
    fun everyFieldEmitsItsSingBoxKey() {
        val rule = SingBoxRule(
            name = "label",                       // 1: UI-only, must NOT be emitted
            source = listOf("192.168.0.0/16"),    // 2: source_ip_cidr
            sourcePort = "1000:2000,53",          // 3: source_port_range + source_port
            networkType = listOf("wifi", "cellular"), // 4: network_type
            client = listOf("chromium"),          // 5: client
            networkIsExpensive = true,            // 6: network_is_expensive
            clashMode = "Global",                 // 7: clash_mode
            packageNames = listOf("com.x.y"),     // 8: package_name
            // 9: action handled in dedicated tests below
        )
        val r = SingBoxRouting.manualRules(listOf(rule)).single().jsonObject

        assertTrue(!r.containsKey("name"), "name is a UI-only label and must not be emitted")
        assertEquals("192.168.0.0/16", r["source_ip_cidr"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("1000:2000", r["source_port_range"]!!.jsonArray[0].jsonPrimitive.content)
        assertTrue(r["source_port"]!!.jsonArray.map { it.jsonPrimitive.int }.contains(53))
        val netTypes = r["network_type"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(netTypes.containsAll(listOf("wifi", "cellular")))
        assertEquals("chromium", r["client"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(true, r["network_is_expensive"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("Global", r["clash_mode"]!!.jsonPrimitive.content)
        assertEquals("com.x.y", r["package_name"]!!.jsonArray[0].jsonPrimitive.content)
    }

    /** Field 9: each non-route action emits its `action` token and ignores outbound. */
    @Test
    fun actionTokensAreEmitted() {
        fun actionOf(token: String) = SingBoxRouting.manualRules(
            listOf(SingBoxRule(action = token, outbound = SingBoxRule.OUT_PROXY, domains = listOf("domain:a.com")))
        ).single().jsonObject

        listOf("route-options", "sniff", "resolve", "hijack-dns", "reject").forEach { token ->
            val r = actionOf(token)
            assertEquals(token, r["action"]!!.jsonPrimitive.content)
            assertTrue(!r.containsKey("outbound"), "$token must not emit outbound")
        }
        // action=route (default) emits outbound, no action key.
        val route = actionOf("route")
        assertEquals("proxy", route["outbound"]!!.jsonPrimitive.content)
        assertTrue(!route.containsKey("action"))
    }

    /** Field 10: package_name regex expands against installed packages, merged into package_name. */
    @Test
    fun packageRegexExpandsAgainstInstalledPackages() {
        val installed = listOf("com.google.android.youtube", "com.google.maps", "org.telegram.messenger")
        val rule = SingBoxRule(packageNames = listOf("com.keep.me"), packageRegex = listOf("^com\\.google\\."))
        val expanded = SingBoxRule.expandPackageRegex(listOf(rule), installed).single()
        assertTrue(expanded.packageNames.contains("com.keep.me"))      // pre-existing kept
        assertTrue(expanded.packageNames.contains("com.google.android.youtube"))
        assertTrue(expanded.packageNames.contains("com.google.maps"))
        assertTrue(!expanded.packageNames.contains("org.telegram.messenger")) // non-match excluded
        // The expanded rule emits the matches as package_name.
        val r = SingBoxRouting.manualRules(listOf(expanded)).single().jsonObject
        assertTrue(r["package_name"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("com.google.maps"))
    }
}
