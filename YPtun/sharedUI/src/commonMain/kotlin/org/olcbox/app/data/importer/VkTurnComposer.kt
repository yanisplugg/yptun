package org.olcbox.app.data.importer

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.data.model.VkTurnConfig

/**
 * Editable representation of a VK-TURN (freeturn) location. The connection path consumes two
 * derived artefacts — the freeturn:// [VkTurnConfig.uri] (handed verbatim to the Go freeturn
 * client) and the sing-box WireGuard outbound stored in [ProxyProfile.rawOutbound] — so this
 * draft is the single source of truth the settings UI edits, and [VkTurnComposer] keeps both
 * artefacts in sync via [VkTurnComposer.compose] / [VkTurnComposer.decompose].
 *
 * Numeric fields are kept as strings so the text fields can hold partial/blank input while editing.
 */
data class VkTurnDraft(
    // freeturn transport (the freeturn:// link)
    val provider: String = "vk",
    val transport: String = "tcp",
    val mode: String = "udp",
    val obfProfile: String = "rtpopus",
    val bond: Boolean = false,
    val peerHost: String = "",
    val peerPort: String = "",
    val obfKey: String = "",
    val comment: String = "",
    // per-client VK call link
    val vkLink: String = "",
    // WireGuard [Interface]
    val wgPrivateKey: String = "",
    val wgAddress: String = "10.7.1.2/32",
    val wgDns: String = "1.1.1.1",
    // 1200 default: through TURN+DTLS+RTP-obf the path MTU is well under 1500; 1280 can
    // black-hole large packets (speed-test/upload sites fail) while small exchanges work.
    val wgMtu: String = "1200",
    // WireGuard [Peer]
    val wgPeerPublicKey: String = "",
    val wgAllowedIps: String = "0.0.0.0/0",
    val wgKeepalive: String = "25",
    /** Local freeturn entry listener port = the WireGuard Endpoint port (127.0.0.1:listenPort). */
    val listenPort: String = LocationConfig.DEFAULT_FREETURN_PORT.toString(),
    /** Parallel TURN relay streams (freeturn -n); blank/0 keeps the default (10). More = faster. */
    val streams: String = "10",
    /** Optional proxy link (vless/vmess/trojan/ss) chained over the WG tunnel; blank = WG only. */
    val chainProxyLink: String = "",
)

/**
 * Builds and parses the two derived VK-TURN artefacts (freeturn:// URI + sing-box WireGuard
 * outbound) from a [VkTurnDraft]. The URI layout mirrors the Go panel/client codec
 * (`free-turn-proxy/internal/uri`): `freeturn://<provider>?<transport><k=v&…&wg=b64>@<peer>#<obfKey>$<comment>`.
 */
object VkTurnComposer {

    /** Builds the freeturn:// URI + WireGuard outbound for [draft]. [name] becomes the proxy tag. */
    fun compose(draft: VkTurnDraft, name: String): Pair<VkTurnConfig, ProxyProfile> {
        val listenPort = draft.listenPort.trim().toIntOrNull()
            ?.takeIf { it in 1..65535 }
            ?: LocationConfig.DEFAULT_FREETURN_PORT
        val peerPort = draft.peerPort.trim().toIntOrNull() ?: 0
        val mtu = draft.wgMtu.trim().toIntOrNull() ?: 0

        val wgConf = buildWgConf(draft, listenPort)
        val wgB64 = encodeBase64Url(wgConf)

        val uri = buildUri(draft, peerPort, wgB64)

        val rawOutbound = buildJsonObject {
            put("type", "wireguard")
            put("server", "127.0.0.1")
            put("server_port", listenPort)
            putJsonArray("local_address") {
                draft.wgAddress.trim().takeIf { it.isNotBlank() }?.let { add(it) }
            }
            put("private_key", draft.wgPrivateKey.trim())
            put("peer_public_key", draft.wgPeerPublicKey.trim())
            if (mtu > 0) put("mtu", mtu)
        }.toString()

        val vkturn = VkTurnConfig(
            uri = uri,
            vkLink = draft.vkLink.trim(),
            listenPort = listenPort,
            streams = draft.streams.trim().toIntOrNull()?.takeIf { it > 0 } ?: 0,
            chainProxyLink = draft.chainProxyLink.trim(),
        )
        val proxy = ProxyProfile(
            tag = name.ifBlank { "VK-TURN" },
            type = "wireguard",
            server = draft.peerHost.trim(),
            serverPort = peerPort,
            rawOutbound = rawOutbound,
        )
        return vkturn to proxy
    }

    /** Reconstructs an editable [VkTurnDraft] from a stored [vkturn] config + WG [proxy]. */
    fun decompose(vkturn: VkTurnConfig?, proxy: ProxyProfile?): VkTurnDraft {
        var draft = VkTurnDraft()

        // 1. freeturn:// URI → transport/obf/peer/comment + the embedded wg= INI (for DNS/keepalive).
        val uri = vkturn?.uri.orEmpty()
        if (uri.startsWith(FreeturnUriParser.SCHEME, ignoreCase = true)) {
            draft = applyUri(draft, uri)
        }
        if (vkturn != null) {
            draft = draft.copy(
                vkLink = vkturn.vkLink,
                listenPort = vkturn.listenPort.toString(),
                streams = vkturn.streams.takeIf { it > 0 }?.toString() ?: "",
                chainProxyLink = vkturn.chainProxyLink,
            )
        }

        // 2. The sing-box WG outbound is authoritative for the keys/address/mtu actually dialled.
        proxy?.rawOutbound?.let { raw ->
            runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()?.let { obj ->
                obj["private_key"]?.jsonPrimitive?.contentOrNull?.let { draft = draft.copy(wgPrivateKey = it) }
                obj["peer_public_key"]?.jsonPrimitive?.contentOrNull?.let { draft = draft.copy(wgPeerPublicKey = it) }
                obj["local_address"]?.let { addr ->
                    runCatching { addr.jsonArray.firstOrNull()?.jsonPrimitive?.contentOrNull }
                        .getOrNull()?.let { draft = draft.copy(wgAddress = it) }
                }
                obj["mtu"]?.jsonPrimitive?.intOrNull?.let { draft = draft.copy(wgMtu = it.toString()) }
            }
        }
        if (proxy != null && proxy.server.isNotBlank()) {
            draft = draft.copy(peerHost = proxy.server, peerPort = proxy.serverPort.toString())
        }
        return draft
    }

    /** Mirrors `uri.Config.String()`: assembles the canonical freeturn:// share link. */
    private fun buildUri(draft: VkTurnDraft, peerPort: Int, wgB64: String): String {
        val params = buildList {
            draft.mode.trim().takeIf { it.isNotBlank() }?.let { add("mode=$it") }
            draft.obfProfile.trim().takeIf { it.isNotBlank() }?.let { add("obf-profile=$it") }
            if (draft.bond) add("bond=1")
            if (wgB64.isNotBlank()) add("wg=$wgB64")
        }
        val transport = draft.transport.trim()
        val transportStr = if (params.isEmpty()) transport else "$transport<${params.joinToString("&")}>"

        return buildString {
            append(FreeturnUriParser.SCHEME)
            append(draft.provider.trim().ifBlank { "vk" })
            if (transportStr.isNotBlank()) {
                append('?')
                append(transportStr)
            }
            val host = draft.peerHost.trim()
            if (host.isNotBlank()) {
                append('@')
                append(host)
                if (peerPort > 0) {
                    append(':')
                    append(peerPort)
                }
            }
            draft.obfKey.trim().takeIf { it.isNotBlank() }?.let { append('#'); append(it) }
            draft.comment.trim().takeIf { it.isNotBlank() }?.let { append('$'); append(it) }
        }
    }

    /** Parses transport/obf/peer/comment + the embedded wg= INI back into [draft]. */
    private fun applyUri(draft: VkTurnDraft, uri: String): VkTurnDraft {
        var s = uri.trim().substring(FreeturnUriParser.SCHEME.length)
        var result = draft

        s.indexOf('$').takeIf { it >= 0 }?.let { idx ->
            result = result.copy(comment = s.substring(idx + 1))
            s = s.substring(0, idx)
        }
        s.indexOf('#').takeIf { it >= 0 }?.let { idx ->
            result = result.copy(obfKey = s.substring(idx + 1))
            s = s.substring(0, idx)
        }
        s.lastIndexOf('@').takeIf { it >= 0 }?.let { idx ->
            val peer = s.substring(idx + 1)
            s = s.substring(0, idx)
            UriCodec.splitHostPort(peer)?.let { (host, port) ->
                result = result.copy(peerHost = host, peerPort = port.toString())
            } ?: run { result = result.copy(peerHost = peer) }
        }

        val parts = s.split("?", limit = 2)
        result = result.copy(provider = parts[0].ifBlank { "vk" })
        if (parts.size == 2) {
            val transportPart = parts[1]
            val open = transportPart.indexOf('<')
            val close = transportPart.lastIndexOf('>')
            if (open >= 0 && close > open) {
                result = result.copy(transport = transportPart.substring(0, open))
                val params = UriCodec.parseQuery(transportPart.substring(open + 1, close))
                params["mode"]?.let { result = result.copy(mode = it) }
                params["obf-profile"]?.let { result = result.copy(obfProfile = it) }
                params["bond"]?.let { result = result.copy(bond = it == "1" || it == "true") }
                params["wg"]?.let { wg ->
                    SubscriptionDecoder.decodeBase64Chunk(wg)?.let { result = applyWgConf(result, it) }
                }
            } else {
                result = result.copy(transport = transportPart)
            }
        }
        return result
    }

    /** Pulls DNS/keepalive (and any keys not already set) out of a wg-quick INI block. */
    private fun applyWgConf(draft: VkTurnDraft, conf: String): VkTurnDraft {
        var result = draft
        for (rawLine in conf.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("[") || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim().lowercase()
            val value = line.substring(eq + 1).trim()
            when (key) {
                "dns" -> result = result.copy(wgDns = value)
                "persistentkeepalive" -> result = result.copy(wgKeepalive = value)
                "allowedips" -> result = result.copy(wgAllowedIps = value)
            }
        }
        return result
    }

    /** Builds the wg-quick INI carried (base64url) as `wg=` so the shared link re-imports cleanly. */
    private fun buildWgConf(draft: VkTurnDraft, listenPort: Int): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = ${draft.wgPrivateKey.trim()}")
        appendLine("Address = ${draft.wgAddress.trim()}")
        draft.wgDns.trim().takeIf { it.isNotBlank() }?.let { appendLine("DNS = $it") }
        draft.wgMtu.trim().takeIf { it.isNotBlank() }?.let { appendLine("MTU = $it") }
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = ${draft.wgPeerPublicKey.trim()}")
        appendLine("Endpoint = 127.0.0.1:$listenPort")
        appendLine("AllowedIPs = ${draft.wgAllowedIps.trim().ifBlank { "0.0.0.0/0" }}")
        draft.wgKeepalive.trim().takeIf { it.isNotBlank() }?.let { appendLine("PersistentKeepalive = $it") }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun encodeBase64Url(value: String): String =
        Base64.UrlSafe.encode(value.encodeToByteArray()).trimEnd('=')
}
