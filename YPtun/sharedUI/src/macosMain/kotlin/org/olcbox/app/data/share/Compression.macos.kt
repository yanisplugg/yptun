package org.olcbox.app.data.share

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDataCompressionAlgorithmZlib
import platform.Foundation.compressedDataUsingAlgorithm
import platform.Foundation.create
import platform.Foundation.decompressedDataUsingAlgorithm
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual fun deflateOrNull(data: ByteArray): ByteArray? = runCatching {
    if (data.isEmpty()) return ByteArray(0)
    val nsData = data.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = data.size.toULong())
    } ?: return null
    val compressed = nsData.compressedDataUsingAlgorithm(NSDataCompressionAlgorithmZlib, error = null)
        ?: return null
    val len = compressed.length.toInt()
    if (len <= 0) return ByteArray(0)
    val result = ByteArray(len)
    result.usePinned { pinned ->
        memcpy(pinned.addressOf(0), compressed.bytes, compressed.length)
    }
    result
}.getOrNull()

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual fun inflateOrNull(data: ByteArray): ByteArray? = runCatching {
    if (data.isEmpty()) return ByteArray(0)

    fun decompressRaw(bytes: ByteArray): ByteArray? = runCatching {
        if (bytes.isEmpty()) return ByteArray(0)
        val nsData = bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        } ?: return null
        val decompressed = nsData.decompressedDataUsingAlgorithm(NSDataCompressionAlgorithmZlib, error = null)
            ?: return null
        val len = decompressed.length.toInt()
        if (len <= 0) return ByteArray(0)
        val result = ByteArray(len)
        result.usePinned { pinned ->
            memcpy(pinned.addressOf(0), decompressed.bytes, decompressed.length)
        }
        result
    }.getOrNull()

    // 1. Check for RFC 1950 zlib wrapper (produced by Java/Android Deflater):
    // Header is 2 bytes: CMF and FLG.
    // (CMF * 256 + FLG) % 31 == 0 and (CMF & 0x0F) == 8 (Deflate).
    // Apple's NSDataCompressionAlgorithmZlib is RFC 1951 RAW deflate without header/trailer.
    // Stripping the 2-byte header and 4-byte trailer yields the raw deflate stream.
    if (data.size >= 6) {
        val b0 = data[0].toInt() and 0xFF
        val b1 = data[1].toInt() and 0xFF
        if ((b0 and 0x0F) == 8 && (b0 * 256 + b1) % 31 == 0) {
            val stripped = data.copyOfRange(2, data.size - 4)
            val res = decompressRaw(stripped)
            if (res != null) return res
        }
    }

    // 2. Try raw deflate as-is (RFC 1951, produced by Apple compressedDataUsingAlgorithm):
    val direct = decompressRaw(data)
    if (direct != null) return direct

    // 3. Gzip format (RFC 1952: 0x1F, 0x8B): 10 bytes header, 8 bytes trailer:
    if (data.size >= 18 && (data[0].toInt() and 0xFF) == 0x1F && (data[1].toInt() and 0xFF) == 0x8B) {
        val gzipStripped = data.copyOfRange(10, data.size - 8)
        val res = decompressRaw(gzipStripped)
        if (res != null) return res
    }

    null
}.getOrNull()
