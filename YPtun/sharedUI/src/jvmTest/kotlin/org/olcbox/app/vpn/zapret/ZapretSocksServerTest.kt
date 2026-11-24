package org.olcbox.app.vpn.zapret

import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.ZapretConfig
import org.olcbox.app.vpn.PingProbeHelpers
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ZapretSocksServerTest {

    @Test
    fun zapretConfigNormalizesPresetsAndIsCompleteWithoutServer() {
        val cfg = LocationConfig(
            name = "Zapret (DPI)",
            engine = EngineType.Zapret,
            zapret = ZapretConfig(),
        ).normalized()
        assertTrue(cfg.isComplete())
        assertEquals(ZapretConfig.STRATEGY_AUTO, cfg.zapret?.strategy)

        val tlsRec = ZapretConfig().withPreset(ZapretConfig.STRATEGY_TLSREC)
        assertTrue(tlsRec.tlsRecSplit)
        assertEquals(false, tlsRec.blockQuic)
        assertEquals(2, tlsRec.udpFakeCount)
    }

    @Test
    fun tlsRecordSplitPreservesExactHandshakeBytesAcrossMultipleRecords() {
        val fakeClientHelloPayload = ByteArray(180) { i -> ((i * 7 + 13) and 0xFF).toByte() }.also { p ->
            p[0] = 0x01 // HandshakeType: ClientHello
            p[1] = 0x00; p[2] = 0x00; p[3] = (176).toByte()
            p[4] = 0x03; p[5] = 0x03
        }
        val singleRecord = ByteArray(5 + fakeClientHelloPayload.size).also { r ->
            r[0] = 0x16
            r[1] = 0x03
            r[2] = 0x01
            r[3] = ((fakeClientHelloPayload.size shr 8) and 0xFF).toByte()
            r[4] = (fakeClientHelloPayload.size and 0xFF).toByte()
            fakeClientHelloPayload.copyInto(r, 5)
        }

        val split = ZapretSocksServer.splitTlsClientHelloRecords(singleRecord, singleRecord.size, splitOffset = 10)
        assertTrue(split.size == singleRecord.size + 5, "Expected one extra 5-byte TLS header")

        // Reassemble both TLS records and confirm the inner ClientHello bytes are byte-for-byte identical.
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
        assertEquals(2, records)
        assertContentEquals(fakeClientHelloPayload, reassembled.toByteArray())
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
                    config = ZapretConfig(splitSize = 2, splitCount = 4, splitDelayMs = 0),
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
                    // Auth negotiation
                    out.write(byteArrayOf(0x05, 0x01, 0x02)); out.flush()
                    assertContentEquals(byteArrayOf(0x05, 0x02), inp.readNBytes(2))
                    out.write(byteArrayOf(0x01, 0x01, 'u'.code.toByte(), 0x01, 'p'.code.toByte())); out.flush()
                    assertContentEquals(byteArrayOf(0x01, 0x00), inp.readNBytes(2))
                    // CONNECT 127.0.0.1:echoPort
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
