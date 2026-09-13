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
    val nsData = data.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = data.size.toULong())
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
