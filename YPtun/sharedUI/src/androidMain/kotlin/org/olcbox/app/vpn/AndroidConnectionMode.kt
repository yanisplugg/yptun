package org.olcbox.app.vpn

enum class AndroidConnectionMode(val value: String) {
    Tun("tun"),
    Proxy("proxy"),

    /**
     * Transparent proxy: sing-box exposes a `tproxy` inbound (TCP+UDP) on the LAN, so another device
     * or a local iptables TPROXY rule can redirect traffic through it with no per-app proxy config.
     * Like [Proxy] there is NO VpnService TUN — the core listens and forwards. NOTE: opening an
     * IP_TRANSPARENT socket needs CAP_NET_ADMIN, so this mode only works on rooted devices (or as a
     * gateway that a rooted router redirects to); on a stock phone the inbound fails to bind.
     */
    Tproxy("tproxy");

    /** True for the tunnel-less modes (no VpnService TUN / no tun2socks) — the core listens directly. */
    val isTunless: Boolean get() = this != Tun

    companion object {
        fun fromValue(value: String?): AndroidConnectionMode {
            return entries.firstOrNull { it.value == value } ?: Tun
        }
    }
}
