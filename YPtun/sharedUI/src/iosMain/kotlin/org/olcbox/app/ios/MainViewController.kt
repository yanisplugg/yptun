package org.olcbox.app.ios

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.olcbox.app.data.datasource.IosLocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.exporter.IosLogExporter
import org.olcbox.app.data.identity.PersistentDeviceIdentityProvider
import org.olcbox.app.data.importer.IosConfigImporter
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.share.ConfigShareService
import org.olcbox.app.data.share.SubscriptionShareItem
import org.olcbox.app.ui.OlcboxAppContent
import org.olcbox.app.ui.activities.AppSettingsSheet
import org.olcbox.app.ui.components.ApplicationUpdateOfferSheet
import org.olcbox.app.ui.features.home.HomeScreenViewModel
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.ui.features.locations.LocationViewModel
import org.olcbox.app.ui.navigation.AppScreen
import org.olcbox.app.ui.theme.AppTheme
import org.olcbox.app.update.AppUpdateInfo
import org.olcbox.app.update.AppUpdateSettings
import org.olcbox.app.update.AppUpdateService
import org.olcbox.app.update.IosUpdateSettingsStore
import org.olcbox.app.update.identity
import org.olcbox.app.update.isDownloaded
import org.olcbox.app.update.isUpdateCheckDue
import org.olcbox.app.update.shouldShowOffer
import org.olcbox.app.vpn.AndroidConnectionMode
import org.olcbox.app.vpn.AndroidSocksProxySettings
import org.olcbox.app.vpn.AndroidSplitTunnelSettings
import org.olcbox.app.vpn.IosVpnManager
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.ios.IosSettingsController
import org.olcbox.app.vpn.telegram.TelegramProxyState
import platform.UIKit.UIViewController

class IosAppFactory {
    fun createSession(
        platformBridge: IosPlatformBridge,
        coreBridge: IosCoreBridge
    ): IosAppSession {
        return IosAppSession(platformBridge, coreBridge)
    }

    fun createViewController(
        platformBridge: IosPlatformBridge,
        coreBridge: IosCoreBridge
    ): UIViewController {
        return createSession(platformBridge, coreBridge).createViewController()
    }
}

class IosAppSession internal constructor(
    private val platformBridge: IosPlatformBridge,
    coreBridge: IosCoreBridge
) {
    private val dependencies = IosAppDependencies(platformBridge, coreBridge)

    init {
        IosPlatformHooks.bridge = platformBridge
        IosPlatformHooks.core = coreBridge
    }

    fun startVpn() {
        dependencies.vpnManager.startVpn()
    }

    fun stopVpn() {
        dependencies.vpnManager.stopVpn()
    }

    /** Stop, wait until the tunnel is really down (a start while it is still stopping gets lost), start. */
    fun restartVpn() {
        restartScope.launch {
            val vpn = dependencies.vpnManager
            vpn.stopVpn()
            withTimeoutOrNull(RESTART_STOP_TIMEOUT_MS) {
                vpn.status.first { it is VpnStatus.Disconnected || it is VpnStatus.Error }
            }
            vpn.startVpn()
        }
    }

    private val restartScope = MainScope()

    fun createViewController(): UIViewController {
        return ComposeUIViewController {
            IosApp(platformBridge, dependencies)
        }
    }

    fun close() {
        restartScope.cancel()
        dependencies.close()
    }

    private companion object {
        const val RESTART_STOP_TIMEOUT_MS = 10_000L
    }
}

private class IosAppDependencies(
    platformBridge: IosPlatformBridge,
    coreBridge: IosCoreBridge
) {
    private val locationsDataSource = IosLocationsDataSourceImpl()
    val locationsRepository = LocationsRepositoryImpl(locationsDataSource)
    val vpnManager = IosVpnManager(locationsRepository, coreBridge)
    val updateService = AppUpdateService(
        deviceIdentityProvider = PersistentDeviceIdentityProvider(locationsDataSource)
    )
    val updateSettingsStore = IosUpdateSettingsStore()
    val homeViewModel = HomeScreenViewModel(
        vpnManager = vpnManager,
        locationsRepository = locationsRepository,
        configImporter = IosConfigImporter(platformBridge),
        logExporter = IosLogExporter(platformBridge)
    )
    val locationViewModel = LocationViewModel(locationsRepository)
    val settings = IosSettingsController()

    init {
        // Routing profiles are read through the VpnManager interface (the per-location selector and
        // happ:// link import); hand it the controller so both sides see one state.
        vpnManager.settingsController = settings
    }

    fun close() {
        vpnManager.close()
    }
}

@Composable
private fun IosApp(
    platformBridge: IosPlatformBridge,
    dependencies: IosAppDependencies
) {
    val scope = rememberCoroutineScope()
    var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Home) }
    var isAppSettingsOpen by remember { mutableStateOf(false) }
    var updateSettings by remember { mutableStateOf(AppUpdateSettings()) }
    var updateStatusText by remember { mutableStateOf<String?>(null) }
    var updateDownloadProgress by remember { mutableStateOf<Float?>(null) }
    var updateOffer by remember { mutableStateOf<AppUpdateInfo?>(null) }

    fun reloadLocationsAfterImport(onComplete: () -> Unit = {}) {
        dependencies.locationViewModel.loadLocations {
            dependencies.homeViewModel.loadCurrentConfig(onComplete)
        }
    }

    suspend fun saveUpdateSettings(settings: AppUpdateSettings) {
        val normalized = settings.normalized()
        updateSettings = normalized
        dependencies.updateSettingsStore.save(normalized)
    }

    fun checkUpdate(manual: Boolean) {
        scope.launch {
            val previousSettings = updateSettings
            val checkStartedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
            if (!manual && !previousSettings.isUpdateCheckDue(checkStartedAt)) return@launch

            updateStatusText = "Checking ${previousSettings.channel.name.lowercase()}..."
            val result = dependencies.updateService.check(previousSettings.channel)
            val checkedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
            val checkedSettings = previousSettings.copy(lastCheckAtEpochMs = checkedAt).normalized()
            saveUpdateSettings(checkedSettings)
            result.fold(
                onSuccess = { info ->
                    if (manual || info.shouldShowOffer(previousSettings, checkedAt)) {
                        if (info.isDownloaded(checkedSettings)) {
                            updateOffer = null
                            updateStatusText = "Latest ${info.channel.name.lowercase()} is already downloaded"
                        } else if (info.isUpdateAvailable) {
                            updateOffer = info
                            updateStatusText = "${info.channel.name} update available: ${info.version}"
                        } else {
                            updateOffer = null
                            updateStatusText = "Olcbox is up to date"
                        }
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

    fun laterUpdate(info: AppUpdateInfo) {
        scope.launch {
            saveUpdateSettings(updateSettings.copy(lastSeenUpdateVersion = info.identity()))
            updateOffer = null
        }
    }

    fun downloadUpdate(info: AppUpdateInfo) {
        updateStatusText = "Install ${info.version} from the release page"
        updateOffer = null
    }

    LaunchedEffect(Unit) {
        val loaded = dependencies.updateSettingsStore.load()
        updateSettings = loaded
        dependencies.locationViewModel.loadLocations()
        dependencies.homeViewModel.loadCurrentConfig()
        checkUpdate(manual = false)
    }

    AppTheme {
        val logs by dependencies.homeViewModel.logs.collectAsState()
        val homeState by dependencies.homeViewModel.state.collectAsState()
        val socksProxySettings by dependencies.vpnManager.socksProxySettings.collectAsState()

        val appBehavior by dependencies.settings.appBehavior.collectAsState()
        val routing by dependencies.settings.routing.collectAsState()
        val routingProfiles by dependencies.settings.routingProfiles.collectAsState()
        val trafficSettings by dependencies.settings.traffic.collectAsState()
        val geoUpdateStatus by dependencies.settings.geoUpdateStatus.collectAsState()
        val language by dependencies.settings.language.collectAsState()
        val lightTheme by dependencies.settings.lightTheme.collectAsState()
        // Shown in settings as the device id the panel sees; reading it touches storage, so it is
        // resolved once off the composition.
        var hwid by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            hwid = runCatching { dependencies.locationsRepository.getDeviceIdentity() }.getOrDefault("")
        }

        Box(modifier = Modifier.fillMaxSize()) {
            OlcboxAppContent(
                homeViewModel = dependencies.homeViewModel,
                locationViewModel = dependencies.locationViewModel,
                currentScreen = currentScreen,
                onNavigate = { screen -> currentScreen = screen },
                onToggleClick = {
                    dependencies.homeViewModel.ToggleVpn()
                },
                onImportFileRequested = {
                    platformBridge.pickConfigText(object : IosTextCallback {
                        override fun onSuccess(text: String) {
                            dependencies.homeViewModel.onImportFullConfig(text) {
                                reloadLocationsAfterImport {
                                    platformBridge.showMessage("Config imported")
                                }
                            }
                        }

                        override fun onError(message: String) {
                            platformBridge.showMessage(message)
                        }
                    })
                },
                onImportFromClipboardRequested = { onImported, onError ->
                    dependencies.homeViewModel.onPasteFromClipboard(
                        onComplete = {
                            reloadLocationsAfterImport(onImported)
                        },
                        onError = onError
                    )
                },
                onScanQrRequested = {
                    IosQrScanner.present(
                        onResult = { text ->
                            dependencies.homeViewModel.onImportFullConfig(
                                rawText = text,
                                onComplete = {
                                    reloadLocationsAfterImport {
                                        platformBridge.showMessage(org.olcbox.app.ui.i18n.stringsFor(
                                            org.olcbox.app.ui.i18n.LocalizationState.effective
                                        ).qrImported)
                                    }
                                },
                                onError = platformBridge::showMessage
                            )
                        },
                        onError = platformBridge::showMessage
                    )
                },
                onCopyConfigRequested = {
                    dependencies.homeViewModel.onCopyFullConfigClicked()
                },
                onShareLocationRequested = { config: LocationConfig ->
                    // Share our universal yptun:// link (carries the whole inbound incl. proxy + toggles).
                    platformBridge.shareText("Location", org.olcbox.app.data.share.YptunInboundCodec.compose(config))
                },
                onSaveLogsRequested = { onSaved, onError ->
                    dependencies.homeViewModel.onSaveLogsToFile(
                        target = dependencies.homeViewModel.suggestedLogsFileName(),
                        onSaved = onSaved,
                        onError = onError
                    )
                },
                showAppSettingsButton = true,
                showSplitTunnelingButton = false,
                canScanQr = true,
                onAppSettingsClick = { isAppSettingsOpen = true },
                onSplitTunnelingClick = {},
                collapsedGroups = appBehavior.collapsedSubscriptionGroups,
                pinnedGroups = appBehavior.pinnedSubscriptionGroups,
                pingSortedGroups = appBehavior.pingSortedSubscriptionGroups,
                pingSortDescendingGroups = appBehavior.pingSortDescendingSubscriptionGroups,
                pinnedCustomLocations = appBehavior.pinnedCustomLocations,
                customLocationsPingSorted = appBehavior.customLocationsPingSorted,
                customLocationsPingSortDescending = appBehavior.customLocationsPingSortDescending,
                onToggleGroupCollapsed = { key ->
                    val current = appBehavior.collapsedSubscriptionGroups
                    val updated = if (key in current) current - key else current + key
                    val newBehavior = appBehavior.copy(collapsedSubscriptionGroups = updated)
                    dependencies.settings.setAppBehavior(newBehavior)
                },
                onToggleGroupPinned = { key ->
                    val current = appBehavior.pinnedSubscriptionGroups
                    val updated = if (key in current) current - key else current + key
                    val newBehavior = appBehavior.copy(pinnedSubscriptionGroups = updated)
                    dependencies.settings.setAppBehavior(newBehavior)
                },
                onToggleGroupPingSort = { key ->
                    val sorted = appBehavior.pingSortedSubscriptionGroups
                    val desc = appBehavior.pingSortDescendingSubscriptionGroups
                    val updated = when {
                        key !in sorted -> appBehavior.copy(
                            pingSortedSubscriptionGroups = sorted + key,
                            pingSortDescendingSubscriptionGroups = desc - key,
                        )
                        key !in desc -> appBehavior.copy(pingSortDescendingSubscriptionGroups = desc + key)
                        else -> appBehavior.copy(
                            pingSortedSubscriptionGroups = sorted - key,
                            pingSortDescendingSubscriptionGroups = desc - key,
                        )
                    }
                    dependencies.settings.setAppBehavior(updated)
                },
                onToggleCustomLocationPinned = { id ->
                    val current = appBehavior.pinnedCustomLocations
                    val updated = if (id in current) current - id else current + id
                    val newBehavior = appBehavior.copy(pinnedCustomLocations = updated)
                    dependencies.settings.setAppBehavior(newBehavior)
                },
                onToggleCustomLocationsPingSort = {
                    val updated = when {
                        !appBehavior.customLocationsPingSorted -> appBehavior.copy(
                            customLocationsPingSorted = true,
                            customLocationsPingSortDescending = false,
                        )
                        !appBehavior.customLocationsPingSortDescending -> appBehavior.copy(
                            customLocationsPingSortDescending = true
                        )
                        else -> appBehavior.copy(
                            customLocationsPingSorted = false,
                            customLocationsPingSortDescending = false,
                        )
                    }
                    dependencies.settings.setAppBehavior(updated)
                },
                customGroups = appBehavior.customGroups,
                onCreateFolder = { name, memberKeys ->
                    val folder = org.olcbox.app.data.model.CustomGroup(
                        id = "folder_${kotlin.random.Random.nextInt(100_000, 999_999)}",
                        name = name.trim(),
                        members = memberKeys
                    )
                    val cleaned = appBehavior.customGroups.map { g -> g.copy(members = g.members - memberKeys.toSet()) }
                    val updated = appBehavior.copy(customGroups = cleaned + folder)
                    dependencies.settings.setAppBehavior(updated)
                },
                onRenameFolder = { id, name ->
                    val updated = appBehavior.copy(
                        customGroups = appBehavior.customGroups.map {
                            if (it.id == id) it.copy(name = name.trim()) else it
                        }
                    )
                    dependencies.settings.setAppBehavior(updated)
                },
                onDeleteFolder = { id ->
                    val updated = appBehavior.copy(customGroups = appBehavior.customGroups.filter { it.id != id })
                    dependencies.settings.setAppBehavior(updated)
                },
                onAddToFolder = { id, memberKeys ->
                    val updated = appBehavior.copy(
                        customGroups = appBehavior.customGroups.map {
                            if (it.id == id) it.copy(members = (it.members + memberKeys).distinct()) else it
                        }
                    )
                    dependencies.settings.setAppBehavior(updated)
                },
                onRemoveFromFolder = { memberKeys ->
                    val removeSet = memberKeys.toSet()
                    val updated = appBehavior.copy(
                        customGroups = appBehavior.customGroups.map {
                            it.copy(members = it.members.filter { m -> m !in removeSet })
                        }
                    )
                    dependencies.settings.setAppBehavior(updated)
                },
                onToggleFolderPinned = { id ->
                    val updated = appBehavior.copy(
                        customGroups = appBehavior.customGroups.map {
                            if (it.id == id) it.copy(pinned = !it.pinned) else it
                        }
                    )
                    dependencies.settings.setAppBehavior(updated)
                },
                onToggleFolderCollapsed = { id ->
                    val updated = appBehavior.copy(
                        customGroups = appBehavior.customGroups.map {
                            if (it.id == id) it.copy(collapsed = !it.collapsed) else it
                        }
                    )
                    dependencies.settings.setAppBehavior(updated)
                }
            )

            if (isAppSettingsOpen) {
                AppSettingsSheet(
                    // iOS runs a single connection mode (a Network Extension packet tunnel). The
                    // mode picker is hidden on this platform, so the value is fixed.
                    selectedMode = AndroidConnectionMode.Tun,
                    proxySettings = AndroidSocksProxySettings(
                        host = socksProxySettings.host,
                        port = socksProxySettings.port,
                        username = socksProxySettings.username,
                        password = socksProxySettings.password,
                        // iOS has no separate flag: the local SOCKS listener is secured exactly when
                        // it carries credentials.
                        secured = socksProxySettings.username.isNotBlank()
                    ),
                    // Per-app routing is impossible on iOS — there is no way to enumerate installed
                    // apps or attribute traffic to them — so the section is hidden and these stay at
                    // their defaults.
                    splitTunnelSettings = AndroidSplitTunnelSettings(),
                    installedApps = emptyList(),
                    logs = logs,
                    // No Material You equivalent on iOS; the switch is hidden there.
                    dynamicThemeEnabled = false,
                    lightThemeEnabled = lightTheme,
                    hwid = hwid,
                    routing = routing,
                    onRoutingChanged = {
                        dependencies.settings.setRouting(it)
                        if (homeState.isVpnConnected) dependencies.homeViewModel.restartVpnIfRunning()
                    },
                    routingProfilesState = routingProfiles,
                    geoUpdateStatus = geoUpdateStatus,
                    onRoutingProfileSaved = {
                        dependencies.settings.saveRoutingProfile(it)
                        if (homeState.isVpnConnected) dependencies.homeViewModel.restartVpnIfRunning()
                    },
                    onRoutingProfileDeleted = {
                        dependencies.settings.deleteRoutingProfile(it)
                        if (homeState.isVpnConnected) dependencies.homeViewModel.restartVpnIfRunning()
                    },
                    onGlobalRoutingProfileChanged = {
                        dependencies.settings.setGlobalRoutingProfile(it)
                        if (homeState.isVpnConnected) dependencies.homeViewModel.restartVpnIfRunning()
                    },
                    onRoutingProfileLinkImported = {
                        val ok = dependencies.settings.importRoutingProfileLink(it)
                        if (ok && homeState.isVpnConnected) dependencies.homeViewModel.restartVpnIfRunning()
                        ok
                    },
                    onGeoSourcesChanged = dependencies.settings::setGeoSources,
                    onUpdateGeoNow = dependencies.settings::updateGeoAssetsNow,
                    trafficSettings = trafficSettings,
                    onTrafficChanged = {
                        dependencies.settings.setTrafficSettings(it)
                        if (homeState.isVpnConnected) dependencies.homeViewModel.restartVpnIfRunning()
                    },
                    appBehavior = appBehavior,
                    onAppBehaviorChanged = {
                        dependencies.settings.setAppBehavior(it)
                        if (homeState.isVpnConnected) dependencies.homeViewModel.restartVpnIfRunning()
                    },
                    // Telegram-over-WARP is an Android/desktop feature; the section is hidden on iOS.
                    telegramProxyState = TelegramProxyState.Stopped,
                    language = language,
                    onLanguageChanged = dependencies.settings::setLanguage,
                    updateSettings = updateSettings,
                    updateStatusText = updateStatusText,
                    updateDownloadProgress = updateDownloadProgress,
                    subscriptions = iosSubscriptionItems(dependencies.locationViewModel.locations.toList()),
                    enabled = !homeState.isVpnLoading,
                    isConnectionActive = homeState.isVpnConnected,
                    onDismiss = { isAppSettingsOpen = false },
                    onCopyConfigClick = {
                        dependencies.homeViewModel.onCopyFullConfigClicked()
                    },
                    onSaveLogsClick = {
                        dependencies.homeViewModel.onSaveLogsToFile(
                            target = dependencies.homeViewModel.suggestedLogsFileName(),
                            onSaved = platformBridge::showMessage,
                            onError = platformBridge::showMessage
                        )
                    },
                    onShareLogsClick = {
                        dependencies.homeViewModel.onShareLogs(
                            onShared = platformBridge::showMessage,
                            onError = platformBridge::showMessage
                        )
                    },
                    onUpdateIntervalSelected = { hours ->
                        scope.launch {
                            saveUpdateSettings(updateSettings.copy(intervalHours = hours))
                        }
                    },
                    onCheckUpdatesClick = { checkUpdate(manual = true) },
                    onSubscriptionShareClick = { url ->
                        platformBridge.shareText("Subscription", ConfigShareService.subscriptionQrText(url))
                    },
                    onSubscriptionRefreshClick = { url ->
                        dependencies.homeViewModel.refreshSubscription(url) { updatedCount ->
                            reloadLocationsAfterImport {
                                dependencies.homeViewModel.restartVpnIfRunning()
                                platformBridge.showMessage(
                                    if (updatedCount > 0) "Subscription updated" else "Subscription not updated"
                                )
                            }
                        }
                    },
                    onDynamicThemeChanged = {},
                    onLightThemeChanged = dependencies.settings::setLightTheme,
                    onAccentColorSelected = dependencies.settings::setAccentColor,
                    onTextColorSelected = dependencies.settings::setTextColor,
                    onBackgroundColorSelected = dependencies.settings::setBackgroundColor,
                    onModeSelected = {},
                    // The host is fixed at 127.0.0.1 on iOS (the listener lives in the tunnel
                    // process), so only the credentials and the port are applied.
                    onProxySettingsSaved = { _, username, password, port ->
                        dependencies.vpnManager.updateSocksProxySettings(username, password, port)
                        if (homeState.isVpnConnected) {
                            dependencies.homeViewModel.restartVpnIfRunning()
                        }
                    },
                    onProxyPasswordRegenerated = {
                        dependencies.vpnManager.regenerateSocksProxyPassword()
                        if (homeState.isVpnConnected) {
                            dependencies.homeViewModel.restartVpnIfRunning()
                        }
                    },
                    onSecuredProxyChanged = { on ->
                        // Turning it off drops the credentials; turning it on seeds a password so the
                        // form is not empty.
                        val user = if (on) socksProxySettings.username.ifBlank { "yptun" } else ""
                        val pass = if (on) socksProxySettings.password.ifBlank { randomProxyPassword() } else ""
                        dependencies.vpnManager.updateSocksProxySettings(user, pass, socksProxySettings.port)
                        if (homeState.isVpnConnected) {
                            dependencies.homeViewModel.restartVpnIfRunning()
                        }
                    },
                    // Split tunneling is hidden on iOS (see above) — nothing can reach these.
                    onSplitTunnelModeSelected = {},
                    onSplitTunnelAppToggled = { _, _ -> },
                    onSplitTunnelAppsSelected = { _, _ -> }
                )
            }

            updateOffer?.let { info ->
                ApplicationUpdateOfferSheet(
                    info = info,
                    downloadProgress = updateDownloadProgress,
                    onLater = { laterUpdate(info) },
                    onDownload = { downloadUpdate(info) }
                )
            }
        }
    }
}

/** Seed for the local SOCKS password when the user switches credentials on (no UUID on Native). */
private fun randomProxyPassword(): String {
    val alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return buildString { repeat(14) { append(alphabet[kotlin.random.Random.nextInt(alphabet.length)]) } }
}

private fun iosSubscriptionItems(items: List<LocationItem>): List<SubscriptionShareItem> {
    return items
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
        .map { (url, locations) ->
            val metadata = locations.firstNotNullOfOrNull { it.metadata?.subscription }
            SubscriptionShareItem(
                url = url,
                name = metadata?.name?.takeIf { it.isNotBlank() }
                    ?: locations.first().fullName,
                updateIntervalHours = metadata?.updateIntervalHours,
                lastRefreshAtEpochMs = metadata?.lastRefreshAtEpochMs,
                locationCount = locations.size
            )
        }
}
