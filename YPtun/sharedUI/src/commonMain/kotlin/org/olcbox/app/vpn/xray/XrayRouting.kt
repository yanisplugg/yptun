package org.olcbox.app.vpn.xray

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
 * Translates a [RoutingProfile] into an Xray-core `routing` object. Xray natively accepts
 * `geosite:`/`geoip:`/`domain:`/`full:`/`regexp:` selectors and CIDRs in the rule `domain`/`ip`
 * arrays, so the profile's buckets map almost 1:1 — no geo expansion needed here (the referenced
 * `geoip.dat`/`geosite.dat` must be present in XRAY_LOCATION_ASSET, downloaded from the profile URLs).
 */
object XrayRouting {

    const val DIRECT_TAG = "direct"
    const val BLOCK_TAG = "block"
    const val PROXY_TAG = "proxy"

    /** Maps the Happ/v2ray domainStrategy onto Xray's accepted values (or the expert override). */
    fun domainStrategy(profile: RoutingProfile): String {
        // Expert mode: honor the explicit per-core Xray strategy when set.
        if (profile.expertEnabled && profile.xrayDomainStrategy.isNotBlank()) return profile.xrayDomainStrategy
        return when (profile.domainStrategy) {
            "IPIfNonMatch", "IPOnDemand", "AsIs" -> profile.domainStrategy
            "ipv4_only", "ipv6_only", "prefer_ipv4", "prefer_ipv6" -> "IPIfNonMatch"
            else -> "AsIs"
        }
    }

    /** The `routing` object: `{ domainStrategy, rules: [...] }`, ordered by [RoutingProfile.routeOrder]. */
    fun routingObject(profile: RoutingProfile): JsonObject = buildJsonObject {
        put("domainStrategy", domainStrategy(profile))
        put("rules", rules(profile))
    }

    fun rules(profile: RoutingProfile): JsonArray = buildJsonArray {
        bucketRules(profile).forEach { add(it) }
        // When not a global proxy, anything unmatched falls through to direct (instead of the
        // proxy, which is Xray's default first-outbound behaviour).
        catchAllDirectRule(profile)?.let { add(it) }
    }

    /** The ordered direct/block/proxy field rules only (no fall-through). Reused for raw-config merge. */
    fun bucketRules(profile: RoutingProfile): List<JsonObject> {
        val order = profile.routeOrder.split('-')
            .map { it.trim().lowercase() }
            .filter { it in RoutingProfile.DEFAULT_ORDER }
            .ifEmpty { RoutingProfile.DEFAULT_ORDER }
        return order.flatMap { bucket ->
            when (bucket) {
                "block" -> bucketSplitRules(profile.blockSites, profile.blockIp, BLOCK_TAG)
                "direct" -> bucketSplitRules(profile.directSites, profile.directIp, DIRECT_TAG)
                "proxy" -> bucketSplitRules(profile.proxySites, profile.proxyIp, PROXY_TAG)
                else -> emptyList()
            }
        }
    }

    /** The "everything unmatched → direct" fall-through, or null for a global-proxy profile. */
    fun catchAllDirectRule(profile: RoutingProfile): JsonObject? {
        if (profile.globalProxy) return null
        return buildJsonObject {
            put("type", "field")
            putJsonArray("network") { add("tcp"); add("udp") }
            put("outboundTag", DIRECT_TAG)
        }
    }

    /**
     * SEPARATE domain and ip rules for a bucket (a domain rule and/or an ip rule, same outboundTag).
     *
     * CRITICAL: Xray AND-matches `domain` and `ip` WITHIN a single rule — a rule with both only fires
     * when the destination domain matches AND its resolved IP matches. Combining them (the old form)
     * made `domain:ru → direct` apply only to .ru sites that ALSO resolve to a Russian IP, so .ru sites
     * on foreign CDNs leaked through the proxy — i.e. "routing ignored on xray/xhttp" while sing-box
     * (which already splits the matchers into OR'd rules) worked. Emitting them as two rules makes them
     * OR (match domain OR match ip), matching sing-box and the user's intent.
     */
    private fun bucketSplitRules(sites: List<String>, ips: List<String>, tag: String): List<JsonObject> {
        val s = sites.map { it.trim() }.filter { it.isNotEmpty() }
        // `asn:` selectors are expanded to CIDRs before config build; drop any unresolved ones so they
        // never reach xray's `ip` array (which would reject the whole config).
        val i = ips.map { it.trim() }
            .filter { it.isNotEmpty() && !org.olcbox.app.data.model.Asn.isSelector(it) }
        return buildList {
            if (s.isNotEmpty()) add(buildJsonObject {
                put("type", "field")
                putJsonArray("domain") { s.forEach { add(it) } }
                put("outboundTag", tag)
            })
            if (i.isNotEmpty()) add(buildJsonObject {
                put("type", "field")
                putJsonArray("ip") { i.forEach { add(it) } }
                put("outboundTag", tag)
            })
        }
    }
}
