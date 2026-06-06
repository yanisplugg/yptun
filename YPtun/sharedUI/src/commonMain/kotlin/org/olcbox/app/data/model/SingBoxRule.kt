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
    /** Where matching traffic goes: [OUT_PROXY] / [OUT_DIRECT] / [OUT_BLOCK]. */
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
    /** Disabled rules are kept in the list but skipped when building the config. */
    val enabled: Boolean = true,
) {
    /** True when the rule has at least one matcher (otherwise it would match everything). */
    fun hasMatcher(): Boolean =
        domains.isNotEmpty() || ip.isNotEmpty() || source.isNotEmpty() || port.isNotBlank() ||
            sourcePort.isNotBlank() || network.isNotBlank() || protocol.isNotEmpty() ||
            networkType.isNotEmpty()

    companion object {
        const val OUT_PROXY = "proxy"
        const val OUT_DIRECT = "direct"
        const val OUT_BLOCK = "block"

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
    }
}
