package org.olcbox.app.data.importer

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.olcbox.app.data.model.ProxyProfile

/** A self-contained `.ovpn` profile → sing-box 1.14 `openvpn-client` endpoint JSON. */
object OpenVpnParser {
    private val INLINE_BLOCKS = setOf(
        "ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2", "secret", "auth-user-pass"
    )

    fun looksLikeOpenVpn(text: String): Boolean {
        val names = directiveLines(text).mapNotNull { tokenize(it).firstOrNull()?.lowercase() }.toSet()
        val lower = text.lowercase()
        return "remote" in names &&
            ("client" in names || "tls-client" in names || "secret" in names || "<ca>" in lower || "<secret>" in lower)
    }

    fun parse(text: String): ProxyProfile? {
        if (!looksLikeOpenVpn(text)) return null
        val parsed = splitBlocks(text)
        val directives = parsed.lines.mapNotNull { line ->
            tokenize(line).takeIf { it.isNotEmpty() }
        }
        fun all(name: String): List<List<String>> = directives.filter { it[0].equals(name, true) }
        fun first(name: String): List<String>? = all(name).firstOrNull()
        fun value(name: String): String? = first(name)?.getOrNull(1)?.takeIf { it.isNotBlank() }
        fun flag(name: String): Boolean = first(name) != null

        val defaultNetwork = normalizeNetwork(value("proto").orEmpty())
        val remotes = all("remote").mapNotNull { parts ->
            val host = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val explicitPort = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it in 1..65535 }
            val port = explicitPort ?: 1194
            val networkToken = if (explicitPort != null) parts.getOrNull(3) else parts.getOrNull(2)
            val network = normalizeNetwork(networkToken.orEmpty()).ifBlank { defaultNetwork }
            Remote(host, port, network.ifBlank { "udp" })
        }
        if (remotes.isEmpty()) return null

        val staticKey = parsed.blocks["secret"]
        val mode = if (!staticKey.isNullOrBlank() || first("secret") != null) "static_key" else "tls"
        val ca = parsed.blocks["ca"]
        val fingerprint = all("peer-fingerprint").mapNotNull { it.getOrNull(1) }
            .map { it.removePrefix("SHA256:").removePrefix("sha256:").replace(":", "").lowercase() }
            .filter { it.length == 64 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' } }
        // A path next to an .ovpn file cannot be resolved after the text is imported; self-contained
        // profiles (the standard distribution format) carry a CA or a peer fingerprint inline.
        if (mode == "tls" && ca.isNullOrBlank() && fingerprint.isEmpty()) return null
        // Likewise, silently dropping a referenced client key/control key/auth file would create a
        // profile that imports successfully but can never authenticate. Only embedded material is
        // portable once the selected file has been read into text.
        val materialDirectives = listOf("ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2", "secret")
        if (materialDirectives.any { name -> first(name) != null && parsed.blocks[name].isNullOrBlank() }) return null
        if ((parsed.blocks["cert"] == null) != (parsed.blocks["key"] == null)) return null
        if (first("auth-user-pass") != null && parsed.blocks["auth-user-pass"].isNullOrBlank()) return null
        if (mode == "static_key" && !parsed.blocks["auth-user-pass"].isNullOrBlank()) return null
        if (mode == "static_key" && first("ifconfig")?.let { it.size >= 3 } != true) return null

        val endpoint = buildJsonObject {
            put("type", ProxyProfile.TYPE_OPENVPN)
            put("tag", "OpenVPN ${remotes.first().host}")
            put("mode", mode)
            put("network", defaultNetwork.ifBlank { remotes.first().network })
            if (remotes.size == 1) {
                put("server", remotes.first().host)
                put("server_port", remotes.first().port)
            } else {
                putJsonArray("servers") {
                    remotes.forEach { remote ->
                        add(buildJsonObject {
                            put("server", remote.host)
                            put("server_port", remote.port)
                            put("network", remote.network)
                        })
                    }
                }
            }
            if (flag("remote-random")) put("remote_random", true)
            value("auth-retry")?.let { put("auth_retry", it) }
            value("topology")?.let { put("topology", it) }
            value("tun-mtu")?.toIntOrNull()?.takeIf { it > 0 }?.let { put("mtu", it) }
            value("mssfix")?.toIntOrNull()?.takeIf { it > 0 }?.let { put("mss_fix", it) }
            value("auth")?.let { put("auth", it) }
            if (mode == "tls") {
                value("data-ciphers")?.split(':')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { ciphers ->
                    putJsonArray("data_ciphers") { ciphers.forEach { add(it) } }
                }
                (value("data-ciphers-fallback") ?: value("cipher"))?.let { put("data_ciphers_fallback", it) }
            } else {
                value("cipher")?.let { put("cipher", it) }
            }
            value("compress")?.let { put("compression", it) }
            value("comp-lzo")?.let { put("compression_lzo", it) }
            value("allow-compression")?.let { put("allow_compression", it) }
            if (mode == "tls" && (flag("route-nopull") || flag("route-no-pull"))) put("route_no_pull", true)
            if (flag("block-ipv6")) put("block_ipv6", true)
            first("redirect-gateway")?.let { redirect ->
                put("redirect_gateway", true)
                redirect.drop(1).takeIf { it.isNotEmpty() }?.let { flags ->
                    putJsonArray("redirect_gateway_flags") { flags.forEach { add(it) } }
                }
            }
            duration(value("ping"))?.let { put("ping_interval", it) }
            duration(value("ping-restart"))?.let { put("ping_restart", it) }
            first("keepalive")?.let { keepalive ->
                duration(keepalive.getOrNull(1))?.let { put("ping_interval", it) }
                duration(keepalive.getOrNull(2))?.let { put("ping_restart", it) }
            }
            if (mode == "tls") {
                value("reneg-sec")?.let { seconds ->
                    if (seconds == "0") put("renegotiate_disabled", true)
                    else duration(seconds)?.let { put("renegotiate_interval", it) }
                }
                duration(value("tls-timeout"))?.let { put("tls_timeout", it) }
                duration(value("hand-window"))?.let { put("handshake_window", it) }
            }
            value("explicit-exit-notify")?.toIntOrNull()?.let { put("explicit_exit_notify", it) }
            (if (mode == "tls") all("pull-filter") else emptyList()).mapNotNull { filter ->
                val action = filter.getOrNull(1)?.lowercase()?.takeIf { it in setOf("accept", "ignore", "reject") }
                    ?: return@mapNotNull null
                val filterText = filter.drop(2).joinToString(" ").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                action to filterText
            }.takeIf { it.isNotEmpty() }?.let { filters ->
                putJsonArray("pull_filters") {
                    filters.forEach { (action, filterText) ->
                        add(buildJsonObject { put("action", action); put("text", filterText) })
                    }
                }
            }

            val credentials = parsed.blocks["auth-user-pass"]
                ?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList().orEmpty()
            credentials.getOrNull(0)?.let { put("username", it) }
            credentials.getOrNull(1)?.let { put("password", it) }

            if (mode == "static_key") {
                staticKey?.let { key -> putJsonArray("static_key") { add(key) } }
                keyDirection(value("key-direction"))?.let { put("key_direction", it) }
                value("ifconfig")?.let { local ->
                    val peer = first("ifconfig")?.getOrNull(2)
                    val address = when {
                        '/' in local -> local
                        ':' in local -> "$local/128"
                        else -> "$local/32"
                    }
                    putJsonArray("address") { add(address) }
                    peer?.let { put(if (':' in it) "peer_address_ipv6" else "peer_address", it) }
                }
            } else {
                putJsonObject("tls") {
                    ca?.let { putJsonArray("certificate") { add(it) } }
                    parsed.blocks["cert"]?.let { putJsonArray("client_certificate") { add(it) } }
                    parsed.blocks["key"]?.let { putJsonArray("client_key") { add(it) } }
                    if (fingerprint.isNotEmpty()) putJsonArray("peer_fingerprint") { fingerprint.forEach { add(it) } }
                    first("verify-x509-name")?.getOrNull(1)?.let { put("server_name", it) }
                    first("verify-x509-name")?.getOrNull(2)?.normalizeNameType()?.let { put("server_name_type", it) }
                    value("remote-cert-tls")?.let { put("remote_certificate_tls", it) }
                    value("remote-cert-eku")?.let { put("remote_certificate_eku", it) }
                    value("tls-cert-profile")?.let { put("certificate_profile", it) }
                    value("ns-cert-type")?.let { put("ns_certificate_type", it) }
                    all("remote-cert-ku").flatMap { it.drop(1) }.takeIf { it.isNotEmpty() }?.let { usages ->
                        putJsonArray("remote_certificate_ku") { usages.forEach { add(it) } }
                    }
                    value("tls-version-min")?.let { put("version_min", it.removeSuffix("-or-highest")) }
                    value("tls-version-max")?.let { put("version_max", it) }
                    value("tls-cipher")?.let { put("cipher", it) }
                    value("tls-groups")?.let { put("groups", it) }

                    val control = listOf("tls-auth", "tls-crypt", "tls-crypt-v2")
                        .firstNotNullOfOrNull { name -> parsed.blocks[name]?.let { Triple(name, it, value("key-direction")) } }
                    if (control != null) {
                        putJsonObject("control_wrap") {
                            put("type", control.first.replace('-', '_'))
                            putJsonArray("key") { add(control.second) }
                            if (control.first == "tls-auth") keyDirection(control.third)?.let { put("direction", it) }
                        }
                    }
                }
            }
        }

        val remote = remotes.first()
        return ProxyProfile(
            tag = "OpenVPN ${remote.host}",
            type = ProxyProfile.TYPE_OPENVPN,
            server = remote.host,
            serverPort = remote.port,
            rawOutbound = endpoint.toString(),
        )
    }

    private data class Remote(val host: String, val port: Int, val network: String)
    private data class Parsed(val lines: List<String>, val blocks: Map<String, String>)

    private fun splitBlocks(text: String): Parsed {
        val lines = mutableListOf<String>()
        val blocks = linkedMapOf<String, String>()
        var name: String? = null
        var body = StringBuilder()
        text.replace("\r\n", "\n").lineSequence().forEach { raw ->
            val line = raw.trim()
            val current = name
            if (current != null) {
                if (line.equals("</$current>", true)) {
                    blocks[current] = body.toString().trim()
                    name = null
                    body = StringBuilder()
                } else {
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(raw)
                }
            } else {
                val opening = line.takeIf { it.startsWith('<') && it.endsWith('>') && !it.startsWith("</") }
                    ?.removePrefix("<")?.removeSuffix(">")?.trim()?.lowercase()
                if (opening in INLINE_BLOCKS) {
                    name = opening
                } else if (line.isNotEmpty() && !line.startsWith('#') && !line.startsWith(';')) {
                    lines += line
                }
            }
        }
        return Parsed(lines, blocks)
    }

    private fun directiveLines(text: String): List<String> = splitBlocks(text).lines

    private fun tokenize(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        fun flush() {
            if (current.isNotEmpty()) {
                result += current.toString()
                current.clear()
            }
        }
        line.forEach { ch ->
            when {
                escaped -> { current.append(ch); escaped = false }
                ch == '\\' && quote != '\'' -> escaped = true
                quote != null && ch == quote -> quote = null
                quote == null && (ch == '\'' || ch == '"') -> quote = ch
                quote == null && ch.isWhitespace() -> flush()
                else -> current.append(ch)
            }
        }
        flush()
        return result
    }

    private fun normalizeNetwork(value: String): String = when (value.lowercase()) {
        "tcp", "tcp4", "tcp6", "tcp-client" -> value.lowercase().replace("-client", "")
        "udp", "udp4", "udp6" -> value.lowercase()
        else -> ""
    }

    private fun duration(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }?.let {
        if (it.all(Char::isDigit)) "${it}s" else it
    }

    private fun keyDirection(value: String?): String? = when (value?.trim()?.lowercase()) {
        "1", "client" -> "client"
        "0", "server" -> "server"
        else -> null
    }

    private fun String.normalizeNameType(): String? = when (lowercase()) {
        "name", "name-prefix", "subject" -> lowercase()
        else -> null
    }
}
