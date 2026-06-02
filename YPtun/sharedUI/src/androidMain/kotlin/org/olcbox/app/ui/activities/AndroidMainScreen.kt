package org.olcbox.app.ui.activities

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import org.olcbox.app.data.share.ConfigShareService
import org.olcbox.app.update.AndroidUpdateSettingsStore
import org.olcbox.app.update.AppUpdateInfo
import org.olcbox.app.update.AppUpdateSettings
import org.olcbox.app.update.AppUpdateService
import org.olcbox.app.update.AndroidUpdateInstaller
import org.olcbox.app.update.identity
import org.olcbox.app.update.isDownloaded
import org.olcbox.app.update.isUpdateCheckDue
import org.olcbox.app.update.shouldShowOffer
import org.olcbox.app.ui.OlcboxAppContent
import org.olcbox.app.ui.components.ApplicationUpdateOfferSheet
import org.olcbox.app.ui.features.home.HomeScreenViewModel
import org.olcbox.app.ui.features.locations.LocationViewModel
import org.olcbox.app.ui.navigation.AppScreen
import org.olcbox.app.vpn.AndroidConnectionMode
import org.olcbox.app.vpn.AndroidSplitTunnelList
import org.olcbox.app.vpn.AndroidSplitTunnelMode
import org.olcbox.app.vpn.AndroidVpnManager

@Composable
fun AndroidMainScreen(
    viewModel: HomeScreenViewModel,
    locationViewModel: LocationViewModel,
    vpnManager: AndroidVpnManager,
    appUpdateService: AppUpdateService? = null
) {

    var currentScreenRoute by rememberSaveable { mutableStateOf("home") }
    var currentLocationId by rememberSaveable { mutableStateOf<String?>(null) }

    val currentScreen: AppScreen =
        when (currentScreenRoute) {
            "location_settings" -> AppScreen.LocationSettings(currentLocationId)
            else -> AppScreen.Home
        }

    val navigate: (AppScreen) -> Unit = { screen ->
        when (screen) {
            AppScreen.Home -> {
                currentScreenRoute = "home"
                currentLocationId = null
            }
            is AppScreen.LocationSettings -> {
                currentScreenRoute = "location_settings"
                currentLocationId = screen.locationId
            }
        }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connectionMode by vpnManager.connectionMode.collectAsState()
    val proxySettings by vpnManager.proxySettings.collectAsState()
    val splitTunnelSettings by vpnManager.splitTunnelSettings.collectAsState()
    val dynamicThemeEnabled by vpnManager.dynamicThemeEnabled.collectAsState()
    val hwid by vpnManager.hwid.collectAsState()
    val routing by vpnManager.routing.collectAsState()
    val trafficSettings by vpnManager.trafficSettings.collectAsState()
    val appBehavior by vpnManager.appBehavior.collectAsState()
    val language by vpnManager.language.collectAsState()
    val installedApps by vpnManager.installedApps.collectAsState()
    val homeState by viewModel.state.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val pendingLogSaveCallbacks = remember {
        mutableStateOf<Pair<(String) -> Unit, (String) -> Unit>?>(null)
    }
    val pendingVpnAction = remember {
        mutableStateOf<PendingVpnPermissionAction?>(null)
    }
    var isAppSettingsOpen by remember { mutableStateOf(false) }
    var appSettingsInitialRoute by remember { mutableStateOf(AppSettingsInitialRoute.Hub) }
    var shareSheetPayload by remember { mutableStateOf<Pair<String, String>?>(null) }
    var splitTunnelRestartPending by remember { mutableStateOf(false) }
    val updateSettingsStore = remember(context) {
        AndroidUpdateSettingsStore(context)
    }
    val updateInstaller = remember(context, vpnManager) {
        AndroidUpdateInstaller(context) {
            vpnManager.subscriptionFetchProxy()
        }
    }
    var updateSettings by remember { mutableStateOf(AppUpdateSettings()) }
    var updateStatusText by remember { mutableStateOf<String?>(null) }
    var updateDownloadProgress by remember { mutableStateOf<Float?>(null) }
    var updateOffer by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var relaunchAfterInstall by remember { mutableStateOf(false) }
    val subscriptionShareItems = locationViewModel.locations.toList()
        .mapNotNull { item ->
            val url = item.subscriptionUrl
                ?.trim()
                ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ?: return@mapNotNull null
            url to item
        }
        .groupBy({ it.first }, { it.second })
        .entries
        .sortedBy { it.key }
        .map { (url, items) ->
            val metadata = items.firstNotNullOfOrNull { it.metadata?.subscription }
            org.olcbox.app.data.share.SubscriptionShareItem(
                url = url,
                name = metadata?.name?.takeIf { it.isNotBlank() }
                    ?: items.first().fullName,
                updateIntervalHours = metadata?.updateIntervalHours,
                lastRefreshAtEpochMs = metadata?.lastRefreshAtEpochMs,
                locationCount = items.size
            )
        }

    val updateInstallLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (relaunchAfterInstall && result.resultCode == Activity.RESULT_OK) {
            relaunchAfterInstall = false
            updateInstaller.relaunchIntent()?.let { intent ->
                runCatching { context.startActivity(intent) }
            }
        } else {
            relaunchAfterInstall = false
        }
    }

    fun markSplitTunnelChanged() {
        if (homeState.isVpnConnected && connectionMode == AndroidConnectionMode.Tun) {
            splitTunnelRestartPending = true
        }
    }

    fun applyPendingSplitTunnelRestart() {
        if (splitTunnelRestartPending && homeState.isVpnConnected && connectionMode == AndroidConnectionMode.Tun) {
            viewModel.restartVpnIfRunning()
        }
        splitTunnelRestartPending = false
    }

    suspend fun saveUpdateSettings(settings: AppUpdateSettings) {
        val normalized = settings.normalized()
        updateSettings = normalized
        updateSettingsStore.save(normalized)
    }

    fun showUpdateResult(info: AppUpdateInfo) {
        if (info.isDownloaded(updateSettings)) {
            updateOffer = null
            updateStatusText = "Latest ${info.channel.name.lowercase()} is already downloaded"
        } else if (info.isUpdateAvailable) {
            updateOffer = info
            updateStatusText = "${info.channel.name} update available: ${info.version}"
        } else {
            updateOffer = null
            updateStatusText = "YPtun is up to date"
        }
    }

    fun checkUpdate(manual: Boolean) {
        val service = appUpdateService
        if (service == null) {
            updateStatusText = "Update service unavailable"
            return
        }
        scope.launch {
            val previousSettings = updateSettings
            val checkStartedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
            if (!manual && !previousSettings.isUpdateCheckDue(checkStartedAt)) return@launch

            updateStatusText = "Checking ${previousSettings.channel.name.lowercase()}..."
            val result = service.check(
                previousSettings.channel,
                vpnManager.subscriptionFetchProxy()
            )
            val checkedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
            val checkedSettings = previousSettings.copy(lastCheckAtEpochMs = checkedAt).normalized()
            saveUpdateSettings(checkedSettings)
            result.fold(
                onSuccess = { info ->
                    if (manual || info.shouldShowOffer(previousSettings, checkedAt)) {
                        showUpdateResult(info)
                    } else {
                        updateOffer = null
                        updateStatusText = null
                    }
                },
                onFailure = { error ->
                    updateStatusText = error.message ?: "Update check failed"
                }
            )
        }
    }

    fun downloadUpdate(info: AppUpdateInfo) {
        scope.launch {
            if (!updateInstaller.canRequestPackageInstalls()) {
                updateInstaller.openUnknownSourcesSettings()
                updateStatusText = "Allow YPtun to install updates, then tap Download again"
                Toast.makeText(context, updateStatusText, Toast.LENGTH_LONG).show()
                return@launch
            }

            updateDownloadProgress = 0f
            updateStatusText = "Downloading ${info.asset.name}..."
            val result = updateInstaller.download(info.asset) { progress ->
                updateDownloadProgress = progress
            }
            val file = result.getOrElse { error ->
                updateStatusText = "Download failed: ${error.message ?: "unknown error"}"
                updateDownloadProgress = null
                Toast.makeText(context, updateStatusText, Toast.LENGTH_LONG).show()
                return@launch
            }
            updateStatusText = "Installing ${info.asset.name}"
            saveUpdateSettings(
                updateSettings.copy(
                    lastSeenUpdateVersion = info.identity(),
                    lastDownloadedUpdateVersion = info.identity()
                )
            )
            updateOffer = null
            updateDownloadProgress = null
            relaunchAfterInstall = true
            updateInstallLauncher.launch(updateInstaller.installIntent(file))
        }
    }

    fun postponeUpdate(info: AppUpdateInfo) {
        scope.launch {
            saveUpdateSettings(updateSettings.copy(lastSeenUpdateVersion = info.identity()))
            updateOffer = null
        }
    }

    LaunchedEffect(appUpdateService) {
        val loaded = updateSettingsStore.load()
        updateSettings = loaded
        if (appUpdateService != null) {
            checkUpdate(manual = false)
        }
    }

    fun reloadLocationsAfterImport(onComplete: () -> Unit = {}) {
        locationViewModel.loadLocations {
            viewModel.loadCurrentConfig(onComplete)
        }
    }

    val vpnRequestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            when (val action = pendingVpnAction.value) {
                PendingVpnPermissionAction.Toggle -> viewModel.ToggleVpn()
                is PendingVpnPermissionAction.RestartWithMode -> {
                    vpnManager.selectConnectionMode(action.mode)
                    viewModel.restartVpnIfRunning()
                }
                null -> Unit
            }
        }
        pendingVpnAction.value = null
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        org.olcbox.app.vpn.service.OlcboxVpnState.addLog("import: file picker result uri=$uri")
        if (uri == null) {
            Toast.makeText(context, "No file selected", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        viewModel.onFileSelected(
            fileSource = uri,
            onComplete = { reloadLocationsAfterImport() },
            onError = { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
        )
    }

    val qrScannerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult

        val rawText = result.data?.getStringExtra(QrScannerActivity.EXTRA_QR_TEXT)
            ?.trim()
            .orEmpty()

        if (rawText.isBlank()) return@rememberLauncherForActivityResult

        viewModel.onImportFullConfig(
            rawText = rawText,
            onComplete = {
                reloadLocationsAfterImport {
                    Toast.makeText(context, "QR imported", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
        )
    }

    val logSaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        val callbacks = pendingLogSaveCallbacks.value
        pendingLogSaveCallbacks.value = null
        if (uri == null || callbacks == null) return@rememberLauncherForActivityResult

        viewModel.onSaveLogsToFile(
            target = uri,
            onSaved = callbacks.first,
            onError = callbacks.second
        )
    }

    var autoConnectTried by remember { mutableStateOf(false) }
    LaunchedEffect(appBehavior.autoConnectOnLaunch, homeState.canStartVpn) {
        if (!autoConnectTried &&
            appBehavior.autoConnectOnLaunch &&
            homeState.canStartVpn &&
            !homeState.isVpnConnected &&
            !homeState.isVpnLoading
        ) {
            autoConnectTried = true
            val prepIntent = if (connectionMode == AndroidConnectionMode.Tun) {
                VpnService.prepare(context)
            } else {
                null
            }
            if (prepIntent != null) {
                pendingVpnAction.value = PendingVpnPermissionAction.Toggle
                vpnRequestLauncher.launch(prepIntent)
            } else {
                viewModel.ToggleVpn()
            }
        }
    }

    fun navigateHomeFromLocationSettings() {
        viewModel.loadCurrentConfig()
        navigate(AppScreen.Home)
    }

    BackHandler(enabled = currentScreen is AppScreen.LocationSettings) {
        navigateHomeFromLocationSettings()
    }

    OlcboxAppContent(
        homeViewModel = viewModel,
        locationViewModel = locationViewModel,
        currentScreen = currentScreen,
        onNavigate = navigate,
        onToggleClick = {
            val prepIntent = if (connectionMode == AndroidConnectionMode.Tun) {
                VpnService.prepare(context)
            } else {
                null
            }
            if (prepIntent != null) {
                pendingVpnAction.value = PendingVpnPermissionAction.Toggle
                vpnRequestLauncher.launch(prepIntent)
            } else {
                viewModel.ToggleVpn()
            }
        },
        onImportFileRequested = {
            org.olcbox.app.vpn.service.OlcboxVpnState.addLog("import: launching file picker")
            runCatching { filePickerLauncher.launch(arrayOf("*/*")) }
                .onFailure {
                    org.olcbox.app.vpn.service.OlcboxVpnState.addLog("import: launch failed: ${it.message}")
                    Toast.makeText(context, "Cannot open file picker: ${it.message}", Toast.LENGTH_LONG).show()
                }
        },
        onImportFromClipboardRequested = { onImported, onError ->
            viewModel.onPasteFromClipboard(
                onComplete = {
                    reloadLocationsAfterImport(onImported)
                },
                onError = onError
            )
        },
        onScanQrRequested = {
            qrScannerLauncher.launch(Intent(context, QrScannerActivity::class.java))
        },
        onCopyConfigRequested = {
            viewModel.onCopyFullConfigClicked()
        },
        onShareLocationRequested = { config ->
            shareSheetPayload = "Location QR" to ConfigShareService.olcRtcUri(config)
        },
        onSaveLogsRequested = { onSaved, onError ->
            pendingLogSaveCallbacks.value = onSaved to onError
            logSaveLauncher.launch(viewModel.suggestedLogsFileName())
        },
        showAppSettingsButton = true,
        showSplitTunnelingButton = false,
        canScanQr = true,
        confirmBeforeDelete = appBehavior.confirmBeforeDelete,
        onAppSettingsClick = {
            appSettingsInitialRoute = AppSettingsInitialRoute.Hub
            vpnManager.refreshInstalledApps()
            isAppSettingsOpen = true
        },
        onSplitTunnelingClick = {
            appSettingsInitialRoute = AppSettingsInitialRoute.SplitTunneling
            vpnManager.refreshInstalledApps()
            isAppSettingsOpen = true
        }
    )

    shareSheetPayload?.let { (title, payload) ->
        AndroidConfigShareSheet(
            title = title,
            payload = payload,
            onDismiss = { shareSheetPayload = null }
        )
    }

    homeState.vkTurnLinkPrompt?.let { prompt ->
        VkTurnLinkPromptDialog(
            locationName = prompt.locationName,
            onLater = { viewModel.dismissVkTurnLinkPrompt() },
            onNext = { link -> viewModel.submitVkTurnLink(prompt.storageId, link) }
        )
    }

    updateOffer?.let { info ->
        ApplicationUpdateOfferSheet(
            info = info,
            downloadProgress = updateDownloadProgress,
            onLater = { postponeUpdate(info) },
            onDownload = { downloadUpdate(info) }
        )
    }

    if (isAppSettingsOpen) {
        AppSettingsSheet(
            initialRoute = appSettingsInitialRoute,
            selectedMode = connectionMode,
            proxySettings = proxySettings,
            splitTunnelSettings = splitTunnelSettings,
            installedApps = installedApps,
            logs = logs,
            dynamicThemeEnabled = dynamicThemeEnabled,
            hwid = hwid,
            routing = routing,
            onRoutingChanged = vpnManager::setRouting,
            trafficSettings = trafficSettings,
            onTrafficChanged = vpnManager::setTrafficSettings,
            appBehavior = appBehavior,
            onAppBehaviorChanged = vpnManager::setAppBehavior,
            language = language,
            onLanguageChanged = vpnManager::setLanguage,
            updateSettings = updateSettings,
            updateStatusText = updateStatusText,
            updateDownloadProgress = updateDownloadProgress,
            subscriptions = subscriptionShareItems,
            enabled = !homeState.isVpnLoading,
            isConnectionActive = homeState.isVpnConnected,
            onDismiss = {
                isAppSettingsOpen = false
                applyPendingSplitTunnelRestart()
            },
            onCopyConfigClick = {
                viewModel.onCopyFullConfigClicked()
                Toast.makeText(context, "Config copied", Toast.LENGTH_SHORT).show()
            },
            onSaveLogsClick = {
                val showToast: (String) -> Unit = { message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
                pendingLogSaveCallbacks.value = showToast to showToast
                logSaveLauncher.launch(viewModel.suggestedLogsFileName())
            },
            onShareLogsClick = {
                val showToast: (String) -> Unit = { message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
                viewModel.onShareLogs(showToast, showToast)
            },
            onUpdateIntervalSelected = { hours ->
                scope.launch {
                    saveUpdateSettings(updateSettings.copy(intervalHours = hours))
                }
            },
            onCheckUpdatesClick = {
                checkUpdate(manual = true)
            },
            onSubscriptionShareClick = { url ->
                shareSheetPayload = "Subscription QR" to ConfigShareService.subscriptionQrText(url)
            },
            onSubscriptionRefreshClick = { url ->
                viewModel.refreshSubscription(url) { updatedCount ->
                    reloadLocationsAfterImport {
                        viewModel.restartVpnIfRunning()
                        Toast.makeText(
                            context,
                            if (updatedCount > 0) "Subscription updated" else "Subscription not updated",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            onDynamicThemeChanged = vpnManager::setDynamicThemeEnabled,
            onAccentColorSelected = vpnManager::setAccentColor,
            onTextColorSelected = vpnManager::setTextColor,
            onBackgroundColorSelected = vpnManager::setBackgroundColor,
            onModeSelected = { mode ->
                if (mode != connectionMode && homeState.isVpnConnected) {
                    val prepIntent = if (mode == AndroidConnectionMode.Tun) {
                        VpnService.prepare(context)
                    } else {
                        null
                    }
                    if (prepIntent != null) {
                        pendingVpnAction.value = PendingVpnPermissionAction.RestartWithMode(mode)
                        vpnRequestLauncher.launch(prepIntent)
                    } else {
                        vpnManager.selectConnectionMode(mode)
                        viewModel.restartVpnIfRunning()
                    }
                } else if (mode != connectionMode) {
                    vpnManager.selectConnectionMode(mode)
                }
            },
            onProxySettingsSaved = { host, username, password, port ->
                vpnManager.updateProxySettings(host, username, password, port)
                if (homeState.isVpnConnected) {
                    viewModel.restartVpnIfRunning()
                }
            },
            onProxyPasswordRegenerated = {
                vpnManager.regenerateProxyPassword()
                if (homeState.isVpnConnected) {
                    viewModel.restartVpnIfRunning()
                }
            },
            onSplitTunnelModeSelected = { mode: AndroidSplitTunnelMode ->
                vpnManager.selectSplitTunnelMode(mode)
                markSplitTunnelChanged()
            },
            onSplitTunnelAppToggled = { list: AndroidSplitTunnelList, packageName: String ->
                vpnManager.toggleSplitTunnelApp(list, packageName)
                markSplitTunnelChanged()
            },
            onSplitTunnelAppsSelected = { list: AndroidSplitTunnelList, packages: Set<String> ->
                vpnManager.setSplitTunnelApps(list, packages)
                markSplitTunnelChanged()
            }
        )
    }
}

private sealed class PendingVpnPermissionAction {
    object Toggle : PendingVpnPermissionAction()
    data class RestartWithMode(val mode: AndroidConnectionMode) : PendingVpnPermissionAction()
}

/**
 * Asks for the per-client VK Calls link right after a VK-TURN location is imported. "Later" defers
 * (the link can be added from location settings); "Next" saves it so the location can connect.
 */
@Composable
private fun VkTurnLinkPromptDialog(
    locationName: String,
    onLater: () -> Unit,
    onNext: (String) -> Unit
) {
    var link by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("VK call link") },
        text = {
            Column {
                Text("Paste your VK Calls join link for \"$locationName\". You can paste several links (one per line) to spread the tunnel across calls for more speed.")
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    placeholder = { Text("https://vk.com/call/join/…") },
                    isError = link.isNotBlank() && !link.contains("/call/join/"),
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onNext(link) },
                enabled = link.contains("/call/join/")
            ) {
                Text("Next")
            }
        },
        dismissButton = {
            TextButton(onClick = onLater) {
                Text("Later")
            }
        }
    )
}
