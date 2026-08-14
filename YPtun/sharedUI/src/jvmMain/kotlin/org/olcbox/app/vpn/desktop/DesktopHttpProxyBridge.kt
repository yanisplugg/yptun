package org.olcbox.app.vpn.desktop

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
 * Tiny HTTP/HTTPS proxy in front of the core's local SOCKS5 — the desktop port of Android's
 * `HttpProxyBridge`.
 *
 * WHY proxy mode carried nothing: Windows' system proxy (and every browser that follows it) speaks
 * **HTTP**, not SOCKS. `startSystemProxy` pointed it straight at the core's local port, which only
 * answers HTTP when the core happens to be sing-box with a `mixed` inbound. Every other path —
 * xray-core (any routing profile, any raw config from a subscription, an xhttp cascade), olcRTC
 * Stealth, dnstt — publishes a SOCKS-only listener, so WinINET's `GET http://… HTTP/1.1` was
 * answered by a SOCKS greeting parser, every request failed, and the browser silently fell back to
 * a direct connection. That is "режим прокси вообще не работает".
 *
 * This bridge is engine-agnostic: it dials [socksHost]:[socksPort] itself, so the system proxy can
 * point at ONE stable HTTP port no matter which core ends up running. It also terminates SOCKS auth
 * ([socksUsername]/[socksPassword]) — WinINET cannot, which is the second reason proxy mode died
 * whenever the user had SOCKS credentials configured.
 */
internal class DesktopHttpProxyBridge(
    private val listenHost: String,
    private val listenPort: Int,
    private val socksHost: String,
    private val socksPort: Int,
    private val socksUsername: String = "",
    private val socksPassword: String = "",
    private val log: (String) -> Unit,
) {
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var server: ServerSocket? = null

    fun start(): Boolean {
        return try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(listenHost, listenPort))
            server = s
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch { acceptLoop(s) }
            true
        } catch (e: Exception) {
            log("HTTP proxy: failed to bind $listenHost:$listenPort — ${e.message}")
            false
        }
    }

    fun stop() {
        if (server == null) return
        runCatching { server?.close() }
        server = null
        runCatching { scope.cancel() }
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
            // HTTPS: the client wants a raw tunnel to host:port.
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
            // Plain HTTP: absolute-form request line (GET http://host/path …). Dial host:port, rewrite
            // the request line to origin-form, forward the (already-read) head and stream the rest.
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

    /**
     * Opens the local SOCKS5 and CONNECTs to host:port **by domain** (so the core's routing and the
     * exit server both still see the hostname). Null on any failure.
     */
    fun dialViaSocks(host: String, port: Int): Socket? {
        return runCatching {
            val sock = Socket()
            sock.connect(InetSocketAddress(socksHost, socksPort), SOCKS_CONNECT_TIMEOUT_MS)
            sock.soTimeout = SOCKS_IO_TIMEOUT_MS
            val out = sock.getOutputStream()
            val input = sock.getInputStream()

            val wantsAuth = socksUsername.isNotBlank()
            if (wantsAuth) {
                out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
            } else {
                out.write(byteArrayOf(0x05, 0x01, 0x00))
            }
            out.flush()
            val method = ByteArray(2); readFully(input, method)
            if (method[0] != 0x05.toByte()) { sock.close(); return null }
            when (method[1]) {
                0x00.toByte() -> Unit
                0x02.toByte() -> {
                    if (!wantsAuth) { sock.close(); return null }
                    val user = socksUsername.toByteArray(Charsets.UTF_8)
                    val pass = socksPassword.toByteArray(Charsets.UTF_8)
                    val auth = ByteArray(3 + user.size + pass.size)
                    auth[0] = 0x01
                    auth[1] = user.size.toByte()
                    System.arraycopy(user, 0, auth, 2, user.size)
                    auth[2 + user.size] = pass.size.toByte()
                    System.arraycopy(pass, 0, auth, 3 + user.size, pass.size)
                    out.write(auth); out.flush()
                    val reply = ByteArray(2); readFully(input, reply)
                    if (reply[1] != 0x00.toByte()) { sock.close(); return null }
                }
                else -> { sock.close(); return null }
            }

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
        val t = Thread {
            runCatching { a.getInputStream().copyTo(b.getOutputStream()) }
            runCatching { b.shutdownOutput() }
        }
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
            out.write("HTTP/1.1 $status\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
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

        /**
         * Offset of the HTTP port from the configured SOCKS port. Same +4 Android uses, and clear of
         * every other offset in play (+1 olcRTC chain, +2 AmneziaWG, +5 Trust Tunnel, +6/+7 the
         * sing-box front for Xray).
         */
        const val HTTP_PORT_OFFSET = 4

        fun httpPortFor(socksPort: Int): Int = socksPort + HTTP_PORT_OFFSET
    }
}
