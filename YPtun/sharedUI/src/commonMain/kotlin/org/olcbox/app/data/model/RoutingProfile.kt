package org.olcbox.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

    /** Local-only id for our storage; not part of the Happ wire format. */
    @SerialName("_id") val id: String = "",
) {
    /** True if any bucket references a `geoip:`/`geosite:` selector (needs the .dat files). */
    fun needsGeoFiles(): Boolean =
        (blockSites + directSites + proxySites).any { it.startsWith("geosite:", ignoreCase = true) } ||
            (blockIp + directIp + proxyIp).any { it.startsWith("geoip:", ignoreCase = true) }

    fun displayName(): String = name.ifBlank { "Routing profile" }

    companion object {
        /** Buckets in the order named by [routeOrder] (unknown tokens ignored). */
        val DEFAULT_ORDER = listOf("block", "direct", "proxy")
    }
}
