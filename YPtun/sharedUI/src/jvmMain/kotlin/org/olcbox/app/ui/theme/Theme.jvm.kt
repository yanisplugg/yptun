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
    val isDarkState = remember { mutableStateOf(systemIsDark) }
    val typography = getAppTypography()

    val strings = stringsFor(LocalizationState.effective)

    CompositionLocalProvider(
        LocalThemeIsDark provides isDarkState,
        LocalStrings provides strings
    ) {
        // Mirrors Theme.android.kt; "dynamic theme" on desktop = the Windows system accent color
        // (the closest analog of Material You). When dynamic is on, custom colors are ignored,
        // matching the Android behavior where the two systems never mix.
        val systemAccent = remember { windowsAccentColor() }
        val dynamic = useDynamicColor && systemAccent != null
        val accent = if (dynamic) systemAccent else ThemeState.accent
        val textColor = if (dynamic) null else ThemeState.textColor
        val background = if (dynamic) null else ThemeState.background

        var colorScheme = OlcboxDarkColorScheme
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

/**
 * The Windows accent color (Settings → Personalization → Colors), read from the registry.
 * Null on other platforms or when unavailable. `AccentColor` is stored as ABGR.
 */
private fun windowsAccentColor(): Color? {
    if (!System.getProperty("os.name").orEmpty().lowercase().contains("win")) return null
    return runCatching {
        val process = ProcessBuilder(
            "reg", "query", "HKCU\\Software\\Microsoft\\Windows\\DWM", "/v", "AccentColor"
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        val hex = Regex("0x([0-9a-fA-F]{6,8})").find(output)?.groupValues?.get(1) ?: return null
        val abgr = hex.toLong(16)
        val b = (abgr shr 16 and 0xFF).toInt()
        val g = (abgr shr 8 and 0xFF).toInt()
        val r = (abgr and 0xFF).toInt()
        Color(red = r, green = g, blue = b)
    }.getOrNull()
}
