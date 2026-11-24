package org.olcbox.app.vpn.turnable

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Android-only Turnable client subprocess. It exposes the configured route as local TCP/UDP. */
internal class TurnableProcess(
    private val log: (String) -> Unit,
    private val onCaptchaShow: (String) -> Unit = {},
    private val onCaptchaHide: () -> Unit = {},
) {
    @Volatile
    private var process: Process? = null

    @Volatile
    private var captchaActive = false

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
                    if (line.contains("VK_CAPTCHA_URL|")) {
                        val url = line.substringAfter("VK_CAPTCHA_URL|").trim()
                        if (url.isNotBlank()) {
                            captchaActive = true
                            onCaptchaShow(url)
                        }
                    }
                    if (line.contains("VK_CAPTCHA_DONE")) {
                        captchaActive = false
                        onCaptchaHide()
                    }
                    if (line.contains("turnable client started", ignoreCase = true)) ready.complete(Unit)
                }
            }
            if (!ready.isCompleted) ready.completeExceptionally(
                IllegalStateException("Turnable exited before its local listener was ready")
            )
        }

        var deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        var captchaStartTime = 0L
        while (System.currentTimeMillis() < deadline && !ready.isCompleted && child.isAlive) {
            if (captchaActive) {
                if (captchaStartTime == 0L) captchaStartTime = System.currentTimeMillis()
                val maxCaptchaDeadline = captchaStartTime + MANUAL_CAPTCHA_TIMEOUT_MS
                val targetDeadline = System.currentTimeMillis() + START_TIMEOUT_MS
                deadline = maxOf(deadline, minOf(targetDeadline, maxCaptchaDeadline))
            }
            delay(100L)
        }

        val started = if (ready.isCompleted) runCatching { ready.await() }.getOrNull() else null
        if (started == null || !child.isAlive) {
            val detail = synchronized(recent) { recent.takeLast(5).joinToString(" | ") }
            stop()
            error("Turnable failed to start${detail.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")
        }
        log("Turnable local route ready on 127.0.0.1:$listenPort")
    }

    fun stop() {
        if (captchaActive) {
            captchaActive = false
            onCaptchaHide()
        }
        val child = process ?: return
        process = null
        runCatching { child.destroy() }
        if (runCatching { !child.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(true)) {
            runCatching { child.destroyForcibly() }
        }
    }

    private companion object {
        const val START_TIMEOUT_MS = 20_000L
        const val MANUAL_CAPTCHA_TIMEOUT_MS = 300_000L
    }
}
