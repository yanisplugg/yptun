package org.olcbox.app.ui.features.locations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.olcbox.app.data.datasource.LocationsDataSource
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.importer.ShareLinkParser
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.ProxyProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The manual SSTP and Naïve forms build usable main proxies field by field. */
@OptIn(ExperimentalCoroutinesApi::class)
class SstpEditorTest {
    private class Source : LocationsDataSource {
        override suspend fun loadLocationBundle(): LocationBundleV4? = null
        override suspend fun saveLocationBundle(bundle: LocationBundleV4) {}
        override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()
        override suspend fun loadLegacyActiveLocationId(): String? = null
    }

    @Test
    fun fieldsBuildACompleteSstpProfile() {
        Dispatchers.setMain(StandardTestDispatcher())
        try {
            val vm = LocationViewModel(LocationsRepositoryImpl(Source()))
            vm.startEditing(null)
            vm.onSstpEngineSelected()
            assertTrue(vm.isSstpEngine)
            assertEquals(EngineType.Standard, vm.editingConfig.engine)

            vm.editSstp { it.copy(server = "sstp.example.com", serverPort = 443) }
            val p1 = vm.editingConfig.proxy
            assertNotNull(p1)
            assertTrue(p1.isComplete())
            assertEquals(ProxyProfile.TYPE_SSTP, p1.type)

            vm.editSstp {
                it.copy(
                    username = "vpnuser",
                    password = "vpnpassword",
                    allowInsecure = true,
                    sni = "sstp-sni.example.com"
                )
            }
            val p2 = vm.editingConfig.proxy
            assertNotNull(p2)
            assertTrue(p2.isComplete())
            assertEquals("vpnuser", p2.username)
            assertEquals("vpnpassword", p2.password)
            assertTrue(p2.allowInsecure)
            assertEquals("sstp-sni.example.com", p2.sni)

            val link = vm.editingProxyLink
            assertTrue(link.startsWith("sstp://vpnuser:vpnpassword@sstp.example.com"))
            assertTrue(link.contains("insecure=1"))
            assertTrue(link.contains("sni=sstp-sni.example.com"))

            val parsed = ShareLinkParser.parse(link)
            assertNotNull(parsed)
            assertEquals(ProxyProfile.TYPE_SSTP, parsed.type)
            assertEquals("sstp.example.com", parsed.server)
            assertEquals(443, parsed.serverPort)
            assertEquals("vpnuser", parsed.username)
            assertEquals("vpnpassword", parsed.password)
            assertTrue(parsed.allowInsecure)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun sstpEngineSelectionSwitchesCleanly() {
        Dispatchers.setMain(StandardTestDispatcher())
        try {
            val vm = LocationViewModel(LocationsRepositoryImpl(Source()))
            vm.startEditing(null)
            assertFalse(vm.isSstpEngine)
            assertFalse(vm.isVlessEngine)

            vm.onSstpEngineSelected()
            assertTrue(vm.isSstpEngine)
            assertFalse(vm.isVlessEngine)
            assertEquals(EngineType.Standard, vm.editingConfig.engine)

            vm.onVlessEngineSelected()
            assertFalse(vm.isSstpEngine)
            assertTrue(vm.isVlessEngine)

            vm.onSstpEngineSelected()
            assertTrue(vm.isSstpEngine)
            assertFalse(vm.isVlessEngine)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun fieldsBuildACompleteNaiveProfile() {
        Dispatchers.setMain(StandardTestDispatcher())
        try {
            val vm = LocationViewModel(LocationsRepositoryImpl(Source()))
            vm.startEditing(null)
            vm.onNaiveEngineSelected()
            assertTrue(vm.isNaiveEngine)
            assertEquals(EngineType.Standard, vm.editingConfig.engine)

            vm.editNaive {
                it.copy(
                    server = "naive.example.com",
                    serverPort = 8443,
                    username = "user1",
                    password = "pass1",
                    naiveQuic = true
                )
            }
            val p = vm.editingConfig.proxy
            assertNotNull(p)
            assertTrue(p.isComplete())
            assertEquals(ProxyProfile.TYPE_NAIVE, p.type)
            assertTrue(p.naiveQuic)

            val link = vm.editingProxyLink
            assertTrue(link.startsWith("naive+quic://user1:pass1@naive.example.com:8443"))

            val parsed = ShareLinkParser.parse(link)
            assertNotNull(parsed)
            assertEquals(ProxyProfile.TYPE_NAIVE, parsed.type)
            assertEquals("naive.example.com", parsed.server)
            assertEquals(8443, parsed.serverPort)
            assertEquals("user1", parsed.username)
            assertEquals("pass1", parsed.password)
            assertTrue(parsed.naiveQuic)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
