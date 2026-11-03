package org.olcbox.app.vpn.xray

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.data.model.TrafficSettings
import kotlin.test.Test
import kotlin.test.assertEquals

/** Xray has no DoT client: `tls://` resolvers become DoH on the same host; DoH/IPs pass through. */
class XrayDnsFormatTest {
    @Test
    fun dohKeptAndDotBecomesDoh() {
        val profile = ProxyProfile(
            type = ProxyProfile.TYPE_VLESS, server = "vbn.azz.su", serverPort = 443,
            uuid = "11111111-1111-1111-1111-111111111111",
            network = ProxyProfile.NETWORK_TCP, security = ProxyProfile.SECURITY_TLS, sni = "vbn.azz.su",
        )
        val traffic = TrafficSettings(
            remoteDns = "https://dns.google/dns-query",
            remoteDns2 = "tls://one.one.one.one:853",
            directDns = "tls://dns.quad9.net",
        )
        val servers = Json.parseToJsonElement(XrayConfig.build(profile = profile, listenPort = 10808, traffic = traffic))
            .jsonObject["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(
            listOf("https://8.8.8.8/dns-query", "https://1.1.1.1/dns-query", "https://9.9.9.9/dns-query"),
            servers,
        )
    }

    private val profile = ProxyProfile(
        type = ProxyProfile.TYPE_VLESS, server = "vbn.azz.su", serverPort = 443,
        uuid = "11111111-1111-1111-1111-111111111111",
        network = ProxyProfile.NETWORK_TCP, security = ProxyProfile.SECURITY_TLS, sni = "vbn.azz.su",
    )

    /** A DoH server is reached by Xray's own DNS client, which has no detour: pin it to the proxy by routing. */
    @Test
    fun remoteResolversRideTheProxyWhateverTheProfileSays() {
        val traffic = TrafficSettings(
            remoteDns = "https://cloudflare-dns.com/dns-query",
            remoteDns2 = "https://dns.example.org/dns-query",
            directDns = "77.88.8.8",
        )
        val rules = Json.parseToJsonElement(XrayConfig.build(profile = profile, listenPort = 10808, traffic = traffic))
            .jsonObject["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        val domainRule = rules.first { it["domain"] != null }
        assertEquals(listOf("full:dns.example.org"), domainRule["domain"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("proxy", domainRule["outboundTag"]!!.jsonPrimitive.content)
        // Well-known DoH hostnames are rewritten to their IP, which is pinned by an ip rule instead.
        val ipRule = rules.first { it["ip"] != null }
        assertEquals(listOf("1.1.1.1"), ipRule["ip"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun endpointsSkipTheDirectResolverLocalOnesAndPrivateAddresses() {
        val (domains, ips) = XrayConfig.remoteDnsEndpoints(
            TrafficSettings(remoteDns = "1.1.1.1", remoteDns2 = "https+local://dns.google/dns-query", directDns = "77.88.8.8")
        )
        assertEquals(emptyList(), domains)
        assertEquals(listOf("1.1.1.1"), ips)
        val (d2, i2) = XrayConfig.remoteDnsEndpoints(TrafficSettings(remoteDns = "192.168.1.1", remoteDns2 = "dns.example.org"))
        assertEquals(listOf("full:dns.example.org"), d2)
        assertEquals(emptyList(), i2)
    }

    /** A hostname DoH would be bootstrapped through the DNS being configured (4s stall); known hosts become IPs. */
    @Test
    fun knownDohHostnamesBecomeIps() {
        fun servers(r1: String) = Json.parseToJsonElement(
            XrayConfig.build(profile = profile, listenPort = 10808, traffic = TrafficSettings(remoteDns = r1, remoteDns2 = "", directDns = "77.88.8.8"))
        ).jsonObject["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonPrimitive.content }.first()
        assertEquals("https://1.1.1.1/dns-query", servers("https://cloudflare-dns.com/dns-query"))
        assertEquals("https://1.1.1.1/dns-query", servers("tls://cloudflare-dns.com"))
        assertEquals("https://8.8.8.8/dns-query", servers("https://DNS.GOOGLE"))
        // another path / host is the user's own choice
        assertEquals("https://cloudflare-dns.com/family", servers("https://cloudflare-dns.com/family"))
        assertEquals("https://dns.example.org/dns-query", servers("https://dns.example.org/dns-query"))
    }
}
