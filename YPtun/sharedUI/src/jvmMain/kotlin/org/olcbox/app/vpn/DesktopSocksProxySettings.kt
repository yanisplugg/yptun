package org.olcbox.app.vpn

import kotlinx.serialization.Serializable
import org.olcbox.app.vpn.desktop.PacServer

@Serializable
data class DesktopSocksProxySettings(
    val host: String = PacServer.LOCAL_SOCKS_HOST,
    val port: Int = PacServer.LOCAL_SOCKS_PORT,
    val username: String = "",
    val password: String = "",
    /**
     * SOCKS authentication (user + password) on a port of your choosing. OFF by default: the local
     * proxy then listens on [UNSECURED_PORT] with no credentials, which is what the Windows system
     * proxy and browsers can actually talk to — neither can answer a SOCKS auth challenge.
     */
    val secured: Boolean = false
) {
    val isConfigured: Boolean
        get() = username.isNotBlank() && password.isNotBlank()

    fun normalized(): DesktopSocksProxySettings {
        // Unsecured is the default shape: no credentials, standard proxy port.
        if (!secured) return copy(
            host = host.ifBlank { PacServer.LOCAL_SOCKS_HOST },
            port = UNSECURED_PORT,
            username = "",
            password = ""
        )
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

        /** Port used when [secured] is off — the default proxy port Windows/browsers expect. */
        const val UNSECURED_PORT = 8080

        fun isValidPort(port: Int): Boolean = port in MIN_PORT..MAX_PORT

        fun sanitizePort(port: Int?): Int {
            return port?.takeIf { isValidPort(it) } ?: PacServer.LOCAL_SOCKS_PORT
        }
    }
}
