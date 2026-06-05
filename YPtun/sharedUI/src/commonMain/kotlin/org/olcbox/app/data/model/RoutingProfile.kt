package org.olcbox.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * A named routing profile, Happ-compatible. The wire shape mirrors the JSON carried by
 * `happ://routing/add/<base64url-json>` links so such links deserialize straight into this class
 * (and our profiles re-export to the same format). It is applied to the generated sing-box / xray
 * config — either globally or pinned to a single location.
 *
 * Lists accept Xray/sing-box selectors: `geoip:ru`, `geosite:category-ads-all`, `domain:vk.com`,
 * CIDRs (`10.0.0.0/8`) and bare domains. `geoip:`/`geosite:` entries require the corresponding
 * `geoip.dat` / `geosite.dat` to be present (downloaded from [geoipUrl] / [geositeUrl]).
 */
@Serializable
data class RoutingProfile(
    @SerialName("name") val name: String = "",

    // Routing buckets (evaluated in [routeOrder]).
    @SerialName("blockip") val blockIp: List<String> = emptyList(),
    @SerialName("blocksites") val blockSites: List<String> = emptyList(),
    @SerialName("directip") val directIp: List<String> = emptyList(),
    @SerialName("directsites") val directSites: List<String> = emptyList(),
    @SerialName("proxyip") val proxyIp: List<String> = emptyList(),
    @SerialName("proxysites") val proxySites: List<String> = emptyList(),

    // DNS.
    @SerialName("dnshosts") val dnsHosts: Map<String, String> = emptyMap(),
    @SerialName("remotednsdomain") val remoteDnsDomain: String = "",
    @SerialName("remotednsip") val remoteDnsIp: String = "",
    @SerialName("remotednstype") val remoteDnsType: String = "",
    @SerialName("domesticdnsdomain") val domesticDnsDomain: String = "",
    @SerialName("domesticdnsip") val domesticDnsIp: String = "",
    @SerialName("domesticdnstype") val domesticDnsType: String = "",
    @SerialName("fakedns") val fakeDns: Boolean = false,

    @SerialName("domainstrategy") val domainStrategy: String = "IPIfNonMatch",
    /** Order the buckets are applied, dash-separated, e.g. "block-direct-proxy". */
    @SerialName("routeorder") val routeOrder: String = "block-direct-proxy",
    /** When true, everything not matched by a direct/block rule goes through the proxy. */
    @SerialName("globalproxy") val globalProxy: Boolean = true,

    // Geo database sources (each a URL to a v2ray-format .dat).
    @SerialName("geoipurl") val geoipUrl: String = "",
    @SerialName("geositeurl") val geositeUrl: String = "",

    // --- Expert (per-core) overrides. Local extension to the Happ format; ignored by other clients. ---
    /** When on, the per-core fields below override the generic settings for fine-grained control. */
    @SerialName("expert") val expertEnabled: Boolean = false,
    /** Xray `routing.domainStrategy` override (blank → derive from [domainStrategy]). */
    @SerialName("xraydomainstrategy") val xrayDomainStrategy: String = "",
    /** Xray inbound domain sniffing (needed for `domain:` rules to match TLS/HTTP by SNI/Host). */
    @SerialName("xraysniffing") val xraySniffing: Boolean = true,
    /** Xray `sniffing.routeOnly`: sniff for ROUTING only, keep dialing the original destination. */
    @SerialName("xrayrouteonly") val xrayRouteOnly: Boolean = false,
    /** sing-box DNS/route `strategy` override (blank → inherit the global traffic strategy). */
    @SerialName("singboxdomainstrategy") val singboxDomainStrategy: String = "",
    /** sing-box `sniff` action (recover the domain so domain rules match). */
    @SerialName("singboxsniff") val singboxSniff: Boolean = true,
    /** sing-box `resolve` action: resolve the sniffed domain to an IP so geoip/ip_cidr rules match. */
    @SerialName("singboxresolve") val singboxResolve: Boolean = false,

    /** Local-only id for our storage; not part of the Happ wire format. */
    @SerialName("_id") val id: String = "",
) {
    /**
     * True if any bucket carries an IP-based matcher (a CIDR or a `geoip:` selector). sing-box must
     * `resolve` sniffed domains before such rules can match a domain connection.
     */
    fun usesIpRules(): Boolean =
        (blockIp + directIp + proxyIp).any { it.trim().isNotEmpty() }

    /** True if any bucket references a `geoip:`/`geosite:` selector (needs the .dat files). */
    fun needsGeoFiles(): Boolean =
        (blockSites + directSites + proxySites).any { it.startsWith("geosite:", ignoreCase = true) } ||
            (blockIp + directIp + proxyIp).any { it.startsWith("geoip:", ignoreCase = true) }

    /**
     * A copy with every `geoip:`/`geosite:` selector removed (plain domains and CIDRs kept). Used as
     * a fallback on the Xray core when the geo `.dat` files aren't available, so the non-geo rules
     * (e.g. `domain:ru → direct`) still take effect instead of the whole profile being ignored.
     */
    fun withoutGeoSelectors(): RoutingProfile {
        fun strip(list: List<String>) = list.filterNot {
            val v = it.trim()
            v.startsWith("geoip:", ignoreCase = true) || v.startsWith("geosite:", ignoreCase = true)
        }
        return copy(
            blockIp = strip(blockIp), blockSites = strip(blockSites),
            directIp = strip(directIp), directSites = strip(directSites),
            proxyIp = strip(proxyIp), proxySites = strip(proxySites),
        )
    }

    fun displayName(): String = name.ifBlank { "Routing profile" }

    /** Total number of selectors across every bucket (for a one-line summary in the UI). */
    fun ruleCount(): Int =
        blockIp.size + blockSites.size + directIp.size + directSites.size + proxyIp.size + proxySites.size

    /** Re-encodes this profile to a `happ://routing/add/<base64url-json>` link for sharing/export. */
    @OptIn(ExperimentalEncodingApi::class)
    fun toHappLink(): String {
        // Drop the local-only id before export so the link is portable.
        val json = exportJson.encodeToString(serializer(), copy(id = ""))
        val payload = Base64.UrlSafe.encode(json.encodeToByteArray()).trimEnd('=')
        return "happ://routing/add/$payload"
    }

    companion object {
        /** Buckets in the order named by [routeOrder] (unknown tokens ignored). */
        val DEFAULT_ORDER = listOf("block", "direct", "proxy")

        /** Sentinel id meaning "explicitly no routing profile" (overrides the global default). */
        const val NONE_ID = "__none__"

        /** Stable id of the built-in convenience profile, seeded on first run. */
        const val DEFAULT_RU_DIRECT_ID = "default-ru-direct"

        /**
         * Built-in convenience profile: Russian sites (`.ru`) and Russian IPs go direct (no VPN),
         * everything else through the proxy. Uses `geoip:ru` (reliable country data in any geoip.dat)
         * and `domain:ru` (no geo file needed); add `geosite:ru` manually if your geosite.dat has it.
         */
        fun russiaDirect(): RoutingProfile = RoutingProfile(
            name = "Россия напрямую",
            directSites = listOf("domain:ru"),
            directIp = listOf("geoip:ru"),
            globalProxy = true,
            routeOrder = "block-direct-proxy",
            id = DEFAULT_RU_DIRECT_ID,
        )

        /**
         * Default geo `.dat` sources (v2ray format) for xray-core. The runetfreedom releases track the
         * Russian blocklists the user routes around; both are overridable in [RoutingProfilesState].
         */
        const val DEFAULT_GEOIP_URL =
            "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download/geoip.dat"
        const val DEFAULT_GEOSITE_URL =
            "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download/geosite.dat"

        /** Accepted dash-separated bucket orderings offered in the editor. */
        val ROUTE_ORDERS = listOf(
            "block-direct-proxy",
            "block-proxy-direct",
            "direct-block-proxy",
            "proxy-block-direct",
        )

        /** Domain-resolution strategies accepted by both cores (mapped per-core at build time). */
        val DOMAIN_STRATEGIES = listOf("IPIfNonMatch", "IPOnDemand", "AsIs")

        /** Expert: native Xray `routing.domainStrategy` values. */
        val XRAY_DOMAIN_STRATEGIES = listOf("AsIs", "IPIfNonMatch", "IPOnDemand")

        /** Expert: native sing-box DNS/route strategy values ("" = inherit the global setting). */
        val SINGBOX_DOMAIN_STRATEGIES = listOf("", "prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only")

        private val exportJson = Json { encodeDefaults = false }
    }
}
