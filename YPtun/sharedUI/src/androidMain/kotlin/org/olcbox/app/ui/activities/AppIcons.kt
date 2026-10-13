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

internal object AppIcons {
    val options = listOf(
        AppIconOption("AppActivity", null, "ic_launcher_foreground", cat = true),
        AppIconOption("IconNeon", "ic_icon_neon_bg", "ic_icon_neon_fg", cat = true),
        AppIconOption("IconSunset", "ic_icon_sunset_bg", "ic_icon_sunset_fg", cat = true),
        AppIconOption("IconKawaii", "ic_icon_kawaii_bg", "ic_icon_kawaii_fg", cat = true),
        AppIconOption("IconPixel", "ic_icon_pixel_bg", "ic_icon_pixel_fg", cat = true),
        AppIconOption("IconSpace", "ic_icon_space_bg", "ic_icon_space_fg", cat = true),
        AppIconOption("IconShield", "ic_icon_shield_bg", "ic_icon_shield_fg", cat = false),
        AppIconOption("IconTurtle", "ic_icon_turtle_bg", "ic_icon_turtle_fg", cat = false),
        AppIconOption("IconWord", "ic_icon_word_bg", "ic_icon_word_fg", cat = false),
        AppIconOption("IconTunnel", "ic_icon_tunnel_bg", "ic_icon_tunnel_fg", cat = false),
    )

    private const val NAMESPACE = "org.olcbox.app."

    private fun component(context: Context, option: AppIconOption) =
        ComponentName(context.packageName, NAMESPACE + option.alias)

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
