package org.olcbox.app.vpn.dnstt

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayOutputStream
import java.util.Properties
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
actual fun rememberDnsttServerInstaller(): DnsttServerInstaller {
    val context = LocalContext.current.applicationContext
    return remember { AndroidDnsttServerInstaller(context) }
}

/**
 * SSH-based dnstt-server installer. Connects with password auth, detects the VPS architecture
 * (`uname -m`), uploads the matching bundled server binary (gzip asset) to /tmp, then runs a small
 * install script that places it in /usr/local/bin, generates a persistent Noise keypair (only if one
 * doesn't already exist, so the public key is stable across reinstalls), writes a systemd unit that
 * runs the server with its built-in SOCKS5 exit and starts it. The script prints the public key on a
 * `DNSTT_PUBKEY=` line, which is parsed out and returned. The binaries live in assets/dnstt/ (see
 * build-dnstt-server.ps1).
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

            val jsch = JSch()
            val session = jsch.getSession(options.login.ifBlank { "root" }, options.host, options.sshPort)
            session.setPassword(options.sshPassword)
            // VPSes rarely have a known host key on first contact; accept it (password auth still
            // protects the channel). Matches the WDTT installer's behaviour.
            session.setConfig(Properties().apply {
                put("StrictHostKeyChecking", "no")
                put("PreferredAuthentications", "password,keyboard-interactive")
            })
            // Many servers (PAM / OpenSSH with KbdInteractiveAuthentication) accept the password ONLY
            // via keyboard-interactive, not the plain "password" method — JSch then reports "Auth fail
            // for methods publickey,password" despite a correct password. A UserInfo that answers the
            // interactive prompt with the same password covers both methods.
            session.userInfo = SshPasswordUserInfo(options.sshPassword)
            onLog("Подключение к ${options.host}:${options.sshPort} (пользователь '${options.login.ifBlank { "root" }}', пароль ${options.sshPassword.length} симв.)…")
            connectOrExplain(session, options.login.ifBlank { "root" })
            try {
                onLog("SSH соединение установлено")

                val machine = exec(session, "uname -m").trim()
                val goArch = when {
                    machine.contains("aarch64") || machine.contains("arm64") -> "arm64"
                    machine.contains("x86_64") || machine.contains("amd64") -> "amd64"
                    else -> error("Неподдерживаемая архитектура VPS: '$machine' (нужен x86_64 или aarch64)")
                }
                onLog("Архитектура VPS: $machine → $goArch")

                val assetPath = "dnstt/dnstt-server-linux-$goArch.gz"
                onLog("Загрузка сервера ($assetPath)…")
                uploadAsset(session, assetPath, REMOTE_GZ)
                onLog("Бинарник загружен в $REMOTE_GZ")

                onLog("Установка, генерация ключа и запуск службы…")
                val script = buildInstallScript(options)
                val output = exec(session, "bash -s", stdin = script)

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
            } finally {
                session.disconnect()
            }
        }
    }

    /** Streams a gzip-compressed asset to [remotePath] over SFTP, decompressing on the fly. */
    private fun uploadAsset(session: Session, assetPath: String, remotePath: String) {
        val sftp = session.openChannel("sftp") as ChannelSftp
        sftp.connect(CONNECT_TIMEOUT_MS)
        try {
            GZIPInputStream(context.assets.open(assetPath)).use { input ->
                sftp.put(input, remotePath, ChannelSftp.OVERWRITE)
            }
        } finally {
            sftp.disconnect()
        }
    }

    /**
     * Connects the session; on an auth failure rethrows with an actionable hint. "Auth fail for
     * methods publickey,password" with a correct password almost always means the SERVER blocks
     * password login for this user — typically `PermitRootLogin prohibit-password` (the OpenSSH
     * default for root) or `PasswordAuthentication no`. The client cannot override that, so we tell
     * the user exactly what to change instead of surfacing the raw library error.
     */
    private fun connectOrExplain(session: Session, login: String) {
        try {
            session.connect(CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (msg.contains("Auth fail", ignoreCase = true) || msg.contains("Auth cancel", ignoreCase = true)) {
                throw RuntimeException(
                    "Сервер отклонил вход для '$login'. Пароль дошёл, но SSH не принимает вход по паролю. " +
                        "На VPS в /etc/ssh/sshd_config поставь:  PermitRootLogin yes  и  PasswordAuthentication yes,  " +
                        "затем  systemctl restart ssh  (или sshd). Либо войди под обычным пользователем, которому разрешён вход по паролю."
                )
            }
            throw e
        }
    }

    /**
     * Runs [command] (optionally feeding [stdin] to it), merges stdout+stderr and returns the
     * combined output. Throws if the remote command exits non-zero, with the output as the message.
     */
    private fun exec(session: Session, command: String, stdin: String? = null): String {
        val channel = session.openChannel("exec") as ChannelExec
        val merged = ByteArrayOutputStream()
        channel.setCommand(command)
        channel.setErrStream(merged) // fold stderr into the same buffer
        if (stdin != null) channel.setInputStream(stdin.byteInputStream())
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

/** Wraps [this] in single quotes for safe shell interpolation, escaping any embedded single quote. */
private fun String.shellSingleQuote(): String = "'" + replace("'", "'\\''") + "'"

/**
 * JSch auth helper: supplies [password] for BOTH the "password" method and the keyboard-interactive
 * prompt, and auto-accepts the unknown host key. Without the keyboard-interactive answer, servers that
 * gate password auth through PAM/keyboard-interactive fail with "Auth fail" even for a correct password.
 */
private class SshPasswordUserInfo(private val password: String) : UserInfo, UIKeyboardInteractive {
    // Each auth method is answered EXACTLY ONCE. JSch loops a method as long as the prompt callback
    // keeps returning an answer; always answering would retry until the server's MaxAuthTries trips
    // ("Too many authentication failures"). One password attempt + one keyboard-interactive attempt
    // is enough and bounded.
    private var passwordOffered = false
    private var kbdOffered = false

    override fun getPassphrase(): String? = null
    override fun getPassword(): String = password
    override fun promptPassword(message: String?): Boolean {
        if (passwordOffered) return false
        passwordOffered = true
        return true
    }
    override fun promptPassphrase(message: String?): Boolean = false
    override fun promptYesNo(message: String?): Boolean = true // accept host key / generic confirms
    override fun showMessage(message: String?) {}
    override fun promptKeyboardInteractive(
        destination: String?,
        name: String?,
        instruction: String?,
        prompt: Array<out String>?,
        echo: BooleanArray?
    ): Array<String>? {
        // Info/banner rounds carry no prompts — return an empty answer without consuming the attempt.
        if (prompt.isNullOrEmpty()) return emptyArray()
        if (kbdOffered) return null // already tried once; don't loop into "Too many auth failures"
        kbdOffered = true
        return Array(prompt.size) { password }
    }
}
