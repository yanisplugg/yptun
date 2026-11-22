package org.olcbox.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font

// Bundled Google Noto Color Emoji font (Android system emoji font) — renders the exact same Android
// emoji glyphs and country flags on Linux and Windows.
private val androidEmojiFamily: FontFamily? by lazy {
    runCatching {
        val bytes = object {}.javaClass.classLoader
            ?.getResourceAsStream("fonts/NotoColorEmoji.ttf")
            ?.use { it.readBytes() }
            ?: object {}.javaClass.classLoader
                ?.getResourceAsStream("fonts/TwemojiMozilla.ttf")
                ?.use { it.readBytes() }
            ?: return@runCatching null
        FontFamily(Font(identity = "NotoColorEmoji", data = bytes))
    }.getOrNull()
}

@Composable
actual fun emojiFontFamily(): FontFamily? = androidEmojiFamily
