package org.olcbox.app.vpn.desktop

import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** `shell32!IsUserAnAdmin` — the cheapest elevation check there is (see [WindowsTunController]). */
private interface Shell32Ext : StdCallLibrary {
    fun IsUserAnAdmin(): Boolean

    companion object {
        val INSTANCE: Shell32Ext by lazy { Native.load("shell32", Shell32Ext::class.java) }
    }
}

internal class WindowsTunController(
    private val addLog: (String) -> Unit
) {
    private var routesInstalled = false
    private var bypassIps: List<String> = emptyList()

    suspend fun start(
        tun2SocksBinary: Path,
        socksPort: Int = PacServer.LOCAL_SOCKS_PORT,
        // Proxy/relay server IPs that must NOT be routed into the TUN (the engines' own upstream
        // traffic): they get host routes via the physical default gateway, the same trick Android
        // does with VpnService.protect. Without this every non-interface-binding engine (xray,
        // AmneziaWG, Hysteria2, freeturn) would loop its upstream through its own tunnel.
        bypassServerIps: List<String> = emptyList(),
        // Credentials of the core's SOCKS inbound — see [tun2SocksCommand].
        socksUsername: String = "",
        socksPassword: String = ""
    ): Process {
        ensureAdministratorOrRequestRestart()

        val process = ProcessBuilder(
            tun2SocksCommand(tun2SocksBinary, socksPort, socksUsername, socksPassword)
        )
            .directory(tun2SocksBinary.parent.toFile())
            .redirectErrorStream(true)
            .start()

        try {
            waitForAdapter(process)
            bypassIps = bypassServerIps.distinct()
            // ONE PowerShell invocation for the whole route setup. Each spawn costs the better part
            // of a second on Windows, and this used to be two of them on top of a per-poll spawn in
            // waitForAdapter — several seconds of the "connecting" spinner were just process starts.
            installRoutes(bypassIps)
            if (bypassIps.isNotEmpty()) {
                addLog("Installed ${bypassIps.size} bypass route(s) for proxy server(s)")
            }
            routesInstalled = true
            addLog("Windows TUN connected on $TUN_NAME")
            return process
        } catch (e: Exception) {
            runCatching { removeRoutes(bypassIps) }
                .onFailure { addLog("Windows TUN partial route cleanup failed: ${it.message}") }
            routesInstalled = false
            bypassIps = emptyList()
            stopProcess(process)
            throw e
        }
    }

    suspend fun stop(process: Process?) {
        if (routesInstalled || bypassIps.isNotEmpty()) {
            runCatching { removeRoutes(bypassIps) }
                .onFailure { addLog("Windows TUN route cleanup failed: ${it.message}") }
            routesInstalled = false
            bypassIps = emptyList()
        }

        stopProcess(process)
    }

    suspend fun ensureAdministratorOrRequestRestart() {
        if (isAdministrator()) return

        addLog("Requesting Windows administrator privileges for TUN mode")
        requestAdministratorRestart()
        exitProcess(0)
    }

    /**
     * Elevation state of THIS process. `shell32!IsUserAnAdmin` answers instantly; the PowerShell
     * equivalent it replaced cost ~1s of the connect path every single time. Cached because a
     * process cannot gain or lose elevation while it runs.
     */
    private suspend fun isAdministrator(): Boolean {
        cachedIsAdministrator?.let { return it }
        val viaWin32 = runCatching { Shell32Ext.INSTANCE.IsUserAnAdmin() }.getOrNull()
        val result = viaWin32 ?: runPowerShell(
            """
            ${'$'}principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
            if (${'$'}principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { 'true' } else { 'false' }
            """.trimIndent()
        ).trim().equals("true", ignoreCase = true)
        cachedIsAdministrator = result
        return result
    }

    private var cachedIsAdministrator: Boolean? = null

    private suspend fun requestAdministratorRestart() {
        val processInfo = ProcessHandle.current().info()
        val currentCommand = processInfo.command().orElse(null)
            ?: error("YPtun cannot resolve its Windows launcher for administrator restart")
        val currentArguments = processInfo.arguments().orElse(emptyArray()).toList()
        val restartArguments = if (ELEVATED_START_ARGUMENT in currentArguments) {
            currentArguments
        } else {
            currentArguments + ELEVATED_START_ARGUMENT
        }

        runPowerShell(
            restartAsAdministratorScript(
                command = currentCommand,
                arguments = restartArguments,
                workingDirectory = System.getProperty("user.dir").orEmpty()
            )
        )
    }

    private suspend fun waitForAdapter(process: Process) {
        val deadline = System.currentTimeMillis() + TUN_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                error(
                    buildString {
                        append("tun2socks exited before $TUN_NAME was ready")
                        if (output.isNotBlank()) append(": ").append(output)
                    }
                )
            }

            if (adapterExists()) return
            delay(TUN_READY_POLL_MS)
        }

        error("$TUN_NAME adapter was not created")
    }

    /**
     * Whether wintun has published the adapter yet. Answered from the JVM's own interface list
     * instead of `Get-NetAdapter`: this is polled every [TUN_READY_POLL_MS], and one PowerShell
     * per poll dominated the connect time all by itself.
     */
    private fun adapterExists(): Boolean = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().any { nic ->
            listOfNotNull(nic.name, nic.displayName).any { it.equals(TUN_NAME, ignoreCase = true) }
        }
    }.getOrDefault(false)

    /**
     * The whole TUN route setup in ONE PowerShell run: the tunnel's own address + `0.0.0.0/1` and
     * `128.0.0.0/1` default capture + DNS, plus a host/prefix route via the PHYSICAL gateway for
     * every entry in [bypassPrefixes].
     *
     * Those bypass routes carry the engines' own upstream traffic around the capture. Windows has no
     * VpnService.protect(), so they are the only thing keeping a transport core's sockets off the
     * tunnel it is carrying. Entries may be a bare address (routed as /32) or a CIDR — VK-TURN needs
     * whole prefixes, since its TURN relays are picked at runtime and can't be listed up front.
     */
    private suspend fun installRoutes(bypassPrefixes: List<String>) {
        val bypassCommands = bypassPrefixes.map(::toDestinationPrefix).joinToString("\n") { prefix ->
            """
            Get-NetRoute -DestinationPrefix '$prefix' -ErrorAction SilentlyContinue |
              Remove-NetRoute -Confirm:${'$'}false -ErrorAction SilentlyContinue
            New-NetRoute -InterfaceIndex ${'$'}physIfIndex -DestinationPrefix '$prefix' -NextHop ${'$'}gateway -RouteMetric 1 | Out-Null
            """.trimIndent()
        }
        val bypassBlock = if (bypassPrefixes.isEmpty()) "" else """
            ${'$'}default = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction Stop |
              Where-Object { ${'$'}_.InterfaceAlias -ne '$TUN_NAME' -and ${'$'}_.NextHop -ne '0.0.0.0' } |
              Sort-Object RouteMetric, InterfaceMetric |
              Select-Object -First 1
            if (${'$'}null -eq ${'$'}default) { throw 'No physical default gateway found' }
            ${'$'}gateway = ${'$'}default.NextHop
            ${'$'}physIfIndex = ${'$'}default.InterfaceIndex
            $bypassCommands
        """.trimIndent()

        runPowerShell(
            """
            ${'$'}ErrorActionPreference = 'Stop'
            $bypassBlock

            ${'$'}adapter = Get-NetAdapter -Name '$TUN_NAME' -ErrorAction Stop
            ${'$'}ifIndex = ${'$'}adapter.ifIndex

            Get-NetIPAddress -InterfaceIndex ${'$'}ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue |
              Where-Object { ${'$'}_.IPAddress -eq '$TUN_IPV4_ADDRESS' } |
              Remove-NetIPAddress -Confirm:${'$'}false -ErrorAction SilentlyContinue

            New-NetIPAddress -InterfaceIndex ${'$'}ifIndex -IPAddress '$TUN_IPV4_ADDRESS' -PrefixLength $TUN_IPV4_PREFIX_LENGTH -AddressFamily IPv4 | Out-Null

            Get-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '0.0.0.0/1' -ErrorAction SilentlyContinue |
              Remove-NetRoute -Confirm:${'$'}false -ErrorAction SilentlyContinue
            Get-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '128.0.0.0/1' -ErrorAction SilentlyContinue |
              Remove-NetRoute -Confirm:${'$'}false -ErrorAction SilentlyContinue

            New-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '0.0.0.0/1' -NextHop '0.0.0.0' -RouteMetric 1 | Out-Null
            New-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '128.0.0.0/1' -NextHop '0.0.0.0' -RouteMetric 1 | Out-Null
            Set-DnsClientServerAddress -InterfaceIndex ${'$'}ifIndex -ServerAddresses '$MAPDNS_ADDRESS'
            """.trimIndent()
        )
    }

    /** A bare address becomes a host route; anything already carrying a prefix length is kept as-is. */
    private fun toDestinationPrefix(entry: String): String =
        entry.trim().let { if (it.contains('/')) it else "$it/32" }

    /** Undoes [installRoutes] — tunnel address, capture routes, DNS and every bypass route. */
    private suspend fun removeRoutes(bypassPrefixes: List<String>) {
        val bypassCommands = bypassPrefixes.map(::toDestinationPrefix).joinToString("\n") { prefix ->
            """
            Get-NetRoute -DestinationPrefix '$prefix' -ErrorAction SilentlyContinue |
              Remove-NetRoute -Confirm:${'$'}false -ErrorAction SilentlyContinue
            """.trimIndent()
        }
        runPowerShell(
            """
            $bypassCommands
            ${'$'}adapter = Get-NetAdapter -Name '$TUN_NAME' -ErrorAction SilentlyContinue
            if (${'$'}null -eq ${'$'}adapter) { exit 0 }
            ${'$'}ifIndex = ${'$'}adapter.ifIndex
            Get-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '0.0.0.0/1' -ErrorAction SilentlyContinue |
              Remove-NetRoute -Confirm:${'$'}false -ErrorAction SilentlyContinue
            Get-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '128.0.0.0/1' -ErrorAction SilentlyContinue |
              Remove-NetRoute -Confirm:${'$'}false -ErrorAction SilentlyContinue
            Set-DnsClientServerAddress -InterfaceIndex ${'$'}ifIndex -ResetServerAddresses -ErrorAction SilentlyContinue
            Get-NetIPAddress -InterfaceIndex ${'$'}ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue |
              Where-Object { ${'$'}_.IPAddress -eq '$TUN_IPV4_ADDRESS' } |
              Remove-NetIPAddress -Confirm:${'$'}false -ErrorAction SilentlyContinue
            """.trimIndent()
        )
    }

    /**
     * Runs [script] via a temp .ps1 rather than `-Command`.
     *
     * A VK-TURN session carves ~120 VK/OK prefixes out of the tunnel, and the resulting inline
     * script blew past the 32 767-char Windows command line: `CreateProcess error=206, the filename
     * or extension is too long` — the connection then failed outright. A file has no such limit.
     */
    private suspend fun runPowerShell(script: String): String = withContext(Dispatchers.IO) {
        val scriptFile = Files.createTempFile("yptun-tun-", ".ps1")
        try {
            Files.writeString(scriptFile, script)
            val process = ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                scriptFile.toAbsolutePath().toString()
            )
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                error("PowerShell failed with code $exitCode: $output")
            }
            output
        } finally {
            runCatching { Files.deleteIfExists(scriptFile) }
        }
    }

    private fun stopProcess(process: Process?) {
        if (process == null || !process.isAlive) return
        process.toHandle().descendants().forEach { it.destroy() }
        process.destroy()
        if (!process.waitFor(PROCESS_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.toHandle().descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(PROCESS_KILL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    internal companion object {
        const val TUN_NAME = "YPtun"
        const val TUN_MTU = 1500
        const val TUN_IPV4_ADDRESS = "10.0.88.88"
        const val TUN_IPV4_PREFIX_LENGTH = 24
        const val MAPDNS_ADDRESS = "1.1.1.1"
        const val TUN_READY_TIMEOUT_MS = 10_000L
        const val TUN_READY_POLL_MS = 100L
        const val PROCESS_STOP_TIMEOUT_MS = 3_000L
        const val PROCESS_KILL_TIMEOUT_MS = 1_000L
        const val ELEVATED_START_ARGUMENT = "--olcbox-start-vpn-after-elevation"

        /**
         * The tun2socks bridge MUST authenticate against the core's SOCKS inbound. sing-box/xray are
         * started with the per-session DesktopSocksProxySettings credentials, so a credential-less
         * `socks5://host:port` here made the core reject every connection ("no matching auth method")
         * — the tunnel came up and then carried nothing. That only bites on the external-bridge path,
         * i.e. exactly when sing-box does NOT own the TUN itself: a routing profile or an xhttp
         * cascade forces the Xray core, which is why "routing" and "cascade" looked broken in TUN mode
         * while a plain sing-box connection worked.
         */
        fun tun2SocksCommand(
            tun2SocksBinary: Path,
            socksPort: Int = PacServer.LOCAL_SOCKS_PORT,
            socksUsername: String = "",
            socksPassword: String = ""
        ): List<String> {
            val credentials = if (socksUsername.isNotBlank()) {
                "${socksUsername.urlEncoded()}:${socksPassword.urlEncoded()}@"
            } else {
                ""
            }
            return listOf(
                tun2SocksBinary.toString(),
                "--device",
                TUN_NAME,
                "--proxy",
                "socks5://$credentials${PacServer.LOCAL_SOCKS_HOST}:$socksPort",
                "--mtu",
                TUN_MTU.toString(),
                "--loglevel",
                "warn"
            )
        }

        /** tun2socks parses `--proxy` as a URL, so credentials need percent-encoding. */
        private fun String.urlEncoded(): String =
            java.net.URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")

        fun restartAsAdministratorScript(
            command: String,
            arguments: List<String>,
            workingDirectory: String
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
}
