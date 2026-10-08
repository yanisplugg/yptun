package org.olcbox.app.data.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.OpenFluxConfig

/**
 * Parses and generates OpenFlux connection URIs:
 *
 * 1. URI query scheme:
 *    `openflux://config?transport=yandex&doc_url=https%3A%2F%2F...#Name`
 *    `openflux://yandex?url=https%3A%2F%2F...#Name`
 *    `openflux://config?transport=oneme&max_token=...&max_uid=...#Name`
 * 2. Bare document URL:
 *    `openflux://https://docs.yandex.ru/...#Name`
 * 3. Base64 JSON payload:
 *    `openflux://<base64>` where base64 decodes to JSON `{ "transport": "yandex", "doc_url": "...", "name": "..." }`
 */
object OpenFluxUriParser {

    const val SCHEME = "openflux://"
    const val SCHEME_SHORT = "openflux:"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun isMatch(line: String): Boolean {
        val t = line.trim()
        return t.startsWith(SCHEME, ignoreCase = true) || t.startsWith(SCHEME_SHORT, ignoreCase = true)
    }

    /**
     * Parses an `openflux://...` line into an [OpenFluxConfig] and optional profile name.
     */
    fun parse(rawUri: String): Pair<OpenFluxConfig, String>? {
        val trimmed = rawUri.trim()
        if (!isMatch(trimmed)) return null

        val prefix = if (trimmed.startsWith(SCHEME, ignoreCase = true)) SCHEME else SCHEME_SHORT
        val rest = trimmed.substring(prefix.length)
        val nameFromHash = if (rest.contains('#')) UriCodec.percentDecode(rest.substringAfter('#').trim()) else ""
        val withoutHash = rest.substringBefore('#').trim()

        // 1. Try base64 payload
        val b64Decoded = SubscriptionDecoder.decodeBase64Chunk(withoutHash)
        if (b64Decoded != null && b64Decoded.trim().startsWith("{")) {
            val fromJson = parseJson(b64Decoded.trim())
            if (fromJson != null) {
                val name = nameFromHash.ifBlank { fromJson.second }
                return fromJson.first to name
            }
        }

        // 2. Query / URI parameters
        val questionMark = withoutHash.indexOf('?')
        val hostPart = if (questionMark >= 0) withoutHash.substring(0, questionMark).trim() else withoutHash
        val queryPart = if (questionMark >= 0) withoutHash.substring(questionMark + 1).trim() else ""

        val params = if (queryPart.isNotEmpty()) UriCodec.parseQuery(queryPart) else emptyMap()

        var transport = params["transport"] ?: params["type"] ?: ""
        if (transport.isBlank()) {
            val lowerHost = hostPart.lowercase().trimEnd('/')
            if (lowerHost in OpenFluxConfig.TRANSPORTS) {
                transport = lowerHost
            }
        }

        var docUrl = params["doc_url"] ?: params["docurl"] ?: params["url"] ?: params["doc"] ?: ""
        if (docUrl.isBlank() && hostPart.startsWith("http", ignoreCase = true)) {
            docUrl = if (queryPart.isNotEmpty()) "$hostPart?$queryPart" else hostPart
        }

        val maxToken = params["max_token"] ?: params["maxtoken"] ?: params["token"] ?: ""
        val maxUid = params["max_uid"] ?: params["maxuid"] ?: params["uid"] ?: ""
        val dns = params["dns"] ?: params["dns_server"] ?: params["dnsserver"] ?: OpenFluxConfig.DEFAULT_DNS
        val debug = params["debug"]?.equals("true", ignoreCase = true) == true
        val proxyLink = params["proxy_link"] ?: params["proxylink"] ?: params["proxy"] ?: ""
        val name = nameFromHash.ifBlank { params["name"] ?: "" }

        val cfg = OpenFluxConfig(
            transport = transport.ifBlank { OpenFluxConfig.TRANSPORT_YANDEX },
            docUrl = docUrl,
            maxToken = maxToken,
            maxUid = maxUid,
            dnsServer = dns,
            debug = debug,
            proxyLink = proxyLink
        ).normalized()

        if (!cfg.isComplete()) return null
        return cfg to name
    }

    fun parseJson(jsonText: String): Pair<OpenFluxConfig, String>? {
        val root = runCatching { json.parseToJsonElement(jsonText).jsonObject }.getOrNull() ?: return null
        val transport = root["transport"]?.jsonPrimitive?.contentOrNull
            ?: root["type"]?.jsonPrimitive?.contentOrNull
            ?: OpenFluxConfig.TRANSPORT_YANDEX
        val docUrl = root["doc_url"]?.jsonPrimitive?.contentOrNull
            ?: root["docUrl"]?.jsonPrimitive?.contentOrNull
            ?: root["url"]?.jsonPrimitive?.contentOrNull
            ?: ""
        val maxToken = root["max_token"]?.jsonPrimitive?.contentOrNull
            ?: root["maxToken"]?.jsonPrimitive?.contentOrNull
            ?: root["token"]?.jsonPrimitive?.contentOrNull
            ?: ""
        val maxUid = root["max_uid"]?.jsonPrimitive?.contentOrNull
            ?: root["maxUid"]?.jsonPrimitive?.contentOrNull
            ?: root["uid"]?.jsonPrimitive?.contentOrNull
            ?: ""
        val dns = root["dns"]?.jsonPrimitive?.contentOrNull
            ?: OpenFluxConfig.DEFAULT_DNS
        val debug = root["debug"]?.jsonPrimitive?.booleanOrNull ?: false
        val proxyLink = root["proxy_link"]?.jsonPrimitive?.contentOrNull
            ?: root["proxyLink"]?.jsonPrimitive?.contentOrNull
            ?: root["proxy"]?.jsonPrimitive?.contentOrNull
            ?: ""
        val name = root["name"]?.jsonPrimitive?.contentOrNull ?: ""

        val cfg = OpenFluxConfig(
            transport = transport,
            docUrl = docUrl,
            maxToken = maxToken,
            maxUid = maxUid,
            dnsServer = dns,
            debug = debug,
            proxyLink = proxyLink
        ).normalized()

        if (!cfg.isComplete()) return null
        return cfg to name
    }

    /** Encodes an [OpenFluxConfig] into a shareable `openflux://...` URI. */
    fun toUri(config: OpenFluxConfig, name: String = ""): String {
        val norm = config.normalized()
        val frag = if (name.isNotBlank()) "#" + UriCodec.percentDecode(name).replace(" ", "%20") else ""
        return if (norm.usesMax()) {
            "openflux://config?transport=${norm.transport}&max_token=${norm.maxToken}&max_uid=${norm.maxUid}$frag"
        } else {
            val encDoc = UriCodec.percentDecode(norm.docUrl)
            "openflux://config?transport=${norm.transport}&doc_url=$encDoc$frag"
        }
    }
}
