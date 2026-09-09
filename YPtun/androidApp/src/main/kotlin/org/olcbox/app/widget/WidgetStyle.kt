package org.olcbox.app.widget

import android.content.Context
import org.olcbox.app.R

/**
 * Per-widget-instance look, chosen in [WidgetConfigActivity] and read straight back here while
 * painting. Kept in SharedPreferences (not the app's DataStore) on purpose: a widget is repainted
 * from a BroadcastReceiver, and SharedPreferences can be read synchronously there.
 */
data class WidgetStyle(
    val theme: Int = THEME_DARK,
    /** Background opacity, 0..100. */
    val opacity: Int = 100,
    val corners: Int = CORNERS_MEDIUM,
    val accent: Int = ACCENTS[0],
    val showSpeed: Boolean = true,
    val showControls: Boolean = true,
) {

    val backgroundColor: Int
        get() = when (theme) {
            THEME_LIGHT -> 0xFFF3F4F6.toInt()
            THEME_TRANSPARENT -> 0xFF000000.toInt()
            else -> 0xFF161719.toInt()
        }

    /** 0..255 for RemoteViews' setImageAlpha. */
    val backgroundAlpha: Int get() = (opacity.coerceIn(0, 100) * 255 / 100)

    val textPrimary: Int get() = if (theme == THEME_LIGHT) 0xFF16181A.toInt() else 0xFFECEDEF.toInt()
    val textSecondary: Int get() = if (theme == THEME_LIGHT) 0xFF5A5E63.toInt() else 0xFFAEB1B6.toInt()
    val iconIdle: Int get() = if (theme == THEME_LIGHT) 0xFF80858B.toInt() else 0xFF6E7176.toInt()

    val backgroundDrawable: Int
        get() = when (corners) {
            CORNERS_SMALL -> R.drawable.widget_shape_small
            CORNERS_ROUND -> R.drawable.widget_shape_round
            else -> R.drawable.widget_shape_medium
        }

    companion object {
        const val THEME_DARK = 0
        const val THEME_LIGHT = 1
        const val THEME_TRANSPARENT = 2

        const val CORNERS_SMALL = 0
        const val CORNERS_MEDIUM = 1
        const val CORNERS_ROUND = 2

        /** Accent swatches offered in the config screen; the first one is the app's own blue. */
        val ACCENTS = intArrayOf(
            0xFF3B8EF7.toInt(), // blue
            0xFF46C26B.toInt(), // green
            0xFFB05CF0.toInt(), // purple
            0xFFE0A030.toInt(), // amber
            0xFFEF5D5D.toInt(), // red
            0xFFE9EAEC.toInt(), // white
        )

        private const val PREFS = "yptun_widget_style"

        fun load(context: Context, widgetId: Int): WidgetStyle {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val default = WidgetStyle()
            return WidgetStyle(
                theme = p.getInt("$widgetId.theme", default.theme),
                opacity = p.getInt("$widgetId.opacity", default.opacity),
                corners = p.getInt("$widgetId.corners", default.corners),
                accent = p.getInt("$widgetId.accent", default.accent),
                showSpeed = p.getBoolean("$widgetId.speed", default.showSpeed),
                showControls = p.getBoolean("$widgetId.controls", default.showControls),
            )
        }

        fun save(context: Context, widgetId: Int, style: WidgetStyle) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("$widgetId.theme", style.theme)
                .putInt("$widgetId.opacity", style.opacity)
                .putInt("$widgetId.corners", style.corners)
                .putInt("$widgetId.accent", style.accent)
                .putBoolean("$widgetId.speed", style.showSpeed)
                .putBoolean("$widgetId.controls", style.showControls)
                .apply()
        }

        /** Drops a removed widget's settings so the prefs file doesn't grow forever. */
        fun forget(context: Context, widgetIds: IntArray) {
            val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            widgetIds.forEach { id ->
                listOf("theme", "opacity", "corners", "accent", "speed", "controls")
                    .forEach { key -> editor.remove("$id.$key") }
            }
            editor.apply()
        }
    }
}
