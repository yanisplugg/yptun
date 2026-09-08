package org.olcbox.app.vpn.masterdns

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.vpn.ssh.SshTarget
import org.olcbox.app.vpn.ssh.loadServerBinaryGz
import org.olcbox.app.vpn.ssh.shellSingleQuote
import org.olcbox.app.vpn.ssh.sshOneShot
import org.olcbox.app.vpn.ssh.sshUploadInChunks

@Composable
actual fun rememberMasterDnsServerInstaller(): MasterDnsServerInstaller {
    val context = LocalContext.current.applicationContext
    return remember { AndroidMasterDnsServerInstaller(context) }
}

/**
 * SSH-based MasterDnsVPN server installer. Connects with password or key auth, detects the VPS
 * architecture (`uname -m`), streams the matching bundled server binary (gzip asset) into /tmp via a
 * plain exec channel (no SFTP — minimal VPS images often lack the subsystem), then runs the install
 * script as a single shell command: it places the binary in /usr/local/bin, writes the server config,
 * generates a PERSISTENT encryption key (only when one doesn't exist yet, so the key stays valid
 * across reinstalls), writes a systemd unit and starts it. The script prints the key on a
 * `MASTERDNS_KEY=` line, which is parsed out and returned. The binaries live in assets/masterdns/
 * (see masterdns/build-masterdns-server.ps1).
 */
internal class AndroidMasterDnsServerInstaller(private val context: Context) : MasterDnsServerInstaller {

    override suspend fun install(
        options: MasterDnsInstallOptions,
        onLog: (String) -> Unit
    ): Result<MasterDnsInstallResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(options.host.isNotBlank()) { "Не указан IP/хост VPS" }
            require(options.sshKey.isNotBlank() || options.sshPassword.isNotBlank()) {
                "Укажи пароль SSH или SSH-ключ"
            }
            require(options.domain.isNotBlank()) { "Не указан домен туннеля" }

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

            val gz = loadServerBinaryGz(context, "masterdns/masterdns-server-linux-$goArch")
            onLog("Загрузка сервера (${gz.size / 1024} КБ, по частям)…")
            sshUploadInChunks(target, gz, REMOTE_GZ, onLog)
            onLog("Бинарник загружен, ставлю службу и генерирую ключ…")

            val output = sshOneShot(target, buildInstallScript(options), onLog)

            var key = ""
            output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                val marker = line.substringAfter("MASTERDNS_KEY=", "")
                if (marker.isNotEmpty()) key = marker.trim()
                else onLog(line)
            }
            if (key.isBlank()) {
                error("Не удалось получить ключ шифрования с сервера")
            }
            onLog("Ключ шифрования получен")

            MasterDnsInstallResult(
                encryptionKey = key,
                message = "MasterDNS-сервер установлен и запущен на ${options.host}:${options.udpPort} " +
                    "(резолвер: ${options.host}:${options.udpPort})"
            )
        }
    }

    private companion object {
        const val REMOTE_GZ = "/tmp/masterdns-server.gz"
    }
}

/**
 * The remote install script. Decompresses + installs the binary, writes the server config, generates a
 * PERSISTENT encryption key the first time (`-genkey` is a no-op when `encrypt_key.txt` already
 * exists, so the key survives a reinstall), writes a systemd unit, opens the UDP port on any common
 * firewall (best-effort), starts the service and prints the key on a `MASTERDNS_KEY=` line.
 *
 * `USE_EXTERNAL_SOCKS5 = false` makes the server its own internet exit, so no second daemon is needed.
 * Single-quoted values are escaped so an awkward domain can't break out of the shell quoting.
 */
internal fun buildInstallScript(options: MasterDnsInstallOptions): String {
    val udp = options.udpPort
    val domain = options.domain.shellSingleQuote()
    val encryption = options.encryptionMethod.coerceIn(0, 5)
    return """
        set -e
        gunzip -f /tmp/masterdns-server.gz
        install -m 0755 /tmp/masterdns-server /usr/local/bin/masterdns-server
        rm -f /tmp/masterdns-server
        mkdir -p /etc/masterdns
        cat > /etc/masterdns/server_config.toml <<CONFIG
        PROTOCOL_TYPE = "SOCKS5"
        DOMAIN = [$domain]
        UDP_HOST = ""
        UDP_PORT = $udp
        DATA_ENCRYPTION_METHOD = $encryption
        ENCRYPTION_KEY_FILE = "/etc/masterdns/encrypt_key.txt"
        USE_EXTERNAL_SOCKS5 = false
        LOG_LEVEL = "INFO"
        CONFIG
        /usr/local/bin/masterdns-server -config /etc/masterdns/server_config.toml -genkey -nowait
        chmod 600 /etc/masterdns/encrypt_key.txt
        cat > /etc/systemd/system/masterdns-server.service <<UNIT
        [Unit]
        Description=MasterDnsVPN Server
        After=network-online.target
        Wants=network-online.target
        [Service]
        ExecStart=/usr/local/bin/masterdns-server -config /etc/masterdns/server_config.toml -nowait
        Restart=always
        RestartSec=3
        LimitNOFILE=1048576
        [Install]
        WantedBy=multi-user.target
        UNIT
        if command -v ufw >/dev/null 2>&1; then ufw allow $udp/udp || true; fi
        if command -v firewall-cmd >/dev/null 2>&1; then firewall-cmd --add-port=$udp/udp --permanent && firewall-cmd --reload || true; fi
        systemctl daemon-reload
        systemctl enable --now masterdns-server
        sleep 1
        systemctl is-active masterdns-server && echo "Служба masterdns-server активна на порту $udp"
        echo "MASTERDNS_KEY=$(cat /etc/masterdns/encrypt_key.txt)"
    """.trimIndent()
}
