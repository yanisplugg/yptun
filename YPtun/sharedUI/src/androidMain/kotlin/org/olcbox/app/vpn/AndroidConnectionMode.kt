package org.olcbox.app.vpn

enum class AndroidConnectionMode(val value: String) {
    Tun("tun"),
    Proxy("proxy");

    companion object {
        fun fromValue(value: String?): AndroidConnectionMode {
            return entries.firstOrNull { it.value == value } ?: Tun
        }
    }
}
