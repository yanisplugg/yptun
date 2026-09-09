package org.olcbox.app.desktop

import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.common.HybridBinarizer
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Decodes the first QR code found in an image; null when there is none (or it is unreadable).
 *
 * A PC has no camera, so the desktop equivalent of Android's «сканировать QR» is picking a
 * screenshot or photo of one. zxing-core carries no BufferedImage source (that lives in
 * zxing-javase, which we do not ship) — luma from RGB is a few lines, so we do it here.
 */
fun decodeQrImage(file: File): String? = runCatching {
    decodeQrImage(ImageIO.read(file) ?: return null)
}.getOrNull()

fun decodeQrImage(image: BufferedImage): String? = runCatching {
    val pixels = IntArray(image.width * image.height)
    image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
    val luminances = ByteArray(pixels.size) { i ->
        val p = pixels[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        ((r * 33 + g * 34 + b * 33) / 100).toByte()
    }
    val source = object : LuminanceSource(image.width, image.height) {
        override fun getRow(y: Int, row: ByteArray?): ByteArray {
            val out = if (row != null && row.size >= width) row else ByteArray(width)
            System.arraycopy(luminances, y * width, out, 0, width)
            return out
        }

        override fun getMatrix(): ByteArray = luminances
    }
    MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
}.getOrNull()
