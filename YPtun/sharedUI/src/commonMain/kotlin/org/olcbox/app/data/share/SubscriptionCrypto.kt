package org.olcbox.app.data.share

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * An encrypted subscription line: `yptun-enc://v1/<base64url(nonce12 + ChaCha20("YPE1" + link))>`.
 *
 * The panel (app/subcrypt.py) encrypts every link when "encrypted subscription" is on. The key is
 * shared with the panel and sits in this code, so it keeps links out of the UI, screenshots and
 * clipboard — it does not stop someone who takes the app apart. Plain ChaCha20 (RFC 8439, counter 0)
 * written out by hand because commonMain has no crypto library and the key must work on every target.
 */
object SubscriptionCrypto {
    const val PREFIX = "yptun-enc://v1/"
    private const val MAGIC = "YPE1"
    private val KEY = "3ada5b1420a9a6ba2f7524a69eb8bc5fd05d58fd4a66b61229182463fb327a19"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** [text] with every encrypted line replaced by its link; [encrypted] = at least one such line was there. */
    data class Decrypted(val text: String, val encrypted: Boolean)

    fun decryptLines(text: String): Decrypted {
        var found = false
        val lines = text.lines().mapNotNull { raw ->
            val line = raw.trim()
            if (!line.startsWith(PREFIX)) return@mapNotNull raw
            found = true
            decrypt(line) // an unreadable line is dropped rather than shown as garbage
        }
        return Decrypted(if (found) lines.joinToString("\n") else text, found)
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun decrypt(line: String): String? {
        if (!line.startsWith(PREFIX)) return null
        val body = line.removePrefix(PREFIX).filterNot { it.isWhitespace() }.trimEnd('=')
        val blob = runCatching { Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(body) }.getOrNull() ?: return null
        if (blob.size < 12 + MAGIC.length) return null
        val plain = chacha20(KEY, blob.copyOfRange(0, 12), 0u, blob.copyOfRange(12, blob.size))
        if (plain.copyOfRange(0, MAGIC.length).decodeToString() != MAGIC) return null
        return plain.copyOfRange(MAGIC.length, plain.size).decodeToString()
    }

    internal fun chacha20(key: ByteArray, nonce: ByteArray, counter: UInt, data: ByteArray): ByteArray {
        val init = UIntArray(16)
        intArrayOf(0x61707865, 0x3320646e, 0x79622d32, 0x6b206574).forEachIndexed { i, v -> init[i] = v.toUInt() }
        for (i in 0 until 8) init[4 + i] = le32(key, i * 4)
        for (i in 0 until 3) init[13 + i] = le32(nonce, i * 4)
        val out = ByteArray(data.size)
        var pos = 0
        var block = 0u
        while (pos < data.size) {
            init[12] = counter + block++
            val x = init.copyOf()
            repeat(10) {
                quarter(x, 0, 4, 8, 12); quarter(x, 1, 5, 9, 13); quarter(x, 2, 6, 10, 14); quarter(x, 3, 7, 11, 15)
                quarter(x, 0, 5, 10, 15); quarter(x, 1, 6, 11, 12); quarter(x, 2, 7, 8, 13); quarter(x, 3, 4, 9, 14)
            }
            var i = 0
            while (i < 64 && pos < data.size) {
                val word = x[i / 4] + init[i / 4]
                out[pos] = (data[pos].toInt() xor (word shr (8 * (i % 4))).toInt()).toByte()
                i++; pos++
            }
        }
        return out
    }

    private fun le32(b: ByteArray, o: Int): UInt =
        (b[o].toUInt() and 0xffu) or ((b[o + 1].toUInt() and 0xffu) shl 8) or
            ((b[o + 2].toUInt() and 0xffu) shl 16) or ((b[o + 3].toUInt() and 0xffu) shl 24)

    private fun rotl(v: UInt, n: Int): UInt = (v shl n) or (v shr (32 - n))

    private fun quarter(s: UIntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 7)
    }
}
