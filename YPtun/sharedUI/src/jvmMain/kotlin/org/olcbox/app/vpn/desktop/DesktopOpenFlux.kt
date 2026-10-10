package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.data.model.OpenFluxConfig
import java.util.concurrent.TimeUnit

/**
 * The OpenFlux client as a subprocess (native/openflux-<os>-<arch>), mirroring OlcboxVpnService's
 * startOpenFluxCore: a SOCKS5 with the session credentials, domains resolved through the tunnel, secrets
 * in the environment, and `--exit-on-stdin-eof` so a crashed or killed app doesn't leave it running.
 */
internal class DesktopOpenFlux(
    private val log: (String) -> Unit,
) {
    private var process: Process? = null

    fun isRunning(): Boolean = process?.isAlive == true

    suspend fun start(
        config: OpenFluxConfig,
        listenHost: String,
        listenPort: Int,
        socksUsername: String,
        socksPassword: String,
    ) = withContext(Dispatchers.IO) {
        stop()
        val binary = DesktopNativeAssets.resolveOpenFluxBinary()
        // The core reads the key file once at start; the secret never goes through argv.
        val keyFile = config.secret.takeIf { it.isNotBlank() }?.let {
            kotlin.io.path.createTempFile("openflux-key", ".txt").also { f -> f.toFile().deleteOnExit(); f.toFile().writeText(it) }
        }
        val cmd = buildList {
            addAll(listOf(binary.toString(), "--role=client", "--inbound=socks5", "--transport", config.transport, "--socks5", "$listenHost:$listenPort"))
            if (config.usesMax()) addAll(listOf("--maxUid", config.maxUid)) else addAll(listOf("--url", config.docUrl))
            if (config.dnsServer.isNotBlank()) addAll(listOf("--dns", config.dnsServer))
            if (keyFile != null) addAll(config.encryptionArgs(keyFile.toString()))
            if (config.debug) add("--debug")
            add("--exit-on-stdin-eof")
        }
        log("Starting OpenFlux (${config.summary()}) on $listenHost:$listenPort, dns=${config.dnsServer.ifBlank { "system" }}")
        val started = ProcessBuilder(cmd).redirectErrorStream(true).apply {
            environment()["OPENFLUX_MAX_TOKEN"] = config.maxToken
            environment()["OPENFLUX_SOCKS_USER"] = socksUsername
            environment()["OPENFLUX_SOCKS_PASS"] = socksPassword
        }.start()
        process = started
        if (keyFile != null) Thread { Thread.sleep(5_000); runCatching { java.nio.file.Files.deleteIfExists(keyFile) } }.apply { isDaemon = true; start() }
        Thread {
            runCatching {
                started.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) log("openflux: ${line.trimEnd()}")
                }
            }
        }.apply {
            isDaemon = true
            name = "openflux-log"
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
        }.onFailure { log("OpenFlux stop failed: ${it.message}") }
    }
}
