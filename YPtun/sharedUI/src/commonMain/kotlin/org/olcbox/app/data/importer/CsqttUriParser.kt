package org.olcbox.app.data.importer

import org.olcbox.app.data.model.VkTurnConfig

/**
 * The csqtt connection link the server's web panel emits (rust-server/web_panel.rs `buildCsqttLink`):
 *
 * ```
 * csqtt://connect?v=2&host=<esc>&peer=<port>&password=<esc>[&hashes=<hash>+<hash>…][#<name>]
 * ```
 *
 * `host` is the server, `peer` its DTLS port (46000 by default), `hashes` the VK call hashes joined by
 * `+`. Older links are `csqtt://<password>@host:port[#name]`. Maps onto a VK-TURN location whose core is
 * [VkTurnConfig.CORE_CSQTT].
 */
object CsqttUriParser {

    const val SCHEME = "csqtt://"

    data class CsqttLink(
        val host: String,
        val port: Int,
        val password: String,
        /** VK hashes, ONE PER LINE — the storage format of [VkTurnConfig.vkLink]. */
        val hashes: String,
        val name: String = "",
    )

    fun parse(uri: String): CsqttLink? {
        val trimmed = uri.trim()
        if (!trimmed.startsWith(SCHEME, ignoreCase = true)) return null
        val afterScheme = trimmed.substring(SCHEME.length).trim()
        val fragment = if ('#' in afterScheme) {
            val rawFrag = afterScheme.substringAfter('#').trim()
            runCatching { UriCodec.percentDecode(rawFrag) }.getOrDefault(rawFrag)
        } else ""
        val withoutFrag = afterScheme.substringBefore('#').trim()
        val query = withoutFrag.substringAfter('?', "")
        val path = withoutFrag.substringBefore('?').trimEnd('/')

        if (path.equals("connect", ignoreCase = true) && query.isNotBlank()) {
            return parseV2(query, fragment)
        }
        if (query.isNotBlank()) {
            val v2 = parseV2(query, fragment)
            if (v2 != null) return v2
        }
        return parseLegacy(withoutFrag, fragment)
    }

    /**
     * Extracts a remote subscription HTTP(S) URL if the link is a csqtt subscription pointer,
     * e.g. `csqtt://https://example.com/sub` or `csqtt://sub?url=https%3A%2F%2F...`.
     */
    fun extractSubscriptionUrl(uri: String): String? {
        val trimmed = uri.trim()
        if (!trimmed.startsWith(SCHEME, ignoreCase = true)) return null
        val rest = trimmed.substring(SCHEME.length).trim()
        if (rest.startsWith("http://", ignoreCase = true) || rest.startsWith("https://", ignoreCase = true)) {
            return rest
        }
        if (rest.startsWith("sub?", ignoreCase = true) || rest.startsWith("subscription?", ignoreCase = true)) {
            val q = rest.substringAfter('?')
            val p = UriCodec.parseQuery(q)
            p["url"]?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun parseV2(query: String, fragment: String = ""): CsqttLink? {
        // The panel joins hashes with a literal '+', which a form decoder would turn into spaces —
        // either way it is a separator, so both are split below.
        val p = UriCodec.parseQuery(query.replace("&amp;", "&"))
        val host = (p["host"] ?: p["server"] ?: p["peer_host"])?.trim().orEmpty()
        val port = (p["peer"] ?: p["port"] ?: p["peer_port"])?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: VkTurnConfig.DEFAULT_CSQTT_PORT
        val password = (p["password"] ?: p["pass"] ?: p["key"])?.trim().orEmpty()
        if (host.isBlank() || password.isBlank() || host.any(Char::isWhitespace) || password.any(Char::isWhitespace)) return null
        val hashes = splitHashes(p["hashes"].orEmpty())
        val name = p["name"]?.trim()?.takeIf { it.isNotBlank() }
            ?: p["title"]?.trim()?.takeIf { it.isNotBlank() }
            ?: p["remarks"]?.trim()?.takeIf { it.isNotBlank() }
            ?: fragment.takeIf { it.isNotBlank() }
            ?: ""
        return CsqttLink(host, port, password, hashes.joinToString("\n"), name)
    }

    /** `csqtt://<password>@host:port` or `csqtt://host:port?password=...` */
    private fun parseLegacy(rest: String, fragment: String = ""): CsqttLink? {
        val authority = rest.substringBefore('/').substringBefore('?')
        val password = authority.substringBeforeLast('@', "").takeIf { it.isNotBlank() }
            ?.let { UriCodec.percentDecode(it) } ?: ""
        val hostPort = if ('@' in authority) authority.substringAfterLast('@') else authority
        val port = hostPort.substringAfterLast(':', "").toIntOrNull()?.takeIf { it in 1..65535 }
            ?: VkTurnConfig.DEFAULT_CSQTT_PORT
        val host = hostPort.substringBeforeLast(':').removePrefix("[").removeSuffix("]").trim()
        if (host.isBlank()) return null
        val query = rest.substringAfter('?', "")
        val p = if (query.isNotBlank()) UriCodec.parseQuery(query) else emptyMap()
        val effectivePass = password.ifBlank { p["password"] ?: p["pass"] ?: "" }
        if (effectivePass.isBlank()) return null
        val hashes = splitHashes(p["hashes"].orEmpty())
        val name = p["name"]?.trim()?.takeIf { it.isNotBlank() }
            ?: p["title"]?.trim()?.takeIf { it.isNotBlank() }
            ?: p["remarks"]?.trim()?.takeIf { it.isNotBlank() }
            ?: fragment.takeIf { it.isNotBlank() }
            ?: ""
        return CsqttLink(host, port, effectivePass, hashes.joinToString("\n"), name)
    }

    /** Re-emits the connection link for a stored csqtt VK-TURN location (round-trips [parse]). */
    fun compose(vk: VkTurnConfig, name: String = ""): String {
        val addr = vk.csqttPeerAddr()
        val host = addr.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        val port = addr.substringAfterLast(':').toIntOrNull() ?: VkTurnConfig.DEFAULT_CSQTT_PORT
        val hashes = splitHashes(vk.vkLink)
        return buildString {
            append(SCHEME).append("connect?v=2&host=").append(encode(host))
            append("&peer=").append(port)
            append("&password=").append(encode(vk.csqttPassword.trim()))
            if (hashes.isNotEmpty()) append("&hashes=").append(hashes.joinToString("+") { encode(it) })
            val label = name.trim().takeIf { it.isNotBlank() }
            if (label != null) append("#").append(encode(label))
        }
    }

    private fun splitHashes(raw: String): List<String> =
        raw.split(',', ';', '+', '\n', '\r', '\t', ' ').map { it.trim() }.filter { it.isNotEmpty() }

    /** Minimal RFC 3986 percent-encoding for query values. */
    private fun encode(value: String): String = buildString {
        for (b in value.encodeToByteArray()) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-_.~") append(ch)
            else {
                append('%'); append("0123456789ABCDEF"[c shr 4]); append("0123456789ABCDEF"[c and 0x0F])
            }
        }
    }
}
