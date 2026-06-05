package org.olcbox.app.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray

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
    /**
     * Advanced: a verbatim sing-box `route.rules` JSON array for power users. Inserted right after
     * the sniff action (so it takes precedence over the toggle-based rules above). Blank = unused.
     * Example: `[{"domain_suffix":["openai.com"],"outbound":"direct"}]`.
     */
    val customRulesJson: String = "",
    /**
     * Advanced: a verbatim sing-box `route.rule_set` JSON array, merged with the built-in rule-sets
     * so [customRulesJson] can reference custom `rule_set` tags. Blank = unused.
     */
    val customRuleSetsJson: String = "",
    /**
     * Structured v2rayNG-style rules (sing-box only). Each entry becomes a single `route.rules`
     * object; geo selectors add the matching remote `.srs` rule-sets. Empty = unused.
     */
    val rules: List<SingBoxRule> = emptyList(),
) {
    companion object {
        /** Split a free-text field (newline/comma/space separated) into clean domain suffixes. */
        fun parseDomains(text: String): List<String> =
            text.split('\n', '\r', ',', ' ', ';')
                .map { it.trim().removePrefix("https://").removePrefix("http://").trimEnd('/') }
                .filter { it.isNotEmpty() }

        fun domainsToText(list: List<String>): String = list.joinToString("\n")

        /** True when [text] is blank or a parseable JSON array (used to validate advanced fields). */
        fun isValidRulesJson(text: String): Boolean {
            if (text.isBlank()) return true
            return runCatching { Json.parseToJsonElement(text).jsonArray }.isSuccess
        }
    }
}
