package org.olcbox.app.desktop

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QrImageDecoderTest {

    private fun qrImage(text: String, size: Int = 256): BufferedImage {
        val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until size) {
            for (y in 0 until size) {
                image.setRGB(x, y, if (matrix[x, y]) 0x000000 else 0xFFFFFF)
            }
        }
        return image
    }

    @Test
    fun `decodes a QR image back to its text`() {
        val link = "vless://uuid@example.test:443?security=reality#Location"
        assertEquals(link, decodeQrImage(qrImage(link)))
    }

    @Test
    fun `returns null when the image holds no QR code`() {
        assertNull(decodeQrImage(BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)))
    }
}
