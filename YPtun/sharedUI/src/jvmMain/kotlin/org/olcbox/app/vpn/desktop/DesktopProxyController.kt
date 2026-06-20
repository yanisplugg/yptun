package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths

internal interface DesktopProxyController {
    /**
     * Route the OS through our local proxy. [httpProxyHostPort] is a `host:port` for a direct HTTP
     * proxy (Windows); [pacUrl] is a PAC file URL (macOS). Each platform uses whichever it supports.
     */
    suspend fun enable(httpProxyHostPort: String, pacUrl: String)

    /** Put the OS proxy settings back exactly as they were before [enable]. Safe to call twice. */
    suspend fun restore()

    /**
     * Clear any *stale* proxy this app left behind after a crash/kill (system proxy still pointing at
     * our now-dead local port). Called on startup so a previous unclean exit can't keep the machine
     * offline. No-op if the current proxy isn't ours.
     */
    suspend fun clearStaleProxy()

    companion object {
        fun current(): DesktopProxyController {
            return when (DesktopPaths.os) {
                DesktopOs.MacOS -> MacOsProxyController()
                DesktopOs.Windows -> WindowsProxyController()
                DesktopOs.Linux -> UnsupportedProxyController()
                DesktopOs.Other -> UnsupportedProxyController()
            }
        }
    }
}

internal class UnsupportedProxyController : DesktopProxyController {
    override suspend fun enable(httpProxyHostPort: String, pacUrl: String) {
        error("System proxy mode supports macOS and Windows")
    }

    override suspend fun restore() = Unit
    override suspend fun clearStaleProxy() = Unit
}

internal data class MacOsAutoProxyState(
    val service: String,
    val enabled: Boolean,
    val url: String?
)

internal class MacOsProxyController : DesktopProxyController {
    private var backup: List<MacOsAutoProxyState>? = null

    override suspend fun enable(httpProxyHostPort: String, pacUrl: String) {
        val services = enabledNetworkServices()
        // Don't capture our own PAC as the "original" if enable() runs twice.
        val captured = services.map { readAutoProxyState(it) }
        if (captured.none { it.url == pacUrl }) backup = captured
        enableCommands(services, pacUrl).forEach { runCommand(it) }
    }

    override suspend fun restore() {
        val states = backup ?: return
        restoreCommands(states).forEach { command ->
            runCatching { runCommand(command) }
        }
        backup = null
    }

    override suspend fun clearStaleProxy() {
        val services = runCatching { enabledNetworkServices() }.getOrDefault(emptyList())
        services.forEach { service ->
            val state = runCatching { readAutoProxyState(service) }.getOrNull() ?: return@forEach
            if (state.enabled && state.url?.contains("127.0.0.1") == true) {
                runCatching { runCommand(listOf("networksetup", "-setautoproxystate", service, "off")) }
            }
        }
    }

    private suspend fun enabledNetworkServices(): List<String> {
        return runCommand(listOf("networksetup", "-listallnetworkservices"))
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("An asterisk") && !it.startsWith("*") }
            .toList()
    }

    private suspend fun readAutoProxyState(service: String): MacOsAutoProxyState {
        val output = runCommand(listOf("networksetup", "-getautoproxyurl", service))
        val enabled = output.lineSequence()
            .firstOrNull { it.startsWith("Enabled:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
            ?.equals("Yes", ignoreCase = true) == true
        val url = output.lineSequence()
            .firstOrNull { it.startsWith("URL:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "(null)" }
        return MacOsAutoProxyState(service, enabled, url)
    }

    companion object {
        fun enableCommands(services: List<String>, pacUrl: String): List<List<String>> {
            return services.flatMap { service ->
                listOf(
                    listOf("networksetup", "-setautoproxyurl", service, pacUrl),
                    listOf("networksetup", "-setautoproxystate", service, "on")
                )
            }
        }

        fun restoreCommands(states: List<MacOsAutoProxyState>): List<List<String>> {
            return states.flatMap { state ->
                if (state.enabled && !state.url.isNullOrBlank()) {
                    listOf(
                        listOf("networksetup", "-setautoproxyurl", state.service, state.url),
                        listOf("networksetup", "-setautoproxystate", state.service, "on")
                    )
                } else {
                    listOf(listOf("networksetup", "-setautoproxystate", state.service, "off"))
                }
            }
        }
    }
}

internal data class WindowsProxyState(
    val proxyEnable: String?,
    val proxyServer: String?,
    val proxyOverride: String?,
    val autoConfigUrl: String?
) {
    /** True when these settings already point at one of OUR local proxies (loopback). */
    fun looksLikeOurs(): Boolean {
        val s = proxyServer?.contains("127.0.0.1") == true || proxyServer?.contains("localhost") == true
        val a = autoConfigUrl?.contains("127.0.0.1") == true || autoConfigUrl?.contains("localhost") == true
        return s || a
    }
}

internal class WindowsProxyController : DesktopProxyController {
    @Volatile private var backup: WindowsProxyState? = null
    @Volatile private var active = false
    private var shutdownHook: Thread? = null

    override suspend fun enable(httpProxyHostPort: String, pacUrl: String) {
        val current = readState()
        // Never capture our own proxy as the thing to restore to — otherwise disabling would
        // "restore" the machine to a dead loopback proxy and kill all internet. If the current state
        // is already ours (re-enable / leftover), keep the previous clean backup or fall back to a
        // disabled state.
        if (!current.looksLikeOurs()) {
            backup = current
        } else if (backup == null) {
            backup = DISABLED_STATE
        }
        active = true
        ensureShutdownHook()
        // Direct HTTP proxy is what WinINET honours reliably (PAC + SOCKS5 is flaky on Windows).
        // Best-effort per command: e.g. `reg delete AutoConfigURL` exits non-zero when the value is
        // absent — that must NOT abort enabling the proxy.
        enableHttpCommands(httpProxyHostPort).forEach { runCatching { runCommand(it) } }
        refreshProxySettings()
    }

    override suspend fun restore() {
        val state = backup
        active = false
        if (state == null) return
        restoreCommands(state).forEach { command ->
            runCatching { runCommand(command) }
        }
        refreshProxySettings()
        backup = null
        removeShutdownHook()
    }

    override suspend fun clearStaleProxy() {
        val current = runCatching { readState() }.getOrNull() ?: return
        if (current.looksLikeOurs()) {
            // A previous run left a loopback proxy set but nothing is serving it now → disable it so
            // the machine has working internet again.
            restoreCommands(DISABLED_STATE).forEach { runCatching { runCommand(it) } }
            refreshProxySettings()
        }
    }

    private fun ensureShutdownHook() {
        if (shutdownHook != null) return
        val hook = Thread {
            // Last-resort cleanup on JVM exit / Ctrl-C / window close so a crash never bricks the net.
            if (active) runCatching { runBlocking { restore() } }
        }
        shutdownHook = hook
        runCatching { Runtime.getRuntime().addShutdownHook(hook) }
    }

    private fun removeShutdownHook() {
        val hook = shutdownHook ?: return
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
        shutdownHook = null
    }

    private suspend fun readState(): WindowsProxyState {
        return WindowsProxyState(
            proxyEnable = queryValue("ProxyEnable"),
            proxyServer = queryValue("ProxyServer"),
            proxyOverride = queryValue("ProxyOverride"),
            autoConfigUrl = queryValue("AutoConfigURL")
        )
    }

    private suspend fun queryValue(name: String): String? {
        val output = runCatching {
            runCommand(listOf("reg", "query", REGISTRY_KEY, "/v", name))
        }.getOrNull() ?: return null

        return output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(name) }
            ?.split(Regex("\\s{2,}"))
            ?.lastOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private suspend fun refreshProxySettings() {
        runCatching { runCommand(refreshCommand()) }
    }

    companion object {
        private const val REGISTRY_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"

        // Represents "no proxy configured" — restoring to this leaves a clean, online machine.
        private val DISABLED_STATE = WindowsProxyState(
            proxyEnable = "0x0",
            proxyServer = null,
            proxyOverride = null,
            autoConfigUrl = null
        )

        fun enableHttpCommands(hostPort: String): List<List<String>> {
            return listOf(
                // Clear any PAC so it can't race with the fixed proxy.
                listOf("reg", "delete", REGISTRY_KEY, "/v", "AutoConfigURL", "/f"),
                setStringCommand("ProxyServer", hostPort),
                // Let loopback/intranet bypass the proxy so localhost tooling keeps working.
                setStringCommand("ProxyOverride", "<local>;localhost;127.*;10.*;172.16.*;192.168.*"),
                setDwordCommand("ProxyEnable", "1")
            )
        }

        fun restoreCommands(state: WindowsProxyState): List<List<String>> {
            return listOf(
                valueCommand("ProxyEnable", state.proxyEnable, isDword = true),
                valueCommand("ProxyServer", state.proxyServer, isDword = false),
                valueCommand("ProxyOverride", state.proxyOverride, isDword = false),
                valueCommand("AutoConfigURL", state.autoConfigUrl, isDword = false)
            )
        }

        private fun valueCommand(name: String, value: String?, isDword: Boolean): List<String> {
            return if (value == null) {
                listOf("reg", "delete", REGISTRY_KEY, "/v", name, "/f")
            } else if (isDword) {
                setDwordCommand(name, value.removePrefix("0x").toIntOrNull(16)?.toString() ?: value)
            } else {
                setStringCommand(name, value)
            }
        }

        private fun setStringCommand(name: String, value: String): List<String> {
            return listOf("reg", "add", REGISTRY_KEY, "/v", name, "/t", "REG_SZ", "/d", value, "/f")
        }

        private fun setDwordCommand(name: String, value: String): List<String> {
            return listOf("reg", "add", REGISTRY_KEY, "/v", name, "/t", "REG_DWORD", "/d", value, "/f")
        }

        fun refreshCommand(): List<String> {
            val script = """
                ${'$'}signature = '[System.Runtime.InteropServices.DllImport("wininet.dll", SetLastError = true)] public static extern bool InternetSetOption(System.IntPtr hInternet, int dwOption, System.IntPtr lpBuffer, int dwBufferLength);';
                Add-Type -MemberDefinition ${'$'}signature -Name WinInet -Namespace Native;
                [Native.WinInet]::InternetSetOption([System.IntPtr]::Zero, 39, [System.IntPtr]::Zero, 0) | Out-Null;
                [Native.WinInet]::InternetSetOption([System.IntPtr]::Zero, 37, [System.IntPtr]::Zero, 0) | Out-Null;
            """.trimIndent()
            return listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script)
        }
    }
}

private suspend fun runCommand(command: List<String>): String = withContext(Dispatchers.IO) {
    val process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        error("${command.joinToString(" ")} failed with code $exitCode: $output")
    }
    output
}
