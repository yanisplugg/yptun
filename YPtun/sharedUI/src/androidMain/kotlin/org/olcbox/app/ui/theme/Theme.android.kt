package org.olcbox.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import org.olcbox.app.ui.i18n.LocalStrings
import org.olcbox.app.ui.i18n.LocalizationState
import org.olcbox.app.ui.i18n.stringsFor

@Composable
actual fun AppTheme(
    useDynamicColor: Boolean,
    content: @Composable () -> Unit
) {
    val systemIsDark = isSystemInDarkTheme()
    val isDarkState = remember { mutableStateOf(systemIsDark) }
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
            // Dynamic ON: pure device (Material You) theme, following system light/dark.
            useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            // Dynamic OFF: always our own dark base — the device theme must NOT influence colors.
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

        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography
        ) {
            ProvideTextStyle(MaterialTheme.typography.bodyMedium, content)
        }
    }
}
