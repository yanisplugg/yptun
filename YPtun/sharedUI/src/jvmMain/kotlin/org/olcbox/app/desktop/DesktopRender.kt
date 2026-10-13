package org.olcbox.app.desktop

import java.awt.Frame
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Window
import java.io.File
import kotlin.math.abs

/**
 * Picks Skiko's render API and recovers from a blank window.
 *
 * On old GPUs (a Radeon HD 7950 reported it) the default hardware path opens a window that stays plain
 * white: nothing throws, so Skiko's own fallback never kicks in, and the only cure was setting
 * `SKIKO_RENDER_API=SOFTWARE_COMPAT` in the shortcut. [watch] notices the blank window, saves that
 * choice to [file] and restarts the app once; [applySaved] then applies it before Skiko loads.
 *
 * The choice lives in `%LOCALAPPDATA%\YPtun\render-api.txt` (one word, a Skiko GraphicsApi such as
 * SOFTWARE_COMPAT or OPENGL; delete the file to go back to automatic). It is deliberately NOT under
 * [DesktopPaths.appDataDir]: that moves with the portable build and is not safe to touch this early.
 * An explicit `SKIKO_RENDER_API` / `-Dskiko.renderApi` always wins and disables the watchdog.
 */
object DesktopRender {
    private const val PROPERTY = "skiko.renderApi"
    private const val ENVIRONMENT = "SKIKO_RENDER_API"
    private const val FALLBACK = "SOFTWARE_COMPAT"

    private val file: File by lazy {
        val base = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home")
        File(base, "YPtun").resolve("render-api.txt")
    }

    private val chosenExplicitly: Boolean
        get() = System.getProperty(PROPERTY) != null || System.getenv(ENVIRONMENT) != null

    /** First thing in main(): Skiko reads the property when its first window is created. */
    fun applySaved() {
        if (chosenExplicitly) return
        runCatching { file.readText().trim().takeIf { it.isNotEmpty() } }.getOrNull()
            ?.let { System.setProperty(PROPERTY, it) }
    }

    /**
     * Watches [window] for the blank-window failure and, if it happens, switches to software
     * rendering and calls [restart]. Looks only during the first minute, only while the window is
     * actually on screen, and needs two blank looks in a row.
     */
    fun watch(window: Window, restart: () -> Unit) {
        // Windows only: a screenshot on Wayland / macOS without permission is plain black, which would look "blank".
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows") || chosenExplicitly) return
        val thread = Thread {
            var blankInARow = 0
            repeat(12) {
                runCatching { Thread.sleep(5_000) }
                val visible = window.isShowing && (window as? Frame)?.state != Frame.ICONIFIED
                if (!visible) return@repeat
                val blank = runCatching { isBlank(window) }.getOrNull() ?: return@Thread
                blankInARow = if (blank) blankInARow + 1 else 0
                if (blankInARow >= 2) {
                    DesktopFileLog.append("render: window stayed blank, switching to $FALLBACK and restarting")
                    runCatching {
                        file.parentFile.mkdirs()
                        file.writeText(FALLBACK)
                    }
                    restart()
                    return@Thread
                }
            }
        }
        thread.isDaemon = true
        thread.name = "render-watchdog"
        thread.start()
    }

    /** True when the middle of the window is one flat colour — the real UI always has text and buttons there. */
    private fun isBlank(window: Window): Boolean {
        val origin = window.locationOnScreen
        val area = Rectangle(origin.x + window.width / 10, origin.y + window.height / 10, window.width * 8 / 10, window.height * 8 / 10)
        if (area.width < 40 || area.height < 40) return false
        val shot = Robot().createScreenCapture(area)
        val first = shot.getRGB(0, 0)
        val step = 16
        for (y in 0 until shot.height step step) for (x in 0 until shot.width step step) {
            if (!close(first, shot.getRGB(x, y))) return false
        }
        return true
    }

    private fun close(a: Int, b: Int): Boolean =
        abs((a shr 16 and 255) - (b shr 16 and 255)) < 6 &&
            abs((a shr 8 and 255) - (b shr 8 and 255)) < 6 &&
            abs((a and 255) - (b and 255)) < 6

    /**
     * Starts a fresh copy of this launcher with the same arguments (it waits for this one to exit, see
     * [DesktopElevation.STARTUP_ELEVATION_ARGUMENT]). Returns false from a JDK/IDE run, where there is no launcher.
     */
    fun relaunch(): Boolean {
        val info = ProcessHandle.current().info()
        val command = info.command().orElse(null)?.takeIf {
            val lower = it.lowercase()
            lower.endsWith(".exe") && !lower.endsWith("java.exe") && !lower.endsWith("javaw.exe")
        } ?: return false
        val args = info.arguments().orElse(emptyArray()).filterNot { it == DesktopElevation.STARTUP_ELEVATION_ARGUMENT }
        return runCatching {
            ProcessBuilder(listOf(command) + args + DesktopElevation.STARTUP_ELEVATION_ARGUMENT)
                .directory(File(System.getProperty("user.dir").orEmpty().ifBlank { "." }))
                .start()
            true
        }.getOrDefault(false)
    }
}
