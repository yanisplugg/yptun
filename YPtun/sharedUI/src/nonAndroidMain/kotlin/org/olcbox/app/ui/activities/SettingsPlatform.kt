package org.olcbox.app.ui.activities

/** The few platform calls the shared (desktop + iOS) settings screens need. */
internal expect object SettingsPlatform {
    fun toast(message: String)
    fun openUri(uri: String)

    /** True when some app handles [scheme] (e.g. "tg"), so a deep link beats the https fallback. */
    fun schemeRegistered(scheme: String): Boolean

    /** host:port of the local Telegram-over-WARP SOCKS proxy. */
    val telegramProxyEndpoint: String

    /** Lets the user pick a text file; [onResult] gets its content, or null when cancelled/unreadable. */
    fun pickTextFile(title: String, onResult: (String?) -> Unit)

    /** Version reported by the running [core] ("" when unknown). */
    fun coreVersion(core: CoreName): String
}

internal enum class CoreName { Xray, SingBox, OlcRtc, Freeturn, Wdtt, AmneziaWg }

/** Local date + time for the settings screens (was java.text.DateFormat on the desktop). */
internal fun formatEpochMs(ms: Long): String = org.olcbox.app.util.IsoTime.formatLocalDateTime(ms)
