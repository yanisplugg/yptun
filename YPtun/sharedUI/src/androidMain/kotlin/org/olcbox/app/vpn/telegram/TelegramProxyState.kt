package org.olcbox.app.vpn.telegram

/** UI state of the Telegram-over-WARP background proxy. */
sealed class TelegramProxyState {
    /** Proxy is off. */
    data object Stopped : TelegramProxyState()

    /** First-time WARP config generation in progress (requires internet). */
    data object Generating : TelegramProxyState()

    /**
     * Proxy is up; apps can use the local SOCKS5 at [host]:[port] with auto-generated [user]/[pass]
     * (entered once into Telegram's SOCKS5 proxy settings).
     */
    data class Running(
        val host: String,
        val port: Int,
        val user: String,
        val pass: String
    ) : TelegramProxyState()

    /** Enable failed (e.g. no internet during first-run generation, or the tunnel didn't come up). */
    data class Error(val message: String) : TelegramProxyState()
}
