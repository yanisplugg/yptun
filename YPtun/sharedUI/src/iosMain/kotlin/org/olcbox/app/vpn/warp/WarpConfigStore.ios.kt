package org.olcbox.app.vpn.warp

import org.olcbox.app.vpn.ios.IosSharedStore

internal actual object WarpConfigStore {
    private const val FILE = "warp.conf"

    actual fun loadConfig(): String? = IosSharedStore.readText(FILE)?.takeIf { it.isNotBlank() }

    actual fun saveConfig(ini: String) {
        IosSharedStore.writeText(FILE, ini)
    }
}
