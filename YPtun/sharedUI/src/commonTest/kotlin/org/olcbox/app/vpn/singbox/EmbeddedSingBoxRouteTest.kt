package org.olcbox.app.vpn.singbox

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.datasource.LocationsDataSource
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.RoutingProfile
import org.olcbox.app.data.model.RoutingRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A sing-box JSON subscription ships its own `route`, and it used to be dropped on import (only the
 * outbound survived) — so the app's routing ran instead. It must now be carried through the import and
 * take precedence over the app's profile/toggles when the config is built.
 */
class EmbeddedSingBoxRouteTest {

    private val configJson = """
        {
          "dns": { "servers": [ { "tag": "google", "address": "tls://8.8.8.8" } ] },
          "inbounds": [ { "type": "tun", "tag": "tun-in" } ],
          "outbounds": [
            { "type": "vless", "tag": "vless-out", "server": "1.2.3.4", "server_port": 443,
              "uuid": "11111111-2222-3333-4444-555555555555" },
            { "type": "direct", "tag": "direct-out" },
            { "type": "block", "tag": "block-out" },
            { "type": "dns", "tag": "dns-out" }
          ],
          "route": {
            "final": "vless-out",
            "rules": [
              { "protocol": "dns", "outbound": "dns-out" },
              { "action": "sniff" },
              { "inbound": ["tun-in"], "outbound": "vless-out" },
              { "rule_set": "geosite-ru", "outbound": "direct-out" },
              { "domain_suffix": ["ads.example"], "outbound": "block-out" },
              { "domain": ["mail.ru"], "outbound": "vless-out" }
            ],
            "rule_set": [
              { "type": "remote", "tag": "geosite-ru", "format": "binary",
                "url": "https://example.invalid/geosite-ru.srs", "download_detour": "direct-out" }
            ]
          }
        }
    """.trimIndent()

    private suspend fun importedProfile() = FakeDataSource().let { source ->
        assertTrue(LocationsRepositoryImpl(source).importText(configJson, null), "import failed")
        assertNotNull(assertNotNull(source.stored).locations.single().location.proxy)
    }

    @Test
    fun importCarriesTheConfigsOwnRouteAndRemapsItsTags() = runTest {
        val route = Json.parseToJsonElement(
            assertNotNull(importedProfile().rawSingBoxRoute, "the config's route must be kept")
        ).jsonObject
        val rules = route["rules"]!!.jsonArray.map { it.jsonObject }

        // dns-out, sniff and the inbound-keyed rule can't survive into our config.
        assertEquals(3, rules.size, "expected only the three routable rules, got $rules")
        assertEquals("direct", rules[0]["outbound"]?.jsonPrimitive?.content, "direct-out must map to direct")
        assertEquals("geosite-ru", rules[0]["rule_set"]?.jsonPrimitive?.content)
        assertEquals("reject", rules[1]["action"]?.jsonPrimitive?.content, "a block outbound becomes reject")
        assertNull(rules[1]["outbound"], "a reject rule must not also name an outbound")
        assertEquals("proxy", rules[2]["outbound"]?.jsonPrimitive?.content, "the server outbound is our proxy")
        assertEquals("proxy", route["final"]?.jsonPrimitive?.content)

        val set = route["rule_set"]!!.jsonArray.single().jsonObject
        assertEquals("direct", set["download_detour"]?.jsonPrimitive?.content, "detour must be re-pointed")
    }

    @Test
    fun builtConfigUsesTheEmbeddedRouteInsteadOfTheAppProfile() = runTest {
        val profile = importedProfile()
        val json = SingBoxConfig.build(
            profile = profile,
            listenPort = 10808,
            // An app routing profile that would otherwise send RU traffic direct — the config's own
            // routing must win over it.
            routingProfile = RoutingProfile(id = "p", name = "P", directSites = listOf("domain:ru")),
            routing = RoutingRules(bypassRussia = true),
        )
        val root = Json.parseToJsonElement(json).jsonObject
        val route = root["route"]!!.jsonObject
        val rules = route["rules"]!!.jsonArray.map { it.jsonObject }

        assertEquals("proxy", route["final"]?.jsonPrimitive?.content)
        assertTrue(
            rules.any { it["domain_suffix"]?.jsonArray?.any { d -> d.jsonPrimitive.content == "ads.example" } == true },
            "the config's own reject rule must be in the built config"
        )
        // geoip-ru only ever comes from the app's bypassRussia toggle.
        assertTrue(
            rules.none { it.toString().contains("geoip-ru") },
            "the app's bypassRussia rule must NOT be emitted alongside the embedded routing"
        )
        val sets = route["rule_set"]!!.jsonArray.map { it.jsonObject }
        assertTrue(
            sets.any { it["url"]?.jsonPrimitive?.content == "https://example.invalid/geosite-ru.srs" },
            "the rule-set the embedded routing references must be declared"
        )
    }

    private class FakeDataSource(var stored: LocationBundleV4? = null) : LocationsDataSource {
        override suspend fun loadLocationBundle(): LocationBundleV4? = stored
        override suspend fun saveLocationBundle(bundle: LocationBundleV4) { stored = bundle }
        override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()
        override suspend fun loadLegacyActiveLocationId(): String? = null
    }

}
