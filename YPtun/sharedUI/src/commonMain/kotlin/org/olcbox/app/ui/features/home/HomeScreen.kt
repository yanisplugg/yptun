package org.olcbox.app.ui.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableStateListOf
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
import org.olcbox.app.data.model.AppBehaviorSettings
import org.olcbox.app.data.model.CustomGroup
import org.olcbox.app.ui.features.home.components.folderMemberKey
import org.olcbox.app.ui.components.StartButton
import org.olcbox.app.ui.i18n.LocalStrings
import org.olcbox.app.ui.features.home.components.AddConfigurationSheet
import org.olcbox.app.ui.features.home.components.HomeScreenAppBar
import org.olcbox.app.ui.features.home.components.locationSelectorContent
import org.olcbox.app.ui.features.home.components.LogsSheet
import org.olcbox.app.ui.features.home.components.RelayStatus
import org.olcbox.app.ui.features.locations.LocationViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeScreenViewModel,
    locationViewModel: LocationViewModel,
    scrollState: LazyListState,
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
    /** How many locations to probe in parallel during a ping pass (the ping-speed knob). */
    pingParallelism: Int = AppBehaviorSettings.DEFAULT_PING_PARALLELISM,
    /** A newer app release is available on GitHub → show the "update app" banner above the nav bar. */
    updateAvailable: Boolean = false,
    /** Tapping the update banner's button — opens the update offer (auto/manual). */
    onUpdateClick: () -> Unit = {},
    onUnlockExperimental: () -> Unit = {},
    collapsedGroups: Set<String> = emptySet(),
    pinnedGroups: List<String> = emptyList(),
    pingSortedGroups: Set<String> = emptySet(),
    pingSortDescendingGroups: Set<String> = emptySet(),
    pinnedCustomLocations: List<String> = emptyList(),
    customLocationsPingSorted: Boolean = false,
    customLocationsPingSortDescending: Boolean = false,
    onToggleGroupCollapsed: (String) -> Unit = {},
    onToggleGroupPinned: (String) -> Unit = {},
    onToggleGroupPingSort: (String) -> Unit = {},
    onToggleCustomLocationPinned: (String) -> Unit = {},
    onToggleCustomLocationsPingSort: () -> Unit = {},
    // Folders (user-created groups).
    customGroups: List<CustomGroup> = emptyList(),
    onCreateFolder: (name: String, memberKeys: List<String>) -> Unit = { _, _ -> },
    onRenameFolder: (id: String, name: String) -> Unit = { _, _ -> },
    onDeleteFolder: (id: String) -> Unit = {},
    onAddToFolder: (id: String, memberKeys: List<String>) -> Unit = { _, _ -> },
    onRemoveFromFolder: (memberKeys: List<String>) -> Unit = {},
    onToggleFolderPinned: (String) -> Unit = {},
    onToggleFolderCollapsed: (String) -> Unit = {}
) {
    var isLogsSheetOpen by remember { mutableStateOf(false) }
    var isAddSheetOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }
    // Folder dialogs: create / rename (a small name dialog), "choose folder" target sheet, delete confirm.
    var folderDialog by remember { mutableStateOf<FolderDialog?>(null) }
    var chooseFolderForMembers by remember { mutableStateOf<List<String>?>(null) }
    var pendingFolderDelete by remember { mutableStateOf<String?>(null) }
    val s = LocalStrings.current

    // Bulk-selection: long-press a row to enter multi-select, tick several, then delete them at once.
    val selectedIds = remember { mutableStateListOf<String>() }
    var selectionMode by remember { mutableStateOf(false) }
    fun exitSelection() { selectionMode = false; selectedIds.clear() }
    fun toggleSelected(id: String) {
        if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
        if (selectedIds.isEmpty()) selectionMode = false
    }
    fun startSelection(id: String) {
        selectionMode = true
        if (id !in selectedIds) selectedIds.add(id)
    }

    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    // True while an "Auto = fastest" pass (ping → pick → connect) is in flight, so the Auto button
    // shows a spinner and ignores re-taps instead of launching a second concurrent pass.
    var autoRunning by remember { mutableStateOf(false) }
    val pingsState = locationViewModel.pingsState
    val locations = locationViewModel.locations.toList()
    // Drop selected ids that no longer exist (e.g. after a delete) so the count stays accurate.
    selectedIds.retainAll(locations.map { it.storageId }.toSet())
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
                    s.subscriptionsUpdatedCount(updatedCount)
                } else {
                    s.subscriptionsUpdated
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
            parallelism = pingParallelism,
            performPing = { config ->
                viewModel.performPingFor(config)
            },
        )
    }

    // "Auto = fastest": proxy-ping every ready location in parallel (the real-handshake probe, the only
    // measure that reflects whether a node actually WORKS — not just that a TCP/ICMP port answers),
    // then hand the fastest-first order to the model, which connects to the first that comes up and
    // advances on failure. App-level only; nothing about the running tunnel is touched.
    fun autoConnectFastest() {
        if (autoRunning) return
        val candidates = locations.filter { it.config?.isComplete() == true }
        if (candidates.isEmpty()) {
            scope.launch { snackbarHostState.showSnackbar(s.autoConnectNoServers) }
            return
        }
        autoRunning = true
        scope.launch { snackbarHostState.showSnackbar(s.autoConnectSearching) }
        locationViewModel.refreshPings(
            targetLocationIds = candidates.map { it.storageId },
            // Crank parallelism for the auto pass so a big group is probed fast, independent of the
            // user's normal ping setting.
            parallelism = AUTO_CONNECT_PING_PARALLELISM,
            performPing = { config -> viewModel.performPingFor(config) },
            onComplete = { _, _ ->
                val pings = (locationViewModel.pingsState as? org.olcbox.app.ui.features.locations.PingsState.Success)
                    ?.pings.orEmpty()
                // Reachable nodes first, fastest → slowest; if none answered, still try them all in list order.
                val reachable = candidates
                    .map { it.storageId to pings[it.storageId] }
                    .filter { it.second != null }
                    .sortedBy { it.second }
                    .map { it.first }
                val order = reachable.ifEmpty { candidates.map { it.storageId } }
                viewModel.autoConnectInOrder(order) { connectedName ->
                    autoRunning = false
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (connectedName != null) s.autoConnectConnected(connectedName)
                            else s.autoConnectFailed
                        )
                    }
                }
            },
        )
    }

    // Home-screen widget "Auto" button → fastest-server search. Waits until at least one ready
    // location is loaded so a cold start (app launched by the tap) doesn't see an empty list.
    val autoSignalPending by org.olcbox.app.widget.WidgetAutoSignal.pending.collectAsState()
    LaunchedEffect(autoSignalPending, locations.size) {
        if (autoSignalPending && locations.any { it.config?.isComplete() == true }) {
            org.olcbox.app.widget.WidgetAutoSignal.consume()
            autoConnectFastest()
        }
    }

    fun afterDeletion(message: String) {
        viewModel.loadCurrentConfig()
        viewModel.restartVpnIfRunning()
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun executeDelete(request: PendingDelete) {
        when (request) {
            is PendingDelete.Subscription ->
                locationViewModel.deleteLocations(request.ids) { afterDeletion(s.subscriptionDeleted) }
            PendingDelete.AllSubscriptions ->
                locationViewModel.deleteAllSubscriptions { afterDeletion(s.subscriptionsDeleted) }
            PendingDelete.AllConfigs ->
                locationViewModel.deleteAllLocations { afterDeletion(s.configsDeleted) }
            PendingDelete.Unreachable ->
                locationViewModel.deleteUnreachableCustomLocations { count ->
                    if (count > 0) afterDeletion(s.unreachableDeleted(count))
                    else scope.launch { snackbarHostState.showSnackbar(s.noUnreachableFound) }
                }
            PendingDelete.Duplicates ->
                locationViewModel.deleteDuplicateLocations { count ->
                    if (count > 0) afterDeletion(s.duplicatesDeleted(count))
                    else scope.launch { snackbarHostState.showSnackbar(s.noDuplicatesFound) }
                }
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
                onDeleteUnreachable = { requestDelete(PendingDelete.Unreachable) },
                onDeleteDuplicates = { requestDelete(PendingDelete.Duplicates) },
                onDeleteAllSubscriptions = { requestDelete(PendingDelete.AllSubscriptions) },
                onDeleteAllConfigs = { requestDelete(PendingDelete.AllConfigs) }
            )
        },
        bottomBar = {
            Column {
                if (updateAvailable) {
                    UpdateAvailableBanner(onClick = onUpdateClick)
                }
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
        }
    ) { innerPadding ->
        LazyColumn(
            state = scrollState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "connection-timer") {
                ConnectionTimer(
                    isConnected = state.isVpnConnected,
                    connectedSinceEpochMs = state.connectedSinceEpochMs,
                    onSecretTap = onUnlockExperimental,
                )
            }

            item(key = "start-button") {
                // The main connect button, with a small round "Авто" satellite to its right that
                // proxy-pings every server and (re)connects to the fastest. Always available while there
                // are usable servers — tapping it while connected re-rolls onto the new fastest node.
                val canAutoConnect = locations.any { it.config?.isComplete() == true }
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
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
                    if (canAutoConnect) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .offset(x = 100.dp)
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clickable(enabled = !autoRunning) { autoConnectFastest() },
                            contentAlignment = Alignment.Center
                        ) {
                            if (autoRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text(
                                    text = s.autoButtonLabel,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }

            if (hasSubscriptions) {
                item(key = "subscriptions-refresh") {
                    SubscriptionsRefreshRow(text = s.refreshSubscriptions, onClick = { refreshSubscriptions() })
                }
            }

            if (selectionMode) {
                item(key = "selection-bar") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { exitSelection() }) {
                                Icon(Icons.Outlined.Close, contentDescription = s.cancel)
                            }
                            Text(
                                text = s.selectedCount(selectedIds.size),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f).padding(start = 4.dp)
                            )
                            IconButton(
                                onClick = {
                                    // Resolve selected rows to folder member keys (whole subscription or
                                    // single custom location), de-duplicated, then pick a target folder.
                                    val keys = selectedIds.toList()
                                        .mapNotNull { id -> locations.firstOrNull { it.storageId == id } }
                                        .map { it.folderMemberKey() }
                                        .distinct()
                                    if (keys.isNotEmpty()) chooseFolderForMembers = keys
                                    exitSelection()
                                },
                                enabled = selectedIds.isNotEmpty()
                            ) {
                                Icon(Icons.Outlined.CreateNewFolder, contentDescription = s.moveToFolder)
                            }
                            IconButton(
                                onClick = {
                                    val ids = selectedIds.toList()
                                    if (ids.isNotEmpty()) requestDelete(PendingDelete.Subscription(ids))
                                    exitSelection()
                                },
                                enabled = selectedIds.isNotEmpty()
                            ) {
                                Icon(Icons.Outlined.Delete, contentDescription = s.delete, tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            locationSelectorContent(
                selectionMode = selectionMode,
                selectedIds = selectedIds,
                onToggleSelect = { toggleSelected(it) },
                onStartSelection = { startSelection(it) },
                onRefreshClick = { targetIds ->
                    refreshHttpPings(targetIds)
                },
                onAddSubscriptionClick = {
                    isAddSheetOpen = true
                },
                hasLoaded = locationViewModel.hasLoadedLocations,
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
                },
                onSetSubscriptionAutoUpdate = { url, enabled ->
                    locationViewModel.setSubscriptionAutoUpdate(url, enabled)
                },
                collapsedGroups = collapsedGroups,
                pinnedGroups = pinnedGroups,
                pingSortedGroups = pingSortedGroups,
                pingSortDescendingGroups = pingSortDescendingGroups,
                pinnedCustomLocations = pinnedCustomLocations,
                customLocationsPingSorted = customLocationsPingSorted,
                customLocationsPingSortDescending = customLocationsPingSortDescending,
                onToggleGroupCollapsed = onToggleGroupCollapsed,
                onToggleGroupPinned = onToggleGroupPinned,
                onToggleGroupPingSort = onToggleGroupPingSort,
                onToggleCustomLocationPinned = onToggleCustomLocationPinned,
                onToggleCustomLocationsPingSort = onToggleCustomLocationsPingSort,
                customGroups = customGroups,
                onToggleFolderCollapsed = onToggleFolderCollapsed,
                onToggleFolderPinned = onToggleFolderPinned,
                onRenameFolder = { folder -> folderDialog = FolderDialog.Rename(folder.id, folder.name) },
                onDeleteFolder = { id -> pendingFolderDelete = id },
                onRequestMoveToFolder = { keys -> chooseFolderForMembers = keys }
            )

            item(key = "bottom-spacer") {
                Spacer(modifier = Modifier.height(12.dp))
            }
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
                                snackbarHostState.showSnackbar(s.importedFromClipboard)
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
                },
                onCreateGroupClick = {
                    isAddSheetOpen = false
                    folderDialog = FolderDialog.Create(emptyList())
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
                PendingDelete.Unreachable -> s.deleteUnreachableTitle to
                    s.deleteUnreachableMessage
                PendingDelete.Duplicates -> s.deleteDuplicatesTitle to
                    s.deleteDuplicatesMessage
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

        // Create / rename folder: a single text field dialog.
        folderDialog?.let { dialog ->
            var name by remember(dialog) { mutableStateOf(dialog.initialName) }
            AlertDialog(
                onDismissRequest = { folderDialog = null },
                title = { Text(if (dialog is FolderDialog.Rename) s.folderRename else s.newFolderTitle) },
                text = {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text(s.folderNameHint) }
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = name.isNotBlank(),
                        onClick = {
                            val trimmed = name.trim()
                            when (dialog) {
                                is FolderDialog.Rename -> onRenameFolder(dialog.id, trimmed)
                                is FolderDialog.Create -> onCreateFolder(trimmed, dialog.memberKeys)
                            }
                            folderDialog = null
                        }
                    ) { Text(if (dialog is FolderDialog.Rename) s.folderSave else s.folderCreate) }
                },
                dismissButton = {
                    TextButton(onClick = { folderDialog = null }) { Text(s.cancel) }
                }
            )
        }

        // "Choose a folder" for the selected items: pick an existing folder or create a new one.
        chooseFolderForMembers?.let { memberKeys ->
            AlertDialog(
                onDismissRequest = { chooseFolderForMembers = null },
                title = { Text(s.chooseFolderTitle) },
                text = {
                    Column {
                        TextButton(onClick = {
                            folderDialog = FolderDialog.Create(memberKeys)
                            chooseFolderForMembers = null
                        }) { Text(s.newFolderOption) }
                        customGroups.forEach { folder ->
                            TextButton(onClick = {
                                onAddToFolder(folder.id, memberKeys)
                                chooseFolderForMembers = null
                            }) { Text(folder.name) }
                        }
                        // Pull the items out of every folder (back to the main list).
                        if (customGroups.isNotEmpty()) {
                            TextButton(onClick = {
                                onRemoveFromFolder(memberKeys)
                                chooseFolderForMembers = null
                            }) { Text(s.removeFromFolder, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { chooseFolderForMembers = null }) { Text(s.cancel) }
                }
            )
        }

        // Delete folder confirmation.
        pendingFolderDelete?.let { id ->
            AlertDialog(
                onDismissRequest = { pendingFolderDelete = null },
                title = { Text(s.folderDeleteTitle) },
                text = { Text(s.folderDeleteMessage) },
                confirmButton = {
                    TextButton(onClick = {
                        onDeleteFolder(id)
                        pendingFolderDelete = null
                    }) { Text(s.delete, color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingFolderDelete = null }) { Text(s.cancel) }
                }
            )
        }
    }
}

private sealed interface FolderDialog {
    val initialName: String
    data class Create(val memberKeys: List<String>) : FolderDialog {
        override val initialName: String get() = ""
    }
    data class Rename(val id: String, val name: String) : FolderDialog {
        override val initialName: String get() = name
    }
}

private sealed interface PendingDelete {
    data class Subscription(val ids: List<String>) : PendingDelete
    data object AllSubscriptions : PendingDelete
    data object AllConfigs : PendingDelete
    data object Unreachable : PendingDelete
    data object Duplicates : PendingDelete
}

@Composable
private fun UpdateAvailableBanner(onClick: () -> Unit) {
    val s = org.olcbox.app.ui.i18n.LocalStrings.current
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = s.updateBannerTitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            TextButton(onClick = onClick) {
                Text(
                    text = s.updateBannerAction,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@OptIn(kotlin.time.ExperimentalTime::class)
@Composable
private fun ConnectionTimer(
    isConnected: Boolean,
    connectedSinceEpochMs: Long = 0L,
    onSecretTap: () -> Unit = {},
) {
    var elapsed by remember { mutableStateOf(0L) }
    // Derive the elapsed time from the REAL connection start (persisted in the VPN service, not a
    // UI-local clock), so closing and reopening the app no longer resets the timer to 0.
    LaunchedEffect(isConnected, connectedSinceEpochMs) {
        if (isConnected && connectedSinceEpochMs > 0L) {
            while (true) {
                val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                elapsed = ((now - connectedSinceEpochMs) / 1000L).coerceAtLeast(0L)
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

/** Probe this many locations at once during an auto-connect pass (faster than the normal ping setting). */
private const val AUTO_CONNECT_PING_PARALLELISM = 16
