package org.olcbox.app.vpn.ssh

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ServerBinaryLoadTest {

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(bytes.inputStream()).use { it.readBytes() }

    @Test
    fun `already-gzipped bytes are passed through untouched`() {
        val packed = gzip(byteArrayOf(1, 2, 3))
        assertContentEquals(packed, gzipIfNeeded(packed))
    }

    @Test
    fun `raw bytes are gzipped so the upload stays small`() {
        val raw = ByteArray(1000) { 7 }
        val packed = gzipIfNeeded(raw)
        assertTrue(packed.size < raw.size)
        assertContentEquals(raw, gunzip(packed))
    }

    @Test
    fun `falls back to the dot-gz name when the plain one is absent`() {
        // Android's asset packaging decompresses .gz assets and stores them under the bare name;
        // the desktop keeps the .gz. Both must resolve.
        val raw = ByteArray(100) { 3 }
        val plainOnly = ServerBinarySource { path -> raw.takeIf { path == "wdtt/server" } }
        val gzOnly = ServerBinarySource { path -> gzip(raw).takeIf { path == "wdtt/server.gz" } }

        assertContentEquals(raw, gunzip(loadServerBinaryGz(plainOnly, "wdtt/server")))
        assertContentEquals(raw, gunzip(loadServerBinaryGz(gzOnly, "wdtt/server")))
    }

    @Test
    fun `missing binary fails loudly`() {
        assertFailsWith<IllegalStateException> {
            loadServerBinaryGz(ServerBinarySource { null }, "wdtt/server")
        }
    }
}
