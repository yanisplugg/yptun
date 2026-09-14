package org.olcbox.app.data.share

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** An iOS link = raw DEFLATE (what Apple's codec emits) + [zlibWrap]; released builds read it with a plain Inflater. */
class ZlibWrapTest {
    private val payload = "[Interface]\nPrivateKey = abc\nJc = 4\n".repeat(40).encodeToByteArray()

    private fun rawDeflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, true).apply { setInput(data); finish() }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    @Test
    fun wrappedRawDeflateOpensWithClassicInflater() {
        val wrapped = zlibWrap(rawDeflate(payload), payload)
        assertTrue(hasZlibHeader(wrapped))

        val inflater = Inflater() // zlib mode only, exactly like Android/desktop 3.5.2 and older
        inflater.setInput(wrapped)
        val out = ByteArray(payload.size)
        val n = inflater.inflate(out)
        assertTrue(inflater.finished(), "Adler-32 trailer rejected")
        inflater.end()
        assertContentEquals(payload, out.copyOf(n))

        assertContentEquals(payload, inflateOrNull(wrapped))
    }
}
