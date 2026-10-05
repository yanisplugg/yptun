package org.olcbox.app.data.datasource

internal actual object SubscriptionIconDisk {
    actual fun read(url: String): ByteArray? = null
    actual fun write(url: String, bytes: ByteArray) = Unit
    actual fun stamp(url: String): Long = 0L
}
