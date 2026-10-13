package org.olcbox.app.ui.activities

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Alternative launcher icons. Each one is an `<activity-alias>` in the manifest (all pointing at
 * MainActivity); exactly one is enabled at a time and the launcher shows that one's icon.
 * [alias] is the manifest name relative to the `org.olcbox.app` namespace, [bg]/[fg] are the mipmap
 * layers drawn in the picker (the real launcher builds the icon from the adaptive XML).
 * The order is the order of `Strings.appIconNames`; the first entry is the default (enabled in the manifest).
 */
internal class AppIconOption(val alias: String, val bg: String?, val fg: String, val cat: Boolean)

object AppIcons {
    internal val options = listOf(
        AppIconOption("AppActivity", null, "ic_launcher_foreground", cat = true),
        AppIconOption("IconOcean", "ic_icon_ocean_bg", "ic_icon_ocean_fg", cat = true),
        AppIconOption("IconSunset", "ic_icon_sunset_bg", "ic_icon_sunset_fg", cat = true),
        AppIconOption("IconMint", "ic_icon_mint_bg", "ic_icon_mint_fg", cat = true),
        AppIconOption("IconViolet", "ic_icon_violet_bg", "ic_icon_violet_fg", cat = true),
        AppIconOption("IconGold", "ic_icon_gold_bg", "ic_icon_gold_fg", cat = true),
        AppIconOption("IconWsky", "ic_icon_wsky_bg", "ic_icon_wsky_fg", cat = false),
        AppIconOption("IconWneon", "ic_icon_wneon_bg", "ic_icon_wneon_fg", cat = false),
        AppIconOption("IconWsun", "ic_icon_wsun_bg", "ic_icon_wsun_fg", cat = false),
        AppIconOption("IconWlight", "ic_icon_wlight_bg", "ic_icon_wlight_fg", cat = false),
    )

    private const val NAMESPACE = "org.olcbox.app."

    private fun component(context: Context, option: AppIconOption) =
        ComponentName(context.packageName, NAMESPACE + option.alias)

    /**
     * Safety net, called on every process start: if NO alias is enabled the app has no launcher entry at
     * all (the home-screen icon opens «App info»). That happens when an alias that was switched on is
     * removed or renamed by an update and the default one is still stored as disabled; re-enable the default.
     */
    fun ensureLauncherEntry(context: Context) {
        runCatching {
            val pm = context.packageManager
            val anyEnabled = options.withIndex().any { (index, option) ->
                when (pm.getComponentEnabledSetting(component(context, option))) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> index == 0
                    else -> false
                }
            }
            if (!anyEnabled) select(context, 0)
        }
    }

    /** Index in [options] of the enabled alias (the default one while nothing was ever switched). */
    fun current(context: Context): Int {
        val pm = context.packageManager
        options.forEachIndexed { index, option ->
            when (pm.getComponentEnabledSetting(component(context, option))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> return index
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> if (index == 0) return index
            }
        }
        return 0
    }

    /** Enables [index] FIRST and only then disables the rest, so the app never has no launcher entry. */
    fun select(context: Context, index: Int) {
        val pm = context.packageManager
        pm.setComponentEnabledSetting(
            component(context, options[index]),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        options.forEachIndexed { i, option ->
            if (i != index) pm.setComponentEnabledSetting(
                component(context, option),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }
}
