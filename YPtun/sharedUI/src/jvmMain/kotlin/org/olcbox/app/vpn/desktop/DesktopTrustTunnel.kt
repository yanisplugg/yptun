package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Desktop counterpart of the Android Trust Tunnel integration.
 *
 * Android links AdGuard's prebuilt AAR and drives `VpnClient` in-process; that AAR only ships
 * Android JNI `.so`s, so desktop runs the official `trusttunnel_client` CLI as a subprocess instead.
 * The shape is otherwise identical to `prepareTrustTunnelProxy` in OlcboxVpnService: the client is
 * configured SOCKS-only (no `[listener.tun]` — our own TUN owns routing) with the kill switch off,
 * and the proxy core then routes through the local SOCKS5 like it does through AmneziaWG.
 *
 * The `tt://` payload is an opaque base64url TLV blob that only AdGuard's code can decode. Rather
 * than reimplement it, we shell out to `setup_wizard --deeplink`, which ships in the same release
 * archive and is the documented non-Android way to turn a deep link into an `[endpoint]` table.
 */
internal class DesktopTrustTunnel(
    private val log: (String) -> Unit,
) {
    private var process: Process? = null

    fun isRunning(): Boolean = process?.isAlive == true

    /**
     * Starts the client with a SOCKS5 listener on `127.0.0.1:[socksPort]`. Throws if the deep link
     * cannot be decoded or the client fails to launch.
     */
    suspend fun start(deepLink: String, socksPort: Int, dirName: String = "trusttunnel") = withContext(Dispatchers.IO) {
        stop()

        val clientBinary = DesktopNativeAssets.resolveTrustTunnelClientBinary()
        val workDir = workDir(dirName)
        val configFile = workDir.resolve("client.toml")
        Files.writeString(configFile, composeConfig(endpointTable(deepLink), socksPort))

        log("Starting Trust Tunnel SOCKS on 127.0.0.1:$socksPort")
        val started = ProcessBuilder(clientBinary.toString(), "--config", configFile.toString())
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .start()
        process = started
        pumpLog(started)
    }

    /**
     * Waits until the client has actually opened its tunnel session to the endpoint. The SOCKS port
     * (and the "Successfully connected" log line) only mean the local listener is up; the HTTP/2
     * session is established lazily on the first request and that first CONNECT stalls for ~6 s.
     * Done here, before the TUN exists, so the stall isn't paid by the first DNS lookups and the
     * first ping. Best-effort: false just means we gave up waiting.
     */
    suspend fun awaitTunnelReady(socksPort: Int, timeoutMs: Long = WARMUP_TIMEOUT_MS): Boolean =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (isRunning() && System.currentTimeMillis() < deadline) {
                if (runCatching { probeConnect(socksPort) }.getOrDefault(false)) return@withContext true
                kotlinx.coroutines.delay(300)
            }
            false
        }

    /** SOCKS5 CONNECT to a public resolver through the local listener; true on a success reply. */
    private fun probeConnect(socksPort: Int): Boolean =
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", socksPort), 1_000)
            s.soTimeout = WARMUP_ATTEMPT_MS
            val out = s.getOutputStream()
            val inp = s.getInputStream()
            out.write(byteArrayOf(5, 1, 0)); out.flush()
            if (inp.readNBytes(2).let { it.size < 2 || it[1].toInt() != 0 }) return false
            // 1.1.1.1:443
            out.write(byteArrayOf(5, 1, 0, 1, 1, 1, 1, 1, 0x01, 0xBB.toByte())); out.flush()
            val reply = inp.readNBytes(10)
            reply.size >= 2 && reply[1].toInt() == 0
        }

    fun stop() {
        val running = process ?: return
        process = null
        runCatching {
            running.destroy()
            if (!running.waitFor(PROCESS_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                running.destroyForcibly()
                running.waitFor(PROCESS_KILL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
        }.onFailure { log("Trust Tunnel stop failed: ${it.message}") }
    }

    private fun composeConfig(endpointToml: String, socksPort: Int): String = buildString {
        // Top-level keys must precede any [table]. Kill switch off: our TUN owns routing, and on
        // desktop the client's kill switch would install firewall rules that need root.
        appendLine("loglevel = \"info\"")
        // Required since 1.1.x: without it the client aborts with "Unexpected VPN mode:" before listening.
        appendLine("vpn_mode = \"general\"")
        appendLine("killswitch_enabled = false")
        appendLine()
        appendLine(endpointToml.trim())
        appendLine()
        appendLine("[listener.socks]")
        appendLine("address = \"127.0.0.1:$socksPort\"")
    }

    private fun pumpLog(process: Process) {
        Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) log("trusttunnel: ${line.trimEnd()}")
                }
            }
        }.apply {
            isDaemon = true
            name = "trusttunnel-log"
            start()
        }
    }

    companion object {
        private const val WIZARD_TIMEOUT_MS = 15_000L
        private const val WARMUP_TIMEOUT_MS = 15_000L
        private const val WARMUP_ATTEMPT_MS = 8_000
        private const val PROCESS_STOP_TIMEOUT_MS = 3_000L
        private const val PROCESS_KILL_TIMEOUT_MS = 1_000L

        /**
         * Decoding shells out to a subprocess, and the endpoint addresses are needed once for the TUN
         * bypass routes (before the engine starts) and again to build the client config, so keep the
         * result. Deep links are immutable, so the link itself is a sound cache key.
         */
        private val decodeCache = ConcurrentHashMap<String, String>()

        private fun workDir(dirName: String = "trusttunnel"): Path =
            DesktopPaths.appDataDir().resolve(dirName).also { Files.createDirectories(it) }

        /** Removes a throwaway client's work dir (ping probes). */
        fun deleteWorkDir(dirName: String) {
            runCatching { workDir(dirName).toFile().deleteRecursively() }
        }

        /**
         * The `[endpoint]` table (header included) behind a `tt://` link, via `setup_wizard` in
         * non-interactive mode — it never prompts there (`select_index` and `checked_overwrite` both
         * short-circuit). The rest of the wizard's document is discarded: it defaults to a TUN
         * listener, which would make the client create an interface and demand root.
         */
        fun endpointTable(deepLink: String): String = decodeCache.getOrPut(deepLink) {
            val wizard = DesktopNativeAssets.resolveTrustTunnelWizardBinary()
            val workDir = workDir()
            val settingsFile = workDir.resolve("wizard-settings.toml")
            Files.deleteIfExists(settingsFile)

            val builder = ProcessBuilder(
                wizard.toString(),
                "--mode", "non-interactive",
                "--deeplink", deepLink,
                "--settings", settingsFile.toString()
            )
                .directory(workDir.toFile())
                .redirectErrorStream(true)
            // setup_wizard.exe ships without an application manifest, so Windows' installer-detection
            // heuristic decides a "wizard" must be an installer and demands elevation — a UAC prompt
            // on every connect. RunAsInvoker opts out of the heuristic. (trusttunnel_client.exe does
            // carry a manifest with asInvoker, so it needs none of this.) Ignored on other platforms.
            builder.environment()["__COMPAT_LAYER"] = "RunAsInvoker"
            val process = builder.start()

            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(WIZARD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                throw IllegalStateException("Trust Tunnel: setup_wizard timed out decoding the tt:// link")
            }
            if (process.exitValue() != 0 || !Files.exists(settingsFile)) {
                throw IllegalStateException(
                    "Trust Tunnel: invalid tt:// link (setup_wizard exited ${process.exitValue()}): " +
                        output.trim().takeLast(400)
                )
            }

            extractEndpointTable(Files.readString(settingsFile))
                ?: throw IllegalStateException("Trust Tunnel: setup_wizard produced no [endpoint] table")
        }

        /**
         * Hosts the client dials directly. They have to skip the TUN, otherwise the tunnel carries
         * its own transport and deadlocks — the same trap AmneziaWG hit. Never throws: a bad link
         * surfaces later, at connect time, with a better message.
         */
        fun endpointHosts(deepLink: String): List<String> {
            val table = runCatching { endpointTable(deepLink) }.getOrElse { return emptyList() }
            val addresses = table.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("addresses") && "=" in it }
                ?: return emptyList()
            return ADDRESS_LITERAL.findAll(addresses.substringAfter("="))
                .map { it.groupValues[1] }
                .mapNotNull { stripPort(it) }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()
        }

        private val ADDRESS_LITERAL = Regex("\"([^\"]+)\"")

        /** `1.2.3.4:443` / `[2001:db8::1]:443` / `vpn.example.com:443` -> the host part. */
        private fun stripPort(value: String): String? {
            val trimmed = value.trim()
            if (trimmed.startsWith("[")) return trimmed.substringAfter('[').substringBefore(']')
            // A bare IPv6 literal has several colons and no port; only strip when there is exactly one.
            if (trimmed.count { it == ':' } != 1) return trimmed
            return trimmed.substringBeforeLast(':')
        }

        /**
         * Returns the `[endpoint]` table (header included) out of a full settings document, i.e.
         * everything up to the next top-level table header. Values never start a line with `[`:
         * inline arrays keep the bracket on the `key = [` line and the embedded certificate is PEM,
         * so a line-oriented scan is enough and avoids pulling in a TOML parser.
         */
        fun extractEndpointTable(document: String): String? {
            val lines = document.lines()
            val start = lines.indexOfFirst { it.trim() == "[endpoint]" }
            if (start < 0) return null
            val rest = lines.drop(start + 1)
            val end = rest.indexOfFirst { it.trimStart().startsWith("[") }
            val body = if (end < 0) rest else rest.take(end)
            return (listOf("[endpoint]") + body).joinToString("\n").trimEnd()
        }
    }
}
