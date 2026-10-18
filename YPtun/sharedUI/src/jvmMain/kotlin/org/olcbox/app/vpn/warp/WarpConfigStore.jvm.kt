package org.olcbox.app.vpn.warp

import org.olcbox.app.vpn.telegram.TelegramProxyStore

internal actual object WarpConfigStore {
    actual fun loadConfig(): String? = TelegramProxyStore.loadConfig()
    actual fun saveConfig(ini: String) = TelegramProxyStore.saveConfig(ini)
}
