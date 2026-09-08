package org.olcbox.app.vpn.xray

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.FakeDnsSpec
import org.olcbox.app.data.model.ProxyProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FakeDNS is per-location: a JSON subscription's fakeip pool travels as a [FakeDnsSpec]. sing-box has
 * always reproduced it natively; xray-core's builder only ever looked at the (removed, always-false)
 * global toggle, so the same location lost FakeDNS the moment it ran on Xray — an explicit core
 * choice, the app-wide default, blockRuDomains, or a routing profile with dns.hosts all do that.
 */
class XrayFakeDnsSpecTest {

    private val profile = ProxyProfile(
        tag = "n", type = ProxyProfile.TYPE_VLESS, server = "1.2.3.4", serverPort = 443,
        uuid = "11111111-2222-3333-4444-555555555555",
    )

    private fun build(spec: FakeDnsSpec?) = Json.parseToJsonElement(
        XrayConfig.build(profile = profile, listenPort = 10808, fakeDnsSpec = spec)
    ).jsonObject

    @Test
    fun specTurnsOnTheWholeFakeDnsPlumbing() {
        val root = build(
            FakeDnsSpec(
                inet4Range = "198.19.0.0/16",
                inet6Range = "fc00::/18",
                blockRegex = listOf("^ads[.]example$"),
            )
        )

        assertEquals(
            "198.19.0.0/16",
            root["fakedns"]!!.jsonArray.single().jsonObject["ipPool"]?.jsonPrimitive?.content,
            "the config's own pool must be used, not our default"
        )
        val dns = root["dns"]!!.jsonObject
        assertEquals("fakedns", dns["servers"]!!.jsonArray.first().jsonPrimitive.content)
        assertEquals(
            "0.0.0.0",
            dns["hosts"]!!.jsonObject["regexp:^ads[.]example$"]?.jsonPrimitive?.content,
            "the config's dns.hosts blackholes must be reproduced"
        )

        val sniffing = root["inbounds"]!!.jsonArray.first().jsonObject["sniffing"]!!.jsonObject
        assertTrue(
            sniffing["destOverride"]!!.jsonArray.any { it.jsonPrimitive.content == "fakedns" },
            "without the fakedns sniffer the synthetic IP never resolves back to a domain"
        )

        val outboundTags = root["outbounds"]!!.jsonArray
            .map { it.jsonObject["tag"]?.jsonPrimitive?.content }
        assertTrue("dns-out" in outboundTags, "hijacked queries need the dns outbound")

        val rules = root["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        // Scoped to our own inbound: xray's DNS client dials `tcp://<ip>` upstreams itself, and an
        // unscoped port-53 rule would hijack those back into dns-out and loop.
        val hijack = rules.filter { it["outboundTag"]?.jsonPrimitive?.content == "dns-out" }
        assertTrue(hijack.isNotEmpty(), "DNS must be hijacked to dns-out")
        assertTrue(
            hijack.all { it["inboundTag"]?.jsonArray?.any { t -> t.jsonPrimitive.content == "socks-in" } == true },
            "the hijack must be scoped to socks-in, else xray loops on its own tcp:// resolvers"
        )
        // TCP/53 matters on desktop: the sing-box TUN front forwards system DNS as a SOCKS CONNECT.
        assertTrue(
            hijack.any { it["port"]?.jsonPrimitive?.content == "53" && it["network"] == null },
            "port 53 must be hijacked on BOTH networks, got $hijack"
        )
        // A synthetic address can never be dialled for real — it must always reach the proxy.
        assertTrue(
            rules.any {
                it["outboundTag"]?.jsonPrimitive?.content == "proxy" &&
                    it["ip"]?.jsonArray?.any { ip -> ip.jsonPrimitive.content == "198.19.0.0/16" } == true
            },
            "the spec's pool must be pinned to the proxy, got $rules"
        )
        // dns.hosts sends the blocked domains to 0.0.0.0; something has to blackhole that.
        assertTrue(
            rules.any {
                it["outboundTag"]?.jsonPrimitive?.content == "block" &&
                    it["ip"]?.jsonArray?.any { ip -> ip.jsonPrimitive.content == "0.0.0.0" } == true
            },
            "a blackholed host must be blocked, not dialled"
        )
    }

    @Test
    fun noSpecKeepsTheConfigExactlyAsBefore() {
        val root = build(null)
        assertTrue(root["fakedns"] == null, "no spec, no fakedns block")
        assertTrue(
            root["routing"]!!.jsonObject["rules"]!!.jsonArray
                .none { it.jsonObject["outboundTag"]?.jsonPrimitive?.content == "dns-out" },
            "no spec, no DNS hijack"
        )
    }
}
