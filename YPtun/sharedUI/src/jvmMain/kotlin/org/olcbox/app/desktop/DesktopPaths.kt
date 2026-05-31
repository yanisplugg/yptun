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

    fun appDataDir(): Path {
        val home = Path(System.getProperty("user.home"))
        val dir = when (os) {
            DesktopOs.MacOS -> home.resolve("Library").resolve("Application Support").resolve("Olcbox")
            DesktopOs.Windows -> {
                val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                (appData?.let { Path(it) } ?: home.resolve("AppData").resolve("Roaming")).resolve("Olcbox")
            }
            DesktopOs.Linux,
            DesktopOs.Other -> home.resolve(".olcbox")
        }
        Files.createDirectories(dir)
        return dir
    }
}
