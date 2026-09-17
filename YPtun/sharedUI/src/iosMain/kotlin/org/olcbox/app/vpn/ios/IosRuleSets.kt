package org.olcbox.app.vpn.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToFile

/**
 * sing-box's geo rule-sets (`.srs`) kept on disk in the App Group, so starting the tunnel never waits
 * on a download.
 *
 * sing-box declares geosite:/geoip: selectors as `type: "remote"` rule-sets and fetches them inside
 * `Start()`. Inside a packet-tunnel extension that is the worst possible moment: the extension has a
 * deadline to report that the tunnel is up, and the fetch goes through a proxy that has just come up
 * on a network the user turned the VPN on to escape. A slow fetch overruns the deadline and a failed
 * one aborts the whole core with `initial rule-set: <tag>` — either way the tunnel never reports
 * "connected", and a user who keeps retrying sees a connection that keeps reconnecting. xray is
 * unaffected because it reads geoip.dat/geosite.dat from disk (see IosGeoAssets).
 *
 * So the extension never downloads: [localize] rewrites every remote rule-set whose file is already
 * here into a `local` one and drops the rest (along with the rules that can only match through them),
 * recording what was missing. The app then fetches exactly that list — at launch and, more usefully,
 * once the tunnel is up — so the next connect has the full routing and still starts instantly.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosRuleSets {

    /** What [localize] could not resolve locally: a JSON object of tag → url. */
    private const val WANTED_FILE = "srs_wanted.json"

    /** A binary rule-set is a few KB at the smallest; anything below this is a failed download. */
    private const val MIN_SRS_BYTES = 256L

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val dir: String by lazy {
        IosSharedStore.path("srs").also {
            NSFileManager.defaultManager.createDirectoryAtPath(it, true, null, null)
        }
    }

    /**
     * Route-rule fields that decide WHAT a rule matches. A rule left with none of them matches
     * everything (sing-box: an empty rule is a match-all), so a rule whose only matcher was a dropped
     * rule-set has to go entirely. Unknown fields deliberately do not count: dropping one rule too
     * many costs a routing exception, keeping a match-all rule would send every connection to it.
     */
    private val MATCHER_KEYS = setOf(
        "inbound", "ip_version", "network", "auth_user", "protocol", "client",
        "domain", "domain_suffix", "domain_keyword", "domain_regex",
        "source_ip_cidr", "source_ip_is_private", "ip_cidr", "ip_is_private", "ip_accept_any",
        "source_port", "source_port_range", "port", "port_range",
        "process_name", "process_path", "process_path_regex", "package_name", "user", "user_id",
        "clash_mode", "network_type", "network_is_expensive", "network_is_constrained",
        "wifi_ssid", "wifi_bssid", "query_type", "rule_set",
    )

    private fun sizeOf(path: String): Long =
        (NSFileManager.defaultManager.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)
            ?.longLongValue ?: 0L

    private fun pathFor(tag: String): String = "$dir/$tag.srs"

    private fun hasLocal(tag: String): Boolean = sizeOf(pathFor(tag)) >= MIN_SRS_BYTES

    /**
     * Rewrites a sing-box config so it needs no network to start. Returns the config unchanged when it
     * declares no remote rule-sets (the common case — geo rule-sets only appear with a routing profile
     * or the RU-bypass / ad-block toggles).
     */
    fun localize(configJson: String, log: (String) -> Unit): String {
        val root = runCatching { Json.parseToJsonElement(configJson) as? JsonObject }.getOrNull() ?: return configJson
        val route = root["route"] as? JsonObject ?: return configJson
        val declared = route["rule_set"] as? JsonArray ?: return configJson

        val missing = mutableMapOf<String, String>()
        val keptSets = buildJsonArray {
            declared.forEach { element ->
                val entry = element as? JsonObject ?: return@forEach
                val tag = (entry["tag"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val isRemote = (entry["type"] as? JsonPrimitive)?.contentOrNull == "remote"
                if (!isRemote || tag.isEmpty()) {
                    add(entry)
                    return@forEach
                }
                if (hasLocal(tag)) {
                    add(
                        buildJsonObject {
                            put("type", "local")
                            put("tag", tag)
                            put("format", (entry["format"] as? JsonPrimitive)?.contentOrNull ?: "binary")
                            put("path", pathFor(tag))
                        }
                    )
                } else {
                    missing[tag] = (entry["url"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                }
            }
        }
        if (missing.isEmpty()) {
            // Every set is on disk: only the remote→local rewrite, routing is untouched.
            return json.encodeToString(JsonObject.serializer(), replaceRoute(root, route, keptSets, route["rules"] as? JsonArray))
        }

        IosSharedStore.writeText(
            WANTED_FILE,
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject { missing.forEach { (tag, url) -> put(tag, url) } }
            )
        )
        log(
            "Rule-sets not downloaded yet (${missing.keys.joinToString(", ")}): starting without them " +
                "so the tunnel comes up now — they are fetched in the background and apply on the next connect"
        )

        val keptRules = (route["rules"] as? JsonArray)?.let { rules ->
            buildJsonArray {
                rules.forEach { element ->
                    val rule = element as? JsonObject ?: return@forEach
                    pruneRule(rule, missing.keys)?.let { add(it) }
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), replaceRoute(root, route, keptSets, keptRules))
    }

    /** The same config with `route.rule_set` / `route.rules` replaced. */
    private fun replaceRoute(
        root: JsonObject,
        route: JsonObject,
        ruleSets: JsonArray,
        rules: JsonArray?,
    ): JsonObject = buildJsonObject {
        root.forEach { (key, value) -> if (key != "route") put(key, value) }
        put(
            "route",
            buildJsonObject {
                route.forEach { (key, value) ->
                    if (key != "rule_set" && key != "rules") put(key, value)
                }
                if (ruleSets.isNotEmpty()) put("rule_set", ruleSets)
                if (rules != null) put("rules", rules)
            }
        )
    }

    /**
     * Drops [dropped] tags from a rule's `rule_set`. Returns the rule without them, or null when the
     * rule has nothing left to match on.
     */
    private fun pruneRule(rule: JsonObject, dropped: Set<String>): JsonObject? {
        val ruleSet = rule["rule_set"] ?: return rule
        val tags = when (ruleSet) {
            is JsonArray -> ruleSet.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOf(ruleSet.contentOrNull.orEmpty())
            else -> return rule
        }
        val remaining = tags.filter { it.isNotEmpty() && it !in dropped }
        if (remaining.size == tags.size) return rule
        val stillMatches = remaining.isNotEmpty() ||
            rule.keys.any { it != "rule_set" && it in MATCHER_KEYS }
        if (!stillMatches) return null
        return buildJsonObject {
            rule.forEach { (key, value) -> if (key != "rule_set") put(key, value) }
            if (remaining.isNotEmpty()) {
                put("rule_set", buildJsonArray { remaining.forEach { add(JsonPrimitive(it)) } })
            }
        }
    }

    /**
     * Downloads whatever the last connect reported missing. Runs in the APP: at launch on whatever
     * network is there, and again once the tunnel is up — which is when a blocked raw.githubusercontent
     * .com actually becomes reachable. Returns true if anything new landed.
     */
    fun fetchWanted(): Boolean {
        val text = IosSharedStore.readText(WANTED_FILE)?.takeIf { it.isNotBlank() } ?: return false
        val wanted = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return false
        var fetched = false
        val stillMissing = buildJsonObject {
            wanted.forEach { (tag, element) ->
                val url = (element as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (hasLocal(tag)) {
                    fetched = true
                    return@forEach
                }
                if (url.isBlank() || !download(url, pathFor(tag))) put(tag, url) else fetched = true
            }
        }
        IosSharedStore.writeText(
            WANTED_FILE,
            if (stillMissing.isEmpty()) "" else json.encodeToString(JsonObject.serializer(), stillMissing)
        )
        return fetched
    }

    private fun download(url: String, target: String): Boolean {
        val data = NSURL.URLWithString(url)?.let { NSData.dataWithContentsOfURL(it) } ?: return false
        data.writeToFile(target, true)
        return sizeOf(target) >= MIN_SRS_BYTES
    }
}
