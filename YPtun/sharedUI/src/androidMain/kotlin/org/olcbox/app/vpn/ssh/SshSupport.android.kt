package org.olcbox.app.vpn.ssh

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Logger
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayOutputStream
import java.util.Properties
import java.util.zip.GZIPOutputStream

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
    logProgress: Boolean = true,
): Session {
    val user = login.ifBlank { "root" }
    val password = rawPassword.trim()
    if (logProgress && password.length != rawPassword.length) {
        onLog(
            "⚠ Пароль содержал пробелы/перевод строки по краям — убрал их " +
                "(было ${rawPassword.length}, стало ${password.length} симв.). " +
                "Если пароль реально оканчивается пробелом — напиши, отключу очистку."
        )
    }

    // Stream JSch's negotiation log into the on-screen log so auth problems are diagnosable. INFO and
    // above keeps it readable. Silenced for the many repeated upload connections (logProgress=false)
    // so the log isn't drowned in per-connection "ssh:" chatter.
    JSch.setLogger(object : Logger {
        override fun isEnabled(level: Int): Boolean = logProgress && level >= Logger.INFO
        override fun log(level: Int, message: String) {
            if (logProgress) onLog("ssh: $message")
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

    if (logProgress) onLog("Подключение к $host:$port (пользователь '$user', пароль ${password.length} симв.)…")
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
 * Connection parameters reused for every one-shot step of an install.
 */
internal data class SshTarget(
    val host: String,
    val port: Int,
    val login: String,
    val password: String,
)

/**
 * Opens a FRESH connection, runs ONE small exec [command] (no stdin), returns its output, closes.
 *
 * This is the ONLY remote primitive we use, because it is the only one observed to work on the user's
 * VPS: a single small command on a fresh connection (the `uname -m` probe). That server resets the
 * link the moment a SECOND channel is opened on the same connection, so reusing a session for multiple
 * commands — or streaming a binary through one channel's stdin — always died. Slower (a login per
 * step) but reliable. [logProgress] is true only for the first call so auth diagnostics show once.
 */
internal fun sshOneShot(
    target: SshTarget,
    command: String,
    onLog: (String) -> Unit,
    logProgress: Boolean = false,
): String {
    val session = openSshSession(target.host, target.port, target.login, target.password, onLog, logProgress)
    try {
        return sshExec(session, command)
    } finally {
        session.disconnect()
    }
}

/**
 * Lands [data] at [remotePath] using only [sshOneShot] steps: truncate the file, then append the
 * base64-decoded bytes in chunks — each chunk a separate small `printf '…' | base64 -d >> file`
 * command on its OWN fresh connection. base64 is split on 4-char boundaries so the per-chunk decodes
 * concatenate to the exact original. Reports progress through [onLog].
 */
internal fun sshUploadInChunks(
    target: SshTarget,
    data: ByteArray,
    remotePath: String,
    onLog: (String) -> Unit,
) {
    val q = remotePath.shellSingleQuote()
    sshOneShot(target, ": > $q", onLog) // truncate/create
    val b64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
    // The whole command is one argv element to the server's `sh -c`, and Linux caps a single argument
    // at MAX_ARG_STRLEN = 128 KB (131072). Keep the chunk comfortably under that (the command also
    // carries the `printf '%s' '…' | base64 -d >> …` wrapper). Multiple of 4 so each chunk decodes
    // cleanly and the per-chunk decodes concatenate to the exact original.
    val chunk = 100_000
    val total = (b64.length + chunk - 1) / chunk
    var index = 0
    var sent = 0
    while (index < b64.length) {
        val end = minOf(index + chunk, b64.length)
        val part = b64.substring(index, end)
        sshOneShot(target, "printf '%s' '$part' | base64 -d >> $q", onLog)
        index = end
        sent++
        onLog("…загружено $sent/$total (${(end.toLong() * 100 / b64.length)}%)")
    }
}

/**
 * Loads a bundled gzip'd server binary as GZIP bytes ready to upload. The build ships it as
 * "<basePath>.gz", but Android's asset packaging DECOMPRESSES .gz assets and stores the raw file
 * under "<basePath>" (no extension) — so opening "<basePath>.gz" at runtime throws FileNotFound. We
 * therefore try the plain name first, then ".gz". If what we read is the raw (decompressed) binary we
 * re-gzip it in-app so the upload stays small and the server-side `gunzip` still works; if it's
 * already gzip we pass it through. Throws if neither asset exists.
 */
internal fun loadServerBinaryGz(context: Context, basePath: String): ByteArray {
    val raw = context.openAssetBytesOrNull(basePath)
        ?: context.openAssetBytesOrNull("$basePath.gz")
        ?: error("В APK нет бинарника сервера ($basePath[.gz])")
    val isGzip = raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()
    if (isGzip) return raw
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use { it.write(raw) }
    return out.toByteArray()
}

private fun Context.openAssetBytesOrNull(path: String): ByteArray? =
    try {
        assets.open(path).use { it.readBytes() }
    } catch (_: Exception) {
        null
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
