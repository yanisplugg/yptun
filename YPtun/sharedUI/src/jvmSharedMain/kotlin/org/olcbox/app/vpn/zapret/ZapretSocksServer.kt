package org.olcbox.app.vpn.zapret

import org.olcbox.app.data.model.ZapretConfig
import org.olcbox.app.vpn.PingProbeHelpers
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/**
 * In-process userspace DPI-bypass SOCKS5 + UDP-Associate proxy ([org.olcbox.app.data.model.EngineType.Zapret]).
 *
 * Requires no root privileges or kernel drivers and works across Android (via `VpnService.protect`)
 * and Desktop (Windows/Linux Proxy & TUN):
 * - Splits TLS ClientHello into multiple valid TLS record frames and/or small TCP segments (`TCP_NODELAY`),
 * - Applies HTTP `Host:` header desync on plain HTTP,
 * - Optionally blocks UDP/443 (QUIC) so clients fall back to TCP HTTP/2 through TLS desync,
 * - Resolves domain targets via protected DoH/UDP DNS to avoid ISP DNS spoofing.
 */
class ZapretSocksServer {

    private val running = AtomicBoolean(false)
    private val dnsTxId = AtomicInteger(1)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var executor: ExecutorService? = null
    private var activeConfig: ZapretConfig = ZapretConfig()
    private val dnsCache = ConcurrentHashMap<String, CachedDns>()
    private var protectTcp: (Socket) -> Unit = {}
    private var protectUdp: (DatagramSocket) -> Unit = {}
    private var logFn: (String) -> Unit = {}

    val isRunning: Boolean
        get() = running.get() && serverSocket?.isClosed == false

    @Synchronized
    fun start(
        config: ZapretConfig,
        listenHost: String,
        listenPort: Int,
        socksUsername: String = "",
        socksPassword: String = "",
        protectSocket: (Socket) -> Unit = {},
        protectDatagram: (DatagramSocket) -> Unit = {},
        log: (String) -> Unit = {},
    ) {
        stop()
        val cfg = config.normalized()
        activeConfig = cfg
        protectTcp = protectSocket
        protectUdp = protectDatagram
        logFn = log
        dnsCache.clear()

        val bindAddr = InetAddress.getByName(listenHost.ifBlank { "127.0.0.1" })
        val ss = ServerSocket(listenPort, 128, bindAddr)
        serverSocket = ss
        val pool = Executors.newCachedThreadPool { r ->
            Thread(r, "zapret-worker").apply { isDaemon = true }
        }
        executor = pool
        running.set(true)

        val accept = Thread({
            log("Zapret listening on ${bindAddr.hostAddress}:$listenPort (${cfg.summary()}, dns=${cfg.dnsServer})")
            while (running.get() && !ss.isClosed) {
                val client = try {
                    ss.accept()
                } catch (_: Exception) {
                    break
                }
                pool.execute {
                    handleClient(client, cfg, socksUsername, socksPassword)
                }
            }
        }, "zapret-accept").apply { isDaemon = true }
        acceptThread = accept
        accept.start()
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { acceptThread?.interrupt() }
        acceptThread = null
        runCatching { executor?.shutdownNow() }
        executor = null
        dnsCache.clear()
    }

    private fun handleClient(
        client: Socket,
        cfg: ZapretConfig,
        username: String,
        password: String,
    ) {
        client.use { c ->
            runCatching {
                c.tcpNoDelay = true
                c.soTimeout = HANDSHAKE_TIMEOUT_MS
                val inp = c.getInputStream()
                val out = c.getOutputStream()

                if (!negotiateAuth(inp, out, username, password)) return

                val reqHead = readExactly(inp, 4)
                if (reqHead[0].toInt() and 0xFF != 0x05) return
                val cmd = reqHead[1].toInt() and 0xFF
                val atyp = reqHead[3].toInt() and 0xFF
                val dstHost = readSocksAddr(inp, atyp) ?: run {
                    writeSocksReply(out, 0x08)
                    return
                }
                val portBytes = readExactly(inp, 2)
                val dstPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

                when (cmd) {
                    0x01 -> handleTcpConnect(c, inp, out, dstHost, dstPort, cfg)
                    0x03 -> handleUdpAssociate(c, inp, out, cfg)
                    else -> writeSocksReply(out, 0x07)
                }
            }
        }
    }

    private fun negotiateAuth(
        inp: InputStream,
        out: OutputStream,
        username: String,
        password: String,
    ): Boolean {
        val ver = inp.read()
        if (ver != 0x05) return false
        val nMethods = inp.read()
        if (nMethods <= 0) return false
        val methods = readExactly(inp, nMethods)
        if (username.isBlank()) {
            if (methods.none { (it.toInt() and 0xFF) == 0x00 }) {
                out.write(byteArrayOf(0x05, 0xFF.toByte()))
                out.flush()
                return false
            }
            out.write(byteArrayOf(0x05, 0x00))
            out.flush()
            return true
        }
        if (methods.none { (it.toInt() and 0xFF) == 0x02 }) {
            out.write(byteArrayOf(0x05, 0xFF.toByte()))
            out.flush()
            return false
        }
        out.write(byteArrayOf(0x05, 0x02))
        out.flush()

        val authVer = inp.read()
        if (authVer != 0x01) return false
        val uLen = inp.read()
        if (uLen < 0) return false
        val u = readExactly(inp, uLen).decodeToString()
        val pLen = inp.read()
        if (pLen < 0) return false
        val p = readExactly(inp, pLen).decodeToString()
        val ok = u == username && p == password
        out.write(byteArrayOf(0x01, if (ok) 0x00 else 0x01))
        out.flush()
        return ok
    }

    private fun handleTcpConnect(
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        dstHost: String,
        dstPort: Int,
        cfg: ZapretConfig,
    ) {
        if (dstPort !in 1..65535 || dstHost.isBlank()) {
            writeSocksReply(clientOut, 0x01)
            return
        }
        val targetAddr = resolveAddress(dstHost, cfg.dnsServer) ?: run {
            writeSocksReply(clientOut, 0x04)
            return
        }

        val remote = Socket()
        try {
            remote.bind(InetSocketAddress(0))
            protectTcp(remote)
            remote.tcpNoDelay = true
            remote.connect(InetSocketAddress(targetAddr, dstPort), CONNECT_TIMEOUT_MS)
        } catch (_: Exception) {
            runCatching { remote.close() }
            writeSocksReply(clientOut, 0x05)
            return
        }

        remote.use { r ->
            client.soTimeout = 0
            r.soTimeout = 0
            writeSocksReply(clientOut, 0x00, r.localAddress, r.localPort)

            val remoteIn = r.getInputStream()
            val remoteOut = r.getOutputStream()

            val downTask = executor?.submit {
                pump(remoteIn, clientOut, onDone = { runCatching { client.shutdownOutput() } })
            }

            try {
                val firstBuf = ByteArray(RELAY_BUF_SIZE)
                val firstLen = clientIn.read(firstBuf)
                if (firstLen > 0) {
                    val sniffed = sniffHost(firstBuf, firstLen)
                    if (shouldDesync(cfg, dstHost, sniffed)) {
                        writeDesyncedInitial(remoteOut, firstBuf, firstLen, cfg)
                    } else {
                        remoteOut.write(firstBuf, 0, firstLen)
                        remoteOut.flush()
                    }
                    pump(clientIn, remoteOut, onDone = { runCatching { r.shutdownOutput() } })
                } else {
                    runCatching { r.shutdownOutput() }
                }
            } finally {
                runCatching { downTask?.get() }
            }
        }
    }

    private fun handleUdpAssociate(
        controlSocket: Socket,
        controlIn: InputStream,
        controlOut: OutputStream,
        cfg: ZapretConfig,
    ) {
        val relay = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val outbound = DatagramSocket()
        try {
            protectUdp(outbound)
            writeSocksReply(controlOut, 0x00, relay.localAddress, relay.localPort)
            controlSocket.soTimeout = 0

            val lastClient = AtomicReference<InetSocketAddress?>(null)
            val seenTargets = ConcurrentHashMap.newKeySet<String>()
            val resolvedCache = ConcurrentHashMap<String, InetAddress>()
            val open = AtomicBoolean(true)

            val returnTask = executor?.submit {
                val buf = ByteArray(MAX_UDP_PACKET)
                while (open.get() && running.get() && !relay.isClosed && !outbound.isClosed) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        outbound.receive(pkt)
                    } catch (_: Exception) {
                        break
                    }
                    val clientEp = lastClient.get() ?: continue
                    val wrapped = buildUdpSocksPacket(pkt.address, pkt.port, pkt.data, pkt.offset, pkt.length)
                    runCatching {
                        relay.send(DatagramPacket(wrapped, wrapped.size, clientEp))
                    }.onFailure { break }
                }
            }

            val forwardTask = executor?.submit {
                val buf = ByteArray(MAX_UDP_PACKET)
                while (open.get() && running.get() && !relay.isClosed && !outbound.isClosed) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        relay.receive(pkt)
                    } catch (_: Exception) {
                        break
                    }
                    lastClient.set(InetSocketAddress(pkt.address, pkt.port))
                    val parsed = parseUdpSocksPacket(pkt.data, pkt.offset, pkt.length) ?: continue
                    if (cfg.blockQuic && parsed.port == 443) {
                        // Drop QUIC UDP/443 so apps fall back cleanly to TCP TLS-desync HTTP/2.
                        continue
                    }
                    val dstAddr = resolvedCache[parsed.host] ?: resolveAddress(parsed.host, cfg.dnsServer)?.also {
                        resolvedCache[parsed.host] = it
                    } ?: continue
                    val key = "${dstAddr.hostAddress}:${parsed.port}"
                    if (cfg.udpFakeCount > 0 && parsed.port != 53 && seenTargets.add(key)) {
                        repeat(cfg.udpFakeCount) {
                            val dummy = ByteArray(Random.nextInt(16, 48))
                            runCatching {
                                outbound.send(DatagramPacket(dummy, dummy.size, dstAddr, parsed.port))
                            }
                        }
                    }
                    runCatching {
                        outbound.send(DatagramPacket(parsed.payload, parsed.payload.size, dstAddr, parsed.port))
                    }
                }
            }

            // Hold control socket open until the client closes the UDP association.
            val tmp = ByteArray(64)
            while (running.get()) {
                val r = try {
                    controlIn.read(tmp)
                } catch (_: Exception) {
                    -1
                }
                if (r < 0) break
            }

            open.set(false)
            runCatching { relay.close() }
            runCatching { outbound.close() }
            runCatching { forwardTask?.cancel(true) }
            runCatching { returnTask?.cancel(true) }
        } finally {
            runCatching { relay.close() }
            runCatching { outbound.close() }
        }
    }

    private fun resolveAddress(host: String, dnsServer: String): InetAddress? {
        val clean = host.trim().trim('[', ']')
        if (clean.isEmpty()) return null
        if (PingProbeHelpers.isIpLiteral(clean)) {
            return runCatching { InetAddress.getByName(clean) }.getOrNull()
        }
        val key = clean.lowercase()
        val now = System.currentTimeMillis()
        dnsCache[key]?.takeIf { it.expiresAtMs > now }?.let { return it.address }

        val resolved = queryProtectedDns(clean, dnsServer)
            ?: runCatching { PingProbeHelpers.resolveAddressesPreferIpv4(clean).firstOrNull() }.getOrNull()
        if (resolved != null) {
            if (dnsCache.size > MAX_DNS_CACHE) dnsCache.clear()
            dnsCache[key] = CachedDns(resolved, now + DNS_CACHE_TTL_MS)
        }
        return resolved
    }

    private fun queryProtectedDns(domain: String, dnsServer: String): InetAddress? {
        val trimmed = dnsServer.trim()
        if (trimmed.isEmpty()) return null
        val query = buildDnsQuery(domain, dnsTxId.getAndIncrement() and 0xFFFF) ?: return null
        return try {
            if (trimmed.startsWith("https://", ignoreCase = true)) {
                queryDoh(trimmed, query)
            } else {
                queryUdpDns(trimmed, query)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun queryDoh(dohUrl: String, wireQuery: ByteArray): InetAddress? {
        val uri = URI(dohUrl)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val path = uri.rawPath.ifBlank { "/dns-query" } + (uri.rawQuery?.let { "?$it" } ?: "")
        val bootstrapIp = bootstrapDohHost(host)

        Socket().use { raw ->
            raw.bind(InetSocketAddress(0))
            protectTcp(raw)
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(bootstrapIp, port), DNS_TIMEOUT_MS)
            raw.soTimeout = DNS_TIMEOUT_MS
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val tls = factory.createSocket(raw, host, port, true) as SSLSocket
            tls.use { s ->
                s.soTimeout = DNS_TIMEOUT_MS
                if (!PingProbeHelpers.isIpLiteral(host)) {
                    s.sslParameters = s.sslParameters.apply {
                        serverNames = listOf(SNIHostName(host))
                        endpointIdentificationAlgorithm = "HTTPS"
                    }
                }
                s.startHandshake()
                val header = buildString {
                    append("POST ").append(path).append(" HTTP/1.1\r\n")
                    append("Host: ").append(host).append("\r\n")
                    append("Content-Type: application/dns-message\r\n")
                    append("Accept: application/dns-message\r\n")
                    append("Content-Length: ").append(wireQuery.size).append("\r\n")
                    append("Connection: close\r\n\r\n")
                }.toByteArray(Charsets.US_ASCII)
                val out = s.getOutputStream()
                out.write(header)
                out.write(wireQuery)
                out.flush()

                val respBytes = s.getInputStream().readBytes()
                val bodyStart = findHttpBodyOffset(respBytes)
                if (bodyStart < 0 || bodyStart >= respBytes.size) return null
                return parseDnsAResponse(respBytes, bodyStart, respBytes.size - bodyStart)
            }
        }
    }

    private fun queryUdpDns(serverSpec: String, wireQuery: ByteArray): InetAddress? {
        val clean = serverSpec.removePrefix("udp://").trim()
        val host = clean.substringBefore(':').trim('[', ']')
        val port = clean.substringAfter(':', "").toIntOrNull()?.takeIf { it in 1..65535 } ?: 53
        val addr = InetAddress.getByName(host)
        DatagramSocket().use { ds ->
            ds.soTimeout = DNS_TIMEOUT_MS
            protectUdp(ds)
            ds.send(DatagramPacket(wireQuery, wireQuery.size, addr, port))
            val resp = ByteArray(1500)
            val pkt = DatagramPacket(resp, resp.size)
            ds.receive(pkt)
            return parseDnsAResponse(pkt.data, 0, pkt.length)
        }
    }

    private fun bootstrapDohHost(host: String): InetAddress = when (host.lowercase()) {
        "cloudflare-dns.com", "one.one.one.one", "1.1.1.1" -> InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))
        "dns.google", "8.8.8.8" -> InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
        "dns.quad9.net", "9.9.9.9" -> InetAddress.getByAddress(byteArrayOf(9, 9, 9, 9))
        else -> InetAddress.getByName(host)
    }

    private fun pump(inp: InputStream, out: OutputStream, onDone: () -> Unit) {
        val buf = ByteArray(RELAY_BUF_SIZE)
        try {
            while (running.get()) {
                val n = inp.read(buf)
                if (n < 0) break
                if (n > 0) {
                    out.write(buf, 0, n)
                    out.flush()
                }
            }
        } catch (_: Exception) {
        } finally {
            onDone()
        }
    }

    private fun readExactly(inp: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(buf, off, n - off)
            if (r < 0) throw EOFException("short SOCKS5 read")
            off += r
        }
        return buf
    }

    private fun readSocksAddr(inp: InputStream, atyp: Int): String? = when (atyp) {
        0x01 -> InetAddress.getByAddress(readExactly(inp, 4)).hostAddress
        0x04 -> InetAddress.getByAddress(readExactly(inp, 16)).hostAddress
        0x03 -> {
            val len = inp.read()
            if (len <= 0) null else readExactly(inp, len).decodeToString()
        }
        else -> null
    }

    private fun writeSocksReply(
        out: OutputStream,
        rep: Int,
        addr: InetAddress? = null,
        port: Int = 0,
    ) {
        val ipBytes = (addr as? Inet4Address)?.address ?: byteArrayOf(0, 0, 0, 0)
        val resp = byteArrayOf(
            0x05,
            rep.toByte(),
            0x00,
            0x01,
            ipBytes[0],
            ipBytes[1],
            ipBytes[2],
            ipBytes[3],
            ((port shr 8) and 0xFF).toByte(),
            (port and 0xFF).toByte(),
        )
        out.write(resp)
        out.flush()
    }

    private data class CachedDns(val address: InetAddress, val expiresAtMs: Long)
    private data class UdpSocksPacket(val host: String, val port: Int, val payload: ByteArray)

    private fun parseUdpSocksPacket(data: ByteArray, offset: Int, length: Int): UdpSocksPacket? {
        if (length < 8) return null
        if (data[offset + 2].toInt() != 0x00) return null // no fragmentation support
        var pos = offset + 4
        val end = offset + length
        val host = when (data[offset + 3].toInt() and 0xFF) {
            0x01 -> {
                if (pos + 4 + 2 > end) return null
                InetAddress.getByAddress(data.copyOfRange(pos, pos + 4)).hostAddress.also { pos += 4 }
            }
            0x04 -> {
                if (pos + 16 + 2 > end) return null
                InetAddress.getByAddress(data.copyOfRange(pos, pos + 16)).hostAddress.also { pos += 16 }
            }
            0x03 -> {
                if (pos + 1 > end) return null
                val l = data[pos].toInt() and 0xFF
                pos++
                if (l == 0 || pos + l + 2 > end) return null
                data.copyOfRange(pos, pos + l).decodeToString().also { pos += l }
            }
            else -> return null
        } ?: return null
        val port = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
        pos += 2
        if (port !in 1..65535) return null
        return UdpSocksPacket(host, port, data.copyOfRange(pos, end))
    }

    private fun buildUdpSocksPacket(
        src: InetAddress,
        port: Int,
        data: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray {
        val addrBytes = src.address
        val atyp = if (addrBytes.size == 16) 0x04 else 0x01
        val out = ByteArray(4 + addrBytes.size + 2 + length)
        out[0] = 0x00
        out[1] = 0x00
        out[2] = 0x00
        out[3] = atyp.toByte()
        addrBytes.copyInto(out, 4)
        out[4 + addrBytes.size] = ((port shr 8) and 0xFF).toByte()
        out[5 + addrBytes.size] = (port and 0xFF).toByte()
        data.copyInto(out, 6 + addrBytes.size, offset, offset + length)
        return out
    }

    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 10_000
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val DNS_TIMEOUT_MS = 4_000
        private const val DNS_CACHE_TTL_MS = 120_000L
        private const val MAX_DNS_CACHE = 512
        private const val RELAY_BUF_SIZE = 32 * 1024
        private const val MAX_UDP_PACKET = 64 * 1024

        internal fun shouldDesync(cfg: ZapretConfig, dstHost: String, sniffedHost: String?): Boolean {
            val suffixes = cfg.targetSuffixes()
            if (suffixes.isEmpty()) return true
            val candidates = listOfNotNull(sniffedHost, dstHost)
                .map { it.trim().trimEnd('.').lowercase() }
                .filter { it.isNotEmpty() && !PingProbeHelpers.isIpLiteral(it) }
            if (candidates.isEmpty()) return false
            return candidates.any { host ->
                suffixes.any { suffix -> host == suffix || host.endsWith(".$suffix") }
            }
        }

        internal fun writeDesyncedInitial(
            out: OutputStream,
            buf: ByteArray,
            len: Int,
            cfg: ZapretConfig,
        ) {
            if (isTlsClientHello(buf, len)) {
                val framed = if (cfg.tlsRecSplit) splitTlsClientHelloRecords(buf, len, cfg.splitSize) else buf.copyOf(len)
                if (cfg.tcpSplit) {
                    writeSegmented(out, framed, framed.size, cfg.splitSize, cfg.splitCount, cfg.splitDelayMs)
                } else if (cfg.tlsRecSplit && framed.size > len) {
                    // Flush each TLS record separately so the DPI sees separate record writes.
                    val firstRecLen = 5 + (((framed[3].toInt() and 0xFF) shl 8) or (framed[4].toInt() and 0xFF))
                    if (firstRecLen in 6 until framed.size) {
                        out.write(framed, 0, firstRecLen)
                        out.flush()
                        if (cfg.splitDelayMs > 0) runCatching { Thread.sleep(cfg.splitDelayMs.toLong()) }
                        out.write(framed, firstRecLen, framed.size - firstRecLen)
                        out.flush()
                        return
                    }
                    out.write(framed)
                    out.flush()
                } else {
                    out.write(framed)
                    out.flush()
                }
                return
            }

            if (isHttpRequest(buf, len)) {
                val payload = if (cfg.httpHostTricks) mutateHttpHostHeader(buf, len) else buf.copyOf(len)
                if (cfg.tcpSplit) {
                    writeSegmented(out, payload, payload.size, cfg.splitSize, cfg.splitCount, cfg.splitDelayMs)
                } else {
                    out.write(payload)
                    out.flush()
                }
                return
            }

            if (cfg.tcpSplit) {
                writeSegmented(out, buf, len, cfg.splitSize, cfg.splitCount, cfg.splitDelayMs)
            } else {
                out.write(buf, 0, len)
                out.flush()
            }
        }

        internal fun isTlsClientHello(buf: ByteArray, len: Int): Boolean =
            len >= 10 &&
                (buf[0].toInt() and 0xFF) == 0x16 &&
                (buf[1].toInt() and 0xFF) == 0x03 &&
                (buf[5].toInt() and 0xFF) == 0x01

        /**
         * Splits a single TLS ClientHello record into two valid TLS record frames (inside SNI if found,
         * or at [splitOffset] otherwise), preserving the exact reassembled handshake stream.
         */
        internal fun splitTlsClientHelloRecords(buf: ByteArray, len: Int, splitOffset: Int): ByteArray {
            if (!isTlsClientHello(buf, len)) return buf.copyOf(len)
            val recLen = ((buf[3].toInt() and 0xFF) shl 8) or (buf[4].toInt() and 0xFF)
            if (recLen < 2 || 5 + recLen > len) return buf.copyOf(len)

            val sniSpan = findTlsSniPayloadRange(buf, 5, 5 + recLen)
            val splitAt = when {
                sniSpan != null && sniSpan.last - sniSpan.first >= 2 -> {
                    val mid = (sniSpan.first + sniSpan.last) / 2
                    (mid - 5).coerceIn(1, recLen - 1)
                }
                else -> splitOffset.coerceIn(1, recLen - 1)
            }
            val secondRecLen = recLen - splitAt
            val out = ByteArrayOutputStream(len + 5)
            // Record 1
            out.write(0x16)
            out.write(buf[1].toInt() and 0xFF)
            out.write(buf[2].toInt() and 0xFF)
            out.write((splitAt shr 8) and 0xFF)
            out.write(splitAt and 0xFF)
            out.write(buf, 5, splitAt)
            // Record 2
            out.write(0x16)
            out.write(buf[1].toInt() and 0xFF)
            out.write(buf[2].toInt() and 0xFF)
            out.write((secondRecLen shr 8) and 0xFF)
            out.write(secondRecLen and 0xFF)
            out.write(buf, 5 + splitAt, secondRecLen)
            // Any remaining bytes after the first TLS record
            val tailOffset = 5 + recLen
            if (tailOffset < len) {
                out.write(buf, tailOffset, len - tailOffset)
            }
            return out.toByteArray()
        }

        private fun writeSegmented(
            out: OutputStream,
            buf: ByteArray,
            len: Int,
            chunkSize: Int,
            maxSegments: Int,
            delayMs: Int,
        ) {
            val step = chunkSize.coerceIn(1, 64)
            var off = 0
            var count = 0
            while (off < len && count < maxSegments - 1) {
                val n = minOf(step, len - off)
                out.write(buf, off, n)
                out.flush()
                off += n
                count++
                if (off < len && delayMs > 0) {
                    runCatching { Thread.sleep(delayMs.toLong()) }
                }
            }
            if (off < len) {
                out.write(buf, off, len - off)
                out.flush()
            }
        }

        internal fun isHttpRequest(buf: ByteArray, len: Int): Boolean {
            if (len < 12) return false
            val head = buf.copyOfRange(0, minOf(len, 16)).decodeToString()
            return head.startsWith("GET ") || head.startsWith("POST ") ||
                head.startsWith("HEAD ") || head.startsWith("PUT ") ||
                head.startsWith("DELETE ") || head.startsWith("OPTIONS ") ||
                head.startsWith("PATCH ")
        }

        internal fun mutateHttpHostHeader(buf: ByteArray, len: Int): ByteArray {
            val copy = buf.copyOf(len)
            val marker = "\r\nHost:".toByteArray(Charsets.US_ASCII)
            val alt = "\r\nhOsT:".toByteArray(Charsets.US_ASCII)
            for (i in 0..(len - marker.size)) {
                var matched = true
                for (j in marker.indices) {
                    val a = copy[i + j].toInt().toChar().lowercaseChar()
                    val b = marker[j].toInt().toChar().lowercaseChar()
                    if (a != b) {
                        matched = false
                        break
                    }
                }
                if (matched) {
                    alt.copyInto(copy, i)
                    break
                }
            }
            return copy
        }

        internal fun sniffHost(buf: ByteArray, len: Int): String? {
            if (isTlsClientHello(buf, len)) {
                val recLen = ((buf[3].toInt() and 0xFF) shl 8) or (buf[4].toInt() and 0xFF)
                val end = minOf(len, 5 + recLen)
                val span = findTlsSniPayloadRange(buf, 5, end) ?: return null
                return buf.copyOfRange(span.first, span.last + 1).decodeToString()
            }
            if (isHttpRequest(buf, len)) {
                val text = buf.copyOfRange(0, minOf(len, 2048)).decodeToString()
                val hostLine = text.lineSequence().firstOrNull {
                    it.startsWith("Host:", ignoreCase = true)
                } ?: return null
                return hostLine.substringAfter(':').trim().substringBefore(':').ifBlank { null }
            }
            return null
        }

        private fun findTlsSniPayloadRange(buf: ByteArray, start: Int, end: Int): IntRange? {
            // Handshake header(4) + version(2) + random(32)
            var p = start + 4 + 2 + 32
            if (p >= end) return null
            val sessLen = buf[p].toInt() and 0xFF
            p += 1 + sessLen
            if (p + 2 > end) return null
            val cipherLen = ((buf[p].toInt() and 0xFF) shl 8) or (buf[p + 1].toInt() and 0xFF)
            p += 2 + cipherLen
            if (p >= end) return null
            val compLen = buf[p].toInt() and 0xFF
            p += 1 + compLen
            if (p + 2 > end) return null
            val extEnd = minOf(end, p + 2 + (((buf[p].toInt() and 0xFF) shl 8) or (buf[p + 1].toInt() and 0xFF)))
            p += 2
            while (p + 4 <= extEnd) {
                val extType = ((buf[p].toInt() and 0xFF) shl 8) or (buf[p + 1].toInt() and 0xFF)
                val extLen = ((buf[p + 2].toInt() and 0xFF) shl 8) or (buf[p + 3].toInt() and 0xFF)
                p += 4
                if (p + extLen > extEnd) break
                if (extType == 0x0000 && extLen >= 5) {
                    val listLen = ((buf[p].toInt() and 0xFF) shl 8) or (buf[p + 1].toInt() and 0xFF)
                    var q = p + 2
                    val listEnd = minOf(p + extLen, q + listLen)
                    while (q + 3 <= listEnd) {
                        val nameType = buf[q].toInt() and 0xFF
                        val nameLen = ((buf[q + 1].toInt() and 0xFF) shl 8) or (buf[q + 2].toInt() and 0xFF)
                        q += 3
                        if (q + nameLen > listEnd) break
                        if (nameType == 0x00 && nameLen > 0) {
                            return q until (q + nameLen)
                        }
                        q += nameLen
                    }
                }
                p += extLen
            }
            return null
        }

        internal fun buildDnsQuery(domain: String, id: Int): ByteArray? {
            val labels = domain.trimEnd('.').split('.')
            if (labels.isEmpty() || labels.any { it.isEmpty() || it.length > 63 }) return null
            val out = ByteArrayOutputStream(64)
            // Header: ID, RD=1, QDCOUNT=1
            out.write((id shr 8) and 0xFF); out.write(id and 0xFF)
            out.write(0x01); out.write(0x00)
            out.write(0x00); out.write(0x01)
            out.write(0x00); out.write(0x00)
            out.write(0x00); out.write(0x00)
            out.write(0x00); out.write(0x00)
            for (l in labels) {
                val b = l.toByteArray(Charsets.US_ASCII)
                out.write(b.size)
                out.write(b)
            }
            out.write(0x00)
            // QTYPE=A(1), QCLASS=IN(1)
            out.write(0x00); out.write(0x01)
            out.write(0x00); out.write(0x01)
            return out.toByteArray()
        }

        internal fun parseDnsAResponse(pkt: ByteArray, offset: Int, length: Int): InetAddress? {
            if (length < 12) return null
            val end = offset + length
            val qd = ((pkt[offset + 4].toInt() and 0xFF) shl 8) or (pkt[offset + 5].toInt() and 0xFF)
            val an = ((pkt[offset + 6].toInt() and 0xFF) shl 8) or (pkt[offset + 7].toInt() and 0xFF)
            var pos = offset + 12
            repeat(qd) {
                pos = skipDnsName(pkt, pos, end)
                if (pos < 0 || pos + 4 > end) return null
                pos += 4
            }
            repeat(an) {
                pos = skipDnsName(pkt, pos, end)
                if (pos < 0 || pos + 10 > end) return null
                val rtype = ((pkt[pos].toInt() and 0xFF) shl 8) or (pkt[pos + 1].toInt() and 0xFF)
                val rdLen = ((pkt[pos + 8].toInt() and 0xFF) shl 8) or (pkt[pos + 9].toInt() and 0xFF)
                pos += 10
                if (pos + rdLen > end) return null
                if (rtype == 1 && rdLen == 4) {
                    return InetAddress.getByAddress(pkt.copyOfRange(pos, pos + 4))
                }
                pos += rdLen
            }
            return null
        }

        private fun skipDnsName(pkt: ByteArray, start: Int, end: Int): Int {
            var p = start
            var steps = 0
            while (p < end && steps++ < 128) {
                val b = pkt[p].toInt() and 0xFF
                if (b == 0) return p + 1
                if ((b and 0xC0) == 0xC0) {
                    return if (p + 2 <= end) p + 2 else -1
                }
                p += 1 + b
            }
            return -1
        }

        private fun findHttpBodyOffset(bytes: ByteArray): Int {
            for (i in 0..(bytes.size - 4)) {
                if (bytes[i] == '\r'.code.toByte() && bytes[i + 1] == '\n'.code.toByte() &&
                    bytes[i + 2] == '\r'.code.toByte() && bytes[i + 3] == '\n'.code.toByte()
                ) {
                    return i + 4
                }
            }
            return -1
        }
    }
}
