package org.olcbox.app

import android.app.Application
import android.content.Context
import androidx.emoji2.bundled.BundledEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat

class App : Application() {
    companion object {
        lateinit var appContext: Context
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        // Use the bundled emoji font so country-flag emojis render everywhere
        // (Compose Text picks up EmojiCompat automatically once initialized).
        EmojiCompat.init(BundledEmojiCompatConfig(this))
    }
}
