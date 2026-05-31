package org.olcbox.app.vpn

import kotlinx.serialization.Serializable
import org.olcbox.app.vpn.desktop.PacServer

@Serializable
data class DesktopSocksProxySettings(
    val host: String = PacServer.LOCAL_SOCKS_HOST,
    val port: Int = PacServer.LOCAL_SOCKS_PORT,
    val username: String = "",
    val password: String = ""
) {
    val isConfigured: Boolean
        get() = username.isNotBlank() && password.isNotBlank()

    fun normalized(): DesktopSocksProxySettings {
        return copy(
            host = host.ifBlank { PacServer.LOCAL_SOCKS_HOST },
            port = sanitizePort(port),
            username = username.take(MAX_CREDENTIAL_LENGTH),
            password = password.take(MAX_CREDENTIAL_LENGTH)
        )
    }

    companion object {
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
        const val MAX_CREDENTIAL_LENGTH = 64

        fun isValidPort(port: Int): Boolean = port in MIN_PORT..MAX_PORT

        fun sanitizePort(port: Int?): Int {
            return port?.takeIf { isValidPort(it) } ?: PacServer.LOCAL_SOCKS_PORT
        }
    }
}
