package org.olcbox.app.vpn.zapret

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.io.FileDescriptor
import java.lang.reflect.Field
import java.net.Inet6Address
import java.net.Socket

private interface Ws2_32 : StdCallLibrary {
    fun setsockopt(s: Pointer, level: Int, optname: Int, optval: Pointer, optlen: Int): Int

    companion object {
        val INSTANCE: Ws2_32? by lazy {
            runCatching { Native.load("ws2_32", Ws2_32::class.java) }.getOrNull()
        }
    }
}

private interface LibcSocket : Library {
    fun setsockopt(sockfd: Int, level: Int, optname: Int, optval: Pointer, optlen: Int): Int

    companion object {
        val INSTANCE: LibcSocket? by lazy {
            runCatching { Native.load("c", LibcSocket::class.java) }.getOrNull()
        }
    }
}

private fun findField(clazz: Class<*>, name: String): Field? {
    var cur: Class<*>? = clazz
    while (cur != null) {
        val f = runCatching { cur.getDeclaredField(name) }.getOrNull()
        if (f != null) {
            f.isAccessible = true
            return f
        }
        cur = cur.superclass
    }
    return null
}

private fun extractFileDescriptor(socket: Socket): FileDescriptor? = runCatching {
    val implField = findField(Socket::class.java, "impl") ?: return null
    var impl: Any? = implField.get(socket) ?: return null
    // Unwrap possible delegating SocketImpl wrappers (e.g. SocksSocketImpl)
    repeat(4) {
        val fdField = findField(impl!!.javaClass, "fd")
        val fd = fdField?.get(impl) as? FileDescriptor
        if (fd != null && fd.valid()) return fd
        val delegateField = findField(impl!!.javaClass, "delegate") ?: return@repeat
        impl = delegateField.get(impl) ?: return@repeat
    }
    findField(impl!!.javaClass, "fd")?.get(impl) as? FileDescriptor
}.getOrNull()

/**
 * Sets `IP_TTL` / `IPV6_UNICAST_HOPS` on a connected JVM [Socket] for Disorder DPI bypass.
 *
 * Requires `--add-opens=java.base/java.io=ALL-UNNAMED`,
 * `--add-opens=java.base/java.net=ALL-UNNAMED`, and
 * `--add-opens=java.base/sun.nio.ch=ALL-UNNAMED` on JDK 17+ (configured in desktopApp/test
 * jvmArgs); returns false gracefully if unavailable or unsupported.
 */
internal actual fun setZapretSocketTtl(socket: Socket, ttl: Int): Boolean = runCatching {
    val boundedTtl = ttl.coerceIn(1, 255)
    val fd = extractFileDescriptor(socket) ?: return false
    if (!fd.valid()) return false
    val isIpv6 = socket.inetAddress is Inet6Address
    val optBuf = Memory(4).apply { setInt(0, boundedTtl) }

    when {
        Platform.isWindows() -> {
            // OpenJDK on Windows stores WinSock SOCKET handles in FileDescriptor.fd (handle == -1).
            val handle = (findField(FileDescriptor::class.java, "handle")?.get(fd) as? Number)?.toLong() ?: -1L
            val sockFd = if (handle != -1L && handle != 0L) {
                handle
            } else {
                val rawFd = (findField(FileDescriptor::class.java, "fd")?.get(fd) as? Number)?.toInt() ?: -1
                if (rawFd >= 0) rawFd.toLong() and 0xFFFF_FFFFL else -1L
            }
            if (sockFd == -1L || sockFd == 0L) return false
            val lib = Ws2_32.INSTANCE ?: return false
            val level = if (isIpv6) 41 else 0 // IPPROTO_IPV6 : IPPROTO_IP
            val opt = 4 // WinSock IP_TTL == 4, IPV6_UNICAST_HOPS == 4
            lib.setsockopt(Pointer.createConstant(sockFd), level, opt, optBuf, 4) == 0
        }
        Platform.isLinux() -> {
            val rawFd = (findField(FileDescriptor::class.java, "fd")?.get(fd) as? Number)?.toInt() ?: -1
            if (rawFd < 0) return false
            val lib = LibcSocket.INSTANCE ?: return false
            val level = if (isIpv6) 41 else 0 // IPPROTO_IPV6 : IPPROTO_IP
            val opt = if (isIpv6) 16 else 2 // Linux IPV6_UNICAST_HOPS : IP_TTL
            lib.setsockopt(rawFd, level, opt, optBuf, 4) == 0
        }
        Platform.isMac() -> {
            val rawFd = (findField(FileDescriptor::class.java, "fd")?.get(fd) as? Number)?.toInt() ?: -1
            if (rawFd < 0) return false
            val lib = LibcSocket.INSTANCE ?: return false
            val level = if (isIpv6) 41 else 0
            val opt = 4 // Darwin IP_TTL == 4, IPV6_UNICAST_HOPS == 4
            lib.setsockopt(rawFd, level, opt, optBuf, 4) == 0
        }
        else -> false
    }
}.getOrDefault(false)
