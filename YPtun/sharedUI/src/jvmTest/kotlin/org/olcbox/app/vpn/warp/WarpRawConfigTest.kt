package org.olcbox.app.vpn.warp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.vpn.xray.XrayConfig

/**
 * WARP over a JSON subscription (verbatim Xray config). When WARP_DUMP_DIR is set the configs are also written
 * there so a real `xray` can run them (that is how the chain was verified against a local VLESS server).
 */
class WarpRawConfigTest {
    private fun raw(server: String, port: Int, flow: String) = """
        {
          "inbounds": [{ "tag": "socks", "port": 10808, "protocol": "socks", "settings": { "udp": true } }],
          "outbounds": [
            { "tag": "proxy", "protocol": "vless",
              "settings": { "vnext": [{ "address": "$server", "port": $port,
                "users": [{ "id": "11111111-2222-3333-4444-555555555555", "encryption": "none" $flow }] }] },
              "streamSettings": { "network": "tcp", "security": "none", "sockopt": { "mark": 255 } } },
            { "tag": "direct", "protocol": "freedom" },
            { "tag": "block", "protocol": "blackhole" }
          ],
          "routing": { "domainStrategy": "AsIs", "rules": [
            { "type": "field", "domain": ["domain:ru"], "outboundTag": "direct" },
            { "type": "field", "network": "tcp,udp", "outboundTag": "proxy" } ] }
        }
    """.trimIndent()

    private val dir: File? = System.getenv("WARP_DUMP_DIR")?.let { File(it).apply { mkdirs() } }

    private fun JsonObject.outbound(tag: String) =
        this["outbounds"]!!.jsonArray.map { it.jsonObject }.first { it["tag"]?.jsonPrimitive?.content == tag }

    @Test
    fun clientSideEveryProxyOutboundDialsThroughTheWarpSocks() {
        val json = XrayConfig.prepareRaw(
            rawConfigJson = raw("127.0.0.1", 18991, ""","flow":"xtls-rprx-vision""""),
            listenPort = 18081, warpChainPort = 18900,
        )
        dir?.resolve("raw-client.json")?.writeText(json)
        val o = Json.parseToJsonElement(json).jsonObject
        val proxy = o.outbound("proxy")
        val stream = proxy["streamSettings"]!!.jsonObject
        assertEquals("warp-out", stream["sockopt"]!!.jsonObject["dialerProxy"]!!.jsonPrimitive.content)
        assertEquals("255", stream["sockopt"]!!.jsonObject["mark"]!!.jsonPrimitive.content, "the config's own sockopt is kept")
        assertTrue(proxy.toString().contains("xtls-rprx-vision"), "the Vision flow survives")
        assertEquals("socks", o.outbound("warp-out")["protocol"]!!.jsonPrimitive.content)
        assertTrue("dialerProxy" !in (o.outbound("direct")["streamSettings"]?.toString() ?: ""), "direct is not chained")
    }

    @Test
    fun serverSideAwgproxyIsTheExitAndItsUdpRidesTheMainProxy() {
        val json = XrayConfig.prepareRaw(
            rawConfigJson = raw("127.0.0.1", 18991, ""),
            listenPort = 18081, warpExitPort = 18900, warpRelay = WarpRelay(18993, "162.159.192.1", 2408),
        )
        dir?.resolve("raw-server.json")?.writeText(json)
        val o = Json.parseToJsonElement(json).jsonObject
        assertEquals("cascade-exit", o["outbounds"]!!.jsonArray.first().jsonObject["tag"]!!.jsonPrimitive.content)
        val relay = o["inbounds"]!!.jsonArray.map { it.jsonObject }.first { it["tag"]?.jsonPrimitive?.content == "warp-relay-in" }
        assertEquals("dokodemo-door", relay["protocol"]!!.jsonPrimitive.content)
        val rules = o["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        assertEquals("proxy", rules.first()["outboundTag"]!!.jsonPrimitive.content, "relay rule goes to the real main proxy")
        assertTrue(rules.first()["inboundTag"]!!.jsonArray.single().jsonPrimitive.content == "warp-relay-in")
        val catchAll = rules.last()
        assertEquals("cascade-exit", catchAll["outboundTag"]!!.jsonPrimitive.content, "everything else leaves through WARP")
        assertEquals("direct", rules[1]["outboundTag"]!!.jsonPrimitive.content, "the config's direct rule stays")
    }
}
