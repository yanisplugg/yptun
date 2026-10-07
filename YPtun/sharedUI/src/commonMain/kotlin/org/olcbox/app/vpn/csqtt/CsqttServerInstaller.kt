package org.olcbox.app.vpn.csqtt

import androidx.compose.runtime.Composable

/**
 * Inputs for the one-tap csqtt server install on a VPS: SSH access plus the DTLS port and connection
 * password the server is launched with. The password must match the one in the location — the WRAP key
 * is derived from it on BOTH sides.
 */
data class CsqttInstallOptions(
    val host: String,
    val sshPort: Int = 22,
    val login: String = "root",
    val sshPassword: String = "",
    /** PEM/OpenSSH private key for SSH publickey auth; when set it is used instead of [sshPassword]. */
    val sshKey: String = "",
    /** Passphrase for an encrypted [sshKey]; empty for an unencrypted key. */
    val sshKeyPassphrase: String = "",
    /** UDP port the clients dial (csqtt's "peer" port; default 46000). */
    val peerPort: Int = 46000,
    /** HTTPS port of the server's web panel (default 46002). */
    val webPort: Int = 46002,
    /** The connection password. */
    val password: String,
    /** Web panel login / password; blank = generated and returned in [CsqttInstallResult]. */
    val webLogin: String = "",
    val webPassword: String = "",
)

/**
 * [peerPort] / [webPort] are what the server really runs on: the requested ones, or the next free ones
 * when another program holds them (the location then has to dial [peerPort]). [webLogin] / [webPassword]
 * are the web panel credentials (generated when none were asked for), where keys for more clients and
 * hashes are managed.
 */
data class CsqttInstallResult(
    val message: String,
    val peerPort: Int,
    val webPort: Int,
    val webLogin: String,
    val webPassword: String,
)

/**
 * Installs (or upgrades) the csqtt server on a VPS over SSH with the project's own `deploy.sh`
 * (uploaded with the server binary for the VPS architecture). Only the JVM platforms (Android, desktop)
 * ship a real implementation.
 */
interface CsqttServerInstaller {
    suspend fun install(options: CsqttInstallOptions, onLog: (String) -> Unit): Result<CsqttInstallResult>
}

@Composable
expect fun rememberCsqttServerInstaller(): CsqttServerInstaller
