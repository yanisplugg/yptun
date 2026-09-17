package org.olcbox.app.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Appends a line to `%APPDATA%\YPtun\yptun.log` — the file that is the first thing anyone reads when
 * something "doesn't work on the PC". The in-app journal is memory-only, so anything written before
 * a restart (or by a component that runs outside the VPN lifecycle, like the updater) would
 * otherwise be gone by the time someone looks.
 *
 * Extracted from DesktopVpnManager, which owns the journal but is not reachable from everywhere.
 */
object DesktopFileLog {

    /** Size past which the file is dropped instead of appended to (matches singbox.log's cap). */
    private const val MAX_BYTES = 32L * 1024 * 1024

    private val TIMESTAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    val path: Path? by lazy {
        runCatching {
            val path = DesktopPaths.appDataDir().resolve("yptun.log")
            Files.createDirectories(path.parent)
            path
        }.getOrNull()
    }

    fun append(message: String) {
        runCatching {
            val file = (path ?: return).toFile()
            if (file.length() > MAX_BYTES) file.delete()
            file.appendText("${LocalDateTime.now().format(TIMESTAMP)} $message${System.lineSeparator()}")
        }
    }
}

/** Shorthand for [DesktopFileLog.append]. */
fun appendToYptunLog(message: String) = DesktopFileLog.append(message)
