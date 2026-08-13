package org.olcbox.app.vpn.desktop

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * The adapter this machine reaches the internet through, as an OS interface index.
 *
 * Used to pin the cores' own sockets to the real network (see [YpTunCore.bindOutboundInterface]),
 * the Windows stand-in for `VpnService.protect()`. Resolve it BEFORE the TUN is raised: the probe
 * follows the routing table, and once our own `0.0.0.0/1` route is in place it would answer with
 * the tunnel itself — which is exactly the loop we are avoiding. The TUN adapter is rejected by
 * name as a second line of defence (a leftover adapter from a crashed run).
 */
internal object PhysicalInterface {

    /** OS interface index of the default route's adapter, or 0 when it can't be determined. */
    fun index(): Int = runCatching {
        // A connected UDP socket sends nothing; it just makes the OS pick a route and bind a
        // source address. Cheaper and far faster than shelling out to PowerShell/route.
        val local = DatagramSocket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getByName(PROBE_HOST), PROBE_PORT))
            socket.localAddress
        }
        if (local == null || local.isAnyLocalAddress || local.isLoopbackAddress) return 0
        val nic = NetworkInterface.getByInetAddress(local) ?: return 0
        val names = listOfNotNull(nic.name, nic.displayName)
        if (names.any { it.contains(WindowsTunController.TUN_NAME, ignoreCase = true) }) return 0
        nic.index.takeIf { it > 0 } ?: 0
    }.getOrDefault(0)

    private const val PROBE_HOST = "8.8.8.8"
    private const val PROBE_PORT = 53
}
