package org.olcbox.app.data.importer

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import org.olcbox.app.data.model.OpenFluxConfig
import org.olcbox.app.data.share.inflateOrNull

/**
 * The client link an OpenFlux exit prints with `--share` (core `share` package):
 * `openflux://v1/<base64url(raw DEFLATE(JSON))>`, JSON = `{name, negotiate, codec, secret, context, mode,
 * transports:[{type, name, url, priority, dial}]}`.
 *
 * The client engine runs the classic single-carrier tunnel only (`--transport T --url U`), so a link is
 * importable when it names exactly one Yandex/Mail.ru/cups.online transport with a URL and carries no
 * session, secret or stream mode; anything else yields null rather than a location that cannot connect.
 */
object OpenFluxUriParser {

    const val SCHEME = "openflux://"
    private const val PREFIX = "openflux://v1/"

    data class OpenFluxLink(val name: String, val config: OpenFluxConfig)

    @OptIn(ExperimentalEncodingApi::class)
    fun parse(link: String): OpenFluxLink? {
        val t = link.trim()
        if (!t.startsWith(PREFIX)) return null
        // Same leniency as the core: whitespace from a wrapped copy, "+/" for "-_", stray "=" padding.
        val body = t.removePrefix(PREFIX)
            .filterNot { it.isWhitespace() || it == ' ' || it == '​' }
            .replace('+', '-').replace('/', '_').trimEnd('=')
        val packed = runCatching { Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(body) }.getOrNull() ?: return null
        val raw = inflateOrNull(packed) ?: return null
        val root = runCatching { Json.parseToJsonElement(raw.decodeToString()).jsonObject }.getOrNull() ?: return null

        if (root.str("secret").isNotEmpty() || root.str("mode").isNotEmpty()) return null
        if ((root["negotiate"] as? JsonPrimitive)?.booleanOrNull == true) return null
        val transports = root["transports"] as? JsonArray ?: return null
        val tr = transports.singleOrNull() as? JsonObject ?: return null
        val type = tr.str("type")
        if (type !in OpenFluxConfig.TRANSPORTS || type == OpenFluxConfig.TRANSPORT_MAX) return null
        val url = tr.str("url")
        if (!url.startsWith("http", ignoreCase = true)) return null
        return OpenFluxLink(root.str("name"), OpenFluxConfig(transport = type, docUrl = url).normalized())
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty().trim()
}
