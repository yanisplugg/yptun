package org.olcbox.app.vpn.openflux

import androidx.compose.runtime.Composable
import org.olcbox.app.data.model.OpenFluxConfig

/**
 * Inputs for the one-tap OpenFlux exit-node install on a VPS: SSH access plus the carrier the node
 * listens on — the same Yandex Docs [docUrl] the client uses, or the EXIT NODE's own MAX account token
 * [exitMaxToken] (the client then calls that account).
 */
data class OpenFluxInstallOptions(
    val host: String,
    val sshPort: Int = 22,
    val login: String = "root",
    val sshPassword: String = "",
    /** PEM/OpenSSH private key for SSH publickey auth; when set it is used instead of [sshPassword]. */
    val sshKey: String = "",
    /** Passphrase for an encrypted [sshKey]; empty for an unencrypted key. */
    val sshKeyPassphrase: String = "",
    val transport: String = OpenFluxConfig.TRANSPORT_YANDEX,
    val docUrl: String = "",
    val exitMaxToken: String = "",
)

/**
 * Installs (or upgrades) the OpenFlux exit node on a remote VPS over SSH: detects the architecture,
 * uploads the bundled binary, keeps the carrier secrets in a root-only EnvironmentFile and runs it as a
 * systemd service. Shared by Android and desktop (SSH + bundled binaries); iOS/macOS have no engines.
 */
interface OpenFluxServerInstaller {
    suspend fun install(options: OpenFluxInstallOptions, onLog: (String) -> Unit): Result<String>
}

@Composable
expect fun rememberOpenFluxServerInstaller(): OpenFluxServerInstaller
