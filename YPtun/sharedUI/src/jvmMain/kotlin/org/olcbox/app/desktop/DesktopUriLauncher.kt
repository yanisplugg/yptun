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
 * `explorer.exe <uri>` is the standard way out: explorer runs as the logged-on user at medium
 * integrity, so the handler it invokes lands in the SAME session as the user's other windows.
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
            isWindows -> listOf(
                { exec("explorer.exe", uri) },
                // url.dll's FileProtocolHandler is plain ShellExecute; it still runs elevated, but it
                // is a better last resort than nothing when explorer is unavailable.
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
