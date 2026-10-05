package org.olcbox.app.data.datasource

import java.io.File

// java.io.tmpdir is the app cache dir on Android and the user temp dir on desktop; a miss only costs
// one re-download (the UI re-fills it), so no persistent-dir plumbing.
internal actual object SubscriptionIconDisk {
    private fun file(url: String) = File(
        File(System.getProperty("java.io.tmpdir"), "yptun-sub-icons"),
        url.hashCode().toUInt().toString(16) + "-" + url.length
    )

    actual fun read(url: String): ByteArray? = runCatching { file(url).takeIf { it.isFile }?.readBytes() }.getOrNull()

    actual fun write(url: String, bytes: ByteArray) {
        runCatching {
            val f = file(url)
            f.parentFile.mkdirs()
            f.writeBytes(bytes)
        }
    }

    actual fun stamp(url: String): Long = runCatching { file(url).lastModified() }.getOrDefault(0L)
}
