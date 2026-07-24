package org.olcbox.app.vpn.wdtt

import androidx.compose.runtime.Composable

/**
 * Inputs for the one-tap WDTT server install on a VPS. SSH access (host/login/password) plus the
 * WDTT listener port + connection password the server is launched with. The WDTT password must
 * match the one entered in the location editor — the WRAP key is HKDF-derived from it on BOTH
 * sides, so a mismatch silently fails to connect.
 */
data class WdttInstallOptions(
    val host: String,
    val sshPort: Int = 22,
    val login: String = "root",
    val sshPassword: String = "",
    /** PEM/OpenSSH private key for SSH publickey auth; when set it is used instead of [sshPassword]. */
    val sshKey: String = "",
    /** Passphrase for an encrypted [sshKey]; empty for an unencrypted key. */
    val sshKeyPassphrase: String = "",
    /** DTLS listener port the server binds (the client's wdtt-server port; default 56000). */
    val wdttPort: Int = 56000,
    val wdttPassword: String,
    /** DNS handed to clients in the WireGuard config the server distributes. */
    val dns: String = "1.1.1.1",
)

/**
 * Installs (or upgrades) the wdtt-server on a remote VPS over SSH: detects the architecture,
 * uploads the bundled server binary, installs it to /usr/local/bin and runs it as a systemd
 * service. The server itself configures IP forwarding + NAT + the userspace WireGuard tunnel at
 * startup, so the installer only has to place the binary and the unit. Implemented per platform —
 * only Android ships a real implementation (SSH client + bundled binary asset).
 */
interface WdttServerInstaller {
    /**
     * Runs the full install, streaming human-readable progress through [onLog]. Returns the final
     * status line on success, or a [Result.failure] carrying the SSH/install error.
     */
    suspend fun install(options: WdttInstallOptions, onLog: (String) -> Unit): Result<String>
}

/**
 * Platform factory for the [WdttServerInstaller]. Android returns a real SSH-based installer;
 * other platforms return one that fails with an "Android only" message (the WDTT feature targets
 * the Android client).
 */
@Composable
expect fun rememberWdttServerInstaller(): WdttServerInstaller
