package org.olcbox.app.vpn.singbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.data.model.RoutingProfile
import org.olcbox.app.data.model.RoutingRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Remote `.srs` rule-sets must be fetched THROUGH THE TUNNEL.
 *
 * They live on raw.githubusercontent.com, which is blocked in the places this app exists to serve, and
 * a failed initial fetch is fatal, not cosmetic: sing-box's RemoteRuleSet.StartContext returns
 * "initial rule-set: <tag>" and the whole core refuses to start. Downloading them over `direct` meant
 * any profile with a geosite:/geoip: selector could take the connection down with it — or, when the
 * core did start, leave every geo rule permanently unmatched ("routing does nothing").
 */
class SingBoxRuleSetDetourTest {

    private val profile = ProxyProfile(
        type = ProxyProfile.TYPE_VLESS,
        server = "1.2.3.4",
        serverPort = 443,
        uuid = "11111111-1111-1111-1111-111111111111",
        network = ProxyProfile.NETWORK_TCP,
        security = ProxyProfile.SECURITY_TLS,
        sni = "example.com",
    )

    private fun ruleSets(routingProfile: RoutingProfile?, routing: RoutingRules): List<String> {
        val json = SingBoxConfig.build(
            profile = profile,
            listenPort = 10808,
            listenHost = "127.0.0.1",
            socksUsername = "",
            socksPassword = "",
            routing = routing,
            routingProfile = routingProfile,
        )
        val sets = Json.parseToJsonElement(json).jsonObject["route"]!!.jsonObject["rule_set"]
            ?.jsonArray.orEmpty()
        return sets.map { it.jsonObject["download_detour"]!!.jsonPrimitive.content }
    }

    @Test
    fun profileRuleSetsDownloadThroughTheProxy() {
        val detours = ruleSets(
            RoutingProfile(
                id = "ru",
                directSites = listOf("geosite:ru"),
                directIp = listOf("geoip:ru"),
            ),
            RoutingRules(),
        )
        assertTrue(detours.isNotEmpty(), "a geo profile must emit rule-sets")
        detours.forEach { assertEquals(SingBoxRouting.PROXY_TAG, it) }
    }

    @Test
    fun toggleRuleSetsDownloadThroughTheProxy() {
        val detours = ruleSets(null, RoutingRules(bypassRussia = true, blockAds = true))
        assertTrue(detours.isNotEmpty(), "the bypass-RU / block-ads toggles must emit rule-sets")
        detours.forEach { assertEquals(SingBoxRouting.PROXY_TAG, it) }
    }
}
