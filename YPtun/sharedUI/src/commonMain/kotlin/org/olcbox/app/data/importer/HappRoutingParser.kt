package org.olcbox.app.data.importer

import kotlinx.serialization.json.Json
import org.olcbox.app.data.model.RoutingProfile

/**
 * Parses `happ://routing/add/<base64url-json>` links into a [RoutingProfile]. The payload is a
 * url-safe (often unpadded) base64 of the Happ routing JSON, whose keys map 1:1 onto
 * [RoutingProfile] via its `@SerialName`s. Unknown keys are ignored so the format can evolve.
 */
object HappRoutingParser {

    const val SCHEME = "happ://routing/add/"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** True if [link] looks like a Happ routing link (cheap prefix check). */
    fun isHappRoutingLink(link: String): Boolean =
        link.trim().startsWith(SCHEME, ignoreCase = true)

    /** Returns the decoded profile, or null if the link is not a valid Happ routing link. */
    fun parse(link: String): RoutingProfile? {
        val trimmed = link.trim()
        if (!isHappRoutingLink(trimmed)) return null
        // Strip the scheme + any trailing #fragment / ?query before decoding the base64 payload.
        val payload = trimmed.substring(SCHEME.length)
            .substringBefore('#')
            .substringBefore('?')
            .trim()
        if (payload.isEmpty()) return null
        val jsonText = SubscriptionDecoder.decodeBase64Chunk(payload) ?: return null
        return runCatching { json.decodeFromString<RoutingProfile>(jsonText) }.getOrNull()
    }
}
