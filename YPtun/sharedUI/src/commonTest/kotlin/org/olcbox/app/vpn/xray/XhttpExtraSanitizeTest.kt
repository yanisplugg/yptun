package org.olcbox.app.vpn.xray

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.ProxyProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
