package org.olcbox.app.vpn.masterdns

import androidx.compose.runtime.Composable

/**
 * Inputs for the one-tap MasterDnsVPN server install on a VPS. SSH access (host/login/password or key)
 * plus the DNS listener (UDP) port, the tunnel domain and the payload cipher.
 *
 * The installer runs the server in *direct mode*: the client points its resolver straight at
 * `host:udpPort`, so no NS delegation is needed and the domain is just a label both ends agree on.
 * Delegating a real subdomain (an `A` record plus an `NS` record, per the upstream README) is what
 * lets the tunnel ride ordinary public resolvers instead — set the server up on UDP 53 for that and
 * point the client's resolver list at the public resolvers.
 */
data class MasterDnsInstallOptions(
    val host: String,
    val sshPort: Int = 22,
    val login: String = "root",
    val sshPassword: String = "",
    /** PEM/OpenSSH private key for SSH publickey auth; when set it is used instead of [sshPassword]. */
    val sshKey: String = "",
    /** Passphrase for an encrypted [sshKey]; empty for an unencrypted key. */
    val sshKeyPassphrase: String = "",
    /** UDP port the server binds for tunnel queries (the client's resolver port in direct mode). */
    val udpPort: Int = DEFAULT_UDP_PORT,
    /** Tunnel domain — arbitrary in direct mode, but must match the client's domain. */
    val domain: String = DEFAULT_DOMAIN,
    /** Payload cipher (DATA_ENCRYPTION_METHOD); the client must be set to the same value. */
    val encryptionMethod: Int = DEFAULT_ENCRYPTION_METHOD,
) {
    companion object {
        const val DEFAULT_UDP_PORT = 5300
        const val DEFAULT_DOMAIN = "v.masterdns.net"
        /** XOR — the upstream default. */
        const val DEFAULT_ENCRYPTION_METHOD = 1
    }
}

/**
 * Result of a successful install: the server's encryption key (generated on the VPS and read back out
 * of `encrypt_key.txt`) plus a human status line. That key + the domain + the resolver
 * (`host:udpPort`) are everything the client needs, so the dialog fills them into the location.
 */
data class MasterDnsInstallResult(
    val encryptionKey: String,
    val message: String,
)

/**
 * Installs (or upgrades) the MasterDnsVPN server on a remote VPS over SSH: detects the architecture,
 * uploads the bundled server binary, installs it to /usr/local/bin, writes the config, generates a
 * PERSISTENT encryption key (kept across reinstalls so an already-configured client keeps working),
 * writes a systemd unit and starts it. Returns the key on success. Implemented per platform — only
 * Android ships a real implementation (SSH client + bundled binary asset).
 */
interface MasterDnsServerInstaller {
    suspend fun install(
        options: MasterDnsInstallOptions,
        onLog: (String) -> Unit
    ): Result<MasterDnsInstallResult>
}

/**
 * Platform factory for the [MasterDnsServerInstaller]. Android returns a real SSH-based installer;
 * other platforms return one that fails with an "Android only" message (the feature targets Android).
 */
@Composable
expect fun rememberMasterDnsServerInstaller(): MasterDnsServerInstaller
