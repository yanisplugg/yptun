package org.olcbox.app.ui.features.home.components

import kotlin.test.Test
import kotlin.test.assertEquals
import org.olcbox.app.ui.i18n.RuStrings

class AddConfigurationDetectionTest {
    @Test
    fun distinguishesXraySingBoxAmneziaWgAndOpenVpnInTheMainPasteField() {
        val xray = """[{"outbounds":[{"tag":"proxy","protocol":"vless"}]}]"""
        val singBox = """{"outbounds":[{"tag":"proxy","type":"vless","server":"example.com","server_port":443}]}"""
        val amneziaWg = """
            [Interface]
            PrivateKey = private
            Address = 10.8.0.2/32
            Jc = 4
            Jmin = 40
            Jmax = 70
            [Peer]
            PublicKey = public
            Endpoint = 203.0.113.7:51820
        """.trimIndent()
        val openVpn = """
            client
            remote vpn.example.com 1194
            <ca>certificate</ca>
        """.trimIndent()

        assertEquals(RuStrings.addDetectedConfig("Xray JSON"), describeImportText(xray, RuStrings))
        assertEquals(RuStrings.addDetectedConfig("sing-box JSON"), describeImportText(singBox, RuStrings))
        assertEquals(RuStrings.addDetectedConfig("AmneziaWG"), describeImportText(amneziaWg, RuStrings))
        assertEquals(RuStrings.addDetectedConfig("OpenVPN"), describeImportText(openVpn, RuStrings))
    }

    @Test
    fun keepsUnknownJsonGeneric() {
        assertEquals(RuStrings.addDetectedJson, describeImportText("""{"version":5,"locations":[]}""", RuStrings))
    }
}
