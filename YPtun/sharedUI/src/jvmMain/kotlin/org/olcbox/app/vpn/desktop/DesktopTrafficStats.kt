package org.olcbox.app.vpn.desktop

import com.sun.jna.platform.win32.IPHlpAPI
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Byte counters of the desktop tunnel adapter — the desktop stand-in for Android's
 * `getTun2socksStatsNative()`.
 *
 * Android can ask its bundled hev-socks5-tunnel over JNI; on desktop tun2socks is a separate process
 * (or the TUN lives inside sing-box), so there is nothing to ask. Instead we read the OS's own
 * per-interface counters, which has the nice property of being engine-agnostic: whatever core is
 * running, everything the user sends crosses this adapter exactly once.
 *
 * Both reads are plain syscalls — no process spawn — so a 2 s sampling cadence costs nothing.
 */
internal object DesktopTrafficStats {

    /** A cumulative byte count of the tunnel adapter since it came up. */
    data class Counters(val rxBytes: Long, val txBytes: Long)

    /**
     * IPv4 addresses that identify the tunnel adapter: the external tun2socks device that both the
     * Windows and Linux controllers configure, and sing-box's in-core TUN (desktop per-process split
     * tunneling). Matching on the address rather than the adapter name works for both, and survives
     * Windows handing the adapter a different Java-visible name across reconnects.
     */
    private val TUNNEL_ADDRESSES = setOf(
        WindowsTunController.TUN_IPV4_ADDRESS, // == LinuxTunController.TUN_IPV4_ADDRESS
        "172.19.0.1",                          // SingBoxConfig's in-core tun inbound
    )

    /** Counters of the active tunnel adapter, or null when there is no TUN (proxy mode) / on error. */
    fun readTunnelCounters(): Counters? {
        val iface = findTunnelInterface() ?: return null
        return when (DesktopPaths.os) {
            DesktopOs.Windows -> readWindows(iface.index)
            DesktopOs.Linux -> readLinux(iface.name)
            // macOS has no packaged TUN path yet; nothing to read.
            else -> null
        }
    }

    private fun findTunnelInterface(): NetworkInterface? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().firstOrNull { iface ->
            iface.inetAddresses.asSequence().any { it.hostAddress in TUNNEL_ADDRESSES }
        }
    }.getOrNull()

    /**
     * `GetIfEntry2` gives 64-bit counters (the older MIB_IFROW wraps at 4 GB). From the adapter's
     * point of view In = delivered to the OS stack = the user's download, Out = transmitted into the
     * tunnel = the user's upload.
     */
    private fun readWindows(interfaceIndex: Int): Counters? = runCatching {
        val row = IPHlpAPI.MIB_IF_ROW2()
        row.InterfaceIndex = interfaceIndex
        if (IPHlpAPI.INSTANCE.GetIfEntry2(row) != 0) return null
        Counters(rxBytes = row.InOctets, txBytes = row.OutOctets)
    }.getOrNull()

    /** Same directions as Windows: a TUN's rx is what userspace wrote into it, i.e. the download. */
    private fun readLinux(name: String): Counters? = runCatching {
        val base = Paths.get("/sys/class/net", name, "statistics")
        Counters(
            rxBytes = Files.readString(base.resolve("rx_bytes")).trim().toLong(),
            txBytes = Files.readString(base.resolve("tx_bytes")).trim().toLong(),
        )
    }.getOrNull()
}
