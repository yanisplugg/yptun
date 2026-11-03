package org.olcbox.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/*
 * One corner scale for the whole app ("soft" look): sheets, dialogs and grouped cards 28, list items and
 * single-row cards 24, text fields and blocks inside a card 18, chips 16 (so a 32dp chip is a pill).
 * Buttons keep Material's full rounding.
 */
internal val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(24.dp),
    small = RoundedCornerShape(24.dp),
    medium = RoundedCornerShape(28.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * A custom accent: the accent itself for buttons, switches and highlights, but the *containers*
 * (selected server, install card, tonal buttons, chips) only take a tint of it over the card color — a
 * bright accent painted as a whole block made the screens look like overlapping colored layers.
 */
internal fun ColorScheme.withAccent(accent: Color): ColorScheme {
    val onAccent = if (accent.luminance() > 0.5f) Color(0xFF101010) else Color(0xFFFFFFFF)
    val dark = background.luminance() < 0.5f
    val container = if (dark) lerp(surfaceContainer, accent, 0.22f) else lerp(surfaceContainer, accent, 0.18f)
    val onContainer = if (dark) lerp(accent, Color.White, 0.45f) else lerp(accent, Color.Black, 0.55f)
    return copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = container,
        onPrimaryContainer = onContainer,
        inversePrimary = accent,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = container,
        onSecondaryContainer = onContainer,
        tertiary = accent,
        onTertiary = onAccent,
        tertiaryContainer = container,
        onTertiaryContainer = onContainer
    )
}
