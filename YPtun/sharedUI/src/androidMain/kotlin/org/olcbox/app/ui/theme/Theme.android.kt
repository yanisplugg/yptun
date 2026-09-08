package org.olcbox.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import org.olcbox.app.ui.i18n.LocalStrings
import org.olcbox.app.ui.i18n.LocalizationState
import org.olcbox.app.ui.i18n.stringsFor

@Composable
actual fun AppTheme(
    useDynamicColor: Boolean,
    content: @Composable () -> Unit
) {
    val systemIsDark = isSystemInDarkTheme()
    // The white theme is an explicit user choice, so it outranks the system setting: with it on the
    // UI is light even when the device (and the Material You palette) is dark.
    val lightMode = ThemeState.lightMode
    val resolvedIsDark = !lightMode && systemIsDark
    val isDarkState = remember(resolvedIsDark) { mutableStateOf(resolvedIsDark) }
    val typography = getAppTypography()

    val strings = stringsFor(LocalizationState.effective)

    CompositionLocalProvider(
        LocalThemeIsDark provides isDarkState,
        LocalStrings provides strings
    ) {
        val isDark by isDarkState
        // Read custom overrides so theme recomposes when the user changes them.
        val accent = ThemeState.accent
        val textColor = ThemeState.textColor
        val background = ThemeState.background

        val baseScheme = when {
            // Dynamic ON: pure device (Material You) theme, following system light/dark — unless the
            // white theme is on, which pins it to the device's LIGHT palette.
            useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            // Dynamic OFF: always our own base — the device theme must NOT influence colors.
            lightMode -> OlcboxLightColorScheme

            else -> OlcboxDarkColorScheme
        }

        // Custom colors apply ONLY when dynamic theme is off. With dynamic on, use the pure
        // device (Material You) scheme so the two systems never mix.
        var colorScheme = baseScheme
        if (!useDynamicColor) {
            if (accent != null) {
                val onAccent = if (accent.luminance() > 0.5f) Color(0xFF101010) else Color(0xFFFFFFFF)
                colorScheme = colorScheme.copy(
                    primary = accent,
                    onPrimary = onAccent,
                    primaryContainer = accent,
                    onPrimaryContainer = onAccent,
                    secondary = accent,
                    onSecondary = onAccent,
                    tertiary = accent,
                    inversePrimary = accent
                )
            }
            // The background/text swatches are all dark-canvas colors; applying them over the light
            // scheme would put a black canvas (or near-white text) under light-scheme content.
            if (!lightMode) {
                if (background != null) {
                    colorScheme = colorScheme.copy(
                        background = background,
                        surface = background,
                        surfaceContainerLowest = background
                    )
                }
                if (textColor != null) {
                    colorScheme = colorScheme.copy(onBackground = textColor, onSurface = textColor)
                }
            }
        }

        // Edge-to-edge draws the status/navigation bar icons over our canvas, and enableEdgeToEdge()
        // picks their color from the SYSTEM theme — with a dark system theme and the white theme on,
        // that leaves white icons on a white bar. Drive them from the canvas we actually painted.
        val context = LocalContext.current
        val window = context.findActivity()?.window
        val lightBars = colorScheme.background.luminance() > 0.5f
        if (window != null) {
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
        }

        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography
        ) {
            ProvideTextStyle(MaterialTheme.typography.bodyMedium, content)
        }
    }
}

/** Compose hands out a ContextWrapper as often as the Activity itself; unwrap to reach the window. */
private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
