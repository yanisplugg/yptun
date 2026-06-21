package org.olcbox.app.vpn.dnstt

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.vpn.ssh.base64Heredoc
import org.olcbox.app.vpn.ssh.openSshSession
import org.olcbox.app.vpn.ssh.shellSingleQuote
import org.olcbox.app.vpn.ssh.sshExec
import org.olcbox.app.vpn.ssh.sshRunScript

@Composable
actual fun rememberDnsttServerInstaller(): DnsttServerInstaller {
    val context = LocalContext.current.applicationContext
    return remember { AndroidDnsttServerInstaller(context) }
}

/**
 * SSH-based dnstt-server installer. Connects with password auth, detects the VPS architecture
 * (`uname -m`), streams the matching bundled server binary (gzip asset) into /tmp via a plain exec
 * channel (no SFTP — minimal VPS images often lack the subsystem), then runs the install script as a
 * single shell command: it places the binary in /usr/local/bin, generates a persistent Noise keypair
 * (only if one doesn't already exist, so the public key is stable across reinstalls), writes a systemd
 * unit that runs the server with its built-in SOCKS5 exit and starts it. The script prints the public
 * key on a `DNSTT_PUBKEY=` line, which is parsed out and returned. The binaries live in assets/dnstt/
 * (see build-dnstt-server.ps1).
 */
internal class AndroidDnsttServerInstaller(private val context: Context) : DnsttServerInstaller {

    override suspend fun install(
        options: DnsttInstallOptions,
        onLog: (String) -> Unit
    ): Result<DnsttInstallResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(options.host.isNotBlank()) { "Не указан IP/хост VPS" }
            require(options.sshPassword.isNotBlank()) { "Не указан пароль SSH" }
            require(options.domain.isNotBlank()) { "Не указан домен туннеля" }

            // Connection 1 — detect the architecture (this server resets the link when a 2nd channel
            // is opened, so each phase gets its own fresh connection, one channel each).
            onLog("Определяю архитектуру VPS…")
            val archSession = openSshSession(options.host, options.sshPort, options.login, options.sshPassword, onLog)
            val goArch = try {
                val machine = sshExec(archSession, "uname -m").trim()
                onLog("Архитектура VPS: $machine")
                when {
                    machine.contains("aarch64") || machine.contains("arm64") -> "arm64"
                    machine.contains("x86_64") || machine.contains("amd64") -> "amd64"
                    else -> error("Неподдерживаемая архитектура VPS: '$machine' (нужен x86_64 или aarch64)")
                }
            } finally {
                archSession.disconnect()
            }

            val gz = context.assets.open("dnstt/dnstt-server-linux-$goArch.gz").use { it.readBytes() }
            onLog("Установка сервера (${gz.size / 1024} КБ) одним сеансом…")
            // Connection 2 — one channel: deliver the binary (base64 heredoc) AND run the install
            // script (which also generates the keypair), all in a single `sh -s` stream.
            val script = buildString {
                append("set -e\n")
                append("command -v base64 >/dev/null 2>&1 || { echo 'no base64 on VPS'; exit 1; }\n")
                append(base64Heredoc(gz, REMOTE_GZ))
                append(buildInstallScript(options))
            }
            val runSession = openSshSession(options.host, options.sshPort, options.login, options.sshPassword, onLog)
            val output = try {
                sshRunScript(runSession, script, onLog)
            } finally {
                runSession.disconnect()
            }

            var pubKey = ""
            output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                val marker = line.substringAfter("DNSTT_PUBKEY=", "")
                if (marker.isNotEmpty()) pubKey = marker.trim()
                else onLog(line)
            }
            if (pubKey.isBlank() || !pubKey.matches(Regex("[0-9a-fA-F]{64}"))) {
                error("Не удалось получить публичный ключ сервера (ключ: '$pubKey')")
            }
            onLog("Публичный ключ сервера получен")

            DnsttInstallResult(
                publicKey = pubKey,
                message = "dnstt-server установлен и запущен на ${options.host}:${options.udpPort} (резолвер: ${options.host}:${options.udpPort})"
            )
        }
    }

    private companion object {
        const val REMOTE_GZ = "/tmp/dnstt-server.gz"
    }
}

/**
 * The remote install script. Decompresses + installs the binary, generates a PERSISTENT keypair the
 * first time (kept on reinstall so the client's public key stays valid), writes a systemd unit that
 * runs the server in direct mode with its built-in SOCKS5 exit, opens the UDP port on any common
 * firewall (best-effort), starts the service and prints the public key on a `DNSTT_PUBKEY=` line.
 * Single-quoted values are escaped so an awkward domain can't break out of the shell quoting.
 */
internal fun buildInstallScript(options: DnsttInstallOptions): String {
    val udp = options.udpPort
    val socks = options.socksPort
    val domain = options.domain.shellSingleQuote()
    return """
        set -e
        gunzip -f /tmp/dnstt-server.gz
        install -m 0755 /tmp/dnstt-server /usr/local/bin/dnstt-server
        rm -f /tmp/dnstt-server
        mkdir -p /etc/dnstt
        if [ ! -s /etc/dnstt/server.key ] || [ ! -s /etc/dnstt/server.pub ]; then
          /usr/local/bin/dnstt-server -gen-key -privkey-file /etc/dnstt/server.key -pubkey-file /etc/dnstt/server.pub
        fi
        chmod 600 /etc/dnstt/server.key
        cat > /etc/systemd/system/dnstt-server.service <<UNIT
        [Unit]
        Description=DNSTT Server
        After=network-online.target
        Wants=network-online.target
        [Service]
        ExecStart=/usr/local/bin/dnstt-server -udp 0.0.0.0:$udp -privkey-file /etc/dnstt/server.key -domain $domain -socks-port $socks
        Restart=always
        RestartSec=3
        LimitNOFILE=1048576
        [Install]
        WantedBy=multi-user.target
        UNIT
        if command -v ufw >/dev/null 2>&1; then ufw allow $udp/udp || true; fi
        if command -v firewall-cmd >/dev/null 2>&1; then firewall-cmd --add-port=$udp/udp --permanent && firewall-cmd --reload || true; fi
        systemctl daemon-reload
        systemctl enable --now dnstt-server
        sleep 1
        systemctl is-active dnstt-server && echo "Служба dnstt-server активна на порту $udp"
        echo "DNSTT_PUBKEY=$(cat /etc/dnstt/server.pub)"
    """.trimIndent()
}
