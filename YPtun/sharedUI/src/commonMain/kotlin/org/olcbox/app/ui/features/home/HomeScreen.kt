package org.olcbox.app.ui.features.home

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.TimeSource
import org.olcbox.app.ui.components.StartButton
import org.olcbox.app.ui.i18n.LocalStrings
import org.olcbox.app.ui.features.home.components.AddConfigurationSheet
import org.olcbox.app.ui.features.home.components.HomeScreenAppBar
import org.olcbox.app.ui.features.home.components.LocationSelectorScreen
import org.olcbox.app.ui.features.home.components.LogsSheet
import org.olcbox.app.ui.features.home.components.RelayStatus
import org.olcbox.app.ui.features.locations.LocationViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeScreenViewModel,
    locationViewModel: LocationViewModel,
    scrollState: ScrollState,
    onToggleClick: () -> Unit = { viewModel.ToggleVpn() },
    onImportFileRequested: () -> Unit = {},
    onImportFromClipboardRequested: (onImported: () -> Unit, onError: (String) -> Unit) -> Unit = { _, _ -> },
    onScanQrRequested: () -> Unit = {},
    onCopyConfigRequested: () -> Unit = { viewModel.onCopyFullConfigClicked() },
    onSaveLogsRequested: (onSaved: (String) -> Unit, onError: (String) -> Unit) -> Unit = { _, _ -> },
    showAppSettingsButton: Boolean = false,
    canScanQr: Boolean = false,
    onAppSettingsClick: () -> Unit = {},
    showSplitTunnelingButton: Boolean = false,
    onSplitTunnelingClick: () -> Unit = {},
    onOpenLocationSettings: (String?) -> Unit,
    onAddLocation: () -> Unit,
    confirmBeforeDelete: Boolean = true,
    onUnlockExperimental: () -> Unit = {}
) {
    var isLogsSheetOpen by remember { mutableStateOf(false) }
    var isAddSheetOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }
    val s = LocalStrings.current

    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val pingsState = locationViewModel.pingsState
    val locations = locationViewModel.locations.toList()
    val hasSubscriptions = locations.any { !it.subscriptionUrl.isNullOrBlank() }

    val requiresSetup = !state.canStartVpn && !state.isVpnConnected && !state.isVpnLoading

    val primaryActionLabel = when {
        requiresSetup -> s.labelSetup
        state.isVpnLoading || state.isVpnConnected -> s.labelStop
        else -> s.labelStart
    }

    fun refreshSubscriptions() {
        viewModel.refreshSubscriptions { updatedCount ->
            locationViewModel.loadLocations {
                viewModel.restartVpnIfRunning()

                val message = if (updatedCount > 0) {
                    "Подписки обновлены: $updatedCount"
                } else {
                    "Подписки обновлены"
                }

                scope.launch {
                    snackbarHostState.showSnackbar(message)
                }
            }
        }
    }

    fun refreshHttpPings(targetLocationIds: List<String>? = null) {
        locationViewModel.refreshPings(
            targetLocationIds = targetLocationIds,
            performPing = { config ->
                viewModel.performPingFor(config)
            },
        )
    }

    fun afterDeletion(message: String) {
        viewModel.loadCurrentConfig()
        viewModel.restartVpnIfRunning()
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun executeDelete(request: PendingDelete) {
        when (request) {
            is PendingDelete.Subscription ->
                locationViewModel.deleteLocations(request.ids) { afterDeletion("Подписка удалена") }
            PendingDelete.AllSubscriptions ->
                locationViewModel.deleteAllSubscriptions { afterDeletion("Подписки удалены") }
            PendingDelete.AllConfigs ->
                locationViewModel.deleteAllLocations { afterDeletion("Конфигурации удалены") }
        }
    }

    fun requestDelete(request: PendingDelete) {
        if (confirmBeforeDelete) pendingDelete = request else executeDelete(request)
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState)
        },
        topBar = {
            HomeScreenAppBar(
                onHistoryClick = { isLogsSheetOpen = true },
                showAppSettingsButton = showAppSettingsButton,
                onAppSettingsClick = onAppSettingsClick,
                showSplitTunnelingButton = showSplitTunnelingButton,
                onSplitTunnelingClick = onSplitTunnelingClick,
                onAddClick = { isAddSheetOpen = true },
                showOverflowMenu = locations.isNotEmpty(),
                onDeleteAllSubscriptions = { requestDelete(PendingDelete.AllSubscriptions) },
                onDeleteAllConfigs = { requestDelete(PendingDelete.AllConfigs) }
            )
        },
        bottomBar = {
            if (showAppSettingsButton) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    NavigationBarItem(
                        selected = true,
                        onClick = {},
                        icon = { Icon(Icons.Rounded.Bolt, contentDescription = null) },
                        label = { Text(s.navConnection) }
                    )
                    NavigationBarItem(
                        selected = false,
                        onClick = onAppSettingsClick,
                        icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                        label = { Text(s.navSettings) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ConnectionTimer(isConnected = state.isVpnConnected, onSecretTap = onUnlockExperimental)

            Spacer(modifier = Modifier.height(6.dp))

            StartButton(
                isActive = state.isVpnConnected,
                isLoading = state.isVpnLoading,
                requiresSetup = requiresSetup,
                label = primaryActionLabel,
                enabled = true,
                onClick = {
                    if (requiresSetup) {
                        isAddSheetOpen = true
                    } else {
                        onToggleClick()
                    }
                }
            )

            Spacer(modifier = Modifier.height(14.dp))

            if (hasSubscriptions) {
                SubscriptionsRefreshRow(text = s.refreshSubscriptions, onClick = { refreshSubscriptions() })
                Spacer(modifier = Modifier.height(8.dp))
            }

            LocationSelectorScreen(
                onRefreshClick = { targetIds ->
                    refreshHttpPings(targetIds)
                },
                onAddSubscriptionClick = {
                    isAddSheetOpen = true
                },
                locations = locations,
                selectedLocationId = locationViewModel.selectedLocationId,
                pingsState = pingsState,
                onLocationSelected = { id ->
                    locationViewModel.selectLocation(id) {
                        viewModel.loadCurrentConfig()
                        viewModel.restartVpnIfRunning()
                    }
                },
                onLocationSettingsClick = { id ->
                    onOpenLocationSettings(id)
                },
                onAddLocationClick = {
                    onAddLocation()
                },
                onDeleteSubscription = { ids ->
                    requestDelete(PendingDelete.Subscription(ids))
                }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        if (isLogsSheetOpen) {
            val logs by viewModel.logs.collectAsState()

            LogsSheet(
                logs = logs,
                onSaveClick = {
                    onSaveLogsRequested(
                        { message ->
                            scope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        },
                        { message ->
                            scope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        }
                    )
                },
                onShareClick = {
                    viewModel.onShareLogs(
                        onShared = { message ->
                            scope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        },
                        onError = { message ->
                            scope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        }
                    )
                },
                onDismiss = {
                    isLogsSheetOpen = false
                }
            )
        }

        if (isAddSheetOpen) {
            AddConfigurationSheet(
                canScanQr = canScanQr,
                hasSubscriptions = hasSubscriptions,
                onDismiss = {
                    isAddSheetOpen = false
                },
                onScanQrClick = {
                    isAddSheetOpen = false
                    onScanQrRequested()
                },
                onPasteLinkClick = {
                    isAddSheetOpen = false
                    onImportFromClipboardRequested(
                        {
                            scope.launch {
                                snackbarHostState.showSnackbar("Imported from clipboard")
                            }
                        },
                        { message ->
                            scope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        }
                    )
                },
                onImportFileClick = {
                    isAddSheetOpen = false
                    onImportFileRequested()
                },
                onUpdateSubscriptionsClick = {
                    isAddSheetOpen = false
                    refreshSubscriptions()
                },
                onAddCustomLocationClick = {
                    isAddSheetOpen = false
                    onAddLocation()
                }
            )
        }

        pendingDelete?.let { request ->
            val (title, message) = when (request) {
                is PendingDelete.Subscription -> s.deleteSubscriptionTitle to
                    s.deleteSubscriptionMessage(request.ids.size)
                PendingDelete.AllSubscriptions -> s.deleteAllSubscriptionsTitle to
                    s.deleteAllSubscriptionsMessage
                PendingDelete.AllConfigs -> s.deleteAllConfigsTitle to
                    s.deleteAllConfigsMessage
            }
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text(title) },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = {
                        executeDelete(request)
                        pendingDelete = null
                    }) {
                        Text(s.delete, color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text(s.cancel) }
                }
            )
        }
    }
}

private sealed interface PendingDelete {
    data class Subscription(val ids: List<String>) : PendingDelete
    data object AllSubscriptions : PendingDelete
    data object AllConfigs : PendingDelete
}

@Composable
private fun ConnectionTimer(isConnected: Boolean, onSecretTap: () -> Unit = {}) {
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(isConnected) {
        if (isConnected) {
            val mark = TimeSource.Monotonic.markNow()
            while (true) {
                elapsed = mark.elapsedNow().inWholeSeconds
                delay(1000)
            }
        } else {
            elapsed = 0L
        }
    }
    val hours = elapsed / 3600
    val minutes = (elapsed % 3600) / 60
    val seconds = elapsed % 60
    val time = "${hours.pad()}:${minutes.pad()}:${seconds.pad()}"

    var taps by remember { mutableStateOf(0) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = LocalStrings.current.connectionTime,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
        Text(
            text = time,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 32.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.sp,
            // Tap the timer 5× to unlock Experimental settings (Android dev-options style).
            modifier = Modifier.clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                taps++
                if (taps >= 5) {
                    taps = 0
                    onSecretTap()
                }
            }
        )
    }
}

private fun Long.pad(): String = this.toString().padStart(2, '0')

@Composable
private fun SubscriptionsRefreshRow(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Refresh,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
    }
}
