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
     * process. Used when bundling hev launch with another privileged process (e.g. olcRTC).
     */
    fun prepareHevLaunch(
        hevBinary: Path,
        socksPort: Int = PacServer.LOCAL_SOCKS_PORT,
        socksUsername: String = "",
        socksPassword: String = "",
        bypassPrefixes: List<String> = emptyList(),
        udpOverTcp: Boolean = true
    ): List<String> {
        this.hevBinary = hevBinary
        val upScript = writeScript("linux-tun-up.sh", upScriptContent(bypassPrefixes))
        val downScript = writeDownScript()
        val config = writeConfig(socksPort, upScript, downScript, socksUsername, socksPassword, udpOverTcp)
        return listOf(hevBinary.toString(), config.toString())
    }

    /**
     * Launches hev on its own under pkexec/sudo, for the in-process engines (sing-box, Xray,
     * AmneziaWG, …): unlike olcRTC there is no privileged process of ours to ride along with.
     *
     * Those cores run inside this JVM as the USER, so the root-only bypass rule does not cover their
     * own dials — [bypassPrefixes] (their upstreams, see DesktopVpnManager.resolveBypassServerIps)
     * are routed around the TUN instead, or the tunnel would carry itself.
     *
     * hev runs under a root wrapper ([runScriptContent]) that lives exactly as long as this JVM holds
     * its stdin: stopping is closing that pipe ([release]) — no second password — and a crash, kill or
     * logout closes it too, so the tunnel never outlives the app. It used to: hev (root) kept running
     * with its rules in place and the whole machine stayed routed into a dead TUN until a reboot.
     *
     * [udpOverTcp]: hev's own UDP-in-TCP framing, which only olcRTC/MasterDNS/OpenFlux-style SOCKS
     * servers need (they have no UDP ASSOCIATE). Xray and sing-box speak standard SOCKS5 UDP and do
     * NOT understand the framing — with it every UDP flow (DNS to any resolver but the mapped one,
     * QUIC, calls, games) silently died. Same split as Android's hev config.
     */
    suspend fun start(
        hevBinary: Path,
        socksPort: Int,
        socksUsername: String,
        socksPassword: String,
        bypassPrefixes: List<String>,
        udpOverTcp: Boolean
    ): Process = withContext(Dispatchers.IO) {
        val hevCommand = prepareHevLaunch(hevBinary, socksPort, socksUsername, socksPassword, bypassPrefixes, udpOverTcp)
        val runScript = writeScript("linux-tun-run.sh", runScriptContent(hevCommand, writeDownScript().toString()))
        addLog("Starting Linux TUN bridge: ${hevBinary.fileName} -> 127.0.0.1:$socksPort")
        val process = ProcessBuilder(LinuxPrivilege.command(listOf("sh", runScript.toString())))
            .redirectErrorStream(true)
            .start()
        try {
            // The wait includes the pkexec password dialog, so it gets far longer than the olcRTC path.
            awaitReady(launcher = process, timeoutMs = AUTH_READY_TIMEOUT_MS)
        } catch (e: Exception) {
            release(process)
            throw e
        }
        process
    }

    /**
     * Stops a bridge started by [start]: closing its stdin makes the root wrapper take hev and the
     * routes down itself. Once it has exited nothing is left for [privilegedCleanupCommands], so the
     * disconnect needs no authorization; if it hangs, the privileged cleanup still runs as before.
     */
    suspend fun release(process: Process) = withContext(Dispatchers.IO) {
        runCatching { process.outputStream.close() }
        if (process.waitFor(RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            hevBinary = null
            routesInstalled = false
        }
    }

    /**
     * A tunnel left by something that could not clean up after itself (an olcRTC session that crashed,
     * an older YPtun) still owns [TUN_NAME] and the default route: the next hev cannot create the
     * device and every upstream dial of the cores lands in the dead TUN. Take it down before we start.
     */
    suspend fun clearStale(hevBinary: Path) = withContext(Dispatchers.IO) {
        if (!interfaceExists() && !routeRuleExists()) return@withContext
        addLog("A previous $TUN_NAME tunnel is still up — removing it before connecting")
        val script = writeScript(
            "linux-tun-clear.sh",
            "#!/bin/sh\npkill -f ${shellQuoted(hevBinary.toString())} >/dev/null 2>&1\n" +
                "sh ${shellQuoted(writeDownScript().toString())} >/dev/null 2>&1\nexit 0\n"
        )
        runCatching {
            ProcessBuilder(LinuxPrivilege.command(listOf("sh", script.toString())))
                .redirectErrorStream(true)
                .start()
                .waitFor(AUTH_READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        val deadline = System.currentTimeMillis() + ROUTE_CLEANUP_TIMEOUT_MS
        while (interfaceExists() && System.currentTimeMillis() < deadline) delay(TUN_READY_POLL_MS)
    }

    /**
     * Polls for the TUN interface + route rule hev's up-script installs. With a [launcher], gives up
     * as soon as it exits non-zero — pkexec does that when the authorization is dismissed.
     */
    suspend fun awaitReady(launcher: Process? = null, timeoutMs: Long = TUN_READY_TIMEOUT_MS) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (interfaceExists() && routeRuleExists()) {
                routesInstalled = true
                addLog("Linux TUN connected on $TUN_NAME")
                return
            }
            if (launcher != null && !launcher.isAlive && launcher.exitValue() != 0) {
                val code = launcher.exitValue()
                // pkexec's "dismissed"/"not authorized": nothing ran as root, so nothing to clean up
                // and no reason to show a second password prompt for the cleanup.
                if (code == 126 || code == 127) hevBinary = null
                error("Linux TUN bridge exited with code $code${if (code == 126 || code == 127) " (authorization cancelled)" else ""}")
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
     * actually still around.
     */
    fun privilegedCleanupCommands(): List<String> {
        val commands = mutableListOf<String>()
        hevBinary?.let { binary ->
            commands += "pkill -f ${shellQuoted(binary.toString())} >/dev/null 2>&1 || true"
        }
        // Not only once readiness was confirmed: hev's up-script may already have run when the start
        // fails (olcRTC never came up), and a killed hev leaves its rules behind.
        if (routesInstalled || hevBinary != null) {
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
        socksPassword: String,
        udpOverTcp: Boolean
    ): Path {
        val config = DesktopPaths.appDataDir().resolve("linux-tun.yml")
        Files.writeString(
            config,
            configContent(
                socksPort = socksPort,
                postUpScript = upScript.toString(),
                preDownScript = downScript.toString(),
                socksUsername = socksUsername,
                socksPassword = socksPassword,
                udpOverTcp = udpOverTcp
            )
        )
        return config
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

    internal companion object {
        const val TUN_NAME = "olcbox0"
        const val TUN_MTU = 1500
        const val TUN_IPV4_ADDRESS = "10.0.88.88"
        const val MAPDNS_ADDRESS = "1.1.1.1"
        const val MAPDNS_NETWORK = "100.64.0.0"
        const val MAPDNS_NETMASK = "255.192.0.0"
        const val ROUTE_TABLE = "51820"
        const val ROOT_BYPASS_RULE_PREF = "10"
        const val UPSTREAM_BYPASS_RULE_PREF = "15"
        const val TUN_RULE_PREF = "20"
        const val IPV6_LAN_RULE_PREF = "19"
        private const val IPV6_CLEANUP = """ip -6 rule del uidrange 0-0 lookup main pref $ROOT_BYPASS_RULE_PREF 2>/dev/null || true
ip -6 rule del lookup main suppress_prefixlength 0 pref $IPV6_LAN_RULE_PREF 2>/dev/null || true
ip -6 rule del lookup $ROUTE_TABLE pref $TUN_RULE_PREF 2>/dev/null || true
ip -6 route flush table $ROUTE_TABLE 2>/dev/null || true"""
        const val TUN_READY_TIMEOUT_MS = 10_000L
        const val AUTH_READY_TIMEOUT_MS = 120_000L
        private val IPV4_PREFIX = Regex("""\d{1,3}(\.\d{1,3}){3}(/\d{1,2})?""")
        const val TUN_READY_POLL_MS = 100L
        const val ROUTE_CLEANUP_TIMEOUT_MS = 2_000L
        const val RELEASE_TIMEOUT_MS = 5_000L

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
            socksPassword: String = "",
            udpOverTcp: Boolean = true
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
                appendLine("  udp: '${if (udpOverTcp) "tcp" else "udp"}'")
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

        /**
         * The root wrapper around hev (see [start]): hev in the background, then block on stdin until
         * YPtun closes it — on purpose or by dying — and take hev and its routes down.
         */
        fun runScriptContent(hevCommand: List<String>, downScript: String): String = buildString {
            appendLine("#!/bin/sh")
            appendLine(hevCommand.joinToString(" ") { shellQuoted(it) } + " &")
            appendLine("HEV=\$!")
            appendLine("cat >/dev/null")
            appendLine("kill \$HEV 2>/dev/null")
            appendLine("wait \$HEV 2>/dev/null")
            appendLine("sh ${shellQuoted(downScript)} >/dev/null 2>&1")
            appendLine("exit 0")
        }

        /** POSIX single-quoted: the only escape inside is closing the quote, inserting a literal ', reopening. */
        private fun shellQuoted(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        /** YAML single-quoted scalar: the only escape inside is a doubled quote. */
        private fun yamlQuoted(value: String): String = "'" + value.replace("'", "''") + "'"

        /**
         * [bypassPrefixes]: IPv4 addresses/CIDRs sent via the main table ahead of the TUN rule. They
         * land in a script run as root and partly come off the network (ASN lists), so anything that
         * is not strictly a dotted IPv4 (optionally /len) is dropped.
         */
        fun upScriptContent(bypassPrefixes: List<String> = emptyList()): String {
            val bypassRules = bypassPrefixes.distinct().filter { IPV4_PREFIX.matches(it) }.joinToString("") {
                // One bad entry must not abort the whole `set -e` script and with it the TUN.
                "\nip rule add to $it lookup main pref $UPSTREAM_BYPASS_RULE_PREF 2>/dev/null || true"
            }
            return """
                #!/bin/sh
                set -eu
                ip rule del uidrange 0-0 lookup main pref $ROOT_BYPASS_RULE_PREF 2>/dev/null || true
                while ip rule del pref $UPSTREAM_BYPASS_RULE_PREF 2>/dev/null; do :; done
                ip rule del lookup $ROUTE_TABLE pref $TUN_RULE_PREF 2>/dev/null || true
                ip route flush table $ROUTE_TABLE 2>/dev/null || true
            """.trimIndent() + "\n" + IPV6_CLEANUP + "\n" + """
                sysctl -w net.ipv4.conf.all.rp_filter=0 >/dev/null 2>&1 || true
                sysctl -w net.ipv4.conf.$TUN_NAME.rp_filter=0 >/dev/null 2>&1 || true
                ip link set $TUN_NAME up
                ip rule add uidrange 0-0 lookup main pref $ROOT_BYPASS_RULE_PREF
            """.trimIndent() + bypassRules + "\n" + """
                ip route add default dev $TUN_NAME table $ROUTE_TABLE
                ip rule add lookup $ROUTE_TABLE pref $TUN_RULE_PREF
                # The TUN is IPv4-only: without this every AAAA answer went AROUND the tunnel. Make the
                # IPv6 default unreachable instead (apps fall back to IPv4 at once), the way Android
                # blocks a family the VPN does not route; LAN/link-local keep their own routes (pref
                # $IPV6_LAN_RULE_PREF), root keeps IPv6 like it keeps IPv4 (olcRTC's own ICE). Best-effort:
                # a kernel booted with IPv6 disabled has nothing to leak.
                ip -6 rule add uidrange 0-0 lookup main pref $ROOT_BYPASS_RULE_PREF 2>/dev/null || true
                ip -6 rule add lookup main suppress_prefixlength 0 pref $IPV6_LAN_RULE_PREF 2>/dev/null || true
                ip -6 route add unreachable default table $ROUTE_TABLE 2>/dev/null || true
                ip -6 rule add lookup $ROUTE_TABLE pref $TUN_RULE_PREF 2>/dev/null || true
                if command -v resolvectl >/dev/null 2>&1; then
                  resolvectl dns $TUN_NAME $MAPDNS_ADDRESS >/dev/null 2>&1 || true
                  resolvectl domain $TUN_NAME '~.' >/dev/null 2>&1 || true
                  resolvectl default-route $TUN_NAME yes >/dev/null 2>&1 || true
                elif command -v resolvconf >/dev/null 2>&1; then
                  printf "nameserver %s\n" "$MAPDNS_ADDRESS" | resolvconf -a "$TUN_NAME" 2>/dev/null || true
                fi
            """.trimIndent()
        }

        fun downScriptContent(): String {
            return """
                #!/bin/sh
                ip rule del uidrange 0-0 lookup main pref $ROOT_BYPASS_RULE_PREF 2>/dev/null || true
                while ip rule del pref $UPSTREAM_BYPASS_RULE_PREF 2>/dev/null; do :; done
                ip rule del lookup $ROUTE_TABLE pref $TUN_RULE_PREF 2>/dev/null || true
                ip route flush table $ROUTE_TABLE 2>/dev/null || true
            """.trimIndent() + "\n" + IPV6_CLEANUP + "\n" + """
                if command -v resolvectl >/dev/null 2>&1; then
                  resolvectl revert $TUN_NAME >/dev/null 2>&1 || true
                elif command -v resolvconf >/dev/null 2>&1; then
                  resolvconf -d "$TUN_NAME" 2>/dev/null || true
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

    fun executableExists(name: String): Boolean {
        val path = System.getenv("PATH").orEmpty()
        return path.split(':')
            .filter { it.isNotBlank() }
            .map { Path(it).resolve(name) }
            .any { it.exists() && Files.isExecutable(it) }
    }
}
