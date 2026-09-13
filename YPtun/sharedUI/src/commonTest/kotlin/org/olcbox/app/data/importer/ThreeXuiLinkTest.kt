package org.olcbox.app.data.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.data.share.ShareLinkComposer
import org.olcbox.app.vpn.xray.XrayConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 3x-ui subscription links carry Xray-only params (encryption/mode/extra/fm/pcs/vcn/pqv/spx) that
 * used to be dropped — every server of such a subscription was dead in YPtun while Happ connected.
 * Links are encoded exactly like 3x-ui's Go side (url.Values.Encode: sorted keys, QueryEscape).
 */
class ThreeXuiLinkTest {

    private fun queryEscape(s: String) = buildString {
        for (b in s.encodeToByteArray()) {
            val c = (b.toInt() and 0xFF).toChar()
            when {
                c.isLetterOrDigit() && c.code < 128 || c in "-_.~" -> append(c)
                c == ' ' -> append('+')
                else -> append('%').append("0123456789ABCDEF"[c.code shr 4]).append("0123456789ABCDEF"[c.code and 15])
            }
        }
    }

    private fun link(host: String, params: Map<String, String>) =
        "vless://11111111-2222-3333-4444-555555555555@$host:443?" +
            params.toSortedMap().entries.joinToString("&") { "${it.key}=${queryEscape(it.value)}" } + "#3x-ui%20node"

    private val extra = """{"mode":"packet-up","xPaddingBytes":"100-1000","xPaddingObfsMode":true}"""
    private val fm = """{"tcp":[{"type":"fragment","settings":{"packets":"tlshello","length":"100-200"}}]}"""
    private val pinB64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=" // bytes 0..31
    private val pinHex = (0 until 32).joinToString("") { it.toString(16).padStart(2, '0') }

    private val xhttpTls = link(
        "1.2.3.4", mapOf(
            "type" to "xhttp", "encryption" to "mlkem768x25519plus.native.0rtt.KEY+/=", "path" to "/x",
            "host" to "", "mode" to "packet-up", "extra" to extra, "fm" to fm, "security" to "tls",
            "sni" to "example.com", "fp" to "chrome", "alpn" to "h2,http/1.1", "pcs" to pinB64, "vcn" to "example.com",
            "flow" to "xtls-rprx-vision",
        )
    )

    private fun proxyOutbound(profile: ProxyProfile): JsonObject =
        Json.parseToJsonElement(XrayConfig.build(profile = profile, listenPort = 10808)).jsonObject["outbounds"]!!
            .jsonArray.map { it.jsonObject }.first { it["protocol"]?.jsonPrimitive?.content == "vless" }

    @Test
    fun parsesXrayOnlyParams() {
        val p = ShareLinkParser.parse(xhttpTls)!!
        assertEquals("mlkem768x25519plus.native.0rtt.KEY+/=", p.vlessEncryption)
        assertEquals("packet-up", p.xhttpMode)
        assertEquals(extra, p.xhttpExtra)
        assertEquals(fm, p.finalMask)
        assertEquals(pinHex, p.pinnedCertSha256) // base64 → hex, xray only takes hex
        assertEquals("example.com", p.verifyCertByName)
        assertTrue(p.requiresXray())
    }

    @Test
    fun xrayOutboundCarriesThem() {
        val out = proxyOutbound(ShareLinkParser.parse(xhttpTls)!!)
        val user = out["settings"]!!.jsonObject["vnext"]!!.jsonArray[0].jsonObject["users"]!!.jsonArray[0].jsonObject
        assertEquals("mlkem768x25519plus.native.0rtt.KEY+/=", user["encryption"]!!.jsonPrimitive.content)
        val stream = out["streamSettings"]!!.jsonObject
        assertEquals(Json.parseToJsonElement(fm), stream["finalmask"])
        val tls = stream["tlsSettings"]!!.jsonObject
        assertEquals(pinHex, tls["pinnedPeerCertSha256"]!!.jsonPrimitive.content)
        assertEquals("example.com", tls["verifyPeerCertByName"]!!.jsonPrimitive.content)
        val xhttp = stream["xhttpSettings"]!!.jsonObject
        assertEquals("packet-up", xhttp["mode"]!!.jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement(extra), xhttp["extra"])
    }

    @Test
    fun realityPqvSpx() {
        val p = ShareLinkParser.parse(
            link("5.6.7.8", mapOf(
                "type" to "tcp", "encryption" to "none", "security" to "reality", "pbk" to "PBK", "sid" to "ab",
                "sni" to "www.google.com", "fp" to "chrome", "pqv" to "PQV", "spx" to "/abc", "flow" to "xtls-rprx-vision",
            ))
        )!!
        assertEquals("", p.vlessEncryption) // "none" is the default, not a knob
        assertFalse(p.requiresXray())       // plain reality stays on the sing-box default
        val reality = proxyOutbound(p)["streamSettings"]!!.jsonObject["realitySettings"]!!.jsonObject
        assertEquals("PQV", reality["mldsa65Verify"]!!.jsonPrimitive.content)
        assertEquals("/abc", reality["spiderX"]!!.jsonPrimitive.content)
    }

    @Test
    fun plainLinksUnchanged() {
        // A Remnawave-style reality link and a grpc mode=multi link: nothing new leaks in.
        val reality = ShareLinkParser.parse(
            "vless://u@h.example:443?type=tcp&security=reality&pbk=K&sid=1&sni=a.com&fp=chrome&flow=xtls-rprx-vision#r"
        )!!
        assertEquals(ProxyProfile(
            tag = "r", server = "h.example", serverPort = 443, uuid = "u", flow = "xtls-rprx-vision",
            security = ProxyProfile.SECURITY_REALITY, sni = "a.com", fingerprint = "chrome",
            realityPublicKey = "K", realityShortId = "1",
        ), reality)
        val out = proxyOutbound(reality)
        assertEquals("none", out["settings"]!!.jsonObject["vnext"]!!.jsonArray[0].jsonObject["users"]!!
            .jsonArray[0].jsonObject["encryption"]!!.jsonPrimitive.content)
        assertNull(out["streamSettings"]!!.jsonObject["finalmask"])

        val grpc = ShareLinkParser.parse("vless://u@h:443?type=grpc&serviceName=s&mode=multi&security=tls#g")!!
        assertEquals("", grpc.xhttpMode)
        assertFalse(grpc.requiresXray())

        val xhttp = ShareLinkParser.parse("vless://u@h:443?type=xhttp&path=%2Fp&security=tls#x")!!
        assertEquals("auto", proxyOutbound(xhttp)["streamSettings"]!!.jsonObject["xhttpSettings"]!!
            .jsonObject["mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun shareRoundTrips() {
        val p = ShareLinkParser.parse(xhttpTls)!!
        assertEquals(p, ShareLinkParser.parse(ShareLinkComposer.compose(p)!!))
    }

    @Test
    fun pinFormats() {
        assertEquals(pinHex, pinHex(pinHex.uppercase()))
        assertEquals(pinHex, pinHex(pinHex.chunked(2).joinToString(":")))
        assertEquals(pinHex, pinHex(pinB64.trimEnd('=').replace('+', '-').replace('/', '_')))
        assertEquals("junk", pinHex("junk"))
    }
}
