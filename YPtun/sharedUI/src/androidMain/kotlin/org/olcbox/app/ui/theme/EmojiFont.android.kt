package org.olcbox.app.ui.theme

import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily

private var cachedAndroidEmojiFamily: FontFamily? = null
private var loadAttempted = false

@Composable
actual fun emojiFontFamily(): FontFamily? {
    val context = LocalContext.current
    return remember(context) {
        if (!loadAttempted) {
            loadAttempted = true
            cachedAndroidEmojiFamily = runCatching {
                val typeface = Typeface.createFromAsset(context.assets, "NotoColorEmojiCompat.ttf")
                FontFamily(typeface)
            }.getOrNull()
        }
        cachedAndroidEmojiFamily
    }
}

