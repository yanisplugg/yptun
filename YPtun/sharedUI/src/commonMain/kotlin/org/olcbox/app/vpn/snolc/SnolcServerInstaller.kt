package org.olcbox.app.vpn.snolc

import androidx.compose.runtime.Composable
import org.olcbox.app.data.model.SnolcConfig

/** SSH access to the VPS plus the TCP port the snolc exit node listens on. */
data class SnolcInstallOptions(
    val host: String,
    val sshPort: Int = 22,
    val login: String = "root",
    val sshPassword: String = "",
    /** PEM/OpenSSH private key for SSH publickey auth; when set it is used instead of [sshPassword]. */
    val sshKey: String = "",
    val sshKeyPassphrase: String = "",
    val listenPort: Int = SnolcConfig.DEFAULT_PORT,
)

/**
 * Installs (or upgrades) the snolc exit node on a VPS over SSH: uploads the bundled static executable,
 * keeps (or creates) the Noise keypair, writes the config and runs it as a systemd service. On success
 * the result is the `snolc://` link the client imports — it carries the node's public key.
 */
interface SnolcServerInstaller {
    suspend fun install(options: SnolcInstallOptions, onLog: (String) -> Unit): Result<String>
}

@Composable
expect fun rememberSnolcServerInstaller(): SnolcServerInstaller
