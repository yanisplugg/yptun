package org.olcbox.app.update

import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Swaps a freshly-patched application jar into the installed app image.
 *
 * The running JVM holds its own jar open, so the file cannot be replaced in place. Instead a tiny
 * script is started that waits for THIS process to exit, moves the new jar over the old one and
 * relaunches YPtun. If the move fails (no permission, disk full), the installation is untouched and
 * the app simply starts on the old version again — the update is retried next time.
 */
internal object DesktopSelfUpdate {

    /**
     * Starts the waiting swapper for [stagedJar] → [targetJar]. Safe to call before the app begins
     * its own shutdown: the script polls for the process to disappear first.
     */
    fun scheduleSwap(stagedJar: Path, targetJar: Path) {
        val pid = ProcessHandle.current().pid()
        val launcher = DesktopAppImage.launcher()
        val script = when (DesktopPaths.os) {
            DesktopOs.Windows -> writeWindowsScript(pid, stagedJar, targetJar, launcher)
            else -> writeUnixScript(pid, stagedJar, targetJar, launcher)
        }
        start(script, targetJar)
    }

    private fun scriptDir(): Path =
        DesktopPaths.appDataDir().resolve("updates").also { Files.createDirectories(it) }

    private fun writeWindowsScript(
        pid: Long,
        stagedJar: Path,
        targetJar: Path,
        launcher: Path?
    ): Path {
        val script = scriptDir().resolve("yptun-apply-update.cmd")
        val relaunch = launcher?.let { "start \"\" \"${it.toAbsolutePath()}\"" }.orEmpty()
        Files.writeString(
            script,
            """
            @echo off
            :wait
            tasklist /FI "PID eq $pid" 2>nul | find "$pid" >nul
            if not errorlevel 1 (
              ping -n 2 127.0.0.1 >nul
              goto wait
            )
            move /y "${stagedJar.toAbsolutePath()}" "${targetJar.toAbsolutePath()}" >nul
            $relaunch
            del "%~f0"
            """.trimIndent().replace("\n", "\r\n")
        )
        return script
    }

    private fun writeUnixScript(
        pid: Long,
        stagedJar: Path,
        targetJar: Path,
        launcher: Path?
    ): Path {
        val script = scriptDir().resolve("yptun-apply-update.sh")
        val relaunch = launcher?.let { "\"${it.toAbsolutePath()}\" >/dev/null 2>&1 &" }.orEmpty()
        Files.writeString(
            script,
            """
            #!/bin/sh
            while kill -0 $pid 2>/dev/null; do sleep 0.5; done
            mv -f "${stagedJar.toAbsolutePath()}" "${targetJar.toAbsolutePath()}" || exit 1
            $relaunch
            rm -f "$0"
            """.trimIndent()
        )
        runCatching {
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"))
        }
        return script
    }

    /**
     * Runs the swapper, elevating only when the install directory is not writable by this process —
     * an app already running as administrator (TUN mode restarts itself that way) never prompts.
     */
    private fun start(script: Path, targetJar: Path) {
        val needsElevation = !Files.isWritable(targetJar)
        val command = when {
            DesktopPaths.os == DesktopOs.Windows && needsElevation -> listOf(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "Start-Process -FilePath 'cmd.exe' -ArgumentList '/c','\"${script.toAbsolutePath()}\"' -Verb RunAs -WindowStyle Hidden"
            )
            DesktopPaths.os == DesktopOs.Windows -> listOf(
                "cmd.exe", "/c", "start", "/min", "", script.toAbsolutePath().toString()
            )
            needsElevation -> listOf("pkexec", "sh", script.toAbsolutePath().toString())
            else -> listOf("sh", script.toAbsolutePath().toString())
        }
        ProcessBuilder(command)
            .directory(scriptDir().toFile())
            .redirectErrorStream(true)
            .start()
    }
}
