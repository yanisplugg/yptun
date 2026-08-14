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
 * `explorer.exe <uri>` is the standard way out for **web** links: explorer runs as the logged-on user
 * at medium integrity, so the browser it invokes lands in the SAME session as the user's other
 * windows.
 *
 * It is NOT a way out for custom schemes. explorer only resolves file-system paths and http(s) URLs;
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

    /** `"tg://socks?…"` → `"tg"`; the scheme is what decides how the URI has to be launched. */
    private fun schemeOf(uri: String): String =
        uri.substringBefore("://", missingDelimiterValue = uri.substringBefore(':'))
            .trim()
            .lowercase(Locale.ROOT)

    private fun isWebLink(uri: String): Boolean = schemeOf(uri) in setOf("http", "https", "file")

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
