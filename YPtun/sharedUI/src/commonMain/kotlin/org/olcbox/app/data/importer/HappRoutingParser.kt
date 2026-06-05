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

    /**
     * Alternative `routing://` scheme prefixes (our own export format / cross-app sharing), accepted
     * in addition to Happ's. The payload is the same base64url-json as Happ, so all three forms decode
     * identically: `routing://routing/add/<b64>`, `routing://add/<b64>`, and bare `routing://<b64>`.
     */
    private val ROUTING_SCHEMES = listOf(
        "routing://routing/add/",
        "routing://add/",
        "routing://",
    )

    /** Distinctive Happ routing-JSON keys, used to recognise a pasted profile vs. some other config. */
    private val ROUTING_KEYS = setOf(
        "directsites", "proxysites", "blocksites", "directip", "proxyip", "blockip",
        "routeorder", "globalproxy", "dnshosts", "domainstrategy",
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** The matching scheme prefix for [link] (Happ or routing://), or null when none applies. */
    private fun schemePrefixOf(link: String): String? {
        val t = link.trim()
        if (t.startsWith(SCHEME, ignoreCase = true)) return SCHEME
        // Longest prefixes first so "routing://add/" wins over the bare "routing://".
        return ROUTING_SCHEMES.firstOrNull { t.startsWith(it, ignoreCase = true) }
    }

    /** True if [link] looks like a routing link (Happ `happ://` or our `routing://` scheme). */
    fun isHappRoutingLink(link: String): Boolean = schemePrefixOf(link) != null

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
        val scheme = schemePrefixOf(trimmed) ?: return null
        // Strip the scheme + any trailing #fragment / ?query before decoding the base64 payload.
        val payload = trimmed.substring(scheme.length)
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
