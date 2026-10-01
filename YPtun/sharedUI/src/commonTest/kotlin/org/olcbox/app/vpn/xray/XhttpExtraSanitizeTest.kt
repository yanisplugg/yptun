package org.olcbox.app.vpn.xray

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.ProxyProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A CDN xhttp config whose `extra` carries a value xray-core can't parse into an `Int32Range` (a float,
 * a stray string, an object) must NOT abort the whole outbound build with "Invalid integer range" —
 * Happ tolerates such configs. We coerce/drop only the offending value and keep valid ones verbatim.
 */
class XhttpExtraSanitizeTest {

    private fun xhttp(extra: String) = ProxyProfile(
        tag = "n", type = ProxyProfile.TYPE_VLESS, server = "1.2.3.4", serverPort = 443,
        uuid = "11111111-2222-3333-4444-555555555555",
        network = ProxyProfile.NETWORK_XHTTP, security = "tls", sni = "example.com",
        xhttpMode = "auto", xhttpExtra = extra,
    )

    private fun builtExtra(profile: ProxyProfile): JsonObject {
        val out = Json.parseToJsonElement(XrayConfig.build(profile = profile, listenPort = 10808))
            .jsonObject["outbounds"]!!.jsonArray.map { it.jsonObject }
            .first { it["protocol"]?.jsonPrimitive?.content == "vless" }
        return out["streamSettings"]!!.jsonObject["xhttpSettings"]!!.jsonObject["extra"]!!.jsonObject
    }

    @Test
    fun coercesFloatsDropsGarbage() {
        val bad = """{"scMaxEachPostBytes":1000000.0,"xPaddingObfsMode":true,""" +
            """"xPaddingBytes":"oops","noGRPCHeader":false,""" +
            """"xmux":{"maxConcurrency":16.0,"maxConnections":0,"cMaxReuseTimes":{},"hMaxRequestTimes":"600-900"}}"""
        val e = builtExtra(xhttp(bad))
        assertEquals(1000000L, e["scMaxEachPostBytes"]!!.jsonPrimitive.content.toLong()) // float → int
        assertNull(e["xPaddingBytes"])                                                    // "oops" dropped
        assertEquals("true", e["xPaddingObfsMode"]!!.jsonPrimitive.content)               // non-range kept
        val xmux = e["xmux"]!!.jsonObject
        assertEquals(16L, xmux["maxConcurrency"]!!.jsonPrimitive.content.toLong())         // 16.0 → 16
        assertEquals("0", xmux["maxConnections"]!!.jsonPrimitive.content)                  // integer kept
        assertNull(xmux["cMaxReuseTimes"])                                                 // object dropped
        assertEquals("600-900", xmux["hMaxRequestTimes"]!!.jsonPrimitive.content)          // range kept
    }

    @Test
    fun validExtraUntouched() {
        val good = """{"xPaddingBytes":"100-1000","scMaxEachPostBytes":1000000,""" +
            """"xmux":{"maxConcurrency":"16-32","maxConnections":0}}"""
        assertEquals(Json.parseToJsonElement(good), builtExtra(xhttp(good)))
    }

    /**
     * The reported CDN config (3x-ui xhttp/packet-up): its panel emitted `xmux.hKeepAlivePeriod` — a
     * PLAIN `int64` in xray, not an Int32Range — as the float `20000.0`, and xray aborted the ENTIRE
     * config with "Failed to unmarshal extra > json: cannot unmarshal number 20000.0 into Go struct
     * field SplitHTTPConfig.xmux.hKeepAlivePeriod of type int64" — the whole profile wouldn't connect.
     */
    @Test
    fun cdnConfigWithFloatPlainIntsStillBuilds() {
        val bad = """{"path":"/static/main/video/segment.ts/m37f08c983037","host":"","mode":"packet-up",""" +
            """"xPaddingBytes":"100-1000","xPaddingObfsMode":true,"xPaddingKey":"hash",""" +
            """"xPaddingHeader":"X-Client-Version","xPaddingPlacement":"queryInHeader",""" +
            """"xPaddingMethod":"tokenish","sessionIDPlacement":"header","sessionIDKey":"X-Upload-Token",""" +
            """"sessionIDTable":"","sessionIDLength":"","seqPlacement":"query","seqKey":"chunk_id",""" +
            """"noSSEHeader":false,"scMaxBufferedPosts":30.0,"scStreamUpServerSecs":"20-80",""" +
            """"serverMaxHeaderBytes":0.0,"uplinkHTTPMethod":"GET","headers":{},"uplinkChunkSize":0,""" +
            """"noGRPCHeader":false,"xmux":{"maxConcurrency":"16-32","maxConnections":0,""" +
            """"cMaxReuseTimes":1000,"hMaxRequestTimes":"600-900","hMaxReusableSecs":"100",""" +
            """"hKeepAlivePeriod":20000.0}}"""
        val e = builtExtra(xhttp(bad))
        val xmux = e["xmux"]!!.jsonObject
        assertEquals("20000", xmux["hKeepAlivePeriod"]!!.jsonPrimitive.content)  // 20000.0 → 20000
        assertEquals("30", e["scMaxBufferedPosts"]!!.jsonPrimitive.content)      // 30.0 → 30
        assertEquals("0", e["serverMaxHeaderBytes"]!!.jsonPrimitive.content)     // 0.0 → 0
        // Everything the server must agree on survives byte-for-byte.
        assertEquals("packet-up", e["mode"]!!.jsonPrimitive.content)
        assertEquals("100-1000", e["xPaddingBytes"]!!.jsonPrimitive.content)
        assertEquals("X-Upload-Token", e["sessionIDKey"]!!.jsonPrimitive.content)
        assertEquals("", e["sessionIDLength"]!!.jsonPrimitive.content)
        assertEquals("16-32", xmux["maxConcurrency"]!!.jsonPrimitive.content)
        assertEquals("1000", xmux["cMaxReuseTimes"]!!.jsonPrimitive.content)
        // No float literal is left anywhere for xray to choke on.
        assertFalse(e.toString().contains(".0"))
    }

    /** A plain int that is garbage (a bool, an object, a non-numeric string) is dropped, not passed on. */
    @Test
    fun dropsUnsalvageablePlainInts() {
        val bad = """{"scMaxBufferedPosts":true,"serverMaxHeaderBytes":"none",""" +
            """"xmux":{"hKeepAlivePeriod":{}}}"""
        val e = builtExtra(xhttp(bad))
        assertNull(e["scMaxBufferedPosts"])
        assertNull(e["serverMaxHeaderBytes"])
        assertNull(e["xmux"]!!.jsonObject["hKeepAlivePeriod"])
        // A quoted integer IS salvageable.
        val ok = builtExtra(xhttp("""{"scMaxBufferedPosts":"30"}"""))
        assertEquals("30", ok["scMaxBufferedPosts"]!!.jsonPrimitive.content)
    }

    /** Wrong-typed bools/strings abort the same unmarshal — coerce what we can, drop the rest. */
    @Test
    fun coercesBoolsAndStrings() {
        val bad = """{"xPaddingObfsMode":"true","noSSEHeader":0,"noGRPCHeader":"nope",""" +
            """"seqKey":1,"xPaddingMethod":["a"],"headers":{"X-N":5,"X-Ok":"v","X-Bad":{}}}"""
        val e = builtExtra(xhttp(bad))
        assertEquals("true", e["xPaddingObfsMode"]!!.jsonPrimitive.content)   // "true" → true
        assertEquals("false", e["noSSEHeader"]!!.jsonPrimitive.content)       // 0 → false
        assertNull(e["noGRPCHeader"])                                         // garbage dropped
        assertEquals("1", e["seqKey"]!!.jsonPrimitive.content)                // 1 → "1"
        assertNull(e["xPaddingMethod"])                                       // array dropped
        val headers = e["headers"]!!.jsonObject
        assertEquals("5", headers["X-N"]!!.jsonPrimitive.content)             // map[string]string
        assertEquals("v", headers["X-Ok"]!!.jsonPrimitive.content)
        assertNull(headers["X-Bad"])
        // The coerced values carry the JSON TYPE xray wants: a bare bool, a quoted string.
        assertFalse((e["xPaddingObfsMode"] as JsonPrimitive).isString)
        assertTrue((e["seqKey"] as JsonPrimitive).isString)
    }

    /**
     * A RAW JSON config states the same fields at the TOP LEVEL of `xhttpSettings` (no `extra`), where
     * a float kills the build just as dead — prepareRaw must sanitize there too, and leave the rest of
     * the outbound (reality/vless/transport) verbatim.
     */
    @Test
    fun rawConfigTopLevelXhttpSettingsSanitized() {
        val raw = """
        {
          "outbounds": [
            { "protocol": "vless", "tag": "proxy",
              "settings": { "vnext": [ { "address": "1.2.3.4", "port": 443,
                "users": [ { "encryption": "none", "id": "11111111-2222-3333-4444-555555555555" } ] } ] },
              "streamSettings": { "network": "xhttp", "security": "tls",
                "xhttpSettings": { "mode": "packet-up", "path": "/seg.ts", "host": "cdn.example.com",
                  "scMaxBufferedPosts": 30.0,
                  "xmux": { "maxConcurrency": "16-32", "hKeepAlivePeriod": 20000.0 } } } },
            { "protocol": "freedom", "tag": "direct" }
          ]
        }
        """.trimIndent()
        val out = XrayConfig.prepareRaw(rawConfigJson = raw, listenPort = 10808)
        val proxy = Json.parseToJsonElement(out).jsonObject["outbounds"]!!.jsonArray
            .map { it.jsonObject }.first { it["tag"]?.jsonPrimitive?.content == "proxy" }
        val stream = proxy["streamSettings"]!!.jsonObject
        val x = stream["xhttpSettings"]!!.jsonObject
        assertEquals("20000", x["xmux"]!!.jsonObject["hKeepAlivePeriod"]!!.jsonPrimitive.content)
        assertEquals("30", x["scMaxBufferedPosts"]!!.jsonPrimitive.content)
        // Untouched: mode/path/host and the surrounding transport.
        assertEquals("packet-up", x["mode"]!!.jsonPrimitive.content)
        assertEquals("/seg.ts", x["path"]!!.jsonPrimitive.content)
        assertEquals("cdn.example.com", x["host"]!!.jsonPrimitive.content)
        assertEquals("16-32", x["xmux"]!!.jsonObject["maxConcurrency"]!!.jsonPrimitive.content)
        assertEquals("tls", stream["security"]!!.jsonPrimitive.content)
        assertEquals("xhttp", stream["network"]!!.jsonPrimitive.content)
    }
}
