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
import org.olcbox.app.data.model.ProxyCore
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
    /** Tunnel exit: "wireguard" | "amneziawg" | "proxy" (see [VkTurnConfig.outbound]). */
    val outbound: String = VkTurnConfig.OUTBOUND_WIREGUARD,
    // AmneziaWG obfuscation knobs (used only when outbound == amneziawg); shipped in the [Interface].
    val awgJc: String = "4",
    val awgJmin: String = "40",
    val awgJmax: String = "70",
    val awgS1: String = "0",
    val awgS2: String = "0",
    val awgH1: String = "1",
    val awgH2: String = "2",
    val awgH3: String = "3",
    val awgH4: String = "4",
    /** Proxy share link (vless/vmess/trojan/ss) used as the exit when outbound == proxy. */
    val outboundProxyLink: String = "",
    val proxyCore: ProxyCore = ProxyCore.Auto,
    // VK-TURN transport core: "freeturn" (default) | "wdtt". WDTT fields are used only when core==wdtt.
    val core: String = VkTurnConfig.CORE_FREETURN,
    /** WDTT server IP/host dialled over VK TURN (the wdtt-server / Peer). Port = [wdttPort]. */
    val wdttPeer: String = "",
    /** WDTT server port; blank/0 → 56000. */
    val wdttPort: String = "",
    /** WDTT connection password (the WRAP key is HKDF-derived from it). */
    val wdttPassword: String = "",
    /** WDTT TLS fingerprint for the VK auth flow (chrome/safari/ios/android/firefox). */
    val wdttFingerprint: String = "chrome",
    /** WDTT worker count; blank/0 → core default. */
    val wdttWorkers: String = "",
)

/**
 * Builds and parses the two derived VK-TURN artefacts (freeturn:// URI + sing-box WireGuard
 * outbound) from a [VkTurnDraft]. The URI layout mirrors the Go panel/client codec
 * (`free-turn-proxy/internal/uri`): `freeturn://<provider>?<transport><k=v&…&wg=b64>@<peer>#<obfKey>$<comment>`.
 */
object VkTurnComposer {

    /** Builds the freeturn:// URI + the chosen exit outbound for [draft]. [name] becomes the tag. */
    fun compose(draft: VkTurnDraft, name: String): Pair<VkTurnConfig, ProxyProfile> {
        val listenPort = draft.listenPort.trim().toIntOrNull()
            ?.takeIf { it in 1..65535 }
            ?: LocationConfig.DEFAULT_FREETURN_PORT
        val peerPort = draft.peerPort.trim().toIntOrNull() ?: 0
        val mtu = draft.wgMtu.trim().toIntOrNull() ?: 0
        val outbound = draft.outbound.ifBlank { VkTurnConfig.OUTBOUND_WIREGUARD }

        // The freeturn payload mode is dictated by the outbound transport: WireGuard/AmneziaWG are
        // UDP (udprelay → udp local listener), a proxy exit is TCP (tcpfwd → tcp local listener).
        val mode = if (outbound == VkTurnConfig.OUTBOUND_PROXY) "tcp" else "udp"

        // The wg= INI is only meaningful for the WG/AWG (UDP) paths; for a proxy exit it is omitted.
        val wgB64 = when (outbound) {
            VkTurnConfig.OUTBOUND_WIREGUARD -> encodeBase64Url(buildWgConf(draft, listenPort, awg = false))
            VkTurnConfig.OUTBOUND_AMNEZIAWG -> encodeBase64Url(buildWgConf(draft, listenPort, awg = true))
            else -> ""
        }

        val uri = buildUri(draft, peerPort, wgB64, mode)

        val proxy = when (outbound) {
            VkTurnConfig.OUTBOUND_AMNEZIAWG -> ProxyProfile(
                tag = name.ifBlank { "VK-TURN" },
                type = ProxyProfile.TYPE_AMNEZIAWG,
                server = draft.peerHost.trim(),
                serverPort = peerPort,
                // awgproxy re-parses this INI; its Endpoint is the local freeturn UDP listener.
                awgConfig = buildWgConf(draft, listenPort, awg = true),
            )

            VkTurnConfig.OUTBOUND_PROXY -> {
                // Parse the exit proxy and rewrite its dial target to the local freeturn TCP
                // listener; TLS SNI / params stay pointed at the real server.
                val parsed = ShareLinkParser.parse(draft.outboundProxyLink.trim())
                val base = parsed ?: ProxyProfile()
                // BUGFIX: SingBoxConfig sets `server_name = sni.ifBlank { server }`. Since we are
                // about to overwrite `server` with 127.0.0.1 (the local freeturn listener), a link
                // WITHOUT an explicit sni= would end up presenting "127.0.0.1" as the TLS SNI and the
                // handshake to the real relayed server is reset → the proxy "never connects". Pin the
                // SNI to the original host first so TLS still validates against the real server.
                base.copy(
                    tag = name.ifBlank { base.tag.ifBlank { "VK-TURN" } },
                    sni = base.sni.ifBlank { base.server },
                    server = "127.0.0.1",
                    serverPort = listenPort,
                )
            }

            else -> { // WireGuard
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
                ProxyProfile(
                    tag = name.ifBlank { "VK-TURN" },
                    type = "wireguard",
                    server = draft.peerHost.trim(),
                    serverPort = peerPort,
                    rawOutbound = rawOutbound,
                )
            }
        }

        val vkturn = VkTurnConfig(
            uri = uri,
            vkLink = draft.vkLink.trim(),
            listenPort = listenPort,
            streams = draft.streams.trim().toIntOrNull()?.takeIf { it > 0 } ?: 0,
            chainProxyLink = draft.chainProxyLink.trim(),
            outbound = outbound,
            outboundProxyLink = if (outbound == VkTurnConfig.OUTBOUND_PROXY) draft.outboundProxyLink.trim() else "",
            proxyCore = draft.proxyCore,
            core = draft.core.ifBlank { VkTurnConfig.CORE_FREETURN },
            wdttPeer = draft.wdttPeer.trim(),
            wdttPort = draft.wdttPort.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 0,
            wdttPassword = draft.wdttPassword.trim(),
            wdttFingerprint = draft.wdttFingerprint.trim(),
            wdttWorkers = draft.wdttWorkers.trim().toIntOrNull()?.takeIf { it > 0 } ?: 0,
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
                outbound = vkturn.outbound.ifBlank { VkTurnConfig.OUTBOUND_WIREGUARD },
                outboundProxyLink = vkturn.outboundProxyLink,
                proxyCore = vkturn.proxyCore,
                core = vkturn.core.ifBlank { VkTurnConfig.CORE_FREETURN },
                wdttPeer = vkturn.wdttPeer,
                wdttPort = vkturn.wdttPort.takeIf { it > 0 }?.toString() ?: "",
                wdttPassword = vkturn.wdttPassword,
                wdttFingerprint = vkturn.wdttFingerprint.ifBlank { "chrome" },
                wdttWorkers = vkturn.wdttWorkers.takeIf { it > 0 }?.toString() ?: "",
            )
        }

        // 2. The stored outbound is authoritative for the keys/address/mtu actually dialled.
        when (vkturn?.outbound) {
            VkTurnConfig.OUTBOUND_AMNEZIAWG -> proxy?.awgConfig?.let { ini ->
                draft = applyWgConf(draft, ini)
                draft = applyAwgKnobs(draft, ini)
            }
            VkTurnConfig.OUTBOUND_PROXY -> { /* peerHost/port come from the URI; link kept above. */ }
            else -> proxy?.rawOutbound?.let { raw ->
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
        }
        // peerHost/port: for WG/AWG the proxy carries the VPS peer; for a proxy exit the proxy.server
        // was rewritten to 127.0.0.1, so keep the peer parsed from the URI instead.
        if (proxy != null && proxy.server.isNotBlank() && vkturn?.outbound != VkTurnConfig.OUTBOUND_PROXY) {
            draft = draft.copy(peerHost = proxy.server, peerPort = proxy.serverPort.toString())
        }
        return draft
    }

    /** Mirrors `uri.Config.String()`: assembles the canonical freeturn:// share link. */
    private fun buildUri(draft: VkTurnDraft, peerPort: Int, wgB64: String, mode: String): String {
        val params = buildList {
            mode.trim().takeIf { it.isNotBlank() }?.let { add("mode=$it") }
            draft.obfProfile.trim().takeIf { it.isNotBlank() }?.let { add("obf-profile=$it") }
            // freeturn rejects `-bond` unless mode==tcp ("-bond requires -mode tcp"); emitting it in
            // udp mode (WireGuard/AmneziaWG) makes the client fail to start → the whole tunnel dies.
            // UDP aggregation is instead achieved via multiple streams (-n) and multiple VK links.
            if (draft.bond && mode == "tcp") add("bond=1")
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

    /** Pulls WG keys/address/mtu + AmneziaWG obfuscation knobs out of an AmneziaWG INI. */
    private fun applyAwgKnobs(draft: VkTurnDraft, conf: String): VkTurnDraft {
        var result = draft
        for (rawLine in conf.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("[") || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim().lowercase()
            val value = line.substring(eq + 1).trim()
            when (key) {
                "privatekey" -> result = result.copy(wgPrivateKey = value)
                "publickey" -> result = result.copy(wgPeerPublicKey = value)
                "address" -> result = result.copy(wgAddress = value.substringBefore(',').trim())
                "mtu" -> result = result.copy(wgMtu = value)
                "jc" -> result = result.copy(awgJc = value)
                "jmin" -> result = result.copy(awgJmin = value)
                "jmax" -> result = result.copy(awgJmax = value)
                "s1" -> result = result.copy(awgS1 = value)
                "s2" -> result = result.copy(awgS2 = value)
                "h1" -> result = result.copy(awgH1 = value)
                "h2" -> result = result.copy(awgH2 = value)
                "h3" -> result = result.copy(awgH3 = value)
                "h4" -> result = result.copy(awgH4 = value)
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

    /**
     * Builds the wg-quick INI carried (base64url) as `wg=` so the shared link re-imports cleanly.
     * When [awg] is true the AmneziaWG obfuscation knobs (Jc/Jmin/Jmax/S1/S2/H1..H4) are emitted in
     * the [Interface] so the awgproxy module recognises it as AmneziaWG.
     */
    private fun buildWgConf(draft: VkTurnDraft, listenPort: Int, awg: Boolean): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = ${draft.wgPrivateKey.trim()}")
        appendLine("Address = ${draft.wgAddress.trim()}")
        draft.wgDns.trim().takeIf { it.isNotBlank() }?.let { appendLine("DNS = $it") }
        draft.wgMtu.trim().takeIf { it.isNotBlank() }?.let { appendLine("MTU = $it") }
        if (awg) {
            appendLine("Jc = ${draft.awgJc.trim().ifBlank { "4" }}")
            appendLine("Jmin = ${draft.awgJmin.trim().ifBlank { "40" }}")
            appendLine("Jmax = ${draft.awgJmax.trim().ifBlank { "70" }}")
            appendLine("S1 = ${draft.awgS1.trim().ifBlank { "0" }}")
            appendLine("S2 = ${draft.awgS2.trim().ifBlank { "0" }}")
            appendLine("H1 = ${draft.awgH1.trim().ifBlank { "1" }}")
            appendLine("H2 = ${draft.awgH2.trim().ifBlank { "2" }}")
            appendLine("H3 = ${draft.awgH3.trim().ifBlank { "3" }}")
            appendLine("H4 = ${draft.awgH4.trim().ifBlank { "4" }}")
        }
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
