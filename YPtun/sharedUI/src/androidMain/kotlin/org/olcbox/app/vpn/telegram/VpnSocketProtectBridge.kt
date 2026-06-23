package org.olcbox.app.vpn.telegram

/**
 * Process-wide hand-off so the always-on Telegram-over-WARP proxy ([TelegramProxyService], a SEPARATE
 * foreground service) can protect its WireGuard UDP socket through the MAIN VpnService when one is
 * active. [OlcboxVpnService] publishes its `protect(fd)` while its system TUN is up and clears it on
 * teardown; the Telegram proxy reads it when starting its AmneziaWG instance.
 *
 * - Main VPN OFF, or in Proxy (SOCKS) mode → [protect] is null → WARP packets egress the real network
 *   directly (correct, no protection needed).
 * - Main VPN in TUN mode → [protect] is set → WARP packets bypass the system tun instead of being
 *   captured into it, so the Telegram tunnel doesn't ride (and fight) the main tunnel.
 */
object VpnSocketProtectBridge {
    /** Returns true when the fd was protected. Set by the main VpnService only while its TUN is up. */
    @Volatile
    var protect: ((Int) -> Boolean)? = null
}
