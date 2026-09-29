package org.olcbox.app.vpn.snolc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.data.model.SnolcConfig
import org.olcbox.app.vpn.ssh.ServerBinarySource
import org.olcbox.app.vpn.ssh.SshTarget
import org.olcbox.app.vpn.ssh.loadServerBinaryGz
import org.olcbox.app.vpn.ssh.sshOneShot
import org.olcbox.app.vpn.ssh.sshUpload

/** SSH snolc exit-node installer — same shape as the OpenFlux one: `uname -m`, gzip'd binary, one script. */
internal class SshSnolcServerInstaller(private val binaries: ServerBinarySource) : SnolcServerInstaller {

    override suspend fun install(options: SnolcInstallOptions, onLog: (String) -> Unit): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(options.host.isNotBlank()) { "Не указан IP/хост VPS" }
                require(options.sshKey.isNotBlank() || options.sshPassword.isNotBlank()) { "Укажи пароль SSH или SSH-ключ" }
                require(options.listenPort in 1..65535) { "Порт вне диапазона 1–65535" }
                val target = SshTarget(
                    options.host, options.sshPort, options.login, options.sshPassword,
                    privateKey = options.sshKey, passphrase = options.sshKeyPassphrase,
                )

                onLog("Определяю архитектуру VPS…")
                val machine = sshOneShot(target, "uname -m", onLog, logProgress = true).trim()
                val arch = when {
                    machine.contains("aarch64") || machine.contains("arm64") -> "arm64"
                    machine.contains("x86_64") || machine.contains("amd64") -> "amd64"
                    else -> error("Неподдерживаемая архитектура VPS: '$machine' (нужен x86_64 или aarch64)")
                }
                onLog("Архитектура VPS: $machine → $arch")

                val gz = loadServerBinaryGz(binaries, "snolc/snolc-server-linux-$arch")
                onLog("Загрузка snolc (${gz.size / 1024} КБ, по частям)…")
                sshUpload(target, gz, REMOTE_GZ, onLog)
                onLog("Бинарник загружен, ставлю службу…")

                val output = sshOneShot(target, buildSnolcInstallScript(options.listenPort), onLog)
                output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith(KEY_MARK) }.forEach(onLog)
                output.lineSequence().firstOrNull { it.trim().startsWith("ОШИБКА:") }?.let {
                    error(it.trim().removePrefix("ОШИБКА:").trim())
                }
                val key = output.lineSequence().map { it.trim() }.firstOrNull { it.startsWith(KEY_MARK) }
                    ?.removePrefix(KEY_MARK)?.trim()
                    ?: error("Нода запущена, но публичный ключ не получен")
                val config = SnolcConfig(host = options.host.trim(), port = options.listenPort, publicKey = key)
                check(config.isComplete()) { "Нода вернула некорректный ключ: $key" }
                config.toUri()
            }
        }

    private companion object {
        const val REMOTE_GZ = "/tmp/snolc.gz"
    }
}

internal const val KEY_MARK = "SNOLC_PUBLIC_KEY="

/**
 * The remote script. Keys survive a reinstall (links already handed out keep working); the config is
 * rewritten every time so a new [port] takes effect. An earlier install is replaced wholesale.
 */
internal fun buildSnolcInstallScript(port: Int): String {
    val files = SnolcFiles.server(port, keyFile = "/etc/snolc/priv.hex", stateDir = "/var/lib/snolc")
    val writes = files.entries.joinToString("\n") { (path, body) ->
        "cat > /etc/snolc/$path <<'SNOLCEOF'\n${body.trimEnd()}\nSNOLCEOF"
    }
    val d = '$'
    return """
        set -e
        gunzip -f /tmp/snolc.gz
        systemctl disable --now snolc >/dev/null 2>&1 || true
        install -m 0755 /tmp/snolc /usr/local/bin/snolc
        rm -f /tmp/snolc
        mkdir -p /etc/snolc/modules /var/lib/snolc
        chmod 700 /etc/snolc
        if [ ! -s /etc/snolc/priv.hex ]; then
          snolc keygen /etc/snolc/priv.hex /etc/snolc/pub.hex >/dev/null
        fi
        chmod 600 /etc/snolc/priv.hex
    """.trimIndent() + "\n" + writes + "\n" + """
        snolc validate /etc/snolc/snolc.toml >/dev/null || { echo "ОШИБКА: snolc не принял конфигурацию"; exit 1; }
        cat > /etc/systemd/system/snolc.service <<'UNIT'
        [Unit]
        Description=snolc exit node
        After=network-online.target
        Wants=network-online.target
        [Service]
        ExecStart=/usr/local/bin/snolc run /etc/snolc/snolc.toml
        Restart=always
        RestartSec=5
        LimitNOFILE=65536
        [Install]
        WantedBy=multi-user.target
        UNIT
        systemctl daemon-reload
        systemctl enable --now snolc
        if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
          ufw allow $port/tcp >/dev/null && echo "ufw: порт $port/tcp открыт"
        fi
        sleep 2
        systemctl is-active --quiet snolc || { echo "ОШИБКА: служба snolc не запустилась: ${d}(journalctl -u snolc -n 5 -o cat --no-pager | tail -3)"; exit 1; }
        (ss -ltn 2>/dev/null | grep -q ":$port ") || { echo "ОШИБКА: порт $port не слушается"; exit 1; }
        echo "Служба snolc активна, слушает порт $port"
        echo "$KEY_MARK${d}(cat /etc/snolc/pub.hex)"
    """.trimIndent()
}
