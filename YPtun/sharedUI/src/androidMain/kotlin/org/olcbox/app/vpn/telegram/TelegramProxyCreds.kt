package org.olcbox.app.vpn.telegram

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import org.olcbox.app.vpn.data.vpnPrefDataStore
import java.security.SecureRandom

/**
 * Persistent username/password for the Telegram-over-WARP SOCKS proxy. Auto-generated once on first
 * enable and kept stable across restarts (stored in the same DataStore as the WARP config) so the
 * value the user typed into Telegram's SOCKS5 settings keeps working. Even though the listener is
 * loopback-only, requiring credentials stops any other local app from silently riding the WARP proxy.
 */
object TelegramProxyCreds {

    private val KEY_USER = stringPreferencesKey("olcbox_tg_proxy_user")
    private val KEY_PASS = stringPreferencesKey("olcbox_tg_proxy_pass")

    private const val USER_LEN = 8
    private const val PASS_LEN = 16
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    data class Credentials(val user: String, val pass: String)

    /** Returns the stored credentials, generating and persisting a fresh pair on first call. */
    suspend fun getOrCreate(context: Context): Credentials {
        peek(context)?.let { return it }
        val created = Credentials(randomToken(USER_LEN), randomToken(PASS_LEN))
        context.vpnPrefDataStore.edit {
            it[KEY_USER] = created.user
            it[KEY_PASS] = created.pass
        }
        return created
    }

    /** Returns the stored credentials, or null if none have been generated yet. */
    suspend fun peek(context: Context): Credentials? {
        val prefs = context.vpnPrefDataStore.data.first()
        val user = prefs[KEY_USER]
        val pass = prefs[KEY_PASS]
        return if (!user.isNullOrBlank() && !pass.isNullOrBlank()) Credentials(user, pass) else null
    }

    private fun randomToken(length: Int): String {
        val rnd = SecureRandom()
        return buildString(length) {
            repeat(length) { append(ALPHABET[rnd.nextInt(ALPHABET.length)]) }
        }
    }
}
