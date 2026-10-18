package org.olcbox.app.vpn.warp

internal actual object WarpConfigStore {
    @Volatile
    private var cached: String? = null

    actual fun loadConfig(): String? = cached

    actual fun saveConfig(ini: String) {
        cached = ini
    }
}
