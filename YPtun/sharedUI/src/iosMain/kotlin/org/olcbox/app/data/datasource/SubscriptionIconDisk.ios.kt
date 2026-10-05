package org.olcbox.app.data.datasource

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.olcbox.app.vpn.ios.IosSharedStore
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual object SubscriptionIconDisk {
    private val dir by lazy {
        val path = "${IosSharedStore.dir}/sub-icons"
        IosSharedStore.ensureDirectory(path)
        path
    }

    private fun filePath(url: String): String {
        val key = url.hashCode().toUInt().toString(16) + "-" + url.length
        return "$dir/$key"
    }

    actual fun read(url: String): ByteArray? = runCatching {
        val path = filePath(url)
        val data = NSData.dataWithContentsOfFile(path) ?: return@runCatching null
        val length = data.length.toInt()
        if (length == 0) return@runCatching null
        val bytes = ByteArray(length)
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), data.bytes, data.length)
        }
        bytes
    }.getOrNull()

    actual fun write(url: String, bytes: ByteArray) {
        runCatching {
            val path = filePath(url)
            bytes.usePinned { pinned ->
                val data = NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
                data?.writeToFile(path, true)
            }
        }
    }

    actual fun stamp(url: String): Long = runCatching {
        val path = filePath(url)
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
        val modDate = attrs?.get(NSFileModificationDate) as? NSDate
        ((modDate?.timeIntervalSince1970 ?: 0.0) * 1000).toLong()
    }.getOrDefault(0L)
}
