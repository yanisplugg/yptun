package org.olcbox.app.ui.i18n

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.olcbox.app.vpn.data.KEY_ANDROID_LANGUAGE
import org.olcbox.app.vpn.data.vpnPrefDataStore
import java.util.Locale

/**
 * Loads the user's language choice into [LocalizationState] for process entry points that start
 * WITHOUT the UI — the home-screen widgets (a BroadcastReceiver can boot the process on its own) and
 * the VPN foreground service (boot / QS tile / widget). [AndroidVpnManager] only fills
 * [LocalizationState] when an Activity creates it, so without this those surfaces render in the
 * default language and silently ignore the setting.
 *
 * Cheap and once per process: the live collector in AndroidVpnManager keeps the state current
 * afterwards, so this never fights a language change made in the UI.
 */
object AppLocale {

    @Volatile
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        LocalizationState.systemLanguage = when (Locale.getDefault().language) {
            "ru" -> AppLanguage.Russian
            "fa" -> AppLanguage.Persian
            "zh" -> AppLanguage.Chinese
            else -> AppLanguage.English
        }
        runCatching {
            runBlocking {
                val id = context.applicationContext.vpnPrefDataStore.data.first()[KEY_ANDROID_LANGUAGE]
                LocalizationState.language = AppLanguage.fromId(id)
            }
        }
    }

    /** The strings the user actually picked, whatever started this process. */
    fun strings(context: Context): Strings {
        ensureLoaded(context)
        return stringsFor(LocalizationState.effective)
    }
}
