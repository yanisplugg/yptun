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

    /** Maps the Happ/v2ray domainStrategy onto Xray's accepted values. */
    fun domainStrategy(profile: RoutingProfile): String = when (profile.domainStrategy) {
        "IPIfNonMatch", "IPOnDemand", "AsIs" -> profile.domainStrategy
        "ipv4_only", "ipv6_only", "prefer_ipv4", "prefer_ipv6" -> "IPIfNonMatch"
        else -> "AsIs"
    }

    /** The `routing` object: `{ domainStrategy, rules: [...] }`, ordered by [RoutingProfile.routeOrder]. */
    fun routingObject(profile: RoutingProfile): JsonObject = buildJsonObject {
        put("domainStrategy", domainStrategy(profile))
        put("rules", rules(profile))
    }

    fun rules(profile: RoutingProfile): JsonArray = buildJsonArray {
        val order = profile.routeOrder.split('-')
            .map { it.trim().lowercase() }
            .filter { it in RoutingProfile.DEFAULT_ORDER }
            .ifEmpty { RoutingProfile.DEFAULT_ORDER }
        for (bucket in order) {
            when (bucket) {
                "block" -> rule(profile.blockSites, profile.blockIp, BLOCK_TAG)?.let { add(it) }
                "direct" -> rule(profile.directSites, profile.directIp, DIRECT_TAG)?.let { add(it) }
                "proxy" -> rule(profile.proxySites, profile.proxyIp, PROXY_TAG)?.let { add(it) }
            }
        }
        // When not a global proxy, anything unmatched falls through to direct (instead of the
        // proxy, which is Xray's default first-outbound behaviour).
        if (!profile.globalProxy) {
            addJsonObject {
                put("type", "field")
                putJsonArray("network") { add("tcp"); add("udp") }
                put("outboundTag", DIRECT_TAG)
            }
        }
    }

    /** A single field rule for a bucket, or null when both lists are empty. */
    private fun rule(sites: List<String>, ips: List<String>, tag: String): JsonObject? {
        val s = sites.map { it.trim() }.filter { it.isNotEmpty() }
        val i = ips.map { it.trim() }.filter { it.isNotEmpty() }
        if (s.isEmpty() && i.isEmpty()) return null
        return buildJsonObject {
            put("type", "field")
            if (s.isNotEmpty()) putJsonArray("domain") { s.forEach { add(it) } }
            if (i.isNotEmpty()) putJsonArray("ip") { i.forEach { add(it) } }
            put("outboundTag", tag)
        }
    }
}
