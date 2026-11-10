package org.olcbox.app.vpn.turnable

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Android-only Turnable client subprocess. It exposes the configured route as local TCP/UDP. */
internal class TurnableProcess(private val log: (String) -> Unit) {
    @Volatile
    private var process: Process? = null

    fun isRunning(): Boolean = process?.isAlive == true

    suspend fun start(context: Context, config: String, listenPort: Int) = withContext(Dispatchers.IO) {
        require(config.isNotBlank()) { "Turnable: client config is empty" }
        require(listenPort in 1..65535) { "Turnable: invalid local port $listenPort" }
        stop()

        val executable = File(context.applicationInfo.nativeLibraryDir, "libturnable.so")
        require(executable.isFile) {
            "Turnable: this APK has no client binary for ${android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}"
        }
        val workDir = File(context.filesDir, "turnable").apply { mkdirs() }
        val trimmed = config.trim()
        val args = mutableListOf(
            executable.absolutePath,
            "client",
            "--no-interactive",
            "--listen", "127.0.0.1:$listenPort",
        )
        if (trimmed.startsWith("{")) {
            val configFile = File(workDir, "client-$listenPort.json")
            configFile.writeText(trimmed)
            args += listOf("--config", configFile.absolutePath)
        } else {
            require(trimmed.startsWith("turnable://", ignoreCase = true)) {
                "Turnable: expected turnable:// URL or JSON config"
            }
            args += trimmed
        }

        val ready = CompletableDeferred<Unit>()
        val recent = ArrayDeque<String>()
        val child = ProcessBuilder(args)
            .directory(workDir)
            .redirectErrorStream(true)
            .apply { environment()["TMPDIR"] = context.cacheDir.absolutePath }
            .start()
        process = child
        thread(name = "turnable-log", isDaemon = true) {
            runCatching {
                child.inputStream.bufferedReader().forEachLine { raw ->
                    val line = raw.trimEnd()
                    synchronized(recent) {
                        recent.addLast(line)
                        while (recent.size > 20) recent.removeFirst()
                    }
                    log("turnable: $line")
                    if (line.contains("turnable client started", ignoreCase = true)) ready.complete(Unit)
                }
            }
            if (!ready.isCompleted) ready.completeExceptionally(
                IllegalStateException("Turnable exited before its local listener was ready")
            )
        }

        val started = withTimeoutOrNull(START_TIMEOUT_MS) {
            runCatching { ready.await() }.getOrNull()
        }
        if (started == null || !child.isAlive) {
            val detail = synchronized(recent) { recent.takeLast(5).joinToString(" | ") }
            stop()
            error("Turnable failed to start${detail.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")
        }
        log("Turnable local route ready on 127.0.0.1:$listenPort")
    }

    fun stop() {
        val child = process ?: return
        process = null
        runCatching { child.destroy() }
        if (runCatching { !child.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(true)) {
            runCatching { child.destroyForcibly() }
        }
    }

    private companion object {
        const val START_TIMEOUT_MS = 20_000L
    }
}
