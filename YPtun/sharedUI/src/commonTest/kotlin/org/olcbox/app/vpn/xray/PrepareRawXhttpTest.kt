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
    fun xhttpConfigHonorsItsOwnGeoRoutingWhenNotStripped() {
        // The user's requirement: a JSON config's OWN routing takes precedence over the app's. When the
        // geo .dat is available (the service downloads it before connecting), prepareRaw runs with
        // stripGeoSelectors=false so the config's geosite:/geoip: rules (RU-direct / ad-block) are kept
        // VERBATIM — NOT stripped, NOT overlaid with the app routing profile. Guards against the
        // strip-everything regression (geosite:ru-available-only-inside stripped → RU sites wrongly
        // detour through the proxy = "slow"; the whole point of honoring the embedded routing).
        val out = XrayConfig.prepareRaw(
            rawConfigJson = xhttpConfig,
            listenPort = 10808,
            stripGeoSelectors = false,
        )
        val root = Json.parseToJsonElement(out).jsonObject
        // Geo selectors preserved so xray-core applies the config's embedded routing.
        assertTrue(out.contains("geosite:"), "geosite: must be preserved when not stripping")
        assertTrue(out.contains("geoip:"), "geoip: must be preserved when not stripping")
        // The config's own geo rules are intact (not dropped).
        val rules = root["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        assertTrue(
            rules.any { it["domain"]?.jsonArray?.any { d -> d.jsonPrimitive.content == "geosite:private" } == true },
            "the config's geosite:private rule should survive verbatim"
        )
        assertTrue(
            rules.any { it["ip"]?.jsonArray?.any { i -> i.jsonPrimitive.content == "geoip:private" } == true },
            "the config's geoip:private rule should survive verbatim"
        )
        // The xhttp proxy outbound is untouched (verbatim transport → full speed).
        val proxy = root["outbounds"]!!.jsonArray.map { it.jsonObject }
            .first { it["tag"]?.jsonPrimitive?.content == "proxy" }
        assertEquals("xhttp", proxy["streamSettings"]!!.jsonObject["network"]!!.jsonPrimitive.content)
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
        // Cascade exit present, is the FIRST outbound (default exit), and chains THROUGH the main "proxy".
        assertEquals("cascade-exit", outbounds.first()["tag"]!!.jsonPrimitive.content)
        val exit = outbounds.first { it["tag"]?.jsonPrimitive?.content == "cascade-exit" }
        assertEquals("vless", exit["protocol"]!!.jsonPrimitive.content)
        // A tcp/reality exit chains at the PROXY level (proxySettings.tag), NOT sockopt.dialerProxy —
        // dialerProxy makes the exit's raw socket be dialed through the base, and an xhttp/splithttp base
        // can't serve as a generic sub-dialer (→ no connection). proxySettings is the working multi-hop.
        assertEquals(
            "proxy",
            exit["proxySettings"]!!.jsonObject["tag"]!!.jsonPrimitive.content
        )
        assertTrue(exit["streamSettings"]!!.jsonObject["sockopt"] == null, "tcp exit must NOT use dialerProxy over an xhttp base")
        // The xhttp main outbound is still there (as the first hop the exit chains through).
        assertTrue(outbounds.any { it["tag"]?.jsonPrimitive?.content == "proxy" })
    }

    @Test
    fun cascadeXhttpExitKeepsDialerProxy() {
        // When the SECOND proxy is itself xhttp, dialerProxy IS required (proxySettings would drop the
        // exit's xhttp transport → raw VLESS to the server → HTTP web-fallback reject). Pin that case.
        val second = ProxyProfile(
            tag = "exit", type = ProxyProfile.TYPE_VLESS,
            server = "exit.example.com", serverPort = 443, uuid = "exit-uuid",
            network = ProxyProfile.NETWORK_XHTTP, security = ProxyProfile.SECURITY_TLS,
            sni = "exit.example.com",
        )
        val out = XrayConfig.prepareRaw(
            rawConfigJson = xhttpConfig, listenPort = 10808,
            stripGeoSelectors = true, secondProfile = second,
        )
        val exit = Json.parseToJsonElement(out).jsonObject["outbounds"]!!.jsonArray
            .map { it.jsonObject }.first { it["tag"]?.jsonPrimitive?.content == "cascade-exit" }
        assertEquals(
            "proxy",
            exit["streamSettings"]!!.jsonObject["sockopt"]!!.jsonObject["dialerProxy"]!!.jsonPrimitive.content
        )
        assertTrue(exit["proxySettings"] == null, "xhttp exit chains via dialerProxy, not proxySettings")
    }

    @Test
    fun cascadeExitDropsXtlsVisionFlow() {
        // XTLS Vision splices the RAW TLS to its own server and CAN'T ride a chain — a vless-reality/
        // vision 2nd proxy over the xhttp main hung ("no connection"). The chained exit must drop `flow`.
        val second = ProxyProfile(
            tag = "exit", type = ProxyProfile.TYPE_VLESS,
            server = "exit.example.com", serverPort = 443, uuid = "exit-uuid",
            network = ProxyProfile.NETWORK_TCP, security = ProxyProfile.SECURITY_TLS,
            sni = "exit.example.com", flow = "xtls-rprx-vision",
        )
        val out = XrayConfig.prepareRaw(
            rawConfigJson = xhttpConfig,
            listenPort = 10808,
            stripGeoSelectors = true,
            secondProfile = second,
        )
        val exit = Json.parseToJsonElement(out).jsonObject["outbounds"]!!.jsonArray
            .map { it.jsonObject }.first { it["tag"]?.jsonPrimitive?.content == "cascade-exit" }
        val user = exit["settings"]!!.jsonObject["vnext"]!!.jsonArray.first().jsonObject["users"]!!
            .jsonArray.first().jsonObject
        assertTrue(user["flow"] == null, "Vision flow must be dropped on a chained exit (else it hangs)")
        // It still chains through the main proxy (a tcp exit uses proxy-level proxySettings, not dialerProxy).
        assertEquals(
            "proxy",
            exit["proxySettings"]!!.jsonObject["tag"]!!.jsonPrimitive.content
        )
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
    fun rawProxyPingConfigKeepsVerbatimTransportAndRoutesAllViaProxy() {
        // The per-server "ping" must probe THROUGH the verbatim xhttp/reality outbound — rebuilding a
        // plain vless from type/server/port can't reach the server, which is why such locations falsely
        // showed "недоступен". Pin: the xhttp+reality transport is preserved verbatim, the only outbound
        // is the proxy (so every dialed destination exits via it, à la Happ), and NO geo selectors are
        // referenced (a ping needs no geosite.dat, so it loads on any network).
        val out = XrayConfig.buildRawProxyPingConfig(
            rawConfigJson = xhttpConfig,
            listenPort = 34567,
        )!!
        val root = Json.parseToJsonElement(out).jsonObject
        assertFalse(out.contains("geosite:"), "ping config must not depend on a geosite.dat")
        assertFalse(out.contains("geoip:"), "ping config must not depend on a geoip.dat")

        // Exactly one outbound, the proxy, with its xhttp/reality streamSettings intact.
        val outbounds = root["outbounds"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, outbounds.size, "all traffic must exit through the single proxy outbound")
        val proxy = outbounds.single()
        assertEquals("proxy", proxy["tag"]!!.jsonPrimitive.content)
        assertEquals("vless", proxy["protocol"]!!.jsonPrimitive.content)
        val stream = proxy["streamSettings"]!!.jsonObject
        assertEquals("xhttp", stream["network"]!!.jsonPrimitive.content)
        assertEquals("reality", stream["security"]!!.jsonPrimitive.content)
        // The reality/xhttp fronting that a bare ProxyProfile loses is carried verbatim.
        assertTrue(stream["realitySettings"] != null && stream["xhttpSettings"] != null)

        // No routing block → default-routed to the lone proxy outbound.
        assertTrue(root["routing"] == null, "ping config carries no routing (everything → proxy)")

        // Listen host/port applied to the socks inbound.
        val inbound = root["inbounds"]!!.jsonArray.map { it.jsonObject }.single()
        assertEquals("socks", inbound["protocol"]!!.jsonPrimitive.content)
        assertEquals("34567", inbound["port"]!!.jsonPrimitive.content)
    }

    @Test
    fun rawProxyPingConfigReturnsNullWithoutProxyOutbound() {
        // A config whose only outbounds are freedom/blackhole isn't pingable through a proxy.
        val noProxy = """
        { "outbounds": [ { "protocol": "freedom", "tag": "direct" }, { "protocol": "blackhole", "tag": "block" } ] }
        """.trimIndent()
        assertEquals(null, XrayConfig.buildRawProxyPingConfig(noProxy, listenPort = 10808))
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

    @Test
    fun buildRoutesRemoteDnsOverDohOrTcpButLeavesDirectUdp() {
        // Device logs (Finland-xhttp + Nürnberg cascade): DNS to 8.8.8.8/1.1.1.1 timed out over the exit
        // (port 53 blocked both UDP & TCP) → 4s serial timeouts → ERR_CONNECTION_ABORTED. Remote resolvers
        // must ride DoH-over-443 (major provider IPs) or DNS-over-TCP (other IPs); direct DNS stays plain
        // UDP (queried for direct/RU domains where UDP is fine).
        fun servers(remote: String, remote2: String): List<String> {
            val main = ProxyProfile(
                tag = "main", type = ProxyProfile.TYPE_VLESS, server = "main.example.com", serverPort = 8443,
                uuid = "uuid-main", network = ProxyProfile.NETWORK_XHTTP, security = ProxyProfile.SECURITY_TLS,
                sni = "main.example.com",
            )
            val out = XrayConfig.build(
                profile = main, listenPort = 10808,
                traffic = org.olcbox.app.data.model.TrafficSettings(
                    remoteDns = remote, remoteDns2 = remote2, directDns = "223.5.5.5",
                ),
            )
            return Json.parseToJsonElement(out).jsonObject["dns"]!!.jsonObject["servers"]!!.jsonArray
                .map { it.jsonPrimitive.content }
        }
        // Major providers → DoH on their own IP; direct DNS untouched.
        val s1 = servers("8.8.8.8", "1.1.1.1")
        assertTrue(s1.contains("https://8.8.8.8/dns-query"), "Google remoteDns → DoH")
        assertTrue(s1.contains("https://1.1.1.1/dns-query"), "Cloudflare remoteDns2 → DoH")
        assertTrue(s1.contains("223.5.5.5"), "directDns stays plain UDP")
        assertFalse(s1.contains("https://223.5.5.5/dns-query"), "directDns must NOT be rewritten")
        // A non-DoH custom IP → DNS-over-TCP fallback (still no port-53-UDP reliance).
        val s2 = servers("45.90.28.0", "")
        assertTrue(s2.contains("tcp://45.90.28.0"), "unknown IP remoteDns → tcp://")
    }
}
