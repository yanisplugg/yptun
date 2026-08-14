package org.olcbox.app.update

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * The layout of an installed YPtun desktop build (a jpackage app image):
 *
 * - `<install>/YPtun.exe` — launcher
 * - `<install>/app/` — the jars: the application itself, the only part that changes release to release
 * - `<install>/runtime/` — the bundled JRE (~120 MB, essentially never changes)
 *
 * Delta updates patch exactly one file: the fat application jar, which carries our code AND the
 * native cores. Everything else is either identical between releases or, if it did change, reason
 * enough to fall back to the full installer — the release-time generator makes that call.
 */
internal object DesktopAppImage {

    /**
     * The jar the running app was launched from, or null when the app isn't running from an
     * installed app image (a Gradle `run`, an IDE, a `-cp` of loose classes).
     */
    fun runningJar(): Path? = runCatching {
        val source = DesktopAppImage::class.java.protectionDomain?.codeSource?.location ?: return null
        val path = Path.of(source.toURI())
        path.takeIf { it.exists() && !it.isDirectory() && it.extension.equals("jar", ignoreCase = true) }
    }.getOrNull()

    /**
     * The `app/` directory of the installed image — every jar plus `YPtun.cfg`, i.e. everything a
     * delta update touches. Null outside an installation (a Gradle `run`, an IDE, loose classes).
     *
     * The running jar sits in it in a packaged build; the check that it is an `app/` directory next
     * to a `runtime/` one is what tells a real installation apart from a development run.
     */
    fun appDir(): Path? {
        val jar = runningJar() ?: return null
        val appDir = jar.parent ?: return null
        if (!appDir.name.equals("app", ignoreCase = true)) return null
        val installDir = appDir.parent ?: return null
        if (!installDir.resolve("runtime").isDirectory()) return null
        return appDir
    }

    /** The installed image's root (the directory holding the launcher), or null outside one. */
    fun installDir(): Path? = appDir()?.parent

    /** The launcher executable to restart after an update, or null when it can't be found. */
    fun launcher(): Path? {
        val root = installDir() ?: return null
        val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        val candidates = if (windows) {
            listOf(root.resolve("YPtun.exe"))
        } else {
            listOf(root.resolve("bin").resolve("YPtun"), root.resolve("YPtun"))
        }
        return candidates.firstOrNull { it.exists() }
    }

    /** Lowercase hex SHA-256 of [path]. */
    fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
