package org.olcbox.app.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

internal enum class DesktopOs {
    MacOS,
    Windows,
    Linux,
    Other
}

internal object DesktopPaths {
    val os: DesktopOs
        get() {
            val name = System.getProperty("os.name").lowercase()
            return when {
                name.contains("mac") || name.contains("darwin") -> DesktopOs.MacOS
                name.contains("windows") -> DesktopOs.Windows
                name.contains("linux") -> DesktopOs.Linux
                else -> DesktopOs.Other
            }
        }

    val arch: String
        get() = System.getProperty("os.arch").lowercase()

    /**
     * Where the app keeps its data. Normally [standardAppDataDir]; the portable build moves it to a private
     * copy of the encrypted bundle next to its .exe (see [PortableData]).
     */
    fun appDataDir(): Path = PortableData.resolve(::standardAppDataDir)

    private fun standardAppDataDir(): Path {
        val home = Path(System.getProperty("user.home"))
        val base = when (os) {
            DesktopOs.MacOS -> home.resolve("Library").resolve("Application Support")
            DesktopOs.Windows -> {
                val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                appData?.let { Path(it) } ?: home.resolve("AppData").resolve("Roaming")
            }
            DesktopOs.Linux,
            DesktopOs.Other -> home
        }
        val hidden = os == DesktopOs.Linux || os == DesktopOs.Other
        val dir = base.resolve(if (hidden) ".yptun" else "YPtun")
        // One-time migration from the pre-rebrand "Olcbox" data dir (locations, settings, identity).
        if (!Files.exists(dir)) {
            val legacy = base.resolve(if (hidden) ".olcbox" else "Olcbox")
            if (Files.exists(legacy)) {
                runCatching { Files.move(legacy, dir) }
            }
        }
        Files.createDirectories(dir)
        return dir
    }
}
