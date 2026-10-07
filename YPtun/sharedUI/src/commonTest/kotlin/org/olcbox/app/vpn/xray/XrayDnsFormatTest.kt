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
            listOf("https://dns.google/dns-query", "https://one.one.one.one/dns-query", "https://dns.quad9.net/dns-query"),
            servers,
        )
    }
}
