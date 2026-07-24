package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

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
            if (bypassIps.isNotEmpty()) {
                installBypassRoutes(bypassIps)
                addLog("Installed ${bypassIps.size} bypass route(s) for proxy server(s)")
            }
            installRoutes()
            routesInstalled = true
            addLog("Windows TUN connected on $TUN_NAME")
            return process
        } catch (e: Exception) {
            runCatching { removeRoutes() }
                .onFailure { addLog("Windows TUN partial route cleanup failed: ${it.message}") }
            routesInstalled = false
            stopProcess(process)
            throw e
        }
    }

    suspend fun stop(process: Process?) {
        if (routesInstalled) {
            runCatching { removeRoutes() }
                .onFailure { addLog("Windows TUN route cleanup failed: ${it.message}") }
            routesInstalled = false
        }
        if (bypassIps.isNotEmpty()) {
            runCatching { removeBypassRoutes(bypassIps) }
                .onFailure { addLog("Windows TUN bypass route cleanup failed: ${it.message}") }
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

    private suspend fun isAdministrator(): Boolean {
        val isAdmin = runPowerShell(
            """
            ${'$'}principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
            if (${'$'}principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { 'true' } else { 'false' }
            """.trimIndent()
        ).trim().equals("true", ignoreCase = true)

        return isAdmin
    }

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

    private suspend fun adapterExists(): Boolean {
        return runCatching {
            runPowerShell(
                """
                ${'$'}adapter = Get-NetAdapter -Name '$TUN_NAME' -ErrorAction SilentlyContinue
                if (${'$'}null -ne ${'$'}adapter) { 'true' } else { 'false' }
                """.trimIndent()
            ).trim().equals("true", ignoreCase = true)
        }.getOrDefault(false)
    }

    private suspend fun installRoutes() {
        runPowerShell(
            """
            ${'$'}ErrorActionPreference = 'Stop'
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

    /** Host routes for the engines' upstream servers via the physical default gateway. */
    private suspend fun installBypassRoutes(ips: List<String>) {
        val routeCommands = ips.joinToString("\n") { ip ->
            """
            Get-NetRoute -DestinationPrefix '$ip/32' -ErrorAction SilentlyContinue |
              Remove-NetRoute -Confirm:${'$'}false -ErrorAction SilentlyContinue
            New-NetRoute -InterfaceIndex ${'$'}physIfIndex -DestinationPrefix '$ip/32' -NextHop ${'$'}gateway -RouteMetric 1 | Out-Null
            """.trimIndent()
        }
        runPowerShell(
            """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}default = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction Stop |
              Where-Object { ${'$'}_.InterfaceAlias -ne '$TUN_NAME' -and ${'$'}_.NextHop -ne '0.0.0.0' } |
              Sort-Object RouteMetric, InterfaceMetric |
              Select-Object -First 1
            if (${'$'}null -eq ${'$'}default) { throw 'No physical default gateway found' }
            ${'$'}gateway = ${'$'}default.NextHop
            ${'$'}physIfIndex = ${'$'}default.InterfaceIndex
            $routeCommands
            """.trimIndent()
        )
    }

    private suspend fun removeBypassRoutes(ips: List<String>) {
        runPowerShell(
            ips.joinToString("\n") { ip ->
                """
                Get-NetRoute -DestinationPrefix '$ip/32' -ErrorAction SilentlyContinue |
                  Remove-NetRoute -Confirm:${'$'}false -ErrorAction SilentlyContinue
                """.trimIndent()
            }
        )
    }

    private suspend fun removeRoutes() {
        runPowerShell(
            """
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

    private suspend fun runPowerShell(script: String): String = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            script
        )
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            error("PowerShell failed with code $exitCode: $output")
        }
        output
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
