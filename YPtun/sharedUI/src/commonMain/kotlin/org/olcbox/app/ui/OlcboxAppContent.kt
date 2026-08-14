package org.olcbox.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import org.olcbox.app.data.model.AppBehaviorSettings
import org.olcbox.app.data.model.CustomGroup
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
    allowVpsAutoInstall: Boolean = false,
    pingParallelism: Int = AppBehaviorSettings.DEFAULT_PING_PARALLELISM,
    updateAvailable: Boolean = false,
    onUpdateClick: () -> Unit = {},
    onUnlockExperimental: () -> Unit = {},
    collapsedGroups: Set<String> = emptySet(),
    pinnedGroups: List<String> = emptyList(),
    pingSortedGroups: Set<String> = emptySet(),
    pingSortDescendingGroups: Set<String> = emptySet(),
    pinnedCustomLocations: List<String> = emptyList(),
    customLocationsPingSorted: Boolean = false,
    customLocationsPingSortDescending: Boolean = false,
    twoColumns: Boolean = false,
    showAutoButton: Boolean = true,
    onToggleGroupCollapsed: (String) -> Unit = {},
    onToggleGroupPinned: (String) -> Unit = {},
    onToggleGroupPingSort: (String) -> Unit = {},
    onToggleCustomLocationPinned: (String) -> Unit = {},
    onToggleCustomLocationsPingSort: () -> Unit = {},
    customGroups: List<CustomGroup> = emptyList(),
    onCreateFolder: (name: String, memberKeys: List<String>) -> Unit = { _, _ -> },
    onRenameFolder: (id: String, name: String) -> Unit = { _, _ -> },
    onDeleteFolder: (id: String) -> Unit = {},
    onAddToFolder: (id: String, memberKeys: List<String>) -> Unit = { _, _ -> },
    onRemoveFromFolder: (memberKeys: List<String>) -> Unit = {},
    onToggleFolderPinned: (String) -> Unit = {},
    onToggleFolderCollapsed: (String) -> Unit = {},
    // Desktop wide-window layout (locations list in a left pane) + the desktop mode switch slot.
    wideLayout: Boolean = false,
    extraConnectContent: (@Composable () -> Unit)? = null
) {
    val homeScrollState = rememberLazyListState()

    // A crossfade only looks like a crossfade if BOTH halves are on screen at the same time and
    // something opaque is behind them. The old spec faded the outgoing screen out over 160 ms while
    // the incoming one did not even start for 30 ms, so for a moment neither was opaque — on desktop
    // that is a bare (white) window showing through, the "белое на секунду" when entering any menu.
    // Callers additionally paint the app background behind this (Compose Desktop windows have none).
    AnimatedContent(
        targetState = currentScreen,
        label = "app_screen_transition",
        transitionSpec = {
            ContentTransform(
                targetContentEnter = fadeIn(
                    animationSpec = tween(
                        durationMillis = 220,
                        easing = LinearOutSlowInEasing
                    )
                ),
                // Outlasts the incoming fade so the two overlap for the whole transition.
                initialContentExit = fadeOut(
                    animationSpec = tween(
                        durationMillis = 220,
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
                    pingParallelism = pingParallelism,
                    updateAvailable = updateAvailable,
                    onUpdateClick = onUpdateClick,
                    onUnlockExperimental = onUnlockExperimental,
                    collapsedGroups = collapsedGroups,
                    pinnedGroups = pinnedGroups,
                    pingSortedGroups = pingSortedGroups,
                    pingSortDescendingGroups = pingSortDescendingGroups,
                    pinnedCustomLocations = pinnedCustomLocations,
                    customLocationsPingSorted = customLocationsPingSorted,
                    customLocationsPingSortDescending = customLocationsPingSortDescending,
                    twoColumns = twoColumns,
                    showAutoButton = showAutoButton,
                    onToggleGroupCollapsed = onToggleGroupCollapsed,
                    onToggleGroupPinned = onToggleGroupPinned,
                    onToggleGroupPingSort = onToggleGroupPingSort,
                    onToggleCustomLocationPinned = onToggleCustomLocationPinned,
                    onToggleCustomLocationsPingSort = onToggleCustomLocationsPingSort,
                    customGroups = customGroups,
                    onCreateFolder = onCreateFolder,
                    onRenameFolder = onRenameFolder,
                    onDeleteFolder = onDeleteFolder,
                    onAddToFolder = onAddToFolder,
                    onRemoveFromFolder = onRemoveFromFolder,
                    onToggleFolderPinned = onToggleFolderPinned,
                    onToggleFolderCollapsed = onToggleFolderCollapsed,
                    wideLayout = wideLayout,
                    extraConnectContent = extraConnectContent
                )
            }

            is AppScreen.LocationSettings -> {
                LocationSettingsScreen(
                    viewModel = locationViewModel,
                    homeViewModel = homeViewModel,
                    allowVpsAutoInstall = allowVpsAutoInstall,
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
