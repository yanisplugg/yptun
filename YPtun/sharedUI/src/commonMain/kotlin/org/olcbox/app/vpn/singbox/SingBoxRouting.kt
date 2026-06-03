package org.olcbox.app.vpn.singbox

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.olcbox.app.data.model.RoutingProfile

/**
 * Translates a [RoutingProfile] into sing-box `route.rules` + `route.rule_set` fragments.
 *
 * sing-box (unlike Xray) has no native `geosite:`/`geoip:` selectors: geo categories are referenced
 * through downloaded `.srs` rule-sets. So `geosite:ru` becomes a remote rule-set tagged `geosite-ru`
 * pulled from [geositeBase], and `geoip:ru` becomes `geoip-ru` from [geoipBase] — the same mechanism
 * the toggle-based bypass-Russia / block-ads rules already use.
 *
 * Each bucket (block/direct/proxy) is emitted as a small set of single-matcher rules (one per field
 * type) so the result is unambiguous regardless of sing-box's cross-field matching semantics — the
 * combined geoip+geosite rule-set rule mirrors the proven shipped form. Rules are ordered by
 * [RoutingProfile.routeOrder].
 */
object SingBoxRouting {

    const val PROXY_TAG = "proxy"
    const val DIRECT_TAG = "direct"

    const val DEFAULT_GEOSITE_BASE = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/"
    const val DEFAULT_GEOIP_BASE = "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/"

    /** Parsed split of a bucket's selectors into sing-box matcher dimensions. */
    private data class Selectors(
        val domainSuffix: List<String>,
        val domainExact: List<String>,
        val domainKeyword: List<String>,
        val domainRegex: List<String>,
        val ipCidr: List<String>,
        val geositeTags: List<String>,
        val geoipTags: List<String>,
    )

    /** The route rules for [profile], ordered by its routeOrder. Caller inserts these after `sniff`. */
    fun rules(profile: RoutingProfile): JsonArray = buildJsonArray {
        val order = profile.routeOrder.split('-')
            .map { it.trim().lowercase() }
            .filter { it in RoutingProfile.DEFAULT_ORDER }
            .ifEmpty { RoutingProfile.DEFAULT_ORDER }
        for (bucket in order) {
            when (bucket) {
                "block" -> emitBucket(profile.blockSites, profile.blockIp, reject = true, outbound = null)
                "direct" -> emitBucket(profile.directSites, profile.directIp, reject = false, outbound = DIRECT_TAG)
                "proxy" -> emitBucket(profile.proxySites, profile.proxyIp, reject = false, outbound = PROXY_TAG)
            }
        }
    }

    /** The final outbound when nothing matches: proxy for a global proxy, direct otherwise. */
    fun finalOutbound(profile: RoutingProfile): String =
        if (profile.globalProxy) PROXY_TAG else DIRECT_TAG

    /**
     * The `rule_set` definitions for every geo tag the profile references, as remote `.srs` entries.
     * Returns an empty array when the profile uses no geo selectors.
     */
    fun ruleSets(
        profile: RoutingProfile,
        geositeBase: String = DEFAULT_GEOSITE_BASE,
        geoipBase: String = DEFAULT_GEOIP_BASE,
    ): JsonArray {
        val site = parse(profile.blockSites) + parse(profile.directSites) + parse(profile.proxySites)
        val ip = parseIp(profile.blockIp) + parseIp(profile.directIp) + parseIp(profile.proxyIp)
        val geositeTags = site.flatMap { it.geositeTags }.distinct()
        val geoipTags = ip.flatMap { it.geoipTags }.distinct()
        if (geositeTags.isEmpty() && geoipTags.isEmpty()) return JsonArray(emptyList())
        val siteBase = geositeBase.ifBlank { DEFAULT_GEOSITE_BASE }.let { if (it.endsWith('/')) it else "$it/" }
        val ipBase = geoipBase.ifBlank { DEFAULT_GEOIP_BASE }.let { if (it.endsWith('/')) it else "$it/" }
        return buildJsonArray {
            geositeTags.forEach { tag ->
                addJsonObject {
                    put("type", "remote")
                    put("tag", "geosite-$tag")
                    put("format", "binary")
                    put("url", "${siteBase}geosite-$tag.srs")
                    put("download_detour", DIRECT_TAG)
                }
            }
            geoipTags.forEach { tag ->
                addJsonObject {
                    put("type", "remote")
                    put("tag", "geoip-$tag")
                    put("format", "binary")
                    put("url", "${ipBase}geoip-$tag.srs")
                    put("download_detour", DIRECT_TAG)
                }
            }
        }
    }

    // --- internals ---

    private fun kotlinx.serialization.json.JsonArrayBuilder.emitBucket(
        sites: List<String>,
        ips: List<String>,
        reject: Boolean,
        outbound: String?,
    ) {
        val s = parse(sites)
        val i = parseIp(ips)
        val domainSuffix = s.flatMap { it.domainSuffix }.distinct()
        val domainExact = s.flatMap { it.domainExact }.distinct()
        val domainKeyword = s.flatMap { it.domainKeyword }.distinct()
        val domainRegex = s.flatMap { it.domainRegex }.distinct()
        val ipCidr = i.flatMap { it.ipCidr }.distinct()
        val geositeTags = s.flatMap { it.geositeTags }.distinct()
        val geoipTags = i.flatMap { it.geoipTags }.distinct()

        // Geo rule-sets (geosite + geoip) combined into one OR-matched rule (proven shipped form).
        if (geositeTags.isNotEmpty() || geoipTags.isNotEmpty()) {
            add(matchRule(reject, outbound) {
                putJsonArray("rule_set") {
                    geositeTags.forEach { add("geosite-$it") }
                    geoipTags.forEach { add("geoip-$it") }
                }
            })
        }
        if (domainSuffix.isNotEmpty()) add(matchRule(reject, outbound) { putJsonArray("domain_suffix") { domainSuffix.forEach { add(it) } } })
        if (domainExact.isNotEmpty()) add(matchRule(reject, outbound) { putJsonArray("domain") { domainExact.forEach { add(it) } } })
        if (domainKeyword.isNotEmpty()) add(matchRule(reject, outbound) { putJsonArray("domain_keyword") { domainKeyword.forEach { add(it) } } })
        if (domainRegex.isNotEmpty()) add(matchRule(reject, outbound) { putJsonArray("domain_regex") { domainRegex.forEach { add(it) } } })
        if (ipCidr.isNotEmpty()) add(matchRule(reject, outbound) { putJsonArray("ip_cidr") { ipCidr.forEach { add(it) } } })
    }

    private inline fun matchRule(reject: Boolean, outbound: String?, matcher: JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            matcher()
            if (reject) put("action", "reject") else put("outbound", outbound ?: PROXY_TAG)
        }

    private fun parse(selectors: List<String>): List<Selectors> = selectors.mapNotNull { raw ->
        val v = raw.trim()
        if (v.isEmpty()) return@mapNotNull null
        when {
            v.startsWith("geosite:", true) -> sel(geosite = listOf(v.substringAfter(':').trim().lowercase()))
            v.startsWith("geoip:", true) -> sel(geoip = listOf(v.substringAfter(':').trim().lowercase()))
            v.startsWith("domain:", true) -> sel(suffix = listOf(v.substringAfter(':').trim()))
            v.startsWith("full:", true) -> sel(exact = listOf(v.substringAfter(':').trim()))
            v.startsWith("keyword:", true) -> sel(keyword = listOf(v.substringAfter(':').trim()))
            v.startsWith("regexp:", true) || v.startsWith("regex:", true) -> sel(regex = listOf(v.substringAfter(':').trim()))
            isCidrOrIp(v) -> sel(cidr = listOf(toCidr(v)))
            else -> sel(suffix = listOf(v))
        }
    }

    private fun parseIp(selectors: List<String>): List<Selectors> = selectors.mapNotNull { raw ->
        val v = raw.trim()
        if (v.isEmpty()) return@mapNotNull null
        when {
            v.startsWith("geoip:", true) -> sel(geoip = listOf(v.substringAfter(':').trim().lowercase()))
            v.startsWith("geosite:", true) -> sel(geosite = listOf(v.substringAfter(':').trim().lowercase()))
            else -> sel(cidr = listOf(toCidr(v)))
        }
    }

    private fun sel(
        suffix: List<String> = emptyList(),
        exact: List<String> = emptyList(),
        keyword: List<String> = emptyList(),
        regex: List<String> = emptyList(),
        cidr: List<String> = emptyList(),
        geosite: List<String> = emptyList(),
        geoip: List<String> = emptyList(),
    ) = Selectors(suffix, exact, keyword, regex, cidr, geosite, geoip)

    private fun isCidrOrIp(v: String): Boolean =
        v.contains('/') || v.count { it == ':' } >= 2 || v.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))

    /** sing-box `ip_cidr` wants a CIDR; a bare address becomes a /32 (v4) or /128 (v6). */
    private fun toCidr(v: String): String {
        if (v.contains('/')) return v
        return if (v.contains(':')) "$v/128" else "$v/32"
    }
}

private typealias JsonObjectBuilder = kotlinx.serialization.json.JsonObjectBuilder
