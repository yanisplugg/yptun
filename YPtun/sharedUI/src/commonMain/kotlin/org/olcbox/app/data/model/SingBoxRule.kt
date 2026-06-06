package org.olcbox.app.data.model

import kotlinx.serialization.Serializable

/**
 * A single v2rayNG-style routing rule for sing-box. Every populated field is ANDed together
 * (a connection must match all of them); within a field the entries are ORed.
 *
 * Selector syntax (shared with [RoutingProfile] / SingBoxRouting):
 * - domains: `geosite:cn`, `domain:example.com`, `full:exact.com`, `keyword:goog`, `regexp:.*\.cn`, or a bare domain.
 * - ip: `geoip:ru`, a CIDR `10.0.0.0/8`, or a bare IP.
 *
 * Applied only on the sing-box core (Xray ignores these).
 */
@Serializable
data class SingBoxRule(
    /** Optional human label for the rule (shown in the editor). Not emitted to the sing-box config. */
    val name: String = "",
    /**
     * sing-box rule `action` (one of [ACTIONS]). [ACTION_ROUTE] (default) routes to [outbound];
     * the others ([ACTION_REJECT], [ACTION_HIJACK_DNS], [ACTION_SNIFF], [ACTION_RESOLVE],
     * [ACTION_ROUTE_OPTIONS]) ignore [outbound].
     */
    val action: String = ACTION_ROUTE,
    /** Where matching traffic goes when [action] is [ACTION_ROUTE]: [OUT_PROXY] / [OUT_DIRECT] / [OUT_BLOCK]. */
    val outbound: String = OUT_PROXY,
    /** Domain selectors (see class doc). */
    val domains: List<String> = emptyList(),
    /** IP / geoip selectors (see class doc). */
    val ip: List<String> = emptyList(),
    /** Source IP selectors (CIDR or bare IP) → sing-box `source_ip_cidr`. */
    val source: List<String> = emptyList(),
    /** Ports: "443", a range "1000:2000", or comma-separated. Blank = any. */
    val port: String = "",
    /** Source ports: "443", a range "1000:2000", or comma-separated → `source_port`/`source_port_range`. */
    val sourcePort: String = "",
    /** "" (any), "tcp" or "udp". */
    val network: String = "",
    /** Network interface types → sing-box `network_type` (any of [NETWORK_TYPES]). Empty = any. */
    val networkType: List<String> = emptyList(),
    /** Sniffed protocols: http / tls / quic / bittorrent. Empty = any. */
    val protocol: List<String> = emptyList(),
    /** Sniffed TLS client fingerprints → sing-box `client` (e.g. chromium, firefox, safari). Empty = any. */
    val client: List<String> = emptyList(),
    /** Match metered (expensive) networks → sing-box `network_is_expensive`. */
    val networkIsExpensive: Boolean = false,
    /** Match the active Clash mode → sing-box `clash_mode` (e.g. Rule / Global / Direct). Blank = any. */
    val clashMode: String = "",
    /** Android app package names → sing-box `package_name`. Empty = any. */
    val packageNames: List<String> = emptyList(),
    /**
     * Regular expressions matched against installed package names. sing-box has no native
     * package-regex field, so these are expanded to concrete packages (merged into
     * [packageNames]) at config-build time on Android — see [expandPackageRegex]. Empty = none.
     */
    val packageRegex: List<String> = emptyList(),
    /** Disabled rules are kept in the list but skipped when building the config. */
    val enabled: Boolean = true,
) {
    /** True when the rule has at least one matcher (otherwise it would match everything). */
    fun hasMatcher(): Boolean =
        domains.isNotEmpty() || ip.isNotEmpty() || source.isNotEmpty() || port.isNotBlank() ||
            sourcePort.isNotBlank() || network.isNotBlank() || protocol.isNotEmpty() ||
            networkType.isNotEmpty() || client.isNotEmpty() || networkIsExpensive ||
            clashMode.isNotBlank() || packageNames.isNotEmpty() || packageRegex.isNotEmpty()

    companion object {
        const val OUT_PROXY = "proxy"
        const val OUT_DIRECT = "direct"
        const val OUT_BLOCK = "block"

        // sing-box rule actions (exact tokens from sing-box/constant/rule.go).
        const val ACTION_ROUTE = "route"
        const val ACTION_ROUTE_OPTIONS = "route-options"
        const val ACTION_SNIFF = "sniff"
        const val ACTION_RESOLVE = "resolve"
        const val ACTION_HIJACK_DNS = "hijack-dns"
        const val ACTION_REJECT = "reject"

        /** Selectable rule actions, in editor order. */
        val ACTIONS = listOf(
            ACTION_ROUTE, ACTION_ROUTE_OPTIONS, ACTION_SNIFF,
            ACTION_RESOLVE, ACTION_HIJACK_DNS, ACTION_REJECT,
        )

        val OUTBOUNDS = listOf(OUT_PROXY, OUT_DIRECT, OUT_BLOCK)
        val NETWORKS = listOf("", "tcp", "udp")
        val PROTOCOLS = listOf("http", "tls", "quic", "bittorrent")
        /** sing-box `network_type` interface kinds. */
        val NETWORK_TYPES = listOf("wifi", "cellular", "ethernet", "other")

        /** Newline-join a selector list for a multiline text field. */
        fun listToText(list: List<String>): String = list.joinToString("\n")

        /** Split a free-text field (newline/comma/semicolon separated) into clean selectors. */
        fun textToList(text: String): List<String> =
            text.split('\n', '\r', ',', ';').map { it.trim() }.filter { it.isNotEmpty() }

        /**
         * Resolve [packageRegex] against the device's [installedPackages], merging every matching
         * package into [packageNames] (sing-box has no native package-regex matcher). Invalid
         * patterns are ignored. Returns [rules] unchanged when no rule uses a regex.
         */
        fun expandPackageRegex(rules: List<SingBoxRule>, installedPackages: List<String>): List<SingBoxRule> {
            if (rules.none { it.packageRegex.isNotEmpty() }) return rules
            return rules.map { rule ->
                if (rule.packageRegex.isEmpty()) return@map rule
                val regexes = rule.packageRegex.mapNotNull { runCatching { Regex(it) }.getOrNull() }
                if (regexes.isEmpty()) return@map rule
                val matched = installedPackages.filter { pkg -> regexes.any { it.containsMatchIn(pkg) } }
                if (matched.isEmpty()) rule
                else rule.copy(packageNames = (rule.packageNames + matched).distinct())
            }
        }
    }
}
