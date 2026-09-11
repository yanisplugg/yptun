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
 * One-tap qWDTT server install on a VPS, the way the qWDTT app itself deploys: its own `deploy.sh`
 * (assets/wdtt/deploy.sh — distro detection, prerequisites, sysctl, NAT/firewall, systemd unit, admin
 * TLS) is uploaded with the server binary and run with the ports and secrets in its environment.
 *
 * Around it, what that script does not cover and a real VPS needs:
 *  - an older WDTT under ANY unit name (the pre-Plus `wdtt-server.service`, a hand-started one…) still
 *    holding the port is stopped, its unit and binary removed — deploy.sh only knows `wdtt.service`;
 *  - a WDTT Plus database is moved aside: the qWDTT server stops dead on a passwords.json it can't
 *    read, and clients fetch their WireGuard config from the server every start anyway;
 *  - ports another program holds (the freeturn server also defaults to 56000) are skipped: DTLS, the
 *    internal WireGuard and the admin port each move to the next free one, and the location follows;
 *  - "installed" means the service stays up: deploy.sh checks `is-active` after 2 s, which a crash
 *    loop passes. We re-check after a few seconds with NRestarts and show the journal on failure.
 *
 * Every step is its own fresh SSH connection (some VPSes reset the link on a 2nd channel).
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

            onLog("Готовлю VPS: старый WDTT, база WDTT Plus, свободные порты…")
            val prepared = sshOneShot(target, buildPrepareScript(options.wdttPort), onLog)
            prepared.lines().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith(PORTS_MARKER) }
                .forEach(onLog)
            val ports = parsePorts(prepared) ?: error("VPS не сообщил свободные порты:\n${prepared.trim()}")
            onLog("Порты: DTLS ${ports.dtls}/udp, WireGuard ${ports.wg}/udp (только локально), админка ${ports.admin}/tcp")

            val gz = loadServerBinaryGz(binaries, "wdtt/wdtt-server-linux-$goArch")
            onLog("Загрузка сервера qWDTT (${gz.size / 1024} КБ, по частям)…")
            sshUploadInChunks(target, gz, REMOTE_GZ, onLog)
            // CR stripped: a Windows checkout can hand the asset over with CRLF, and bash on the VPS
            // then dies on the first line ("syntax error near {\r").
            val script = binaries.bytesOrNull(DEPLOY_SCRIPT_ASSET)
                ?.let { bytes -> String(bytes, Charsets.UTF_8).replace("\r", "").toByteArray(Charsets.UTF_8) }
                ?: error("В сборке нет установщика $DEPLOY_SCRIPT_ASSET")
            onLog("Загрузка установщика qWDTT…")
            sshUploadInChunks(target, script, REMOTE_SCRIPT, onLog)

            onLog("Установка qWDTT (пакеты, сеть, служба) — может занять пару минут…")
            val output = sshOneShot(
                target,
                buildDeployCommand(options, ports, adminToken = randomToken()),
                onLog,
            )
            val report = readDeployOutput(output)
            report.lines.forEach(onLog)
            check(report.ok) {
                "Установщик qWDTT не завершился успехом (код ${report.exitCode ?: "?"}). " +
                    "Подробности — в строках журнала выше."
            }

            onLog("Проверяю, что служба держится…")
            val verify = sshOneShot(target, buildVerifyScript(), onLog)
            check(verify.contains(VERIFY_OK)) {
                "Служба wdtt не держится после запуска. Журнал сервера:\n${verify.trim()}"
            }

            WdttInstallResult("qWDTT установлен и запущен на ${options.host}:${ports.dtls}", ports.dtls)
        }
    }

    private companion object {
        const val REMOTE_GZ = "/tmp/wdtt-server.gz"
        const val REMOTE_SCRIPT = "/tmp/deploy.sh"
        const val DEPLOY_SCRIPT_ASSET = "wdtt/deploy.sh"

        fun randomToken(): String {
            val bytes = ByteArray(24)
            java.security.SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

internal data class WdttPorts(val dtls: Int, val wg: Int, val admin: Int)

internal const val PORTS_MARKER = "WDTT_PORTS="
internal const val VERIFY_OK = "WDTT_SERVICE_STABLE"

internal fun parsePorts(output: String): WdttPorts? {
    val line = output.lineSequence().map { it.trim() }.lastOrNull { it.startsWith(PORTS_MARKER) } ?: return null
    val parts = line.removePrefix(PORTS_MARKER).split('|').map { it.trim().toIntOrNull() }
    if (parts.size != 3 || parts.any { it == null || it !in 1..65535 }) return null
    return WdttPorts(parts[0]!!, parts[1]!!, parts[2]!!)
}

/**
 * Runs before deploy.sh: takes down every older WDTT (any unit name) still holding the DTLS or the WG
 * port, sets a WDTT Plus database aside, then picks free ports and prints `WDTT_PORTS=dtls|wg|admin`.
 */
internal fun buildPrepareScript(requestedPort: Int): String {
    val d = "$"
    return """
        set -e
        systemctl stop wdtt >/dev/null 2>&1 || true
        udp_pids() { ss -Hulnp "sport = :${d}1" 2>/dev/null | grep -o 'pid=[0-9]*' | cut -d= -f2 | sort -u; }
        udp_busy() { ss -Hulnp "sport = :${d}1" 2>/dev/null | grep -q .; }
        tcp_busy() { ss -Htlnp "sport = :${d}1" 2>/dev/null | grep -q .; }
        if [ -f /etc/systemd/system/wdtt-server.service ]; then
          echo "Найден старый WDTT (wdtt-server.service) — удаляю"
          systemctl disable --now wdtt-server >/dev/null 2>&1 || true
          rm -f /etc/systemd/system/wdtt-server.service
        fi
        for P in $requestedPort 56001; do
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
          done
        done
        systemctl daemon-reload >/dev/null 2>&1 || true
        sleep 1
        if [ -f /etc/wdtt/passwords.json ] && grep -q '"main_password"' /etc/wdtt/passwords.json 2>/dev/null; then
          aside="/etc/wdtt.pre-qwdtt-${d}(date +%Y%m%d%H%M%S)"
          mv /etc/wdtt "${d}aside"
          echo "База WDTT Plus отложена в ${d}aside (qWDTT её не читает)"
        fi
        DTLS=$requestedPort
        if udp_busy ${d}DTLS; then
          owner=${d}(ss -Hulnp "sport = :${d}DTLS" 2>/dev/null | grep -o 'users:(("[^"]*' | head -1 | cut -d'"' -f2)
          while udp_busy ${d}DTLS; do DTLS=${d}((DTLS+1)); done
          echo "Порт $requestedPort/udp занят (${d}{owner:-другой программой}) — qWDTT встанет на ${d}DTLS"
        fi
        WG=56001
        while [ "${d}WG" = "${d}DTLS" ] || udp_busy ${d}WG; do WG=${d}((WG+1)); done
        ADMIN=56002
        while [ "${d}ADMIN" = "${d}DTLS" ] || [ "${d}ADMIN" = "${d}WG" ] || tcp_busy ${d}ADMIN; do ADMIN=${d}((ADMIN+1)); done
        echo "$PORTS_MARKER${d}DTLS|${d}WG|${d}ADMIN"
    """.trimIndent()
}

/**
 * Unpacks the binary, writes the secrets the way the qWDTT app does (files in /tmp that deploy.sh moves
 * into /etc/wdtt and deletes) and runs deploy.sh. Always exits 0 and reports deploy.sh's own exit code
 * as `WDTT_DEPLOY_EXIT=<n>`, so its output reaches the log even when it fails.
 */
internal fun buildDeployCommand(options: WdttInstallOptions, ports: WdttPorts, adminToken: String): String {
    val b64 = java.util.Base64.getEncoder()
    val password = b64.encodeToString(options.wdttPassword.toByteArray(Charsets.UTF_8))
    val token = b64.encodeToString(adminToken.toByteArray(Charsets.UTF_8))
    val dns = options.dns.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
        .ifEmpty { listOf("1.1.1.1") }.joinToString(",").shellSingleQuote()
    return """
        set -e
        gunzip -f /tmp/wdtt-server.gz
        printf '%s' '$password' | base64 -d > /tmp/wdtt-main.password
        printf '%s' '$token' | base64 -d > /tmp/wdtt-admin.token
        : > /tmp/wdtt-bot.token
        chmod 600 /tmp/wdtt-main.password /tmp/wdtt-admin.token /tmp/wdtt-bot.token
        set +e
        env WDTT_ADMIN_ID= WDTT_DNS_SERVERS=$dns WDTT_DTLS_PORT=${ports.dtls} WDTT_WG_PORT=${ports.wg} WDTT_ADMIN_PORT=${ports.admin} WDTT_SSH_PORT=${options.sshPort} bash /tmp/deploy.sh 2>&1
        echo "WDTT_DEPLOY_EXIT=${'$'}?"
        rm -f /tmp/deploy.sh
        exit 0
    """.trimIndent()
}

/** Checks a few seconds later that the service did not fall into a restart loop. */
internal fun buildVerifyScript(): String {
    val d = "$"
    return """
        sleep 6
        if systemctl is-active --quiet wdtt && [ "${d}(systemctl show -p NRestarts --value wdtt)" = "0" ]; then
          echo "$VERIFY_OK"
        else
          echo "Состояние: ${d}(systemctl is-active wdtt 2>/dev/null), перезапусков: ${d}(systemctl show -p NRestarts --value wdtt 2>/dev/null)"
          journalctl -u wdtt -n 30 --no-pager -o cat 2>/dev/null || true
        fi
    """.trimIndent()
}

internal data class DeployReport(val ok: Boolean, val exitCode: Int?, val lines: List<String>)

private val ansi = Regex("\u001B\\[[0-9;]*[A-Za-z]")

/**
 * deploy.sh's output for the log: colours stripped, its `WDTT_PROGRESS|x|step` lines turned into the
 * step names, apt noise and the secrets markers left out. Success = its own `WDTT_DEPLOY_OK` marker and
 * exit code 0.
 */
internal fun readDeployOutput(output: String): DeployReport {
    var exitCode: Int? = null
    var ok = false
    val lines = mutableListOf<String>()
    for (raw in output.lines()) {
        val line = raw.replace(ansi, "").trim()
        when {
            line.isEmpty() -> Unit
            line.startsWith("WDTT_DEPLOY_EXIT=") -> exitCode = line.substringAfter('=').toIntOrNull()
            line == "WDTT_DEPLOY_OK" -> ok = true
            line.startsWith("WDTT_PROGRESS|") -> line.split('|').getOrNull(2)?.takeIf { it.isNotBlank() }?.let { lines += "• $it" }
            line.startsWith("WDTT_ADMIN_PIN|") -> Unit
            line.startsWith("Get:") || line.startsWith("Hit:") || line.startsWith("Ign:") ||
                line.startsWith("Reading ") || line.startsWith("Selecting ") || line.startsWith("Preparing ") ||
                line.startsWith("Unpacking ") || line.startsWith("Setting up ") || line.startsWith("Processing ") ||
                line.startsWith("(Reading database") -> Unit
            else -> lines += line
        }
    }
    return DeployReport(ok = ok && exitCode == 0, exitCode = exitCode, lines = lines)
}
