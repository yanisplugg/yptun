package org.olcbox.app.vpn.zapret

import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.ZapretConfig
import org.olcbox.app.vpn.PingProbeHelpers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ZapretSocksServerTest {

    @Test
    fun zapretConfigNormalizesAltPresetsAndLegacyKeys() {
        val cfg = LocationConfig(
            name = "Zapret (DPI)",
            engine = EngineType.Zapret,
            zapret = ZapretConfig(),
        ).normalized()
        assertTrue(cfg.isComplete())
        assertEquals(ZapretConfig.STRATEGY_AUTO, cfg.zapret?.strategy)

        val alt2 = ZapretConfig().withPreset(ZapretConfig.STRATEGY_ALT2)
        assertEquals(false, alt2.tlsRecSplit)
        assertTrue(alt2.disorder)
        assertTrue(alt2.tcpSplit)
        assertEquals(false, alt2.oob)

        val alt3 = ZapretConfig().withPreset(ZapretConfig.STRATEGY_ALT3)
        assertEquals(false, alt3.tlsRecSplit)
        assertEquals(ZapretConfig.SPLIT_MODE_SNI_HOLE, alt3.splitMode)
        assertTrue(alt3.disorder)
        assertEquals(false, alt3.oob)

        val alt4 = ZapretConfig().withPreset(ZapretConfig.STRATEGY_ALT4)
        assertEquals(3, alt4.tlsRecCount)
        assertEquals(ZapretConfig.SPLIT_MODE_SNI_HOLE, alt4.splitMode)
        assertTrue(alt4.disorder)

        val alt5 = ZapretConfig().withPreset(ZapretConfig.STRATEGY_ALT5)
        assertTrue(alt5.oob)
        assertTrue(alt5.disorder)

        val alt6 = ZapretConfig().withPreset(ZapretConfig.STRATEGY_ALT6)
        assertTrue(alt6.oob)

        val alt7 = ZapretConfig().withPreset(ZapretConfig.STRATEGY_ALT7)
        assertEquals(false, alt7.blockQuic)
        assertTrue(alt7.disorder)
        assertEquals(3, alt7.udpFakeCount)

        // Legacy strategy names map cleanly to ALT presets.
        assertEquals(ZapretConfig.STRATEGY_ALT1, ZapretConfig().withPreset(ZapretConfig.STRATEGY_LEGACY_TLSREC).strategy)
        assertEquals(ZapretConfig.STRATEGY_ALT3, ZapretConfig().withPreset(ZapretConfig.STRATEGY_LEGACY_SPLIT).strategy)

        // Root mode tuning clamps bounds, defaults blank decoy SNI, and survives preset switches.
        val rootCfg = ZapretConfig(
            strategy = ZapretConfig.STRATEGY_ALT4,
            rootMode = true,
            rootFakeTtl = 99,
            rootFakeCount = 0,
            rootDecoySni = "   ",
            rootBadSum = true,
            rootSplitSni = true,
        ).withPreset(ZapretConfig.STRATEGY_ALT6)
        assertTrue(rootCfg.rootMode)
        assertEquals(16, rootCfg.rootFakeTtl)
        assertEquals(1, rootCfg.rootFakeCount)
        assertEquals(ZapretConfig.DEFAULT_ROOT_DECOY_SNI, rootCfg.rootDecoySni)
        assertEquals("ALT 6 (OOB + Split) + Root", rootCfg.summary())
    }

    @Test
    fun tlsRecordSplitTwoAndThreeRecordsPreserveExactHandshakeBytes() {
        val hello = ZapretSocksServer.buildProbeClientHello("www.youtube.com")
        assertEquals("www.youtube.com", ZapretSocksServer.sniffHost(hello, hello.size))
        val origRecLen = ((hello[3].toInt() and 0xFF) shl 8) or (hello[4].toInt() and 0xFF)
        val origPayload = hello.copyOfRange(5, 5 + origRecLen)

        for (count in listOf(2, 3)) {
            val split = ZapretSocksServer.splitTlsClientHelloRecords(hello, hello.size, splitOffset = 10, recordCount = count)
            assertEquals(hello.size + (count - 1) * 5, split.size, "Expected ${count - 1} extra 5-byte TLS headers")

            val reassembled = ByteArrayOutputStream()
            var pos = 0
            var records = 0
            while (pos < split.size) {
                assertEquals(0x16, split[pos].toInt() and 0xFF)
                assertEquals(0x03, split[pos + 1].toInt() and 0xFF)
                assertEquals(0x01, split[pos + 2].toInt() and 0xFF)
                val recLen = ((split[pos + 3].toInt() and 0xFF) shl 8) or (split[pos + 4].toInt() and 0xFF)
                reassembled.write(split, pos + 5, recLen)
                pos += 5 + recLen
                records++
            }
            assertEquals(count, records)
            assertContentEquals(origPayload, reassembled.toByteArray())
        }
    }

    @Test
    fun desyncSplitsAcrossSniAndFiresOobAndDisorderTtl() {
        val hello = ZapretSocksServer.buildProbeClientHello("discord.com")
        val cfg = ZapretConfig().withPreset(ZapretConfig.STRATEGY_ALT6).copy(splitDelayMs = 0)
        val out = ByteArrayOutputStream()
        val oobCount = AtomicInteger(0)
        ZapretSocksServer.writeDesyncedInitial(out, hello, hello.size, cfg, onUrgentByte = { oobCount.incrementAndGet() })
        assertEquals(1, oobCount.get())
        // Without tlsRecSplit, TCP bytes match ClientHello verbatim across split writes.
        assertContentEquals(hello, out.toByteArray())

        val ttlSequence = mutableListOf<Int>()
        val disorderCfg = ZapretConfig().withPreset(ZapretConfig.STRATEGY_ALT2).copy(splitDelayMs = 0)
        val disorderOut = ByteArrayOutputStream()
        ZapretSocksServer.writeDesyncedInitial(
            disorderOut, hello, hello.size, disorderCfg,
            onTtlChange = { ttl -> ttlSequence.add(ttl); true },
        )
        assertEquals(listOf(1, 64), ttlSequence)
        assertContentEquals(hello, disorderOut.toByteArray())

        // SNI-Hole drops the second slice (SNI itself), keeping header & tail at TTL=64.
        val holeTtl = mutableListOf<Int>()
        val holeCfg = ZapretConfig().withPreset(ZapretConfig.STRATEGY_ALT3).copy(splitDelayMs = 0)
        val holeOut = ByteArrayOutputStream()
        ZapretSocksServer.writeDesyncedInitial(
            holeOut, hello, hello.size, holeCfg,
            onTtlChange = { ttl -> holeTtl.add(ttl); true },
        )
        assertEquals(listOf(1, 64), holeTtl)
        assertContentEquals(hello, holeOut.toByteArray())
        assertTrue(ZapretSocksServer.isStaleMappedDnsIp(InetAddress.getByName("100.64.0.104")))
        assertEquals(false, ZapretSocksServer.isStaleMappedDnsIp(InetAddress.getByName("1.1.1.1")))

        // Long CDN subdomains like *.googlevideo.com must split inside "googlevideo", not inside "rr4---sn-...".
        val gvHost = "rr4---sn-jvhnu5g-n8vs.googlevideo.com"
        val gvHello = ZapretSocksServer.buildProbeClientHello(gvHost)
        val gvSplit = ZapretSocksServer.splitTlsClientHelloRecords(gvHello, gvHello.size, splitOffset = 10, recordCount = 2)
        val rec1Len = ((gvSplit[3].toInt() and 0xFF) shl 8) or (gvSplit[4].toInt() and 0xFF)
        val rec1Text = gvSplit.copyOfRange(5, 5 + rec1Len).decodeToString()
        val rec2Text = gvSplit.copyOfRange(5 + rec1Len + 5, gvSplit.size).decodeToString()
        assertTrue(rec1Text.endsWith("rr4---sn-jvhnu5g-n8vs.googl"), "Expected record 1 to split inside 'googlevideo', got tail: ${rec1Text.takeLast(32)}")
        assertTrue(rec2Text.contains("evideo.com"), "Expected record 2 to contain second half 'evideo.com'")

        val cuts = ZapretSocksServer.computeTcpSplitBoundaries(
            len = hello.size,
            splitMode = ZapretConfig.SPLIT_MODE_MULTI_SNI,
            splitSize = 2,
            splitCount = 6,
            hostStart = 120,
            hostMid = 126,
        )
        assertTrue(1 in cuts && 126 in cuts, "Expected both early offset and SNI midpoint cuts in $cuts")
    }

    @Test
    fun readHttpBodyHandlesContentLengthWithoutEofAndChunkedEncoding() {
        val dnsPayload = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01)
        val clStream = object : ByteArrayInputStream(
            ("HTTP/1.1 200 OK\r\nContent-Type: application/dns-message\r\nContent-Length: ${dnsPayload.size}\r\n\r\n")
                .toByteArray(Charsets.US_ASCII) + dnsPayload + byteArrayOf(99, 99) // trailing bytes after Content-Length
        ) {}
        assertContentEquals(dnsPayload, ZapretSocksServer.readHttpBody(clStream))

        val chunkedStream = ByteArrayInputStream(
            ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
                "3\r\nabc\r\n3\r\ndef\r\n0\r\n\r\n").toByteArray(Charsets.US_ASCII)
        )
        assertContentEquals("abcdef".toByteArray(Charsets.US_ASCII), ZapretSocksServer.readHttpBody(chunkedStream))
    }

    @Test
    fun httpHostTricksAndTargetFilteringMatchSuffixes() {
        val http = "GET / HTTP/1.1\r\nHost: youtube.com\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val mutated = ZapretSocksServer.mutateHttpHostHeader(http, http.size).decodeToString()
        assertTrue("\r\nhOsT: youtube.com\r\n" in mutated, mutated)

        val filtered = ZapretConfig(targets = "youtube.com\ndiscord.com")
        assertTrue(ZapretSocksServer.shouldDesync(filtered, "www.youtube.com", null))
        assertTrue(ZapretSocksServer.shouldDesync(filtered, "1.2.3.4", "cdn.discord.com"))
        assertEquals(false, ZapretSocksServer.shouldDesync(filtered, "example.org", "example.org"))
    }

    @Test
    fun socks5ConnectThroughZapretForwardsAndSplitsPayload() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { echoServer ->
            val echoThread = Thread {
                echoServer.accept().use { s ->
                    val data = s.getInputStream().readNBytes(20)
                    s.getOutputStream().write(data)
                    s.getOutputStream().flush()
                }
            }.apply { isDaemon = true; start() }

            val socksPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val zapret = ZapretSocksServer()
            try {
                zapret.start(
                    config = ZapretConfig(strategy = ZapretConfig.STRATEGY_ALT5, splitDelayMs = 0),
                    listenHost = "127.0.0.1",
                    listenPort = socksPort,
                    socksUsername = "u",
                    socksPassword = "p",
                )
                Socket().use { client ->
                    client.connect(InetSocketAddress("127.0.0.1", socksPort), 3_000)
                    client.soTimeout = 3_000
                    val out = client.getOutputStream()
                    val inp = client.getInputStream()
                    out.write(byteArrayOf(0x05, 0x01, 0x02)); out.flush()
                    assertContentEquals(byteArrayOf(0x05, 0x02), inp.readNBytes(2))
                    out.write(byteArrayOf(0x01, 0x01, 'u'.code.toByte(), 0x01, 'p'.code.toByte())); out.flush()
                    assertContentEquals(byteArrayOf(0x01, 0x00), inp.readNBytes(2))
                    val port = echoServer.localPort
                    out.write(
                        byteArrayOf(
                            0x05, 0x01, 0x00, 0x01,
                            127, 0, 0, 1,
                            ((port shr 8) and 0xFF).toByte(), (port and 0xFF).toByte()
                        )
                    )
                    out.flush()
                    val reply = inp.readNBytes(10)
                    assertEquals(0x00, reply[1].toInt() and 0xFF)

                    val msg = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".take(20).toByteArray(Charsets.US_ASCII)
                    out.write(msg); out.flush()
                    val echoed = inp.readNBytes(20)
                    assertEquals(msg.size, echoed.size)
                }
                echoThread.join(3_000)
            } finally {
                zapret.stop()
            }
        }
    }

    @Test
    fun pingProbeHelpersTcpPingReachesLoopbackListener() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { ss ->
            val t = Thread { runCatching { ss.accept().close() } }.apply { isDaemon = true; start() }
            val ms = PingProbeHelpers.tcpPing("localhost", ss.localPort, timeoutMs = 2_000, attempts = 2)
            t.join(2_000)
            assertNotNull(ms)
            assertTrue(ms >= 1L)
        }
    }
}
