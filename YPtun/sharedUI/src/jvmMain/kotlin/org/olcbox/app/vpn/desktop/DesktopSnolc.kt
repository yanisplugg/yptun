package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.data.model.SnolcConfig
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.vpn.snolc.SnolcFiles
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The snolc client as a subprocess (native/snolc-<os>-<arch>, all modules linked in). Its SOCKS5
 * adapter carries our RFC 1929 patch, so it takes the session login directly and relays UDP — the TUN
 * bridge consumes the port as is. The login sits in the config file under the app data dir.
 */
internal class DesktopSnolc(
    private val log: (String) -> Unit,
) {
    private var process: Process? = null

    fun isRunning(): Boolean = process?.isAlive == true

    suspend fun start(
        config: SnolcConfig,
        listenHost: String,
        listenPort: Int,
        socksUsername: String,
        socksPassword: String,
    ) = withContext(Dispatchers.IO) {
        stop()
        val binary = DesktopNativeAssets.resolveSnolcBinary()
        val dir = DesktopPaths.appDataDir().resolve("snolc")
        Files.createDirectories(dir.resolve("state"))
        Files.createDirectories(dir.resolve("modules"))
        SnolcFiles.client(listenHost, listenPort, config.host, config.port, config.debug, socksUsername, socksPassword).forEach { (name, body) ->
            Files.writeString(dir.resolve(name), body)
        }
        Files.writeString(dir.resolve("pub.hex"), config.publicKey)
        log("Starting snolc (${config.summary()}) on $listenHost:$listenPort")
        val started = ProcessBuilder(binary.toString(), "run", dir.resolve("snolc.toml").toString())
            .directory(dir.toFile()).redirectErrorStream(true)
            .apply { environment()["SNOLC_EXIT_ON_STDIN_EOF"] = "1" }.start()
        process = started
        Thread {
            runCatching {
                started.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) log("snolc: ${line.trimEnd()}")
                }
            }
        }.apply {
            isDaemon = true
            name = "snolc-log"
            start()
        }
    }

    /** Exit code text for a start that never opened its port. */
    fun exitDescription(): String = process?.let { if (it.isAlive) "still starting" else "exited with ${it.exitValue()}" } ?: "not started"

    fun stop() {
        val running = process ?: return
        process = null
        runCatching {
            running.destroy()
            if (!running.waitFor(2_000, TimeUnit.MILLISECONDS)) {
                running.destroyForcibly()
                running.waitFor(2_000, TimeUnit.MILLISECONDS)
            }
        }.onFailure { log("snolc stop failed: ${it.message}") }
    }
}
