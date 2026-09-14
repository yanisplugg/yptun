package org.olcbox.app.data.share

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

internal actual fun deflateOrNull(data: ByteArray): ByteArray? = runCatching {
    val deflater = Deflater(Deflater.BEST_COMPRESSION)
    deflater.setInput(data)
    deflater.finish()
    val out = ByteArrayOutputStream()
    val buf = ByteArray(4096)
    while (!deflater.finished()) {
        val n = deflater.deflate(buf)
        out.write(buf, 0, n)
    }
    deflater.end()
    out.toByteArray()
}.getOrNull()

internal actual fun inflateOrNull(data: ByteArray): ByteArray? = runCatching {
    fun inflateWith(nowrap: Boolean): ByteArray? = runCatching {
        val inflater = Inflater(nowrap)
        inflater.setInput(data)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && inflater.needsInput()) break
            out.write(buf, 0, n)
        }
        inflater.end()
        out.toByteArray()
    }.getOrNull()

    // Try RFC 1950 (zlib header) first, then RFC 1951 (raw deflate / nowrap = true)
    inflateWith(nowrap = false) ?: inflateWith(nowrap = true)
}.getOrNull()
