package org.olcbox.app.data.share

// Compression unsupported on Apple targets — the codec falls back to the uncompressed yptun format.
internal actual fun deflateOrNull(data: ByteArray): ByteArray? = null

internal actual fun inflateOrNull(data: ByteArray): ByteArray? = null
