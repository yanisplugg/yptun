package org.olcbox.app.ui.features.home

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.vpn.xray.XrayConfig

private val prettyJson = Json { prettyPrint = true }

/** The clipboard on Android refuses payloads near 1 MB; stay well below. */
private const val MAX_SUBSCRIPTION_JSON_CHARS = 700_000

/**
 * The JSON of a subscription's servers as one array: the panel's own Xray config when the server came as JSON
 * (`rawXrayConfig`), otherwise a ready client config built from its link. Servers that cannot be expressed as an
 * Xray config (AmneziaWG, Hysteria2, ...) are skipped. Stops before the clipboard limit; null when nothing fits.
 */
fun subscriptionJson(locations: List<LocationItem>): String? {
    val items = mutableListOf<JsonElement>()
    var size = 2
    for (location in locations) {
        val profile = location.config?.proxy ?: continue
        val element = profile.rawXrayConfig?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
            ?: runCatching {
                val built = XrayConfig.build(profile = profile, listenPort = 10808, logLevel = "warning", blockQuic = false)
                Json.parseToJsonElement(built)
            }.getOrNull()
            ?: continue
        val named = if (element is JsonObject && "remarks" !in element) {
            JsonObject(mapOf("remarks" to JsonPrimitive(location.fullName)) + element)
        } else element
        size += named.toString().length + 2
        if (size > MAX_SUBSCRIPTION_JSON_CHARS) break
        items += named
    }
    return if (items.isEmpty()) null else prettyJson.encodeToString(JsonElement.serializer(), JsonArray(items))
}
