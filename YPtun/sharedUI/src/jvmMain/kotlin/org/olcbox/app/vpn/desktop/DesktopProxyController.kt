package org.olcbox.app.vpn.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.win32.StdCallLibrary
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
                DesktopOs.Linux -> LinuxProxyController()
                DesktopOs.Other -> UnsupportedProxyController()
            }
        }
    }
}

internal class UnsupportedProxyController : DesktopProxyController {
    override suspend fun enable(httpProxyHostPort: String, pacUrl: String) {
        error("System proxy mode is not supported on this platform")
    }

    override suspend fun restore() = Unit
    override suspend fun clearStaleProxy() = Unit
}

internal data class LinuxGnomeProxyState(
    val mode: String,
    val httpHost: String,
    val httpPort: Int,
    val httpsHost: String,
    val httpsPort: Int,
    val ignoreHosts: String
) {
    fun looksLikeOurs(): Boolean =
        (httpHost.contains("127.0.0.1") || httpHost.contains("localhost")) &&
        (mode == "'manual'" || mode == "manual")
}

internal data class LinuxKdeProxyState(
    val proxyType: String,
    val httpProxy: String,
    val httpsProxy: String
) {
    fun looksLikeOurs(): Boolean =
        (httpProxy.contains("127.0.0.1") || httpProxy.contains("localhost")) &&
        (proxyType == "1" || proxyType == "2")
}

internal class LinuxProxyController : DesktopProxyController {
    private var gnomeBackup: LinuxGnomeProxyState? = null
    private var kdeBackup: LinuxKdeProxyState? = null

    override suspend fun enable(httpProxyHostPort: String, pacUrl: String) {
        val host = httpProxyHostPort.substringBefore(':')
        val port = httpProxyHostPort.substringAfter(':', "8080").toIntOrNull() ?: 8080

        if (hasGsettings()) {
            val current = readGnomeState()
            if (current != null && !current.looksLikeOurs()) {
                gnomeBackup = current
            }
            enableGnomeProxyCommands(host, port).forEach { cmd ->
                runCatching { runCommand(cmd) }
            }
        }

        if (hasKdeConfig()) {
            val current = readKdeState()
            if (current != null && !current.looksLikeOurs()) {
                kdeBackup = current
            }
            enableKdeProxyCommands("http://$httpProxyHostPort").forEach { cmd ->
                runCatching { runCommand(cmd) }
            }
        }
    }

    override suspend fun restore() {
        gnomeBackup?.let { state ->
            restoreGnomeProxyCommands(state).forEach { cmd ->
                runCatching { runCommand(cmd) }
            }
            gnomeBackup = null
        } ?: run {
            if (hasGsettings()) {
                disableGnomeProxyCommands().forEach { cmd ->
                    runCatching { runCommand(cmd) }
                }
            }
        }

        kdeBackup?.let { state ->
            restoreKdeProxyCommands(state).forEach { cmd ->
                runCatching { runCommand(cmd) }
            }
            kdeBackup = null
        } ?: run {
            if (hasKdeConfig()) {
                disableKdeProxyCommands().forEach { cmd ->
                    runCatching { runCommand(cmd) }
                }
            }
        }
    }

    override suspend fun clearStaleProxy() {
        if (hasGsettings()) {
            val current = readGnomeState()
            if (current != null && current.looksLikeOurs()) {
                disableGnomeProxyCommands().forEach { cmd ->
                    runCatching { runCommand(cmd) }
                }
            }
        }
        if (hasKdeConfig()) {
            val current = readKdeState()
            if (current != null && current.looksLikeOurs()) {
                disableKdeProxyCommands().forEach { cmd ->
                    runCatching { runCommand(cmd) }
                }
            }
        }
    }

    private suspend fun hasGsettings(): Boolean = runCatching {
        val out = runCommand(listOf("which", "gsettings"))
        out.isNotBlank()
    }.getOrDefault(false)

    private suspend fun hasKdeConfig(): Boolean = runCatching {
        val out = runCommand(listOf("which", "kwriteconfig5")).ifBlank {
            runCatching { runCommand(listOf("which", "kwriteconfig6")) }.getOrDefault("")
        }
        out.isNotBlank()
    }.getOrDefault(false)

    private suspend fun kdeConfigBinary(): String = runCatching {
        val which6 = runCatching { runCommand(listOf("which", "kwriteconfig6")) }.getOrDefault("")
        if (which6.isNotBlank()) "kwriteconfig6" else "kwriteconfig5"
    }.getOrDefault("kwriteconfig5")

    private suspend fun kdeReadBinary(): String = runCatching {
        val which6 = runCatching { runCommand(listOf("which", "kreadconfig6")) }.getOrDefault("")
        if (which6.isNotBlank()) "kreadconfig6" else "kreadconfig5"
    }.getOrDefault("kreadconfig5")

    private suspend fun readGnomeState(): LinuxGnomeProxyState? = runCatching {
        val mode = runCommand(listOf("gsettings", "get", "org.gnome.system.proxy", "mode")).trim()
        val httpHost = runCommand(listOf("gsettings", "get", "org.gnome.system.proxy.http", "host")).trim()
        val httpPort = runCommand(listOf("gsettings", "get", "org.gnome.system.proxy.http", "port")).trim().toIntOrNull() ?: 0
        val httpsHost = runCommand(listOf("gsettings", "get", "org.gnome.system.proxy.https", "host")).trim()
        val httpsPort = runCommand(listOf("gsettings", "get", "org.gnome.system.proxy.https", "port")).trim().toIntOrNull() ?: 0
        val ignoreHosts = runCommand(listOf("gsettings", "get", "org.gnome.system.proxy", "ignore-hosts")).trim()
        LinuxGnomeProxyState(mode, httpHost, httpPort, httpsHost, httpsPort, ignoreHosts)
    }.getOrNull()

    private suspend fun readKdeState(): LinuxKdeProxyState? = runCatching {
        val readBin = kdeReadBinary()
        val proxyType = runCommand(listOf(readBin, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "ProxyType")).trim()
        val httpProxy = runCommand(listOf(readBin, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "httpProxy")).trim()
        val httpsProxy = runCommand(listOf(readBin, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "httpsProxy")).trim()
        LinuxKdeProxyState(proxyType, httpProxy, httpsProxy)
    }.getOrNull()

    companion object {
        fun enableGnomeProxyCommands(host: String, port: Int): List<List<String>> = listOf(
            listOf("gsettings", "set", "org.gnome.system.proxy.http", "host", host),
            listOf("gsettings", "set", "org.gnome.system.proxy.http", "port", port.toString()),
            listOf("gsettings", "set", "org.gnome.system.proxy.https", "host", host),
            listOf("gsettings", "set", "org.gnome.system.proxy.https", "port", port.toString()),
            listOf("gsettings", "set", "org.gnome.system.proxy", "ignore-hosts", "['localhost', '127.0.0.0/8', '::1']"),
            listOf("gsettings", "set", "org.gnome.system.proxy", "mode", "manual")
        )

        fun disableGnomeProxyCommands(): List<List<String>> = listOf(
            listOf("gsettings", "set", "org.gnome.system.proxy", "mode", "none")
        )

        fun restoreGnomeProxyCommands(state: LinuxGnomeProxyState): List<List<String>> = buildList {
            if (state.mode == "'none'" || state.mode == "none") {
                add(listOf("gsettings", "set", "org.gnome.system.proxy", "mode", "none"))
            } else {
                add(listOf("gsettings", "set", "org.gnome.system.proxy.http", "host", state.httpHost.trim('\'')))
                add(listOf("gsettings", "set", "org.gnome.system.proxy.http", "port", state.httpPort.toString()))
                add(listOf("gsettings", "set", "org.gnome.system.proxy.https", "host", state.httpsHost.trim('\'')))
                add(listOf("gsettings", "set", "org.gnome.system.proxy.https", "port", state.httpsPort.toString()))
                add(listOf("gsettings", "set", "org.gnome.system.proxy", "ignore-hosts", state.ignoreHosts))
                add(listOf("gsettings", "set", "org.gnome.system.proxy", "mode", state.mode.trim('\'')))
            }
        }

        fun enableKdeProxyCommands(proxyUrl: String, binary: String = "kwriteconfig5"): List<List<String>> = listOf(
            listOf(binary, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "ProxyType", "1"),
            listOf(binary, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "httpProxy", proxyUrl),
            listOf(binary, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "httpsProxy", proxyUrl)
        )

        fun disableKdeProxyCommands(binary: String = "kwriteconfig5"): List<List<String>> = listOf(
            listOf(binary, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "ProxyType", "0"),
            listOf(binary, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "httpProxy", ""),
            listOf(binary, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "httpsProxy", "")
        )

        fun restoreKdeProxyCommands(state: LinuxKdeProxyState, binary: String = "kwriteconfig5"): List<List<String>> = listOf(
            listOf(binary, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "ProxyType", state.proxyType.ifBlank { "0" }),
            listOf(binary, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "httpProxy", state.httpProxy),
            listOf(binary, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "httpsProxy", state.httpsProxy)
        )
    }
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

/**
 * One change to the Internet Settings registry key.
 *
 * Kept as data rather than a command line because these used to be `reg.exe` invocations: enabling
 * spawned eight processes (four `reg query` + four `reg add`) and disabling four more, plus a
 * PowerShell process to notify WinINET. PowerShell alone costs the better part of a second from
 * cold — which is the whole of "медленно завершается отключение в прокси режиме", since the user
 * waits on it with the app sitting in «Отключение…». The edits are now applied through the registry
 * API directly (microseconds), and building them stays a pure function so it can still be tested.
 */
internal sealed interface RegistryEdit {
    val name: String

    data class SetString(override val name: String, val value: String) : RegistryEdit
    data class SetDword(override val name: String, val value: Int) : RegistryEdit
    data class Delete(override val name: String) : RegistryEdit
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
        // Best-effort per edit: deleting AutoConfigURL fails when the value is absent, and that
        // must NOT abort enabling the proxy.
        apply(enableHttpEdits(httpProxyHostPort))
        refreshProxySettings()
    }

    override suspend fun restore() {
        val state = backup
        active = false
        if (state == null) return
        apply(restoreEdits(state))
        refreshProxySettings()
        backup = null
        removeShutdownHook()
    }

    private fun apply(edits: List<RegistryEdit>) {
        edits.forEach { edit ->
            runCatching {
                when (edit) {
                    is RegistryEdit.SetString -> Advapi32Util.registrySetStringValue(
                        WinReg.HKEY_CURRENT_USER, REGISTRY_KEY, edit.name, edit.value
                    )
                    is RegistryEdit.SetDword -> Advapi32Util.registrySetIntValue(
                        WinReg.HKEY_CURRENT_USER, REGISTRY_KEY, edit.name, edit.value
                    )
                    is RegistryEdit.Delete -> Advapi32Util.registryDeleteValue(
                        WinReg.HKEY_CURRENT_USER, REGISTRY_KEY, edit.name
                    )
                }
            }
        }
    }

    override suspend fun clearStaleProxy() {
        val current = runCatching { readState() }.getOrNull() ?: return
        if (current.looksLikeOurs()) {
            // A previous run left a loopback proxy set but nothing is serving it now → disable it so
            // the machine has working internet again.
            apply(restoreEdits(DISABLED_STATE))
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

    private fun readState(): WindowsProxyState {
        return WindowsProxyState(
            proxyEnable = intValue("ProxyEnable")?.let { "0x" + Integer.toHexString(it) },
            proxyServer = stringValue("ProxyServer"),
            proxyOverride = stringValue("ProxyOverride"),
            autoConfigUrl = stringValue("AutoConfigURL")
        )
    }

    private fun stringValue(name: String): String? = runCatching {
        Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, REGISTRY_KEY, name)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun intValue(name: String): Int? = runCatching {
        Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, REGISTRY_KEY, name)
    }.getOrNull()

    /**
     * Tells WinINET — and therefore Edge, Chrome and everything else on the system proxy — to
     * re-read the settings. INTERNET_OPTION_SETTINGS_CHANGED = 39, INTERNET_OPTION_REFRESH = 37.
     */
    private fun refreshProxySettings() {
        runCatching {
            WinINet.INSTANCE.InternetSetOptionW(null, 39, null, 0)
            WinINet.INSTANCE.InternetSetOptionW(null, 37, null, 0)
        }
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

        fun enableHttpEdits(hostPort: String): List<RegistryEdit> {
            return listOf(
                // Clear any PAC so it cannot race with the fixed proxy.
                RegistryEdit.Delete("AutoConfigURL"),
                RegistryEdit.SetString("ProxyServer", hostPort),
                // Let loopback/intranet bypass the proxy so localhost tooling keeps working.
                RegistryEdit.SetString(
                    "ProxyOverride", "<local>;localhost;127.*;10.*;172.16.*;192.168.*"
                ),
                RegistryEdit.SetDword("ProxyEnable", 1)
            )
        }

        fun restoreEdits(state: WindowsProxyState): List<RegistryEdit> {
            return listOf(
                dwordEdit("ProxyEnable", state.proxyEnable),
                stringEdit("ProxyServer", state.proxyServer),
                stringEdit("ProxyOverride", state.proxyOverride),
                stringEdit("AutoConfigURL", state.autoConfigUrl)
            )
        }

        private fun stringEdit(name: String, value: String?): RegistryEdit =
            if (value == null) RegistryEdit.Delete(name) else RegistryEdit.SetString(name, value)

        private fun dwordEdit(name: String, value: String?): RegistryEdit {
            if (value == null) return RegistryEdit.Delete(name)
            val parsed = value.removePrefix("0x").toIntOrNull(16) ?: value.toIntOrNull()
            return if (parsed == null) RegistryEdit.Delete(name) else RegistryEdit.SetDword(name, parsed)
        }
    }
}

/** `wininet!InternetSetOptionW` - the "settings changed" notification, without a PowerShell detour. */
private interface WinINet : StdCallLibrary {
    fun InternetSetOptionW(
        hInternet: Pointer?,
        dwOption: Int,
        lpBuffer: Pointer?,
        dwBufferLength: Int
    ): Boolean

    companion object {
        val INSTANCE: WinINet by lazy { Native.load("wininet", WinINet::class.java) }
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
