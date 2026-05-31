package org.olcbox.app.update

actual fun currentUpdatePlatform(): UpdatePlatform {
    val osName = System.getProperty("os.name", "").lowercase()
    val os = when {
        "win" in osName -> "windows"
        "mac" in osName || "darwin" in osName -> "macos"
        "linux" in osName -> "linux"
        else -> osName.ifBlank { "unknown" }
    }
    return UpdatePlatform(os, normalizedArch())
}

internal fun normalizedArch(): String {
    return when (System.getProperty("os.arch", "").lowercase()) {
        "x86_64", "amd64" -> "amd64"
        "aarch64", "arm64" -> "arm64"
        else -> System.getProperty("os.arch", "unknown").lowercase()
    }
}
