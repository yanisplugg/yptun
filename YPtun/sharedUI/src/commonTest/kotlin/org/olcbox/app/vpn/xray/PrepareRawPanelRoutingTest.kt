package org.olcbox.app.vpn.xray

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A Remnawave/Happ-style JSON subscription config (usr.quofortpost.com, 11.09.2026, hosts trimmed) that
 * routes RU sites DIRECT through the resolver: `dns.hosts` maps them into 198.18.0.0/15 and
 * `ip: 198.18.0.0/15 → direct` catches them. Every one of its rules must still apply after prepareRaw.
 */
class PrepareRawPanelRoutingTest {
    private val config = """
        {
          "dns": {
            "hosts": {
              "regexp:(^|\\.)gov\\.ru${'$'}": "198.18.0.1",
              "regexp:(^|\\.)yandex\\.[a-z0-9.-]+${'$'}": "198.18.0.85",
              "plain.example": "198.18.0.200",
              "elsewhere.example": "203.0.113.9"
            },
            "servers": [
              { "port": 53, "address": "1.1.1.1", "domains": ["geosite:telegram", "domain:t.me"], "skipFallback": true },
              "fakedns",
              "1.1.1.1"
            ],
            "queryStrategy": "UseIP"
          },
          "fakedns": [ { "ipPool": "198.18.0.0/15", "poolSize": 65535 } ],
          "routing": {
            "rules": [
              { "ip": ["0.0.0.0"], "type": "field", "outboundTag": "direct" },
              { "ip": ["198.18.0.0/15"], "type": "field", "outboundTag": "direct" },
              { "port": 53, "type": "field", "network": "udp", "outboundTag": "dns-out" },
              { "port": 853, "type": "field", "network": "tcp", "outboundTag": "dns-out" },
              { "type": "field", "protocol": ["bittorrent"], "outboundTag": "block" },
              { "ip": ["geoip:private"], "type": "field", "outboundTag": "direct" },
              { "type": "field", "domain": ["geosite:private"], "outboundTag": "direct" }
            ],
            "domainStrategy": "IPOnDemand"
          },
          "inbounds": [ { "tag": "socks", "port": 10808, "listen": "127.0.0.1", "protocol": "socks",
            "settings": { "udp": true, "auth": "noauth" },
            "sniffing": { "enabled": true, "destOverride": ["quic", "http", "tls", "fakedns"], "metadataOnly": false } } ],
          "outbounds": [
            { "tag": "proxy", "protocol": "vless", "settings": { "vnext": [ { "address": "spb.quofortpost.com", "port": 443,
              "users": [ { "id": "732c8764-e31d-49ab-852b-54cb0f7cc3de", "encryption": "none", "flow": "xtls-rprx-vision" } ] } ] },
              "streamSettings": { "network": "tcp", "security": "tls", "tlsSettings": { "serverName": "spb.quofortpost.com" } } },
            { "tag": "direct", "protocol": "freedom" },
            { "tag": "block", "protocol": "blackhole" },
            { "tag": "dns-out", "protocol": "dns", "settings": { "nonIPQuery": "skip" } }
          ],
          "remarks": "🇷🇺СПБ (без РКН)"
        }
    """.trimIndent()

    private fun prepared(forceIpv4: Boolean) = Json.parseToJsonElement(
        XrayConfig.prepareRaw(rawConfigJson = config, listenPort = 10808, forceIpv4 = forceIpv4)
    ).jsonObject

    @Test
    fun routingHostsBecomeDomainRulesRightBeforeTheirIpRule() {
        val out = prepared(forceIpv4 = true)
        val rules = out["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        // Lifted rule sits where the 198.18.0.0/15 rule matched them, same outbound.
        assertEquals(8, rules.size)
        val lifted = rules[1]
        assertEquals("direct", lifted["outboundTag"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("regexp:(^|\\.)gov\\.ru$", "regexp:(^|\\.)yandex\\.[a-z0-9.-]+$", "full:plain.example"),
            lifted["domain"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals("198.18.0.0/15", rules[2]["ip"]!!.jsonArray.single().jsonPrimitive.content)
        // Every original rule is still there, in order.
        assertEquals(listOf("direct", "direct", "direct", "dns-out", "dns-out", "block", "direct", "direct"),
            rules.map { it["outboundTag"]!!.jsonPrimitive.content })
        assertEquals("IPOnDemand", out["routing"]!!.jsonObject["domainStrategy"]!!.jsonPrimitive.content)

        // The lifted entries left `hosts` (a direct dial must resolve to the REAL address); others stay.
        val hosts = out["dns"]!!.jsonObject["hosts"]!!.jsonObject
        assertEquals(setOf("elsewhere.example"), hosts.keys)
        // DNS servers, FakeDNS pool and the sniffer that restores fakedns domains are untouched.
        val servers = out["dns"]!!.jsonObject["servers"] as JsonArray
        assertEquals(3, servers.size)
        assertTrue("geosite:telegram" in servers[0].jsonObject["domains"].toString())
        assertEquals("198.18.0.0/15", out["fakedns"]!!.jsonArray[0].jsonObject["ipPool"]!!.jsonPrimitive.content)
        val sniff = out["inbounds"]!!.jsonArray[0].jsonObject["sniffing"]!!.jsonObject
        assertTrue("fakedns" in sniff["destOverride"].toString())
        // ipv4_only: direct resolves through xray's DNS — which no longer holds the synthetic answers.
        val direct = out["outbounds"]!!.jsonArray.map { it.jsonObject }.single { it["tag"]!!.jsonPrimitive.content == "direct" }
        assertEquals("ForceIPv4", direct["settings"]!!.jsonObject["domainStrategy"]!!.jsonPrimitive.content)
        assertEquals("UseIPv4", out["dns"]!!.jsonObject["queryStrategy"]!!.jsonPrimitive.content)
    }

    @Test
    fun configWithoutRoutingKeepsItsHosts() {
        val bare = Json.parseToJsonElement(config).jsonObject.let { root ->
            JsonObject(root.filterKeys { it != "routing" })
        }
        val out = Json.parseToJsonElement(XrayConfig.prepareRaw(rawConfigJson = bare.toString(), listenPort = 10808)).jsonObject
        assertEquals(4, out["dns"]!!.jsonObject["hosts"]!!.jsonObject.size)
        assertFalse(out.containsKey("routing") && "full:plain.example" in out["routing"].toString())
    }
}
