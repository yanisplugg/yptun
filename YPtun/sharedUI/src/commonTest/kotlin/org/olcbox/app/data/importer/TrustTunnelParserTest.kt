package org.olcbox.app.data.importer

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class TrustTunnelParserTest {
    private fun tlv(tag: Int, value: String): ByteArray {
        val v = value.encodeToByteArray()
        // QUIC varint: lengths >= 64 take two bytes with the 01 prefix.
        val len = if (v.size < 64) byteArrayOf(v.size.toByte())
        else byteArrayOf((0x40 or (v.size shr 8)).toByte(), (v.size and 0xFF).toByte())
        return byteArrayOf(tag.toByte()) + len + v
    }

    private fun link(vararg parts: ByteArray) =
        "tt://?" + Base64.UrlSafe.encode(parts.reduce { a, b -> a + b }).trimEnd('=')

    @Test
    fun readsAddressNotHostname() {
        val l = link(tlv(1, "tls.example.com"), tlv(5, "user"), tlv(2, "203.0.113.7:8443"), tlv(8, "x".repeat(300)))
        assertEquals(listOf("203.0.113.7" to 8443), TrustTunnelParser.endpoints(l + "#name"))
    }

    @Test
    fun fallsBackToHostname443AndKeepsAllAddresses() {
        assertEquals(listOf("h.example.com" to 443), TrustTunnelParser.endpoints(link(tlv(1, "h.example.com"))))
        assertEquals(
            listOf("a.example" to 1, "b.example" to 2),
            TrustTunnelParser.endpoints(link(tlv(2, "a.example:1"), tlv(2, "b.example:2"))),
        )
    }

    @Test
    fun garbageGivesEmpty() {
        assertTrue(TrustTunnelParser.endpoints("tt://?!!!").isEmpty())
        assertTrue(TrustTunnelParser.endpoints("tt://").isEmpty())
    }
}
