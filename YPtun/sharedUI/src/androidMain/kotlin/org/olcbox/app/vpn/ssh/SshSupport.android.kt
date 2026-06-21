package org.olcbox.app.vpn.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Logger
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
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
