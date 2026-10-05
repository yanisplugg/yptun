package org.olcbox.app.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.Path

/**
 * Tells an installed YPtun apart from the portable one.
 *
 * It matters because of administrator rights: the installed build is expected to raise a real TUN
 * (that is what it was installed for), while the portable is the "run it from a stick, don't touch
 * my machine" build — asking for UAC before its first window is exactly the behaviour the user did
 * not want. So the portable starts in **proxy mode**, which needs no rights at all, and only asks
 * for administrator once the user picks tunnel mode by hand.
 *
 * Detection, in order:
 *  1. a `.portable` marker file next to the launcher — written by the portable packager, so it is
 *     an explicit statement rather than a guess;
 *  2. otherwise: the launcher does NOT live under Program Files / `%LOCALAPPDATA%\Programs`, i.e.
 *     nothing installed it.
 */
object DesktopRuntimeMode {

    const val MARKER_FILE_NAME = ".portable"

    /** Set by the portable launcher to the path of the .exe the user started (see portable-launcher). */
    const val PORTABLE_EXE_ENV = "YPTUN_PORTABLE_EXE"

    /**
     * The portable .exe the user double-clicked - the file a self-update has to replace, since the
     * app itself runs from the copy the launcher unpacked. Null when unknown (a launcher older than
     * this variable, or the portable was unpacked by hand).
     */
    fun portableExecutable(): Path? = System.getenv(PORTABLE_EXE_ENV)
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Path(it) }.getOrNull() }
        ?.takeIf { Files.isRegularFile(it) }

    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("win")

    /** Directory the running launcher (`YPtun.exe`) sits in, when there is a real launcher. */
    fun launcherDir(): Path? {
        val command = ProcessHandle.current().info().command().orElse(null) ?: return null
        val lower = command.lowercase(Locale.ROOT)
        // A JDK launcher (gradle :run, an IDE) says nothing about how the app was distributed.
        if (!lower.endsWith(".exe") || lower.endsWith("java.exe") || lower.endsWith("javaw.exe")) {
            return null
        }
        return runCatching { Path(command).parent }.getOrNull()
    }

    val isPortable: Boolean by lazy {
        if (!isWindows) return@lazy false
        val dir = launcherDir() ?: return@lazy false
        if (Files.exists(dir.resolve(MARKER_FILE_NAME))) return@lazy true
        // The Inno installer drops unins000.exe next to the launcher wherever the user pointed it —
        // an install in D:\Apps used to look "not installed" and got the portable update.
        if (hasUninstaller(dir)) return@lazy false
        !isUnderInstallRoot(dir)
    }

    private fun hasUninstaller(dir: Path): Boolean = runCatching {
        Files.newDirectoryStream(dir, "unins*.exe").use { it.iterator().hasNext() }
    }.getOrDefault(false)

    private fun isUnderInstallRoot(dir: Path): Boolean {
        val path = dir.toAbsolutePath().toString().lowercase(Locale.ROOT)
        return installRoots().any { path.startsWith(it) }
    }

    private fun installRoots(): List<String> = listOfNotNull(
        System.getenv("ProgramFiles"),
        System.getenv("ProgramFiles(x86)"),
        System.getenv("ProgramW6432"),
        System.getenv("LOCALAPPDATA")?.let { "$it\\Programs" },
    ).map { it.lowercase(Locale.ROOT) }
}
