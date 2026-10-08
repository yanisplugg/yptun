package org.olcbox.app.vpn.csqtt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedWriter
import java.util.concurrent.TimeUnit

/** The tunnel the server assigned: what the bridge reports when it is up. */
data class CsqttReady(val ip: String, val dns: List<String>, val mtu: Int)

/**
 * Drives the csqtt bridge (csqtt/bridge, `csqtthost`) as a SUBPROCESS: it runs the csqtt Rust client,
 * serves its tunnel as a loopback SOCKS5 and speaks a small line protocol (see the bridge's main.go).
 * A subprocess rather than a library because the client is a separate Rust executable anyway, and a
 * crash in either then costs the engine, not the app. Shared by Android and the desktop; only the path
 * of the executables differs.
 *
 * Closing the bridge's stdin stops everything, so even a hard kill of the app never leaves the Rust
 * client behind.
 */
class CsqttBridge(private val onLog: (String) -> Unit) {

    @Volatile private var process: Process? = null
    @Volatile private var stdin: BufferedWriter? = null
    @Volatile private var ready = CompletableDeferred<CsqttReady>()
    @Volatile private var lastError = ""

    @Volatile var bytesUp: Long = 0L; private set
    @Volatile var bytesDown: Long = 0L; private set
    @Volatile var activeStreams: Int = 0; private set

    fun isRunning(): Boolean = process?.isAlive == true

    /** Why the bridge stopped on its own ("" while fine). */
    fun lastError(): String = lastError

    /**
     * Starts the bridge ([bridgeExecutable]) with [optionsJson] (see `VkTurnConfig.csqttCoreOptionsJson`) and
     * returns at once; the tunnel coming up is awaited with [awaitReady], so the caller can do its other
     * preparation while the VK handshake runs.
     */
    fun launch(bridgeExecutable: String, optionsJson: String) {
        stop()
        lastError = ""
        bytesUp = 0; bytesDown = 0; activeStreams = 0
        val done = CompletableDeferred<CsqttReady>()
        ready = done
        val started = ProcessBuilder(bridgeExecutable)
            .redirectErrorStream(true)
            .start()
        process = started
        stdin = started.outputStream.bufferedWriter(Charsets.UTF_8)
        send("OPTS $optionsJson")
        Thread {
            runCatching {
                started.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { handle(it, done) }
            }
            if (!done.isCompleted) {
                if (lastError.isBlank()) lastError = "процесс csqtt завершился (код ${exitCodeOrNull(started)})"
                done.complete(NOT_READY)
            }
        }.apply {
            isDaemon = true
            name = "csqtt-bridge"
            start()
        }
    }

    /** Waits up to [timeoutMs] for the tunnel. Null on timeout or when the bridge exited ([lastError] says why). */
    suspend fun awaitReady(timeoutMs: Long): CsqttReady? =
        withTimeoutOrNull(timeoutMs) { ready.await() }?.takeIf { it !== NOT_READY }

    suspend fun start(bridgeExecutable: String, optionsJson: String, timeoutMs: Long): CsqttReady? {
        launch(bridgeExecutable, optionsJson)
        return awaitReady(timeoutMs)
    }

    private fun handle(line: String, done: CompletableDeferred<CsqttReady>) {
        when {
            line.startsWith("LOG ") -> onLog("csqtt: ${line.removePrefix("LOG ").trimEnd()}")
            line.startsWith("READY ") -> parseReady(line.removePrefix("READY "))?.let {
                done.complete(it)
            }
            line.startsWith("STATS ") -> line.removePrefix("STATS ").split(' ').let { p ->
                activeStreams = p.getOrNull(0)?.toIntOrNull() ?: activeStreams
                bytesUp = p.getOrNull(1)?.toLongOrNull() ?: bytesUp
                bytesDown = p.getOrNull(2)?.toLongOrNull() ?: bytesDown
            }
            line.startsWith("ERROR ") -> {
                val parts = line.removePrefix("ERROR ").split(' ', limit = 3)
                val fatal = parts.getOrNull(0) == "1"
                val text = listOfNotNull(parts.getOrNull(1), parts.getOrNull(2)).joinToString(": ")
                onLog("csqtt: ошибка${if (fatal) " (критическая)" else ""}: $text")
                if (fatal) lastError = text
            }
            line == "STOPPED" -> Unit
            else -> onLog("csqtt: $line")
        }
    }

    /** Pauses / resumes the worker groups (e.g. while the device sleeps). */
    fun setPaused(paused: Boolean) = send(if (paused) "PAUSE" else "RESUME")

    private fun send(line: String) {
        runCatching {
            stdin?.apply { write(line); newLine(); flush() }
        }
    }

    fun stop() {
        val running = process ?: return
        process = null
        send("STOP")
        runCatching { stdin?.close() }
        stdin = null
        runCatching {
            if (!running.waitFor(4, TimeUnit.SECONDS)) {
                running.destroy()
                if (!running.waitFor(2, TimeUnit.SECONDS)) running.destroyForcibly()
            }
        }.onFailure { onLog("csqtt: остановка: ${it.message}") }
    }

    private fun exitCodeOrNull(p: Process): String = runCatching { p.exitValue().toString() }.getOrDefault("?")

    internal companion object {
        /** Completes [ready] when the process ends before the tunnel came up. */
        private val NOT_READY = CsqttReady("", emptyList(), 0)

        /** `ip|dns,dns|mtu` as the bridge prints it. */
        internal fun parseReady(text: String): CsqttReady? {
            val parts = text.trim().split('|')
            if (parts.size != 3 || parts[0].isBlank()) return null
            return CsqttReady(
                ip = parts[0],
                dns = parts[1].split(',').map { it.trim() }.filter { it.isNotEmpty() },
                mtu = parts[2].toIntOrNull() ?: 1300,
            )
        }
    }
}
