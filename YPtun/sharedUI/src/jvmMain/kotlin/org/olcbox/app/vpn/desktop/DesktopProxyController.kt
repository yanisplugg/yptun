package org.olcbox.app.vpn.desktop

import com.sun.jna.Memory
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
import org.olcbox.app.desktop.DesktopToast

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

    /** Set by [enable]: [restore] must not touch settings we never changed (e.g. a start that failed early). */
    private var active = false

    // PATH lookup rather than `which`: it is missing on minimal installs (Arch base), and Plasma 6
    // ships only the *6 tools — checking kwriteconfig5 first used to hide KDE entirely there.
    private val hasGsettings get() = LinuxPrivilege.executableExists("gsettings")
    private val kdeWrite get() = listOf("kwriteconfig6", "kwriteconfig5").firstOrNull(LinuxPrivilege::executableExists)
    private val kdeRead get() = listOf("kreadconfig6", "kreadconfig5").firstOrNull(LinuxPrivilege::executableExists)

    override suspend fun enable(httpProxyHostPort: String, pacUrl: String) {
        val host = httpProxyHostPort.substringBefore(':')
        val port = httpProxyHostPort.substringAfter(':').toInt()
        val kde = kdeWrite
        // XFCE, LXQt, sway, Hyprland…: no system proxy to set. Failing the connect used to throw away
        // a working local proxy, and the error never reached the screen — keep it up and say where it is.
        if (!hasGsettings && kde == null) {
            DesktopToast.show(
                org.olcbox.app.ui.i18n.stringsFor(org.olcbox.app.ui.i18n.LocalizationState.effective)
                    .proxyManualSetup(httpProxyHostPort)
            )
            return
        }
        active = true

        if (hasGsettings) {
            readGnomeState()?.takeUnless { it.looksLikeOurs() }?.let { gnomeBackup = it }
            runAll(enableGnomeProxyCommands(host, port))
        }
        if (kde != null) {
            readKdeState()?.takeUnless { it.looksLikeOurs() }?.let { kdeBackup = it }
            runAll(enableKdeProxyCommands("http://$httpProxyHostPort", kde))
        }
    }

    override suspend fun restore() {
        if (!active) return
        active = false
        if (hasGsettings) {
            runAll(gnomeBackup?.let(::restoreGnomeProxyCommands) ?: disableGnomeProxyCommands())
            gnomeBackup = null
        }
        kdeWrite?.let { kde ->
            runAll(kdeBackup?.let { restoreKdeProxyCommands(it, kde) } ?: disableKdeProxyCommands(kde))
            kdeBackup = null
        }
    }

    override suspend fun clearStaleProxy() {
        if (hasGsettings && readGnomeState()?.looksLikeOurs() == true) {
            runAll(disableGnomeProxyCommands())
        }
        val kde = kdeWrite
        if (kde != null && readKdeState()?.looksLikeOurs() == true) {
            runAll(disableKdeProxyCommands(kde))
        }
    }

    private suspend fun runAll(commands: List<List<String>>) = commands.forEach { runCatching { runCommand(it) } }

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
        val readBin = kdeRead ?: error("kreadconfig5/6 not found")
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
        applyPerConnectionOptions(httpProxyHostPort, PROXY_BYPASS)
        refreshProxySettings()
    }

    override suspend fun restore() {
        val state = backup
        active = false
        if (state == null) return
        apply(restoreEdits(state))
        applyPerConnectionOptions(state.proxyServer.takeIf { state.proxyEnable != "0x0" }, state.proxyOverride)
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
            applyPerConnectionOptions(null, null)
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
     * Pushes the proxy into WinINET's **per-connection** settings — what actually decides whether the
     * machine uses a proxy.
     *
     * Writing ProxyEnable/ProxyServer and firing SETTINGS_CHANGED is only half of it: those values are
     * the legacy mirror, while WinINET reads the ACTIVE CONNECTION's own settings. Until something
     * rewrites those, the proxy is listed in the UI but not used — which is exactly "прокси
     * применяется, только если в настройках Windows покопаться": opening Settings -> Proxy is what
     * rewrote them. INTERNET_OPTION_PER_CONNECTION_OPTION (75) writes them directly, needs no admin
     * rights, and is what v2rayN does.
     *
     * The buffers below are INTERNET_PER_CONN_OPTION_LIST / INTERNET_PER_CONN_OPTION laid out by hand:
     * both carry a union, which JNA's Structure mapping makes far more error-prone than explicit
     * offsets. 64-bit is what we ship (x64 + arm64); the 32-bit offsets are there so a 32-bit JRE
     * degrades to correct rather than to garbage.
     */
    private fun applyPerConnectionOptions(proxyHostPort: String?, bypass: String?) {
        runCatching {
            val wide = Native.POINTER_SIZE == 8
            val optionSize = if (wide) 16 else 12
            val unionOffset = if (wide) 8L else 4L
            // Every Memory stays referenced until AFTER the call: JNA frees native memory when the
            // wrapper is collected, and the list points straight at these.
            val server = proxyHostPort?.let { Memory((it.length + 1) * 2L).apply { setWideString(0, it) } }
            val bypassText = bypass?.takeIf { it.isNotBlank() } ?: PROXY_BYPASS
            val bypassMem = Memory((bypassText.length + 1) * 2L).apply { setWideString(0, bypassText) }
            val count = if (server == null) 1 else 3
            val options = Memory(optionSize.toLong() * count)
            options.clear()
            options.setInt(0L, OPTION_FLAGS)
            options.setInt(
                unionOffset,
                if (server == null) PROXY_TYPE_DIRECT else PROXY_TYPE_DIRECT or PROXY_TYPE_PROXY
            )
            if (server != null) {
                options.setInt(optionSize.toLong(), OPTION_PROXY_SERVER)
                options.setPointer(optionSize + unionOffset, server)
                options.setInt(2L * optionSize, OPTION_PROXY_BYPASS)
                options.setPointer(2L * optionSize + unionOffset, bypassMem)
            }
            val listSize = if (wide) 32 else 20
            val list = Memory(listSize.toLong())
            list.clear()
            list.setInt(0L, listSize)                        // dwSize
            list.setPointer(if (wide) 8L else 4L, null)      // pszConnection: null = the LAN connection
            val countOffset = if (wide) 16L else 8L
            list.setInt(countOffset, count)                  // dwOptionCount
            list.setInt(countOffset + 4, 0)                  // dwOptionError
            list.setPointer(if (wide) 24L else 16L, options) // pOptions
            WinINet.INSTANCE.InternetSetOptionW(null, INTERNET_OPTION_PER_CONNECTION_OPTION, list, listSize)
            // Touched after the call so nothing above can be collected mid-flight.
            listOfNotNull(server, bypassMem, options, list).size
        }
    }

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

        /** What never goes through the proxy. Mirrors v2rayN's default bypass list. */
        const val PROXY_BYPASS = "<local>;localhost;127.*;10.*;172.16.*;192.168.*"

        private const val INTERNET_OPTION_PER_CONNECTION_OPTION = 75
        private const val OPTION_FLAGS = 1
        private const val OPTION_PROXY_SERVER = 2
        private const val OPTION_PROXY_BYPASS = 3
        private const val PROXY_TYPE_DIRECT = 1
        private const val PROXY_TYPE_PROXY = 2

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
                RegistryEdit.SetString("ProxyOverride", PROXY_BYPASS),
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

    fun InternetSetOptionW(
        hInternet: Pointer?,
        dwOption: Int,
        lpBuffer: Memory?,
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
