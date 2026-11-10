package org.olcbox.app.vpn.mieru

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Android subprocess adapter for upstream mieru. The app consumes its loopback SOCKS5 listener. */
internal class MieruProcess(private val log: (String) -> Unit) {
    @Volatile
    private var process: Process? = null

    fun isRunning(): Boolean = process?.isAlive == true

    suspend fun start(context: Context, shareLink: String, listenPort: Int) = withContext(Dispatchers.IO) {
        val link = shareLink.trim()
        require(link.startsWith("mieru://", true) || link.startsWith("mierus://", true)) {
            "Mieru: expected a mieru:// or mierus:// link"
        }
        require(listenPort in 1..65535) { "Mieru: invalid local SOCKS port $listenPort" }
        stop()

        val executable = File(context.applicationInfo.nativeLibraryDir, "libmieru.so")
        require(executable.isFile) {
            "Mieru: this APK has no client binary for ${android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}"
        }
        val workDir = File(context.filesDir, "mieru").apply { mkdirs() }
        val configFile = File(workDir, "client-$listenPort.json")
        // A simple link only carries a profile, so seed the required process-local fields before asking
        // upstream's own importer to decode it. A standard link may overwrite them; normalize again below.
        configFile.writeText(
            """{"activeProfile":"default","rpcPort":0,"socks5Port":$listenPort,"loggingLevel":"INFO","socks5ListenLAN":false}"""
        )
        val env = mapOf(
            CONFIG_ENV to configFile.absolutePath,
            "TMPDIR" to context.cacheDir.absolutePath,
            "HOME" to workDir.absolutePath,
        )
        val imported = runCommand(
            command = listOf(executable.absolutePath, "import", "config", link),
            directory = workDir,
            environment = env,
            timeoutSeconds = IMPORT_TIMEOUT_SECONDS,
        )
        check(imported.exitCode == 0) {
            "Mieru link import failed: ${imported.output.takeLast(600).ifBlank { "exit ${imported.exitCode}" }}"
        }

        val decoded = runCatching { Json.parseToJsonElement(configFile.readText()) as JsonObject }
            .getOrElse { error("Mieru produced an invalid client config: ${it.message}") }
        val runtimeConfig = buildJsonObject {
            decoded.forEach { (key, value) ->
                if (key !in RUNTIME_KEYS) put(key, value)
            }
            put("rpcPort", 0) // foreground/mobile mode; lifecycle is owned by this Process object
            put("socks5Port", listenPort)
            put("socks5ListenLAN", false)
            put("loggingLevel", "INFO")
            // The app's internal bridge may offer credentials even though this loopback listener is
            // private. No ingress auth avoids inheriting credentials embedded by a full share link.
            putJsonArray("socks5Authentication") {}
        }
        configFile.writeText(runtimeConfig.toString())

        val ready = CompletableDeferred<Unit>()
        val recent = ArrayDeque<String>()
        val child = ProcessBuilder(executable.absolutePath, "run")
            .directory(workDir)
            .redirectErrorStream(true)
            .apply { environment().putAll(env) }
            .start()
        process = child
        thread(name = "mieru-log", isDaemon = true) {
            runCatching {
                child.inputStream.bufferedReader().forEachLine { raw ->
                    val line = raw.trimEnd()
                    synchronized(recent) {
                        recent.addLast(line)
                        while (recent.size > 30) recent.removeFirst()
                    }
                    log("mieru: $line")
                    if (line.contains("mieru client socks5 server is running", ignoreCase = true)) {
                        ready.complete(Unit)
                    }
                }
            }
            if (!ready.isCompleted) ready.completeExceptionally(
                IllegalStateException("Mieru exited before its SOCKS5 listener was ready")
            )
        }
        val started = withTimeoutOrNull(START_TIMEOUT_MS) { runCatching { ready.await() }.getOrNull() }
        if (started == null || !child.isAlive) {
            val detail = synchronized(recent) { recent.takeLast(8).joinToString(" | ") }
            stop()
            error("Mieru failed to start${detail.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}")
        }
        log("Mieru SOCKS5 ready on 127.0.0.1:$listenPort")
    }

    fun stop() {
        val child = process ?: return
        process = null
        runCatching { child.destroy() }
        if (runCatching { !child.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(true)) {
            runCatching { child.destroyForcibly() }
        }
    }

    private fun runCommand(
        command: List<String>,
        directory: File,
        environment: Map<String, String>,
        timeoutSeconds: Long,
    ): CommandResult {
        val child = ProcessBuilder(command)
            .directory(directory)
            .redirectErrorStream(true)
            .apply { environment().putAll(environment) }
            .start()
        val output = StringBuilder()
        val reader = thread(name = "mieru-command-log", isDaemon = true) {
            child.inputStream.bufferedReader().forEachLine { line ->
                output.appendLine(line)
                log("mieru: $line")
            }
        }
        if (!child.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            child.destroyForcibly()
            reader.join(1_000)
            error("Mieru command timed out")
        }
        reader.join(1_000)
        return CommandResult(child.exitValue(), output.toString().trim())
    }

    private data class CommandResult(val exitCode: Int, val output: String)

    private companion object {
        const val CONFIG_ENV = "MIERU_CONFIG_JSON_FILE"
        const val START_TIMEOUT_MS = 20_000L
        const val IMPORT_TIMEOUT_SECONDS = 20L
        val RUNTIME_KEYS = setOf(
            "rpcPort", "socks5Port", "socks5ListenLAN", "loggingLevel",
            "httpProxyPort", "httpProxyListenLAN", "socks5Authentication",
        )
    }
}
