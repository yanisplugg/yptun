package org.olcbox.app.vpn.ssh

/**
 * Desktop source of the bundled VPS server binaries: the same files the APK ships in assets/ are
 * copied into the desktop app's resources at build time (see desktopApp/build.gradle.kts), so the
 * installers find them under the identical relative paths ("freeturn/freeturn-server-linux-amd64").
 */
val classpathServerBinaries = ServerBinarySource { path ->
    val loader = object {}.javaClass.classLoader ?: return@ServerBinarySource null
    loader.getResourceAsStream(path)?.use { it.readBytes() }
}
