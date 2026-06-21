package org.olcbox.app.vpn.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Logger
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayOutputStream
import java.util.Properties

internal const val SSH_CONNECT_TIMEOUT_MS = 25_000

/**
 * Opens an authenticated SSH [Session] with password auth, mirroring what OpenSSH/paramiko do so a
 * password that logs in fine from a terminal also logs in here. This is shared by the WDTT and DNSTT
 * VPS auto-installers (which were each carrying their own slightly-different copy).
 *
 * What makes it robust:
 *  - The password is sanitised: surrounding whitespace and CR/LF are stripped. A password field shows
 *    only dots, so a value pasted from a manager (or with a stray trailing newline/space) looks
 *    identical to the correct one — this is the usual reason "the exact same password" works in a
 *    terminal but fails in the app. If sanitising changes the length we say so in the log.
 *  - BOTH interactive methods the server may offer are attempted — plain "password" AND
 *    keyboard-interactive (password-over-PAM) — with the same password answering each. publickey is
 *    left out (we have no key) but the host key is accepted automatically.
 *  - JSch's own protocol negotiation is streamed into [onLog], so when auth still fails the real
 *    cause (which methods the server offered, where it broke) is visible instead of a bare code.
 *
 * On an authentication failure it throws a [RuntimeException] with an actionable, localized hint.
 */
internal fun openSshSession(
    host: String,
    port: Int,
    login: String,
    rawPassword: String,
    onLog: (String) -> Unit,
): Session {
    val user = login.ifBlank { "root" }
    val password = rawPassword.trim()
    if (password.length != rawPassword.length) {
        onLog(
            "⚠ Пароль содержал пробелы/перевод строки по краям — убрал их " +
                "(было ${rawPassword.length}, стало ${password.length} симв.). " +
                "Если пароль реально оканчивается пробелом — напиши, отключу очистку."
        )
    }

    // Stream JSch's negotiation log into the on-screen log so auth problems are diagnosable. INFO and
    // above keeps it readable (skips the very verbose per-packet DEBUG spam).
    JSch.setLogger(object : Logger {
        override fun isEnabled(level: Int): Boolean = level >= Logger.INFO
        override fun log(level: Int, message: String) {
            onLog("ssh: $message")
        }
    })

    val jsch = JSch()
    val session = jsch.getSession(user, host, port)
    session.setPassword(password)
    session.setConfig(Properties().apply {
        // VPSes rarely have a known host key on first contact; accept it (password auth still protects
        // the channel).
        put("StrictHostKeyChecking", "no")
        // Try both the plain "password" method and keyboard-interactive (some servers gate password
        // auth through PAM/keyboard-interactive only). Skip publickey — we have no key to offer.
        put("PreferredAuthentications", "password,keyboard-interactive")
    })
    session.userInfo = SshPasswordUserInfo(password)

    onLog("Подключение к $host:$port (пользователь '$user', пароль ${password.length} симв.)…")
    try {
        session.connect(SSH_CONNECT_TIMEOUT_MS)
    } catch (e: Exception) {
        val msg = e.message.orEmpty()
        if (msg.contains("Auth fail", ignoreCase = true) || msg.contains("Auth cancel", ignoreCase = true)) {
            // JSch names the methods the server actually offered (e.g. "...for methods
            // 'publickey,password'") — surface that verbatim plus the two real causes, in order of
            // likelihood: a hidden character in the password, then a server that blocks password login.
            throw RuntimeException(
                "Не удалось войти под '$user' ($msg). Проверь по порядку: " +
                    "1) в пароле нет лишних пробелов/символов (поле скрывает их за точками); " +
                    "2) на VPS в /etc/ssh/sshd_config заданы PermitRootLogin yes, " +
                    "PasswordAuthentication yes и KbdInteractiveAuthentication yes, затем " +
                    "systemctl restart ssh. Подробности SSH-согласования — в логе выше (строки 'ssh:')."
            )
        }
        throw e
    }
    return session
}

/**
 * Runs [command] on a fresh exec channel, merges stdout+stderr and returns the combined text. Throws
 * if the remote command exits non-zero (with the output as the message). Use this for everything —
 * including a whole multi-line install script as the command — so nothing relies on a separate
 * uploaded file or an SFTP subsystem.
 */
internal fun sshExec(session: Session, command: String): String {
    val channel = session.openChannel("exec") as ChannelExec
    val merged = ByteArrayOutputStream()
    channel.setCommand(command)
    channel.setErrStream(merged) // fold stderr into the same buffer
    val out = channel.inputStream
    channel.connect(SSH_CONNECT_TIMEOUT_MS)
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

/**
 * Lands [data] at [remotePath] on the server WITHOUT SFTP and WITHOUT streaming a large payload
 * through a channel's stdin (that pump dropped the connection mid-transfer on the user's VPS — both
 * JSch's setInputStream and our own EOF pump). Instead the bytes are base64-encoded and appended in
 * small slices, each via its own plain `printf '…' | base64 -d >> file` command — i.e. ordinary
 * console commands, nothing exotic. base64 is split on 4-char boundaries so the concatenation of the
 * per-slice decodes equals the original. Progress is reported through [onLog].
 */
internal fun sshUploadFile(
    session: Session,
    data: ByteArray,
    remotePath: String,
    onLog: (String) -> Unit,
) {
    val q = remotePath.shellSingleQuote()
    // Fail early with a clear message if the server lacks base64 (almost none do).
    sshExec(session, "command -v base64 >/dev/null 2>&1 || { echo 'на VPS нет утилиты base64' >&2; exit 1; }")
    sshExec(session, ": > $q") // truncate/create the target
    val b64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
    val chunk = 60_000 // multiple of 4; ~60 KB per command — safe under ARG_MAX even on busybox
    val total = (b64.length + chunk - 1) / chunk
    var index = 0
    var sent = 0
    while (index < b64.length) {
        val end = minOf(index + chunk, b64.length)
        val part = b64.substring(index, end)
        sshExec(session, "printf '%s' '$part' | base64 -d >> $q")
        index = end
        sent++
        if (sent == 1 || sent % 10 == 0 || end == b64.length) onLog("…загружено $sent/$total частей")
    }
}

/** Wraps [this] in single quotes for safe shell interpolation, escaping any embedded single quote. */
internal fun String.shellSingleQuote(): String = "'" + replace("'", "'\\''") + "'"

/**
 * JSch auth helper: supplies [password] for BOTH the "password" method and the keyboard-interactive
 * prompt, and auto-accepts the unknown host key. Without the keyboard-interactive answer, servers that
 * gate password auth through PAM/keyboard-interactive fail with "Auth fail" even for a correct password.
 */
private class SshPasswordUserInfo(private val password: String) : UserInfo, UIKeyboardInteractive {
    // The "password" method is offered once (JSch also uses Session.setPassword for the first attempt).
    private var passwordOffered = false
    // Count of ANSWERED keyboard-interactive prompt rounds. Many VPSes accept the password ONLY via
    // keyboard-interactive (password-over-PAM; the plain "password" method isn't offered), and some PAM
    // stacks span SEVERAL rounds even for a correct password. Answering every round (bounded below the
    // default MaxAuthTries so a WRONG password still fails cleanly) covers those.
    private var kbdRounds = 0

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
        // Info/banner rounds carry no prompts — acknowledge without consuming a round.
        if (prompt.isNullOrEmpty()) return emptyArray()
        if (kbdRounds >= MAX_KBD_ROUNDS) return null
        kbdRounds++
        // Answer every prompt with the password (OpenSSH/paramiko behaviour for password-over-PAM).
        return Array(prompt.size) { password }
    }

    private companion object {
        const val MAX_KBD_ROUNDS = 4
    }
}
