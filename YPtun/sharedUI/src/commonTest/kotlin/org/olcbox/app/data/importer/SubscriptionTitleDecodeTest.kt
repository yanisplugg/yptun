package org.olcbox.app.data.importer

import kotlin.test.Test
import kotlin.test.assertEquals

class SubscriptionTitleDecodeTest {

    @Test
    fun plainLatinNamesThatHappenToBeValidBase64StayAsIs() {
        // Each of these used to decode into garbage: "Z.w_y", "R[kk^6", "RƁjȜ", "rZ.v˧".
        for (name in listOf("Cloud195", "Ultra142", "UsaBasic", "cloudsun", "Quofortpost")) {
            assertEquals(name, SubscriptionDecoder.decodeIfBase64(name))
        }
    }

    @Test
    fun prefixedBase64IsDecoded() {
        assertEquals("Моя подписка", SubscriptionDecoder.decodeIfBase64("base64:0JzQvtGPINC/0L7QtNC/0LjRgdC60LA="))
    }

    @Test
    fun bareBase64OfRealTextIsDecoded() {
        assertEquals("Моя подписка", SubscriptionDecoder.decodeIfBase64("0JzQvtGPINC/0L7QtNC/0LjRgdC60LA="))
        assertEquals("🇩🇪 Германия ⚡️", SubscriptionDecoder.decodeIfBase64("8J+HqfCfh6og0JPQtdGA0LzQsNC90LjRjyDimqHvuI8="))
        assertEquals("My VPN", SubscriptionDecoder.decodeIfBase64("TXkgVlBO"))
        assertEquals("Nodeless", SubscriptionDecoder.decodeIfBase64("Tm9kZWxpc3M="))
        assertEquals("GoodVpn", SubscriptionDecoder.decodeIfBase64("R29vZFZwbg=="))
        assertEquals("GoodVpn", SubscriptionDecoder.decodeIfBase64("base64:R29vZFZwbg=="))
    }
}
