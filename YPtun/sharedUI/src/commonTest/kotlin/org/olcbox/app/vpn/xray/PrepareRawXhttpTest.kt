package org.olcbox.app.vpn.xray

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.ProxyProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Mirrors the user's xhttp+reality subscription config (its own dns/fakedns/routing with geosite:
 * selectors + a plain freedom direct). Verifies prepareRaw(stripGeoSelectors, forceIpv4) yields a
 * VALID xray config with no geosite:/geoip: left, the proxy outbound intact, and the direct freedom
 * forced to ForceIPv4.
 */
class PrepareRawXhttpTest {

    private val xhttpConfig = """
    {
      "dns": {
        "hosts": { "regexp:(^|\\.)2ip\\.ru+${'$'}": "198.18.0.186" },
        "queryStrategy": "UseIP",
        "servers": [
          { "address": "fakedns", "domains": ["regexp:(^|\\.)vk\\.[a-z0-9.-]+${'$'}"], "queryStrategy": "UseIP", "skipFallback": true },
          { "address": "1.1.1.1", "domains": ["geosite:telegram", "geosite:discord", "domain:t.me"], "port": 53, "skipFallback": true },
          "fakedns",
          "1.1.1.1"
        ]
      },
      "fakedns": [ { "ipPool": "198.18.0.0/15", "poolSize": 65535 } ],
      "inbounds": [
        { "listen": "127.0.0.1", "port": 10808, "protocol": "socks",
          "settings": { "auth": "noauth", "udp": true },
          "sniffing": { "destOverride": ["quic","http","tls","fakedns"], "enabled": true, "metadataOnly": false },
          "tag": "socks" }
      ],
      "outbounds": [
        { "protocol": "vless",
          "settings": { "vnext": [ { "address": "poetica.example.com", "port": 443, "users": [ { "encryption": "none", "id": "uuid" } ] } ] },
          "streamSettings": { "network": "xhttp", "security": "reality",
            "realitySettings": { "publicKey": "PK", "serverName": "5post-gate.x5.ru", "shortId": "9c2e" },
            "xhttpSettings": { "mode": "auto", "host": "", "extra": { "host": "poetica.example.com", "seqKey": "X-Request-Seq" } } },
          "tag": "proxy" },
        { "protocol": "freedom", "tag": "direct" },
        { "protocol": "blackhole", "tag": "block" }
      ],
      "routing": {
        "domainStrategy": "IPOnDemand",
        "rules": [
          { "ip": ["0.0.0.0"], "outboundTag": "direct", "type": "field" },
          { "ip": ["198.18.0.0/15"], "outboundTag": "direct", "type": "field" },
          { "domain": ["regexp:(^|\\.)vk\\.[a-z0-9.-]+${'$'}"], "outboundTag": "direct", "type": "field" },
          { "network": "udp", "outboundTag": "dns-out", "port": 53, "type": "field" },
          { "ip": ["geoip:private"], "outboundTag": "direct", "type": "field" },
          { "domain": ["geosite:private"], "outboundTag": "direct", "type": "field" }
        ]
      }
    }
    """.trimIndent()

    @Test
    fun xhttpConfigPreparesValidlyWithoutGeoSelectors() {
        val out = XrayConfig.prepareRaw(
            rawConfigJson = xhttpConfig,
            listenPort = 10808,
            listenHost = "127.0.0.1",
            stripGeoSelectors = true,
            forceIpv4 = true,
        )
        // Must be parseable + carry no geosite:/geoip: anywhere.
        val root = Json.parseToJsonElement(out).jsonObject
        assertFalse(out.contains("geosite:"), "geosite: should be stripped")
        assertFalse(out.contains("geoip:"), "geoip: should be stripped")

        // Proxy (xhttp) outbound preserved.
        val outbounds = root["outbounds"]!!.jsonArray.map { it.jsonObject }
        val proxy = outbounds.first { it["tag"]?.jsonPrimitive?.content == "proxy" }
        assertEquals("vless", proxy["protocol"]!!.jsonPrimitive.content)
        assertEquals(
            "xhttp",
            proxy["streamSettings"]!!.jsonObject["network"]!!.jsonPrimitive.content
        )

        // Direct freedom forced to ForceIPv4.
        val direct = outbounds.first { it["tag"]?.jsonPrimitive?.content == "direct" }
        assertEquals(
            "ForceIPv4",
            direct["settings"]!!.jsonObject["domainStrategy"]!!.jsonPrimitive.content
        )

        // Routing kept the regexp/ip rules, dropped the two purely-geo rules.
        val rules = root["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        assertTrue(rules.any { it["domain"]?.jsonArray?.any { d -> d.jsonPrimitive.content.startsWith("regexp:") } == true })
        assertTrue(rules.none { it["domain"]?.jsonArray?.any { d -> d.jsonPrimitive.content.startsWith("geosite:") } == true })
        assertTrue(rules.none { it["ip"]?.jsonArray?.any { i -> i.jsonPrimitive.content.startsWith("geoip:") } == true })

        // DNS A-only + fakedns preserved.
        assertEquals("UseIPv4", root["dns"]!!.jsonObject["queryStrategy"]!!.jsonPrimitive.content)
        assertTrue(root["fakedns"] != null)

        // The socks inbound was rewritten to our listen host/port.
        val inbounds = root["inbounds"]!!.jsonArray.map { it.jsonObject }
        assertTrue(inbounds.any { it["protocol"]?.jsonPrimitive?.content == "socks" && it["port"]?.jsonPrimitive?.content == "10808" })
    }

    @Test
    fun cascadeSecondProxyChainsThroughXhttpMain() {
        val second = ProxyProfile(
            tag = "exit", type = ProxyProfile.TYPE_VLESS,
            server = "exit.example.com", serverPort = 443, uuid = "exit-uuid",
            network = ProxyProfile.NETWORK_TCP, security = ProxyProfile.SECURITY_TLS,
            sni = "exit.example.com",
        )
        val out = XrayConfig.prepareRaw(
            rawConfigJson = xhttpConfig,
            listenPort = 10808,
            stripGeoSelectors = true,
            secondProfile = second,
        )
        val root = Json.parseToJsonElement(out).jsonObject
        val outbounds = root["outbounds"]!!.jsonArray.map { it.jsonObject }
        // Cascade exit present, is the FIRST outbound (default exit), and dials THROUGH the main "proxy".
        assertEquals("cascade-exit", outbounds.first()["tag"]!!.jsonPrimitive.content)
        val exit = outbounds.first { it["tag"]?.jsonPrimitive?.content == "cascade-exit" }
        assertEquals("vless", exit["protocol"]!!.jsonPrimitive.content)
        assertEquals(
            "proxy",
            exit["streamSettings"]!!.jsonObject["sockopt"]!!.jsonObject["dialerProxy"]!!.jsonPrimitive.content
        )
        // The xhttp main outbound is still there (as the first hop the exit dials through).
        assertTrue(outbounds.any { it["tag"]?.jsonPrimitive?.content == "proxy" })
    }

    @Test
    fun xhttpExtraHostIsLiftedToTopLevel() {
        // Domain-fronted config: real host only in extra.host, top-level host empty + reality SNI set.
        val fronted = """
        {
          "inbounds": [ { "listen": "127.0.0.1", "port": 10808, "protocol": "socks", "settings": { "auth": "noauth", "udp": true }, "tag": "socks" } ],
          "outbounds": [
            { "protocol": "vless",
              "settings": { "vnext": [ { "address": "poetica.example.com", "port": 443, "users": [ { "id": "uuid", "encryption": "none" } ] } ] },
              "streamSettings": { "network": "xhttp", "security": "reality",
                "realitySettings": { "publicKey": "PK", "serverName": "front.cdn.example", "shortId": "9c2e" },
                "xhttpSettings": { "mode": "auto", "host": "", "extra": { "host": "poetica.example.com", "seqKey": "X-Request-Seq" } } },
              "tag": "proxy" },
            { "protocol": "freedom", "tag": "direct" }
          ]
        }
        """.trimIndent()
        val out = XrayConfig.prepareRaw(rawConfigJson = fronted, listenPort = 10808)
        val proxy = Json.parseToJsonElement(out).jsonObject["outbounds"]!!.jsonArray
            .map { it.jsonObject }.first { it["tag"]?.jsonPrimitive?.content == "proxy" }
        val xhttp = proxy["streamSettings"]!!.jsonObject["xhttpSettings"]!!.jsonObject
        // Top-level host now carries the real (fronted) host so xray's extra-override sends it correctly.
        assertEquals("poetica.example.com", xhttp["host"]!!.jsonPrimitive.content)
        // extra is preserved (seqKey etc. still there).
        assertTrue(xhttp["extra"]!!.jsonObject["seqKey"] != null)
    }

    @Test
    fun amneziawgSecondIsNotChainedOnRaw() {
        val awg = ProxyProfile(
            tag = "awg", type = ProxyProfile.TYPE_AMNEZIAWG,
            server = "wg.example.com", serverPort = 51820,
            awgConfig = "[Interface]\nPrivateKey = x\n[Peer]\nPublicKey = y\nEndpoint = wg.example.com:51820",
        )
        val out = XrayConfig.prepareRaw(
            rawConfigJson = xhttpConfig,
            listenPort = 10808,
            stripGeoSelectors = true,
            secondProfile = awg,
        )
        val root = Json.parseToJsonElement(out).jsonObject
        val outbounds = root["outbounds"]!!.jsonArray.map { it.jsonObject }
        // No cascade exit injected (AWG can't be an Xray exit outbound).
        assertTrue(outbounds.none { it["tag"]?.jsonPrimitive?.content == "cascade-exit" })
    }
}
