package org.olcbox.app.data.datasource

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * On-disk copy of subscription icons, keyed by icon URL. Written when the subscription is refreshed
 * (manual or timer) and only read by the UI, so the icon changes together with the subscription.
 * No-op on platforms without a file cache (iOS/macOS fall back to the in-memory cache).
 */
internal expect object SubscriptionIconDisk {
    fun read(url: String): ByteArray?
    fun write(url: String, bytes: ByteArray)

    /** Changes whenever [write] replaces the file; 0 when nothing is cached. */
    fun stamp(url: String): Long
}

/** Downloads an icon; null unless the server answers 2xx with an image (a 404 page must not pass). */
internal suspend fun downloadSubscriptionIcon(client: HttpClient, url: String): ByteArray? = runCatching {
    val response = client.get(url)
    if (!response.status.isSuccess() || response.contentType()?.contentType != "image") return@runCatching null
    response.bodyAsBytes().takeIf { it.isNotEmpty() && it.size <= 4_000_000 }
}.getOrNull()

/** SVG -> painter, or null where the platform has no decoder (Android's resources lib lacks one). */
internal expect fun decodeSvgPainter(bytes: ByteArray, density: androidx.compose.ui.unit.Density): androidx.compose.ui.graphics.painter.Painter?
