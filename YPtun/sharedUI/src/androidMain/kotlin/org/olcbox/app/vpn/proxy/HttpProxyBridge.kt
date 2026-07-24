package org.olcbox.app.vpn.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Tiny HTTP/HTTPS proxy that forwards everything through a local SOCKS5. Browsers (Chrome, the Android
 * Wi-Fi proxy, desktop-over-LAN) speak an HTTP proxy, NOT SOCKS — so a SOCKS-only listener is silently
 * bypassed and leaks the real IP. This bridge gives the app's local proxy a real HTTP port, exactly like
 * Happ's local-proxy mode (SOCKS5 Port + HTTP Port). Engine-agnostic: it just dials the core's local
 * SOCKS5 ([socksHost]:[socksPort]), so it works for xray / sing-box / olcRTC / VK-TURN / dnstt alike.
 *
 * Started ONLY in Proxy mode (TUN mode never raises any HTTP listener). The SOCKS is no-auth in Proxy
 * mode, so no credentials are forwarded. Bind to 0.0.0.0 to be reachable from loopback AND the LAN.
 */
class HttpProxyBridge(
    private val listenHost: String,
    private val listenPort: Int,
    private val socksHost: String,
    private val socksPort: Int,
    private val log: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var server: ServerSocket? = null

    fun start(): Boolean {
        return try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(listenHost, listenPort))
            server = s
            scope.launch { acceptLoop(s) }
            true
        } catch (e: Exception) {
            log("HTTP proxy: failed to bind $listenHost:$listenPort — ${e.message}")
            false
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        scope.cancel()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (true) {
            val client = try {
                s.accept()
            } catch (e: Exception) {
                return // listener closed
            }
            scope.launch {
                runCatching { handle(client) }.onFailure { runCatching { client.close() } }
            }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = CLIENT_TIMEOUT_MS
        val cin = client.getInputStream()
        val cout = client.getOutputStream()

        val head = readHttpHead(cin) ?: run { client.close(); return }
        val firstLine = head.substringBefore("\r\n")
        val tokens = firstLine.split(' ')
        if (tokens.size < 3) { client.close(); return }
        val method = tokens[0].uppercase()
        val target = tokens[1]
        val version = tokens[2]

        if (method == "CONNECT") {
            // HTTPS: client wants a raw tunnel to host:port.
            val (host, port) = splitHostPort(target, 443)
            val remote = dialViaSocks(host, port)
            if (remote == null) {
                writeError(cout, "502 Bad Gateway"); client.close(); return
            }
            cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
            cout.flush()
            client.soTimeout = 0
            pipe(client, remote)
        } else {
            // Plain HTTP: absolute-form request line (GET http://host/path …). Dial host:port, rewrite the
            // request line to origin-form, forward the (already-read) head and then stream the rest.
            val hostPort = hostPortFromAbsoluteUri(target) ?: hostFromHeaders(head)
            if (hostPort == null) { writeError(cout, "400 Bad Request"); client.close(); return }
            val (host, port) = hostPort
            val remote = dialViaSocks(host, port)
            if (remote == null) { writeError(cout, "502 Bad Gateway"); client.close(); return }
            val originPath = originForm(target)
            val rewrittenHead = "$method $originPath $version\r\n" + head.substringAfter("\r\n")
            val rout = remote.getOutputStream()
            rout.write(rewrittenHead.toByteArray(Charsets.ISO_8859_1))
            rout.flush()
            client.soTimeout = 0
            pipe(client, remote)
        }
    }

    /** Opens the local SOCKS5 (no-auth) and CONNECTs to host:port (by domain). Null on any failure. */
    private fun dialViaSocks(host: String, port: Int): Socket? {
        return runCatching {
            val sock = Socket()
            sock.connect(InetSocketAddress(socksHost, socksPort), SOCKS_CONNECT_TIMEOUT_MS)
            sock.soTimeout = SOCKS_IO_TIMEOUT_MS
            val out = sock.getOutputStream()
            val input = sock.getInputStream()

            out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
            val method = ByteArray(2); readFully(input, method)
            if (method[0] != 0x05.toByte() || method[1] != 0x00.toByte()) { sock.close(); return null }

            val hostBytes = host.toByteArray(Charsets.US_ASCII)
            val req = ByteArray(7 + hostBytes.size)
            req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x03
            req[4] = hostBytes.size.toByte()
            System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
            req[5 + hostBytes.size] = (port shr 8).toByte()
            req[6 + hostBytes.size] = port.toByte()
            out.write(req); out.flush()

            val rep = ByteArray(4); readFully(input, rep)
            if (rep[1] != 0x00.toByte()) { sock.close(); return null }
            val boundLen = when (rep[3]) {
                0x01.toByte() -> 4 + 2
                0x04.toByte() -> 16 + 2
                0x03.toByte() -> { val l = ByteArray(1); readFully(input, l); (l[0].toInt() and 0xFF) + 2 }
                else -> { sock.close(); return null }
            }
            readFully(input, ByteArray(boundLen))
            sock.soTimeout = 0
            sock
        }.getOrNull()
    }

    private fun pipe(a: Socket, b: Socket) {
        val t = Thread { runCatching { a.getInputStream().copyTo(b.getOutputStream()) }; runCatching { b.shutdownOutput() } }
        t.isDaemon = true
        t.start()
        runCatching { b.getInputStream().copyTo(a.getOutputStream()) }
        runCatching { a.shutdownOutput() }
        runCatching { t.join(1000) }
        runCatching { a.close() }
        runCatching { b.close() }
    }

    private fun readHttpHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var last4 = 0
        while (buf.size() < MAX_HEAD_BYTES) {
            val c = input.read()
            if (c < 0) return if (buf.size() > 0) buf.toString("ISO-8859-1") else null
            buf.write(c)
            last4 = (last4 shl 8) or c
            if (last4 == 0x0D0A0D0A) return buf.toString("ISO-8859-1") // \r\n\r\n
        }
        return null
    }

    private fun writeError(out: java.io.OutputStream, status: String) {
        runCatching {
            out.write("HTTP/1.1 $status\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)); out.flush()
        }
    }

    private fun readFully(input: InputStream, b: ByteArray) {
        var n = 0
        while (n < b.size) {
            val r = input.read(b, n, b.size - n)
            if (r < 0) throw EOFException()
            n += r
        }
    }

    private fun splitHostPort(s: String, default: Int): Pair<String, Int> {
        val i = s.lastIndexOf(':')
        if (i <= 0 || i < s.lastIndexOf(']')) return s.trim('[', ']') to default // bare host or [ipv6]
        val host = s.substring(0, i).trim('[', ']')
        val port = s.substring(i + 1).toIntOrNull() ?: default
        return host to port
    }

    private fun hostPortFromAbsoluteUri(uri: String): Pair<String, Int>? {
        val schemeIdx = uri.indexOf("://")
        if (schemeIdx < 0) return null
        val rest = uri.substring(schemeIdx + 3)
        val authority = rest.substringBefore('/')
        if (authority.isEmpty()) return null
        return splitHostPort(authority, 80)
    }

    private fun hostFromHeaders(head: String): Pair<String, Int>? {
        val line = head.lineSequence().firstOrNull { it.startsWith("Host:", ignoreCase = true) } ?: return null
        val value = line.substringAfter(':').trim()
        return splitHostPort(value, 80)
    }

    private fun originForm(uri: String): String {
        val schemeIdx = uri.indexOf("://")
        if (schemeIdx < 0) return uri // already origin-form
        val rest = uri.substring(schemeIdx + 3)
        val slash = rest.indexOf('/')
        return if (slash < 0) "/" else rest.substring(slash)
    }

    companion object {
        private const val CLIENT_TIMEOUT_MS = 30_000
        private const val SOCKS_CONNECT_TIMEOUT_MS = 5_000
        private const val SOCKS_IO_TIMEOUT_MS = 20_000
        private const val MAX_HEAD_BYTES = 16 * 1024
    }
}
