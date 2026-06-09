package org.olcbox.app.data.share

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.olcbox.app.data.model.ProxyProfile

/**
 * Builds a shareable URI / config text from a [ProxyProfile] — the reverse of
 * [org.olcbox.app.data.importer.ShareLinkParser]. Used when sharing a Standard/Chain location so the
 * exported link actually matches the proxy inbound (not a bogus olcrtc:// link).
 */
object ShareLinkComposer {

    /** Returns a share string for [profile], or null when it cannot be represented as a link. */
    @OptIn(ExperimentalEncodingApi::class)
    fun compose(profile: ProxyProfile): String? {
        // AmneziaWG: encode the wg-quick INI as a compact amneziawg://<base64url> link (round-trips
        // via ShareLinkParser), instead of dumping the raw multi-line config text.
        if (profile.type == ProxyProfile.TYPE_AMNEZIAWG) {
            val ini = profile.awgConfig.takeIf { it.isNotBlank() } ?: return null
            return "amneziawg://" + Base64.UrlSafe.encode(ini.encodeToByteArray()).trimEnd('=')
        }
        // A full raw Xray config or a verbatim sing-box outbound: share the JSON as-is.
        profile.rawXrayConfig?.takeIf { it.isNotBlank() }?.let { return it }

        return when (profile.type) {
            ProxyProfile.TYPE_VLESS -> composeVless(profile)
            ProxyProfile.TYPE_TROJAN -> composeTrojan(profile)
            ProxyProfile.TYPE_SHADOWSOCKS -> composeShadowsocks(profile)
            ProxyProfile.TYPE_VMESS -> composeVmess(profile)
            ProxyProfile.TYPE_HYSTERIA2 -> composeHysteria2(profile)
            else -> profile.rawOutbound?.takeIf { it.isNotBlank() }
        }
    }

    private fun composeHysteria2(p: ProxyProfile): String = buildString {
        append("hysteria2://")
        if (p.password.isNotBlank()) append(percentEncode(p.password)).append('@')
        append(p.server).append(':').append(p.serverPort)
        val params = buildList {
            if (p.sni.isNotBlank()) add("sni" to p.sni)
            if (p.allowInsecure) add("insecure" to "1")
            if (p.alpn.isNotEmpty()) add("alpn" to p.alpn.joinToString(","))
            if (p.hy2Obfs.isNotBlank()) add("obfs" to p.hy2Obfs)
            if (p.hy2ObfsPassword.isNotBlank()) add("obfs-password" to p.hy2ObfsPassword)
            if (p.hy2Ports.isNotBlank()) add("mport" to p.hy2Ports)
            if (p.hy2UpMbps > 0) add("up" to p.hy2UpMbps.toString())
            if (p.hy2DownMbps > 0) add("down" to p.hy2DownMbps.toString())
        }
        if (params.isNotEmpty()) {
            append("/?").append(params.joinToString("&") { (k, v) -> "$k=${percentEncode(v)}" })
        }
        appendRemark(p.tag)
    }

    private fun composeVless(p: ProxyProfile): String = buildString {
        append("vless://")
        append(percentEncode(p.uuid))
        append('@').append(p.server).append(':').append(p.serverPort)
        append('?').append(commonStreamQuery(p, includeFlow = true))
        appendRemark(p.tag)
    }

    private fun composeTrojan(p: ProxyProfile): String = buildString {
        append("trojan://")
        append(percentEncode(p.password))
        append('@').append(p.server).append(':').append(p.serverPort)
        append('?').append(commonStreamQuery(p, includeFlow = false))
        appendRemark(p.tag)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun composeShadowsocks(p: ProxyProfile): String = buildString {
        // SIP002: ss://base64url(method:password)@host:port#remark
        val userinfo = Base64.UrlSafe.encode("${p.method}:${p.password}".encodeToByteArray()).trimEnd('=')
        append("ss://").append(userinfo)
        append('@').append(p.server).append(':').append(p.serverPort)
        appendRemark(p.tag)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun composeVmess(p: ProxyProfile): String {
        // v2rayN vmess://base64(json) layout.
        val obj = buildJsonObject {
            put("v", "2")
            put("ps", p.tag)
            put("add", p.server)
            put("port", p.serverPort.toString())
            put("id", p.uuid)
            put("aid", p.alterId.toString())
            put("scy", p.cipher)
            put("net", networkName(p.network))
            put("type", "none")
            put("host", p.host)
            put("path", p.path)
            put("tls", if (p.security == ProxyProfile.SECURITY_NONE) "" else "tls")
            put("sni", p.sni)
            put("alpn", p.alpn.joinToString(","))
        }
        val b64 = Base64.encode(obj.toString().encodeToByteArray()).trimEnd('=')
        return "vmess://$b64"
    }

    /** Builds the shared `type/security/sni/...` query used by vless & trojan links. */
    private fun commonStreamQuery(p: ProxyProfile, includeFlow: Boolean): String {
        val params = buildList {
            add("type" to networkName(p.network))
            if (p.security != ProxyProfile.SECURITY_NONE) add("security" to p.security)
            if (p.sni.isNotBlank()) add("sni" to p.sni)
            if (p.fingerprint.isNotBlank()) add("fp" to p.fingerprint)
            if (p.alpn.isNotEmpty()) add("alpn" to p.alpn.joinToString(","))
            if (includeFlow && p.flow.isNotBlank()) add("flow" to p.flow)
            if (p.realityPublicKey.isNotBlank()) add("pbk" to p.realityPublicKey)
            if (p.realityShortId.isNotBlank()) add("sid" to p.realityShortId)
            if (p.allowInsecure) add("allowInsecure" to "1")
            if (p.host.isNotBlank()) add("host" to p.host)
            if (p.path.isNotBlank()) {
                if (p.network == ProxyProfile.NETWORK_GRPC) add("serviceName" to p.path) else add("path" to p.path)
            }
        }
        return params.joinToString("&") { (k, v) -> "$k=${percentEncode(v)}" }
    }

    private fun StringBuilder.appendRemark(tag: String) {
        if (tag.isNotBlank()) append('#').append(percentEncode(tag))
    }

    private fun networkName(network: String): String = when (network) {
        ProxyProfile.NETWORK_WS -> "ws"
        ProxyProfile.NETWORK_GRPC -> "grpc"
        ProxyProfile.NETWORK_HTTP -> "http"
        ProxyProfile.NETWORK_HTTPUPGRADE -> "httpupgrade"
        ProxyProfile.NETWORK_XHTTP -> "xhttp"
        else -> "tcp"
    }

    /** Minimal RFC 3986 percent-encoding for query/fragment values. */
    private fun percentEncode(value: String): String = buildString {
        for (b in value.encodeToByteArray()) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-_.~") {
                append(ch)
            } else {
                append('%')
                append("0123456789ABCDEF"[c shr 4])
                append("0123456789ABCDEF"[c and 0x0F])
            }
        }
    }
}
