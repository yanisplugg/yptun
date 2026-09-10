package org.olcbox.app.vpn.wdtt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.vpn.ssh.SshTarget
import org.olcbox.app.vpn.ssh.ServerBinarySource
import org.olcbox.app.vpn.ssh.loadServerBinaryGz
import org.olcbox.app.vpn.ssh.shellSingleQuote
import org.olcbox.app.vpn.ssh.sshOneShot
import org.olcbox.app.vpn.ssh.sshUploadInChunks

/**
 * SSH-based wdtt-server installer. Connects with password auth, detects the VPS architecture
 * (`uname -m`), streams the matching bundled server binary (gzip asset) into /tmp via a plain exec
 * channel (no SFTP — minimal VPS images often lack the subsystem), then runs the install script as a
 * single shell command that places it in /usr/local/bin and starts it as a systemd service. The
 * server binary sets up IP forwarding, NAT and the userspace WireGuard tunnel by itself, so the
 * script is deliberately minimal. The binaries live in assets/wdtt/ (see build-wdtt-server.ps1).
 */
internal class SshWdttServerInstaller(private val binaries: ServerBinarySource) : WdttServerInstaller {

    override suspend fun install(
        options: WdttInstallOptions,
        onLog: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(options.host.isNotBlank()) { "Не указан IP/хост VPS" }
            require(options.sshKey.isNotBlank() || options.sshPassword.isNotBlank()) {
                "Укажи пароль SSH или SSH-ключ"
            }
            require(options.wdttPassword.isNotBlank()) { "Не указан пароль WDTT" }

            // This VPS resets the link the moment a 2nd channel is opened on a connection, so EVERY
            // step is its own fresh connection running one small command (the only thing that worked).
            val target = SshTarget(
                options.host, options.sshPort, options.login, options.sshPassword,
                privateKey = options.sshKey, passphrase = options.sshKeyPassphrase,
            )

            onLog("Определяю архитектуру VPS…")
            val machine = sshOneShot(target, "uname -m", onLog, logProgress = true).trim()
            val goArch = when {
                machine.contains("aarch64") || machine.contains("arm64") -> "arm64"
                machine.contains("x86_64") || machine.contains("amd64") -> "amd64"
                else -> error("Неподдерживаемая архитектура VPS: '$machine' (нужен x86_64 или aarch64)")
            }
            onLog("Архитектура VPS: $machine → $goArch")

            val gz = loadServerBinaryGz(binaries, "wdtt/wdtt-server-linux-$goArch")
            onLog("Загрузка сервера (${gz.size / 1024} КБ, по частям)…")
            sshUploadInChunks(target, gz, REMOTE_GZ, onLog)
            onLog("Бинарник загружен, ставлю службу…")

            val output = sshOneShot(target, buildInstallScript(options), onLog)
            output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach(onLog)

            "wdtt-server установлен и запущен на ${options.host}:${options.wdttPort}"
        }
    }

    private companion object {
        const val REMOTE_GZ = "/tmp/wdtt-server.gz"
    }
}

/**
 * The remote install script, following the WDTT Plus deploy contract (binary /usr/local/bin/wdtt-server,
 * unit wdtt.service, data in /etc/wdtt). Decompresses + installs the binary, writes a systemd unit that
 * runs it as root (it needs CAP_NET_ADMIN for the WireGuard/NAT it sets up itself), opens the UDP port
 * on any common firewall (best-effort), starts the service and prints its active state.
 *
 * Upgrading from the pre-Plus WDTT (unit wdtt-server.service): that service is stopped and removed and
 * its /etc/wdtt moved aside — the Plus server refuses to start on a database it doesn't recognise, and
 * a fresh one costs nothing (the client fetches its WireGuard config from the server every start).
 * Re-running over a Plus install keeps /etc/wdtt (clients, keys) as is. Single-quoted values are
 * escaped so an awkward password can't break out of the shell quoting.
 */
internal fun buildInstallScript(options: WdttInstallOptions): String {
    val port = options.wdttPort
    val pass = options.wdttPassword.shellSingleQuote()
    val dns = options.dns.ifBlank { "1.1.1.1" }.shellSingleQuote()
    return """
        set -e
        gunzip -f /tmp/wdtt-server.gz
        if [ -f /etc/systemd/system/wdtt-server.service ]; then
          echo "Найден старый WDTT — переношу на WDTT Plus"
          systemctl disable --now wdtt-server >/dev/null 2>&1 || true
          rm -f /etc/systemd/system/wdtt-server.service
          if [ -d /etc/wdtt ]; then mv /etc/wdtt "/etc/wdtt.pre-plus-${'$'}(date +%Y%m%d%H%M%S)"; fi
        fi
        systemctl stop wdtt >/dev/null 2>&1 || true
        install -m 0755 /tmp/wdtt-server /usr/local/bin/wdtt-server
        rm -f /tmp/wdtt-server
        cat > /etc/systemd/system/wdtt.service <<UNIT
        [Unit]
        Description=WDTT Plus server
        After=network-online.target
        Wants=network-online.target
        [Service]
        ExecStart=/usr/local/bin/wdtt-server -listen 0.0.0.0:$port -password $pass -dns $dns -config-dir /etc/wdtt
        Restart=always
        RestartSec=3
        LimitNOFILE=1048576
        [Install]
        WantedBy=multi-user.target
        UNIT
        if command -v ufw >/dev/null 2>&1; then ufw allow $port/udp || true; fi
        if command -v firewall-cmd >/dev/null 2>&1; then firewall-cmd --add-port=$port/udp --permanent && firewall-cmd --reload || true; fi
        systemctl daemon-reload
        systemctl enable --now wdtt
        sleep 2
        echo "Версия сервера: ${'$'}(/usr/local/bin/wdtt-server --version 2>/dev/null || echo ?)"
        systemctl is-active wdtt && echo "Служба wdtt (WDTT Plus) активна на порту $port"
    """.trimIndent()
}
