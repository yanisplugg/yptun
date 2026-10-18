package org.olcbox.app.vpn.warp

internal expect object WarpConfigStore {
    fun loadConfig(): String?
    fun saveConfig(ini: String)
}
