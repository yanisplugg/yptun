package org.olcbox.app.data.importer

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import org.olcbox.app.data.model.OpenFluxCarrier
import org.olcbox.app.data.model.OpenFluxConfig
import org.olcbox.app.data.share.inflateOrNull

/**
 * The client link an OpenFlux exit prints with `--share` (core `share` package):
 * `openflux://v1/<base64url(raw DEFLATE(JSON))>`, JSON = `{name, negotiate, codec, secret, context, mode,
 * transports:[{type, name, url, priority, dial}]}`.
 *
 * One transport runs as a single carrier (`--transport T --url U`); several (or a direct one) become a
 * multi-transport session (`--transports`, needs the link's `secret`). Optionally encrypted with the link's
 * `secret` (AES-256-GCM, KDF `context`) and `negotiate` (Session-only; without it the Classic-compatible
 * mode). A link is importable when every transport is a Yandex/Mail.ru/cups.online/Avito/direct one with
 * its coordinate (or a lone MAX one with `uid` + `token`) and there is no stream mode; anything else yields null rather than a location that
 * cannot connect.
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
                return OpenFluxLink(root.str("name"), OpenFluxConfig(
                    transport = type, maxUid = uid, maxToken = token, secret = secret, context = root.str("context"), negotiate = negotiate,
                ).normalized())
            }
            val url = single.str("url")
            if (!(url.startsWith("http", ignoreCase = true) || (type in ROOM_CARRIERS && url.length >= 16))) return null
            return OpenFluxLink(root.str("name"), OpenFluxConfig(
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
        return OpenFluxLink(root.str("name"), OpenFluxConfig(
            carriers = carriers, secret = secret, context = root.str("context"), negotiate = negotiate,
        ).normalized())
    }

    private val ROOM_CARRIERS = setOf(OpenFluxConfig.TRANSPORT_CUPS, OpenFluxConfig.TRANSPORT_AVITO)

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty().trim()
}
