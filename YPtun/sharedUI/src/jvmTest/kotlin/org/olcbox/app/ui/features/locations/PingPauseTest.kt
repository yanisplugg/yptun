package org.olcbox.app.ui.features.locations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.olcbox.app.data.datasource.LocationsDataSource
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.importer.ShareLinkParser
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PingPauseTest {
    private class Source(var stored: LocationBundleV4?) : LocationsDataSource {
        override suspend fun loadLocationBundle(): LocationBundleV4? = stored
        override suspend fun saveLocationBundle(bundle: LocationBundleV4) { stored = bundle }
        override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()
        override suspend fun loadLegacyActiveLocationId(): String? = null
    }

    private fun entry(i: Int): LocationEntry {
        val proxy = requireNotNull(
            ShareLinkParser.parse("vless://11111111-2222-3333-4444-555555555555@h$i.example.com:443?security=tls&type=tcp#S$i")
        )
        return LocationEntry.from("loc-$i", LocationConfig(name = "S$i", engine = EngineType.Standard, proxy = proxy))
    }

    @Test
    fun pauseHoldsNewPingsAndResumeFinishesThePass() {
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        try {
            val repo = LocationsRepositoryImpl(Source(LocationBundleV4(activeLocationId = "loc-1", locations = (1..6).map(::entry))))
            val vm = LocationViewModel(repo)
            vm.loadLocations()
            // loadLocations decodes on the real IO pool: let it land.
            repeat(300) {
                main.scheduler.runCurrent()
                if (vm.locations.size == 6) return@repeat
                Thread.sleep(10)
            }
            assertEquals(6, vm.locations.size)

            val started = AtomicInteger()
            vm.refreshPings(parallelism = 1, budgetMs = 5_000, performPing = {
                started.incrementAndGet()
                delay(100)
                40L
            })
            main.scheduler.advanceTimeBy(50)      // the first ping is in flight
            vm.pausePings()
            assertTrue((vm.pingsState as PingsState.Loading).paused)

            main.scheduler.advanceTimeBy(5_000)   // the first finishes, nobody starts after it
            assertEquals(1, started.get())
            val held = vm.pingsState as PingsState.Loading
            assertEquals(5, held.pendingLocationIds.size)
            assertEquals(1, held.completed)
            assertEquals(6, held.total)

            vm.resumePings()
            main.scheduler.advanceTimeBy(5_000)
            assertEquals(6, started.get())
            assertTrue(vm.pingsState is PingsState.Success)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun cancelDropsThePassAndALaterPassIsNotSkipped() {
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        try {
            val repo = LocationsRepositoryImpl(Source(LocationBundleV4(activeLocationId = "loc-1", locations = (1..4).map(::entry))))
            val vm = LocationViewModel(repo)
            vm.loadLocations()
            repeat(300) {
                main.scheduler.runCurrent()
                if (vm.locations.size == 4) return@repeat
                Thread.sleep(10)
            }
            val started = AtomicInteger()
            val ping: suspend (LocationConfig) -> Long? = { started.incrementAndGet(); delay(100); 40L }
            vm.refreshPings(parallelism = 1, budgetMs = 5_000, performPing = ping)
            main.scheduler.advanceTimeBy(50)
            vm.pausePings()
            vm.cancelPings()
            assertTrue(vm.pingsState is PingsState.Success)

            // The restart right after a cancel must not be skipped as "already running".
            vm.refreshPings(parallelism = 1, budgetMs = 5_000, performPing = ping)
            main.scheduler.advanceTimeBy(5_000)
            assertTrue(started.get() >= 4, "restarted pass pinged everything, started=${started.get()}")
            assertTrue(vm.pingsState is PingsState.Success)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
