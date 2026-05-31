package org.olcbox.app.vpn

data class AndroidSplitTunnelSettings(
    val mode: AndroidSplitTunnelMode = AndroidSplitTunnelMode.AllApps,
    val proxyPackages: Set<String> = emptySet(),
    val bypassPackages: Set<String> = emptySet()
)

enum class AndroidSplitTunnelMode(val value: String) {
    AllApps("all_apps"),
    ProxySelected("proxy_selected"),
    BypassSelected("bypass_selected");

    companion object {
        fun fromValue(value: String?): AndroidSplitTunnelMode {
            return entries.firstOrNull { it.value == value } ?: AllApps
        }
    }
}

enum class AndroidSplitTunnelList {
    Proxy,
    Bypass
}

data class AndroidInstalledApp(
    val packageName: String,
    val label: String
)
