package org.olcbox.app.vpn

import android.content.Context
import com.adguard.trusttunnel.DeepLink
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.vpn.service.OlcboxVpnState

/**
 * Trust Tunnel on Android: the official `trusttunnel_client` CLI (lib/<abi>/libtrusttunnel_client.so) run as a
 * SOCKS-only SUBPROCESS. The AAR's JNI cannot do that itself — it always passes a TUN fd and the client then refuses
 * a SOCKS listener — so the AAR is only used to decode `tt://` into its `[endpoint]` TOML. Shared by the VPN service
 * (the real connection) and the ping probes (a throwaway instance).
 */
object TrustTunnelCore {
    /** Full client config for [ttLink]: SOCKS listener on 127.0.0.1:[port], no TUN, kill switch off. */
    suspend fun configToml(ttLink: String, port: Int): String {
        val endpointToml = runCatching { DeepLink.decode(ttLink) }
            .getOrElse { throw IllegalStateException("Trust Tunnel: invalid tt:// link: ${it.message}") }
            .let { resolveHosts(it) }
        // Top-level keys must precede any [table]; then the decoded [endpoint], then our SOCKS listener.
        // vpn_mode is REQUIRED by TrustTunnelConfig::build_config (missing → the client refuses to start);
        // DeepLink.decode emits only [endpoint], so it must be added here. Kill switch off: our VpnService
        // TUN owns routing, not the client.
        return buildString {
            append("loglevel = \"info\"\n")
            append("vpn_mode = \"general\"\n")
            append("killswitch_enabled = false\n\n")
            append(endpointToml.trim()).append("\n\n")
            append("[listener.socks]\n")
            append("address = \"127.0.0.1:").append(port).append("\"\n")
        }
    }

    /**
     * Starts the client with [toml]. Same UID as the app, so the app's own exclusion from the VPN keeps its sockets
     * off the TUN (no protect() needed). The pinned endpoint certificate is part of the config.
     */
    fun start(context: Context, dirName: String, toml: String): Process {
        val exe = File(context.applicationInfo.nativeLibraryDir, "libtrusttunnel_client.so")
        if (!exe.canExecute()) throw IllegalStateException("Trust Tunnel core is not included in this build for this CPU")
        val dir = File(context.filesDir, dirName).apply { mkdirs() }
        val config = File(dir, "client.toml").apply { writeText(toml) }
        return ProcessBuilder(exe.absolutePath, "-c", config.absolutePath)
            .directory(dir).redirectErrorStream(true).start()
    }

    fun stop(process: Process?) {
        process ?: return
        runCatching { process.destroy() }
        if (runCatching { !process.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(true)) {
            runCatching { process.destroyForcibly() }
        }
    }

    /**
     * The static musl CLI resolves hostname addresses via /etc/resolv.conf, which Android does not have. Resolve
     * here, where the process is already bound to the Wi-Fi network, and hand the client plain ip:port;
     * `hostname` stays untouched, so SNI and certificate checks are unchanged.
     */
    private suspend fun resolveHosts(toml: String): String = withContext(Dispatchers.IO) {
        Regex("""(?m)^(\s*addresses\s*=\s*)\[(.*)]\s*$""").replace(toml) { m ->
            val items = Regex(""""([^"]+)"""").findAll(m.groupValues[2]).flatMap { a ->
                val addr = a.groupValues[1]
                val host = addr.substringBeforeLast(':')
                val port = addr.substringAfterLast(':')
                if (host.startsWith("[") || host.all { it.isDigit() || it == '.' } || port.isEmpty()) {
                    sequenceOf(addr)
                } else {
                    val ips = runCatching { java.net.InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
                        .sortedBy { it !is java.net.Inet4Address }
                        .map { ip -> if (ip is java.net.Inet6Address) "[${ip.hostAddress}]:$port" else "${ip.hostAddress}:$port" }
                    OlcboxVpnState.addLog("trusttunnel: resolved $host -> ${ips.joinToString()}")
                    if (ips.isEmpty()) sequenceOf(addr) else ips.asSequence()
                }
            }.toList()
            m.groupValues[1] + items.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        }
    }
}
