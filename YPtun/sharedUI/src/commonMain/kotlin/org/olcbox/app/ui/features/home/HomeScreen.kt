package org.olcbox.app.ui.features.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.runtime.snapshotFlow
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
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.ui.features.home.components.folderMemberKey
import org.olcbox.app.ui.components.StartButton
import org.olcbox.app.ui.i18n.LocalStrings
import org.olcbox.app.ui.features.home.components.AddConfigurationSheet
import org.olcbox.app.ui.features.home.components.FreeServersSheet
import org.olcbox.app.ui.features.home.components.HomeScreenAppBar
import org.olcbox.app.ui.features.home.components.ListViewSheet
import org.olcbox.app.ui.features.home.components.PingSettingsSheet
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
    // «Создать вручную»: a new location that starts on the picked engine (AmneziaWG/WG = Standard + wireguard).
    onAddLocationOfType: (engine: EngineType, wireguard: Boolean) -> Unit = { _, _ -> onAddLocation() },
    // The «Добавить» field: import exactly this text (subscription URLs, share links, JSON).
    onImportTextRequested: (text: String, onImported: () -> Unit, onError: (String) -> Unit) -> Unit =
        { text, onImported, onError -> viewModel.onImportFullConfig(text, onImported, onError) },
    confirmBeforeDelete: Boolean = true,
    /** How many locations to probe in parallel during a ping pass (the ping-speed knob). */
    pingParallelism: Int = AppBehaviorSettings.DEFAULT_PING_PARALLELISM,
    /** Cap on the free-servers list (AppBehaviorSettings.freeServersLimit). */
    freeServersLimit: Int = AppBehaviorSettings.DEFAULT_FREE_SERVERS_LIMIT,
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
    // Render the config list as a 2-column grid (app-settings toggle).
    twoColumns: Boolean = false,
    // Show the round "Авто = fastest" satellite button next to the connect button (app-settings toggle).
    showAutoButton: Boolean = true,
    showGroupAutoPick: Boolean = true,
    onToggleGroupCollapsed: (String) -> Unit = {},
    onToggleGroupPinned: (String) -> Unit = {},
    // A subscription's group key changed with its URL: (old key, new key) — pins/folders follow it.
    onSubscriptionKeyChanged: (oldKey: String, newKey: String) -> Unit = { _, _ -> },
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
    onToggleFolderCollapsed: (String) -> Unit = {},
    // Desktop wide-window layout: the locations list moves into a LEFT pane while the connect
    // controls (timer/start button) stay on the right, like Happ/Hiddify desktop.
    wideLayout: Boolean = false,
    // Extra content under the start button (e.g. the desktop proxy/tunnel mode switch).
    extraConnectContent: (@Composable () -> Unit)? = null,
    // Desktop only: shows an exit button in the top bar.
    onExitClick: (() -> Unit)? = null,
    // The app behaviour settings, for the ⋮ menu's «Вид списка» and «Настройки пинга» sheets
    // (null hides both: the platform keeps those switches in its own settings).
    appBehavior: AppBehaviorSettings? = null,
    onAppBehaviorChanged: (AppBehaviorSettings) -> Unit = {},
    // Share a subscription (QR / link) from its sheet; null hides «Поделиться» there.
    onShareSubscriptionRequested: ((url: String) -> Unit)? = null
) {
    var isLogsSheetOpen by remember { mutableStateOf(false) }
    var isListViewSheetOpen by remember { mutableStateOf(false) }
    var isPingSettingsSheetOpen by remember { mutableStateOf(false) }
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

    // The list must not fall back to the top by itself. A background change of the data (the free servers are
    // replaced by the auto-update, a refresh) can leave the list short for an instant, and LazyColumn then resets
    // its position to 0 for good. When that happens with no finger on the list, bring the position back as soon
    // as the items are there again.
    LaunchedEffect(scrollState) {
        var stable: Pair<Int, Int>? = null   // where the user last left the list (index > 0)
        var touched = false                  // the user scrolled since [stable] was taken
        var lost: Pair<Int, Int>? = null     // position to bring back after an unexpected fall to the top
        snapshotFlow {
            listOf(
                scrollState.firstVisibleItemIndex,
                scrollState.firstVisibleItemScrollOffset,
                if (scrollState.isScrollInProgress) 1 else 0,
                scrollState.layoutInfo.totalItemsCount
            )
        }.collect { (index, offset, scrolling, total) ->
            if (scrolling == 1) {
                touched = true
                lost = null
                return@collect
            }
            val back = lost
            if (back != null) {
                if (total > back.first) {
                    lost = null
                    scrollState.scrollToItem(back.first, back.second)
                }
                return@collect
            }
            val before = stable
            if (index == 0 && offset == 0 && before != null && !touched) {
                lost = before
                if (total > before.first) {
                    lost = null
                    scrollState.scrollToItem(before.first, before.second)
                }
            } else {
                stable = if (index > 0) index to offset else null
                touched = false
            }
        }
    }

    // Free servers auto-update (the group menu's switch): checked every few minutes while the app lives.
    // Only with the window in focus: a search checks thousands of servers on several threads, and run
    // behind the screen / from the background it heats the phone (Samsung "uses a lot of resources").
    val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    LaunchedEffect(pingParallelism, freeServersLimit) {
        delay(60_000L)
        while (true) {
            if (windowInfo.isWindowFocused) viewModel.autoUpdateFreeServersIfDue(pingParallelism, freeServersLimit)
            delay(10 * 60_000L)
        }
    }

    LaunchedEffect(state.connectError) {
        val error = state.connectError
        if (!error.isNullOrBlank()) {
            snackbarHostState.showSnackbar(error)
            viewModel.clearConnectError()
        }
    }
    // True while an "Auto = fastest" pass (ping → pick → connect) is in flight, so the Auto button
    // shows a spinner and ignores re-taps instead of launching a second concurrent pass.
    var autoRunning by remember { mutableStateOf(false) }
    // The ids a per-group auto-pick is running for (null = idle, or the whole-list Auto button).
    var autoPickTarget by remember { mutableStateOf<List<String>?>(null) }
    val pingsState = locationViewModel.pingsState
    // Pause / resume / drop of the running ping pass: the group's ping button turns into these while it runs.
    val pingPaused = (pingsState as? org.olcbox.app.ui.features.locations.PingsState.Loading)?.paused == true
    val showPingControls = org.olcbox.app.ui.features.locations.components.LocalShowPingControls.current
    val pingControls = remember(pingPaused, showPingControls) {
        if (!showPingControls) null else org.olcbox.app.ui.features.locations.components.PingControls(
            paused = pingPaused,
            onPause = { locationViewModel.pausePings() },
            onResume = { locationViewModel.resumePings() },
            onCancel = { locationViewModel.cancelPings() }
        )
    }
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
                    s.subscriptionsUpdateFailed
                }

                scope.launch {
                    snackbarHostState.showSnackbar(message)
                }
            }
        }
    }

    fun refreshOneSubscription(url: String) {
        if (url.trim() == FREE_SERVERS_URL) {
            viewModel.loadFreeServers(parallelism = pingParallelism, limit = freeServersLimit, onError = { message -> scope.launch { snackbarHostState.showSnackbar(message) } })
            return
        }
        viewModel.refreshSubscription(url) { updatedCount ->
            locationViewModel.loadLocations {
                viewModel.restartVpnIfRunning()
                val message = if (updatedCount > 0) {
                    s.subscriptionsUpdatedCount(updatedCount)
                } else {
                    s.subscriptionsUpdateFailed
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
            budgetMs = viewModel.pingBudgetMs,
            performPing = { config ->
                viewModel.performPingFor(config)
            },
        )
    }

    // "Auto = fastest": proxy-ping every ready location in parallel (the real-handshake probe, the only
    // measure that reflects whether a node actually WORKS — not just that a TCP/ICMP port answers),
    // then hand the fastest-first order to the model, which connects to the first that comes up and
    // advances on failure. App-level only; nothing about the running tunnel is touched.
    fun autoConnectFastest(onlyIds: Collection<String>? = null) {
        if (autoRunning) return
        val candidates = locations.filter {
            it.config?.isComplete() == true && (onlyIds == null || it.storageId in onlyIds)
        }
        if (candidates.isEmpty()) {
            scope.launch { snackbarHostState.showSnackbar(s.autoConnectNoServers) }
            return
        }
        autoRunning = true
        autoPickTarget = onlyIds?.toList()
        scope.launch { snackbarHostState.showSnackbar(s.autoConnectSearching) }
        locationViewModel.refreshPings(
            targetLocationIds = candidates.map { it.storageId },
            // One shared knob: the auto pass uses the same "ping parallelism" slider from settings
            // as the manual ping button, so users control both speeds in one place.
            parallelism = pingParallelism,
            budgetMs = viewModel.pingBudgetMs,
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
                    autoPickTarget = null
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
                onDeleteAllConfigs = { requestDelete(PendingDelete.AllConfigs) },
                onListViewClick = if (appBehavior != null) {
                    { isListViewSheetOpen = true }
                } else null,
                onPingSettingsClick = if (appBehavior != null) {
                    { isPingSettingsSheetOpen = true }
                } else null,
                onCreateGroupClick = { folderDialog = FolderDialog.Create(emptyList()) },
                onExitClick = onExitClick
            )
        },
        bottomBar = {
            Column {
                if (updateAvailable) {
                    UpdateAvailableBanner(onClick = onUpdateClick)
                }
                // Free servers auto-update: the search runs silently, this strip (where the update banner
                // writes) shows its progress and lets it be held or dropped.
                androidx.compose.animation.AnimatedVisibility(visible = state.freeServersBackground) {
                    FreeServersAutoStrip(
                        progress = state.freeServersProgress,
                        paused = state.freeServersPaused,
                        onPause = { viewModel.pauseFreeServersSearch() },
                        onResume = { viewModel.resumeFreeServersPaused() },
                        onCancel = { viewModel.cancelFreeServersLoad() }
                    )
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
        // The connect controls (timer + start button + optional extras) and the locations list are
        // built as LazyListScope blocks so the narrow (single-column, Android-like) and wide
        // (two-pane desktop) layouts share the exact same content.
        fun androidx.compose.foundation.lazy.LazyListScope.connectItems() {
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
                val canAutoConnect = showAutoButton && locations.any { it.config?.isComplete() == true }
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
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
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

            extraConnectContent?.let { extra ->
                item(key = "extra-connect-content") { extra() }
            }
        }

        fun androidx.compose.foundation.lazy.LazyListScope.locationItems() {
            if (hasSubscriptions) {
                item(key = "subscriptions-refresh") {
                    SubscriptionsRefreshRow(text = s.refreshSubscriptions, onClick = { refreshSubscriptions() })
                }
            }

            if (selectionMode) {
                item(key = "selection-bar") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
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
                onShareSubscription = onShareSubscriptionRequested,
                selectionMode = selectionMode,
                selectedIds = selectedIds,
                onToggleSelect = { toggleSelected(it) },
                onStartSelection = { startSelection(it) },
                onRefreshClick = { targetIds ->
                    refreshHttpPings(targetIds)
                },
                onAutoPickClick = { targetIds -> autoConnectFastest(targetIds) },
                autoPickTarget = autoPickTarget,
                showGroupAutoPick = showGroupAutoPick,
                onAddSubscriptionClick = {
                    isAddSheetOpen = true
                },
                hasLoaded = locationViewModel.hasLoadedLocations,
                twoColumns = twoColumns,
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
                onRenameSubscription = { url, name -> locationViewModel.renameSubscription(url, name) },
                onToggleFreeServerPinned = { id, pinned -> locationViewModel.setLocationPinned(id, pinned) },
                freeSearchRemaining = state.freeServersResumable,
                onContinueFreeSearch = {
                    viewModel.loadFreeServers(
                        parallelism = pingParallelism,
                        limit = freeServersLimit,
                        resume = true,
                        onError = { message -> scope.launch { snackbarHostState.showSnackbar(message) } }
                    )
                },
                onChangeSubscriptionUrl = { oldUrl, newUrl, oldKey ->
                    locationViewModel.changeSubscriptionUrl(oldUrl, newUrl) { changed ->
                        if (changed) {
                            // The group key is `name|url`: it ends with the URL, so only that tail moves.
                            val newKey = oldKey.removeSuffix(oldUrl.trim()) + newUrl.trim()
                            onSubscriptionKeyChanged(oldKey, newKey)
                            refreshOneSubscription(newUrl.trim())
                        } else {
                            scope.launch { snackbarHostState.showSnackbar(s.subscriptionLinkRejected) }
                        }
                    }
                },
                onSetSubscriptionCascade = { url, link ->
                    locationViewModel.setSubscriptionCascade(url, link) { ok ->
                        if (ok) viewModel.restartVpnIfRunning()
                        else scope.launch { snackbarHostState.showSnackbar(s.subscriptionCascadeRejected) }
                    }
                },
                onSetSubscriptionAutoUpdate = { url, enabled ->
                    locationViewModel.setSubscriptionAutoUpdate(url, enabled)
                },
                onRefreshSubscription = { url ->
                    refreshOneSubscription(url)
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

        androidx.compose.runtime.CompositionLocalProvider(
            org.olcbox.app.ui.features.locations.components.LocalPingControls provides pingControls
        ) {
        if (wideLayout) {
            // Desktop: the connect controls sit centred in a left pane with a status line under the
            // button, the server list fills the right pane (capped so rows don't stretch on 4K).
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .weight(0.85f)
                        .fillMaxHeight()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
                ) {
                    connectItems()
                    item(key = "connection-status") {
                        Text(
                            text = when {
                                state.isVpnConnected -> s.notifConnected
                                state.isVpnLoading -> s.notifConnecting
                                else -> s.widgetDisconnected
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.isVpnConnected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Box(
                    modifier = Modifier.weight(1.15f).fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    LazyColumn(
                        state = scrollState,
                        modifier = Modifier
                            .widthIn(max = 760.dp)
                            .fillMaxHeight()
                            .padding(start = 8.dp, end = 24.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        locationItems()
                    }
                }
            }
        } else {
            LazyColumn(
                state = scrollState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                // contentPadding, not modifier padding: the latter clipped the list 8dp short of the bars,
                // leaving a dead strip where cards were cut off.
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                connectItems()
                locationItems()
            }
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

        if (isListViewSheetOpen && appBehavior != null) {
            ListViewSheet(
                settings = appBehavior,
                onChanged = onAppBehaviorChanged,
                onDismiss = { isListViewSheetOpen = false }
            )
        }

        if (isPingSettingsSheetOpen && appBehavior != null) {
            PingSettingsSheet(
                settings = appBehavior,
                onChanged = onAppBehaviorChanged,
                onDismiss = { isPingSettingsSheetOpen = false }
            )
        }

        if (isAddSheetOpen) {
            AddConfigurationSheet(
                canScanQr = canScanQr,
                onDismiss = {
                    isAddSheetOpen = false
                },
                onScanQrClick = {
                    isAddSheetOpen = false
                    onScanQrRequested()
                },
                onImportText = { text ->
                    isAddSheetOpen = false
                    onImportTextRequested(
                        text,
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
                onStartFreeServers = { limit ->
                    isAddSheetOpen = false
                    viewModel.loadFreeServers(
                        parallelism = pingParallelism,
                        limit = limit,
                        onError = { message ->
                            scope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        }
                    )
                },
                onCreateManual = { engine, wireguard ->
                    isAddSheetOpen = false
                    onAddLocationOfType(engine, wireguard)
                },
                freeServersSettings = appBehavior,
                onFreeServersSettingsChanged = onAppBehaviorChanged
            )
        }

        if (state.isFreeServersLoading) {
            AlertDialog(
                onDismissRequest = {},
                properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
                // The two everyday actions on the right: fold the search into the strip under the list, or
                // stop it and keep what it has found so far (a pass over a long list takes hours).
                confirmButton = {
                    Row {
                        // Closes the dialog; the strip under the toolbar keeps the progress and the result is saved silently.
                        TextButton(onClick = { viewModel.sendFreeServersToBackground() }) {
                            Text(s.freeServersToBackground)
                        }
                        TextButton(onClick = {
                            viewModel.stopFreeServersSearch {
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        s.freeServersNothingYet,
                                        actionLabel = s.freeServersContinue
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        viewModel.loadFreeServers(
                                            parallelism = pingParallelism,
                                            limit = freeServersLimit,
                                            resume = true,
                                            onError = { message -> scope.launch { snackbarHostState.showSnackbar(message) } }
                                        )
                                    }
                                }
                            }
                        }) {
                            Text(s.freeServersStopKeep)
                        }
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            if (state.freeServersPaused) viewModel.resumeFreeServersPaused() else viewModel.pauseFreeServersSearch()
                        }) {
                            Text(if (state.freeServersPaused) s.pingResume else s.pingPause)
                        }
                        TextButton(onClick = { viewModel.cancelFreeServersLoad() }) {
                            Text(s.cancel)
                        }
                    }
                },
                title = { Text(s.freeServers) },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            if (state.freeServersPaused) Spacer(Modifier.size(36.dp)) else PingDot()
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    if (state.freeServersPaused) s.freeServers + " · " + s.pingPausedLabel else s.freeServersLoading,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    s.freeServersWaitNotice,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        state.freeServersProgress?.let { prog ->
                            if (prog.total > 0) {
                                LinearProgressIndicator(
                                    progress = { (prog.checked.toFloat() / prog.total.toFloat()).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().height(4.dp)
                                )
                                Text(
                                    s.freeServersProgressText(prog.checked, prog.total, prog.found) +
                                        (prog.etaSeconds?.takeIf { !state.freeServersPaused }?.let { " · " + s.etaLeft(it) } ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            )
        }

        state.availableFreeServers?.let { freeServers ->
            FreeServersSheet(
                servers = freeServers,
                onDismiss = { viewModel.dismissFreeServersSheet() },
                remaining = state.freeServersResumable,
                onContinue = {
                    viewModel.loadFreeServers(
                        parallelism = pingParallelism,
                        limit = freeServersLimit,
                        resume = true,
                        onError = { message -> scope.launch { snackbarHostState.showSnackbar(message) } }
                    )
                },
                onAddSelected = { selected, warpIds ->
                    viewModel.saveSelectedFreeServers(
                        selectedServers = selected,
                        warpIds = warpIds,
                        onComplete = { count ->
                            scope.launch {
                                snackbarHostState.showSnackbar(s.freeServersImported(count, freeServers.size))
                            }
                        },
                        onError = { message ->
                            scope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        }
                    )
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

/** Sonar-style "ping": a steady dot with a ring that swells and fades, instead of a spinner. */
@Composable
private fun PingDot(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 36.dp) {
    val color = MaterialTheme.colorScheme.primary
    val t = rememberInfiniteTransition(label = "ping")
    val ring by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "ring")
    val pulse by t.animateFloat(0.75f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "pulse")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(size)
                .graphicsLayer { scaleX = 0.3f + 0.7f * ring; scaleY = 0.3f + 0.7f * ring; alpha = 1f - ring }
                .background(color.copy(alpha = 0.5f), CircleShape)
        )
        Box(
            Modifier.size(size * 0.39f)
                .graphicsLayer { scaleX = pulse; scaleY = pulse }
                .background(color, CircleShape)
        )
    }
}

/**
 * Strip above the navigation bar (next to the update banner) while the free servers auto-update: a ping dot,
 * "Free servers · ~8 min left" over "Tested: 120 of 500 (working: 0)", a progress line, pause/resume and stop.
 */
@Composable
private fun FreeServersAutoStrip(
    progress: FreeServersProgress?,
    paused: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    val s = org.olcbox.app.ui.i18n.LocalStrings.current
    val tail = when {
        paused -> s.pingPausedLabel
        progress?.etaSeconds != null -> s.etaLeft(progress.etaSeconds)
        else -> null
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!paused) PingDot(size = 18.dp) else Spacer(Modifier.size(18.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = s.freeServers + (tail?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    progress?.let {
                        Text(
                            text = s.freeServersProgressText(it.checked, it.total, it.found),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = if (paused) onResume else onPause, modifier = Modifier.size(40.dp)) {
                    Icon(
                        if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                        contentDescription = if (paused) s.pingResume else s.pingPause,
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(onClick = onCancel, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = s.cancel, modifier = Modifier.size(20.dp))
                }
            }
            if (progress != null && progress.total > 0) {
                LinearProgressIndicator(
                    progress = { (progress.checked.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(2.dp)
                )
            }
        }
    }
}

@Composable
private fun UpdateAvailableBanner(onClick: () -> Unit) {
    val s = org.olcbox.app.ui.i18n.LocalStrings.current
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        color = colors.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = s.updateBannerTitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onPrimaryContainer,
                modifier = Modifier.weight(1f)
            )
            // Inverted container colours: a text button tinted `primary` vanished on a
            // dynamic-colour primaryContainer (same hue), leaving the banner without an action.
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.onPrimaryContainer,
                    contentColor = colors.primaryContainer
                )
            ) {
                Text(text = s.updateBannerAction, fontWeight = FontWeight.SemiBold)
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
            .clip(RoundedCornerShape(24.dp))
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
