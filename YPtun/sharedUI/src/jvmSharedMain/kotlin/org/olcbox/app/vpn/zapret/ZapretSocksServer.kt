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
import java.util.concurrent.Callable
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
 * - Supports named ALT strategies (`alt1`..`alt7`) plus live `auto` probing across real TLS targets,
 * - Splits TLS ClientHello into 2 or 3 valid TLS record frames and/or SNI-targeted TCP segments (`TCP_NODELAY`),
 * - Supports TCP OOB urgent byte injection (`sendUrgentData`) inside SNI (`ByeDPI`-style),
 * - Applies HTTP `Host:` header desync + SNI segmentation on plain HTTP,
 * - Optionally drops UDP/443 (QUIC) or sends dummy UDP packets before UDP flows,
 * - Resolves domain targets via fast Content-Length / chunked DoH + fallback DoH resolvers.
 */
class ZapretSocksServer {

    private val running = AtomicBoolean(false)
    private val dnsTxId = AtomicInteger(1)
    private val loggedErrors = AtomicInteger(0)
    private val tcpCount = AtomicInteger(0)
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
        loggedErrors.set(0)
        tcpCount.set(0)

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
            }.onFailure { e ->
                logLimited("Zapret client error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun logLimited(msg: String) {
        if (loggedErrors.incrementAndGet() <= MAX_LOGGED_ERRORS) {
            logFn(msg)
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
            logLimited("Zapret DNS failed for $dstHost (dns=${cfg.dnsServer})")
            writeSocksReply(clientOut, 0x04)
            return
        }

        val remote = Socket()
        try {
            val wildcard = if (targetAddr is Inet4Address) InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)) else null
            remote.bind(InetSocketAddress(wildcard, 0))
            protectTcp(remote)
            remote.tcpNoDelay = true
            remote.connect(InetSocketAddress(targetAddr, dstPort), CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            logLimited("Zapret connect failed $dstHost (${targetAddr.hostAddress}):$dstPort -> ${e.javaClass.simpleName}: ${e.message}")
            runCatching { remote.close() }
            writeSocksReply(clientOut, 0x05)
            return
        }
        val n = tcpCount.incrementAndGet()
        if (n <= 3) {
            logFn("Zapret TCP #$n $dstHost -> ${targetAddr.hostAddress}:$dstPort connected")
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
                val firstLen = readInitialFlight(client, clientIn, firstBuf)
                if (firstLen > 0) {
                    val sniffed = sniffHost(firstBuf, firstLen)
                    if (shouldDesync(cfg, dstHost, sniffed)) {
                        // Never send TCP OOB urgent byte to DoT port 853 (Android Private DNS rejects URG).
                        val effectiveCfg = if (dstPort == 853 && cfg.oob) cfg.copy(oob = false) else cfg
                        writeDesyncedInitial(remoteOut, firstBuf, firstLen, effectiveCfg, rawSocket = r)
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

    /**
     * Reads the initial client payload, finishing any incomplete first TLS ClientHello record so SNI
     * extraction and splitting always see the full ClientHello even if Chromium splits writes.
     */
    private fun readInitialFlight(socket: Socket, inp: InputStream, buf: ByteArray): Int {
        var total = inp.read(buf)
        if (total <= 0) return total
        if (total >= 5 && (buf[0].toInt() and 0xFF) == 0x16 && (buf[1].toInt() and 0xFF) == 0x03) {
            val recLen = ((buf[3].toInt() and 0xFF) shl 8) or (buf[4].toInt() and 0xFF)
            val target = minOf(buf.size, 5 + recLen)
            if (total < target) {
                val prevTimeout = runCatching { socket.soTimeout }.getOrDefault(0)
                runCatching { socket.soTimeout = INITIAL_TLS_READ_TIMEOUT_MS }
                try {
                    while (total < target) {
                        val n = inp.read(buf, total, target - total)
                        if (n <= 0) break
                        total += n
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { socket.soTimeout = prevTimeout }
                }
            }
        }
        return total
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

        val resolved = queryProtectedDnsWithFallback(clean, dnsServer, dnsTxId, protectTcp, protectUdp)
            ?: runCatching { PingProbeHelpers.resolveAddressesPreferIpv4(clean).firstOrNull() }.getOrNull()
        if (resolved != null) {
            if (dnsCache.size > MAX_DNS_CACHE) dnsCache.clear()
            dnsCache[key] = CachedDns(resolved, now + DNS_CACHE_TTL_MS)
        }
        return resolved
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
        if (data[offset + 2].toInt() != 0x00) return null
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
        private const val INITIAL_TLS_READ_TIMEOUT_MS = 350
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val PROBE_TIMEOUT_MS = 2_400
        private const val DNS_TIMEOUT_MS = 3_000
        private const val DNS_CACHE_TTL_MS = 120_000L
        private const val AUTO_CACHE_TTL_MS = 10 * 60_000L
        private const val MAX_DNS_CACHE = 512
        private const val RELAY_BUF_SIZE = 32 * 1024
        private const val MAX_UDP_PACKET = 64 * 1024
        private const val MAX_LOGGED_ERRORS = 25

        private val FALLBACK_DOH_URLS = listOf(
            "https://1.1.1.1/dns-query",
            "https://dns.google/dns-query",
            "https://9.9.9.9/dns-query",
        )

        private data class CachedAutoChoice(val key: String, val strategy: String, val expiresAtMs: Long)

        @Volatile
        private var cachedAutoChoice: CachedAutoChoice? = null
        private val sharedDnsTxId = AtomicInteger(100)

        fun invalidateAutoCache() {
            cachedAutoChoice = null
        }

        /**
         * Resolves the effective [ZapretConfig] for runtime use: if `strategy == STRATEGY_AUTO`, probes
         * ALT1..ALT7 against real TLS servers (or uses the fresh cache) and picks the best working ALT.
         */
        fun resolveRuntimeConfig(
            config: ZapretConfig,
            protectSocket: (Socket) -> Unit = {},
            protectDatagram: (DatagramSocket) -> Unit = {},
            forceRefresh: Boolean = false,
            log: (String) -> Unit = {},
        ): ZapretConfig {
            val norm = config.normalized()
            if (norm.strategy != ZapretConfig.STRATEGY_AUTO) return norm
            val result = probeAltStrategies(
                baseConfig = norm,
                forceRefresh = forceRefresh,
                protectSocket = protectSocket,
                protectDatagram = protectDatagram,
                log = log,
            )
            return result.resolvedConfig
        }

        /**
         * Live probes all [ZapretConfig.ALT_PRESETS] (`alt1`..`alt7`) in parallel against the configured
         * [ZapretConfig.probeHosts] and selects the highest-scoring working ALT strategy.
         */
        fun probeAltStrategies(
            baseConfig: ZapretConfig,
            forceRefresh: Boolean = true,
            protectSocket: (Socket) -> Unit = {},
            protectDatagram: (DatagramSocket) -> Unit = {},
            log: (String) -> Unit = {},
        ): ZapretAutoTuneResult {
            val norm = baseConfig.normalized()
            val hosts = norm.probeHosts()
            val cacheKey = "${norm.dnsServer}|${hosts.joinToString(",")}"
            val now = System.currentTimeMillis()
            if (!forceRefresh) {
                cachedAutoChoice?.takeIf { it.key == cacheKey && it.expiresAtMs > now }?.let { cached ->
                    val chosen = norm.withPreset(cached.strategy)
                    log("Zapret Auto: using cached ${chosen.summary()}")
                    return ZapretAutoTuneResult(
                        selectedStrategy = cached.strategy,
                        resolvedConfig = chosen,
                        probes = emptyList(),
                    )
                }
            }

            val resolvedTargets = hosts.mapNotNull { h ->
                val addr = queryProtectedDnsWithFallback(h, norm.dnsServer, sharedDnsTxId, protectSocket, protectDatagram)
                    ?: runCatching { PingProbeHelpers.resolveAddressesPreferIpv4(h).firstOrNull() }.getOrNull()
                addr?.let { h to it }
            }
            if (resolvedTargets.isEmpty()) {
                log("Zapret Auto: could not resolve probe hosts, falling back to ALT 1")
                return ZapretAutoTuneResult(
                    selectedStrategy = ZapretConfig.STRATEGY_ALT1,
                    resolvedConfig = norm.withPreset(ZapretConfig.STRATEGY_ALT1),
                    probes = ZapretConfig.ALT_PRESETS.map {
                        ZapretAltProbeResult(it.key, it.shortLabel, 0, hosts.size, null)
                    },
                )
            }

            val pool = Executors.newFixedThreadPool(ZapretConfig.ALT_PRESETS.size)
            val results = try {
                val futures = ZapretConfig.ALT_PRESETS.map { preset ->
                    pool.submit(Callable {
                        val cfg = norm.withPreset(preset.key)
                        var passed = 0
                        var bestRtt: Long? = null
                        val details = mutableListOf<String>()
                        for ((host, addr) in resolvedTargets) {
                            val rtt = probeTlsWithConfig(host, addr, 443, cfg, protectSocket)
                            if (rtt != null) {
                                passed++
                                if (bestRtt == null || rtt < bestRtt) bestRtt = rtt
                                details.add("$host=${rtt}ms")
                            } else {
                                details.add("$host=✗")
                            }
                        }
                        log("${preset.shortLabel}: $passed/${resolvedTargets.size} [${details.joinToString(", ")}]")
                        ZapretAltProbeResult(
                            strategyKey = preset.key,
                            label = preset.shortLabel,
                            passedCount = passed,
                            totalCount = resolvedTargets.size,
                            rttMs = bestRtt,
                        )
                    })
                }
                futures.map { runCatching { it.get() }.getOrNull() }.filterNotNull()
            } finally {
                pool.shutdownNow()
            }

            val best = results
                .filter { it.passedCount > 0 }
                .sortedWith(
                    compareByDescending<ZapretAltProbeResult> { it.passedCount }
                        .thenBy { it.rttMs ?: Long.MAX_VALUE }
                )
                .firstOrNull()
            val winnerKey = best?.strategyKey ?: ZapretConfig.STRATEGY_ALT1
            if (best != null) {
                cachedAutoChoice = CachedAutoChoice(cacheKey, winnerKey, now + AUTO_CACHE_TTL_MS)
                log("Zapret Auto: selected ${best.label} (${best.passedCount}/${best.totalCount}, ${best.rttMs ?: 0} ms)")
            } else {
                log("Zapret Auto: no ALT probe received ServerHello; defaulting to ALT 1")
            }
            return ZapretAutoTuneResult(
                selectedStrategy = winnerKey,
                resolvedConfig = norm.withPreset(winnerKey),
                probes = results,
            )
        }

        private fun probeTlsWithConfig(
            sniHost: String,
            addr: InetAddress,
            port: Int,
            cfg: ZapretConfig,
            protectSocket: (Socket) -> Unit,
        ): Long? {
            val hello = buildProbeClientHello(sniHost)
            val startNs = System.nanoTime()
            return try {
                Socket().use { s ->
                    s.bind(InetSocketAddress(0))
                    protectSocket(s)
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(addr, port), PROBE_TIMEOUT_MS)
                    s.soTimeout = PROBE_TIMEOUT_MS
                    val out = s.getOutputStream()
                    val inp = s.getInputStream()
                    writeDesyncedInitial(out, hello, hello.size, cfg, rawSocket = s)
                    val first = inp.read()
                    val second = if (first >= 0) inp.read() else -1
                    // ServerHello (0x16 0x03) or valid TLS Alert (0x15 0x03) proves bidirectional TLS handshake passed DPI.
                    if ((first == 0x16 || first == 0x15) && second == 0x03) {
                        ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                    } else {
                        null
                    }
                }
            } catch (_: Exception) {
                null
            }
        }

        /** Builds a standard RFC TLS 1.2 ClientHello with SNI + ALPN for live DPI strategy probing. */
        internal fun buildProbeClientHello(host: String): ByteArray {
            val sniBytes = host.toByteArray(Charsets.US_ASCII)
            val sniEntryLen = 1 + 2 + sniBytes.size
            val sniExtLen = 2 + sniEntryLen

            val exts = ByteArrayOutputStream().apply {
                // server_name (0x0000)
                writeShort(0x0000); writeShort(sniExtLen); writeShort(sniEntryLen)
                write(0x00); writeShort(sniBytes.size); write(sniBytes)
                // supported_groups (0x000a): x25519, secp256r1, secp384r1
                writeShort(0x000a); writeShort(8); writeShort(6)
                writeShort(0x001d); writeShort(0x0017); writeShort(0x0018)
                // ec_point_formats (0x000b)
                writeShort(0x000b); writeShort(2); write(1); write(0)
                // signature_algorithms (0x000d)
                writeShort(0x000d); writeShort(10); writeShort(8)
                writeShort(0x0403); writeShort(0x0804); writeShort(0x0401); writeShort(0x0503)
                // ALPN (0x0010): h2, http/1.1
                writeShort(0x0010); writeShort(14); writeShort(12)
                write(2); write('h'.code); write('2'.code)
                write(8); write("http/1.1".toByteArray(Charsets.US_ASCII))
            }.toByteArray()

            val body = ByteArrayOutputStream().apply {
                writeShort(0x0303) // legacy_version TLS 1.2
                write(Random.nextBytes(32)) // random
                write(0) // session_id_length = 0
                val ciphers = intArrayOf(0x1301, 0x1302, 0xc02b, 0xc02f, 0xc02c, 0xc030, 0xcca8, 0xcca9)
                writeShort(ciphers.size * 2)
                for (c in ciphers) writeShort(c)
                write(1); write(0) // compression: null
                writeShort(exts.size)
                write(exts)
            }.toByteArray()

            val hsLen = body.size
            val recordLen = 4 + hsLen
            val record = ByteArrayOutputStream(5 + recordLen).apply {
                write(0x16); write(0x03); write(0x01)
                writeShort(recordLen)
                write(0x01)
                write((hsLen shr 16) and 0xFF); write((hsLen shr 8) and 0xFF); write(hsLen and 0xFF)
                write(body)
            }.toByteArray()
            return record
        }

        private fun ByteArrayOutputStream.writeShort(v: Int) {
            write((v shr 8) and 0xFF)
            write(v and 0xFF)
        }

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
            rawSocket: Socket? = null,
            onUrgentByte: (() -> Unit)? = null,
        ) {
            if (isTlsClientHello(buf, len)) {
                val framed = if (cfg.tlsRecSplit) {
                    splitTlsClientHelloRecords(buf, len, cfg.splitSize, cfg.tlsRecCount)
                } else {
                    buf.copyOf(len)
                }
                val sniMidInFramed = if (cfg.tlsRecSplit && framed.size > len) {
                    // First record boundary falls right at the split inside SNI / last record cut.
                    findLastTlsRecordStart(framed)
                } else {
                    val recLen = ((framed[3].toInt() and 0xFF) shl 8) or (framed[4].toInt() and 0xFF)
                    val span = findTlsSniPayloadRange(framed, 5, minOf(framed.size, 5 + recLen))
                    span?.let { (it.first + it.last) / 2 }
                }
                val sniStartInFramed = if (!cfg.tlsRecSplit) {
                    val recLen = ((framed[3].toInt() and 0xFF) shl 8) or (framed[4].toInt() and 0xFF)
                    findTlsSniPayloadRange(framed, 5, minOf(framed.size, 5 + recLen))?.first
                } else {
                    5 + (((framed[3].toInt() and 0xFF) shl 8) or (framed[4].toInt() and 0xFF))
                }

                val cuts = if (cfg.tcpSplit) {
                    computeTcpSplitBoundaries(
                        len = framed.size,
                        splitMode = cfg.splitMode,
                        splitSize = cfg.splitSize,
                        splitCount = cfg.splitCount,
                        hostStart = sniStartInFramed,
                        hostMid = sniMidInFramed,
                    )
                } else if (cfg.tlsRecSplit && framed.size > len) {
                    collectTlsRecordBoundaries(framed)
                } else {
                    emptyList()
                }
                val oobOffset = if (cfg.oob) (sniMidInFramed ?: 1).coerceIn(1, framed.size - 1) else null
                val mergedCuts = if (oobOffset != null) (cuts + oobOffset).distinct().sorted() else cuts
                writeSplitSlices(out, framed, mergedCuts, cfg.splitDelayMs, oobOffset, rawSocket, onUrgentByte)
                return
            }

            if (isHttpRequest(buf, len)) {
                val payload = if (cfg.httpHostTricks) mutateHttpHostHeader(buf, len) else buf.copyOf(len)
                val hostRange = findHttpHostRange(payload, payload.size)
                val cuts = if (cfg.tcpSplit) {
                    computeTcpSplitBoundaries(
                        len = payload.size,
                        splitMode = cfg.splitMode,
                        splitSize = cfg.splitSize,
                        splitCount = cfg.splitCount,
                        hostStart = hostRange?.first,
                        hostMid = hostRange?.let { (it.first + it.last) / 2 },
                    )
                } else {
                    emptyList()
                }
                val oobOffset = if (cfg.oob) {
                    hostRange?.let { (it.first + it.last) / 2 } ?: 1
                } else {
                    null
                }
                val mergedCuts = if (oobOffset != null && oobOffset in 1 until payload.size) {
                    (cuts + oobOffset).distinct().sorted()
                } else {
                    cuts
                }
                writeSplitSlices(out, payload, mergedCuts, cfg.splitDelayMs, oobOffset, rawSocket, onUrgentByte)
                return
            }

            out.write(buf, 0, len)
            out.flush()
        }

        private fun writeSplitSlices(
            out: OutputStream,
            buf: ByteArray,
            cuts: List<Int>,
            delayMs: Int,
            oobAtOffset: Int?,
            rawSocket: Socket?,
            onUrgentByte: (() -> Unit)?,
        ) {
            if (cuts.isEmpty()) {
                out.write(buf)
                out.flush()
                return
            }
            var prev = 0
            for (cut in cuts) {
                if (cut <= prev || cut >= buf.size) continue
                out.write(buf, prev, cut - prev)
                out.flush()
                if (oobAtOffset != null && cut == oobAtOffset) {
                    runCatching { rawSocket?.sendUrgentData('a'.code) }
                    onUrgentByte?.invoke()
                }
                if (delayMs > 0) runCatching { Thread.sleep(delayMs.toLong()) }
                prev = cut
            }
            if (prev < buf.size) {
                out.write(buf, prev, buf.size - prev)
                out.flush()
            }
        }

        internal fun computeTcpSplitBoundaries(
            len: Int,
            splitMode: String,
            splitSize: Int,
            splitCount: Int,
            hostStart: Int?,
            hostMid: Int?,
        ): List<Int> {
            if (len <= 1) return emptyList()
            val mid = (hostMid ?: (len / 2)).coerceIn(1, len - 1)
            val start = (hostStart ?: (mid / 2).coerceAtLeast(1)).coerceIn(1, len - 1)
            val raw = when (splitMode) {
                ZapretConfig.SPLIT_MODE_ONE -> listOf(1)
                ZapretConfig.SPLIT_MODE_MIDSLD -> listOf(mid)
                ZapretConfig.SPLIT_MODE_MULTI_SNI -> listOf(1, 2, start, (start + mid) / 2, mid, (mid + 2).coerceAtMost(len - 1))
                ZapretConfig.SPLIT_MODE_FIXED -> buildList {
                    add(1)
                    val step = splitSize.coerceIn(1, 64)
                    var off = step
                    var count = 0
                    while (off < len - 1 && count < (splitCount - 2).coerceAtLeast(0)) {
                        add(off)
                        off += step
                        count++
                    }
                    add(mid)
                }
                else -> listOf(1, mid) // SPLIT_MODE_ONE_PLUS_MIDSLD
            }
            return raw.filter { it in 1 until len }.distinct().sorted()
        }

        private fun collectTlsRecordBoundaries(framed: ByteArray): List<Int> {
            val cuts = mutableListOf<Int>()
            var pos = 0
            while (pos + 5 <= framed.size && (framed[pos].toInt() and 0xFF) == 0x16) {
                val recLen = ((framed[pos + 3].toInt() and 0xFF) shl 8) or (framed[pos + 4].toInt() and 0xFF)
                val next = pos + 5 + recLen
                if (next <= pos + 5 || next >= framed.size) break
                cuts.add(next)
                pos = next
            }
            return cuts
        }

        private fun findLastTlsRecordStart(framed: ByteArray): Int? =
            collectTlsRecordBoundaries(framed).lastOrNull()

        internal fun isTlsClientHello(buf: ByteArray, len: Int): Boolean =
            len >= 10 &&
                (buf[0].toInt() and 0xFF) == 0x16 &&
                (buf[1].toInt() and 0xFF) == 0x03 &&
                (buf[5].toInt() and 0xFF) == 0x01

        /**
         * Splits a single TLS ClientHello record into [recordCount] (2 or 3) valid TLS record frames
         * across the SNI payload (and an early handshake offset when 3 records are requested), preserving
         * the exact reassembled ClientHello byte stream.
         */
        internal fun splitTlsClientHelloRecords(
            buf: ByteArray,
            len: Int,
            splitOffset: Int,
            recordCount: Int = 2,
        ): ByteArray {
            if (!isTlsClientHello(buf, len)) return buf.copyOf(len)
            val recLen = ((buf[3].toInt() and 0xFF) shl 8) or (buf[4].toInt() and 0xFF)
            if (recLen < 4 || 5 + recLen > len) return buf.copyOf(len)

            val sniSpan = findTlsSniPayloadRange(buf, 5, 5 + recLen)
            val mainCut = when {
                sniSpan != null && sniSpan.last - sniSpan.first >= 2 -> {
                    ((sniSpan.first + sniSpan.last) / 2 - 5).coerceIn(1, recLen - 1)
                }
                else -> splitOffset.coerceIn(1, recLen - 1)
            }
            val cuts = if (recordCount >= 3 && mainCut >= 3) {
                val earlyCut = (sniSpan?.first?.minus(5) ?: 2).coerceIn(1, mainCut - 1)
                listOf(earlyCut, mainCut).distinct().sorted()
            } else {
                listOf(mainCut)
            }

            val out = ByteArrayOutputStream(len + cuts.size * 5)
            var prev = 0
            for (cut in cuts) {
                val sliceLen = cut - prev
                if (sliceLen <= 0) continue
                out.write(0x16)
                out.write(buf[1].toInt() and 0xFF)
                out.write(buf[2].toInt() and 0xFF)
                out.write((sliceLen shr 8) and 0xFF)
                out.write(sliceLen and 0xFF)
                out.write(buf, 5 + prev, sliceLen)
                prev = cut
            }
            val lastLen = recLen - prev
            if (lastLen > 0) {
                out.write(0x16)
                out.write(buf[1].toInt() and 0xFF)
                out.write(buf[2].toInt() and 0xFF)
                out.write((lastLen shr 8) and 0xFF)
                out.write(lastLen and 0xFF)
                out.write(buf, 5 + prev, lastLen)
            }
            val tailOffset = 5 + recLen
            if (tailOffset < len) {
                out.write(buf, tailOffset, len - tailOffset)
            }
            return out.toByteArray()
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

        private fun findHttpHostRange(buf: ByteArray, len: Int): IntRange? {
            val marker = "host:"
            val text = buf.copyOfRange(0, minOf(len, 2048)).decodeToString().lowercase()
            val idx = text.indexOf(marker)
            if (idx < 0) return null
            var start = idx + marker.length
            while (start < text.length && (text[start] == ' ' || text[start] == '\t')) start++
            var end = start
            while (end < text.length && text[end] != '\r' && text[end] != '\n' && text[end] != ':') end++
            return if (end > start + 2) start until end else null
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

        private fun queryProtectedDnsWithFallback(
            domain: String,
            primaryDns: String,
            txIdGen: AtomicInteger,
            protectTcp: (Socket) -> Unit,
            protectUdp: (DatagramSocket) -> Unit,
        ): InetAddress? {
            val candidates = buildList {
                val trimmed = primaryDns.trim()
                if (trimmed.isNotEmpty()) add(trimmed)
                for (fb in FALLBACK_DOH_URLS) {
                    if (!fb.equals(trimmed, ignoreCase = true)) add(fb)
                }
            }
            for (server in candidates) {
                val query = buildDnsQuery(domain, txIdGen.getAndIncrement() and 0xFFFF) ?: return null
                val addr = runCatching {
                    if (server.startsWith("https://", ignoreCase = true)) {
                        queryDoh(server, query, protectTcp)
                    } else {
                        queryUdpDns(server, query, protectUdp)
                    }
                }.getOrNull()
                if (addr != null) return addr
            }
            return null
        }

        private fun queryDoh(
            dohUrl: String,
            wireQuery: ByteArray,
            protectTcp: (Socket) -> Unit,
        ): InetAddress? {
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

                    val body = readHttpBody(s.getInputStream()) ?: return null
                    return parseDnsAResponse(body, 0, body.size)
                }
            }
        }

        /**
         * Reads an HTTP/1.1 response body honoring `Content-Length` and `Transfer-Encoding: chunked`
         * so keep-alive DoH servers never stall waiting for TCP FIN.
         */
        internal fun readHttpBody(inp: InputStream): ByteArray? {
            val headerBytes = ByteArrayOutputStream(512)
            var matched = 0
            while (headerBytes.size() < 16_384) {
                val b = inp.read()
                if (b < 0) return null
                headerBytes.write(b)
                matched = when (matched) {
                    0 -> if (b == '\r'.code) 1 else 0
                    1 -> if (b == '\n'.code) 2 else if (b == '\r'.code) 1 else 0
                    2 -> if (b == '\r'.code) 3 else 0
                    3 -> if (b == '\n'.code) 4 else if (b == '\r'.code) 1 else 0
                    else -> 0
                }
                if (matched == 4) break
            }
            if (matched != 4) return null

            val headerLines = headerBytes.toByteArray().decodeToString().split("\r\n")
            val statusCode = headerLines.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: return null
            if (statusCode !in 200..299) return null

            var contentLength = -1
            var chunked = false
            for (line in headerLines.drop(1)) {
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val k = line.substring(0, colon).trim().lowercase()
                val v = line.substring(colon + 1).trim()
                when (k) {
                    "content-length" -> contentLength = v.toIntOrNull() ?: -1
                    "transfer-encoding" -> if (v.contains("chunked", ignoreCase = true)) chunked = true
                }
            }

            if (chunked) {
                val out = ByteArrayOutputStream()
                while (true) {
                    val sizeLine = readAsciiLine(inp)?.substringBefore(';')?.trim() ?: return null
                    val size = sizeLine.toIntOrNull(16) ?: return null
                    if (size == 0) break
                    if (size > 65_535) return null
                    val buf = ByteArray(size)
                    var off = 0
                    while (off < size) {
                        val r = inp.read(buf, off, size - off)
                        if (r < 0) return null
                        off += r
                    }
                    out.write(buf)
                    readAsciiLine(inp) // trailing \r\n after chunk
                }
                return out.toByteArray()
            }

            if (contentLength in 1..65_535) {
                val buf = ByteArray(contentLength)
                var off = 0
                while (off < contentLength) {
                    val r = inp.read(buf, off, contentLength - off)
                    if (r < 0) break
                    off += r
                }
                return if (off == contentLength) buf else buf.copyOf(off)
            }

            return inp.readBytes()
        }

        private fun readAsciiLine(inp: InputStream): String? {
            val sb = StringBuilder()
            while (sb.length < 256) {
                val b = inp.read()
                if (b < 0) return null
                if (b == '\n'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
            }
            return sb.toString()
        }

        private fun queryUdpDns(
            serverSpec: String,
            wireQuery: ByteArray,
            protectUdp: (DatagramSocket) -> Unit,
        ): InetAddress? {
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
            "1.0.0.1" -> InetAddress.getByAddress(byteArrayOf(1, 0, 0, 1))
            "dns.google", "8.8.8.8" -> InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
            "8.8.4.4" -> InetAddress.getByAddress(byteArrayOf(8, 8, 4, 4))
            "dns.quad9.net", "9.9.9.9" -> InetAddress.getByAddress(byteArrayOf(9, 9, 9, 9))
            "dns.adguard-dns.com" -> InetAddress.getByAddress(byteArrayOf(94.toByte(), 140.toByte(), 14, 14))
            else -> InetAddress.getByName(host)
        }

        internal fun buildDnsQuery(domain: String, id: Int): ByteArray? {
            val labels = domain.trimEnd('.').split('.')
            if (labels.isEmpty() || labels.any { it.isEmpty() || it.length > 63 }) return null
            val out = ByteArrayOutputStream(64)
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
    }
}
