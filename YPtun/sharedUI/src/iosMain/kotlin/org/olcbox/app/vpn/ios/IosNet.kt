package org.olcbox.app.vpn.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.coroutines.delay
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.connect
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class)
internal object IosNet {
    /** 127.0.0.1 as in_addr.s_addr (network byte order, read little-endian). */
    private const val LOOPBACK_NETWORK_ORDER = 0x0100007Fu

    /** True when something accepts TCP on 127.0.0.1:[port] (a refused loopback connect fails at once). */
    fun isLocalPortOpen(port: Int): Boolean {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        if (fd < 0) return false
        try {
            return memScoped {
                val addr = alloc<sockaddr_in>()
                addr.sin_family = AF_INET.convert()
                // htons: the port in network byte order (iOS is little-endian).
                addr.sin_port = (((port and 0xFF) shl 8) or ((port shr 8) and 0xFF)).convert()
                addr.sin_addr.s_addr = LOOPBACK_NETWORK_ORDER
                connect(fd, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) == 0
            }
        } finally {
            close(fd)
        }
    }

    suspend fun awaitLocalPortOpen(port: Int, timeoutMs: Int): Boolean {
        val deadline = TimeSource.Monotonic.markNow()
        while (deadline.elapsedNow().inWholeMilliseconds < timeoutMs) {
            if (isLocalPortOpen(port)) return true
            delay(30)
        }
        return false
    }
}
