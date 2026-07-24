package org.olcbox.app.vpn.dnstt

import androidx.compose.runtime.Composable

/**
 * Inputs for the one-tap dnstt-server install on a VPS. SSH access (host/login/password) plus the
 * dnstt listener (UDP) port + tunnel domain. The installer runs the server in *direct mode*: the
 * client points its DNS resolver straight at `host:udpPort` (no NS delegation needed), so the
 * domain is only an arbitrary label that must match on both ends. The server also runs a built-in
 * SOCKS5 exit ([socksPort], internal to the VPS) so it's a self-contained internet exit.
 */
data class DnsttInstallOptions(
    val host: String,
    val sshPort: Int = 22,
    val login: String = "root",
    val sshPassword: String = "",
    /** PEM/OpenSSH private key for SSH publickey auth; when set it is used instead of [sshPassword]. */
    val sshKey: String = "",
    /** Passphrase for an encrypted [sshKey]; empty for an unencrypted key. */
    val sshKeyPassphrase: String = "",
    /** UDP port the dnstt-server binds for DNS-tunnel queries (the client's resolver port). */
    val udpPort: Int = DEFAULT_UDP_PORT,
    /** Tunnel domain — arbitrary in direct mode, but must match the client's domain. */
    val domain: String = DEFAULT_DOMAIN,
    /** Internal SOCKS5 upstream port on the VPS (127.0.0.1:socksPort). */
    val socksPort: Int = DEFAULT_SOCKS_PORT,
) {
    companion object {
        const val DEFAULT_UDP_PORT = 5300
        const val DEFAULT_DOMAIN = "t.dnstt.net"
        const val DEFAULT_SOCKS_PORT = 8000
    }
}

/**
 * Result of a successful dnstt-server install: the server's public key (hex, read back from the
 * freshly generated keypair) plus a human status line. The public key + domain + resolver
 * (`host:udpPort`) are everything the client needs, so the dialog auto-fills them into the location.
 */
data class DnsttInstallResult(
    val publicKey: String,
    val message: String,
)

/**
 * Installs (or upgrades) the dnstt-server on a remote VPS over SSH: detects the architecture,
 * uploads the bundled server binary, installs it to /usr/local/bin, generates a persistent Noise
 * keypair (kept across reinstalls), writes a systemd unit (server + built-in SOCKS5 exit) and starts
 * it. Returns the server's public key on success. Implemented per platform — only Android ships a
 * real implementation (SSH client + bundled binary asset).
 */
interface DnsttServerInstaller {
    suspend fun install(
        options: DnsttInstallOptions,
        onLog: (String) -> Unit
    ): Result<DnsttInstallResult>
}

/**
 * Platform factory for the [DnsttServerInstaller]. Android returns a real SSH-based installer; other
 * platforms return one that fails with an "Android only" message (the feature targets Android).
 */
@Composable
expect fun rememberDnsttServerInstaller(): DnsttServerInstaller
