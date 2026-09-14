package org.olcbox.app.data.share

/**
 * Platform DEFLATE used to shrink the [YptunInboundCodec] payload so a long config (e.g. an
 * AmneziaWG INI) still fits in a QR code. The link carries the RFC 1950 zlib format that
 * java.util.zip.Inflater reads on Android/desktop — the Apple targets wrap their raw stream in it.
 */
internal expect fun deflateOrNull(data: ByteArray): ByteArray?

internal expect fun inflateOrNull(data: ByteArray): ByteArray?

/** Raw DEFLATE (RFC 1951, Apple's "zlib" codec) → zlib: 2-byte header + stream + Adler-32 of [original]. */
internal fun zlibWrap(raw: ByteArray, original: ByteArray): ByteArray {
    var a = 1
    var b = 0
    for (byte in original) {
        a = (a + (byte.toInt() and 0xFF)) % 65521
        b = (b + a) % 65521
    }
    val adler = (b shl 16) or a
    return byteArrayOf(0x78, 0x9C.toByte()) + raw +
        byteArrayOf((adler ushr 24).toByte(), (adler ushr 16).toByte(), (adler ushr 8).toByte(), adler.toByte())
}

/** True when [data] starts with a zlib header (CM=8, FCHECK valid) and has room for the trailer. */
internal fun hasZlibHeader(data: ByteArray): Boolean {
    if (data.size < 6) return false
    val cmf = data[0].toInt() and 0xFF
    val flg = data[1].toInt() and 0xFF
    return (cmf and 0x0F) == 8 && (cmf * 256 + flg) % 31 == 0
}
