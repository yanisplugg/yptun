package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.olcbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.exists

internal class LinuxTunController(
    private val addLog: (String) -> Unit
) {
    private var routesInstalled = false
    private var hevBinary: Path? = null

    /**
     * Writes hev's config/up/down scripts and returns the command to launch it — does NOT start the
     * process. Linux TUN needs both hev and olcRTC running as root under the SAME pkexec
     * authorization (see DesktopVpnManager.startOlcRtcProcess's combined launch), so the caller
     * backgrounds this command inside its own privileged process instead of us spawning it directly
     * under a second, separate pkexec.
     */
    fun prepareHevLaunch(
        hevBinary: Path,
        socksPort: Int = PacServer.LOCAL_SOCKS_PORT,
        socksUsername: String = "",
        socksPassword: String = ""
    ): List<String> {
        this.hevBinary = hevBinary
        val upScript = writeUpScript()
        val downScript = writeDownScript()
        val config = writeConfig(socksPort, upScript, downScript, socksUsername, socksPassword)
        return listOf(hevBinary.toString(), config.toString())
    }

    /**
     * Polls for the TUN interface + route rule hev's up-script installs. olcRTC's own SOCKS5
     * readiness check runs first and takes several seconds (WebRTC handshake) — hev, started earlier
     * in the same combined launch well before olcRTC's exec, has had a comfortable head start by the
     * time we get here, so this rarely waits long in practice.
     */
    suspend fun awaitReady() {
        val deadline = System.currentTimeMillis() + TUN_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (interfaceExists() && routeRuleExists()) {
                routesInstalled = true
                addLog("Linux TUN connected on $TUN_NAME")
                return
            }
            delay(TUN_READY_POLL_MS)
        }
        error("$TUN_NAME routes were not installed")
    }

    /**
     * Shell fragments to clean up hev + its routes as root — pkill by binary path (hev double-forks
     * and detaches, so there is never a Process handle left to destroy()) and, if the route rule is
     * still there, the down-script as a fallback for when hev's own pre-down-script didn't run.
     * Returns commands only, doesn't invoke pkexec itself — the caller bundles these with olcRTC's
     * own privileged kill into ONE combined pkexec call instead of each of us prompting separately.
     * Best-effort by design (`|| true` throughout): always included, never gates on whether hev is
     * actually still around, because the caller's pkexec call is happening either way (olcRTC runs
     * as root in this mode, so an unprivileged kill can never reach it) — bundling this in is free.
     */
    fun privilegedCleanupCommands(): List<String> {
        val commands = mutableListOf<String>()
        hevBinary?.let { binary ->
            commands += "pkill -f ${shellQuoted(binary.toString())} >/dev/null 2>&1 || true"
        }
        if (routesInstalled) {
            commands += "sh ${shellQuoted(writeDownScript().toString())} >/dev/null 2>&1 || true"
        }
        return commands
    }

    /** Call once the combined privileged cleanup from [privilegedCleanupCommands] has actually run. */
    suspend fun onStopped() {
        if (routesInstalled) {
            waitForRoutesRemoved()
            routesInstalled = false
        }
    }

    private fun writeConfig(
        socksPort: Int,
        upScript: Path,
        downScript: Path,
        socksUsername: String,
        socksPassword: String
    ): Path {
        val config = DesktopPaths.appDataDir().resolve("linux-tun.yml")
        Files.writeString(
            config,
            configContent(
                socksPort = socksPort,
                postUpScript = upScript.toString(),
                preDownScript = downScript.toString(),
                socksUsername = socksUsername,
                socksPassword = socksPassword
            )
        )
        return config
    }

    private fun writeUpScript(): Path {
        return writeScript(
            name = "linux-tun-up.sh",
            body = upScriptContent()
        )
    }

    private fun writeDownScript(): Path {
        return writeScript(
            name = "linux-tun-down.sh",
            body = downScriptContent()
        )
    }

    private fun writeScript(name: String, body: String): Path {
        val script = DesktopPaths.appDataDir().resolve(name)
        Files.writeString(script, body)
        script.toFile().setExecutable(true, true)
        return script
    }

    private suspend fun interfaceExists(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder("ip", "link", "show", TUN_NAME)
                .redirectErrorStream(true)
                .start()
            process.waitFor(1, TimeUnit.SECONDS) && process.exitValue() == 0
        }.getOrDefault(false)
    }

    private suspend fun routeRuleExists(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder("ip", "rule", "show")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(1, TimeUnit.SECONDS) &&
                    process.exitValue() == 0 &&
                    output.lineSequence().any { line ->
                        val trimmed = line.trim()
                        (
                            trimmed.startsWith("$TUN_RULE_PREF:") ||
                                trimmed.contains("pref $TUN_RULE_PREF")
                            ) &&
                            trimmed.contains("lookup $ROUTE_TABLE")
                    }
        }.getOrDefault(false)
    }

    private suspend fun waitForRoutesRemoved() {
        val deadline = System.currentTimeMillis() + ROUTE_CLEANUP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!routeRuleExists()) return
            delay(TUN_READY_POLL_MS)
        }
    }

    /** POSIX single-quoted: the only escape inside is closing the quote, inserting a literal ', reopening. */
    private fun shellQuoted(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    internal companion object {
        const val TUN_NAME = "olcbox0"
        const val TUN_MTU = 1500
        const val TUN_IPV4_ADDRESS = "10.0.88.88"
        const val MAPDNS_ADDRESS = "1.1.1.1"
        const val MAPDNS_NETWORK = "100.64.0.0"
        const val MAPDNS_NETMASK = "255.192.0.0"
        const val ROUTE_TABLE = "51820"
        const val ROOT_BYPASS_RULE_PREF = "10"
        const val TUN_RULE_PREF = "20"
        const val TUN_READY_TIMEOUT_MS = 10_000L
        const val TUN_READY_POLL_MS = 100L
        const val ROUTE_CLEANUP_TIMEOUT_MS = 2_000L

        /**
         * hev-socks5-tunnel bridges the TUN into the core's local SOCKS inbound. That inbound is
         * started WITH [DesktopSocksProxySettings] credentials whenever the user sets them, so the
         * yaml has to carry them too — otherwise the core rejects every connection and TUN mode looks
         * dead. Same bug that broke routing/cascade on Windows (see WindowsTunController).
         */
        fun configContent(
            socksPort: Int = PacServer.LOCAL_SOCKS_PORT,
            postUpScript: String? = null,
            preDownScript: String? = null,
            socksUsername: String = "",
            socksPassword: String = ""
        ): String {
            return buildString {
                appendLine("tunnel:")
                appendLine("  name: $TUN_NAME")
                appendLine("  mtu: $TUN_MTU")
                appendLine("  multi-queue: false")
                appendLine("  ipv4: $TUN_IPV4_ADDRESS")
                if (!postUpScript.isNullOrBlank()) {
                    appendLine("  post-up-script: $postUpScript")
                }
                if (!preDownScript.isNullOrBlank()) {
                    appendLine("  pre-down-script: $preDownScript")
                }
                appendLine()
                appendLine("socks5:")
                appendLine("  address: ${PacServer.LOCAL_SOCKS_HOST}")
                appendLine("  port: $socksPort")
                appendLine("  udp: 'tcp'")
                appendLine("  pipeline: false")
                if (socksUsername.isNotBlank()) {
                    appendLine("  username: ${yamlQuoted(socksUsername)}")
                    appendLine("  password: ${yamlQuoted(socksPassword)}")
                }
                appendLine()
                appendLine("mapdns:")
                appendLine("  address: $MAPDNS_ADDRESS")
                appendLine("  port: 53")
                appendLine("  network: $MAPDNS_NETWORK")
                appendLine("  netmask: $MAPDNS_NETMASK")
                appendLine("  cache-size: 10000")
                appendLine()
                appendLine("misc:")
                appendLine("  task-stack-size: 24576")
                appendLine("  tcp-buffer-size: 4096")
                appendLine("  max-session-count: 1200")
                appendLine("  connect-timeout: 10000")
                appendLine("  tcp-read-write-timeout: 300000")
                appendLine("  udp-read-write-timeout: 60000")
                appendLine("  log-file: stderr")
                appendLine("  log-level: warn")
            }.trimEnd()
        }

        /** YAML single-quoted scalar: the only escape inside is a doubled quote. */
        private fun yamlQuoted(value: String): String = "'" + value.replace("'", "''") + "'"

        fun upScriptContent(): String {
            return """
                #!/bin/sh
                set -eu
                ip rule del uidrange 0-0 lookup main pref $ROOT_BYPASS_RULE_PREF 2>/dev/null || true
                ip rule del lookup $ROUTE_TABLE pref $TUN_RULE_PREF 2>/dev/null || true
                ip route flush table $ROUTE_TABLE 2>/dev/null || true
                sysctl -w net.ipv4.conf.all.rp_filter=0 >/dev/null 2>&1 || true
                sysctl -w net.ipv4.conf.$TUN_NAME.rp_filter=0 >/dev/null 2>&1 || true
                ip link set $TUN_NAME up
                ip rule add uidrange 0-0 lookup main pref $ROOT_BYPASS_RULE_PREF
                ip route add default dev $TUN_NAME table $ROUTE_TABLE
                ip rule add lookup $ROUTE_TABLE pref $TUN_RULE_PREF
                if command -v resolvectl >/dev/null 2>&1; then
                  resolvectl dns $TUN_NAME $MAPDNS_ADDRESS >/dev/null 2>&1 || true
                  resolvectl domain $TUN_NAME '~.' >/dev/null 2>&1 || true
                  resolvectl default-route $TUN_NAME yes >/dev/null 2>&1 || true
                fi
            """.trimIndent()
        }

        fun downScriptContent(): String {
            return """
                #!/bin/sh
                ip rule del uidrange 0-0 lookup main pref $ROOT_BYPASS_RULE_PREF 2>/dev/null || true
                ip rule del lookup $ROUTE_TABLE pref $TUN_RULE_PREF 2>/dev/null || true
                ip route flush table $ROUTE_TABLE 2>/dev/null || true
                if command -v resolvectl >/dev/null 2>&1; then
                  resolvectl revert $TUN_NAME >/dev/null 2>&1 || true
                fi
            """.trimIndent()
        }
    }
}

internal object LinuxPrivilege {
    fun command(command: List<String>): List<String> {
        if (isRoot()) return command
        val preferred = System.getenv("OLCBOX_LINUX_PRIVILEGE")?.lowercase()
        return when {
            preferred == "sudo" -> listOf("sudo", "-n") + command
            preferred == "pkexec" -> listOf("pkexec") + command
            executableExists("pkexec") -> listOf("pkexec") + command
            else -> listOf("sudo", "-n") + command
        }
    }

    private fun isRoot(): Boolean {
        return runCatching {
            val process = ProcessBuilder("id", "-u")
                .redirectErrorStream(true)
                .start()
            val uid = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor(1, TimeUnit.SECONDS) && uid == "0"
        }.getOrDefault(false)
    }

    private fun executableExists(name: String): Boolean {
        val path = System.getenv("PATH").orEmpty()
        return path.split(':')
            .filter { it.isNotBlank() }
            .map { Path(it).resolve(name) }
            .any { it.exists() && Files.isExecutable(it) }
    }
}
