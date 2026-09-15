package org.olcbox.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.olcbox.app.ui.i18n.LocalStrings
import org.olcbox.app.ui.i18n.LocalizationState
import org.olcbox.app.ui.i18n.stringsFor

@Composable
actual fun AppTheme(
    useDynamicColor: Boolean,
    content: @Composable () -> Unit
) {
    val systemIsDark = isSystemInDarkTheme()
    // The white theme is an explicit user choice and outranks the OS setting: it stays light with a
    // dark iOS theme on.
    val lightMode = ThemeState.lightMode
    val isDarkState = remember(lightMode, systemIsDark) { mutableStateOf(!lightMode && systemIsDark) }
    val typography = getAppTypography()

    // Without this the whole iOS UI was stuck on the LocalStrings default (Russian) no matter what
    // the language setting said — nothing ever provided it here, unlike Theme.android/Theme.jvm.
    val strings = stringsFor(LocalizationState.effective)

    CompositionLocalProvider(
        LocalThemeIsDark provides isDarkState,
        LocalStrings provides strings
    ) {
        val isDark by isDarkState
        // Mirrors Theme.jvm.kt. iOS exposes no system accent color (there is no Material You
        // equivalent), so [useDynamicColor] has nothing to derive from and the custom swatches
        // always apply.
        val accent = ThemeState.accent
        // The background/text swatches are all dark-canvas colors, so the white theme ignores them
        // (the settings screen hides those two rows while it is on).
        val textColor = if (lightMode) null else ThemeState.textColor
        val background = if (lightMode) null else ThemeState.background

        var colorScheme = if (isDark) OlcboxDarkColorScheme else OlcboxLightColorScheme
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

        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography
        ) {
            ProvideTextStyle(MaterialTheme.typography.bodyMedium, content)
        }
    }
}
