package org.olcbox.app.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopSocksProxySettingsTest {

    @Test
    fun untouchedInstallKeepsTheStandardUnsecuredPort() {
        val n = DesktopSocksProxySettings().normalized()
        assertEquals(DesktopSocksProxySettings.unsecuredPort(), n.port)
        assertNull(n.customPort)
    }

    @Test
    fun customPortIsUsedWithoutAuthentication() {
        val n = DesktopSocksProxySettings(customPort = 9050).normalized()
        assertEquals(9050, n.port)
        assertEquals("", n.username)
    }

    @Test
    fun invalidCustomPortFallsBackToStandard() {
        val n = DesktopSocksProxySettings(customPort = 80).normalized()
        assertEquals(DesktopSocksProxySettings.unsecuredPort(), n.port)
        assertNull(n.customPort)
    }

    @Test
    fun securedModeKeepsItsOwnPortAndRemembersTheCustomOne() {
        val n = DesktopSocksProxySettings(secured = true, port = 20000, customPort = 9050).normalized()
        assertEquals(20000, n.port)
        assertEquals(9050, n.customPort)
    }
}
