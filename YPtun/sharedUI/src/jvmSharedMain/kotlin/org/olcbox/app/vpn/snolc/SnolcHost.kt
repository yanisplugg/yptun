package org.olcbox.app.vpn.snolc

import java.net.Inet4Address
import java.net.InetAddress

/** carrier-tcp's `endpoint_ip` only parses socket addresses ("invalid socket address syntax" for a name), so resolve first. IPv4 wins, like the rest of the tunnel. */
fun resolveSnolcHost(host: String): String = runCatching {
    val all = InetAddress.getAllByName(host)
    (all.firstOrNull { it is Inet4Address } ?: all.first()).hostAddress?.substringBefore('%')
}.getOrNull() ?: host
