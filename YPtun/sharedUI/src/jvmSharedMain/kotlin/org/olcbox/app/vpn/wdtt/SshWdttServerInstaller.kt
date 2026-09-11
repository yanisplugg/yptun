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
    ): Result<WdttInstallResult> = withContext(Dispatchers.IO) {
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
            output.lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith(PORT_MARKER) }
                .forEach(onLog)
            val port = installedPort(output) ?: options.wdttPort
            if (port != options.wdttPort) onLog("Порт WDTT в настройках локации изменён на $port")

            WdttInstallResult("wdtt-server установлен и запущен на ${options.host}:$port", port)
        }
    }

    private companion object {
        const val REMOTE_GZ = "/tmp/wdtt-server.gz"
    }
}

/** How the script reports the DTLS port the server actually runs on (see [buildInstallScript]). */
internal const val PORT_MARKER = "WDTT_PORT="

internal fun installedPort(output: String): Int? =
    output.lineSequence().map { it.trim() }.lastOrNull { it.startsWith(PORT_MARKER) }
        ?.removePrefix(PORT_MARKER)?.toIntOrNull()?.takeIf { it in 1..65535 }

/**
 * The remote install script, following the WDTT Plus deploy contract (binary /usr/local/bin/wdtt-server,
 * unit wdtt.service, data in /etc/wdtt). Decompresses + installs the binary, writes a systemd unit that
 * runs it as root (it needs CAP_NET_ADMIN for the WireGuard/NAT it sets up itself), opens the UDP port
 * on any common firewall (best-effort), starts the service and checks that it STAYS up.
 *
 * The pre-Plus WDTT is removed however it was started — its old unit (wdtt-server.service), or any
 * WDTT-looking process still holding the requested port or 56001: that process's systemd unit is
 * disabled and deleted, the process killed and its binary removed. A non-Plus /etc/wdtt is moved aside
 * (the Plus server refuses a database it doesn't recognise; the client fetches its WireGuard config
 * from the server on every start, so nothing is lost). Re-running over a Plus install keeps /etc/wdtt.
 *
 * Ports: if the requested DTLS port is still taken by something else (typically the freeturn server,
 * which also defaults to 56000) the next free UDP port is used and reported as `WDTT_PORT=<n>`; the
 * internal WireGuard port (server default 56001) is moved off anything already there too. A taken port
 * used to make the server exit at once and systemd restart it forever ("activating", exit code 3).
 *
 * If the service does not stay up, the script prints the service journal and fails with it. Single-quoted
 * values are escaped so an awkward password can't break out of the shell quoting.
 */
internal fun buildInstallScript(options: WdttInstallOptions): String {
    val port = options.wdttPort
    val pass = options.wdttPassword.shellSingleQuote()
    val dns = options.dns.ifBlank { "1.1.1.1" }.shellSingleQuote()
    val d = "$"
    return """
        set -e
        gunzip -f /tmp/wdtt-server.gz
        systemctl stop wdtt >/dev/null 2>&1 || true
        udp_pids() { ss -Hulnp "sport = :${d}1" 2>/dev/null | grep -o 'pid=[0-9]*' | cut -d= -f2 | sort -u; }
        udp_busy() { ss -Hulnp "sport = :${d}1" 2>/dev/null | grep -q .; }
        OLD=""
        if [ -f /etc/systemd/system/wdtt-server.service ]; then
          systemctl disable --now wdtt-server >/dev/null 2>&1 || true
          rm -f /etc/systemd/system/wdtt-server.service
          OLD=1
        fi
        for P in $port 56001; do
          for pid in ${d}(udp_pids ${d}P); do
            exe=${d}(readlink -f /proc/${d}pid/exe 2>/dev/null || true)
            unit=${d}(grep -o '[^/]*[.]service' /proc/${d}pid/cgroup 2>/dev/null | tail -1 || true)
            case "${d}(basename "${d}exe") ${d}unit" in *wdtt*|*wg-turn*|*WDTT*) ;; *) continue ;; esac
            echo "Найден старый WDTT на порту ${d}P (${d}exe ${d}unit) — удаляю"
            if [ -n "${d}unit" ] && [ "${d}unit" != "wdtt.service" ]; then
              frag=${d}(systemctl show -p FragmentPath --value "${d}unit" 2>/dev/null || true)
              systemctl disable --now "${d}unit" >/dev/null 2>&1 || true
              if [ -n "${d}frag" ]; then rm -f "${d}frag"; fi
            fi
            kill ${d}pid 2>/dev/null || true
            if [ "${d}exe" != /usr/local/bin/wdtt-server ]; then rm -f "${d}exe"; fi
            OLD=1
          done
        done
        if [ -n "${d}OLD" ]; then
          systemctl daemon-reload
          sleep 1
          echo "Старый WDTT удалён — ставлю WDTT Plus"
          if [ -d /etc/wdtt ] && ! grep -q '"main_password"' /etc/wdtt/passwords.json 2>/dev/null; then
            mv /etc/wdtt "/etc/wdtt.pre-plus-${d}(date +%Y%m%d%H%M%S)"
          fi
        fi
        PORT=$port
        if udp_busy ${d}PORT; then
          owner=${d}(ss -Hulnp "sport = :${d}PORT" 2>/dev/null | grep -o 'users:(("[^"]*' | head -1 | cut -d'"' -f2)
          while udp_busy ${d}PORT; do PORT=${d}((PORT+1)); done
          echo "Порт $port/udp занят (${d}{owner:-другой программой}) — WDTT Plus встанет на ${d}PORT"
        fi
        WGPORT=56001
        while [ "${d}WGPORT" = "${d}PORT" ] || udp_busy ${d}WGPORT; do WGPORT=${d}((WGPORT+1)); done
        install -m 0755 /tmp/wdtt-server /usr/local/bin/wdtt-server
        rm -f /tmp/wdtt-server
        cat > /etc/systemd/system/wdtt.service <<UNIT
        [Unit]
        Description=WDTT Plus server
        After=network-online.target
        Wants=network-online.target
        [Service]
        ExecStart=/usr/local/bin/wdtt-server -listen 0.0.0.0:${d}PORT -wg-port ${d}WGPORT -password $pass -dns $dns -config-dir /etc/wdtt
        Restart=always
        RestartSec=3
        LimitNOFILE=1048576
        [Install]
        WantedBy=multi-user.target
        UNIT
        if command -v ufw >/dev/null 2>&1; then ufw allow ${d}PORT/udp || true; fi
        if command -v firewall-cmd >/dev/null 2>&1; then firewall-cmd --add-port=${d}PORT/udp --permanent && firewall-cmd --reload || true; fi
        systemctl daemon-reload
        systemctl enable wdtt >/dev/null 2>&1
        systemctl restart wdtt
        sleep 5
        echo "Версия сервера: ${d}(/usr/local/bin/wdtt-server --version 2>/dev/null || echo ?)"
        if systemctl is-active --quiet wdtt && [ "${d}(systemctl show -p NRestarts --value wdtt)" = "0" ]; then
          echo "$PORT_MARKER${d}PORT"
          echo "Служба wdtt (WDTT Plus) активна на порту ${d}PORT"
        else
          echo "Служба wdtt не запускается. Последние строки журнала:"
          journalctl -u wdtt -n 25 --no-pager -o cat 2>/dev/null || true
          exit 3
        fi
    """.trimIndent()
}
