package org.olcbox.app.data.model

import kotlinx.serialization.Serializable

/**
 * Global traffic-routing rules applied to the sing-box / xray config when connecting.
 * Order of evaluation in the generated config: block lists → direct lists → bypass presets → proxy (final).
 */
@Serializable
data class RoutingRules(
    /** Send private/LAN ranges straight out (not through the proxy). */
    val bypassLan: Boolean = true,
    /** Block ad/tracker domains (geosite category-ads). */
    val blockAds: Boolean = false,
    /** Send Russian sites/IPs directly (geoip-ru + geosite-ru). */
    val bypassRussia: Boolean = false,
    /** User domains routed directly (domain suffixes). */
    val directDomains: List<String> = emptyList(),
    /** User domains blocked (domain suffixes). */
    val blockDomains: List<String> = emptyList(),
) {
    companion object {
        /** Split a free-text field (newline/comma/space separated) into clean domain suffixes. */
        fun parseDomains(text: String): List<String> =
            text.split('\n', '\r', ',', ' ', ';')
                .map { it.trim().removePrefix("https://").removePrefix("http://").trimEnd('/') }
                .filter { it.isNotEmpty() }

        fun domainsToText(list: List<String>): String = list.joinToString("\n")
    }
}
