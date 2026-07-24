package org.olcbox.app.widget

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One-shot signal raised by the home-screen widget's "Auto" button (via the `yptun://control/auto`
 * deep link) to ask the Home screen to run the fastest-server search. A consumable flag (not a
 * counter) so it fires exactly once whether the app was cold-started by the tap or already open, and
 * never re-fires on recomposition.
 */
object WidgetAutoSignal {
    private val _pending = MutableStateFlow(false)
    val pending = _pending.asStateFlow()

    fun request() {
        _pending.value = true
    }

    fun consume() {
        _pending.value = false
    }
}
