package org.olcbox.app.data.datasource

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import org.olcbox.app.data.identity.DeviceIdentityProvider
import org.olcbox.app.data.model.LocationBundleV4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A cascade set for a whole subscription lands in every server, survives a refresh, and can be removed. */
class SubscriptionCascadeTest {

    private class Source(var stored: LocationBundleV4? = null) : LocationsDataSource {
        override suspend fun loadLocationBundle(): LocationBundleV4? = stored
        override suspend fun saveLocationBundle(bundle: LocationBundleV4) { stored = bundle }
        override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()
        override suspend fun loadLegacyActiveLocationId(): String? = null
    }

    private class Identity : DeviceIdentityProvider {
        override suspend fun hwid(): String = "hwid-test"
        override suspend fun appId(): String = "hwid-test"
    }

    private fun link(host: String, name: String) =
        "vless://11111111-2222-3333-4444-555555555555@$host:443?type=tcp&security=tls#$name"

    private val cascade = "vless://99999999-2222-3333-4444-555555555555@exit.test:443?type=tcp&security=tls#Exit"
    private val url = "https://example.test/sub"

    @Test
    fun cascadeIsWrittenToEveryServerSurvivesRefreshAndCanBeRemoved() = runTest {
        var body = listOf(link("a.test", "A"), link("b.test", "B")).joinToString("\n")
        val source = Source()
        val repo = LocationsRepositoryImpl(
            dataSource = source,
            httpClient = HttpClient(MockEngine { respond(body) }),
            deviceIdentityProvider = Identity()
        )
        repo.importText(url)

        assertTrue(repo.setSubscriptionCascade(url, cascade))
        assertTrue(source.stored!!.locations.all { it.proxy2?.server == "exit.test" })

        // The panel hands out a new server next time; the refresh knows nothing about the cascade.
        body += "\n" + link("c.test", "C")
        repo.refreshSubscription(url)
        val refreshed = source.stored!!.locations
        assertEquals(3, refreshed.size)
        assertTrue(refreshed.all { it.proxy2?.server == "exit.test" })
        assertEquals(cascade, refreshed.first().metadata?.subscription?.cascadeLink)

        assertTrue(repo.setSubscriptionCascade(url, ""))
        assertTrue(source.stored!!.locations.all { it.proxy2 == null })
        assertNull(source.stored!!.locations.first().metadata?.subscription?.cascadeLink)
    }

    @Test
    fun unusableLinkAndUnknownSubscriptionAreRefused() = runTest {
        val source = Source()
        val repo = LocationsRepositoryImpl(
            dataSource = source,
            httpClient = HttpClient(MockEngine { respond(link("a.test", "A")) }),
            deviceIdentityProvider = Identity()
        )
        repo.importText(url)

        assertFalse(repo.setSubscriptionCascade(url, "not a proxy link"))
        assertFalse(repo.setSubscriptionCascade("https://example.test/other", cascade))
        assertNotNull(source.stored)
        assertTrue(source.stored!!.locations.all { it.proxy2 == null })
    }

    @Test
    fun aServerIsNeverCascadedThroughItself() = runTest {
        val source = Source()
        val repo = LocationsRepositoryImpl(
            dataSource = source,
            httpClient = HttpClient(MockEngine { respond(listOf(link("a.test", "A"), link("exit.test", "Same")).joinToString("\n")) }),
            deviceIdentityProvider = Identity()
        )
        repo.importText(url)

        assertTrue(repo.setSubscriptionCascade(url, "vless://11111111-2222-3333-4444-555555555555@exit.test:443?type=tcp&security=tls#X"))
        val byName = source.stored!!.locations.associateBy { it.name }
        assertEquals("exit.test", byName.getValue("A").proxy2?.server)
        assertNull(byName.getValue("Same").proxy2)
    }
}
