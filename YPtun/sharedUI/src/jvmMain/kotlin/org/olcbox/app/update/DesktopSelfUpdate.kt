package org.olcbox.app.update

import org.olcbox.app.desktop.DesktopElevation
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.name

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
        // The app directory is what the swapper writes into. Reading it off the staging directory
        // was only correct while staging lived inside the installation - which is exactly what a
        // root-owned /opt (deb) or Program Files install does not allow.
        start(script, needsElevation = !Files.isWritable(plan.appDir))
    }

    /**
     * Portable build: once this process has exited, put [newExe] in place of [target] (the portable
     * .exe the user started) and run it; the launcher inside unpacks the new version on first start.
     * With no known [target] the downloaded file is simply run from where it lies. The file may be
     * briefly held by antivirus after the move, so the swap retries for about half a minute and,
     * failing that, runs the freshly downloaded copy instead of leaving the user with no app.
     */
    fun scheduleReplacePortable(newExe: Path, target: Path?) {
        check(DesktopPaths.os == DesktopOs.Windows) { "portable self-update is Windows-only" }
        val pid = ProcessHandle.current().pid()
        val fresh = newExe.toAbsolutePath()
        val swap = if (target == null) {
            "start \"\" \"$fresh\""
        } else {
            val dest = target.toAbsolutePath()
            """
            set n=0
            :swap
            move /y "$fresh" "$dest" >nul 2>&1
            if not errorlevel 1 goto moved
            set /a n+=1
            if %n% GEQ 30 goto fallback
            ping -n 2 127.0.0.1 >nul
            goto swap
            :moved
            start "" "$dest"
            goto done
            :fallback
            start "" "$fresh"
            :done
            """.trimIndent()
        }
        val script = scriptDir().resolve("yptun-apply-update.cmd")
        Files.writeString(
            script,
            (windowsWaitForExit(pid) + "\n" + swap + "\ndel \"%~f0\"").replace("\n", "\r\n")
        )
        start(script, needsElevation = target != null && !Files.isWritable(target.toAbsolutePath().parent))
    }

    /**
     * Full-installer fallback on Windows: once this process has exited, run the downloaded installer
     * silently (Inno Setup / MSI), delete it, and start YPtun again. The installer needs administrator
     * rights, so the swapper is started elevated unless the app already is - and a declined UAC prompt
     * throws HERE, while the app is still running, instead of closing it and installing nothing.
     */
    fun scheduleInstaller(installer: Path) {
        check(DesktopPaths.os == DesktopOs.Windows) { "silent install is Windows-only" }
        val pid = ProcessHandle.current().pid()
        val launcher = DesktopAppImage.launcher()
        val file = installer.toAbsolutePath()
        val run = if (installer.name.lowercase().endsWith(".msi")) {
            "msiexec /i \"$file\" /qn /norestart"
        } else {
            "\"$file\" /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /SP-"
        }
        val relaunch = launcher?.let { "start \"\" \"${it.toAbsolutePath()}\"" }.orEmpty()
        val script = scriptDir().resolve("yptun-apply-update.cmd")
        val lines = listOf(
            windowsWaitForExit(pid),
            "start \"\" /wait $run",
            "del /f /q \"$file\" >nul 2>&1",
            relaunch,
            "del \"%~f0\""
        )
        Files.writeString(script, lines.joinToString("\n").replace("\n", "\r\n"))
        start(script, needsElevation = !DesktopElevation.isElevated())
    }

    private fun windowsWaitForExit(pid: Long): String = """
        @echo off
        :wait
        %SystemRoot%\System32\tasklist.exe /FI "PID eq $pid" 2>nul | %SystemRoot%\System32\find.exe "$pid" >nul
        if not errorlevel 1 (
          ping -n 2 127.0.0.1 >nul
          goto wait
        )
    """.trimIndent()

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
            %SystemRoot%\System32\tasklist.exe /FI "PID eq $pid" 2>nul | %SystemRoot%\System32\find.exe "$pid" >nul
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
    private fun start(script: Path, needsElevation: Boolean) {
        val command = when {
            DesktopPaths.os == DesktopOs.Windows && needsElevation -> listOf(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "try { Start-Process -FilePath 'cmd.exe' -ArgumentList '/c','\"${script.toAbsolutePath()}\"' " +
                    "-Verb RunAs -WindowStyle Hidden -ErrorAction Stop } catch { exit 1 }"
            )
            DesktopPaths.os == DesktopOs.Windows -> listOf(
                "cmd.exe", "/c", "start", "/min", "", script.toAbsolutePath().toString()
            )
            needsElevation -> listOf("pkexec", "sh", script.toAbsolutePath().toString())
            else -> listOf("sh", script.toAbsolutePath().toString())
        }
        val process = ProcessBuilder(command)
            .directory(scriptDir().toFile())
            .redirectErrorStream(true)
            .start()
        if (DesktopPaths.os == DesktopOs.Windows && needsElevation) {
            // Start-Process returns once the UAC prompt is answered; a refusal exits non-zero.
            if (!process.waitFor(120, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly()
                runCatching { Files.deleteIfExists(script) }
                error("Administrator rights were not granted - the update was not applied")
            }
        }
    }
}
