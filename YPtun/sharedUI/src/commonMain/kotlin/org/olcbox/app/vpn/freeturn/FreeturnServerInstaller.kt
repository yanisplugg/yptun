package org.olcbox.app.vpn.freeturn

import androidx.compose.runtime.Composable

/**
 * Inputs for the one-tap free-turn-proxy server install on a VPS. SSH access (host/login/password)
 * plus the freeturn public listener port the server binds. The installer also provisions a local
 * WireGuard server (the freeturn `-connect` backend) and hands back the keys needed to build the
 * client `freeturn://` link — mirroring what the Flask panel does (`_panel/server.py`).
 */
data class FreeturnInstallOptions(
    val host: String,
    val sshPort: Int = 22,
    val login: String = "root",
    val sshPassword: String = "",
    /** PEM/OpenSSH private key for SSH publickey auth; when set it is used instead of [sshPassword]. */
    val sshKey: String = "",
    /** Passphrase for an encrypted [sshKey]; empty for an unencrypted key. */
    val sshKeyPassphrase: String = "",
    /** Public UDP port the free-turn-proxy server binds (the freeturn:// peer port; default 56000). */
    val freeturnPort: Int = 56000,
    /** Wire obfuscation profile the server runs with (must match the client). */
    val obfProfile: String = "rtpopus",
    /** DNS handed to the client in the generated WireGuard config. */
    val dns: String = "1.1.1.1",
)

/**
 * The artefacts the freeturn install produces, used to build the client `freeturn://` link: the
 * obfuscation key + the WireGuard keypair halves the client needs (server public key, client
 * private key, client tunnel address) and the public listener port. [status] is a human summary.
 */
data class FreeturnInstallResult(
    val obfKey: String,
    val serverWgPublicKey: String,
    val clientWgPrivateKey: String,
    /** Client tunnel address, e.g. `10.7.3.2/32`. */
    val clientWgAddress: String,
    val freeturnPort: Int,
    val status: String,
)

/**
 * Installs (or upgrades) the free-turn-proxy server on a remote VPS over SSH: detects the
 * architecture, uploads the bundled server binary, provisions a persistent WireGuard exit
 * (wg-quick + NAT) as the `-connect` backend, then runs the server as a systemd service. The
 * generated WireGuard client keys + obf key come back in [FreeturnInstallResult] so the app can
 * compose the `freeturn://` link. Implemented per platform — only Android ships a real
 * implementation (SSH client + bundled binary asset).
 */
interface FreeturnServerInstaller {
    /**
     * Runs the full install, streaming human-readable progress through [onLog]. Returns the
     * artefacts on success, or a [Result.failure] carrying the SSH/install error.
     */
    suspend fun install(options: FreeturnInstallOptions, onLog: (String) -> Unit): Result<FreeturnInstallResult>
}

/**
 * Platform factory for the [FreeturnServerInstaller]. Android returns a real SSH-based installer;
 * other platforms return one that fails with an "Android only" message (the feature targets the
 * Android client).
 */
@Composable
expect fun rememberFreeturnServerInstaller(): FreeturnServerInstaller
