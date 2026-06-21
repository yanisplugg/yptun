package org.olcbox.app.vpn.wdtt

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.vpn.ssh.openSshSession

@Composable
actual fun rememberWdttServerInstaller(): WdttServerInstaller {
    val context = LocalContext.current.applicationContext
    return remember { AndroidWdttServerInstaller(context) }
}

/**
 * SSH-based wdtt-server installer. Connects with password auth, detects the VPS architecture
 * (`uname -m`), uploads the matching bundled server binary (gzip asset) to /tmp, then runs a small
 * install script that places it in /usr/local/bin and starts it as a systemd service. The server
 * binary sets up IP forwarding, NAT and the userspace WireGuard tunnel by itself, so the script is
 * deliberately minimal. The binaries live in assets/wdtt/ (see build-wdtt-server.ps1).
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

            val session = openSshSession(
                host = options.host,
                port = options.sshPort,
                login = options.login,
                rawPassword = options.sshPassword,
                onLog = onLog
            )
            try {
                onLog("SSH соединение установлено")

                val machine = exec(session, "uname -m").trim()
                val goArch = when {
                    machine.contains("aarch64") || machine.contains("arm64") -> "arm64"
                    machine.contains("x86_64") || machine.contains("amd64") -> "amd64"
                    else -> error("Неподдерживаемая архитектура VPS: '$machine' (нужен x86_64 или aarch64)")
                }
                onLog("Архитектура VPS: $machine → $goArch")

                val assetPath = "wdtt/wdtt-server-linux-$goArch.gz"
                onLog("Загрузка сервера ($assetPath)…")
                upload(session, assetPath, REMOTE_GZ, onLog)
                onLog("Бинарник загружен в $REMOTE_GZ")

                onLog("Установка и запуск службы…")
                val script = buildInstallScript(options)
                val output = exec(session, "bash -s", stdin = script.byteInputStream())
                output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach(onLog)

                "wdtt-server установлен и запущен на ${options.host}:${options.wdttPort}"
            } finally {
                session.disconnect()
            }
        }
    }

    /**
     * Uploads the bundled gzip asset to [remotePath] **as-is** (still compressed) — the remote
     * install script gunzips it. Tries SFTP first; if the server has no SFTP subsystem (minimal
     * images often ship without it) falls back to streaming the bytes through `cat > remotePath`
     * on a plain exec channel (8-bit clean, no PTY), the same trick the reference deployer uses.
     */
    private fun upload(session: Session, assetPath: String, remotePath: String, onLog: (String) -> Unit) {
        if (uploadViaSftp(session, assetPath, remotePath, onLog)) return
        context.assets.open(assetPath).use { input ->
            exec(session, "cat > ${remotePath.shellSingleQuote()}", stdin = input)
        }
    }

    /** SFTP upload of the raw asset bytes. Returns false (with a log note) if SFTP is unavailable. */
    private fun uploadViaSftp(session: Session, assetPath: String, remotePath: String, onLog: (String) -> Unit): Boolean =
        try {
            val sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(CONNECT_TIMEOUT_MS)
            try {
                context.assets.open(assetPath).use { input ->
                    sftp.put(input, remotePath, ChannelSftp.OVERWRITE)
                }
            } finally {
                sftp.disconnect()
            }
            true
        } catch (e: Exception) {
            onLog("SFTP недоступен (${e.message}); загружаю через exec…")
            false
        }

    /**
     * Runs [command] (optionally feeding [stdin] to it), merges stdout+stderr and returns the
     * combined output. Throws if the remote command exits non-zero, with the output as the message.
     */
    private fun exec(session: Session, command: String, stdin: InputStream? = null): String {
        val channel = session.openChannel("exec") as ChannelExec
        val merged = ByteArrayOutputStream()
        channel.setCommand(command)
        channel.setErrStream(merged) // fold stderr into the same buffer
        if (stdin != null) channel.setInputStream(stdin)
        val out = channel.inputStream
        channel.connect(CONNECT_TIMEOUT_MS)
        try {
            val buf = ByteArray(8192)
            while (true) {
                while (out.available() > 0) {
                    val n = out.read(buf)
                    if (n < 0) break
                    merged.write(buf, 0, n)
                }
                if (channel.isClosed) {
                    if (out.available() > 0) continue
                    break
                }
                Thread.sleep(50)
            }
        } finally {
            channel.disconnect()
        }
        val text = merged.toString(Charsets.UTF_8.name())
        val code = channel.exitStatus
        if (code != 0) {
            throw RuntimeException("Команда завершилась с кодом $code:\n${text.trim()}")
        }
        return text
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 25_000
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

/** Wraps [this] in single quotes for safe shell interpolation, escaping any embedded single quote. */
private fun String.shellSingleQuote(): String = "'" + replace("'", "'\\''") + "'"
