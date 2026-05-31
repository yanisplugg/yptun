package org.olcbox.app.ui.navigation

sealed interface AppScreen {
    data object Home : AppScreen
    data class LocationSettings(val locationId: String?) : AppScreen
}