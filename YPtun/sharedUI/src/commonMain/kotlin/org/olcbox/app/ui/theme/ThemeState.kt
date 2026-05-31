package org.olcbox.app.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Global, user-customizable theme overrides. [AppTheme] reads these, so changing them
 * recomposes the whole UI. Values are loaded from prefs at startup and updated from settings.
 *
 * - [accent] = seed color; a full Material scheme is generated from it (null = default pink).
 * - [textColor] = override for primary text (onBackground/onSurface); null = scheme default.
 */
object ThemeState {
    /** Element/accent color (buttons, highlights). */
    var accent by mutableStateOf<Color?>(null)
    /** Primary text/font color. */
    var textColor by mutableStateOf<Color?>(null)
    /** Base theme background color. */
    var background by mutableStateOf<Color?>(null)

    /** Theme (background) swatches; first = default (black). */
    val backgroundPresets: List<Color> = listOf(
        Color(0xFF000000), // black (default)
        Color(0xFF0B1020), // midnight blue
        Color(0xFF0E1410), // dark green
        Color(0xFF140E16), // dark purple
        Color(0xFF161214), // dark mauve
        Color(0xFF101214), // graphite
    )

    /** Curated accent swatches for the picker (ARGB). First = default (pink). */
    val accentPresets: List<Color> = listOf(
        Color(0xFF3B8EF7), // blue (default)
        Color(0xFF4DD0E1), // cyan
        Color(0xFF7CF2C0), // mint
        Color(0xFFB388FF), // lavender
        Color(0xFFF7AFCD), // pink
        Color(0xFFFF6FA5), // hot pink
        Color(0xFFFFD479), // amber
        Color(0xFFFF8A80), // coral
    )

    /** Curated text-color swatches. First = default (scheme onSurface). */
    val textPresets: List<Color?> = listOf(
        null,              // default
        Color(0xFFFFFFFF), // white
        Color(0xFFF3DEE6), // pink-white
        Color(0xFFFFD9E7), // light pink
        Color(0xFFB8C0CC), // muted
    )
}
