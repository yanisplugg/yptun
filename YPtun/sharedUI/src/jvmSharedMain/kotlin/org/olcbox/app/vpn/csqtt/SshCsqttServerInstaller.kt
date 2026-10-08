package org.olcbox.app.vpn.csqtt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.olcbox.app.vpn.ssh.ServerBinarySource
import org.olcbox.app.vpn.ssh.SshTarget
import org.olcbox.app.vpn.ssh.loadServerBinaryGz
import org.olcbox.app.vpn.ssh.sshOneShot
import org.olcbox.app.vpn.ssh.sshUpload

/**
 * One-tap csqtt server install on a VPS, the way the csqtt app itself deploys: its own `deploy.sh`
 * (assets/csqtt/deploy.sh — distro detection, prerequisites, sysctl, NAT/firewall, TLS, systemd unit)
 * is uploaded with the server binary for the VPS architecture, plus the two small files the script
 * expects next to them (web panel login, main password), and run as root.
 *
 * Around it: a port that another program holds (UDP peer port, TCP web port) is moved to the next
 * free one and the location follows; and "installed" means the service stays up — deploy.sh checks
 * once, we look again a few seconds later (restart counter) and show the journal on failure.
 *
 * Every step is its own fresh SSH connection (some VPSes reset the link on a 2nd channel).
 */
internal class SshCsqttServerInstaller(private val binaries: ServerBinarySource) : CsqttServerInstaller {

    override suspend fun install(
        options: CsqttInstallOptions,
        onLog: (String) -> Unit,
    ): Result<CsqttInstallResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(options.host.isNotBlank()) { "Не указан IP/хост VPS" }
            require(options.sshKey.isNotBlank() || options.sshPassword.isNotBlank()) {
                "Укажи пароль SSH или SSH-ключ"
            }
            require(options.password.isNotBlank()) { "Не указан пароль csqtt" }
            require(options.password.none(Char::isWhitespace)) { "В пароле csqtt не должно быть пробелов" }

            val target = SshTarget(
                options.host, options.sshPort, options.login, options.sshPassword,
                privateKey = options.sshKey, passphrase = options.sshKeyPassphrase,
            )

            onLog("Определяю архитектуру VPS…")
            val machine = sshOneShot(target, "uname -m", onLog, logProgress = true).trim()
            val arch = serverArch(machine)
                ?: error("Неподдерживаемая архитектура VPS: '$machine' (нужен x86_64, aarch64 или armv7)")
            onLog("Архитектура VPS: $machine → $arch")

            onLog("Готовлю VPS: свободные порты…")
            val prepared = sshOneShot(target, asRoot(buildPrepareScript(options.peerPort, options.webPort)), onLog)
            prepared.lines().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith(PORTS_MARKER) }
                .forEach(onLog)
            val ports = parsePorts(prepared) ?: error("VPS не сообщил свободные порты:\n${prepared.trim()}")
            onLog("Порты: peer ${ports.peer}/udp, веб-панель ${ports.web}/tcp")

            val gz = loadServerBinaryGz(binaries, "csqtt/csqtt-server-linux-$arch")
            onLog("Загрузка сервера csqtt (${gz.size / 1024} КБ)…")
            sshUpload(target, gz, REMOTE_GZ, onLog)
            // CR stripped: a Windows checkout can hand the asset over with CRLF, and bash on the VPS
            // then dies on the first line.
            val script = binaries.bytesOrNull(DEPLOY_SCRIPT_ASSET)
                ?.let { bytes -> String(bytes, Charsets.UTF_8).replace("\r", "").toByteArray(Charsets.UTF_8) }
                ?: error("В сборке нет установщика $DEPLOY_SCRIPT_ASSET")
            onLog("Загрузка установщика csqtt…")
            sshUpload(target, script, REMOTE_SCRIPT, onLog)

            val webLogin = options.webLogin.ifBlank { "admin" }
            val webPassword = options.webPassword.ifBlank { randomToken(12) }
            onLog("Установка csqtt (пакеты, сеть, служба) — может занять несколько минут…")
            val output = sshOneShot(
                target,
                asRoot(buildDeployCommand(options, ports, webLogin, webPassword, options.sshPort)),
                onLog,
            )
            val report = readDeployOutput(output)
            report.lines.forEach(onLog)
            check(report.ok) {
                "Установщик csqtt не завершился успехом (код ${report.exitCode ?: "?"}). " +
                    "Подробности — в строках журнала выше."
            }

            onLog("Проверяю, что служба держится…")
            val verify = sshOneShot(target, asRoot(buildVerifyScript()), onLog)
            check(verify.contains(VERIFY_OK)) {
                "Служба csqtt не держится после запуска. Журнал сервера:\n${verify.trim()}"
            }

            CsqttInstallResult(
                message = "csqtt установлен и запущен на ${options.host}:${ports.peer}. " +
                    "Веб-панель: https://${options.host}:${ports.web} ($webLogin / $webPassword). " +
                    "Основной пароль привязывается к первому подключившемуся устройству — " +
                    "для остальных создай ключи в веб-панели.",
                peerPort = ports.peer,
                webPort = ports.web,
                webLogin = webLogin,
                webPassword = webPassword,
            )
        }
    }

    private companion object {
        const val REMOTE_GZ = "/tmp/.csqtt-upload-server.gz"
        const val REMOTE_SCRIPT = "/tmp/deploy.sh"
        const val DEPLOY_SCRIPT_ASSET = "csqtt/deploy.sh"
    }
}

internal data class CsqttPorts(val peer: Int, val web: Int)

internal const val PORTS_MARKER = "CSQTT_PORTS="
internal const val VERIFY_OK = "CSQTT_SERVICE_STABLE"

/** `uname -m` → the suffix of the bundled server binary (`csqtt-server-linux-<arch>.gz`). */
internal fun serverArch(machine: String): String? = when {
    machine.contains("aarch64") || machine.contains("arm64") -> "arm64"
    machine.contains("x86_64") || machine.contains("amd64") -> "amd64"
    machine.startsWith("armv7") || machine.startsWith("armv8l") || machine == "arm" -> "armv7"
    else -> null
}

internal fun parsePorts(output: String): CsqttPorts? {
    val line = output.lineSequence().map { it.trim() }.lastOrNull { it.startsWith(PORTS_MARKER) } ?: return null
    val parts = line.removePrefix(PORTS_MARKER).split('|').map { it.trim().toIntOrNull() }
    if (parts.size != 2 || parts.any { it == null || it !in 1..65535 }) return null
    return CsqttPorts(parts[0]!!, parts[1]!!)
}

/** Runs [script] as root: directly when the login is root, else through `sudo -n`. Script travels as base64. */
internal fun asRoot(script: String): String {
    val b64 = java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_8))
    val d = "$"
    return "printf '%s' '$b64' | base64 -d > /tmp/.csqtt-step.sh; " +
        "if [ \"${d}(id -u)\" = \"0\" ]; then bash /tmp/.csqtt-step.sh; " +
        "elif command -v sudo >/dev/null 2>&1; then sudo -n bash /tmp/.csqtt-step.sh; " +
        "else echo 'нужны права root (или sudo без пароля)'; exit 1; fi; " +
        "rc=${d}?; rm -f /tmp/.csqtt-step.sh; exit ${d}rc"
}

/**
 * Picks the ports before deploy.sh: the requested ones when free (or already held by a csqtt of a
 * previous install — a redeploy takes them over), else the next free ones. Prints
 * `CSQTT_PORTS=peer|web`.
 */
internal fun buildPrepareScript(peerPort: Int, webPort: Int): String {
    val d = "$"
    return """
        udp_owner() { ss -Hulnp "sport = :${d}1" 2>/dev/null | grep -o 'users:(("[^"]*' | head -1 | cut -d'"' -f2; }
        tcp_owner() { ss -Htlnp "sport = :${d}1" 2>/dev/null | grep -o 'users:(("[^"]*' | head -1 | cut -d'"' -f2; }
        udp_busy() { ss -Hulnp "sport = :${d}1" 2>/dev/null | grep -q .; }
        tcp_busy() { ss -Htlnp "sport = :${d}1" 2>/dev/null | grep -q .; }
        PEER=$peerPort
        while udp_busy ${d}PEER && [ "${d}(udp_owner ${d}PEER)" != csqtt ]; do
          echo "Порт ${d}PEER/udp занят (${d}(udp_owner ${d}PEER)) — пробую следующий"
          PEER=${d}((PEER+1))
        done
        WEB=$webPort
        while [ "${d}WEB" = "${d}PEER" ] || { tcp_busy ${d}WEB && [ "${d}(tcp_owner ${d}WEB)" != csqtt ]; }; do
          echo "Порт ${d}WEB/tcp занят (${d}(tcp_owner ${d}WEB)) — пробую следующий"
          WEB=${d}((WEB+1))
        done
        echo "$PORTS_MARKER${d}PEER|${d}WEB"
    """.trimIndent()
}

/** systemd EnvironmentFile value: double-quoted, backslash and quote escaped, newlines flattened. */
internal fun systemdEnvironmentValue(value: String): String = buildString {
    append('"')
    value.forEach { ch ->
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n', '\r' -> append(' ')
            else -> append(ch)
        }
    }
    append('"')
}

/**
 * Unpacks the binary, writes the two files deploy.sh expects (web panel login, main password — through
 * base64, so no password can break the shell quoting) and runs `deploy.sh install`. Always exits 0 and
 * reports deploy.sh's own exit code as `CSQTT_DEPLOY_EXIT=<n>`, so its output reaches the log on failure.
 */
internal fun buildDeployCommand(
    options: CsqttInstallOptions,
    ports: CsqttPorts,
    webLogin: String,
    webPassword: String,
    sshPort: Int,
): String {
    val b64 = java.util.Base64.getEncoder()
    fun enc(text: String) = b64.encodeToString(text.toByteArray(Charsets.UTF_8))
    val env = "CSQTT_WEB_USER=${systemdEnvironmentValue(webLogin)}\nCSQTT_WEB_PASS=${systemdEnvironmentValue(webPassword)}\n"
    val overrides = buildJsonObject {
        put("main_password", options.password)
        // Empty: the main password binds to the first device that connects with it.
        put("device_id", "")
    }.toString()
    return """
        set -e
        gunzip -f /tmp/.csqtt-upload-server.gz
        printf '%s' '${enc(env)}' | base64 -d > /tmp/.csqtt-upload-web.env
        printf '%s' '${enc(overrides)}' | base64 -d > /tmp/.csqtt-upload-overrides.json
        chmod 600 /tmp/.csqtt-upload-web.env /tmp/.csqtt-upload-overrides.json
        set +e
        env CSQTT_PEER_PORT=${ports.peer} CSQTT_SSH_PORT=$sshPort CSQTT_WEB_PORT=${ports.web} CSQTT_DEPLOY_MODE=systemd bash /tmp/deploy.sh install 2>&1
        echo "CSQTT_DEPLOY_EXIT=${'$'}?"
        rm -f /tmp/deploy.sh
        exit 0
    """.trimIndent()
}

/** Checks a few seconds later that the service did not fall into a restart loop. */
internal fun buildVerifyScript(): String {
    val d = "$"
    return """
        sleep 6
        if systemctl is-active --quiet csqtt && [ "${d}(systemctl show -p NRestarts --value csqtt)" = "0" ]; then
          echo "$VERIFY_OK"
        else
          echo "Состояние: ${d}(systemctl is-active csqtt 2>/dev/null), перезапусков: ${d}(systemctl show -p NRestarts --value csqtt 2>/dev/null)"
          journalctl -u csqtt -n 30 --no-pager -o cat 2>/dev/null || true
        fi
    """.trimIndent()
}

internal data class DeployReport(val ok: Boolean, val exitCode: Int?, val lines: List<String>)

private val ansi = Regex("\u001B\\[[0-9;]*[A-Za-z]")

/**
 * deploy.sh's output for the log: colours stripped, its `CSQTT_PROGRESS|x|step` lines turned into the
 * step names, `CSQTT_DEPLOY_ERROR|phase|message` into a readable error, apt noise left out. Success =
 * its own `CSQTT_DEPLOY_OK` marker and exit code 0.
 */
internal fun readDeployOutput(output: String): DeployReport {
    var exitCode: Int? = null
    var ok = false
    val lines = mutableListOf<String>()
    for (raw in output.lines()) {
        val line = raw.replace(ansi, "").trim()
        when {
            line.isEmpty() -> Unit
            line.startsWith("CSQTT_DEPLOY_EXIT=") -> exitCode = line.substringAfter('=').toIntOrNull()
            line == "CSQTT_DEPLOY_OK" -> ok = true
            line.startsWith("CSQTT_PROGRESS|") -> line.split('|').getOrNull(2)?.takeIf { it.isNotBlank() }?.let { lines += "• $it" }
            line.startsWith("CSQTT_DEPLOY_ERROR|") -> {
                val parts = line.split('|', limit = 3)
                lines += "ОШИБКА (${parts.getOrNull(1).orEmpty()}): ${parts.getOrNull(2).orEmpty()}"
            }
            line.startsWith("Get:") || line.startsWith("Hit:") || line.startsWith("Ign:") ||
                line.startsWith("Reading ") || line.startsWith("Selecting ") || line.startsWith("Preparing ") ||
                line.startsWith("Unpacking ") || line.startsWith("Setting up ") || line.startsWith("Processing ") ||
                line.startsWith("(Reading database") -> Unit
            else -> lines += line
        }
    }
    return DeployReport(ok = ok && exitCode == 0, exitCode = exitCode, lines = lines)
}

private fun randomToken(length: Int): String {
    val alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val random = java.security.SecureRandom()
    return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
}
