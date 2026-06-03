package org.olcbox.app.data.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.olcbox.app.data.model.RoutingProfile

/**
 * Parses routing profiles from either a `happ://routing/add/<base64url-json>` link or raw Happ
 * routing JSON. The payload keys map 1:1 onto [RoutingProfile] via its `@SerialName`s; unknown keys
 * are ignored so the format can evolve.
 */
object HappRoutingParser {

    const val SCHEME = "happ://routing/add/"

    /** Distinctive Happ routing-JSON keys, used to recognise a pasted profile vs. some other config. */
    private val ROUTING_KEYS = setOf(
        "directsites", "proxysites", "blocksites", "directip", "proxyip", "blockip",
        "routeorder", "globalproxy", "dnshosts", "domainstrategy",
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** True if [link] looks like a Happ routing link (cheap prefix check). */
    fun isHappRoutingLink(link: String): Boolean =
        link.trim().startsWith(SCHEME, ignoreCase = true)

    /** True if [text] looks like raw Happ routing JSON (a JSON object carrying routing-profile keys). */
    fun isRoutingJson(text: String): Boolean {
        val t = text.trim()
        if (!t.startsWith("{")) return false
        val obj = runCatching { Json.parseToJsonElement(t).jsonObject }.getOrNull() ?: return false
        return obj.keys.any { it.lowercase() in ROUTING_KEYS }
    }

    /** True if [text] is importable as a routing profile (a happ:// link or routing JSON). */
    fun looksLikeRoutingProfile(text: String): Boolean =
        isHappRoutingLink(text) || isRoutingJson(text)

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

    /** Decodes raw Happ routing JSON, or null when it isn't valid routing JSON. */
    fun parseJson(text: String): RoutingProfile? {
        if (!isRoutingJson(text)) return null
        return runCatching { json.decodeFromString<RoutingProfile>(text.trim()) }.getOrNull()
    }

    /** Parses a profile from either form (happ:// link or raw JSON). */
    fun parseAny(text: String): RoutingProfile? = parse(text) ?: parseJson(text)
}
