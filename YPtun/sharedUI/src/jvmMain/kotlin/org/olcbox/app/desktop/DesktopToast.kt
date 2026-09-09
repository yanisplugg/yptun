package org.olcbox.app.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The desktop stand-in for Android's `Toast`: settings screens live in sharedUI and have no way back
 * to the window that renders the notice, so they post here and `main.kt` shows it. Without it every
 * "скопировано" confirmation Android gives simply did not exist on the PC — the click looked dead.
 */
object DesktopToast {
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun show(text: String) {
        _message.value = text
    }

    fun consume() {
        _message.value = null
    }
}
