package org.olcbox.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font

// Bundled Google Noto Color Emoji font (Android system emoji font) — renders the exact same Android
// emoji glyphs and country flags on Linux and Windows.
private object EmojiFontLoader

private val androidEmojiFamily: FontFamily? by lazy {
    runCatching {
        val loaders = listOfNotNull(
            Thread.currentThread().contextClassLoader,
            EmojiFontLoader::class.java.classLoader,
            ClassLoader.getSystemClassLoader()
        )
        fun loadFontBytes(resourcePath: String): ByteArray? {
            for (cl in loaders) {
                val stream = cl.getResourceAsStream(resourcePath)
                if (stream != null) return stream.use { it.readBytes() }
            }
            return null
        }

        val bytes = loadFontBytes("fonts/NotoColorEmoji.ttf")
            ?: loadFontBytes("fonts/TwemojiMozilla.ttf")
            ?: return@runCatching null
        FontFamily(Font(identity = "NotoColorEmoji", data = bytes))
    }.getOrNull()
}

@Composable
actual fun emojiFontFamily(): FontFamily? = androidEmojiFamily

