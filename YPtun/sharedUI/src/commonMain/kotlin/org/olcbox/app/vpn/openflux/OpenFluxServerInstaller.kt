package org.olcbox.app.vpn.openflux

import androidx.compose.runtime.Composable
import org.olcbox.app.data.model.OpenFluxCarrier
import org.olcbox.app.data.model.OpenFluxConfig

/**
 * Inputs for the one-tap OpenFlux exit-node install on a VPS: SSH access plus the carrier the node
 * listens on — a single coordinate or comma/newline-separated multi-room list [docUrl], auto-created
 * `cupsonline` rooms ([cupsRoomsCount]), the exit node's own MAX account token [exitMaxToken], or a
 * multi-transport session ([carriers] + [secret]).
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
    /** How many rooms to create automatically on the VPS for `cupsonline` when no room list is provided (`CUPS_ROOMS`). */
    val cupsRoomsCount: Int = DEFAULT_CUPS_ROOMS,
    /** Multi-transport session carriers (`--transports`). Empty = single-transport mode ([transport] / [docUrl]). */
    val carriers: List<OpenFluxCarrier> = emptyList(),
    /** Optional AES-256-GCM shared secret written to `/etc/openflux/openflux.key` (required for [carriers]). */
    val secret: String = "",
    /** Optional explicit KDF context (`--session-context`). */
    val context: String = "",
    /** Require authenticated Session negotiation (`--negotiate`); ignored when [secret] is blank. */
    val negotiate: Boolean = false,
    /** Node forwarding mode (`l3` or `l4`). Empty defaults multi-transport sessions to `l3` and single-transport to `l4`. */
    val mode: String = "",
) {
    fun effectiveMode(): String = when (mode.trim().lowercase()) {
        MODE_L3 -> MODE_L3
        MODE_L4 -> MODE_L4
        else -> if (carriers.isNotEmpty()) MODE_L3 else MODE_L4
    }

    companion object {
        const val DEFAULT_CUPS_ROOMS = 8
        const val MIN_CUPS_ROOMS = 1
        const val MAX_CUPS_ROOMS = 64
        const val DEFAULT_DIRECT_PORT = 8445
        const val MODE_L3 = "l3"
        const val MODE_L4 = "l4"
    }
}

/** Result returned by [OpenFluxServerInstaller.install] so auto-created rooms/keys sync back into the location. */
data class OpenFluxInstallResult(
    val status: String,
    /** Single-transport document/room list (populated with auto-created `cupsonline` rooms when applicable). */
    val docUrl: String = "",
    /** Multi-transport carriers updated with any auto-created `cupsonline` room list and resolved direct dial. */
    val carriers: List<OpenFluxCarrier> = emptyList(),
    /** Shared secret installed on the VPS (echoed or auto-generated when needed). */
    val secret: String = "",
)

/**
 * Installs (or upgrades) the OpenFlux exit node on a remote VPS over SSH: detects the architecture,
 * uploads the bundled binary, keeps the carrier secrets in a root-only EnvironmentFile/key file and
 * runs it as a systemd service. Shared by Android and desktop (SSH + bundled binaries); iOS/macOS have no engines.
 */
interface OpenFluxServerInstaller {
    suspend fun install(options: OpenFluxInstallOptions, onLog: (String) -> Unit): Result<OpenFluxInstallResult>
}

@Composable
expect fun rememberOpenFluxServerInstaller(): OpenFluxServerInstaller
