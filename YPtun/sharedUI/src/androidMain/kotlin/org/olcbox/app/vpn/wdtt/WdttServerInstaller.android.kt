package org.olcbox.app.vpn.wdtt

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
actual fun rememberWdttServerInstaller(): WdttServerInstaller {
    val context = LocalContext.current.applicationContext
    return remember { AndroidWdttServerInstaller(context) }
}

/**
 * SSH-based wdtt-server installer. Connects with password auth, detects the VPS architecture
 * (`uname -m`), streams the matching bundled server binary (gzip asset) into /tmp via a plain exec
 * channel (no SFTP — minimal VPS images often lack the subsystem), then runs the install script as a
 * single shell command that places it in /usr/local/bin and starts it as a systemd service. The
 * server binary sets up IP forwarding, NAT and the userspace WireGuard tunnel by itself, so the
 * script is deliberately minimal. The binaries live in assets/wdtt/ (see build-wdtt-server.ps1).
 */
internal class AndroidWdttServerInstaller(private val context: Context) : WdttServerInstaller {

    override suspend fun install(
        options: WdttInstallOptions,
        onLog: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(options.host.isNotBlank()) { "Не указан IP/хост VPS" }
            require(options.sshPassword.isNotBlank()) { "Не указан пароль SSH" }
            require(options.wdttPassword.isNotBlank()) { "Не указан пароль WDTT" }

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

            val gz = context.assets.open("wdtt/wdtt-server-linux-$goArch.gz").use { it.readBytes() }
            onLog("Установка сервера (${gz.size / 1024} КБ) одним сеансом…")
            // Connection 2 — one channel: deliver the binary (base64 heredoc) AND run the install
            // script, all in a single `sh -s` stream.
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
            output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach(onLog)

            "wdtt-server установлен и запущен на ${options.host}:${options.wdttPort}"
        }
    }

    private companion object {
        const val REMOTE_GZ = "/tmp/wdtt-server.gz"
    }
}

/**
 * The remote install script. Decompresses + installs the binary, writes a systemd unit that runs
 * it as root (it needs CAP_NET_ADMIN for the TUN/NAT it sets up itself), opens the UDP port on any
 * common firewall (best-effort), starts the service and prints its active state. Single-quoted
 * values are escaped so an awkward password can't break out of the shell quoting.
 */
internal fun buildInstallScript(options: WdttInstallOptions): String {
    val port = options.wdttPort
    val pass = options.wdttPassword.shellSingleQuote()
    val dns = options.dns.ifBlank { "1.1.1.1" }.shellSingleQuote()
    return """
        set -e
        gunzip -f /tmp/wdtt-server.gz
        install -m 0755 /tmp/wdtt-server /usr/local/bin/wdtt-server
        rm -f /tmp/wdtt-server
        cat > /etc/systemd/system/wdtt-server.service <<UNIT
        [Unit]
        Description=WDTT Server
        After=network-online.target
        Wants=network-online.target
        [Service]
        ExecStart=/usr/local/bin/wdtt-server -listen 0.0.0.0:$port -password $pass -dns $dns
        Restart=always
        RestartSec=3
        LimitNOFILE=1048576
        [Install]
        WantedBy=multi-user.target
        UNIT
        if command -v ufw >/dev/null 2>&1; then ufw allow $port/udp || true; fi
        if command -v firewall-cmd >/dev/null 2>&1; then firewall-cmd --add-port=$port/udp --permanent && firewall-cmd --reload || true; fi
        systemctl daemon-reload
        systemctl enable --now wdtt-server
        sleep 1
        systemctl is-active wdtt-server && echo "Служба wdtt-server активна на порту $port"
    """.trimIndent()
}
