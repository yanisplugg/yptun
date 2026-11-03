package org.olcbox.app.data.importer

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.OpenFluxCarrier
import org.olcbox.app.data.model.OpenFluxConfig
import org.olcbox.app.data.share.inflateOrNull

/**
 * Parses and generates OpenFlux connection URIs:
 *
 * 0. The client link an OpenFlux exit prints with `--share` (core `share` package) and the panel hands out:
 *    `openflux://v1/<base64url(raw DEFLATE(JSON))>`, JSON = `{name, negotiate, codec, secret, context, mode,
 *    transports:[{type, name, url, priority, dial, uid, token, cookies}]}` — see [parseV1]. One transport runs as a
 *    single carrier; several (or a direct one) become a multi-transport session (needs `secret`). A lone MAX one
 *    carries `uid` + `token`, an Avito one the client `cookies`.
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
    private const val PREFIX = "openflux://v1/"

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
        if (trimmed.startsWith(PREFIX, ignoreCase = true)) {
            return parseV1(trimmed)?.let { it.config to it.name }
        }

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

    data class OpenFluxLink(val name: String, val config: OpenFluxConfig)

    @OptIn(ExperimentalEncodingApi::class)
    fun parseV1(link: String): OpenFluxLink? {
        val t = link.trim()
        if (!t.startsWith(PREFIX, ignoreCase = true)) return null
        val name = if ('#' in t) UriCodec.percentDecode(t.substringAfter('#')).trim() else ""
        // Same leniency as the core: whitespace from a wrapped copy, "+/" for "-_", stray "=" padding.
        val body = t.substring(PREFIX.length).substringBefore('#')
            .filterNot { it.isWhitespace() || it == ' ' || it == '​' }
            .replace('+', '-').replace('/', '_').trimEnd('=')
        val packed = runCatching { Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(body) }.getOrNull() ?: return null
        val raw = inflateOrNull(packed) ?: return null
        val root = runCatching { Json.parseToJsonElement(raw.decodeToString()).jsonObject }.getOrNull() ?: return null

        if (root.str("mode").isNotEmpty()) return null
        val secret = root.str("secret")
        val negotiate = (root["negotiate"] as? JsonPrimitive)?.booleanOrNull == true
        if (negotiate && secret.isEmpty()) return null
        val transports = root["transports"] as? JsonArray ?: return null
        val items = transports.map { it as? JsonObject ?: return null }
        if (items.isEmpty()) return null
        val single = items.singleOrNull()
        if (single != null && single.str("type") != OpenFluxConfig.TRANSPORT_DIRECT) {
            val type = single.str("type")
            if (type !in OpenFluxConfig.TRANSPORTS) return null
            if (type == OpenFluxConfig.TRANSPORT_MAX) {
                // The panel's own addition (not in the core's share format): the exit account's `uid` to call and the
                // clients' MAX web `token`. The token makes the link secret, so the panel hands it out encrypted only.
                val uid = single.str("uid")
                val token = single.str("token")
                if (uid.toLongOrNull() == null || token.isEmpty()) return null
                return OpenFluxLink(name.ifBlank { root.str("name") }, OpenFluxConfig(
                    transport = type, maxUid = uid, maxToken = token, secret = secret, context = root.str("context"), negotiate = negotiate,
                ).normalized())
            }
            val url = single.str("url")
            if (!(url.startsWith("http", ignoreCase = true) || (type in ROOM_CARRIERS && url.length >= 16))) return null
            return OpenFluxLink(name.ifBlank { root.str("name") }, OpenFluxConfig(
                transport = type, docUrl = url, secret = secret, context = root.str("context"), negotiate = negotiate,
                // Avito needs the account cookies on the client too; the panel puts them in the (encrypted) link.
                avitoCookies = if (type == OpenFluxConfig.TRANSPORT_AVITO) single.str("cookies") else "",
            ).normalized())
        }
        // A session: the core only runs one without an encryption key.
        if (secret.isEmpty()) return null
        val carriers = items.map { tr ->
            val type = tr.str("type")
            if (type !in OpenFluxConfig.SESSION_CARRIERS) return null
            OpenFluxCarrier(
                type = type, url = tr.str("url"), dial = tr.str("dial"),
                priority = tr.str("priority").toIntOrNull() ?: OpenFluxConfig.DEFAULT_PRIORITY,
            ).also { if (!it.isComplete()) return null }
        }
        if (carriers.map { it.type }.toSet().size != carriers.size) return null
        return OpenFluxLink(name.ifBlank { root.str("name") }, OpenFluxConfig(
            carriers = carriers, secret = secret, context = root.str("context"), negotiate = negotiate,
        ).normalized())
    }

    private val ROOM_CARRIERS = setOf(OpenFluxConfig.TRANSPORT_CUPS, OpenFluxConfig.TRANSPORT_AVITO)

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty().trim()
}
