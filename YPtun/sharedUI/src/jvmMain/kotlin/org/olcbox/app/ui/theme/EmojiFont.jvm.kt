package org.olcbox.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font

// Bundled Twemoji Mozilla (COLRv0 — Skia renders it in color): Windows has no flag glyphs.
private val twemojiFamily: FontFamily? by lazy {
    runCatching {
        val bytes = object {}.javaClass.classLoader
            ?.getResourceAsStream("fonts/TwemojiMozilla.ttf")
            ?.use { it.readBytes() }
            ?: return@runCatching null
        FontFamily(Font(identity = "TwemojiMozilla", data = bytes))
    }.getOrNull()
}

@Composable
actual fun emojiFontFamily(): FontFamily? = twemojiFamily
