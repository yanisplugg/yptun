import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.awt.awtEventOrNull
import org.olcbox.app.desktop.DesktopElevation
import org.olcbox.app.desktop.GlobalHotkey
import org.olcbox.app.desktop.HotkeyBinding
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.security.SecureRandom
import kotlin.math.min
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.data.datasource.JvmLocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.exporter.JvmLogExporter
import org.olcbox.app.data.identity.PersistentDeviceIdentityProvider
import org.olcbox.app.data.importer.JvmConfigImporter
import org.olcbox.app.data.share.ConfigShareService
import org.olcbox.app.data.share.SubscriptionShareItem
import org.olcbox.app.ui.OlcboxAppContent
import org.olcbox.app.ui.activities.AppSettingsSheet
import org.olcbox.app.vpn.AndroidSocksProxySettings
import org.olcbox.app.vpn.AndroidSplitTunnelSettings
import org.olcbox.app.ui.components.ApplicationSocksProxySettings
import org.olcbox.app.ui.components.ApplicationSettingsSheet
import org.olcbox.app.ui.components.ApplicationUpdateOfferSheet
import org.olcbox.app.ui.features.home.HomeScreenViewModel
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.ui.features.locations.LocationViewModel
import org.olcbox.app.ui.navigation.AppScreen
import org.olcbox.app.ui.theme.AppTheme
import org.olcbox.app.update.AppUpdateInfo
import org.olcbox.app.update.AppUpdateSettings
import org.olcbox.app.update.AppUpdateService
import org.olcbox.app.update.DesktopUpdateOutcome
import org.olcbox.app.update.JvmUpdateInstaller
import org.olcbox.app.update.JvmUpdateSettingsStore
import org.olcbox.app.update.identity
import org.olcbox.app.update.isDownloaded
import org.olcbox.app.update.isUpdateCheckDue
import org.olcbox.app.update.shouldShowOffer
import org.olcbox.app.vpn.DesktopSocksProxySettings
import org.olcbox.app.vpn.DesktopVpnManager
import org.olcbox.app.vpn.JvmDesktopSocksProxySettingsStore

private class DesktopAppDependencies {
    private val locationsDataSource = JvmLocationsDataSourceImpl()
    val configImporter = JvmConfigImporter()

    val locationsRepository = LocationsRepositoryImpl(locationsDataSource)
    val updateService = AppUpdateService(
        deviceIdentityProvider = PersistentDeviceIdentityProvider(locationsDataSource)
    )
    val updateSettingsStore = JvmUpdateSettingsStore()
    val updateInstaller = JvmUpdateInstaller()
    val socksProxySettingsStore = JvmDesktopSocksProxySettingsStore()

    val settings = org.olcbox.app.vpn.desktop.DesktopSettingsController()

    val vpnManager = DesktopVpnManager(locationsRepository).also { manager ->
        manager.connectionModeProvider = { settings.connectionMode.value }
        // Gate the 2s tunnel-counter sampling on the "speed on home" setting, so the default-off
        // toggle costs nothing and flipping it mid-session takes effect on the next tick.
        manager.speedSamplingProvider = { settings.appBehavior.value.showSpeedOnHome }
    }

    /**
     * Telegram-over-WARP proxy: its own AmneziaWG tunnel + local SOCKS5, independent of the main VPN
     * (so it keeps working whether or not the tunnel is up). Logs land in the same in-app journal.
     */
    val telegramProxy = org.olcbox.app.vpn.telegram.DesktopTelegramProxy { line ->
        vpnManager.appendLog(line)
    }

    val homeViewModel = HomeScreenViewModel(
        vpnManager = vpnManager,
        locationsRepository = locationsRepository,
        configImporter = configImporter,
        logExporter = JvmLogExporter()
    )

    val locationViewModel = LocationViewModel(locationsRepository)

    fun close() {
        telegramProxy.close()
        vpnManager.close()
    }
}

private const val WINDOWS_ELEVATED_START_ARGUMENT = "--olcbox-start-vpn-after-elevation"

/**
 * Keeps an AWT window's own background on the theme colour.
 *
 * Compose Desktop draws into a plain [java.awt.Window] whose background is white until Skia paints —
 * which is a visible white flash every time a new window appears (the hotkey / My-IP dialogs) and
 * during resizes. Compose never touches it, so it is set here and re-set whenever the theme changes.
 */
@Composable
private fun PaintNativeWindowBackground(window: java.awt.Window, color: Color) {
    LaunchedEffect(window, color) {
        runCatching { window.background = java.awt.Color(color.toArgb(), false) }
    }
}

fun main(args: Array<String>) {
    // TUN mode needs administrator rights, and a process cannot acquire them while it runs — it has
    // to come back through UAC. Asking here, before the first window exists, is the difference
    // between one UAC prompt at launch and the app visibly closing and reopening the first time the
    // user presses Connect (worst on the portable build, which then only connected on the second
    // try). Proxy mode needs nothing and is never asked. If this returns true the elevated copy is
    // already starting and this one must go away without ever painting a window.
    if (DesktopElevation.relaunchElevatedForStartup(args)) return
    runApp(args)
}

private fun runApp(args: Array<String>) = application {
    // Configure JNA to find native libraries in resources
    System.setProperty(
        "jna.library.path",
        System.getProperty("jna.library.path", "") +
                File.pathSeparator +
                File(System.getProperty("user.dir"), "native").absolutePath
    )

    val dependencies = remember { DesktopAppDependencies() }
    var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Home) }
    var showDesktopSettings by remember { mutableStateOf(false) }
    var isWindowVisible by remember { mutableStateOf(true) }
    var trayMenuVisible by remember { mutableStateOf(false) }
    var hotkeyBinding by remember { mutableStateOf(loadHotkeyBinding()) }
    var hotkeyDialogVisible by remember { mutableStateOf(false) }
    // True while the "tunnel mode needs administrator rights" confirmation is on screen.
    var tunElevationPrompt by remember { mutableStateOf(false) }
    var showMyIpDialog by remember { mutableStateOf(false) }
    var ipProvider by remember { mutableStateOf(loadIpProvider()) }

    // Global hotkey: start the Win32 listener once; it toggles the VPN from anywhere. No default —
    // only active when the user has bound a combination (persisted in java prefs).
    DisposableEffect(Unit) {
        if (GlobalHotkey.isSupported) {
            GlobalHotkey.start(hotkeyBinding) { dependencies.homeViewModel.ToggleVpn() }
        }
        onDispose { GlobalHotkey.stop() }
    }
    LaunchedEffect(hotkeyBinding) { GlobalHotkey.setBinding(hotkeyBinding) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var updateSettings by remember { mutableStateOf(AppUpdateSettings()) }
    var updateProgress by remember { mutableStateOf<Float?>(null) }
    var updateOffer by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var sharePayload by remember { mutableStateOf<Pair<String, String>?>(null) }
    var desktopNotice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val trayHomeState by dependencies.homeViewModel.state.collectAsState()

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

            updateMessage = "Checking ${previousSettings.channel.name.lowercase()}..."
            val result = dependencies.updateService.check(previousSettings.channel)
            val checkedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
            val checkedSettings = previousSettings.copy(lastCheckAtEpochMs = checkedAt).normalized()
            saveUpdateSettings(checkedSettings)
            result.fold(
                onSuccess = { info ->
                    if (manual || info.shouldShowOffer(previousSettings, checkedAt)) {
                        if (info.isDownloaded(checkedSettings)) {
                            updateOffer = null
                            updateMessage = "Latest ${info.channel.name.lowercase()} is already downloaded"
                        } else if (info.isUpdateAvailable) {
                            updateOffer = info
                            updateMessage = "${info.channel.name} update found: ${info.version}"
                        } else {
                            updateOffer = null
                            updateMessage = "YPtun is up to date"
                        }
                    } else {
                        updateOffer = null
                        updateMessage = null
                    }
                },
                onFailure = { error ->
                    updateMessage = error.message ?: "Update check failed"
                }
            )
        }
    }

    fun downloadUpdate(info: AppUpdateInfo) {
        scope.launch {
            updateProgress = 0f
            // With a delta published for this hop only a few MB are fetched and the installed jar is
            // patched in place; without one this is the full installer, as before.
            updateMessage = "Downloading ${(info.deltaAsset ?: info.asset).name}..."
            val result = dependencies.updateInstaller.install(info) { progress ->
                updateProgress = progress
            }
            updateMessage = result.fold(
                onSuccess = { outcome ->
                    when (outcome) {
                        is DesktopUpdateOutcome.InstallerOpened -> outcome.message
                        is DesktopUpdateOutcome.RestartRequired -> outcome.message
                    }
                },
                onFailure = { error -> "Download failed: ${error.message ?: "unknown error"}" }
            )
            if (result.isSuccess) {
                saveUpdateSettings(
                    updateSettings.copy(
                        lastSeenUpdateVersion = info.identity(),
                        lastDownloadedUpdateVersion = info.identity()
                    )
                )
                updateOffer = null
            }
            updateProgress = null
            // A staged delta only lands once this process is gone (it holds its own jar open), and
            // the swapper is already waiting on our PID — so shut down the way «Выход» does.
            if (result.getOrNull() is DesktopUpdateOutcome.RestartRequired) {
                dependencies.close()
                exitApplication()
            }
        }
    }

    fun postponeUpdate(info: AppUpdateInfo) {
        scope.launch {
            saveUpdateSettings(updateSettings.copy(lastSeenUpdateVersion = info.identity()))
            updateOffer = null
        }
    }

    LaunchedEffect(Unit) {
        val loaded = dependencies.updateSettingsStore.load()
        updateSettings = loaded
        dependencies.vpnManager.updateSocksProxySettings(dependencies.socksProxySettingsStore.load())
        checkUpdate(manual = false)
        if (WINDOWS_ELEVATED_START_ARGUMENT in args) {
            dependencies.homeViewModel.loadCurrentConfig {
                dependencies.homeViewModel.ToggleVpn()
            }
        }
    }

    LaunchedEffect(desktopNotice) {
        if (desktopNotice != null) {
            delay(1_800)
            desktopNotice = null
        }
    }

    val trayRussian = org.olcbox.app.ui.i18n.LocalizationState.effective ==
        org.olcbox.app.ui.i18n.AppLanguage.Russian
    val trayConnected = trayHomeState.isVpnConnected
    val trayLoading = trayHomeState.isVpnLoading
    // AWT tray menus can't render flag/emoji glyphs (they show as □□□), so keep only readable text.
    val trayLocationName = trayHomeState.selectedLocation?.fullName
        ?.filter { it.isLetterOrDigit() || it == ' ' || it in "-_.·()[]/" }
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    val trayStatusText = when {
        trayConnected -> (if (trayRussian) "● Подключено" else "● Connected") +
            (trayLocationName?.let { " · $it" } ?: "")
        trayLoading -> if (trayRussian) "○ Подключение…" else "○ Connecting…"
        else -> if (trayRussian) "○ Отключено" else "○ Disconnected"
    }
    // Raw AWT tray icon so we can open OUR custom menu on RIGHT-click (Compose's Tray only exposes a
    // left-click action + a native right-click menu). Left-click shows/focuses the main window.
    val trayAnchor = remember { java.awt.Point(0, 0) }
    val awtTrayIcon = remember { mutableStateOf<java.awt.TrayIcon?>(null) }
    val trayBaseImage = remember { loadTrayBaseImage() }
    DisposableEffect(Unit) {
        val systemTray = runCatching { java.awt.SystemTray.getSystemTray() }.getOrNull()
        var icon: java.awt.TrayIcon? = null
        if (systemTray != null) {
            val img = composeTrayImage(trayBaseImage, java.awt.Color(0x8E, 0x8E, 0x93))
                ?: runCatching {
                    java.awt.Toolkit.getDefaultToolkit()
                        .getImage(DesktopAppDependencies::class.java.getResource("/LinuxIcon.png"))
                }.getOrNull()
            if (img != null) {
                icon = java.awt.TrayIcon(img, "YPtun").apply {
                    isImageAutoSize = true
                    addMouseListener(object : java.awt.event.MouseAdapter() {
                        override fun mouseReleased(e: java.awt.event.MouseEvent) {
                            if (e.isPopupTrigger || e.button == java.awt.event.MouseEvent.BUTTON3) {
                                trayAnchor.setLocation(e.x, e.y)
                                trayMenuVisible = true
                            } else if (e.button == java.awt.event.MouseEvent.BUTTON1) {
                                isWindowVisible = true
                            }
                        }
                    })
                }
                runCatching { systemTray.add(icon) }
                awtTrayIcon.value = icon
            }
        }
        onDispose {
            icon?.let { ic -> runCatching { systemTray?.remove(ic) } }
            awtTrayIcon.value = null
        }
    }
    // Keep the tray tooltip + status-dot color in sync with the connection state.
    LaunchedEffect(trayStatusText, trayConnected, trayLoading) {
        val ic = awtTrayIcon.value ?: return@LaunchedEffect
        ic.toolTip = trayStatusText.removePrefix("● ").removePrefix("○ ")
        val badge = when {
            trayConnected -> java.awt.Color(0x34, 0xC7, 0x59) // green
            trayLoading -> java.awt.Color(0xFF, 0x9F, 0x0A)   // amber
            else -> java.awt.Color(0x8E, 0x8E, 0x93)          // grey
        }
        composeTrayImage(trayBaseImage, badge)?.let { ic.image = it }
    }

    // Custom Steam-style tray menu: an undecorated, transparent, always-on-top Compose window anchored
    // at the cursor (clamped to the work area), themed like the app. Dismisses on focus loss or click.
    if (trayMenuVisible) {
        val trayDynamicTheme by dependencies.settings.dynamicTheme.collectAsState()
        Window(
            onCloseRequest = { trayMenuVisible = false },
            visible = true,
            undecorated = true,
            transparent = true,
            resizable = false,
            alwaysOnTop = true,
            focusable = true,
            state = rememberWindowState(
                size = DpSize(TRAY_MENU_WIDTH, trayMenuHeight(hasLocationName = trayLocationName != null))
            ),
        ) {
            LaunchedEffect(Unit) {
                runCatching {
                    val area = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    // Anchor the menu's bottom-right near the click, clamped onto the work area.
                    val x = (trayAnchor.x - window.width)
                        .coerceIn(area.x, area.x + area.width - window.width)
                    val y = (trayAnchor.y - window.height - 4)
                        .coerceIn(area.y, area.y + area.height - window.height)
                    window.setLocation(x, y)
                    window.isAlwaysOnTop = true
                    window.toFront()
                    window.requestFocus()
                }
            }
            DisposableEffect(Unit) {
                val listener = object : java.awt.event.WindowFocusListener {
                    override fun windowGainedFocus(e: java.awt.event.WindowEvent?) {}
                    override fun windowLostFocus(e: java.awt.event.WindowEvent?) {
                        trayMenuVisible = false
                    }
                }
                window.addWindowFocusListener(listener)
                onDispose { window.removeWindowFocusListener(listener) }
            }
            AppTheme(useDynamicColor = trayDynamicTheme) {
                TrayMenu(
                    russian = trayRussian,
                    connected = trayConnected,
                    loading = trayLoading,
                    locationName = trayLocationName,
                    canToggle = trayConnected || trayLoading || trayHomeState.canStartVpn,
                    onOpen = { trayMenuVisible = false; isWindowVisible = true },
                    onToggle = { trayMenuVisible = false; dependencies.homeViewModel.ToggleVpn() },
                    onMyIp = { trayMenuVisible = false; showMyIpDialog = true },
                    onHotkey = { trayMenuVisible = false; hotkeyDialogVisible = true },
                    onSettings = {
                        trayMenuVisible = false
                        isWindowVisible = true
                        showDesktopSettings = true
                    },
                    onQuit = {
                        trayMenuVisible = false
                        dependencies.close()
                        exitApplication()
                    },
                )
            }
        }
    }

    // Picking «ТУННЕЛЬ» is the moment administrator rights are asked for — never before. The
    // portable build starts in proxy mode precisely so its first launch touches nothing; the mode is
    // therefore only persisted once the user has agreed to the restart, otherwise a dismissed UAC
    // prompt would leave the app sitting in a mode it cannot run.
    if (tunElevationPrompt) {
        val promptDynamic by dependencies.settings.dynamicTheme.collectAsState()
        val s = org.olcbox.app.ui.i18n.stringsFor(org.olcbox.app.ui.i18n.LocalizationState.effective)
        DesktopConfirmDialog(
            title = s.adminRightsTitle,
            message = s.tunNeedsAdminBody,
            confirmLabel = s.restartAsAdmin,
            dismissLabel = s.cancel,
            useDynamicColor = promptDynamic,
            onConfirm = {
                tunElevationPrompt = false
                dependencies.settings.selectConnectionMode(org.olcbox.app.vpn.AndroidConnectionMode.Tun)
                if (DesktopElevation.relaunchElevatedNow()) {
                    dependencies.close()
                    exitApplication()
                } else {
                    // UAC dismissed (or there is no launcher to relaunch, e.g. gradle :run): stay in
                    // the mode that actually works instead of a TUN that cannot come up.
                    dependencies.settings.selectConnectionMode(org.olcbox.app.vpn.AndroidConnectionMode.Proxy)
                    desktopNotice = s.adminRightsTitle
                }
            },
            onDismiss = { tunElevationPrompt = false },
        )
    }

    // Another VPN client is running and will fight us for the adapter / the system proxy.
    val vpnConflict by dependencies.vpnManager.vpnConflict.collectAsState()
    vpnConflict?.let { conflict ->
        val conflictDynamic by dependencies.settings.dynamicTheme.collectAsState()
        val s = org.olcbox.app.ui.i18n.stringsFor(org.olcbox.app.ui.i18n.LocalizationState.effective)
        DesktopConfirmDialog(
            title = s.otherVpnDetectedTitle,
            message = s.otherVpnDetectedBody(conflict.names.joinToString(", ")),
            confirmLabel = s.closeOtherVpn,
            dismissLabel = s.connectAnyway,
            useDynamicColor = conflictDynamic,
            onConfirm = { conflict.close() },
            onDismiss = { conflict.ignore() },
        )
    }

    if (hotkeyDialogVisible) {
        val hkDynamic by dependencies.settings.dynamicTheme.collectAsState()
        HotkeyCaptureDialog(
            russian = trayRussian,
            current = hotkeyBinding,
            useDynamicColor = hkDynamic,
            onSave = {
                hotkeyBinding = it
                saveHotkeyBinding(it)
                hotkeyDialogVisible = false
            },
            onDismiss = { hotkeyDialogVisible = false },
        )
    }

    if (showMyIpDialog) {
        val ipDynamic by dependencies.settings.dynamicTheme.collectAsState()
        MyIpDialog(
            russian = trayRussian,
            connected = trayConnected,
            initialProvider = ipProvider,
            useDynamicColor = ipDynamic,
            fetchIp = { provider -> dependencies.vpnManager.checkExitIp(provider) },
            onProviderPersist = { ipProvider = it; saveIpProvider(it) },
            onDismiss = { showMyIpDialog = false },
        )
    }

    val windowState = rememberWindowState(width = 430.dp, height = 780.dp)

    // Ctrl+V (Cmd+V) imports a config link from the clipboard from anywhere in the app — the desktop
    // equivalent of the "Вставить ссылку" button, which was the ONLY way in. Declared before the
    // Window so the key handler can call it.
    fun importFromClipboard() {
        dependencies.homeViewModel.onPasteFromClipboard(
            onComplete = {
                dependencies.locationViewModel.loadLocations {
                    dependencies.homeViewModel.loadCurrentConfig()
                }
                desktopNotice = if (trayRussian) "Ссылка импортирована" else "Link imported"
            },
            onError = { message -> desktopNotice = message }
        )
    }

    Window(
        title = "YPtun",
        icon = painterResource("LinuxIcon.png"),
        visible = isWindowVisible,
        state = windowState,
        onCloseRequest = {
            isWindowVisible = false
        },
        // onKeyEvent (not onPreviewKeyEvent): a focused text field consumes Ctrl+V for its own paste
        // first, so editing a config still works and the shortcut only fires when nothing else wants it.
        onKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown && isPasteShortcut(event)) {
                importFromClipboard()
                true
            } else {
                false
            }
        },
    ) {
        window.minimumSize = Dimension(350, 600)

        DisposableEffect(Unit) {
            onDispose {
                dependencies.close()
            }
        }

        val dynamicTheme by dependencies.settings.dynamicTheme.collectAsState()

        AppTheme(useDynamicColor = dynamicTheme) {
            // The AWT frame under the Compose surface is white by default; it is what shows during a
            // resize and for the frame or two before Skia has drawn. Keep it on the theme colour.
            PaintNativeWindowBackground(window, MaterialTheme.colorScheme.background)
            val logs by dependencies.homeViewModel.logs.collectAsState()
            val homeState by dependencies.homeViewModel.state.collectAsState()
            val socksProxySettings by dependencies.vpnManager.socksProxySettings.collectAsState()
            val mainConnectionMode by dependencies.settings.connectionMode.collectAsState()
            // Drives the locations list exactly like AndroidMainScreen: collapsed/pinned/ping-sorted
            // subscription groups, folders, two-column layout, the "Авто" button… Without it the
            // desktop list silently ran on the parameter defaults (nothing ever collapsed).
            val appBehavior by dependencies.settings.appBehavior.collectAsState()
            val telegramProxyState by dependencies.telegramProxy.state.collectAsState()
            // The Telegram-over-WARP proxy follows its toggle, including across restarts (it is
            // deliberately independent of the VPN connection state).
            LaunchedEffect(appBehavior.telegramProxyEnabled) {
                if (appBehavior.telegramProxyEnabled) {
                    dependencies.telegramProxy.start()
                } else {
                    dependencies.telegramProxy.stop()
                }
            }
            // Happ-style wide layout: with enough window width the locations move to a left pane.
            val isWideWindow = windowState.size.width >= 700.dp

            fun reloadLocationsAfterImport(onComplete: () -> Unit = {}) {
                dependencies.locationViewModel.loadLocations {
                    dependencies.homeViewModel.loadCurrentConfig(onComplete)
                }
            }

            // The locations list reads these appearance settings through CompositionLocals, exactly
            // like AndroidMainScreen provides them. Desktop never did, so every one of them silently
            // ran on the CompositionLocal default: "speed on home", "subscription expiry", "alive
            // count", "hide endpoint when a description exists" and the ping-result format were all
            // dead toggles (the ping default even disagreed with AppBehaviorSettings').
            val liveSpeed by dependencies.vpnManager.speed.collectAsState()
            androidx.compose.runtime.CompositionLocalProvider(
                org.olcbox.app.ui.features.locations.components.LocalPingResultDisplay provides
                    appBehavior.pingResultDisplay,
                org.olcbox.app.ui.features.locations.components.LocalShowSubscriptionExpiry provides
                    appBehavior.showSubscriptionExpiry,
                org.olcbox.app.ui.features.locations.components.LocalShowSubscriptionAliveCount provides
                    appBehavior.showSubscriptionAliveCount,
                org.olcbox.app.ui.features.locations.components.LocalHideEndpointWhenDescription provides
                    appBehavior.hideEndpointWhenDescription,
                org.olcbox.app.ui.features.locations.components.LocalConnectedSpeed provides
                    (if (appBehavior.showSpeedOnHome && homeState.isVpnConnected) liveSpeed else null),
            ) {
            // Compose Desktop windows have no background of their own: the AWT frame is bare white
            // until Skia paints, and any frame where the content is not fully opaque — every screen
            // crossfade, every sheet that fades in — shows that white through. Painting the theme
            // background here is what turns those into proper transitions instead of a flash.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                OlcboxAppContent(
                    homeViewModel = dependencies.homeViewModel,
                    locationViewModel = dependencies.locationViewModel,
                    currentScreen = currentScreen,
                    onNavigate = { screen ->
                        currentScreen = screen
                    },
                    onToggleClick = {
                        dependencies.homeViewModel.ToggleVpn()
                    },
                    onImportFileRequested = {
                        chooseConfigFile(window)?.let { file ->
                            dependencies.homeViewModel.onFileSelected(file) {
                                reloadLocationsAfterImport()
                            }
                        }
                    },
                    onImportFromClipboardRequested = { onImported, onError ->
                        dependencies.homeViewModel.onPasteFromClipboard(
                            onComplete = {
                                reloadLocationsAfterImport(onImported)
                            },
                            onError = onError
                        )
                    },
                    onScanQrRequested = {},
                    onCopyConfigRequested = {
                        dependencies.homeViewModel.onCopyFullConfigClicked()
                    },
                    onShareLocationRequested = { config ->
                        sharePayload = "Location QR" to ConfigShareService.olcRtcUri(config)
                    },
                    onSaveLogsRequested = { onSaved, onError ->
                        chooseSaveFile(
                            owner = window,
                            defaultName = dependencies.homeViewModel.suggestedLogsFileName()
                        )?.let { file ->
                            dependencies.homeViewModel.onSaveLogsToFile(
                                target = file,
                                onSaved = onSaved,
                                onError = onError
                            )
                        }
                    },
                    showAppSettingsButton = true,
                    showSplitTunnelingButton = false,
                    canScanQr = false,
                    onAppSettingsClick = { showDesktopSettings = true },
                    onSplitTunnelingClick = {},
                    confirmBeforeDelete = appBehavior.confirmBeforeDelete,
                    allowVpsAutoInstall = appBehavior.allowVpsAutoInstall,
                    pingParallelism = appBehavior.effectivePingParallelism(),
                    collapsedGroups = appBehavior.collapsedSubscriptionGroups,
                    pinnedGroups = appBehavior.pinnedSubscriptionGroups,
                    pingSortedGroups = appBehavior.pingSortedSubscriptionGroups,
                    pingSortDescendingGroups = appBehavior.pingSortDescendingSubscriptionGroups,
                    pinnedCustomLocations = appBehavior.pinnedCustomLocations,
                    customLocationsPingSorted = appBehavior.customLocationsPingSorted,
                    customLocationsPingSortDescending = appBehavior.customLocationsPingSortDescending,
                    twoColumns = appBehavior.twoColumnLayout,
                    showAutoButton = appBehavior.showAutoButton,
                    onToggleGroupCollapsed = { key ->
                        val current = appBehavior.collapsedSubscriptionGroups
                        val updated = if (key in current) current - key else current + key
                        dependencies.settings.setAppBehavior(
                            appBehavior.copy(collapsedSubscriptionGroups = updated)
                        )
                    },
                    onToggleGroupPinned = { key ->
                        val current = appBehavior.pinnedSubscriptionGroups
                        val updated = if (key in current) current - key else current + key
                        dependencies.settings.setAppBehavior(
                            appBehavior.copy(pinnedSubscriptionGroups = updated)
                        )
                    },
                    onToggleGroupPingSort = { key ->
                        // Cycle: off → ascending → descending → off (same as Android).
                        val sorted = appBehavior.pingSortedSubscriptionGroups
                        val desc = appBehavior.pingSortDescendingSubscriptionGroups
                        dependencies.settings.setAppBehavior(
                            when {
                                key !in sorted -> appBehavior.copy(
                                    pingSortedSubscriptionGroups = sorted + key,
                                    pingSortDescendingSubscriptionGroups = desc - key,
                                )
                                key !in desc -> appBehavior.copy(
                                    pingSortDescendingSubscriptionGroups = desc + key
                                )
                                else -> appBehavior.copy(
                                    pingSortedSubscriptionGroups = sorted - key,
                                    pingSortDescendingSubscriptionGroups = desc - key,
                                )
                            }
                        )
                    },
                    onToggleCustomLocationPinned = { id ->
                        val current = appBehavior.pinnedCustomLocations
                        val updated = if (id in current) current - id else current + id
                        dependencies.settings.setAppBehavior(
                            appBehavior.copy(pinnedCustomLocations = updated)
                        )
                    },
                    onToggleCustomLocationsPingSort = {
                        dependencies.settings.setAppBehavior(
                            when {
                                !appBehavior.customLocationsPingSorted -> appBehavior.copy(
                                    customLocationsPingSorted = true,
                                    customLocationsPingSortDescending = false,
                                )
                                !appBehavior.customLocationsPingSortDescending ->
                                    appBehavior.copy(customLocationsPingSortDescending = true)
                                else -> appBehavior.copy(
                                    customLocationsPingSorted = false,
                                    customLocationsPingSortDescending = false,
                                )
                            }
                        )
                    },
                    customGroups = appBehavior.customGroups,
                    onCreateFolder = { name, memberKeys ->
                        val folder = org.olcbox.app.data.model.CustomGroup(
                            id = "folder_${kotlin.random.Random.nextInt(100_000, 999_999)}",
                            name = name,
                            members = memberKeys
                        )
                        // Keep each item in only one folder.
                        val cleaned = appBehavior.customGroups.map { g ->
                            g.copy(members = g.members - memberKeys.toSet())
                        }
                        dependencies.settings.setAppBehavior(
                            appBehavior.copy(customGroups = cleaned + folder)
                        )
                    },
                    onRenameFolder = { id, name ->
                        val updated = appBehavior.customGroups.map {
                            if (it.id == id) it.copy(name = name) else it
                        }
                        dependencies.settings.setAppBehavior(appBehavior.copy(customGroups = updated))
                    },
                    onDeleteFolder = { id ->
                        dependencies.settings.setAppBehavior(
                            appBehavior.copy(customGroups = appBehavior.customGroups.filterNot { it.id == id })
                        )
                    },
                    onAddToFolder = { id, memberKeys ->
                        val keySet = memberKeys.toSet()
                        val updated = appBehavior.customGroups.map { g ->
                            when (g.id) {
                                id -> g.copy(members = (g.members + memberKeys).distinct())
                                else -> g.copy(members = g.members - keySet)
                            }
                        }
                        dependencies.settings.setAppBehavior(appBehavior.copy(customGroups = updated))
                    },
                    onRemoveFromFolder = { memberKeys ->
                        val keySet = memberKeys.toSet()
                        val updated = appBehavior.customGroups.map { it.copy(members = it.members - keySet) }
                        dependencies.settings.setAppBehavior(appBehavior.copy(customGroups = updated))
                    },
                    onToggleFolderPinned = { id ->
                        val updated = appBehavior.customGroups.map {
                            if (it.id == id) it.copy(pinned = !it.pinned) else it
                        }
                        dependencies.settings.setAppBehavior(appBehavior.copy(customGroups = updated))
                    },
                    onToggleFolderCollapsed = { id ->
                        val updated = appBehavior.customGroups.map {
                            if (it.id == id) it.copy(collapsed = !it.collapsed) else it
                        }
                        dependencies.settings.setAppBehavior(appBehavior.copy(customGroups = updated))
                    },
                    wideLayout = isWideWindow,
                    extraConnectContent = {
                        DesktopModeSwitch(
                            mode = mainConnectionMode,
                            onModeSelected = { mode ->
                                if (needsElevationFor(mode)) {
                                    tunElevationPrompt = true
                                } else {
                                    dependencies.settings.selectConnectionMode(mode)
                                    if (homeState.isVpnConnected || homeState.isVpnLoading) {
                                        dependencies.homeViewModel.restartVpnIfRunning()
                                    }
                                }
                            }
                        )
                    }
                )

                if (showDesktopSettings) {
                    // The full Android settings UI, ported to the JVM: routing rules + Happ
                    // routing profiles, traffic, theme colors, language, ping, experimental, etc.
                    val routing by dependencies.settings.routing.collectAsState()
                    val routingProfilesState by dependencies.settings.routingProfiles.collectAsState()
                    val trafficSettings by dependencies.settings.traffic.collectAsState()
                    val geoUpdateStatus by dependencies.settings.geoUpdateStatus.collectAsState()
                    val language by dependencies.settings.language.collectAsState()
                    val connectionMode by dependencies.settings.connectionMode.collectAsState()
                    val splitTunnelSettings by dependencies.settings.splitTunnel.collectAsState()
                    val installedApps = remember(showDesktopSettings) { dependencies.settings.installedApps() }
                    var hwid by remember { mutableStateOf("") }
                    LaunchedEffect(Unit) {
                        hwid = runCatching { dependencies.locationsRepository.getDeviceIdentity() }.getOrDefault("")
                    }

                    AppSettingsSheet(
                        selectedMode = connectionMode,
                        proxySettings = AndroidSocksProxySettings(
                            host = socksProxySettings.host,
                            port = socksProxySettings.port,
                            username = socksProxySettings.username,
                            password = socksProxySettings.password
                        ),
                        splitTunnelSettings = splitTunnelSettings,
                        installedApps = installedApps,
                        logs = logs,
                        dynamicThemeEnabled = dynamicTheme,
                        hwid = hwid,
                        routing = routing,
                        onRoutingChanged = dependencies.settings::setRouting,
                        routingProfilesState = routingProfilesState,
                        geoUpdateStatus = geoUpdateStatus,
                        onRoutingProfileSaved = { dependencies.settings.saveRoutingProfile(it) },
                        onRoutingProfileDeleted = dependencies.settings::deleteRoutingProfile,
                        onGlobalRoutingProfileChanged = dependencies.settings::setGlobalRoutingProfile,
                        onRoutingProfileLinkImported = dependencies.settings::importRoutingProfileLink,
                        onGeoSourcesChanged = dependencies.settings::setGeoSources,
                        onUpdateGeoNow = dependencies.settings::updateGeoAssetsNow,
                        trafficSettings = trafficSettings,
                        onTrafficChanged = dependencies.settings::setTrafficSettings,
                        appBehavior = appBehavior,
                        onAppBehaviorChanged = dependencies.settings::setAppBehavior,
                        telegramProxyState = telegramProxyState,
                        language = language,
                        onLanguageChanged = dependencies.settings::setLanguage,
                        updateSettings = updateSettings,
                        updateStatusText = updateMessage,
                        updateDownloadProgress = updateProgress,
                        subscriptions = desktopSubscriptionItems(dependencies.locationViewModel.locations.toList()),
                        enabled = !homeState.isVpnLoading,
                        isConnectionActive = homeState.isVpnConnected,
                        onDismiss = { showDesktopSettings = false },
                        onCopyConfigClick = {
                            dependencies.homeViewModel.onCopyFullConfigClicked()
                            desktopNotice = "Copied"
                        },
                        onSaveLogsClick = {
                            chooseSaveFile(
                                owner = window,
                                defaultName = dependencies.homeViewModel.suggestedLogsFileName()
                            )?.let { file ->
                                dependencies.homeViewModel.onSaveLogsToFile(
                                    target = file,
                                    onSaved = { message -> updateMessage = message },
                                    onError = { message -> updateMessage = message }
                                )
                            }
                        },
                        onShareLogsClick = {
                            dependencies.homeViewModel.onShareLogs(
                                onShared = { message -> updateMessage = message },
                                onError = { message -> updateMessage = message }
                            )
                        },
                        onUpdateIntervalSelected = { hours ->
                            scope.launch {
                                saveUpdateSettings(updateSettings.copy(intervalHours = hours))
                            }
                        },
                        onCheckUpdatesClick = { checkUpdate(manual = true) },
                        onSubscriptionShareClick = { url ->
                            sharePayload = "Subscription QR" to ConfigShareService.subscriptionQrText(url)
                        },
                        onSubscriptionRefreshClick = { url ->
                            dependencies.homeViewModel.refreshSubscription(url) { updatedCount ->
                                reloadLocationsAfterImport {
                                    dependencies.homeViewModel.restartVpnIfRunning()
                                    updateMessage = if (updatedCount > 0) {
                                        "Subscription updated"
                                    } else {
                                        "Subscription not updated"
                                    }
                                }
                            }
                        },
                        onDynamicThemeChanged = dependencies.settings::setDynamicTheme,
                        onAccentColorSelected = dependencies.settings::setAccentColor,
                        onTextColorSelected = dependencies.settings::setTextColor,
                        onBackgroundColorSelected = dependencies.settings::setBackgroundColor,
                        onModeSelected = { mode ->
                            if (needsElevationFor(mode)) {
                                tunElevationPrompt = true
                            } else {
                                dependencies.settings.selectConnectionMode(mode)
                                if (homeState.isVpnConnected) {
                                    dependencies.homeViewModel.restartVpnIfRunning()
                                }
                            }
                        },
                        onProxySettingsSaved = { host, username, password, port ->
                            val newSettings = socksProxySettings.copy(
                                host = host,
                                port = port,
                                username = username,
                                password = password
                            ).normalized()
                            dependencies.vpnManager.updateSocksProxySettings(newSettings)
                            scope.launch {
                                dependencies.socksProxySettingsStore.save(newSettings)
                            }
                            desktopNotice = "SOCKS proxy saved"
                            if (homeState.isVpnConnected) {
                                dependencies.homeViewModel.restartVpnIfRunning()
                            }
                        },
                        onProxyPasswordRegenerated = {
                            val newSettings = socksProxySettings.copy(
                                password = generateDesktopProxyPassword()
                            ).normalized()
                            dependencies.vpnManager.updateSocksProxySettings(newSettings)
                            scope.launch {
                                dependencies.socksProxySettingsStore.save(newSettings)
                            }
                            desktopNotice = "Password regenerated"
                            if (homeState.isVpnConnected) {
                                dependencies.homeViewModel.restartVpnIfRunning()
                            }
                        },
                        onSplitTunnelModeSelected = { mode ->
                            dependencies.settings.selectSplitTunnelMode(mode)
                            if (homeState.isVpnConnected) dependencies.homeViewModel.restartVpnIfRunning()
                        },
                        onSplitTunnelAppToggled = { list, processName ->
                            dependencies.settings.toggleSplitTunnelApp(list, processName)
                            if (homeState.isVpnConnected) dependencies.homeViewModel.restartVpnIfRunning()
                        },
                        onSplitTunnelAppsSelected = { list, processes ->
                            dependencies.settings.setSplitTunnelApps(list, processes)
                            if (homeState.isVpnConnected) dependencies.homeViewModel.restartVpnIfRunning()
                        }
                    )
                }

                updateOffer?.let { info ->
                    ApplicationUpdateOfferSheet(
                        info = info,
                        downloadProgress = updateProgress,
                        onLater = { postponeUpdate(info) },
                        onDownload = { downloadUpdate(info) }
                    )
                }

                sharePayload?.let { (title, payload) ->
                    DesktopConfigShareOverlay(
                        title = title,
                        payload = payload,
                        onCopy = {
                            dependencies.configImporter.copyToClipboard(payload)
                            desktopNotice = "Copied"
                        },
                        onDismiss = {
                            sharePayload = null
                        }
                    )
                }

                desktopNotice?.let { notice ->
                    DesktopNotice(
                        text = notice,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp)
                    )
                }
            }
            } // CompositionLocalProvider (locations-list appearance settings)
        }
    }
}

/**
 * True for Ctrl+V / Cmd+V.
 *
 * Matching on [Key.V] alone is not enough: Compose derives `Key` from the AWT key code, which under a
 * non-Latin keyboard layout can be the code of the character the key produces rather than the Latin
 * legend on it. So also accept the AWT extended key code, which stays VK_V for the physical key.
 */
private fun isPasteShortcut(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
    if (!event.isCtrlPressed && !event.isMetaPressed) return false
    if (event.key == Key.V) return true
    val awt = event.awtEventOrNull ?: return false
    return awt.keyCode == java.awt.event.KeyEvent.VK_V ||
        awt.extendedKeyCode.toInt() == java.awt.event.KeyEvent.VK_V
}

/**
 * The desktop "Режим: Прокси | Туннель" switch shown right on the home screen (under the start
 * button). Proxy = PAC system proxy (no admin needed); Tunnel = TUN via wintun (asks for admin).
 * Switching while connected restarts the tunnel in the new mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DesktopModeSwitch(
    mode: org.olcbox.app.vpn.AndroidConnectionMode,
    onModeSelected: (org.olcbox.app.vpn.AndroidConnectionMode) -> Unit
) {
    val isRussian = org.olcbox.app.ui.i18n.LocalizationState.effective == org.olcbox.app.ui.i18n.AppLanguage.Russian
    val proxyLabel = if (isRussian) "ПРОКСИ" else "PROXY"
    val tunLabel = if (isRussian) "ТУННЕЛЬ" else "TUNNEL"

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (isRussian) "Режим" else "Mode",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        SingleChoiceSegmentedButtonRow {
            SegmentedButton(
                selected = mode == org.olcbox.app.vpn.AndroidConnectionMode.Proxy,
                onClick = { onModeSelected(org.olcbox.app.vpn.AndroidConnectionMode.Proxy) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                label = { Text(proxyLabel, fontSize = 12.sp) }
            )
            SegmentedButton(
                selected = mode == org.olcbox.app.vpn.AndroidConnectionMode.Tun,
                onClick = { onModeSelected(org.olcbox.app.vpn.AndroidConnectionMode.Tun) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                label = { Text(tunLabel, fontSize = 12.sp) }
            )
        }
    }
}

@Composable
private fun DesktopConfigShareOverlay(
    title: String,
    payload: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    val s = org.olcbox.app.ui.i18n.LocalStrings.current
    var copied by remember(payload) { mutableStateOf(false) }
    val qrMatrix = remember(payload) {
        runCatching {
            MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, 128, 128)
        }.getOrNull()
    }

    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            val noOpInteraction = remember { MutableInteractionSource() }

            Surface(
                modifier = Modifier
                    .padding(24.dp)
                    .widthIn(max = 440.dp)
                    .clickable(
                        interactionSource = noOpInteraction,
                        indication = null,
                        onClick = {}
                    ),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = title,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (copied) s.copied else s.shareScanOrCopy,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }

                    if (qrMatrix != null) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .size(240.dp),
                            shape = RoundedCornerShape(20.dp),
                            color = Color.White,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            DesktopQrCode(
                                matrix = qrMatrix,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        SelectionContainer {
                            Text(
                                text = payload,
                                modifier = Modifier.padding(14.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(s.closeAction)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                onCopy()
                                copied = true
                            }
                        ) {
                            Text(s.copy)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopQrCode(
    matrix: BitMatrix,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(Color.White)
        val cellSize = min(size.width / matrix.width, size.height / matrix.height)
        val qrWidth = cellSize * matrix.width
        val qrHeight = cellSize * matrix.height
        val left = (size.width - qrWidth) / 2f
        val top = (size.height - qrHeight) / 2f

        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                if (matrix[x, y]) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(left + x * cellSize, top + y * cellSize),
                        size = Size(cellSize, cellSize)
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopNotice(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            color = MaterialTheme.colorScheme.inverseOnSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun DesktopSocksProxySettings.toApplicationSocksProxySettings(): ApplicationSocksProxySettings {
    return ApplicationSocksProxySettings(
        host = host,
        port = port,
        username = username,
        password = password
    )
}

private fun generateDesktopProxyPassword(length: Int = 24): String {
    val random = SecureRandom()
    return buildString(length) {
        repeat(length) {
            append(DESKTOP_PROXY_PASSWORD_ALPHABET[random.nextInt(DESKTOP_PROXY_PASSWORD_ALPHABET.length)])
        }
    }
}

private const val DESKTOP_PROXY_PASSWORD_ALPHABET =
    "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

private fun desktopSubscriptionItems(items: List<LocationItem>): List<SubscriptionShareItem> {
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
        .map { (url, subscriptionItems) ->
            val metadata = subscriptionItems.firstNotNullOfOrNull { it.metadata?.subscription }
            SubscriptionShareItem(
                url = url,
                name = metadata?.name?.takeIf { it.isNotBlank() }
                    ?: subscriptionItems.first().fullName,
                updateIntervalHours = metadata?.updateIntervalHours,
                lastRefreshAtEpochMs = metadata?.lastRefreshAtEpochMs,
                locationCount = subscriptionItems.size
            )
        }
}

private fun chooseConfigFile(owner: Frame): File? {
    val dialog = FileDialog(owner, "Import YPtun Config", FileDialog.LOAD)
    dialog.isVisible = true

    return dialog.files.firstOrNull()
}

private fun chooseSaveFile(owner: Frame, defaultName: String): File? {
    val dialog = FileDialog(owner, "Save YPtun Logs", FileDialog.SAVE)
    dialog.file = defaultName
    dialog.isVisible = true

    val fileName = dialog.file ?: return null
    val directory = dialog.directory ?: return File(fileName)

    return File(directory, fileName)
}

// ---------------------------------------------------------------------------------------
// Custom Steam-style system-tray menu (rendered in our own undecorated Compose window).

private val TRAY_MENU_WIDTH = 260.dp

/** Row height of a [TrayMenuItem]: 1.dp outer + 9.dp inner padding on each side around an 18.dp icon. */
private val TRAY_MENU_ITEM_HEIGHT = 38.dp

/**
 * Exact height of [TrayMenu]'s content. The window is undecorated and fixed-size, so a value that
 * is too small silently CLIPS the bottom row — which is what cut the «Выход» item in half whenever
 * a location name made the status header two lines tall. Derived from the same paddings [TrayMenu]
 * uses, so adding a row here is a one-line change instead of a new magic number.
 */
private fun trayMenuHeight(hasLocationName: Boolean): androidx.compose.ui.unit.Dp {
    val itemCount = 5 // Open, Connect/Disconnect, My IP, Hotkey, Settings
    val header = 20.dp + if (hasLocationName) 34.dp else 19.dp // vertical padding + 1 or 2 text lines
    val rows = TRAY_MENU_ITEM_HEIGHT * (itemCount + 1) // + the Quit row below the second divider
    val dividers = 2.dp
    val columnPadding = 12.dp
    val surfaceInset = 16.dp // the Surface's own 8.dp padding, top + bottom
    return header + rows + dividers + columnPadding + surfaceInset
}

@Composable
private fun TrayMenu(
    russian: Boolean,
    connected: Boolean,
    loading: Boolean,
    locationName: String?,
    canToggle: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onMyIp: () -> Unit,
    onHotkey: () -> Unit,
    onSettings: () -> Unit,
    onQuit: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .shadow(16.dp, RoundedCornerShape(14.dp), clip = false),
        shape = RoundedCornerShape(14.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            // Status header.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val dot = when {
                    connected -> Color(0xFF34C759)
                    loading -> scheme.primary
                    else -> scheme.outline
                }
                Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = when {
                            connected -> if (russian) "Подключено" else "Connected"
                            loading -> if (russian) "Подключение…" else "Connecting…"
                            else -> if (russian) "Отключено" else "Disconnected"
                        },
                        color = scheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (!locationName.isNullOrBlank()) {
                        Text(
                            text = locationName,
                            color = scheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            HorizontalDivider(color = scheme.outlineVariant, modifier = Modifier.padding(horizontal = 6.dp))
            TrayMenuItem(Icons.Outlined.Home, if (russian) "Открыть" else "Open", onClick = onOpen)
            TrayMenuItem(
                Icons.Outlined.PowerSettingsNew,
                when {
                    connected || loading -> if (russian) "Отключиться" else "Disconnect"
                    else -> if (russian) "Подключиться" else "Connect"
                },
                enabled = canToggle,
                tint = if (connected || loading) scheme.primary else scheme.onSurface,
                onClick = onToggle,
            )
            TrayMenuItem(Icons.Outlined.Public, if (russian) "Мой IP" else "My IP", onClick = onMyIp)
            TrayMenuItem(
                Icons.Outlined.Keyboard,
                if (russian) "Горячая клавиша" else "Global hotkey",
                onClick = onHotkey,
            )
            TrayMenuItem(Icons.Outlined.Settings, if (russian) "Настройки" else "Settings", onClick = onSettings)
            HorizontalDivider(color = scheme.outlineVariant, modifier = Modifier.padding(horizontal = 6.dp))
            TrayMenuItem(
                Icons.Outlined.Close,
                if (russian) "Выход" else "Quit",
                tint = scheme.error,
                onClick = onQuit,
            )
        }
    }
}

@Composable
private fun TrayMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val rowTint = if (enabled) tint else scheme.onSurfaceVariant.copy(alpha = 0.4f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (hovered && enabled) scheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .hoverable(interaction, enabled = enabled)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = rowTint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = rowTint, fontSize = 13.sp)
    }
}

// ---------------------------------------------------------------------------------------
// Tray icon helpers: a status-colored dot badge composited onto the brand icon.

private fun loadTrayBaseImage(): java.awt.image.BufferedImage? = runCatching {
    javax.imageio.ImageIO.read(DesktopAppDependencies::class.java.getResource("/LinuxIcon.png"))
}.getOrNull()

private fun composeTrayImage(
    base: java.awt.image.BufferedImage?,
    badge: java.awt.Color,
): java.awt.Image? {
    if (base == null) return null
    val size = 32
    val out = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    g.setRenderingHint(
        java.awt.RenderingHints.KEY_ANTIALIASING,
        java.awt.RenderingHints.VALUE_ANTIALIAS_ON,
    )
    g.setRenderingHint(
        java.awt.RenderingHints.KEY_INTERPOLATION,
        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
    )
    g.drawImage(base, 0, 0, size, size, null)
    // Status dot in the bottom-right with a dark ring so it reads on any wallpaper.
    val d = 13
    val x = size - d - 1
    val y = size - d - 1
    g.color = java.awt.Color(0, 0, 0, 180)
    g.fillOval(x - 1, y - 1, d + 2, d + 2)
    g.color = badge
    g.fillOval(x, y, d, d)
    g.dispose()
    return out
}

// ---------------------------------------------------------------------------------------
// Global hotkey: persistence (java.util.prefs), key mapping, and an in-app capture dialog.

private fun hotkeyPrefs() = java.util.prefs.Preferences.userRoot().node("org/olcbox/yptun")

private fun loadHotkeyBinding(): HotkeyBinding? {
    val p = hotkeyPrefs()
    val vk = p.getInt("hotkey_vk", 0)
    if (vk == 0) return null
    val mod = p.getInt("hotkey_mod", 0)
    val label = p.get("hotkey_label", "")
    return HotkeyBinding(mod, vk, label)
}

private fun saveHotkeyBinding(binding: HotkeyBinding?) {
    val p = hotkeyPrefs()
    if (binding == null) {
        p.remove("hotkey_vk"); p.remove("hotkey_mod"); p.remove("hotkey_label")
    } else {
        p.putInt("hotkey_vk", binding.vk)
        p.putInt("hotkey_mod", binding.modifiers)
        p.put("hotkey_label", binding.label)
    }
    runCatching { p.flush() }
}

/** Build a HotkeyBinding from a Compose key event, or null for modifier-only / no-modifier presses. */
private fun hotkeyFromKeyEvent(e: androidx.compose.ui.input.key.KeyEvent): HotkeyBinding? {
    val vk = e.key.nativeKeyCode
    // Ignore pure modifier keys (Ctrl/Alt/Shift/Win) pressed alone.
    val modKeys = setOf(
        java.awt.event.KeyEvent.VK_CONTROL, java.awt.event.KeyEvent.VK_ALT,
        java.awt.event.KeyEvent.VK_SHIFT, java.awt.event.KeyEvent.VK_META,
        java.awt.event.KeyEvent.VK_WINDOWS,
    )
    if (vk in modKeys || vk == 0) return null
    var mod = 0
    if (e.isCtrlPressed) mod = mod or HotkeyBinding.MOD_CONTROL
    if (e.isAltPressed) mod = mod or HotkeyBinding.MOD_ALT
    if (e.isShiftPressed) mod = mod or HotkeyBinding.MOD_SHIFT
    if (e.isMetaPressed) mod = mod or HotkeyBinding.MOD_WIN
    if (mod == 0) return null // require at least one modifier so it's a sane global hotkey
    val parts = buildList {
        if (e.isCtrlPressed) add("Ctrl")
        if (e.isAltPressed) add("Alt")
        if (e.isShiftPressed) add("Shift")
        if (e.isMetaPressed) add("Win")
        add(java.awt.event.KeyEvent.getKeyText(vk))
    }
    return HotkeyBinding(mod, vk, parts.joinToString("+"))
}

/**
 * True when picking [mode] must go through UAC first: only TUN needs administrator rights, and only
 * when this process does not already have them (the installed build normally elevates at launch).
 */
private fun needsElevationFor(mode: org.olcbox.app.vpn.AndroidConnectionMode): Boolean =
    mode == org.olcbox.app.vpn.AndroidConnectionMode.Tun && DesktopElevation.needsAdministratorForTun()

/**
 * Plain two-button confirmation window, in the app's own theme — used for "tunnel mode needs
 * administrator rights" and "another VPN client is running". Closing the window counts as dismiss,
 * so a prompt can never be left hanging with nothing behind it.
 */
@Composable
private fun DesktopConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    useDynamicColor: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.DialogWindow(
        onCloseRequest = onDismiss,
        state = androidx.compose.ui.window.rememberDialogState(size = DpSize(430.dp, 250.dp)),
        title = title,
    ) {
        AppTheme(useDynamicColor = useDynamicColor) {
            PaintNativeWindowBackground(window, MaterialTheme.colorScheme.surface)
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        title,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text(dismissLabel) }
                        Button(onClick = onConfirm) { Text(confirmLabel) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HotkeyCaptureDialog(
    russian: Boolean,
    current: HotkeyBinding?,
    useDynamicColor: Boolean,
    onSave: (HotkeyBinding?) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.DialogWindow(
        onCloseRequest = onDismiss,
        state = androidx.compose.ui.window.rememberDialogState(size = DpSize(400.dp, 230.dp)),
        title = if (russian) "Горячая клавиша" else "Global hotkey",
    ) {
        AppTheme(useDynamicColor = useDynamicColor) {
            PaintNativeWindowBackground(window, MaterialTheme.colorScheme.surface)
            var captured by remember { mutableStateOf(current) }
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                        .focusRequester(focus)
                        .focusable()
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown) {
                                hotkeyFromKeyEvent(e)?.let { captured = it; return@onPreviewKeyEvent true }
                            }
                            false
                        },
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        if (russian) "Нажмите комбинацию (например, Ctrl+Alt+V) — она переключает VPN из любого приложения."
                        else "Press a combination (e.g. Ctrl+Alt+V) — it toggles the VPN from anywhere.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            captured?.label ?: (if (russian) "Не задано" else "Not set"),
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(onClick = { captured = null }) {
                            Text(if (russian) "Очистить" else "Clear")
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDismiss) {
                            Text(if (russian) "Отмена" else "Cancel")
                        }
                        Button(onClick = { onSave(captured) }) {
                            Text(if (russian) "Сохранить" else "Save")
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// 2ip IP viewer: provider choice (2ip.ru / 2ip.io) + a dialog that shows the current IP in any state.

private fun loadIpProvider(): String =
    hotkeyPrefs().get("ip_provider", "2ip.ru")

private fun saveIpProvider(provider: String) {
    hotkeyPrefs().put("ip_provider", provider)
    runCatching { hotkeyPrefs().flush() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MyIpDialog(
    russian: Boolean,
    connected: Boolean,
    initialProvider: String,
    useDynamicColor: Boolean,
    fetchIp: suspend (String) -> String?,
    onProviderPersist: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.DialogWindow(
        onCloseRequest = onDismiss,
        state = androidx.compose.ui.window.rememberDialogState(size = DpSize(420.dp, 290.dp)),
        title = if (russian) "Мой IP" else "My IP",
    ) {
        AppTheme(useDynamicColor = useDynamicColor) {
            val scheme = MaterialTheme.colorScheme
            PaintNativeWindowBackground(window, scheme.surface)
            var provider by remember { mutableStateOf(initialProvider) }
            var ip by remember { mutableStateOf<String?>(null) }
            var loading by remember { mutableStateOf(true) }
            val scope = rememberCoroutineScope()

            fun refresh() {
                loading = true
                ip = null
                scope.launch {
                    ip = fetchIp(provider)
                    loading = false
                }
            }
            LaunchedEffect(provider) { refresh() }

            Surface(color = scheme.surface, modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // Provider choice.
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        listOf("2ip.ru", "2ip.io").forEachIndexed { i, p ->
                            SegmentedButton(
                                selected = provider == p,
                                onClick = {
                                    if (provider != p) {
                                        provider = p
                                        onProviderPersist(p)
                                    }
                                },
                                shape = SegmentedButtonDefaults.itemShape(i, 2),
                            ) { Text(p) }
                        }
                    }
                    // The IP.
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = scheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = when {
                                    loading -> if (russian) "Загрузка…" else "Loading…"
                                    ip != null -> ip!!
                                    else -> if (russian) "Не удалось получить" else "Failed"
                                },
                                color = scheme.onSurface,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = if (connected) {
                                    if (russian) "Через VPN" else "Through VPN"
                                } else {
                                    if (russian) "Без VPN (реальный IP)" else "No VPN (real IP)"
                                },
                                color = if (connected) Color(0xFF34C759) else scheme.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(onClick = {
                            // Not Desktop.browse: in TUN mode this process is elevated, and a browser
                            // launched from here can't reach the user's already-open session.
                            org.olcbox.app.desktop.DesktopUriLauncher.open("https://$provider/")
                        }) { Text(if (russian) "Открыть в браузере" else "Open in browser") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { refresh() }) {
                            Text(if (russian) "Обновить" else "Refresh")
                        }
                        Button(onClick = onDismiss) {
                            Text(if (russian) "Закрыть" else "Close")
                        }
                    }
                }
            }
        }
    }
}
