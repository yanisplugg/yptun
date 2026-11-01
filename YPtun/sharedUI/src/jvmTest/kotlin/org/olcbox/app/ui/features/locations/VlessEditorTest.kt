package org.olcbox.app.ui.features.locations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.olcbox.app.data.datasource.LocationsDataSource
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.ProxyProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The manual VLESS form builds a usable main proxy field by field. */
@OptIn(ExperimentalCoroutinesApi::class)
class VlessEditorTest {
    private class Source : LocationsDataSource {
        override suspend fun loadLocationBundle(): LocationBundleV4? = null
        override suspend fun saveLocationBundle(bundle: LocationBundleV4) {}
        override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()
        override suspend fun loadLegacyActiveLocationId(): String? = null
    }

    @Test
    fun fieldsBuildACompleteProfile() {
        Dispatchers.setMain(StandardTestDispatcher())
        try {
            val vm = LocationViewModel(LocationsRepositoryImpl(Source()))
            vm.startEditing(null)
            vm.onEngineChanged(EngineType.Standard)
            vm.editVless { it.copy(server = "example.com", serverPort = 443) }
            assertFalse(vm.editingConfig.proxy!!.isComplete()) // no uuid yet
            vm.editVless { it.copy(uuid = "11111111-2222-3333-4444-555555555555", security = ProxyProfile.SECURITY_REALITY, realityPublicKey = "k") }
            val p = vm.editingConfig.proxy!!
            assertTrue(p.isComplete())
            assertTrue(vm.editingProxyLink.startsWith("vless://11111111-2222-3333-4444-555555555555@example.com:443"))
            assertTrue(vm.editingProxyLink.contains("security=reality"))
            vm.editVless { it.copy(network = ProxyProfile.NETWORK_XHTTP) }
            assertTrue(vm.editingConfig.requiresXray())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun vlessEngineIsAStandardLocationWithAnEmptyVlessSlot() {
        Dispatchers.setMain(StandardTestDispatcher())
        try {
            val vm = LocationViewModel(LocationsRepositoryImpl(Source()))
            vm.startEditing(null)
            assertFalse(vm.isVlessEngine)
            vm.onVlessEngineSelected()
            assertTrue(vm.isVlessEngine)
            assertEquals(EngineType.Standard, vm.editingConfig.engine)
            vm.onProxyLinkChanged("vless://11111111-2222-3333-4444-555555555555@example.com:443?security=tls&type=tcp#V")
            assertTrue(vm.editingConfig.proxy!!.isComplete())
            vm.onEngineChanged(EngineType.Standard) // back to plain Standard keeps the proxy, drops the VLESS mode
            assertFalse(vm.isVlessEngine)
            assertTrue(vm.editingConfig.proxy!!.isComplete())
        } finally {
            Dispatchers.resetMain()
        }
    }
}
