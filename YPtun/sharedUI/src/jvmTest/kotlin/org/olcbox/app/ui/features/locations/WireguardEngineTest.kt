package org.olcbox.app.ui.features.locations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.olcbox.app.data.datasource.LocationsDataSource
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.importer.AwgIniEditor
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.ProxyProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The AmneziaWG/WG engine of the location editor: a Standard location whose main proxy is a wg-quick INI. */
@OptIn(ExperimentalCoroutinesApi::class)
class WireguardEngineTest {
    private class Source : LocationsDataSource {
        var stored: LocationBundleV4? = null
        override suspend fun loadLocationBundle(): LocationBundleV4? = stored
        override suspend fun saveLocationBundle(bundle: LocationBundleV4) { stored = bundle }
        override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()
        override suspend fun loadLegacyActiveLocationId(): String? = null
    }

    private val config = """
        [Interface]
        PrivateKey = cHJpdmF0ZV9rZXlfZm9yX3Rlc3RzXzEyMzQ1Njc4OTA=
        Address = 10.8.0.2/32
        DNS = 1.1.1.1
        Jc = 6
        Jmin = 50
        Jmax = 90
        S1 = 11
        S2 = 22
        H1 = 111
        H2 = 222
        H3 = 333
        H4 = 444

        [Peer]
        PublicKey = cHVibGljX2tleV9mb3JfdGVzdHNfMTIzNDU2Nzg5MDE=
        Endpoint = 203.0.113.7:51820
        AllowedIPs = 0.0.0.0/0
        PersistentKeepalive = 25
    """.trimIndent()

    private fun withViewModel(block: (LocationViewModel) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher())
        try {
            val vm = LocationViewModel(LocationsRepositoryImpl(Source()))
            vm.startEditing(null)
            block(vm)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun pickingTheEngineGivesAnEmptyAmneziaWgTemplateThatIsNotSaveableYet() = withViewModel { vm ->
        vm.onNameChanged("tunnel")
        vm.onWireguardEngineSelected()

        assertTrue(vm.isWireguardEngine)
        assertEquals(EngineType.Standard, vm.editingConfig.engine)
        assertEquals(ProxyProfile.TYPE_AMNEZIAWG, vm.editingConfig.proxy?.type)
        assertTrue(AwgIniEditor.isAwg(vm.editingConfig.proxy!!.awgConfig))
        assertEquals(listOf("PrivateKey", "PublicKey", "Endpoint"), vm.wireguardMissing)
        assertFalse(vm.isFormValid)
    }

    @Test
    fun aPastedConfigMakesTheFormValidAndFeedsTheListEntry() = withViewModel { vm ->
        vm.onNameChanged("tunnel")
        vm.onWireguardEngineSelected()
        vm.onWireguardIniChanged(config)

        assertTrue(vm.wireguardMissing.isEmpty())
        assertTrue(vm.isFormValid)
        val proxy = vm.editingConfig.proxy!!
        assertEquals("203.0.113.7", proxy.server)
        assertEquals(51820, proxy.serverPort)
        assertTrue(proxy.isComplete())
    }

    @Test
    fun keysAndEveryParameterCanBeEditedAndOnlyTheirOwnLineChanges() = withViewModel { vm ->
        vm.onWireguardEngineSelected()
        vm.onWireguardIniChanged(config)
        fun knob(key: String) = (AwgIniEditor.CONNECTION + AwgIniEditor.KNOBS).first { it.key == key }

        vm.onAwgKnobChanged(knob("PrivateKey"), "TmV3UHJpdmF0ZUtleQ==")
        vm.onAwgKnobChanged(knob("Endpoint"), "198.51.100.9:443")
        vm.onAwgKnobChanged(knob("Jc"), "9")

        val ini = vm.editingConfig.proxy!!.awgConfig
        assertEquals("TmV3UHJpdmF0ZUtleQ==", AwgIniEditor.get(ini, "PrivateKey"))
        assertEquals("9", AwgIniEditor.get(ini, "Jc"))
        assertEquals("444", AwgIniEditor.get(ini, "H4"))
        assertEquals(config.replace("cHJpdmF0ZV9rZXlfZm9yX3Rlc3RzXzEyMzQ1Njc4OTA=", "TmV3UHJpdmF0ZUtleQ==")
            .replace("203.0.113.7:51820", "198.51.100.9:443").replace("Jc = 6", "Jc = 9"), ini)
        // The list entry follows the Endpoint.
        assertEquals("198.51.100.9", vm.editingConfig.proxy!!.server)
        assertEquals(443, vm.editingConfig.proxy!!.serverPort)
    }

    @Test
    fun switchingToPlainWireGuardDropsObfuscationAndSwitchingBackRestoresIt() = withViewModel { vm ->
        vm.onWireguardEngineSelected()
        vm.onWireguardIniChanged(config)

        vm.onWireguardProtocolChanged(awg = false)
        val plain = vm.editingConfig.proxy!!.awgConfig
        assertFalse(AwgIniEditor.isAwg(plain))
        assertTrue(vm.isWireguardEngine)
        assertEquals("203.0.113.7:51820", AwgIniEditor.get(plain, "Endpoint", AwgIniEditor.Section.Peer))

        vm.onWireguardProtocolChanged(awg = true)
        val back = vm.editingConfig.proxy!!.awgConfig
        assertEquals("6", AwgIniEditor.get(back, "Jc"))
        assertEquals("444", AwgIniEditor.get(back, "H4"))
    }

    @Test
    fun leavingTheEngineGivesBackTheProxyItReplaced() = withViewModel { vm ->
        vm.onEngineChanged(EngineType.Standard)
        vm.onProxyLinkChanged("vless://11111111-2222-3333-4444-555555555555@example.com:443?security=tls&type=tcp#Plain")
        val vless = vm.editingConfig.proxy
        assertEquals(ProxyProfile.TYPE_VLESS, vless?.type)

        vm.onWireguardEngineSelected()
        assertTrue(vm.isWireguardEngine)
        vm.onEngineChanged(EngineType.Standard)

        assertFalse(vm.isWireguardEngine)
        assertEquals(vless, vm.editingConfig.proxy)

        // A template that replaced nothing leaves nothing behind.
        vm.startEditing(null)
        vm.onWireguardEngineSelected()
        vm.onEngineChanged(EngineType.Standard)
        assertNull(vm.editingConfig.proxy)
    }
}
