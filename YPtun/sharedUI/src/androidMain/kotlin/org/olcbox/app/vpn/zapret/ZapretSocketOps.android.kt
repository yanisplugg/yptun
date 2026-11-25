package org.olcbox.app.vpn.zapret

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.net.Inet6Address
import java.net.Socket

internal actual fun setZapretSocketTtl(socket: Socket, ttl: Int): Boolean = runCatching {
    ParcelFileDescriptor.fromSocket(socket).use { pfd ->
        if (socket.inetAddress is Inet6Address) {
            Os.setsockoptInt(pfd.fileDescriptor, OsConstants.IPPROTO_IPV6, OsConstants.IPV6_UNICAST_HOPS, ttl)
        } else {
            Os.setsockoptInt(pfd.fileDescriptor, OsConstants.IPPROTO_IP, OsConstants.IP_TTL, ttl)
        }
    }
    true
}.getOrDefault(false)
