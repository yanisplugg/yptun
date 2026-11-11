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

    /** Remnawave/Happ-style full config: several proxy outbounds behind one routing balancer. */
    private fun balancedRaw() = """
        {
          "inbounds": [{ "tag": "socks", "port": 10808, "protocol": "socks", "settings": { "udp": true } }],
          "outbounds": [
            { "tag": "proxy-a", "protocol": "vless",
              "settings": { "vnext": [{ "address": "a.example", "port": 443,
                "users": [{ "id": "11111111-2222-3333-4444-555555555555", "encryption": "none" }] }] },
              "streamSettings": { "network": "tcp", "security": "tls" } },
            { "tag": "proxy-b", "protocol": "trojan",
              "settings": { "servers": [{ "address": "b.example", "port": 443, "password": "secret" }] },
              "streamSettings": { "network": "tcp", "security": "tls" } },
            { "tag": "direct", "protocol": "freedom" },
            { "tag": "block", "protocol": "blackhole" }
          ],
          "routing": {
            "domainStrategy": "AsIs",
            "balancers": [{
              "tag": "proxy-balance", "selector": ["proxy-"], "fallbackTag": "proxy-a",
              "strategy": { "type": "roundRobin" }
            }],
            "rules": [
              { "type": "field", "domain": ["domain:ru"], "outboundTag": "direct" },
              { "type": "field", "network": "tcp,udp", "balancerTag": "proxy-balance" }
            ]
          }
        }
    """.trimIndent()

    /** A panel subscription whose balancer selects chain helpers, not typed proxy outbounds directly. */
    private fun chainedBalancedRaw() = """
        {
          "inbounds": [{ "tag": "socks", "port": 10808, "protocol": "socks", "settings": { "udp": true } }],
          "outbounds": [
            { "tag": "node-a", "protocol": "vless",
              "settings": { "vnext": [{ "address": "a.example", "port": 443,
                "users": [{ "id": "11111111-2222-3333-4444-555555555555", "encryption": "none" }] }] },
              "streamSettings": { "network": "tcp", "security": "tls" } },
            { "tag": "node-b", "protocol": "trojan",
              "settings": { "servers": [{ "address": "b.example", "port": 443, "password": "secret" }] },
              "streamSettings": { "network": "tcp", "security": "tls" } },
            { "tag": "chain-a", "protocol": "freedom", "proxySettings": { "tag": "node-a" } },
            { "tag": "chain-b", "protocol": "freedom", "proxySettings": { "tag": "node-b" } },
            { "tag": "direct", "protocol": "freedom" },
            { "tag": "block", "protocol": "blackhole" }
          ],
          "routing": {
            "domainStrategy": "AsIs",
            "balancers": [
              { "tag": "node-balance", "selector": ["node-"],
                "strategy": { "type": "roundRobin" } },
              { "tag": "subscription-balance", "selector": ["chain-"],
                "strategy": { "type": "roundRobin" } }
            ],
            "rules": [
              { "type": "field", "domain": ["domain:example.com"], "balancerTag": "node-balance" },
              { "type": "field", "domain": ["domain:ru"], "outboundTag": "direct" },
              { "type": "field", "network": "tcp,udp", "balancerTag": "subscription-balance" }
            ]
          }
        }
    """.trimIndent()

    /** Real Quofortpost-style shape: leastLoad waits for burstObservatory and fails closed to block. */
    private fun leastLoadBalancedRaw() = """
        {
          "inbounds": [{ "tag": "socks", "port": 10808, "protocol": "socks", "settings": { "udp": true } }],
          "outbounds": [
            { "tag": "es-ru", "protocol": "vless",
              "settings": { "vnext": [{ "address": "edge-a.example", "port": 8446,
                "users": [{ "id": "11111111-2222-3333-4444-555555555555", "encryption": "none" }] }] },
              "streamSettings": { "network": "tcp", "security": "tls" } },
            { "tag": "es-ru-2", "protocol": "vless",
              "settings": { "vnext": [{ "address": "edge-b.example", "port": 8446,
                "users": [{ "id": "11111111-2222-3333-4444-555555555555", "encryption": "none" }] }] },
              "streamSettings": { "network": "tcp", "security": "tls" } },
            { "tag": "direct", "protocol": "freedom" },
            { "tag": "block", "protocol": "blackhole" },
            { "tag": "dns-out", "protocol": "dns", "settings": { "nonIPQuery": "skip" } }
          ],
          "routing": {
            "domainStrategy": "IPOnDemand",
            "rules": [
              { "type": "field", "port": 53, "network": "udp", "outboundTag": "dns-out" },
              { "type": "field", "network": "tcp,udp", "balancerTag": "QFP_ES_AUTO" }
            ],
            "balancers": [{
              "tag": "QFP_ES_AUTO", "selector": ["es-ru"], "fallbackTag": "block",
              "strategy": { "type": "leastLoad", "settings": {
                "maxRTT": "10s", "expected": 1, "tolerance": 0.34
              } }
            }]
          },
          "burstObservatory": {
            "pingConfig": { "timeout": "10s", "interval": "15s", "sampling": 3,
              "httpMethod": "GET", "destination": "https://speed.cloudflare.com/__down?bytes=32768" },
            "subjectSelector": ["es-ru"]
          }
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
        // The default exit (the first outbound) stays the config's own proxy; the WARP SOCKS is never it.
        val tags = o["outbounds"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertEquals("proxy", tags.first())
        assertEquals("warp-out", tags.last())
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

    @Test
    fun clientSideWarpKeepsInjectedOutboundOutOfRawBalancer() {
        val json = XrayConfig.prepareRaw(
            rawConfigJson = balancedRaw(),
            listenPort = 18081,
            warpChainPort = 18900,
        )
        dir?.resolve("raw-balanced-client.json")?.writeText(json)
        val root = Json.parseToJsonElement(json).jsonObject
        assertEquals("warp-out", root.outbound("proxy-a")["streamSettings"]!!.jsonObject
            ["sockopt"]!!.jsonObject["dialerProxy"]!!.jsonPrimitive.content)
        assertEquals("warp-out", root.outbound("proxy-b")["streamSettings"]!!.jsonObject
            ["sockopt"]!!.jsonObject["dialerProxy"]!!.jsonPrimitive.content)
        val selector = root["routing"]!!.jsonObject["balancers"]!!.jsonArray.single().jsonObject
            .get("selector")!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("proxy-a", "proxy-b"), selector, "the balancer must never select the injected WARP SOCKS")
        assertTrue(root["routing"]!!.jsonObject["balancers"]!!.jsonArray.single().jsonObject["fallbackTag"] == null,
            "fallbackTag requires an observatory and otherwise prevents Xray from starting")
    }

    @Test
    fun serverSideWarpRedirectsBalancedTrafficButRelaysThroughTheBalancer() {
        val json = XrayConfig.prepareRaw(
            rawConfigJson = balancedRaw(),
            listenPort = 18081,
            warpExitPort = 18900,
            warpRelay = WarpRelay(18993, "162.159.192.1", 2408),
        )
        dir?.resolve("raw-balanced-server.json")?.writeText(json)
        val root = Json.parseToJsonElement(json).jsonObject
        val routing = root["routing"]!!.jsonObject
        val rules = routing["rules"]!!.jsonArray.map { it.jsonObject }
        val relay = rules.first()
        assertEquals("proxy-a", relay["outboundTag"]!!.jsonPrimitive.content,
            "one WireGuard flow must be pinned to one concrete proxy member")
        assertTrue(relay["balancerTag"] == null)
        assertEquals("cascade-exit", rules.last()["outboundTag"]!!.jsonPrimitive.content,
            "balanced application traffic must exit through WARP")
        assertTrue(rules.last()["balancerTag"] == null)
        assertEquals("direct", rules[1]["outboundTag"]!!.jsonPrimitive.content)
        val selector = routing["balancers"]!!.jsonArray.single().jsonObject["selector"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(listOf("proxy-a", "proxy-b"), selector)
        assertTrue(routing["balancers"]!!.jsonArray.single().jsonObject["fallbackTag"] == null)
    }

    @Test
    fun serverSideWarpRecognizesBalancerOverSubscriptionChainOutbounds() {
        val json = XrayConfig.prepareRaw(
            rawConfigJson = chainedBalancedRaw(),
            listenPort = 18081,
            warpExitPort = 18900,
            warpRelay = WarpRelay(18993, "162.159.192.1", 2408),
        )
        dir?.resolve("raw-subscription-balanced-server.json")?.writeText(json)
        val routing = Json.parseToJsonElement(json).jsonObject["routing"]!!.jsonObject
        val rules = routing["rules"]!!.jsonArray.map { it.jsonObject }
        assertEquals("chain-a", rules.first()["outboundTag"]!!.jsonPrimitive.content,
            "WARP UDP must pin the first member of the last routed balancer")
        assertTrue(rules.first()["balancerTag"] == null)
        assertEquals("cascade-exit", rules[1]["outboundTag"]!!.jsonPrimitive.content,
            "every routed proxy balancer must be redirected through WARP")
        assertEquals("direct", rules[2]["outboundTag"]!!.jsonPrimitive.content)
        assertEquals("cascade-exit", rules.last()["outboundTag"]!!.jsonPrimitive.content,
            "a balancer over intermediate chain outbounds must still be redirected through WARP")
        assertTrue(rules.last()["balancerTag"] == null)
        val selector = routing["balancers"]!!.jsonArray.last().jsonObject["selector"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(listOf("chain-a", "chain-b"), selector)
    }

    @Test
    fun serverSideWarpBypassesLeastLoadBlackholeUntilObservatoryIsReady() {
        val json = XrayConfig.prepareRaw(
            rawConfigJson = leastLoadBalancedRaw(),
            listenPort = 18081,
            warpExitPort = 18900,
            warpRelay = WarpRelay(18993, "162.159.192.1", 2408),
        )
        dir?.resolve("raw-leastload-balanced-server.json")?.writeText(json)
        val root = Json.parseToJsonElement(json).jsonObject
        val routing = root["routing"]!!.jsonObject
        val rules = routing["rules"]!!.jsonArray.map { it.jsonObject }
        assertEquals("es-ru", rules.first()["outboundTag"]!!.jsonPrimitive.content,
            "the WARP handshake must not hit fallbackTag=block before burstObservatory has results")
        assertTrue(rules.first()["balancerTag"] == null)
        assertEquals("cascade-exit", rules.last()["outboundTag"]!!.jsonPrimitive.content)
        assertTrue(rules.last()["balancerTag"] == null)
        val balancer = routing["balancers"]!!.jsonArray.single().jsonObject
        assertEquals("leastLoad", balancer["strategy"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("block", balancer["fallbackTag"]!!.jsonPrimitive.content,
            "the imported config remains intact for its own observatory")
        assertEquals(listOf("es-ru", "es-ru-2"), balancer["selector"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(root["burstObservatory"] != null)
    }
}
