package org.olcbox.app.data.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.ProxyProfile

/**
 * Multi-protocol share-link parser. Recognises vless/vmess/trojan/ss share links and turns
 * them into [ProxyProfile]s; everything else is skipped (use a raw sing-box outbound for those).
 */
object ShareLinkParser {

    fun parse(uri: String): ProxyProfile? {
        val trimmed = uri.trim()
        return when {
            trimmed.startsWith("vless://", true) -> VlessUriParser.parse(trimmed)
            trimmed.startsWith("vmess://", true) -> parseVmess(trimmed)
            trimmed.startsWith("trojan://", true) -> parseTrojan(trimmed)
            trimmed.startsWith("ss://", true) -> parseShadowsocks(trimmed)
            else -> null
        }
    }

    /** Parse a subscription body across all supported protocols. */
    fun parseSubscription(body: String): List<ProxyProfile> {
        return SubscriptionDecoder.toLinks(body)
            .mapNotNull { parse(it) }
            .filter { it.isComplete() }
    }

    // --- vmess://<base64 json> (v2rayN format) ---
    private fun parseVmess(uri: String): ProxyProfile? {
        val payload = uri.substring("vmess://".length).substringBefore('#')
        val jsonText = SubscriptionDecoder.decodeBase64Chunk(payload) ?: return null
        val obj = runCatching { Json.parseToJsonElement(jsonText) as? JsonObject }.getOrNull() ?: return null

        fun str(vararg keys: String): String {
            for (k in keys) obj[k]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotBlank()) return it }
            return ""
        }

        val server = str("add")
        val port = str("port").toIntOrNull() ?: return null
        val uuid = str("id")
        if (server.isBlank() || uuid.isBlank()) return null

        val network = when (str("net").lowercase()) {
            "ws" -> ProxyProfile.NETWORK_WS
            "grpc" -> ProxyProfile.NETWORK_GRPC
            "h2", "http" -> ProxyProfile.NETWORK_HTTP
            "httpupgrade" -> ProxyProfile.NETWORK_HTTPUPGRADE
            "xhttp", "splithttp" -> ProxyProfile.NETWORK_XHTTP
            else -> ProxyProfile.NETWORK_TCP
        }
        val security = if (str("tls").equals("tls", true) || str("security").equals("tls", true)) {
            ProxyProfile.SECURITY_TLS
        } else {
            ProxyProfile.SECURITY_NONE
        }
        val pathOrService = if (network == ProxyProfile.NETWORK_GRPC) str("path", "serviceName") else str("path")

        return ProxyProfile(
            tag = str("ps").ifBlank { uri.substringAfter('#', "").let(UriCodec::percentDecode) },
            type = ProxyProfile.TYPE_VMESS,
            server = server,
            serverPort = port,
            uuid = uuid,
            alterId = str("aid").toIntOrNull() ?: 0,
            cipher = str("scy").ifBlank { "auto" },
            network = network,
            security = security,
            sni = str("sni", "peer"),
            alpn = str("alpn").split(',').map { it.trim() }.filter { it.isNotEmpty() },
            fingerprint = str("fp"),
            path = pathOrService,
            host = str("host"),
        )
    }

    // --- trojan://password@host:port?params#name ---
    private fun parseTrojan(uri: String): ProxyProfile? {
        val withoutScheme = uri.substring("trojan://".length)
        val remark = withoutScheme.substringAfter('#', "").let(UriCodec::percentDecode)
        val beforeFragment = withoutScheme.substringBefore('#')
        val query = beforeFragment.substringAfter('?', "")
        val authority = beforeFragment.substringBefore('?')

        val atIndex = authority.lastIndexOf('@')
        if (atIndex <= 0) return null
        val password = UriCodec.percentDecode(authority.substring(0, atIndex))
        val (host, port) = UriCodec.splitHostPort(authority.substring(atIndex + 1)) ?: return null
        if (password.isBlank()) return null

        val params = UriCodec.parseQuery(query)
        val network = when (params["type"]?.lowercase()) {
            "ws" -> ProxyProfile.NETWORK_WS
            "grpc" -> ProxyProfile.NETWORK_GRPC
            "http", "h2" -> ProxyProfile.NETWORK_HTTP
            "httpupgrade" -> ProxyProfile.NETWORK_HTTPUPGRADE
            "xhttp", "splithttp" -> ProxyProfile.NETWORK_XHTTP
            else -> ProxyProfile.NETWORK_TCP
        }
        // Trojan implies TLS unless explicitly "none".
        val security = if (params["security"]?.equals("none", true) == true) {
            ProxyProfile.SECURITY_NONE
        } else if (params["security"]?.equals("reality", true) == true) {
            ProxyProfile.SECURITY_REALITY
        } else {
            ProxyProfile.SECURITY_TLS
        }
        val path = if (network == ProxyProfile.NETWORK_GRPC) params["serviceName"].orEmpty() else params["path"].orEmpty()

        return ProxyProfile(
            tag = remark,
            type = ProxyProfile.TYPE_TROJAN,
            server = host,
            serverPort = port,
            password = password,
            network = network,
            security = security,
            sni = (params["sni"] ?: params["peer"]).orEmpty(),
            alpn = params["alpn"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
            fingerprint = params["fp"].orEmpty(),
            allowInsecure = params["allowInsecure"] == "1" || params["allowInsecure"] == "true",
            realityPublicKey = params["pbk"].orEmpty(),
            realityShortId = params["sid"].orEmpty(),
            path = path,
            host = params["host"].orEmpty(),
        )
    }

    // --- ss://  (SIP002: base64(method:password)@host:port ; or legacy base64(all)) ---
    private fun parseShadowsocks(uri: String): ProxyProfile? {
        val withoutScheme = uri.substring("ss://".length)
        val remark = withoutScheme.substringAfter('#', "").let(UriCodec::percentDecode)
        val body = withoutScheme.substringBefore('#').substringBefore('?')

        val atIndex = body.lastIndexOf('@')
        val method: String
        val password: String
        val host: String
        val port: Int
        if (atIndex > 0) {
            // SIP002: userinfo may be base64(method:password) or plain method:password
            val userInfoRaw = body.substring(0, atIndex)
            val userInfo = if (userInfoRaw.contains(':')) {
                userInfoRaw
            } else {
                SubscriptionDecoder.decodeBase64Chunk(userInfoRaw) ?: return null
            }
            val colon = userInfo.indexOf(':')
            if (colon <= 0) return null
            method = userInfo.substring(0, colon)
            password = userInfo.substring(colon + 1)
            val (h, p) = UriCodec.splitHostPort(body.substring(atIndex + 1)) ?: return null
            host = h; port = p
        } else {
            // Legacy: whole body is base64(method:password@host:port)
            val decoded = SubscriptionDecoder.decodeBase64Chunk(body) ?: return null
            val at = decoded.lastIndexOf('@')
            if (at <= 0) return null
            val userInfo = decoded.substring(0, at)
            val colon = userInfo.indexOf(':')
            if (colon <= 0) return null
            method = userInfo.substring(0, colon)
            password = userInfo.substring(colon + 1)
            val (h, p) = UriCodec.splitHostPort(decoded.substring(at + 1)) ?: return null
            host = h; port = p
        }
        if (method.isBlank() || password.isBlank() || host.isBlank()) return null

        return ProxyProfile(
            tag = remark,
            type = ProxyProfile.TYPE_SHADOWSOCKS,
            server = host,
            serverPort = port,
            method = method,
            password = password,
        )
    }
}
