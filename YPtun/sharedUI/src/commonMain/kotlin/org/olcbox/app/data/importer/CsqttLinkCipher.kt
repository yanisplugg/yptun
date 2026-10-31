package org.olcbox.app.data.importer

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * csqtt 2.5.0 links: `csqtt://<base64url(iv12 + AES-256-GCM(connect?v=2&…) + tag16)>`, key =
 * SHA-256("csqtt-chiper-key") (rust-server/web_panel.rs `encrypt_csqtt_link`). The key is public, so
 * this only undoes the obfuscation: commonMain has no crypto library, hence AES-CTR written out by
 * hand. The GCM tag is not checked — a wrong blob decrypts to garbage that [CsqttUriParser] rejects.
 */
internal object CsqttLinkCipher {
    private val KEY = "e0570f20d1d8806d842bd3839967ff7f316168ce787d6149781808a39b56c0a8"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val SBOX = IntArray(256).also { s ->
        // Rijndael S-box from the GF(2^8) inverse, no 256-entry table (Wikipedia's generator).
        var p = 1
        var q = 1
        do {
            p = (p xor (p shl 1) xor (if (p and 0x80 != 0) 0x1B else 0)) and 0xFF
            q = q xor (q shl 1) and 0xFF; q = q xor (q shl 2) and 0xFF; q = q xor (q shl 4) and 0xFF
            if (q and 0x80 != 0) q = q xor 0x09
            fun rotl(n: Int) = ((q shl n) or (q shr (8 - n))) and 0xFF
            s[p] = q xor rotl(1) xor rotl(2) xor rotl(3) xor rotl(4) xor 0x63
        } while (p != 1)
        s[0] = 0x63
    }

    /** The `connect?v=2&…` payload of [body] (the part after `csqtt://`), or null when it isn't one. */
    @OptIn(ExperimentalEncodingApi::class)
    fun decrypt(body: String): String? {
        val clean = body.filterNot { it.isWhitespace() }.replace('+', '-').replace('/', '_').trimEnd('=')
        val blob = runCatching { Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(clean) }.getOrNull() ?: return null
        if (blob.size < 12 + 16) return null
        val rk = expandKey()
        val out = ByteArray(blob.size - 12 - 16)
        val ctr = ByteArray(16)
        var ks = ByteArray(16)
        blob.copyInto(ctr, 0, 0, 12)
        for (i in out.indices) {
            if (i % 16 == 0) {
                val n = i / 16 + 2 // GCM: block 1 is the tag mask, data starts at counter 2
                for (j in 0 until 4) ctr[15 - j] = (n shr (8 * j)).toByte()
                ks = encrypt(ctr, rk)
            }
            out[i] = (blob[12 + i].toInt() xor ks[i % 16].toInt()).toByte()
        }
        return out.decodeToString().takeIf { it.startsWith("connect?") }
    }

    private fun expandKey(): ByteArray {
        val w = ByteArray(240)
        KEY.copyInto(w)
        var rcon = 1
        for (i in 8 until 60) {
            val t = IntArray(4) { w[(i - 1) * 4 + it].toInt() and 0xFF }
            if (i % 8 == 0) {
                val r = intArrayOf(SBOX[t[1]] xor rcon, SBOX[t[2]], SBOX[t[3]], SBOX[t[0]])
                r.copyInto(t)
                rcon = ((rcon shl 1) xor (if (rcon and 0x80 != 0) 0x1B else 0)) and 0xFF
            } else if (i % 8 == 4) for (k in 0 until 4) t[k] = SBOX[t[k]]
            for (k in 0 until 4) w[i * 4 + k] = (w[(i - 8) * 4 + k].toInt() xor t[k]).toByte()
        }
        return w
    }

    private fun xtime(x: Int) = ((x shl 1) xor (if (x and 0x80 != 0) 0x1B else 0)) and 0xFF

    private fun encrypt(block: ByteArray, rk: ByteArray): ByteArray {
        var s = IntArray(16) { (block[it].toInt() xor rk[it].toInt()) and 0xFF }
        for (round in 1..14) {
            val t = IntArray(16) { SBOX[s[(it % 4) + 4 * (((it / 4) + (it % 4)) % 4)]] } // SubBytes + ShiftRows
            if (round < 14) for (c in 0 until 4) {
                val a = IntArray(4) { t[c * 4 + it] }
                for (r in 0 until 4) {
                    val b = a[(r + 1) % 4]
                    t[c * 4 + r] = xtime(a[r]) xor xtime(b) xor b xor a[(r + 2) % 4] xor a[(r + 3) % 4]
                }
            }
            s = IntArray(16) { t[it] xor (rk[round * 16 + it].toInt() and 0xFF) }
        }
        return ByteArray(16) { s[it].toByte() }
    }
}
