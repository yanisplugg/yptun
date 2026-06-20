package org.olcbox.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily

/**
 * Font family for standalone emoji glyphs (country flags in the location list, etc.).
 *
 * Null = use the system font (Android/iOS render emoji natively). The desktop JVM actual
 * returns a bundled color-emoji font, because Windows ships no country-flag glyphs at all
 * (Segoe UI Emoji draws "NL" letters instead of 🇳🇱).
 */
@Composable
expect fun emojiFontFamily(): FontFamily?
