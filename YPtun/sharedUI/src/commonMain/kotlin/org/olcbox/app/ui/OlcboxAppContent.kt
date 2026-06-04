package org.olcbox.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.ui.features.home.HomeScreen
import org.olcbox.app.ui.features.home.HomeScreenViewModel
import org.olcbox.app.ui.features.locations.LocationSettingsScreen
import org.olcbox.app.ui.features.locations.LocationViewModel
import org.olcbox.app.ui.navigation.AppScreen

@Composable
fun OlcboxAppContent(
    homeViewModel: HomeScreenViewModel,
    locationViewModel: LocationViewModel,
    currentScreen: AppScreen,
    onNavigate: (AppScreen) -> Unit,
    onToggleClick: () -> Unit,
    onImportFileRequested: () -> Unit,
    onImportFromClipboardRequested: (onImported: () -> Unit, onError: (String) -> Unit) -> Unit,
    onScanQrRequested: () -> Unit = {},
    onCopyConfigRequested: () -> Unit,
    onShareLocationRequested: (LocationConfig) -> Unit = {},
    onSaveLogsRequested: (onSaved: (String) -> Unit, onError: (String) -> Unit) -> Unit,
    showAppSettingsButton: Boolean,
    showSplitTunnelingButton: Boolean = false,
    canScanQr: Boolean = false,
    onAppSettingsClick: () -> Unit,
    onSplitTunnelingClick: () -> Unit = {},
    confirmBeforeDelete: Boolean = true,
    onUnlockExperimental: () -> Unit = {},
    collapsedGroups: Set<String> = emptySet(),
    pinnedGroups: List<String> = emptyList(),
    pingSortedGroups: Set<String> = emptySet(),
    pingSortDescendingGroups: Set<String> = emptySet(),
    onToggleGroupCollapsed: (String) -> Unit = {},
    onToggleGroupPinned: (String) -> Unit = {},
    onToggleGroupPingSort: (String) -> Unit = {}
) {
    val homeScrollState = rememberScrollState()

    AnimatedContent(
        targetState = currentScreen,
        label = "app_screen_transition",
        transitionSpec = {
            ContentTransform(
                targetContentEnter = fadeIn(
                    animationSpec = tween(
                        durationMillis = 240,
                        delayMillis = 30,
                        easing = LinearOutSlowInEasing
                    )
                ),
                initialContentExit = fadeOut(
                    animationSpec = tween(
                        durationMillis = 160,
                        easing = LinearOutSlowInEasing
                    )
                ),
                sizeTransform = SizeTransform(
                    clip = false,
                    sizeAnimationSpec = { _, _ ->
                        tween(
                            durationMillis = 420,
                            easing = FastOutSlowInEasing
                        )
                    }
                )
            )
        }
    ) { screen ->
        when (screen) {
            AppScreen.Home -> {
                HomeScreen(
                    viewModel = homeViewModel,
                    locationViewModel = locationViewModel,
                    scrollState = homeScrollState,
                    onToggleClick = onToggleClick,
                    onImportFileRequested = onImportFileRequested,
                    onImportFromClipboardRequested = onImportFromClipboardRequested,
                    onScanQrRequested = onScanQrRequested,
                    onCopyConfigRequested = onCopyConfigRequested,
                    onSaveLogsRequested = onSaveLogsRequested,
                    showAppSettingsButton = showAppSettingsButton,
                    showSplitTunnelingButton = showSplitTunnelingButton,
                    canScanQr = canScanQr,
                    onAppSettingsClick = onAppSettingsClick,
                    onSplitTunnelingClick = onSplitTunnelingClick,
                    onOpenLocationSettings = { id ->
                        locationViewModel.startEditing(id)
                        onNavigate(AppScreen.LocationSettings(id))
                    },
                    onAddLocation = {
                        locationViewModel.startEditing(null)
                        onNavigate(AppScreen.LocationSettings(null))
                    },
                    confirmBeforeDelete = confirmBeforeDelete,
                    onUnlockExperimental = onUnlockExperimental,
                    collapsedGroups = collapsedGroups,
                    pinnedGroups = pinnedGroups,
                    pingSortedGroups = pingSortedGroups,
                    pingSortDescendingGroups = pingSortDescendingGroups,
                    onToggleGroupCollapsed = onToggleGroupCollapsed,
                    onToggleGroupPinned = onToggleGroupPinned,
                    onToggleGroupPingSort = onToggleGroupPingSort
                )
            }

            is AppScreen.LocationSettings -> {
                LocationSettingsScreen(
                    viewModel = locationViewModel,
                    homeViewModel = homeViewModel,
                    onShareLocationRequested = onShareLocationRequested,
                    onBack = {
                        homeViewModel.loadCurrentConfig()
                        onNavigate(AppScreen.Home)
                    }
                )
            }
        }
    }
}
