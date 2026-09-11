package org.olcbox.app.desktop

import java.awt.Desktop
import java.net.URI
import java.util.Locale

/**
 * Opens links and custom-scheme URIs (`tg://…`) from the desktop app.
 *
 * Compose's own `LocalUriHandler` calls `java.awt.Desktop.browse`, which is `ShellExecute` **in this
 * process**. In TUN mode YPtun runs ELEVATED (it has to, to raise wintun and edit the routing table),
 * and a handler launched from an elevated process starts elevated too — at a different integrity
 * level from the copy of Telegram/the browser the user already has open. The new instance then
 * cannot hand the URL to the running one and quietly dies, which is exactly how "«Открыть» does
 * nothing" presents.
 *
 * De-elevating through `explorer.exe <uri>` was the first way out — explorer runs as the logged-on
 * user at medium integrity, so what it invokes lands in the SAME session as the user's other windows.
 * But explorer is a shell, not a launcher: it decides for itself what the string means, and an
 * `http://localhost:<port>/…` URL (the VK captcha page freeturn serves) can be read as a network
 * location and open a **File Explorer window** instead of the browser. So http(s) now resolves the
 * user's chosen browser from the registry ([WindowsShellLaunch.browserCommandLine]) and starts it
 * directly, with explorer demoted to a late fallback; `file:` still goes to explorer, whose job it is.
 *
 * explorer is NOT a way out for custom schemes either. It only resolves file-system paths and http(s);
 * handed `tg://…` it exits silently without ever activating the registered handler — verified on
 * Windows 11 with a throwaway test scheme, whose handler ran under `rundll32 url.dll,…` and never
 * under explorer. Since `ProcessBuilder.start()` succeeds either way, the old code reported success
 * and never fell through, which is exactly why «Открыть» still did nothing after the first fix.
 *
 * Custom schemes therefore go through [WindowsShellLaunch]: resolve the handler command from the
 * registry and start it with the shell's token (de-elevated), falling back to an ordinary
 * ShellExecute — which at least activates the handler — when that is not possible.
 */
object DesktopUriLauncher {

    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("win")

    private val isMac: Boolean =
        System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("mac")

    /**
     * Hands [uri] to the OS. Returns false only when every mechanism failed — note that a successful
     * hand-off does NOT prove a handler exists (see [schemeRegistered] for that).
     */
    fun open(uri: String): Boolean {
        if (uri.isBlank()) return false
        val launchers: List<() -> Unit> = when {
            isWindows && !isWebLink(uri) -> buildList {
                // A custom scheme (tg://, happ://…): explorer.exe would swallow it, so drive the
                // registered handler ourselves — de-elevated when we can, elevated rather than not
                // at all when we can't.
                WindowsShellLaunch.handlerCommandLine(schemeOf(uri), uri)?.let { command ->
                    // Only elevated processes need the token dance; unelevated we already are the
                    // user, and ShellExecute reaches the running Telegram just fine.
                    if (WindowsShellLaunch.isElevated()) {
                        add { require(WindowsShellLaunch.startAsShellUser(command)) { "shell-token launch failed" } }
                    }
                    add { startCommandLine(command) }
                }
                // url.dll's FileProtocolHandler is plain ShellExecute: unlike explorer it DOES
                // activate a custom scheme, it just inherits our integrity level.
                add { exec("rundll32.exe", "url.dll,FileProtocolHandler", uri) }
                add { browse(uri) }
            }
            isWindows && isHttpLink(uri) -> buildList {
                // A web link goes to the BROWSER the user chose, resolved from the registry and
                // started ourselves. explorer.exe used to lead here, and it is not a browser launcher:
                // it decides for itself what a string means, and an `http://localhost:<port>/…` URL
                // (which is exactly what freeturn serves the VK captcha on) can be taken for a network
                // location — so the captcha "opened" as a File Explorer window and the user was stuck.
                WindowsShellLaunch.browserCommandLine(uri)?.let { command ->
                    if (WindowsShellLaunch.isElevated()) {
                        add { require(WindowsShellLaunch.startAsShellUser(command)) { "shell-token launch failed" } }
                    }
                    add { startCommandLine(command) }
                }
                add { exec("rundll32.exe", "url.dll,FileProtocolHandler", uri) }
                add { exec("explorer.exe", uri) }
                add { browse(uri) }
            }
            // `file:` — a folder or a document, which IS explorer's job.
            isWindows -> listOf(
                { exec("explorer.exe", uri) },
                { exec("rundll32.exe", "url.dll,FileProtocolHandler", uri) },
                { browse(uri) },
            )
            isMac -> listOf({ exec("open", uri) }, { browse(uri) })
            else -> listOf({ exec("xdg-open", uri) }, { browse(uri) })
        }
        for (launch in launchers) {
            if (runCatching { launch() }.isSuccess) return true
        }
        return false
    }

    /**
     * Opens [uri] in a **dedicated browser window** — no tabs, no address bar, no other pages —
     * instead of dropping it into whatever the user already has open. Used for the VK captcha, which
     * freeturn serves on `http://localhost:8765/…`: the page is a step of connecting, so it should
     * feel like a dialog of the app, and it must not be lost behind thirty tabs while the relay waits.
     *
     * There is no embedded web engine in this build (a Chromium runtime would add hundreds of MB to a
     * VPN client), so the window is the user's OWN browser driven into application mode: Chromium
     * family (Chrome/Edge/Brave/Opera/Vivaldi/Yandex) via `--app=<url>`, Firefox family via
     * `-new-window`. Both leave the browser's normal profile — and therefore its cookies and its
     * captcha-solving JavaScript — fully intact, which a stripped-down embedded view would not.
     *
     * Falls back to [open] whenever the browser cannot be resolved or refuses to start, so the captcha
     * still reaches the user. Returns true when something was launched.
     */
    fun openBrowserWindow(uri: String): Boolean {
        if (uri.isBlank()) return false
        if (isWindows) {
            val command = WindowsShellLaunch.browserCommandLine(uri)
            val exe = command?.let { splitCommandLine(it).firstOrNull() }?.takeIf { it.isNotBlank() }
            val appArgs = exe?.let { appWindowArgs(it, uri) }
            if (appArgs != null) {
                val quoted = appArgs.joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it }
                // Elevated (TUN mode) we must hand the window to the shell's token, or it lands at a
                // different integrity level than the browser the user already has running and dies.
                if (WindowsShellLaunch.isElevated() &&
                    runCatching { WindowsShellLaunch.startAsShellUser(quoted) }.getOrDefault(false)
                ) return true
                if (runCatching { ProcessBuilder(appArgs).start() }.isSuccess) return true
            }
        }
        return open(uri)
    }

    /**
     * Browser arguments that open [uri] as its own window, or null when [exe] is a browser we have no
     * app-mode flag for (then the caller falls back to an ordinary open).
     */
    private fun appWindowArgs(exe: String, uri: String): List<String>? {
        val name = exe.substringAfterLast('\\').substringAfterLast('/').lowercase(Locale.ROOT)
        val chromium = setOf(
            "chrome.exe", "msedge.exe", "brave.exe", "opera.exe", "opera_gx.exe",
            "vivaldi.exe", "browser.exe", "yandex.exe", "chromium.exe", "thorium.exe",
        )
        val firefox = setOf("firefox.exe", "waterfox.exe", "librewolf.exe", "palemoon.exe")
        return when (name) {
            in chromium -> listOf(exe, "--app=$uri", "--window-size=520,760")
            in firefox -> listOf(exe, "-new-window", uri)
            else -> null
        }
    }

    /** `"tg://socks?…"` → `"tg"`; the scheme is what decides how the URI has to be launched. */
    private fun schemeOf(uri: String): String =
        uri.substringBefore("://", missingDelimiterValue = uri.substringBefore(':'))
            .trim()
            .lowercase(Locale.ROOT)

    private fun isWebLink(uri: String): Boolean = schemeOf(uri) in setOf("http", "https", "file")

    /**
     * Only http(s) belongs to a browser. `file:` stays with explorer/ShellExecute — it is a folder or
     * a document, and handing it to the browser would be worse, not better.
     */
    private fun isHttpLink(uri: String): Boolean = schemeOf(uri) in setOf("http", "https")

    /**
     * Starts a registry handler command line (`"C:\…\Telegram.exe"  -- "tg://…"`) as a process.
     * The string is already quoted the Windows way, and ProcessBuilder quotes each argument again —
     * so it is split into argv here instead of being passed through as one blob.
     */
    private fun startCommandLine(commandLine: String) {
        val argv = splitCommandLine(commandLine)
        require(argv.isNotEmpty()) { "empty handler command line" }
        ProcessBuilder(argv).start()
    }

    /** `CommandLineToArgvW`'s rules, minus the backslash escapes no shell handler ever emits. */
    internal fun splitCommandLine(commandLine: String): List<String> {
        val argv = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var started = false
        for (ch in commandLine) {
            when {
                ch == '"' -> {
                    quoted = !quoted
                    started = true
                }
                ch.isWhitespace() && !quoted -> {
                    if (started) {
                        argv += current.toString()
                        current.setLength(0)
                        started = false
                    }
                }
                else -> {
                    current.append(ch)
                    started = true
                }
            }
        }
        if (started) argv += current.toString()
        return argv
    }

    /**
     * Whether the OS has a handler registered for [scheme] (`"tg"`, without `://`).
     *
     * Used to decide between a deep link and its web fallback: handing an unregistered scheme to
     * explorer pops Windows' "How do you want to open this?" chooser instead of doing anything
     * useful. Non-Windows platforms answer false — the web link is a fine default there.
     */
    fun schemeRegistered(scheme: String): Boolean {
        if (!isWindows || scheme.isBlank()) return false
        return runCatching {
            val process = ProcessBuilder(
                "reg", "query", "HKCR\\$scheme\\shell\\open\\command", "/ve"
            ).redirectErrorStream(true).start()
            process.inputStream.readBytes()
            process.waitFor() == 0
        }.getOrDefault(false)
    }

    private fun exec(vararg command: String) {
        ProcessBuilder(*command).start()
    }

    private fun browse(uri: String) {
        val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
        require(desktop?.isSupported(Desktop.Action.BROWSE) == true) { "BROWSE unsupported" }
        desktop.browse(URI(uri))
    }
}
