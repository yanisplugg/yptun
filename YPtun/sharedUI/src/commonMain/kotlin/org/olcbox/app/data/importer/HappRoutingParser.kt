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
     * Accepted Happ routing link prefixes (both `/add/` and `/onadd/`, plus bare `/routing/`).
     */
    private val HAPP_SCHEMES = listOf(
        "happ://routing/add/",
        "happ://routing/onadd/",
        "happ://routing/",
    )

    /**
     * Alternative `routing://` scheme prefixes (our own export format / cross-app sharing), accepted
     * in addition to Happ's. The payload is the same base64url-json as Happ, so all forms decode
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
        coerceInputValues = true
    }

    /** The matching scheme prefix for [link] (Happ or routing://), or null when none applies. */
    private fun schemePrefixOf(link: String): String? {
        val t = link.trim()
        val allSchemes = HAPP_SCHEMES + ROUTING_SCHEMES
        // Longest prefixes first so "happ://routing/add/" wins over "happ://routing/".
        return allSchemes.firstOrNull { t.startsWith(it, ignoreCase = true) }
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

    /**
     * Normalizes a routing JSON object: lowercases all field names (Happ wire format uses PascalCase
     * like "DirectSites", "GlobalProxy", whereas RoutingProfile serial names are lowercase), and
     * coerces string booleans ("true"/"false") to actual boolean primitives.
     */
    private fun normalizeRoutingJson(root: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject {
        val map = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        for ((key, value) in root) {
            val lower = key.lowercase()
            val normalizedValue = when (lower) {
                "globalproxy", "fakedns", "expert", "xraysniffing", "xrayrouteonly", "singboxsniff", "singboxresolve" -> {
                    when (value) {
                        is kotlinx.serialization.json.JsonPrimitive -> {
                            val str = value.content.trim()
                            kotlinx.serialization.json.JsonPrimitive(str.equals("true", ignoreCase = true) || str == "1")
                        }
                        else -> value
                    }
                }
                else -> value
            }
            map[lower] = normalizedValue
        }
        return kotlinx.serialization.json.JsonObject(map)
    }

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
        return parseJson(jsonText)
    }

    /** Decodes raw Happ routing JSON, or null when it isn't valid routing JSON. */
    fun parseJson(text: String): RoutingProfile? {
        val t = text.trim()
        if (!t.startsWith("{")) return null
        return runCatching {
            val root = Json.parseToJsonElement(t) as? kotlinx.serialization.json.JsonObject ?: return null
            val normalized = normalizeRoutingJson(root)
            json.decodeFromJsonElement<RoutingProfile>(normalized)
        }.getOrNull()
    }

    /** Parses a profile from either form (happ:// link or raw JSON). */
    fun parseAny(text: String): RoutingProfile? = parse(text) ?: parseJson(text)
}
