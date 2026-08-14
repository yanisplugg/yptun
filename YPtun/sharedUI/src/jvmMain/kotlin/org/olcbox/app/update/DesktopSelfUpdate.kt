package org.olcbox.app.update

import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Commits a staged delta update into the installed app image.
 *
 * The running JVM holds every jar on its classpath open, so nothing can be replaced in place.
 * Instead a small script is started that waits for THIS process to exit, moves the rebuilt files
 * over the old ones, removes what the new build dropped, and relaunches YPtun.
 *
 * Ordering is the safety net: [DesktopDeltaPatch] hands over moves with `YPtun.cfg` — the file that
 * names the classpath — last, and deletions after that. A move that fails midway therefore leaves an
 * installation that still boots the old build, and the update is simply retried next time.
 */
internal object DesktopSelfUpdate {

    /**
     * Starts the waiting swapper for [plan]. Safe to call before the app begins its own shutdown:
     * the script polls for the process to disappear first.
     */
    fun scheduleSwap(plan: DesktopDeltaPatch.Plan) {
        val pid = ProcessHandle.current().pid()
        val launcher = DesktopAppImage.launcher()
        val script = when (DesktopPaths.os) {
            DesktopOs.Windows -> writeWindowsScript(pid, plan, launcher)
            else -> writeUnixScript(pid, plan, launcher)
        }
        start(script, plan)
    }

    private fun scriptDir(): Path =
        DesktopPaths.appDataDir().resolve("updates").also { Files.createDirectories(it) }

    private fun writeWindowsScript(
        pid: Long,
        plan: DesktopDeltaPatch.Plan,
        launcher: Path?
    ): Path {
        val script = scriptDir().resolve("yptun-apply-update.cmd")
        val moves = plan.moves.joinToString("\r\n") { (from, to) ->
            "move /y \"${from.toAbsolutePath()}\" \"${to.toAbsolutePath()}\" >nul"
        }
        val deletes = plan.deletions.joinToString("\r\n") { path ->
            "del /f /q \"${path.toAbsolutePath()}\" >nul 2>&1"
        }
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
            $moves
            $deletes
            rmdir /s /q "${plan.stagingDir.toAbsolutePath()}" >nul 2>&1
            $relaunch
            del "%~f0"
            """.trimIndent().replace("\n", "\r\n")
        )
        return script
    }

    private fun writeUnixScript(
        pid: Long,
        plan: DesktopDeltaPatch.Plan,
        launcher: Path?
    ): Path {
        val script = scriptDir().resolve("yptun-apply-update.sh")
        val moves = plan.moves.joinToString("\n") { (from, to) ->
            "mv -f \"${from.toAbsolutePath()}\" \"${to.toAbsolutePath()}\" || exit 1"
        }
        val deletes = plan.deletions.joinToString("\n") { path ->
            "rm -f \"${path.toAbsolutePath()}\""
        }
        val relaunch = launcher?.let { "\"${it.toAbsolutePath()}\" >/dev/null 2>&1 &" }.orEmpty()
        Files.writeString(
            script,
            """
            #!/bin/sh
            while kill -0 $pid 2>/dev/null; do sleep 0.5; done
            $moves
            $deletes
            rm -rf "${plan.stagingDir.toAbsolutePath()}"
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
     * Runs the swapper, elevating only when the app directory is not writable by this process — an
     * app already running as administrator (TUN mode restarts itself that way) never prompts.
     */
    private fun start(script: Path, plan: DesktopDeltaPatch.Plan) {
        val needsElevation = !Files.isWritable(plan.stagingDir.parent ?: plan.stagingDir)
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
