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

// Apple's "zlib" codec is raw DEFLATE; the link format is zlib, see [zlibWrap].
internal actual fun deflateOrNull(data: ByteArray): ByteArray? =
    appleZlib(data, compress = true)?.let { zlibWrap(it, data) }

internal actual fun inflateOrNull(data: ByteArray): ByteArray? {
    if (hasZlibHeader(data)) appleZlib(data.copyOfRange(2, data.size - 4), compress = false)?.let { return it }
    // Raw DEFLATE: links shared by the first iOS builds, before the zlib wrap.
    return appleZlib(data, compress = false)
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun appleZlib(bytes: ByteArray, compress: Boolean): ByteArray? = runCatching {
    if (bytes.isEmpty()) return null
    val input = bytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
    }
    val output = if (compress) {
        input.compressedDataUsingAlgorithm(NSDataCompressionAlgorithmZlib, error = null)
    } else {
        input.decompressedDataUsingAlgorithm(NSDataCompressionAlgorithmZlib, error = null)
    } ?: return null
    val result = ByteArray(output.length.toInt())
    if (result.isNotEmpty()) result.usePinned { pinned -> memcpy(pinned.addressOf(0), output.bytes, output.length) }
    result
}.getOrNull()
