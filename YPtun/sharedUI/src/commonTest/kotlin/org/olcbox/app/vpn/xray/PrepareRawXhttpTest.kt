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
        // Cascade exit present, is the FIRST outbound (default exit).
        assertEquals("cascade-exit", outbounds.first()["tag"]!!.jsonPrimitive.content)
        val exit = outbounds.first { it["tag"]?.jsonPrimitive?.content == "cascade-exit" }
        assertEquals("vless", exit["protocol"]!!.jsonPrimitive.content)
        // An xhttp/splithttp MAIN can't be a proxySettings target NOR a dialerProxy sub-dialer, so the
        // exit chains through a local SOCKS loopback instead: the exit dials via sockopt.dialerProxy =
        // "cascade-loop-out", NOT proxySettings. (The xhttp main then runs as an ordinary outbound.)
        assertTrue(exit["proxySettings"] == null, "xhttp-main cascade must NOT chain via proxySettings (xhttp can't be the intermediate)")
        assertEquals(
            "cascade-loop-out",
            exit["streamSettings"]!!.jsonObject["sockopt"]!!.jsonObject["dialerProxy"]!!.jsonPrimitive.content
        )
        // The loopback outbound (socks → 127.0.0.1) and inbound (a relay) exist.
        val loopOut = outbounds.first { it["tag"]?.jsonPrimitive?.content == "cascade-loop-out" }
        assertEquals("socks", loopOut["protocol"]!!.jsonPrimitive.content)
        assertEquals("127.0.0.1", loopOut["settings"]!!.jsonObject["servers"]!!.jsonArray.first().jsonObject["address"]!!.jsonPrimitive.content)
        val inbounds = root["inbounds"]!!.jsonArray.map { it.jsonObject }
        val loopIn = inbounds.first { it["tag"]?.jsonPrimitive?.content == "cascade-loop-in" }
        assertEquals("socks", loopIn["protocol"]!!.jsonPrimitive.content)
        // The loopback port is one above the app's listen port, and the two ends agree.
        assertEquals("10809", loopIn["port"]!!.jsonPrimitive.content)
        assertEquals("10809", loopOut["settings"]!!.jsonObject["servers"]!!.jsonArray.first().jsonObject["port"]!!.jsonPrimitive.content)
        // A high-priority routing rule sends the loopback inbound straight to the real xhttp main (so it
        // does NOT loop back into cascade-exit, and is NOT sent direct/geo).
        val rules = root["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        val loopRule = rules.first { it["inboundTag"]?.jsonArray?.any { t -> t.jsonPrimitive.content == "cascade-loop-in" } == true }
        assertEquals("proxy", loopRule["outboundTag"]!!.jsonPrimitive.content)
        // The xhttp main outbound is still there (the loopback inbound routes to it).
        assertTrue(outbounds.any { it["tag"]?.jsonPrimitive?.content == "proxy" })
    }

    @Test
    fun cascadeXhttpExitKeepsDialerProxy() {
        // When the SECOND proxy is itself xhttp, dialerProxy IS required (proxySettings would drop the
        // exit's xhttp transport → raw VLESS to the server → HTTP web-fallback reject). Over the xhttp
        // MAIN the dialer target is the loopback SOCKS (not the main directly, which can't sub-dial).
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
            "cascade-loop-out",
            exit["streamSettings"]!!.jsonObject["sockopt"]!!.jsonObject["dialerProxy"]!!.jsonPrimitive.content
        )
        assertTrue(exit["proxySettings"] == null, "xhttp exit chains via dialerProxy, not proxySettings")
    }

    @Test
    fun cascadeExitKeepsXtlsVisionFlowOverLoopback() {
        // A vless-reality-VISION tcp 2nd proxy over an xhttp main: the 2nd server's vless inbound REQUIRES
        // the Vision flow it was configured with. Dropping it on the chained exit made the server reset
        // every stream ("EOF") — while an xhttp 2nd (no Vision flow) worked. The SOCKS loopback gives the
        // exit a clean transparent TCP stream Vision can traverse, so the flow must be KEPT here.
        val second = ProxyProfile(
            tag = "exit", type = ProxyProfile.TYPE_VLESS,
            server = "exit.example.com", serverPort = 443, uuid = "exit-uuid",
            network = ProxyProfile.NETWORK_TCP, security = ProxyProfile.SECURITY_REALITY,
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
        assertEquals("xtls-rprx-vision", user["flow"]!!.jsonPrimitive.content, "Vision flow must be KEPT over the clean loopback")
        // It still chains through the main proxy — over an xhttp main via the SOCKS loopback dialer.
        assertEquals(
            "cascade-loop-out",
            exit["streamSettings"]!!.jsonObject["sockopt"]!!.jsonObject["dialerProxy"]!!.jsonPrimitive.content
        )
        assertTrue(exit["proxySettings"] == null, "xhttp-main cascade chains via the loopback dialer, not proxySettings")
    }

    @Test
    fun buildCascadeOverXhttpMainKeepsVisionFlow() {
        // Same Vision-flow requirement on the build() path (normal ProxyProfile main, the user's real case).
        val main = ProxyProfile(
            tag = "main", type = ProxyProfile.TYPE_VLESS, server = "fin.example.com", serverPort = 8443,
            uuid = "uuid-main", network = ProxyProfile.NETWORK_XHTTP, security = ProxyProfile.SECURITY_REALITY,
            sni = "fin.example.com",
        )
        val second = ProxyProfile(
            tag = "exit", type = ProxyProfile.TYPE_VLESS, server = "nbg.example.com", serverPort = 9444,
            uuid = "uuid-exit", network = ProxyProfile.NETWORK_TCP, security = ProxyProfile.SECURITY_REALITY,
            sni = "nbg.example.com", flow = "xtls-rprx-vision",
        )
        val out = XrayConfig.build(profile = main, listenPort = 10808, secondProfile = second)
        val exit = Json.parseToJsonElement(out).jsonObject["outbounds"]!!.jsonArray
            .map { it.jsonObject }.first { it["tag"]?.jsonPrimitive?.content == "proxy" }
        val user = exit["settings"]!!.jsonObject["vnext"]!!.jsonArray.first().jsonObject["users"]!!
            .jsonArray.first().jsonObject
        assertEquals("xtls-rprx-vision", user["flow"]!!.jsonPrimitive.content)
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
    fun buildCascadeOverXhttpMainUsesSocksLoopback() {
        // The user's real setup (Finland-xhttp MAIN + Nürnberg-tcp 2nd proxy) runs through build(), NOT
        // prepareRaw(). An xhttp main can't be a dialerProxy sub-dialer, so the exit dialing "through"
        // proxy-base died ("failed to find an available destination > EOF"). Pin the loopback fix:
        // the exit dials via cascade-loop-out, a relay inbound exists, and a rule routes it to proxy-base.
        val main = ProxyProfile(
            tag = "main", type = ProxyProfile.TYPE_VLESS, server = "fin.example.com", serverPort = 8443,
            uuid = "uuid-main", network = ProxyProfile.NETWORK_XHTTP, security = ProxyProfile.SECURITY_REALITY,
            sni = "fin.example.com",
        )
        val second = ProxyProfile(
            tag = "exit", type = ProxyProfile.TYPE_VLESS, server = "nbg.example.com", serverPort = 9444,
            uuid = "uuid-exit", network = ProxyProfile.NETWORK_TCP, security = ProxyProfile.SECURITY_TLS,
            sni = "nbg.example.com",
        )
        val out = XrayConfig.build(profile = main, listenPort = 10808, secondProfile = second)
        val root = Json.parseToJsonElement(out).jsonObject

        val outbounds = root["outbounds"]!!.jsonArray.map { it.jsonObject }
        // Exit is PROXY_TAG and dials through the SOCKS loopback, NOT directly through proxy-base.
        val exit = outbounds.first { it["tag"]?.jsonPrimitive?.content == "proxy" }
        assertEquals(
            "cascade-loop-out",
            exit["streamSettings"]!!.jsonObject["sockopt"]!!.jsonObject["dialerProxy"]!!.jsonPrimitive.content
        )
        assertTrue(exit["proxySettings"] == null)
        // The relay outbound (socks → 127.0.0.1:10809) and the main (proxy-base) both exist.
        val loopOut = outbounds.first { it["tag"]?.jsonPrimitive?.content == "cascade-loop-out" }
        assertEquals("10809", loopOut["settings"]!!.jsonObject["servers"]!!.jsonArray.first().jsonObject["port"]!!.jsonPrimitive.content)
        val base = outbounds.first { it["tag"]?.jsonPrimitive?.content == "proxy-base" }
        // The xhttp main gets an xmux that spreads the loopback's per-flow connections across a small
        // pool of reused H2 tunnels (4-8): under the server cap that broke 18 connections, yet parallel
        // enough to avoid funnelling everything onto one HOL-blocked connection.
        assertEquals(
            "4-8",
            base["streamSettings"]!!.jsonObject["xhttpSettings"]!!.jsonObject["xmux"]!!.jsonObject["maxConnections"]!!.jsonPrimitive.content
        )
        // The relay inbound listens on the loopback port.
        val loopIn = root["inbounds"]!!.jsonArray.map { it.jsonObject }
            .first { it["tag"]?.jsonPrimitive?.content == "cascade-loop-in" }
        assertEquals("10809", loopIn["port"]!!.jsonPrimitive.content)
        // First routing rule sends the relay inbound straight to the xhttp main.
        val firstRule = root["routing"]!!.jsonObject["rules"]!!.jsonArray.first().jsonObject
        assertEquals("cascade-loop-in", firstRule["inboundTag"]!!.jsonArray.first().jsonPrimitive.content)
        assertEquals("proxy-base", firstRule["outboundTag"]!!.jsonPrimitive.content)
    }

    @Test
    fun buildSingleHopXhttpHasNoLoopback() {
        // No second proxy → no loopback machinery at all (single-hop xhttp is unaffected).
        val main = ProxyProfile(
            tag = "main", type = ProxyProfile.TYPE_VLESS, server = "fin.example.com", serverPort = 8443,
            uuid = "uuid-main", network = ProxyProfile.NETWORK_XHTTP, security = ProxyProfile.SECURITY_REALITY,
            sni = "fin.example.com",
        )
        val out = XrayConfig.build(profile = main, listenPort = 10808)
        assertFalse(out.contains("cascade-loop"), "single-hop must not add loopback inbound/outbound")
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
