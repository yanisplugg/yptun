package org.olcbox.app.data.share

/**
 * Platform DEFLATE (zlib) used to shrink the [YptunInboundCodec] payload so a long config (e.g. an
 * AmneziaWG INI) still fits in a QR code. Implemented on the JVM/Android targets; the Apple targets
 * return `null` (compression unsupported there → the codec falls back to the uncompressed format).
 */
internal expect fun deflateOrNull(data: ByteArray): ByteArray?

internal expect fun inflateOrNull(data: ByteArray): ByteArray?
