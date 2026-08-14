package org.olcbox.app.desktop

import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Windows elevation, asked for **at launch** instead of on the first connect.
 *
 * TUN mode needs administrator rights (wintun + the routing table), and the process cannot gain them
 * while it runs — it has to relaunch itself through UAC. Doing that lazily, from the connect path,
 * meant the app visibly closed and reopened the moment the user pressed Connect for the first time.
 * On the portable build that is at its worst: the window vanishes mid-click and the tunnel only
 * comes up on the second attempt ("перед 1 коннектом портейбл версия перезапускается").
 *
 * Asking before the first window is shown turns the whole thing into a single UAC prompt at launch.
 * The lazy path in WindowsTunController stays as the safety net for someone who dismisses the prompt
 * (or later switches to TUN mode from proxy mode).
 *
 * Nothing is asked in proxy mode: that path never touches wintun, so those users see no UAC at all.
 */
object DesktopElevation {

    /**
     * Marks a process we started ourselves. Distinct from
     * `WindowsTunController.ELEVATED_START_ARGUMENT`, which additionally means "connect once you are
     * up" — a startup elevation must NOT auto-connect. Its only job is to stop a relaunch loop when
     * elevation somehow does not take.
     */
    const val STARTUP_ELEVATION_ARGUMENT = "--olcbox-elevated-at-startup"

    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("win")

    /** Elevation state of this process (`shell32!IsUserAnAdmin`); a process cannot change it. */
    fun isElevated(): Boolean =
        runCatching { Shell32Elevation.INSTANCE.IsUserAnAdmin() }.getOrDefault(false)

    /**
     * True when switching to TUN mode would have to come back through UAC — i.e. the UI should ask
     * the user before restarting the app. False on non-Windows and when we already have rights.
     */
    fun needsAdministratorForTun(): Boolean = isWindows && !isElevated()

    /**
     * Relaunches this process elevated when TUN mode will need it. Returns true when a relaunch was
     * started, in which case the caller must exit **without** showing a window — the elevated copy is
     * the one that continues.
     *
     * False (carry on as we are) whenever: not Windows, already elevated, this IS the relaunched
     * copy, the saved connection mode is proxy, or UAC was dismissed.
     */
    fun relaunchElevatedForStartup(args: Array<String>): Boolean {
        if (!isWindows) return false
        if (STARTUP_ELEVATION_ARGUMENT in args) return false
        if (isElevated()) return false
        if (!savedModeNeedsAdmin()) return false
        return relaunchElevated(args.toList())
    }

    /**
     * Relaunches elevated on demand — the user just switched to TUN mode in a process that has no
     * administrator rights (the portable build's normal state). Returns true when the elevated copy
     * is starting and this one should exit.
     */
    fun relaunchElevatedNow(): Boolean {
        if (!isWindows) return false
        if (isElevated()) return false
        val current = ProcessHandle.current().info().arguments().orElse(emptyArray()).toList()
        return relaunchElevated(current)
    }

    private fun relaunchElevated(currentArguments: List<String>): Boolean {
        val info = ProcessHandle.current().info()
        val command = info.command().orElse(null) ?: return false
        // Only a real launcher can be relaunched. Running from a JDK (gradle :run, an IDE) would
        // otherwise re-exec java.exe without its classpath and start nothing at all.
        if (!command.lowercase(Locale.ROOT).endsWith(".exe") ||
            command.lowercase(Locale.ROOT).endsWith("java.exe") ||
            command.lowercase(Locale.ROOT).endsWith("javaw.exe")
        ) {
            return false
        }
        val arguments = currentArguments.filterNot { it == STARTUP_ELEVATION_ARGUMENT } +
            STARTUP_ELEVATION_ARGUMENT
        return runCatching {
            val script = restartAsAdministratorScript(
                command = command,
                arguments = arguments,
                workingDirectory = System.getProperty("user.dir").orEmpty(),
            )
            runPowerShell(script)
        }.getOrDefault(false)
    }

    /**
     * True when the persisted connection mode is TUN.
     *
     * With nothing saved yet it follows the same first-run default the settings controller picks:
     * the portable build starts in proxy mode (no rights, no UAC on a machine the user did not want
     * to touch), the installed build in TUN.
     */
    private fun savedModeNeedsAdmin(): Boolean {
        val file = DesktopPaths.appDataDir().resolve("settings").resolve("ui.json")
        val mode = runCatching {
            Json.parseToJsonElement(file.toFile().readText())
                .jsonObject["connectionMode"]?.jsonPrimitive?.content
        }.getOrNull()?.trim()
        if (mode.isNullOrBlank()) return !DesktopRuntimeMode.isPortable
        return !"proxy".equals(mode, ignoreCase = true)
    }

    /** Returns true only if UAC was accepted and the elevated process actually started. */
    private fun runPowerShell(script: String): Boolean {
        val process = ProcessBuilder(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-Command", script
        ).redirectErrorStream(true).start()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return false
        }
        return process.exitValue() == 0
    }

    /**
     * Same shape as WindowsTunController's own relaunch script — `Start-Process -Verb RunAs` is what
     * raises the UAC prompt, and it exits non-zero when the prompt is dismissed.
     */
    private fun restartAsAdministratorScript(
        command: String,
        arguments: List<String>,
        workingDirectory: String,
    ): String {
        val quotedArguments = arguments
            .joinToString(separator = " ") { it.windowsCommandLineArgument() }
            .powershellLiteral()
        val workingDirectoryLine = workingDirectory
            .takeIf { it.isNotBlank() }
            ?.let { "  WorkingDirectory = ${it.powershellLiteral()}" }
            .orEmpty()
        return """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}startArgs = @{
              FilePath = ${command.powershellLiteral()}
              Verb = 'RunAs'
              ArgumentList = $quotedArguments
            $workingDirectoryLine
            }
            Start-Process @startArgs | Out-Null
        """.trimIndent()
    }

    private fun String.powershellLiteral(): String = "'${replace("'", "''")}'"

    private fun String.windowsCommandLineArgument(): String {
        if (isEmpty()) return "\"\""
        if (none { it.isWhitespace() || it == '"' }) return this
        val quoted = StringBuilder("\"")
        var pendingBackslashes = 0
        for (char in this) {
            when (char) {
                '\\' -> pendingBackslashes++
                '"' -> {
                    repeat(pendingBackslashes * 2 + 1) { quoted.append('\\') }
                    quoted.append(char)
                    pendingBackslashes = 0
                }
                else -> {
                    repeat(pendingBackslashes) { quoted.append('\\') }
                    pendingBackslashes = 0
                    quoted.append(char)
                }
            }
        }
        repeat(pendingBackslashes * 2) { quoted.append('\\') }
        return quoted.append('"').toString()
    }
}

private interface Shell32Elevation : StdCallLibrary {
    fun IsUserAnAdmin(): Boolean

    companion object {
        val INSTANCE: Shell32Elevation by lazy { Native.load("shell32", Shell32Elevation::class.java) }
    }
}
