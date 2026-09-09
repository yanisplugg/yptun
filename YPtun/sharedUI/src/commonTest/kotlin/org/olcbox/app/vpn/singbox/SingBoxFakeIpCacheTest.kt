package org.olcbox.app.vpn.singbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.FakeDnsSpec
import org.olcbox.app.data.model.ProxyProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fakeip table MUST survive a reconnect.
 *
 * Without `experimental.cache_file.store_fakeip` sing-box keeps the domain↔synthetic-IP map in
 * memory only, so each start hands 198.18.0.x out again in first-lookup order. An app that caches
 * DNS answers for a long time (OkHttp/JVM apps — ChatGPT, banks — unlike browsers) then dials a fake
 * IP it learned in the PREVIOUS session, sing-box maps it to a different domain, and TLS completes
 * against the wrong host: "this network uses an untrusted SSL certificate".
 */
class SingBoxFakeIpCacheTest {

    private val profile = ProxyProfile(
        type = ProxyProfile.TYPE_VLESS,
        server = "vbn.azz.su",
        serverPort = 443,
        uuid = "11111111-1111-1111-1111-111111111111",
        network = ProxyProfile.NETWORK_TCP,
        security = ProxyProfile.SECURITY_TLS,
        sni = "vbn.azz.su",
    )

    private fun build(fakeDns: FakeDnsSpec?, cachePath: String?) =
        Json.parseToJsonElement(
            SingBoxConfig.build(
                profile = profile,
                listenPort = 10808,
                listenHost = "127.0.0.1",
                socksUsername = "",
                socksPassword = "",
                fakeDnsSpec = fakeDns,
                cacheFilePath = cachePath,
            )
        ).jsonObject

    @Test
    fun fakeIpIsPersistedWhenACachePathIsGiven() {
        val root = build(FakeDnsSpec(), "/data/singbox/cache.db")
        val cache = root["experimental"]!!.jsonObject["cache_file"]!!.jsonObject
        assertEquals(true, cache["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, cache["store_fakeip"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("/data/singbox/cache.db", cache["path"]!!.jsonPrimitive.content)
        // fakeip really is on in this config (otherwise the assertions above prove nothing).
        // sing-box 1.14 dropped the top-level `dns.fakeip` block: the pool is a typed server now.
        val servers = root["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
        val fake = servers.first { it["type"]?.jsonPrimitive?.content == "fakeip" }
        assertEquals("198.18.0.0/15", fake["inet4_range"]!!.jsonPrimitive.content)
    }

    /**
     * Without FakeDNS the cache file is still enabled — it is what persists downloaded rule-sets, and
     * a rule-set that has to be re-fetched on a censored network aborts the whole core — but it must
     * not claim to store a fakeip table that this config does not have.
     */
    @Test
    fun cacheFileWithoutFakeDnsDoesNotStoreFakeIp() {
        val cache = build(fakeDns = null, cachePath = "/data/singbox/cache.db")["experimental"]!!
            .jsonObject["cache_file"]!!.jsonObject
        assertEquals(true, cache["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertNull(cache["store_fakeip"])
    }

    @Test
    fun noCacheFileWhenNoPathIsGiven() {
        assertNull(build(FakeDnsSpec(), cachePath = null)["experimental"])
    }
}
