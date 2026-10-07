package org.olcbox.app.data.importer

import org.olcbox.app.data.model.VkTurnConfig

/**
 * The csqtt connection link the server's web panel emits (rust-server/web_panel.rs `buildCsqttLink`):
 *
 * ```
 * csqtt://connect?v=2&host=<esc>&peer=<port>&password=<esc>[&hashes=<hash>+<hash>…]
 * ```
 *
 * `host` is the server, `peer` its DTLS port (46000 by default), `hashes` the VK call hashes joined by
 * `+`. Older links are `csqtt://<password>@host:port`. Maps onto a VK-TURN location whose core is
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
    )

    fun parse(uri: String): CsqttLink? {
        val trimmed = uri.trim()
        if (!trimmed.startsWith(SCHEME, ignoreCase = true)) return null
        val rest = trimmed.substring(SCHEME.length)
        val query = rest.substringAfter('?', "")
        if (rest.substringBefore('?').trimEnd('/').equals("connect", ignoreCase = true) && query.isNotBlank()) {
            return parseV2(query)
        }
        return parseLegacy(rest)
    }

    private fun parseV2(query: String): CsqttLink? {
        // The panel joins hashes with a literal '+', which a form decoder would turn into spaces —
        // either way it is a separator, so both are split below.
        val p = UriCodec.parseQuery(query.replace("&amp;", "&"))
        if (p["v"]?.trim() != "2") return null
        val host = p["host"]?.trim().orEmpty()
        val port = p["peer"]?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val password = p["password"]?.trim().orEmpty()
        if (host.isBlank() || password.isBlank() || host.any(Char::isWhitespace) || password.any(Char::isWhitespace)) return null
        val hashes = splitHashes(p["hashes"].orEmpty())
        if (p.containsKey("hashes") && hashes.isEmpty()) return null
        return CsqttLink(host, port, password, hashes.joinToString("\n"))
    }

    /** `csqtt://<password>@host:port` */
    private fun parseLegacy(rest: String): CsqttLink? {
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        val password = authority.substringBeforeLast('@', "").takeIf { it.isNotBlank() }
            ?.let { UriCodec.percentDecode(it) } ?: return null
        val hostPort = authority.substringAfterLast('@')
        val port = hostPort.substringAfterLast(':', "").toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val host = hostPort.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        if (host.isBlank()) return null
        return CsqttLink(host, port, password, "")
    }

    /** Re-emits the connection link for a stored csqtt VK-TURN location (round-trips [parse]). */
    fun compose(vk: VkTurnConfig): String {
        val addr = vk.csqttPeerAddr()
        val host = addr.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        val port = addr.substringAfterLast(':').toIntOrNull() ?: VkTurnConfig.DEFAULT_CSQTT_PORT
        val hashes = splitHashes(vk.vkLink)
        return buildString {
            append(SCHEME).append("connect?v=2&host=").append(encode(host))
            append("&peer=").append(port)
            append("&password=").append(encode(vk.csqttPassword.trim()))
            if (hashes.isNotEmpty()) append("&hashes=").append(hashes.joinToString("+") { encode(it) })
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
