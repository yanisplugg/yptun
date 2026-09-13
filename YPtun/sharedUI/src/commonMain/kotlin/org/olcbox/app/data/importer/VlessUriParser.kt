package org.olcbox.app.data.importer

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.olcbox.app.data.model.ProxyProfile

/**
 * Parses `vless://` share links and subscription bodies into [ProxyProfile]s.
 * For multi-protocol parsing use [ShareLinkParser]; this object stays VLESS-specific.
 */
object VlessUriParser {

    /** Parse a single `vless://` URI. Returns null if it is not a valid VLESS link. */
    fun parse(uri: String): ProxyProfile? {
        val trimmed = uri.trim()
        if (!trimmed.startsWith("vless://", ignoreCase = true)) return null

        // vless://<uuid>@<host>:<port>?<query>#<remark>
        val withoutScheme = trimmed.substring("vless://".length)

        val fragmentIndex = withoutScheme.indexOf('#')
        val remark = if (fragmentIndex >= 0) {
            UriCodec.percentDecode(withoutScheme.substring(fragmentIndex + 1))
        } else {
            ""
        }
        val beforeFragment = if (fragmentIndex >= 0) {
            withoutScheme.substring(0, fragmentIndex)
        } else {
            withoutScheme
        }

        val queryIndex = beforeFragment.indexOf('?')
        val query = if (queryIndex >= 0) beforeFragment.substring(queryIndex + 1) else ""
        val authority = if (queryIndex >= 0) beforeFragment.substring(0, queryIndex) else beforeFragment

        val atIndex = authority.lastIndexOf('@')
        if (atIndex <= 0) return null
        val uuid = UriCodec.percentDecode(authority.substring(0, atIndex)).trim()
        val hostPort = authority.substring(atIndex + 1)

        val (host, port) = UriCodec.splitHostPort(hostPort) ?: return null
        if (uuid.isBlank() || host.isBlank() || port !in 1..65535) return null

        val params = UriCodec.parseQuery(query)

        val network = when (params["type"]?.lowercase()) {
            "ws" -> ProxyProfile.NETWORK_WS
            "grpc" -> ProxyProfile.NETWORK_GRPC
            "http", "h2" -> ProxyProfile.NETWORK_HTTP
            "httpupgrade" -> ProxyProfile.NETWORK_HTTPUPGRADE
            "xhttp", "splithttp" -> ProxyProfile.NETWORK_XHTTP
            else -> ProxyProfile.NETWORK_TCP
        }

        val security = when (params["security"]?.lowercase()) {
            "tls" -> ProxyProfile.SECURITY_TLS
            "reality" -> ProxyProfile.SECURITY_REALITY
            else -> ProxyProfile.SECURITY_NONE
        }

        // grpc carries its path as serviceName; ws/httpupgrade use path
        val path = when (network) {
            ProxyProfile.NETWORK_GRPC -> params["serviceName"].orEmpty()
            else -> params["path"].orEmpty()
        }

        return ProxyProfile(
            tag = remark,
            type = ProxyProfile.TYPE_VLESS,
            server = host,
            serverPort = port,
            uuid = uuid,
            flow = params["flow"].orEmpty(),
            network = network,
            security = security,
            sni = (params["sni"] ?: params["peer"]).orEmpty(),
            alpn = params["alpn"].orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() },
            fingerprint = params["fp"].orEmpty(),
            allowInsecure = params["allowInsecure"] == "1" || params["allowInsecure"] == "true",
            realityPublicKey = params["pbk"].orEmpty(),
            realityShortId = params["sid"].orEmpty(),
            path = path,
            host = (params["host"]).orEmpty(),
        ).withXrayParams(params)
    }

    /** Parse a subscription body, keeping only VLESS entries. */
    fun parseSubscription(body: String): List<ProxyProfile> {
        return SubscriptionDecoder.toLinks(body)
            .mapNotNull { parse(it) }
            .filter { it.isComplete() }
    }
}

/**
 * Copies the Xray-only link params (3x-ui & co, shared by vless/trojan/vmess) onto this profile.
 * `mode` is only xhttp's — grpc reuses the name for `mode=multi`.
 */
internal fun ProxyProfile.withXrayParams(params: Map<String, String>): ProxyProfile = copy(
    vlessEncryption = params["encryption"].orEmpty().takeUnless { it.equals("none", ignoreCase = true) }.orEmpty(),
    xhttpMode = if (network == ProxyProfile.NETWORK_XHTTP) params["mode"].orEmpty() else "",
    xhttpExtra = if (network == ProxyProfile.NETWORK_XHTTP) params["extra"].orEmpty() else "",
    finalMask = params["fm"].orEmpty(),
    pinnedCertSha256 = params["pcs"].orEmpty().split(',').map(::pinHex).filter { it.isNotEmpty() }.joinToString(","),
    verifyCertByName = params["vcn"].orEmpty(),
    echConfigList = params["ech"].orEmpty(),
    realityMldsa65Verify = params["pqv"].orEmpty(),
    realitySpiderX = params["spx"].orEmpty(),
)

/**
 * xray-core only takes hex pins, but 3x-ui stores them as base64 too (its own generate button).
 * Hex (bare or colon-separated) → lowercase hex; a 32-byte base64 → hex; anything else unchanged.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun pinHex(pin: String): String {
    val p = pin.trim().replace(' ', '+') // a raw '+' in a query is decoded as a space
    val hex = p.replace(":", "")
    if (hex.length == 64 && hex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return hex.lowercase()
    val std = p.replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) }
    val bytes = runCatching { Base64.decode(std) }.getOrNull()
    if (bytes?.size == 32) return bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    return p
}
