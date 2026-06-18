package org.olcbox.app.data.model

import kotlinx.serialization.Serializable

/**
 * Global traffic/transport tuning applied to the generated sing-box config when connecting.
 * Mirrors the "Настройки трафика" screen in v2rayTun (the subset we wire into sing-box).
 */
@Serializable
data class TrafficSettings(
    /** DNS used for proxied traffic (resolved through the proxy, no leak). */
    val remoteDns: String = "8.8.8.8",
    /** DNS used for direct/bootstrap lookups (e.g. resolving the proxy server domain). */
    val directDns: String = "223.5.5.5",
    /** sing-box DNS resolution strategy. One of [STRATEGIES]. */
    val domainStrategy: String = "ipv4_only",
    /** Enable connection multiplexing on the proxy outbound. */
    val muxEnabled: Boolean = false,
    /** Multiplex protocol. One of [MUX_PROTOCOLS]. */
    val muxProtocol: String = "h2mux",
    /** Maximum multiplexed connections (sing-box max_connections). */
    val muxMaxConnections: Int = 8,
    /** Enable TLS fragmentation (Xray core) to evade DPI. */
    val fragmentEnabled: Boolean = false,
    /** What to fragment: "tlshello" or "1-3" (packet range). */
    val fragmentPackets: String = "tlshello",
    /** Fragment byte length range, e.g. "50-100". */
    val fragmentLength: String = "50-100",
    /** Delay between fragments in ms, e.g. "10-20". */
    val fragmentInterval: String = "10-20",
    /** TUN interface MTU (bytes). */
    val mtu: Int = 1500,
    /**
     * Block the bundled list of Russian domains ([org.olcbox.app.vpn.xray.RuBlocklist]) by
     * resolving them to 0.0.0.0 and blackholing that IP. Xray core only (the blocklist uses
     * Xray's regexp DNS hosts). Off by default.
     */
    val blockRuDomains: Boolean = false,
    /**
     * Vestigial global FakeDNS toggle. FakeDNS is no longer a global switch — it is applied PER
     * location/subscription: a raw Xray config that carries its own `fakedns` block is honored
     * verbatim (so the feature follows the config that asked for it), and configs that don't have it
     * never get it injected. [normalized] forces this off so the old global path can't fire.
     */
    val fakeDnsEnabled: Boolean = false,
) {
    fun normalized(): TrafficSettings = copy(
        // FakeDNS is per-config now (honored verbatim from the location's own raw config), never global.
        fakeDnsEnabled = false,
        remoteDns = remoteDns.trim().ifBlank { "8.8.8.8" },
        directDns = directDns.trim().ifBlank { "223.5.5.5" },
        domainStrategy = domainStrategy.takeIf { it in STRATEGIES } ?: "ipv4_only",
        muxProtocol = muxProtocol.takeIf { it in MUX_PROTOCOLS } ?: "h2mux",
        muxMaxConnections = muxMaxConnections.coerceIn(1, 64),
        fragmentPackets = fragmentPackets.trim().ifBlank { "tlshello" },
        fragmentLength = fragmentLength.trim().ifBlank { "50-100" },
        fragmentInterval = fragmentInterval.trim().ifBlank { "10-20" },
        mtu = mtu.coerceIn(1280, 9000),
    )

    /** Xray DNS queryStrategy mapped from [domainStrategy]. */
    fun xrayQueryStrategy(): String = when (domainStrategy) {
        "ipv4_only" -> "UseIPv4"
        "ipv6_only" -> "UseIPv6"
        else -> "UseIP"
    }

    companion object {
        val STRATEGIES = listOf("prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only")
        val MUX_PROTOCOLS = listOf("h2mux", "smux", "yamux")
    }
}
