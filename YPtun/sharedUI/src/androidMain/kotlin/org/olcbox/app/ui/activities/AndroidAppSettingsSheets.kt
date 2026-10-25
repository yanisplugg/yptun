package org.olcbox.app.ui.activities

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.LightMode
import org.olcbox.app.ui.components.SupportProjectSection
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Language
import org.olcbox.app.ui.components.SettingsCaption
import org.olcbox.app.ui.components.SettingsCard
import org.olcbox.app.ui.components.settingsHighlight
import org.olcbox.app.ui.components.GlobalSettingsHighlight
import org.olcbox.app.ui.components.SettingsSearch
import org.olcbox.app.ui.components.SettingsSearchBar
import org.olcbox.app.ui.components.SettingsSearchEntry
import org.olcbox.app.ui.components.SettingsSearchResults
import org.olcbox.app.ui.components.SettingsCardDivider
import org.olcbox.app.ui.components.SettingsCardRow
import org.olcbox.app.ui.components.SettingsCardToggle
import org.olcbox.app.ui.components.SettingsChoiceChips
import org.olcbox.app.ui.components.SettingsNote
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Science
import org.olcbox.app.data.model.AppBehaviorSettings
import org.olcbox.app.data.model.ProxyCore
import org.olcbox.app.data.model.RoutingRules
import org.olcbox.app.data.model.SingBoxRule
import org.olcbox.app.data.model.TrafficSettings
import org.olcbox.app.ui.i18n.AppLanguage
import org.olcbox.app.ui.i18n.LocalStrings
import org.olcbox.app.ui.i18n.Strings
import org.olcbox.app.ui.i18n.LocalizationState
import org.olcbox.app.ui.i18n.stringsFor
import androidx.compose.ui.graphics.toArgb
import org.olcbox.app.ui.theme.ThemeState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.data.share.SubscriptionShareItem
import org.olcbox.app.update.AppUpdateSettings
import org.olcbox.app.ui.features.home.components.LogLines
import org.olcbox.app.vpn.AndroidConnectionMode
import org.olcbox.app.vpn.telegram.TelegramProxyService
import org.olcbox.app.vpn.telegram.TelegramProxyState
import org.olcbox.app.vpn.AndroidInstalledApp
import org.olcbox.app.vpn.AndroidSocksProxySettings
import org.olcbox.app.vpn.AndroidSplitTunnelList
import org.olcbox.app.vpn.AndroidSplitTunnelMode
import org.olcbox.app.vpn.AndroidSplitTunnelSettings
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppSettingsSheet(
    initialRoute: AppSettingsInitialRoute = AppSettingsInitialRoute.Hub,
    selectedMode: AndroidConnectionMode,
    proxySettings: AndroidSocksProxySettings,
    splitTunnelSettings: AndroidSplitTunnelSettings,
    installedApps: List<AndroidInstalledApp>,
    logs: List<String>,
    dynamicThemeEnabled: Boolean,
    lightThemeEnabled: Boolean,
    hwid: String,
    routing: RoutingRules,
    onRoutingChanged: (RoutingRules) -> Unit,
    routingProfilesState: org.olcbox.app.data.model.RoutingProfilesState,
    geoUpdateStatus: org.olcbox.app.vpn.GeoUpdateStatus?,
    onRoutingProfileSaved: (org.olcbox.app.data.model.RoutingProfile) -> Unit,
    onRoutingProfileDeleted: (String) -> Unit,
    onGlobalRoutingProfileChanged: (String) -> Unit,
    onRoutingProfileLinkImported: (String) -> Boolean,
    onGeoSourcesChanged: (String, String) -> Unit,
    onUpdateGeoNow: () -> Unit,
    trafficSettings: TrafficSettings,
    onTrafficChanged: (TrafficSettings) -> Unit,
    appBehavior: AppBehaviorSettings,
    onAppBehaviorChanged: (AppBehaviorSettings) -> Unit,
    telegramProxyState: TelegramProxyState,
    language: AppLanguage,
    onLanguageChanged: (AppLanguage) -> Unit,
    updateSettings: AppUpdateSettings,
    updateStatusText: String?,
    updateDownloadProgress: Float?,
    subscriptions: List<SubscriptionShareItem>,
    enabled: Boolean,
    isConnectionActive: Boolean,
    onDismiss: () -> Unit,
    onCopyConfigClick: () -> Unit,
    onSaveLogsClick: () -> Unit,
    onShareLogsClick: () -> Unit,
    onUpdateIntervalSelected: (Int) -> Unit,
    onCheckUpdatesClick: () -> Unit,
    onSubscriptionShareClick: (String) -> Unit,
    onSubscriptionRefreshClick: (String) -> Unit,
    onDynamicThemeChanged: (Boolean) -> Unit,
    onLightThemeChanged: (Boolean) -> Unit,
    onAccentColorSelected: (androidx.compose.ui.graphics.Color?) -> Unit,
    onTextColorSelected: (androidx.compose.ui.graphics.Color?) -> Unit,
    onBackgroundColorSelected: (androidx.compose.ui.graphics.Color?) -> Unit,
    onModeSelected: (AndroidConnectionMode) -> Unit,
    onProxySettingsSaved: (String, String, String, Int) -> Unit,
    onProxyPasswordRegenerated: () -> Unit,
    onSplitTunnelModeSelected: (AndroidSplitTunnelMode) -> Unit,
    onSplitTunnelAppToggled: (AndroidSplitTunnelList, String) -> Unit,
    onSplitTunnelAppsSelected: (AndroidSplitTunnelList, Set<String>) -> Unit
) {
    if (appBehavior.legacyUi) {
        org.olcbox.app.ui.activities.legacy.AppSettingsSheet(
            initialRoute = org.olcbox.app.ui.activities.legacy.AppSettingsInitialRoute.valueOf(initialRoute.name),
            selectedMode = selectedMode,
            proxySettings = proxySettings,
            splitTunnelSettings = splitTunnelSettings,
            installedApps = installedApps,
            logs = logs,
            dynamicThemeEnabled = dynamicThemeEnabled,
            lightThemeEnabled = lightThemeEnabled,
            hwid = hwid,
            routing = routing,
            onRoutingChanged = onRoutingChanged,
            routingProfilesState = routingProfilesState,
            geoUpdateStatus = geoUpdateStatus,
            onRoutingProfileSaved = onRoutingProfileSaved,
            onRoutingProfileDeleted = onRoutingProfileDeleted,
            onGlobalRoutingProfileChanged = onGlobalRoutingProfileChanged,
            onRoutingProfileLinkImported = onRoutingProfileLinkImported,
            onGeoSourcesChanged = onGeoSourcesChanged,
            onUpdateGeoNow = onUpdateGeoNow,
            trafficSettings = trafficSettings,
            onTrafficChanged = onTrafficChanged,
            appBehavior = appBehavior,
            onAppBehaviorChanged = onAppBehaviorChanged,
            telegramProxyState = telegramProxyState,
            language = language,
            onLanguageChanged = onLanguageChanged,
            updateSettings = updateSettings,
            updateStatusText = updateStatusText,
            updateDownloadProgress = updateDownloadProgress,
            subscriptions = subscriptions,
            enabled = enabled,
            isConnectionActive = isConnectionActive,
            onDismiss = onDismiss,
            onCopyConfigClick = onCopyConfigClick,
            onSaveLogsClick = onSaveLogsClick,
            onShareLogsClick = onShareLogsClick,
            onUpdateIntervalSelected = onUpdateIntervalSelected,
            onCheckUpdatesClick = onCheckUpdatesClick,
            onSubscriptionShareClick = onSubscriptionShareClick,
            onSubscriptionRefreshClick = onSubscriptionRefreshClick,
            onDynamicThemeChanged = onDynamicThemeChanged,
            onLightThemeChanged = onLightThemeChanged,
            onAccentColorSelected = onAccentColorSelected,
            onTextColorSelected = onTextColorSelected,
            onBackgroundColorSelected = onBackgroundColorSelected,
            onModeSelected = onModeSelected,
            onProxySettingsSaved = onProxySettingsSaved,
            onProxyPasswordRegenerated = onProxyPasswordRegenerated,
            onSplitTunnelModeSelected = onSplitTunnelModeSelected,
            onSplitTunnelAppToggled = onSplitTunnelAppToggled,
            onSplitTunnelAppsSelected = onSplitTunnelAppsSelected
        )
        return
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var route by remember(initialRoute) { mutableStateOf(initialRoute.toRoute()) }
    val searchStrings = LocalStrings.current
    var searchQuery by remember { mutableStateOf("") }
    val searchEntries = remember(searchStrings, appBehavior.experimentalUnlocked) {
        SettingsSearch.index(searchStrings, appBehavior.experimentalUnlocked)
    }
    var autoBypassPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var russianBypassPresetEnabled by remember { mutableStateOf(false) }

    fun closeSheet(afterClose: () -> Unit = {}) {
        scope.launch {
            sheetState.hide()
            onDismiss()
            afterClose()
        }
    }

    BackHandler {
        route = when (route) {
            AppSettingsRoute.Hub -> {
                closeSheet()
                AppSettingsRoute.Hub
            }

            is AppSettingsRoute.AppList -> AppSettingsRoute.SplitTunneling
            AppSettingsRoute.ConnectionMode,
            AppSettingsRoute.SocksProxy,
            AppSettingsRoute.SplitTunneling -> AppSettingsRoute.ConnectionSettings
            AppSettingsRoute.UrlSchemes -> AppSettingsRoute.Advanced
            AppSettingsRoute.Updates,
            AppSettingsRoute.ApplicationLogs -> AppSettingsRoute.About

            else -> AppSettingsRoute.Hub
        }
    }

    // Outside AnimatedContent: the Hub is disposed while a section is open, and its scroll position must outlive that.
    val hubScroll = androidx.compose.foundation.rememberScrollState()

    ModalBottomSheet(
        onDismissRequest = { closeSheet() },
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        AnimatedContent(
            targetState = route,
            transitionSpec = {
                fadeIn(
                    animationSpec = tween(
                        durationMillis = 180,
                        delayMillis = 60,
                        easing = LinearOutSlowInEasing
                    )
                ).togetherWith(
                    fadeOut(
                        animationSpec = tween(
                            durationMillis = 90,
                            easing = FastOutLinearInEasing
                        )
                    )
                ).using(
                    SizeTransform(
                        clip = false,
                        sizeAnimationSpec = { _, _ ->
                            tween(
                                durationMillis = 320,
                                easing = FastOutSlowInEasing
                            )
                        }
                    )
                )
            },
            label = "appSettingsRoute"
        ) { currentRoute ->
            when (currentRoute) {
                AppSettingsRoute.Hub -> AppSettingsHubContent(
                    scrollState = hubScroll,
                    selectedMode = selectedMode,
                    dynamicThemeEnabled = dynamicThemeEnabled,
                    lightThemeEnabled = lightThemeEnabled,
                    appBehavior = appBehavior,
                    language = language,
                    enabled = enabled,
                    onOpen = { route = it },
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    searchEntries = searchEntries,
                    onSearchPick = { e ->
                        val target = settingsRouteFor(e.page)
                        if (target != null) {
                            GlobalSettingsHighlight.target = if (e.row) e.title else null
                            searchQuery = ""
                            route = target
                        }
                    }
                )

                AppSettingsRoute.Language -> LanguageContent(
                    language = language,
                    onBack = { route = AppSettingsRoute.Hub },
                    onLanguageChanged = onLanguageChanged
                )

                AppSettingsRoute.Appearance -> AppearanceContent(
                    dynamicThemeEnabled = dynamicThemeEnabled,
                    lightThemeEnabled = lightThemeEnabled,
                    onDynamicThemeChanged = onDynamicThemeChanged,
                    onLightThemeChanged = onLightThemeChanged,
                    onAccentColorSelected = onAccentColorSelected,
                    onTextColorSelected = onTextColorSelected,
                    onBackgroundColorSelected = onBackgroundColorSelected,
                    onBack = { route = AppSettingsRoute.Hub }
                )

                AppSettingsRoute.Privacy -> PrivacyContent(
                    settings = appBehavior,
                    onBack = { route = AppSettingsRoute.Hub },
                    onChanged = onAppBehaviorChanged
                )

                AppSettingsRoute.Experimental -> ExperimentalContent(
                    settings = appBehavior,
                    onBack = { route = AppSettingsRoute.Hub },
                    onChanged = onAppBehaviorChanged
                )

                AppSettingsRoute.Routing,
                AppSettingsRoute.RoutingProfiles -> RoutingContent(
                    routing = routing,
                    enabled = enabled,
                    onBack = { route = AppSettingsRoute.Hub },
                    onRoutingChanged = onRoutingChanged,
                    routingProfilesState = routingProfilesState,
                    geoStatus = geoUpdateStatus,
                    onSaveProfile = onRoutingProfileSaved,
                    onDeleteProfile = onRoutingProfileDeleted,
                    onSetGlobal = onGlobalRoutingProfileChanged,
                    onImportLink = onRoutingProfileLinkImported,
                    onSetGeoSources = onGeoSourcesChanged,
                    onUpdateGeo = onUpdateGeoNow,
                )

                AppSettingsRoute.Traffic -> TrafficSettingsContent(
                    settings = trafficSettings,
                    enabled = enabled,
                    onBack = { route = AppSettingsRoute.Hub },
                    onTrafficChanged = onTrafficChanged
                )

                AppSettingsRoute.Notifications -> NotificationsSettingsContent(
                    settings = appBehavior,
                    onBack = { route = AppSettingsRoute.Hub },
                    onChanged = onAppBehaviorChanged
                )

                AppSettingsRoute.Advanced -> AdvancedSettingsContent(
                    settings = appBehavior,
                    onBack = { route = AppSettingsRoute.Hub },
                    onChanged = onAppBehaviorChanged,
                    onUrlSchemesClick = { route = AppSettingsRoute.UrlSchemes }
                )

                AppSettingsRoute.About -> AboutContent(
                    hwid = hwid,
                    updateSettings = updateSettings,
                    onBack = { route = AppSettingsRoute.Hub },
                    onUpdatesClick = { route = AppSettingsRoute.Updates },
                    onLogsClick = { route = AppSettingsRoute.ApplicationLogs },
                    onCopyConfigClick = onCopyConfigClick
                )

                AppSettingsRoute.UrlSchemes -> UrlSchemesContent(
                    onBack = { route = AppSettingsRoute.Advanced }
                )

                AppSettingsRoute.ConnectionSettings -> ConnectionSettingsContent(
                    selectedMode = selectedMode,
                    proxySettings = proxySettings,
                    splitTunnelSettings = splitTunnelSettings,
                    appBehavior = appBehavior,
                    onAppBehaviorChanged = onAppBehaviorChanged,
                    telegramProxyState = telegramProxyState,
                    enabled = enabled,
                    onBack = { route = AppSettingsRoute.Hub },
                    onConnectionModeClick = { route = AppSettingsRoute.ConnectionMode },
                    onProxySettingsClick = { route = AppSettingsRoute.SocksProxy },
                    onSplitTunnelingClick = { route = AppSettingsRoute.SplitTunneling }
                )

                AppSettingsRoute.ConnectionMode -> ConnectionModeSettingsContent(
                    selectedMode = selectedMode,
                    enabled = enabled,
                    socksHost = proxySettings.host,
                    socksPort = proxySettings.port,
                    onBack = { route = AppSettingsRoute.ConnectionSettings },
                    onModeSelected = onModeSelected
                )

                AppSettingsRoute.SocksProxy -> SocksProxySettingsContent(
                    proxySettings = proxySettings,
                    enabled = enabled,
                    isConnectionActive = isConnectionActive,
                    onBack = { route = AppSettingsRoute.ConnectionSettings },
                    onProxySettingsSaved = onProxySettingsSaved,
                    onProxyPasswordRegenerated = onProxyPasswordRegenerated
                )

                AppSettingsRoute.SplitTunneling -> SplitTunnelingSettingsContent(
                    settings = splitTunnelSettings,
                    enabled = enabled,
                    isConnectionActive = isConnectionActive,
                    selectedMode = selectedMode,
                    onBack = { route = AppSettingsRoute.ConnectionSettings },
                    onModeSelected = onSplitTunnelModeSelected,
                    onAppListClick = { list -> route = AppSettingsRoute.AppList(list) }
                )

                is AppSettingsRoute.AppList -> SplitTunnelingAppListContent(
                    list = currentRoute.list,
                    settings = splitTunnelSettings,
                    installedApps = installedApps,
                    enabled = enabled,
                    onBack = { route = AppSettingsRoute.SplitTunneling },
                    onAppToggled = onSplitTunnelAppToggled,
                    onAppsSelected = onSplitTunnelAppsSelected,
                    autoBypassPackages = autoBypassPackages,
                    onAutoBypassPackagesChanged = { autoBypassPackages = it },
                    russianBypassPresetEnabled = russianBypassPresetEnabled,
                    onRussianBypassPresetEnabledChanged = { russianBypassPresetEnabled = it }
                )

                AppSettingsRoute.ApplicationLogs -> ApplicationLogsSettingsContent(
                    logs = logs,
                    onBack = { route = AppSettingsRoute.About },
                    onSaveClick = onSaveLogsClick,
                    onShareClick = onShareLogsClick
                )

                AppSettingsRoute.Updates -> UpdatesSettingsContent(
                    settings = updateSettings,
                    statusText = updateStatusText,
                    downloadProgress = updateDownloadProgress,
                    onBack = { route = AppSettingsRoute.About },
                    onIntervalSelected = onUpdateIntervalSelected,
                    onCheckUpdatesClick = onCheckUpdatesClick
                )
            }
        }
    }
}

internal enum class AppSettingsInitialRoute {
    Hub,
    SplitTunneling
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ThemeColorSection(
    // The background/text swatches are dark-canvas colors; with the white theme on they are ignored
    // by AppTheme, so the rows are hidden instead of offering settings that do nothing.
    lightMode: Boolean,
    onAccentColorSelected: (Color?) -> Unit,
    onTextColorSelected: (Color?) -> Unit,
    onBackgroundColorSelected: (Color?) -> Unit
) {
    var showAccentPicker by remember { mutableStateOf(false) }
    var showBackgroundPicker by remember { mutableStateOf(false) }
    var showTextPicker by remember { mutableStateOf(false) }
    val s = LocalStrings.current

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!lightMode) {
            Text(
                text = s.themeColor,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val currentBg = ThemeState.background
                val isBgPreset = currentBg == null || ThemeState.backgroundPresets.contains(currentBg)
                ThemeState.backgroundPresets.forEachIndexed { index, color ->
                    val selected = currentBg == color || (currentBg == null && index == 0)
                    ColorSwatch(color = color, selected = selected) { onBackgroundColorSelected(color) }
                }
                CustomColorSwatch(
                    current = if (!isBgPreset) currentBg else null,
                    onClick = { showBackgroundPicker = true }
                )
            }
        }

        Text(
            text = s.elementColor,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val currentAccent = ThemeState.accent
            val isPreset = currentAccent == null || ThemeState.accentPresets.contains(currentAccent)
            ThemeState.accentPresets.forEachIndexed { index, color ->
                val selected = currentAccent == color || (currentAccent == null && index == 0)
                ColorSwatch(color = color, selected = selected) { onAccentColorSelected(color) }
            }
            CustomColorSwatch(
                current = if (!isPreset) currentAccent else null,
                onClick = { showAccentPicker = true }
            )
        }

        if (!lightMode) {
            Text(
                text = s.textColor,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val currentText = ThemeState.textColor
                val isTextPreset = ThemeState.textPresets.contains(currentText)
                ThemeState.textPresets.forEach { color ->
                    val selected = currentText == color
                    ColorSwatch(
                        color = color ?: MaterialTheme.colorScheme.onSurface,
                        selected = selected
                    ) { onTextColorSelected(color) }
                }
                CustomColorSwatch(
                    current = if (!isTextPreset) currentText else null,
                    onClick = { showTextPicker = true }
                )
            }
        }
    }

    if (showAccentPicker) {
        ColorPickerDialog(
            initial = ThemeState.accent ?: ThemeState.accentPresets.first(),
            onDismiss = { showAccentPicker = false },
            onConfirm = {
                onAccentColorSelected(it)
                showAccentPicker = false
            }
        )
    }

    if (showTextPicker) {
        ColorPickerDialog(
            initial = ThemeState.textColor ?: Color.White,
            onDismiss = { showTextPicker = false },
            onConfirm = {
                onTextColorSelected(it)
                showTextPicker = false
            }
        )
    }

    if (showBackgroundPicker) {
        ColorPickerDialog(
            initial = ThemeState.background ?: ThemeState.backgroundPresets.first(),
            onDismiss = { showBackgroundPicker = false },
            onConfirm = {
                onBackgroundColorSelected(it)
                showBackgroundPicker = false
            }
        )
    }
}

@Composable
private fun CustomColorSwatch(current: Color?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(
                brush = androidx.compose.ui.graphics.Brush.sweepGradient(
                    listOf(
                        Color(0xFFFF5370), Color(0xFFFFD479), Color(0xFF7CF2C0),
                        Color(0xFF80D8FF), Color(0xFFB388FF), Color(0xFFFF5370)
                    )
                ),
                shape = CircleShape
            )
            .border(
                width = if (current != null) 3.dp else 1.dp,
                color = if (current != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                shape = CircleShape
            )
            .clickable(onClick = onClick)
    )
}

@Composable
private fun ColorPickerDialog(
    initial: Color,
    onDismiss: () -> Unit,
    onConfirm: (Color) -> Unit
) {
    val argb = initial.toArgb()
    var r by remember { mutableStateOf(((argb shr 16) and 0xFF).toFloat()) }
    var g by remember { mutableStateOf(((argb shr 8) and 0xFF).toFloat()) }
    var b by remember { mutableStateOf((argb and 0xFF).toFloat()) }
    var hex by remember { mutableStateOf(rgbToHex(r, g, b)) }
    val color = Color(r.toInt(), g.toInt(), b.toInt())

    fun syncHex() { hex = rgbToHex(r, g, b) }
    val s = LocalStrings.current

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(color) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
        title = { Text(s.customColorRgb) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .size(56.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                        .background(color)
                )
                OutlinedTextField(
                    value = hex,
                    onValueChange = { input ->
                        hex = input.uppercase()
                        parseHex(input)?.let { (pr, pg, pb) -> r = pr; g = pg; b = pb }
                    },
                    label = { Text("HEX") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("R  ${r.toInt()}", style = MaterialTheme.typography.labelMedium)
                Slider(value = r, onValueChange = { r = it; syncHex() }, valueRange = 0f..255f)
                Text("G  ${g.toInt()}", style = MaterialTheme.typography.labelMedium)
                Slider(value = g, onValueChange = { g = it; syncHex() }, valueRange = 0f..255f)
                Text("B  ${b.toInt()}", style = MaterialTheme.typography.labelMedium)
                Slider(value = b, onValueChange = { b = it; syncHex() }, valueRange = 0f..255f)
            }
        }
    )
}

private fun rgbToHex(r: Float, g: Float, b: Float): String =
    "#%02X%02X%02X".format(r.toInt(), g.toInt(), b.toInt())

private fun parseHex(input: String): Triple<Float, Float, Float>? {
    val h = input.trim().removePrefix("#")
    if (h.length != 6) return null
    return runCatching {
        Triple(
            h.substring(0, 2).toInt(16).toFloat(),
            h.substring(2, 4).toInt(16).toFloat(),
            h.substring(4, 6).toInt(16).toFloat()
        )
    }.getOrNull()
}

@Composable
private fun ColorSwatch(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(color, CircleShape)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                shape = CircleShape
            )
            .clickable(onClick = onClick)
    )
}

/**
 * Settings hub: only the doors, grouped the way people look for them (connection → app → more), each
 * with its current state as the subtitle. What changes the home screen itself (list look, ping, free
 * servers) lives in the home screen's ⋮ menu, next to what it changes.
 */
@Composable
private fun AppSettingsHubContent(
    selectedMode: AndroidConnectionMode,
    dynamicThemeEnabled: Boolean,
    lightThemeEnabled: Boolean,
    appBehavior: AppBehaviorSettings,
    language: AppLanguage,
    enabled: Boolean,
    onOpen: (AppSettingsRoute) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    searchEntries: List<SettingsSearchEntry>,
    onSearchPick: (SettingsSearchEntry) -> Unit,
    scrollState: androidx.compose.foundation.ScrollState
) {
    val s = LocalStrings.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = s.settings,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 2.dp, bottom = 4.dp)
        )

        SettingsSearchBar(searchQuery, onSearchQueryChange)
        if (searchQuery.isNotBlank()) {
            SettingsSearchResults(SettingsSearch.search(searchEntries, searchQuery), onSearchPick)
        } else {
        SettingsCaption(s.settingsSectionConnection)
        SettingsCard {
            SettingsCardRow(
                title = s.connectionSettings,
                subtitle = selectedMode.settingsSummary(),
                icon = selectedMode.icon(),
                enabled = enabled,
                onClick = { onOpen(AppSettingsRoute.ConnectionSettings) }
            )
            SettingsCardDivider()
            // The legacy standalone "Routing & rules" screen has been folded into routing profiles.
            SettingsCardRow(
                title = s.routingTitle,
                subtitle = s.routingProfilesSubtitle,
                icon = Icons.Outlined.AltRoute,
                onClick = { onOpen(AppSettingsRoute.RoutingProfiles) }
            )
            SettingsCardDivider()
            SettingsCardRow(
                title = s.trafficSettings,
                subtitle = s.trafficSettingsSubtitle,
                icon = Icons.Outlined.Dns,
                onClick = { onOpen(AppSettingsRoute.Traffic) }
            )
        }

        SettingsCaption(s.settingsSectionApp)
        SettingsCard {
            SettingsCardRow(
                title = s.language,
                value = languageLabel(language),
                icon = Icons.Outlined.Language,
                onClick = { onOpen(AppSettingsRoute.Language) }
            )
            SettingsCardDivider()
            SettingsCardRow(
                title = s.appearanceTitle,
                subtitle = themeSummary(s, dynamicThemeEnabled, lightThemeEnabled),
                icon = Icons.Outlined.Palette,
                onClick = { onOpen(AppSettingsRoute.Appearance) }
            )
            SettingsCardDivider()
            SettingsCardRow(
                title = s.notificationsSettingsTitle,
                subtitle = s.notificationsSettingsSubtitle,
                icon = Icons.Outlined.Notifications,
                onClick = { onOpen(AppSettingsRoute.Notifications) }
            )
            SettingsCardDivider()
            SettingsCardRow(
                title = s.privacy,
                subtitle = s.privacySubtitle,
                icon = Icons.Outlined.Lock,
                onClick = { onOpen(AppSettingsRoute.Privacy) }
            )
        }

        SettingsCaption(s.settingsSectionMore)
        SettingsCard {
            SettingsCardRow(
                title = s.advancedSettingsTitle,
                subtitle = s.advancedSettingsSubtitle,
                icon = Icons.Outlined.Build,
                onClick = { onOpen(AppSettingsRoute.Advanced) }
            )
            if (appBehavior.experimentalUnlocked) {
                SettingsCardDivider()
                SettingsCardRow(
                    title = s.experimental,
                    subtitle = s.experimentalSubtitle,
                    icon = Icons.Outlined.Science,
                    onClick = { onOpen(AppSettingsRoute.Experimental) }
                )
            }
            SettingsCardDivider()
            SettingsCardRow(
                title = s.aboutTitle,
                subtitle = "${CurrentAppInfo.value.version} · ${s.aboutSubtitle}",
                icon = Icons.Outlined.Info,
                onClick = { onOpen(AppSettingsRoute.About) }
            )
        }

        Spacer(Modifier.height(10.dp))
        SupportProjectSection(
            onCopyAddress = { it -> clipboard.setText(AnnotatedString(it)); Toast.makeText(context, s.donateAddressCopied, Toast.LENGTH_SHORT).show() },
            onOpenUrl = { it -> uriHandler.openUri(it) }
        )
        }
    }
}

/** Topic page: back arrow + title, then the page's cards in one scrolling column. */
@Composable
private fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            Surface(
                modifier = Modifier.size(46.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
            }
            Spacer(Modifier.width(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        content()
    }
}

private fun themeSummary(s: Strings, dynamicTheme: Boolean, lightTheme: Boolean): String =
    "${if (dynamicTheme) s.dynamicThemeOn else s.dynamicThemeOff} · ${if (lightTheme) s.lightTheme else s.lightThemeOff}"

private val LANGUAGE_OPTIONS = listOf(
    AppLanguage.System to "Авто / Auto",
    AppLanguage.Russian to "Русский",
    AppLanguage.English to "English",
    AppLanguage.Persian to "فارسی",
    AppLanguage.Chinese to "中文",
    AppLanguage.Slovak to "Slovenčina",
    AppLanguage.Czech to "Čeština",
    AppLanguage.Belarusian to "Беларуская"
)

private fun languageLabel(language: AppLanguage): String =
    LANGUAGE_OPTIONS.firstOrNull { it.first == language }?.second ?: language.name

/** «Язык»: one tap picks it. */
@Composable
private fun LanguageContent(
    language: AppLanguage,
    onBack: () -> Unit,
    onLanguageChanged: (AppLanguage) -> Unit
) {
    val s = LocalStrings.current
    SettingsPage(title = s.language, onBack = onBack) {
        SettingsCard {
            LANGUAGE_OPTIONS.forEachIndexed { index, (lang, title) ->
                if (index > 0) SettingsCardDivider()
                SettingsCardRow(
                    title = title,
                    showChevron = false,
                    trailing = if (language == lang) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    } else null,
                    onClick = { onLanguageChanged(lang) }
                )
            }
        }
    }
}

@Composable
private fun ConnectionSettingsContent(
    selectedMode: AndroidConnectionMode,
    proxySettings: AndroidSocksProxySettings,
    splitTunnelSettings: AndroidSplitTunnelSettings,
    appBehavior: AppBehaviorSettings,
    onAppBehaviorChanged: (AppBehaviorSettings) -> Unit,
    telegramProxyState: TelegramProxyState,
    enabled: Boolean,
    onBack: () -> Unit,
    onConnectionModeClick: () -> Unit,
    onProxySettingsClick: () -> Unit,
    onSplitTunnelingClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 24.dp)
    ) {
        val s = LocalStrings.current
        SettingsDetailHeader(
            title = s.connectionSettings,
            subtitle = selectedMode.settingsSummary(),
            onBack = onBack
        )

        Spacer(Modifier.height(20.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsCard {
                SettingsCardRow(
                    title = s.connectionMode,
                    subtitle = selectedMode.settingsSummary(),
                    icon = selectedMode.icon(),
                    enabled = enabled,
                    onClick = onConnectionModeClick
                )
                SettingsCardDivider()
                SettingsCardRow(
                    title = s.socks5Proxy,
                    subtitle = "${proxySettings.host}:${proxySettings.port}",
                    icon = Icons.Rounded.Public,
                    enabled = enabled,
                    onClick = onProxySettingsClick
                )
                SettingsCardDivider()
                SettingsCardRow(
                    title = s.splitTunneling,
                    subtitle = splitTunnelSettings.settingsSummary(),
                    icon = Icons.Outlined.Apps,
                    enabled = enabled,
                    onClick = onSplitTunnelingClick
                )
            }

            RoutingToggleRow(
                title = s.telegramProxyTitle,
                subtitle = s.telegramProxySubtitle,
                checked = appBehavior.telegramProxyEnabled
            ) { onAppBehaviorChanged(appBehavior.copy(telegramProxyEnabled = it)) }

            val tgStatus = when (val st = telegramProxyState) {
                is TelegramProxyState.Generating -> s.telegramProxyGenerating
                is TelegramProxyState.Running ->
                    "${s.telegramProxyRunning}: SOCKS5 ${st.host}:${st.port}"
                is TelegramProxyState.Error -> "${s.telegramProxyError}: ${st.message}"
                is TelegramProxyState.Stopped -> if (appBehavior.telegramProxyEnabled) {
                    "${s.telegramProxyRunning}: SOCKS5 ${TelegramProxyService.LISTEN_HOST}:${TelegramProxyService.LISTEN_PORT}"
                } else {
                    null
                }
            }
            if (tgStatus != null) {
                Text(
                    text = tgStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (telegramProxyState is TelegramProxyState.Error) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            // WARP-based: the proxy depends on Cloudflare WARP reachability, which is throttled/blocked
            // on some networks — warn that it works mainly in Russia and not on every ISP.
            if (appBehavior.telegramProxyEnabled) {
                Text(
                    text = s.telegramProxyRegionNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            // Auto-generated SOCKS5 credentials — selectable so the user can copy them into Telegram.
            (telegramProxyState as? TelegramProxyState.Running)
                ?.takeIf { it.user.isNotBlank() }
                ?.let { running ->
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "${s.telegramProxyLogin}: ${running.user}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            Text(
                                text = "${s.telegramProxyPassword}: ${running.pass}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                    // One-tap copyable t.me/socks link: opening it in Telegram auto-fills the SOCKS5
                    // proxy (server/port/user/pass), no manual entry. Works on-device (server=127.0.0.1).
                    val clipboard = LocalClipboardManager.current
                    val context = LocalContext.current
                    val tgLink = remember(running) {
                        "https://t.me/socks?server=${running.host}&port=${running.port}" +
                            "&user=${running.user}&pass=${running.pass}"
                    }
                    // Side-by-side (equal weights) so BOTH actions fit on one row and are fully visible
                    // without scrolling the sheet — stacked, they overflowed the bottom and the copy
                    // button was clipped to a sliver of its outline. Compact labels + icon stay one line.
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // One tap → opens Telegram straight on its "Enable proxy?" dialog (tg://socks
                        // deep link, server/port/user/pass prefilled), falling back to the https link.
                        Button(
                            onClick = {
                                val tgDeep = "tg://socks?server=${running.host}&port=${running.port}" +
                                    "&user=${running.user}&pass=${running.pass}"
                                val opened = runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(tgDeep))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }.isSuccess
                                if (!opened) runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(tgLink))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Send,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(s.telegramProxyOpen, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(tgLink))
                                Toast.makeText(context, s.telegramProxyLinkCopied, Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(s.telegramProxyCopyLink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

            // Startup and battery keep the VPN up, so they sit with the connection, not in a general page.
            SettingsCaption(s.startupSection)
            SettingsCard {
                SettingsCardToggle(s.autoConnectTitle, s.autoConnectSubtitle, appBehavior.autoConnectOnLaunch) {
                    onAppBehaviorChanged(appBehavior.copy(autoConnectOnLaunch = it))
                }
                SettingsCardDivider()
                SettingsCardToggle(s.energySaverTitle, s.energySaverSubtitle, appBehavior.energySaver) {
                    onAppBehaviorChanged(appBehavior.copy(energySaver = it))
                }
            }
            BatteryOptimizationRow()
        }
    }
}

@Composable
private fun ConnectionModeSettingsContent(
    selectedMode: AndroidConnectionMode,
    enabled: Boolean,
    socksHost: String,
    socksPort: Int,
    onBack: () -> Unit,
    onModeSelected: (AndroidConnectionMode) -> Unit
) {
    val options = listOf(
        AndroidConnectionMode.Tun,
        AndroidConnectionMode.Proxy,
        AndroidConnectionMode.Tproxy,
    )
    val s = LocalStrings.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        SettingsDetailHeader(
            title = s.connectionMode,
            subtitle = selectedMode.subtitle(),
            onBack = onBack
        )

        Spacer(Modifier.height(20.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            options.forEach { mode ->
                ConnectionModeOption(
                    mode = mode,
                    selected = selectedMode == mode,
                    enabled = enabled,
                    onClick = { onModeSelected(mode) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Local SOCKS5 endpoint, shown right under the mode picker so the user always sees the port to
        // point apps at. In Proxy mode it's also reachable on the LAN IP + an HTTP port (socks+4).
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = s.localSocksEndpoint,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "SOCKS5: $socksHost:$socksPort",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (selectedMode == AndroidConnectionMode.Proxy) {
                    Text(
                        text = "HTTP: $socksHost:${socksPort + 4}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun SocksProxySettingsContent(
    proxySettings: AndroidSocksProxySettings,
    enabled: Boolean,
    isConnectionActive: Boolean,
    onBack: () -> Unit,
    onProxySettingsSaved: (String, String, String, Int) -> Unit,
    onProxyPasswordRegenerated: () -> Unit
) {
    var editedHost by remember(proxySettings.host) { mutableStateOf(proxySettings.host) }
    var editedPort by remember(proxySettings.port) { mutableStateOf(proxySettings.port.toString()) }
    var editedUsername by remember(proxySettings.username) { mutableStateOf(proxySettings.username) }
    var editedPassword by remember(proxySettings.password) { mutableStateOf(proxySettings.password) }
    val parsedPort = editedPort.toIntOrNull()
    val hostValid = editedHost.isNotBlank()
    val portValid = parsedPort != null && AndroidSocksProxySettings.isValidPort(parsedPort)
    val hostChanged = editedHost != proxySettings.host
    val portChanged = parsedPort != null && parsedPort != proxySettings.port
    val usernameChanged = editedUsername != proxySettings.username
    val passwordChanged = editedPassword != proxySettings.password
    val settingsChanged = hostChanged || portChanged || usernameChanged || passwordChanged
    val canSave = hostValid &&
            portValid &&
            editedUsername.isNotBlank() &&
            editedPassword.isNotBlank() &&
            settingsChanged &&
            enabled

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        SettingsDetailHeader(
            title = LocalStrings.current.socks5Proxy,
            subtitle = proxySettings.host,
            onBack = onBack
        )

        Spacer(Modifier.height(20.dp))

        SocksProxySettingsForm(
            host = editedHost,
            port = editedPort,
            username = editedUsername,
            password = editedPassword,
            hostValid = hostValid,
            portValid = portValid,
            hostChanged = hostChanged,
            portChanged = portChanged,
            usernameChanged = usernameChanged,
            passwordChanged = passwordChanged,
            canSave = canSave,
            enabled = enabled,
            isConnectionActive = isConnectionActive,
            onHostChanged = { value ->
                editedHost = value
                    .replace("\r", "")
                    .replace("\n", "")
                    .take(AndroidSocksProxySettings.MAX_HOST_LENGTH)
            },
            onPortChanged = { value ->
                editedPort = value.filter { it.isDigit() }.take(MAX_PROXY_PORT_LENGTH)
            },
            onUsernameChanged = { value -> editedUsername = value.take(MAX_PROXY_USERNAME_LENGTH) },
            onPasswordChanged = { value -> editedPassword = value.take(MAX_PROXY_PASSWORD_LENGTH) },
            onSaveSettings = {
                onProxySettingsSaved(
                    editedHost,
                    editedUsername,
                    editedPassword,
                    parsedPort ?: proxySettings.port
                )
            },
            onRegeneratePassword = onProxyPasswordRegenerated
        )
    }
}

@Composable
private fun SplitTunnelingSettingsContent(
    settings: AndroidSplitTunnelSettings,
    enabled: Boolean,
    isConnectionActive: Boolean,
    selectedMode: AndroidConnectionMode,
    onBack: () -> Unit,
    onModeSelected: (AndroidSplitTunnelMode) -> Unit,
    onAppListClick: (AndroidSplitTunnelList) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        SettingsDetailHeader(
            title = LocalStrings.current.splitTunneling,
            subtitle = settings.mode.statusTitle(settings),
            onBack = onBack
        )

        Spacer(Modifier.height(18.dp))

        SplitTunnelStatusCard(
            settings = settings,
            selectedMode = selectedMode,
            isConnectionActive = isConnectionActive
        )

        Spacer(Modifier.height(18.dp))

        SettingsSectionLabel(LocalStrings.current.routingBehavior)

        Spacer(Modifier.height(8.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AndroidSplitTunnelMode.entries.forEach { mode ->
                SplitTunnelModeOption(
                    mode = mode,
                    settings = settings,
                    selected = settings.mode == mode,
                    enabled = enabled,
                    onClick = { onModeSelected(mode) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        when (settings.mode) {
            AndroidSplitTunnelMode.AllApps -> SplitTunnelNoListCard()
            AndroidSplitTunnelMode.ProxySelected -> SplitTunnelAppListAction(
                title = LocalStrings.current.appsUsingYptun,
                value = settings.proxyPackages.activeListValue(requireSelection = true),
                icon = Icons.Outlined.Shield,
                enabled = enabled,
                onClick = { onAppListClick(AndroidSplitTunnelList.Proxy) }
            )

            AndroidSplitTunnelMode.BypassSelected -> SplitTunnelAppListAction(
                title = LocalStrings.current.bypassedApps,
                value = settings.bypassPackages.activeListValue(requireSelection = false),
                icon = Icons.Outlined.Apps,
                enabled = enabled,
                onClick = { onAppListClick(AndroidSplitTunnelList.Bypass) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SplitTunnelingAppListContent(
    list: AndroidSplitTunnelList,
    settings: AndroidSplitTunnelSettings,
    installedApps: List<AndroidInstalledApp>,
    enabled: Boolean,
    onBack: () -> Unit,
    onAppToggled: (AndroidSplitTunnelList, String) -> Unit,
    onAppsSelected: (AndroidSplitTunnelList, Set<String>) -> Unit,
    autoBypassPackages: Set<String>,
    onAutoBypassPackagesChanged: (Set<String>) -> Unit,
    russianBypassPresetEnabled: Boolean,
    onRussianBypassPresetEnabledChanged: (Boolean) -> Unit
) {
    var query by remember(list) { mutableStateOf("") }
    // System/background apps (no launcher icon) are hidden by default so the list stays familiar;
    // this reveals them so they can be included/excluded too. Already-selected ones stay visible.
    var showSystemApps by remember { mutableStateOf(false) }
    val hasSystemApps = remember(installedApps) { installedApps.any { it.isSystem } }

    // Не просто scrollToItem(0): keyed LazyColumn может успеть сохранить старый visible key
    // и слегка увести список к элементу, который переехал. Для bulk/search-сценариев
    // пересоздаём LazyListState, чтобы список гарантированно начинался сверху.
    var listStateResetVersion by remember(list) { mutableStateOf(0) }
    val listScrollState = key(listStateResetVersion) { rememberLazyListState() }
    val focusManager = LocalFocusManager.current

    fun resetListScrollToTop() {
        listStateResetVersion += 1
    }

    val selectedPackages = settings.packagesFor(list)

    val russianBypassPackages = remember(installedApps) {
        installedApps
            .map { it.packageName }
            .filter { it.matchesRussianBypassPackage() }
            .toSet()
    }

    val russianBypassActive = list == AndroidSplitTunnelList.Bypass &&
            russianBypassPresetEnabled &&
            russianBypassPackages.isNotEmpty()

    val activeAutoBypassPackages = autoBypassPackages
        .intersect(russianBypassPackages)
        .intersect(selectedPackages)

    val selectedRussianBypassPackagesCount = selectedPackages
        .count { it in russianBypassPackages }

    // Это snapshot порядка, а не всегда актуальное состояние selection.
    // Ручной toggle не должен мгновенно двигать строку вверх/вниз — иначе UX ощущается как jump.
    var sortAutoBypassPackages by remember(list) {
        mutableStateOf(activeAutoBypassPackages)
    }

    var sortSelectedPackages by remember(list) {
        mutableStateOf(selectedPackages)
    }

    val normalizedQuery = query.trim().lowercase()
    val appListEntries = remember(installedApps) {
        installedApps.map { app ->
            AndroidAppListEntry(
                app = app,
                labelSortKey = app.label.lowercase(),
                packageSortKey = app.packageName.lowercase()
            )
        }
    }
    val filteredApps = remember(appListEntries, normalizedQuery, sortSelectedPackages, sortAutoBypassPackages, showSystemApps, selectedPackages) {
        val visible = appListEntries.filter { entry ->
            // Hide system apps unless the toggle is on — but never hide one that's already selected,
            // so the user can always see and remove their own choices.
            showSystemApps || !entry.app.isSystem || entry.app.packageName in selectedPackages
        }
        val apps = if (normalizedQuery.isBlank()) {
            visible
        } else {
            visible.filter { entry ->
                entry.labelSortKey.contains(normalizedQuery) ||
                        entry.packageSortKey.contains(normalizedQuery)
            }
        }
        apps.sortedWith(
            compareBy<AndroidAppListEntry> {
                when (it.app.packageName) {
                    in sortAutoBypassPackages -> 0
                    in sortSelectedPackages -> 1
                    else -> 2
                }
            }.thenBy { it.labelSortKey }.thenBy { it.packageSortKey }
        ).map { it.app }
    }

    LaunchedEffect(list, russianBypassPackages, autoBypassPackages, russianBypassPresetEnabled) {
        if (list == AndroidSplitTunnelList.Bypass) {
            val cleanedAutoPackages = autoBypassPackages.intersect(russianBypassPackages)

            if (cleanedAutoPackages != autoBypassPackages) {
                onAutoBypassPackagesChanged(cleanedAutoPackages)
                sortAutoBypassPackages = sortAutoBypassPackages.intersect(cleanedAutoPackages)
            }

            if (russianBypassPackages.isEmpty() && russianBypassPresetEnabled) {
                onRussianBypassPresetEnabledChanged(false)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.92f)
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 12.dp)
    ) {
        SettingsDetailHeader(
            title = list.title(),
            subtitle = if (list == AndroidSplitTunnelList.Bypass && russianBypassActive) {
                LocalStrings.current.ruBypassAccuracy
            } else {
                list.selectionSubtitle(selectedPackages.size)
            },
            onBack = onBack
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { value ->
                if (value != query) {
                    resetListScrollToTop()
                    query = value
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null
                )
            },
            label = { Text(LocalStrings.current.searchApps) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
        )

        if (hasSystemApps) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) {
                        showSystemApps = !showSystemApps
                        resetListScrollToTop()
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = LocalStrings.current.showSystemApps,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = showSystemApps,
                    onCheckedChange = {
                        showSystemApps = it
                        resetListScrollToTop()
                    },
                    enabled = enabled
                )
            }
        }

        if (list == AndroidSplitTunnelList.Bypass) {
            Spacer(Modifier.height(10.dp))

            RussianBypassPresetChips(
                active = russianBypassActive,
                enabled = enabled && russianBypassPackages.isNotEmpty(),
                value = russianBypassPackages.russianBypassPresetValue(
                    autoCount = activeAutoBypassPackages.size,
                    selectedMatchedCount = selectedRussianBypassPackagesCount,
                    presetActive = russianBypassActive
                ),
                onClick = {
                    focusManager.clearFocus()

                    val activatingRussianBypass = !russianBypassActive

                    val nextAutoPackages = if (activatingRussianBypass) {
                        russianBypassPackages - selectedPackages
                    } else {
                        emptySet()
                    }

                    val nextPackages = if (activatingRussianBypass) {
                        selectedPackages + nextAutoPackages
                    } else {
                        selectedPackages - activeAutoBypassPackages
                    }

                    onRussianBypassPresetEnabledChanged(activatingRussianBypass)
                    onAutoBypassPackagesChanged(nextAutoPackages)

                    sortAutoBypassPackages = nextAutoPackages
                    sortSelectedPackages = nextPackages

                    query = ""
                    resetListScrollToTop()

                    onAppsSelected(list, nextPackages)
                }
            )
        }

        Spacer(Modifier.height(12.dp))

        if (filteredApps.isEmpty()) {
            EmptyAppsState(
                title = if (installedApps.isEmpty()) LocalStrings.current.noApps else LocalStrings.current.noMatchingApps,
                subtitle = if (installedApps.isEmpty()) {
                    LocalStrings.current.noAppsHint
                } else {
                    LocalStrings.current.noMatchHint
                }
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listScrollState,
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = filteredApps,
                    key = { app -> app.packageName }
                ) { app ->
                    val packageName = app.packageName
                    val selected = packageName in selectedPackages
                    val autoAdded = list == AndroidSplitTunnelList.Bypass &&
                            packageName in activeAutoBypassPackages

                    val russianPresetMatched = list == AndroidSplitTunnelList.Bypass &&
                            russianBypassActive &&
                            selected &&
                            packageName in russianBypassPackages

                    SplitTunnelAppRow(
                        app = app,
                        selected = selected,
                        autoSelected = russianPresetMatched,
                        enabled = enabled,
                        onClick = {
                            focusManager.clearFocus()

                            val removing = packageName in selectedPackages
                            val nextSelectedPackages = if (removing) {
                                selectedPackages - packageName
                            } else {
                                selectedPackages + packageName
                            }
                            val nextSelectedRussianPackages = nextSelectedPackages.intersect(russianBypassPackages)

                            val nextAutoPackages = when {
                                list == AndroidSplitTunnelList.Bypass &&
                                        russianBypassActive &&
                                        nextSelectedRussianPackages.isEmpty() -> {
                                    emptySet()
                                }

                                autoAdded -> autoBypassPackages - packageName

                                !removing &&
                                        russianBypassActive &&
                                        packageName in russianBypassPackages -> {
                                    autoBypassPackages + packageName
                                }

                                else -> autoBypassPackages
                            }

                            if (list == AndroidSplitTunnelList.Bypass &&
                                russianBypassActive &&
                                nextSelectedRussianPackages.isEmpty()
                            ) {
                                onRussianBypassPresetEnabledChanged(false)
                            }

                            if (nextAutoPackages != autoBypassPackages) {
                                onAutoBypassPackagesChanged(nextAutoPackages)
                            }

                            // Важно: не меняем sortSelectedPackages/sortAutoBypassPackages на одиночный toggle.
                            // Иначе строка сразу переезжает между группами, а LazyColumn сохраняет её key
                            // как first visible item — отсюда микроскролл к package, который пользователь убрал.
                            onAppToggled(list, packageName)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ApplicationLogsSettingsContent(
    logs: List<String>,
    onBack: () -> Unit,
    onSaveClick: () -> Unit,
    onShareClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.8f)
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsDetailHeader(
                title = LocalStrings.current.applicationLogsTitle,
                subtitle = if (logs.isEmpty()) LocalStrings.current.noLogEntries else LocalStrings.current.logEntriesCount(logs.size),
                onBack = onBack,
                modifier = Modifier.weight(1f)
            )

            TextButton(
                enabled = logs.isNotEmpty(),
                onClick = onSaveClick
            ) {
                Text(LocalStrings.current.save)
            }
            TextButton(
                enabled = logs.isNotEmpty(),
                onClick = onShareClick
            ) {
                Text(LocalStrings.current.share)
            }
        }

        Spacer(Modifier.height(16.dp))

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            LogLines(
                logs = logs,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(14.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun UpdatesSettingsContent(
    settings: AppUpdateSettings,
    statusText: String?,
    downloadProgress: Float?,
    onBack: () -> Unit,
    onIntervalSelected: (Int) -> Unit,
    onCheckUpdatesClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 12.dp)
    ) {
        SettingsDetailHeader(
            title = LocalStrings.current.updatesTitle,
            subtitle = LocalStrings.current.currentVersion(CurrentAppInfo.value.version),
            onBack = onBack
        )

        Spacer(Modifier.height(18.dp))

        SettingsSectionLabel(LocalStrings.current.checkInterval)
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            AppUpdateSettings.INTERVAL_PRESETS.forEach { hours ->
                FilterChip(
                    selected = settings.intervalHours == hours,
                    onClick = { onIntervalSelected(hours) },
                    label = { Text(LocalStrings.current.hoursShort(hours)) }
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = LocalStrings.current.lastCheck,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = settings.lastCheckAtEpochMs?.formatDateTime() ?: LocalStrings.current.notCheckedYet,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!statusText.isNullOrBlank()) {
                    Text(
                        text = statusText,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (downloadProgress != null) {
                    LinearProgressIndicator(
                        progress = { downloadProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        Button(
            onClick = onCheckUpdatesClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text(LocalStrings.current.checkNow)
        }
    }
}

@Composable
private fun SettingsNavigationRow(
    title: String,
    value: String,
    icon: ImageVector,
    enabled: Boolean,
    showChevron: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .settingsHighlight(title)
            .height(72.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = value,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (showChevron) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    value: String,
    icon: ImageVector,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .settingsHighlight(title)
            .height(72.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = value,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
private fun SettingsDetailHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(46.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back"
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ConnectionModeOption(
    mode: AndroidConnectionMode,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    SelectableSettingsCard(
        selected = selected,
        enabled = enabled,
        icon = mode.icon(),
        title = mode.label(),
        subtitle = mode.description(),
        onClick = onClick
    )
}

@Composable
private fun SplitTunnelModeOption(
    mode: AndroidSplitTunnelMode,
    settings: AndroidSplitTunnelSettings,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    SplitTunnelRoutingOption(
        selected = selected,
        enabled = enabled,
        icon = mode.icon(),
        title = mode.title(),
        subtitle = mode.subtitle(settings),
        onClick = onClick
    )
}

@Composable
private fun SplitTunnelRoutingOption(
    selected: Boolean,
    enabled: Boolean,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        label = "splitTunnelRoutingOptionContainer"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            Color.Transparent
        },
        label = "splitTunnelRoutingOptionBorder"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(74.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = CircleShape,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(9.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            RadioButton(
                selected = selected,
                enabled = enabled,
                onClick = onClick
            )
        }
    }
}

@Composable
private fun SelectableSettingsCard(
    selected: Boolean,
    enabled: Boolean,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        label = "selectableSettingsCardContainer"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            Color.Transparent
        },
        label = "selectableSettingsCardBorder"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(82.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    SettingsCaption(text)
}

@Composable
private fun SplitTunnelStatusCard(
    settings: AndroidSplitTunnelSettings,
    selectedMode: AndroidConnectionMode,
    isConnectionActive: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Icon(
                    imageVector = settings.mode.icon(),
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = settings.mode.statusTitle(settings),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = splitTunnelStatusSubtitle(selectedMode, isConnectionActive),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SplitTunnelNoListCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = LocalStrings.current.noAppListNeeded,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = LocalStrings.current.everyAppSameRoute,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SplitTunnelAppListAction(
    title: String,
    value: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    SettingsSectionLabel(LocalStrings.current.appListSection)

    Spacer(Modifier.height(8.dp))

    SettingsNavigationRow(
        title = title,
        value = value,
        icon = icon,
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
private fun SocksProxySettingsForm(
    host: String,
    port: String,
    username: String,
    password: String,
    hostValid: Boolean,
    portValid: Boolean,
    hostChanged: Boolean,
    portChanged: Boolean,
    usernameChanged: Boolean,
    passwordChanged: Boolean,
    canSave: Boolean,
    enabled: Boolean,
    isConnectionActive: Boolean,
    onHostChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onSaveSettings: () -> Unit,
    onRegeneratePassword: () -> Unit
) {
    val s = LocalStrings.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsSectionLabel(LocalStrings.current.endpointSection)

            SocksProxyTextField(
                value = host,
                onValueChange = onHostChanged,
                label = s.listenAddress,
                placeholder = AndroidSocksProxySettings.DEFAULT_HOST,
                enabled = enabled,
                isError = !hostValid,
                leadingIcon = Icons.Rounded.Public,
                supportingText = when {
                    !hostValid -> s.listenAddressRequired
                    hostChanged && isConnectionActive -> s.savingRestarts
                    hostChanged -> s.unsavedChange
                    else -> null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )

            SocksProxyTextField(
                value = port,
                onValueChange = onPortChanged,
                label = s.port,
                placeholder = AndroidSocksProxySettings.DEFAULT_PORT.toString(),
                enabled = enabled,
                isError = port.isBlank() || !portValid,
                leadingIcon = Icons.Rounded.Public,
                supportingText = when {
                    port.isBlank() -> s.portRequired
                    !portValid -> "Use ${AndroidSocksProxySettings.MIN_PORT}-${AndroidSocksProxySettings.MAX_PORT}"
                    portChanged && isConnectionActive -> s.savingRestarts
                    portChanged -> s.unsavedChange
                    else -> null
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next
                )
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsSectionLabel(LocalStrings.current.credentialsSection)

            SocksProxyTextField(
                value = username,
                onValueChange = onUsernameChanged,
                label = s.username,
                placeholder = "yptun...",
                enabled = enabled,
                isError = username.isBlank(),
                leadingIcon = Icons.Rounded.Person,
                supportingText = when {
                    username.isBlank() -> s.usernameRequired
                    usernameChanged && isConnectionActive -> s.savingRestarts
                    usernameChanged -> s.unsavedChange
                    else -> null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )

            SocksProxyTextField(
                value = password,
                onValueChange = onPasswordChanged,
                label = s.password,
                placeholder = s.generatedPassword,
                enabled = enabled,
                isError = password.isBlank(),
                leadingIcon = Icons.Rounded.Key,
                supportingText = when {
                    password.isBlank() -> s.passwordRequired
                    passwordChanged && isConnectionActive -> s.savingRestarts
                    passwordChanged -> s.unsavedChange
                    else -> null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                enabled = enabled,
                onClick = onRegeneratePassword
            ) {
                Text(s.regeneratePassword)
            }

            Spacer(Modifier.width(8.dp))

            Button(
                enabled = canSave,
                onClick = onSaveSettings
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(s.save)
            }
        }
    }
}

@Composable
private fun SocksProxyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    enabled: Boolean,
    isError: Boolean,
    leadingIcon: ImageVector,
    supportingText: String?,
    keyboardOptions: KeyboardOptions
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        isError = isError,
        leadingIcon = { Icon(leadingIcon, contentDescription = null) },
        supportingText = supportingText?.let { { Text(it) } },
        keyboardOptions = keyboardOptions
    )
}

@Composable
private fun RussianBypassPresetChips(
    active: Boolean,
    enabled: Boolean,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = active,
            enabled = enabled,
            onClick = onClick,
            label = {
                Text(if (active) LocalStrings.current.bypassRuOn else LocalStrings.current.bypassRuApps)
            },
            leadingIcon = {
                Icon(
                    imageVector = if (active) Icons.Rounded.Check else Icons.Outlined.Apps,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        )

        RussianBypassInfoPill(value = value)
    }
}

@Composable
private fun RussianBypassInfoPill(value: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = value,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SplitTunnelAppRow(
    app: AndroidInstalledApp,
    selected: Boolean,
    autoSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val iconBitmap = rememberAppIcon(app.packageName)
    val colors = MaterialTheme.colorScheme

    val containerColor by animateColorAsState(
        targetValue = when {
            autoSelected -> colors.surfaceContainerHigh
            selected -> colors.secondaryContainer
            else -> colors.surfaceContainer
        },
        label = "splitTunnelAppRowContainer"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            autoSelected -> colors.tertiary.copy(alpha = 0.72f)
            selected -> colors.primary
            else -> Color.Transparent
        },
        label = "splitTunnelAppRowBorder"
    )
    val iconContainerColor by animateColorAsState(
        targetValue = when {
            autoSelected -> colors.surfaceContainerHigh
            selected -> colors.primary
            else -> colors.surfaceContainerHigh
        },
        label = "splitTunnelAppRowIconContainer"
    )
    val iconContentColor by animateColorAsState(
        targetValue = when {
            autoSelected -> colors.tertiary
            selected -> colors.onPrimary
            else -> colors.onSurfaceVariant
        },
        label = "splitTunnelAppRowIconContent"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
                color = iconContainerColor,
                contentColor = iconContentColor
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (iconBitmap != null) {
                        Image(
                            bitmap = iconBitmap,
                            contentDescription = null,
                            modifier = Modifier.size(30.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text(
                            text = app.label.initials(),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    color = colors.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = app.packageName,
                    color = colors.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (autoSelected) {
                Spacer(Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = colors.surfaceContainerHigh,
                    contentColor = colors.tertiary
                ) {
                    Text(
                        text = "RU",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            Checkbox(
                checked = selected,
                enabled = enabled,
                onCheckedChange = { onClick() }
            )
        }
    }
}

@Composable
private fun EmptyAppsState(
    title: String,
    subtitle: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(128.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun rememberAppIcon(packageName: String): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val iconState = produceState<ImageBitmap?>(initialValue = null, packageName, context) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                context.packageManager
                    .getApplicationIcon(packageName)
                    .toImageBitmap(sizePx = 96)
            }.getOrNull()
        }
    }
    return iconState.value
}

private fun Drawable.toImageBitmap(sizePx: Int): ImageBitmap {
    val width = intrinsicWidth.takeIf { it > 0 } ?: sizePx
    val height = intrinsicHeight.takeIf { it > 0 } ?: sizePx
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap.asImageBitmap()
}

@Composable
private fun RoutingContent(
    routing: RoutingRules,
    enabled: Boolean,
    onBack: () -> Unit,
    onRoutingChanged: (RoutingRules) -> Unit,
    // Happ routing profiles — shown as the third tab.
    routingProfilesState: org.olcbox.app.data.model.RoutingProfilesState,
    geoStatus: org.olcbox.app.vpn.GeoUpdateStatus?,
    onSaveProfile: (org.olcbox.app.data.model.RoutingProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onSetGlobal: (String) -> Unit,
    onImportLink: (String) -> Boolean,
    onSetGeoSources: (String, String) -> Unit,
    onUpdateGeo: () -> Unit,
) {
    var bypassLan by remember(routing) { mutableStateOf(routing.bypassLan) }
    var blockAds by remember(routing) { mutableStateOf(routing.blockAds) }
    var bypassRu by remember(routing) { mutableStateOf(routing.bypassRussia) }
    var directText by remember(routing) { mutableStateOf(RoutingRules.domainsToText(routing.directDomains)) }
    var blockText by remember(routing) { mutableStateOf(RoutingRules.domainsToText(routing.blockDomains)) }
    var customRules by remember(routing) { mutableStateOf(routing.customRulesJson) }
    var customRuleSets by remember(routing) { mutableStateOf(routing.customRuleSetsJson) }
    // v2rayNG-style ordered rules (sing-box). Edited as drafts (raw text) so the cursor stays put;
    // converted back to [SingBoxRule] on save. Kept ALONGSIDE the Happ routing profiles.
    val ruleDrafts = remember(routing) { mutableStateListOf<SbRuleDraft>().apply { addAll(routing.rules.map { it.toDraft() }) } }
    val rulesValid = RoutingRules.isValidRulesJson(customRules)
    val ruleSetsValid = RoutingRules.isValidRulesJson(customRuleSets)
    var selectedTab by remember { mutableStateOf(0) }
    val s = LocalStrings.current

    // Persists the RoutingRules from the Simple + Rules tabs (the Happ tab saves its own profiles).
    fun saveRouting() {
        onRoutingChanged(
            RoutingRules(
                bypassLan = bypassLan,
                blockAds = blockAds,
                bypassRussia = bypassRu,
                directDomains = RoutingRules.parseDomains(directText),
                blockDomains = RoutingRules.parseDomains(blockText),
                customRulesJson = customRules.trim(),
                customRuleSetsJson = customRuleSets.trim(),
                rules = ruleDrafts.map { it.toRule() }.filter { it.hasMatcher() }
            )
        )
        onBack()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = s.routingTitle,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text(s.routingTabSimple) })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text(s.routingTabRules) })
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text(s.routingTabHapp) })
        }

        when (selectedTab) {
            0 -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(top = 12.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsSectionLabel(s.presets)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = false,
                        onClick = { bypassLan = true; bypassRu = true; blockAds = false },
                        label = { Text(s.presetRuDirect) }
                    )
                    FilterChip(
                        selected = false,
                        onClick = { bypassLan = true; blockAds = true; bypassRu = false },
                        label = { Text(s.presetAdsBlock) }
                    )
                    FilterChip(
                        selected = false,
                        onClick = { bypassLan = true; bypassRu = true; blockAds = true },
                        label = { Text(s.presetRuAds) }
                    )
                    FilterChip(
                        selected = false,
                        onClick = { bypassLan = true; bypassRu = false; blockAds = false },
                        label = { Text(s.presetAllVpn) }
                    )
                    FilterChip(
                        selected = false,
                        onClick = {
                            bypassLan = true; bypassRu = false; blockAds = false
                            directText = ""; blockText = ""
                        },
                        label = { Text(s.presetReset) }
                    )
                }

                RoutingToggleRow(s.bypassLan, s.bypassLanSubtitle, bypassLan) { bypassLan = it }
                RoutingToggleRow(s.bypassRussia, s.bypassRussiaSubtitle, bypassRu) { bypassRu = it }
                RoutingToggleRow(s.blockAds, s.blockAdsSubtitle, blockAds) { blockAds = it }

                // Direct/blocked domain fields and the raw sing-box "advanced" routing editor are
                // intentionally hidden from the Simple tab. The underlying values (directText,
                // blockText, customRules, customRuleSets) are still round-tripped by saveRouting()
                // so any previously-configured rules keep applying — only the editing UI is removed.

                Button(
                    onClick = { saveRouting() },
                    enabled = enabled && rulesValid && ruleSetsValid,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(s.saveAndApply)
                }
            }

            1 -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(top = 12.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // v2rayNG-style ordered rules (sing-box). Each rule is matched top-to-bottom; the
                // first match decides the outbound (proxy / direct / block). Runs on the sing-box
                // core alongside the toggle presets and any Happ routing profile.
                Text(
                    s.routingRulesDesc,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                if (ruleDrafts.isEmpty()) {
                    Text(
                        s.routingRulesEmpty,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                ruleDrafts.forEachIndexed { index, draft ->
                    SingBoxRuleCard(
                        draft = draft,
                        onChange = { ruleDrafts[index] = it },
                        onDelete = { ruleDrafts.removeAt(index) }
                    )
                }
                OutlinedButton(
                    onClick = { ruleDrafts.add(SbRuleDraft()) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(s.routingRuleAdd)
                }
                Button(
                    onClick = { saveRouting() },
                    enabled = enabled && rulesValid && ruleSetsValid,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(s.saveAndApply)
                }
            }

            else -> RoutingProfilesContent(
                state = routingProfilesState,
                geoStatus = geoStatus,
                onBack = onBack,
                onSaveProfile = onSaveProfile,
                onDeleteProfile = onDeleteProfile,
                onSetGlobal = onSetGlobal,
                onImportLink = onImportLink,
                onSetGeoSources = onSetGeoSources,
                onUpdateGeo = onUpdateGeo,
                embedded = true,
            )
        }
    }
}

/** Editable (raw-text) form of a [SingBoxRule] so text fields keep their cursor while typing. */
private data class SbRuleDraft(
    val name: String = "",
    val action: String = SingBoxRule.ACTION_ROUTE,
    val outbound: String = SingBoxRule.OUT_PROXY,
    val domains: String = "",
    val ip: String = "",
    val source: String = "",
    val port: String = "",
    val sourcePort: String = "",
    val network: String = "",
    val networkType: List<String> = emptyList(),
    val protocol: String = "",
    val client: String = "",
    val networkIsExpensive: Boolean = false,
    val clashMode: String = "",
    val packageNames: String = "",
    val packageRegex: String = "",
    val enabled: Boolean = true,
) {
    fun toRule(): SingBoxRule = SingBoxRule(
        name = name.trim(),
        action = action,
        outbound = outbound,
        domains = splitList(domains),
        ip = splitList(ip),
        source = splitList(source),
        port = port.trim(),
        sourcePort = sourcePort.trim(),
        network = network.trim(),
        networkType = networkType,
        protocol = splitList(protocol),
        client = splitList(client),
        networkIsExpensive = networkIsExpensive,
        clashMode = clashMode.trim(),
        packageNames = splitList(packageNames),
        // Regex patterns may legitimately contain spaces, so split only on line/comma/semicolon.
        packageRegex = packageRegex.split('\n', ',', ';').map { it.trim() }.filter { it.isNotEmpty() },
        enabled = enabled,
    )

    private fun splitList(text: String): List<String> =
        text.split('\n', ',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
}

private fun SingBoxRule.toDraft(): SbRuleDraft = SbRuleDraft(
    name = name,
    action = action,
    outbound = outbound,
    domains = domains.joinToString("\n"),
    ip = ip.joinToString("\n"),
    source = source.joinToString("\n"),
    port = port,
    sourcePort = sourcePort,
    network = network,
    networkType = networkType,
    protocol = protocol.joinToString(", "),
    client = client.joinToString(", "),
    networkIsExpensive = networkIsExpensive,
    clashMode = clashMode,
    packageNames = packageNames.joinToString("\n"),
    packageRegex = packageRegex.joinToString("\n"),
    enabled = enabled,
)

/** Localized label for a sing-box rule [SingBoxRule.action] token. */
private fun routingActionLabel(s: Strings, action: String): String = when (action) {
    SingBoxRule.ACTION_ROUTE -> s.routingActionRoute
    SingBoxRule.ACTION_ROUTE_OPTIONS -> s.routingActionRouteOptions
    SingBoxRule.ACTION_SNIFF -> s.routingActionSniff
    SingBoxRule.ACTION_RESOLVE -> s.routingActionResolve
    SingBoxRule.ACTION_HIJACK_DNS -> s.routingActionHijackDns
    SingBoxRule.ACTION_REJECT -> s.routingActionReject
    else -> action
}

@Composable
private fun SingBoxRuleCard(
    draft: SbRuleDraft,
    onChange: (SbRuleDraft) -> Unit,
    onDelete: () -> Unit
) {
    val s = LocalStrings.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.routingRuleEdit,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = draft.enabled, onCheckedChange = { onChange(draft.copy(enabled = it)) })
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = s.routingRuleDelete,
                        tint = MaterialTheme.colorScheme.error)
                }
            }
            // Optional label for the rule (UI only; not part of the sing-box config).
            OutlinedTextField(
                value = draft.name,
                onValueChange = { onChange(draft.copy(name = it)) },
                label = { Text(s.routingRuleName) },
                placeholder = { Text("P2P Traffic") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            // Routing action: route / route-options / sniff / resolve / hijack-dns / reject.
            Text(
                s.routingRuleAction,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SingBoxRule.ACTIONS.forEach { value ->
                    FilterChip(
                        selected = draft.action == value,
                        onClick = { onChange(draft.copy(action = value)) },
                        label = { Text(routingActionLabel(s, value)) }
                    )
                }
            }
            // Outbound (only meaningful for the "route" action): proxy / direct / block.
            if (draft.action == SingBoxRule.ACTION_ROUTE) {
                Text(
                    s.routingRuleOutbound,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        SingBoxRule.OUT_PROXY to s.routingOutProxy,
                        SingBoxRule.OUT_DIRECT to s.routingOutDirect,
                        SingBoxRule.OUT_BLOCK to s.routingOutBlock,
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = draft.outbound == value,
                            onClick = { onChange(draft.copy(outbound = value)) },
                            label = { Text(label) }
                        )
                    }
                }
            }
            OutlinedTextField(
                value = draft.domains,
                onValueChange = { onChange(draft.copy(domains = it)) },
                label = { Text(s.routingRuleDomains) },
                placeholder = { Text("geosite:ru, domain:vk.com") },
                minLines = 1,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = draft.ip,
                onValueChange = { onChange(draft.copy(ip = it)) },
                label = { Text(s.routingRuleIps) },
                placeholder = { Text("geoip:ru, 10.0.0.0/8") },
                minLines = 1,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = draft.source,
                onValueChange = { onChange(draft.copy(source = it)) },
                label = { Text(s.routingRuleSource) },
                placeholder = { Text("192.168.0.0/16") },
                minLines = 1,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft.port,
                    onValueChange = { onChange(draft.copy(port = it)) },
                    label = { Text(s.routingRulePort) },
                    placeholder = { Text("443, 8000:9000") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = draft.protocol,
                    onValueChange = { onChange(draft.copy(protocol = it)) },
                    label = { Text(s.routingRuleProtocol) },
                    placeholder = { Text("tls, quic") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            OutlinedTextField(
                value = draft.sourcePort,
                onValueChange = { onChange(draft.copy(sourcePort = it)) },
                label = { Text(s.routingRuleSourcePort) },
                placeholder = { Text("1000:2000") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = draft.client,
                onValueChange = { onChange(draft.copy(client = it)) },
                label = { Text(s.routingRuleClient) },
                placeholder = { Text("chromium, firefox") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = draft.clashMode,
                onValueChange = { onChange(draft.copy(clashMode = it)) },
                label = { Text(s.routingRuleClashMode) },
                placeholder = { Text("Rule / Global / Direct") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = draft.packageNames,
                onValueChange = { onChange(draft.copy(packageNames = it)) },
                label = { Text(s.routingRuleApps) },
                placeholder = { Text("com.google.android.youtube") },
                minLines = 1,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = draft.packageRegex,
                onValueChange = { onChange(draft.copy(packageRegex = it)) },
                label = { Text(s.routingRulePackageRegex) },
                placeholder = { Text("^com\\.google\\..*") },
                minLines = 1,
                modifier = Modifier.fillMaxWidth()
            )
            // Network: any / tcp / udp.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("" to s.routingRuleNetworkAny, "tcp" to "TCP", "udp" to "UDP").forEach { (value, label) ->
                    FilterChip(
                        selected = draft.network == value,
                        onClick = { onChange(draft.copy(network = value)) },
                        label = { Text(label) }
                    )
                }
            }
            // Network type (multi-select): wifi / cellular / ethernet / other.
            Text(
                s.routingRuleNetworkType,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "wifi" to s.netTypeWifi,
                    "cellular" to s.netTypeCellular,
                    "ethernet" to s.netTypeEthernet,
                    "other" to s.netTypeOther,
                ).forEach { (value, label) ->
                    val selected = value in draft.networkType
                    FilterChip(
                        selected = selected,
                        onClick = {
                            val next = if (selected) draft.networkType - value else draft.networkType + value
                            onChange(draft.copy(networkType = next))
                        },
                        label = { Text(label) }
                    )
                }
            }
            // Metered (expensive) network match → network_is_expensive.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.routingRuleMetered,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = draft.networkIsExpensive,
                    onCheckedChange = { onChange(draft.copy(networkIsExpensive = it)) }
                )
            }
        }
    }
}

@Composable
private fun RoutingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .settingsHighlight(title)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Clickable row that requests exemption from Doze/battery optimization — the usual reason a VPN
 * foreground service is throttled/killed over long (24h+) idle sessions while other apps (Happ)
 * survive. Opens the system ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS dialog; once granted the row
 * shows a done state and stops being clickable. Never changes anything silently.
 */
@Composable
private fun BatteryOptimizationRow() {
    val context = LocalContext.current
    val s = org.olcbox.app.ui.i18n.LocalStrings.current
    val pm = remember { context.getSystemService(PowerManager::class.java) }
    fun exempted(): Boolean = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    var ignoring by remember { mutableStateOf(exempted()) }
    val launcher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { ignoring = exempted() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(enabled = !ignoring) {
                val req = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                )
                // Some OEMs block the direct request; fall back to the general exemption list.
                runCatching { launcher.launch(req) }.onFailure {
                    runCatching { launcher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(s.batteryOptTitle, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text(
                if (ignoring) s.batteryOptDone else s.batteryOptSubtitle,
                color = if (ignoring) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        // "Opens elsewhere" affordance: tapping leaves the app for the system battery-optimization
        // screen. Hidden once already exempted (nothing left to open).
        if (!ignoring) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun TrafficSettingsContent(
    settings: TrafficSettings,
    enabled: Boolean,
    onBack: () -> Unit,
    onTrafficChanged: (TrafficSettings) -> Unit
) {
    var remoteDns by remember(settings) { mutableStateOf(settings.remoteDns) }
    var remoteDns2 by remember(settings) { mutableStateOf(settings.remoteDns2) }
    var directDns by remember(settings) { mutableStateOf(settings.directDns) }
    var strategy by remember(settings) { mutableStateOf(settings.domainStrategy) }
    var muxEnabled by remember(settings) { mutableStateOf(settings.muxEnabled) }
    var muxProtocol by remember(settings) { mutableStateOf(settings.muxProtocol) }
    var muxMax by remember(settings) { mutableStateOf(settings.muxMaxConnections.toString()) }
    var fragEnabled by remember(settings) { mutableStateOf(settings.fragmentEnabled) }
    var fragPackets by remember(settings) { mutableStateOf(settings.fragmentPackets) }
    var fragLength by remember(settings) { mutableStateOf(settings.fragmentLength) }
    var fragInterval by remember(settings) { mutableStateOf(settings.fragmentInterval) }
    var mtu by remember(settings) { mutableStateOf(settings.mtu.toString()) }
    var blockRu by remember(settings) { mutableStateOf(settings.blockRuDomains) }
    val s = LocalStrings.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = s.trafficSettings,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        SettingsSectionLabel(s.dns)
        OutlinedTextField(
            value = remoteDns,
            onValueChange = { remoteDns = it },
            label = { Text(s.remoteDnsLabel) },
            placeholder = { Text("8.8.8.8") },
            supportingText = { Text(s.dnsFormatsHint) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = remoteDns2,
            onValueChange = { remoteDns2 = it },
            label = { Text(s.remoteDns2Label) },
            placeholder = { Text("1.1.1.1") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = directDns,
            onValueChange = { directDns = it },
            label = { Text(s.directDnsLabel) },
            placeholder = { Text("223.5.5.5") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        SettingsSectionLabel(s.domainStrategy)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TrafficSettings.STRATEGIES.forEach { option ->
                FilterChip(
                    selected = strategy == option,
                    onClick = { strategy = option },
                    label = { Text(s.domainStrategyName(option)) }
                )
            }
        }

        SettingsSectionLabel(s.multiplexing)
        RoutingToggleRow(
            title = s.useMux,
            subtitle = s.useMuxSubtitle,
            checked = muxEnabled
        ) { muxEnabled = it }

        if (muxEnabled) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TrafficSettings.MUX_PROTOCOLS.forEach { option ->
                    FilterChip(
                        selected = muxProtocol == option,
                        onClick = { muxProtocol = option },
                        label = { Text(option) }
                    )
                }
            }
            OutlinedTextField(
                value = muxMax,
                onValueChange = { muxMax = it.filter { ch -> ch.isDigit() }.take(2) },
                label = { Text(s.muxMaxConnections) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }

        SettingsSectionLabel(s.fragmentation)
        RoutingToggleRow(
            title = s.useFragment,
            subtitle = s.useFragmentSubtitle,
            checked = fragEnabled
        ) { fragEnabled = it }

        if (fragEnabled) {
            OutlinedTextField(
                value = fragPackets,
                onValueChange = { fragPackets = it },
                label = { Text(s.fragmentPackets) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = fragLength,
                onValueChange = { fragLength = it },
                label = { Text(s.fragmentLength) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = fragInterval,
                onValueChange = { fragInterval = it },
                label = { Text(s.fragmentInterval) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        SettingsSectionLabel("MTU")
        OutlinedTextField(
            value = mtu,
            onValueChange = { mtu = it.filter { ch -> ch.isDigit() }.take(4) },
            label = { Text(s.mtuLabel) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        SettingsSectionLabel(s.blockRuDomains)
        RoutingToggleRow(
            title = s.blockRuDomains,
            subtitle = s.blockRuDomainsSubtitle,
            checked = blockRu
        ) { blockRu = it }

        Button(
            onClick = {
                onTrafficChanged(
                    TrafficSettings(
                        remoteDns = remoteDns,
                        remoteDns2 = remoteDns2,
                        directDns = directDns,
                        domainStrategy = strategy,
                        muxEnabled = muxEnabled,
                        muxProtocol = muxProtocol,
                        muxMaxConnections = muxMax.toIntOrNull() ?: 8,
                        fragmentEnabled = fragEnabled,
                        fragmentPackets = fragPackets,
                        fragmentLength = fragLength,
                        fragmentInterval = fragInterval,
                        mtu = mtu.toIntOrNull() ?: 1500,
                        blockRuDomains = blockRu,
                    ).normalized()
                )
                onBack()
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(s.saveAndApply)
        }
    }
}

/** «Оформление»: theme and colors, then the launcher icon (see [AppIcons]: one enabled `activity-alias` per icon). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AppearanceContent(
    dynamicThemeEnabled: Boolean,
    lightThemeEnabled: Boolean,
    onDynamicThemeChanged: (Boolean) -> Unit,
    onLightThemeChanged: (Boolean) -> Unit,
    onAccentColorSelected: (Color?) -> Unit,
    onTextColorSelected: (Color?) -> Unit,
    onBackgroundColorSelected: (Color?) -> Unit,
    onBack: () -> Unit
) {
    val s = LocalStrings.current
    val context = LocalContext.current
    var selected by remember { mutableStateOf(AppIcons.current(context)) }
    SettingsPage(title = s.appearanceTitle, onBack = onBack) {
        SettingsSectionLabel(s.themeSection)
        SettingsSwitchRow(
            title = s.dynamicTheme,
            value = if (dynamicThemeEnabled) s.dynamicThemeOn else s.dynamicThemeOff,
            icon = Icons.Outlined.Palette,
            checked = dynamicThemeEnabled,
            enabled = true,
            onCheckedChange = onDynamicThemeChanged
        )
        SettingsSwitchRow(
            title = s.lightTheme,
            value = if (lightThemeEnabled) s.lightThemeOn else s.lightThemeOff,
            icon = Icons.Outlined.LightMode,
            checked = lightThemeEnabled,
            enabled = true,
            onCheckedChange = onLightThemeChanged
        )
        if (!dynamicThemeEnabled) {
            ThemeColorSection(
                lightMode = lightThemeEnabled,
                onAccentColorSelected = onAccentColorSelected,
                onTextColorSelected = onTextColorSelected,
                onBackgroundColorSelected = onBackgroundColorSelected
            )
        }

        SettingsSectionLabel(s.appearanceSubtitle)
        Text(
            text = s.appIconHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        listOf(true to s.appIconWithCat, false to s.appIconNoCat).forEach { (cat, label) ->
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                maxItemsInEachRow = 3
            ) {
                AppIcons.options.forEachIndexed { index, option ->
                    if (option.cat != cat) return@forEachIndexed
                    AppIconTile(
                        option = option,
                        name = s.appIconNames.getOrElse(index) { option.alias },
                        selected = index == selected,
                        onClick = {
                            runCatching { AppIcons.select(context, index) }.onSuccess { selected = index }
                        }
                    )
                }
            }
        }
    }
}

/** «Уведомления»: what the VPN notification shows, and the subscription/panel alerts. */
@Composable
private fun NotificationsSettingsContent(
    settings: AppBehaviorSettings,
    onBack: () -> Unit,
    onChanged: (AppBehaviorSettings) -> Unit
) {
    val s = LocalStrings.current
    SettingsPage(title = s.notificationsSettingsTitle, onBack = onBack) {
        SettingsCaption(s.notificationVpnSection)
        SettingsCard {
            SettingsCardToggle(s.notifSpeed, s.notifSpeedSubtitle, settings.showSpeedInNotification) {
                onChanged(settings.copy(showSpeedInNotification = it))
            }
            SettingsCardDivider()
            SettingsCardToggle(s.notifPingTitle, s.notifPingSubtitle, settings.pingOwnLineInNotification) {
                onChanged(settings.copy(pingOwnLineInNotification = it))
            }
            if (settings.pingOwnLineInNotification) {
                Text(
                    text = s.notifPingSourceTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                SettingsChoiceChips(
                    options = listOf(false to s.notifPingSourceLast, true to s.notifPingSourceAuto),
                    selected = settings.notifPingAuto,
                    inCard = true
                ) { onChanged(settings.copy(notifPingAuto = it)) }
                if (settings.notifPingAuto) {
                    var minutes by remember { mutableStateOf(settings.notifPingIntervalMin.toString()) }
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { raw ->
                            minutes = raw.filter { ch -> ch.isDigit() }.take(3)
                            minutes.toIntOrNull()?.takeIf { it in 1..999 }
                                ?.let { onChanged(settings.copy(notifPingIntervalMin = it)) }
                        },
                        label = { Text(s.notifPingIntervalLabel) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    )
                }
            }
            SettingsCardDivider()
            SettingsCardToggle(s.roomsInNotifTitle, s.roomsInNotifSubtitle, settings.showRoomsInNotification) {
                onChanged(settings.copy(showRoomsInNotification = it))
            }
        }

        SettingsCaption(s.homeSubscriptionsSection)
        SettingsCard {
            SettingsCardToggle(s.notifySubExpiryTitle, s.notifySubExpirySubtitle, settings.notifySubscriptionExpiry) {
                onChanged(settings.copy(notifySubscriptionExpiry = it))
            }
            SettingsCardDivider()
            SettingsCardToggle(s.panelAnnouncementsTitle, s.panelAnnouncementsSubtitle, settings.notifyPanelAnnouncements) {
                onChanged(settings.copy(notifyPanelAnnouncements = it))
            }
        }
    }
}

/** «Для продвинутых»: rarely touched switches for people who know what they change. */
@Composable
private fun AdvancedSettingsContent(
    settings: AppBehaviorSettings,
    onBack: () -> Unit,
    onChanged: (AppBehaviorSettings) -> Unit,
    onUrlSchemesClick: () -> Unit
) {
    val s = LocalStrings.current
    SettingsPage(title = s.advancedSettingsTitle, onBack = onBack) {
        // App-wide engine for VLESS-like transports. Applies only when a server's own core is "Auto";
        // an explicit per-server choice overrides it (xhttp/raw-Xray always force Xray regardless).
        SettingsCaption(s.globalEngineLabel)
        SettingsNote(s.globalEngineSubtitle)
        SettingsChoiceChips(
            options = listOf(
                ProxyCore.Auto to s.globalEngineAuto,
                ProxyCore.SingBox to "sing-box",
                ProxyCore.Xray to "Xray",
            ),
            selected = settings.globalProxyCore
        ) { onChanged(settings.copy(globalProxyCore = it)) }

        SettingsCaption(s.advancedConfigsSection)
        SettingsCard {
            SettingsCardToggle(s.shareUniversalLinkTitle, s.shareUniversalLinkSubtitle, settings.shareUniversalLink) {
                onChanged(settings.copy(shareUniversalLink = it))
            }
            SettingsCardDivider()
            SettingsCardToggle(s.allowConfigEditingTitle, s.allowConfigEditingSubtitle, settings.allowConfigEditing) {
                onChanged(settings.copy(allowConfigEditing = it))
            }
            SettingsCardDivider()
            SettingsCardRow(
                title = s.urlSchemes,
                subtitle = s.urlSchemesSubtitle,
                onClick = onUrlSchemesClick
            )
        }
    }
}

/** «О приложении»: version and updates, the log, help links, core versions, the device ID, terms. */
@Composable
private fun AboutContent(
    hwid: String,
    updateSettings: AppUpdateSettings,
    onBack: () -> Unit,
    onUpdatesClick: () -> Unit,
    onLogsClick: () -> Unit,
    onCopyConfigClick: () -> Unit
) {
    val s = LocalStrings.current
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    SettingsPage(title = s.aboutTitle, onBack = onBack) {
        SettingsCard {
            SettingsCardRow(
                title = "YPtun",
                value = CurrentAppInfo.value.version,
                icon = Icons.Outlined.Info,
                showChevron = false
            )
            SettingsCardDivider()
            SettingsCardRow(
                title = s.updates,
                value = "${s.releaseChannelLabel} · ${s.hoursShort(updateSettings.intervalHours)}",
                icon = Icons.Outlined.Refresh,
                onClick = onUpdatesClick
            )
        }

        SettingsCaption(s.helpSection)
        SettingsCard {
            SettingsCardRow(
                title = s.howToConnect,
                icon = Icons.AutoMirrored.Outlined.HelpOutline,
                onClick = { uriHandler.openUri("https://t.me/YPtun") }
            )
            SettingsCardDivider()
            SettingsCardRow(
                title = s.community,
                value = "t.me/YPtun",
                icon = Icons.Rounded.Person,
                onClick = { uriHandler.openUri("https://t.me/YPtun") }
            )
            SettingsCardDivider()
            SettingsCardRow(
                title = s.logs,
                subtitle = s.logsSubtitle,
                icon = Icons.Outlined.History,
                onClick = onLogsClick
            )
            SettingsCardDivider()
            SettingsCardRow(
                title = s.copyFullConfig,
                icon = Icons.Outlined.ContentPaste,
                showChevron = false,
                onClick = onCopyConfigClick
            )
        }

        SettingsCaption(s.coreVersionsLabel)
        val xrayVer = remember { runCatching { xraybridge.Xraybridge.version() }.getOrNull()?.ifBlank { null } ?: "—" }
        val singboxVer = remember { runCatching { libbox.Libbox.version() }.getOrNull()?.ifBlank { null } ?: "—" }
        val vkturnVer = remember { runCatching { freeturn.Freeturn.version() }.getOrNull()?.ifBlank { null } ?: "—" }
        val wdttVer = remember { runCatching { wdttmobile.Wdttmobile.version() }.getOrNull()?.ifBlank { null } ?: "—" }
        val awgVer = remember { runCatching { awg.Awg.version() }.getOrNull()?.ifBlank { null } ?: "—" }
        val olcrtcVer = remember { runCatching { mobile.Mobile.version() }.getOrNull()?.ifBlank { null } ?: "—" }
        val cores = listOf(
            "Xray" to xrayVer,
            "sing-box" to singboxVer,
            "olcRTC" to olcrtcVer,
            "VK-TURN" to vkturnVer,
            "WDTT" to wdttVer,
            "AmneziaWG" to awgVer,
            "OpenFlux" to org.olcbox.app.vpn.CoreVersions.OPENFLUX,
            "Trust Tunnel" to org.olcbox.app.vpn.CoreVersions.TRUSTTUNNEL_ANDROID,
            "Snolc" to org.olcbox.app.vpn.CoreVersions.SNOLC,
            "csqtt" to org.olcbox.app.vpn.CoreVersions.CSQTT,
        )
        SettingsCard {
            cores.forEachIndexed { index, (name, version) ->
                if (index > 0) SettingsCardDivider()
                SettingsCardRow(title = name, value = version, showChevron = false)
            }
        }

        SettingsCaption(s.deviceSection)
        SettingsCard {
            SettingsCardRow(
                title = s.hwid(hwid.ifBlank { "—" }),
                icon = Icons.Rounded.Key,
                enabled = hwid.isNotBlank(),
                showChevron = false,
                trailing = if (hwid.isNotBlank()) {
                    {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else null,
                onClick = if (hwid.isNotBlank()) {
                    { clipboard.setText(AnnotatedString(hwid)) }
                } else null
            )
            SettingsCardDivider()
            SettingsCardRow(
                title = s.termsOfUse,
                icon = Icons.Outlined.Shield,
                onClick = { uriHandler.openUri("https://yanisplugg.github.io/yptun/terms.html") }
            )
        }
    }
}

@Composable
private fun AppIconTile(option: AppIconOption, name: String, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    fun layer(res: String?): ImageBitmap? = res?.let {
        runCatching {
            val id = context.resources.getIdentifier(it, "mipmap", context.packageName)
            android.graphics.BitmapFactory.decodeResource(context.resources, id)?.asImageBitmap()
        }.getOrNull()
    }
    val bg = remember(option.bg) { layer(option.bg) }
    val fg = remember(option.fg) { layer(option.fg) }
    val shape = RoundedCornerShape(24.dp)
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = Modifier.width(88.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(shape)
                .background(androidx.compose.ui.graphics.Color.Black)
                .border(if (selected) 3.dp else 1.dp, ring, shape)
        ) {
            // The launcher shows the middle two thirds of an adaptive layer (the rest is the mask margin), so zoom in 1.5x.
            val zoom = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.5f; scaleY = 1.5f }
            bg?.let { Image(it, null, zoom, contentScale = ContentScale.Crop) }
            fg?.let { Image(it, null, zoom, contentScale = ContentScale.Crop) }
        }
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** Settings -> Privacy: what the app sends out on its own, and the switches for it. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PrivacyContent(
    settings: AppBehaviorSettings,
    onBack: () -> Unit,
    onChanged: (AppBehaviorSettings) -> Unit
) {
    val s = LocalStrings.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = s.privacy,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        RoutingToggleRow(
            title = s.providerReportTitle,
            subtitle = s.providerReportSubtitle,
            checked = settings.providerReportEnabled
        ) { onChanged(settings.copy(providerReportEnabled = it)) }
        Text(
            text = s.providerReportDetails,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SettingsSectionLabel(s.subscriptionUserAgentLabel)
        Text(
            text = s.subscriptionUserAgentSubtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val uaOptions = listOf(
                AppBehaviorSettings.SUB_UA_BOTH to s.uaModeBoth,
                AppBehaviorSettings.SUB_UA_HAPP to s.uaModeHapp,
                AppBehaviorSettings.SUB_UA_YPTUN_ONLY to s.uaModeYptun,
                AppBehaviorSettings.SUB_UA_V2RAYTUN to s.uaModeV2raytun,
            )
            uaOptions.forEach { (mode, title) ->
                FilterChip(
                    selected = settings.effectiveSubscriptionUa() == mode,
                    onClick = { onChanged(settings.copy(subscriptionUserAgent = mode)) },
                    label = { Text(title) }
                )
            }
        }

        RoutingToggleRow(
            title = s.sendDeviceInfoTitle,
            subtitle = s.sendDeviceInfoSubtitle,
            checked = settings.sendDeviceInfoToSubscriptions
        ) { onChanged(settings.copy(sendDeviceInfoToSubscriptions = it)) }
    }
}

/** Hidden Experimental section (unlocked by tapping the connection timer 5×). */
@Composable
private fun ExperimentalContent(
    settings: AppBehaviorSettings,
    onBack: () -> Unit,
    onChanged: (AppBehaviorSettings) -> Unit
) {
    val s = LocalStrings.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = s.experimental,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        RoutingToggleRow(
            title = s.legacyUi,
            subtitle = s.legacyUiSubtitle,
            checked = settings.legacyUi
        ) { onChanged(settings.copy(legacyUi = it)) }

        SettingsSectionLabel("Yandex Telemost")
        Text(
            text = s.telemostCookiesDescription,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        RoutingToggleRow(
            title = s.useTelemostCookies,
            subtitle = s.useTelemostCookiesSubtitle,
            checked = settings.telemostCookiesEnabled
        ) { onChanged(settings.copy(telemostCookiesEnabled = it)) }
        OutlinedTextField(
            value = settings.telemostCookies,
            onValueChange = { onChanged(settings.copy(telemostCookies = it)) },
            label = { Text(s.telemostCookieHeader) },
            placeholder = { Text("Session_id=…; yandexuid=…") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
        val context = LocalContext.current
        val cookiePicker = rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text != null) {
                onChanged(settings.copy(telemostCookies = cookiesFromFile(text)))
                android.widget.Toast.makeText(context, s.cookiesLoaded, android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(context, s.cookiesReadFailed, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        OutlinedButton(
            onClick = { cookiePicker.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth()
        ) { Text(s.loadFromFile) }

        SettingsSectionLabel("Root")
        val rootScope = rememberCoroutineScope()
        var showRebootDialog by remember { mutableStateOf(false) }
        RoutingToggleRow(
            title = s.hideTunTitle,
            subtitle = s.hideTunSubtitle,
            checked = settings.hideTunInterface
        ) { enabled ->
            onChanged(settings.copy(hideTunInterface = enabled))
            rootScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                if (enabled) {
                    // Install the bundled Zygisk hide module (or re-enable it if already present).
                    val result = org.olcbox.app.vpn.VpnHideModule.enable(context)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        val msg = when (result) {
                            org.olcbox.app.vpn.VpnHideModule.Result.INSTALLED -> s.hideModuleInstalled
                            org.olcbox.app.vpn.VpnHideModule.Result.ENABLED -> s.hideModuleInstalled
                            org.olcbox.app.vpn.VpnHideModule.Result.ALREADY_ACTIVE -> s.hideModuleActive
                            org.olcbox.app.vpn.VpnHideModule.Result.NO_ROOT -> s.rootDenied
                            org.olcbox.app.vpn.VpnHideModule.Result.FAILED -> s.hideModuleFailed
                        }
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                        if (result.needsReboot) showRebootDialog = true
                    }
                } else {
                    org.olcbox.app.vpn.VpnHideModule.disable(context)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(context, s.hideModuleDisabled, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        Text(
            text = s.hideTunDisclaimer,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        if (showRebootDialog) {
            AlertDialog(
                onDismissRequest = { showRebootDialog = false },
                title = { Text(s.hideModuleRebootTitle) },
                text = { Text(s.hideModuleRebootMessage) },
                confirmButton = {
                    TextButton(onClick = {
                        showRebootDialog = false
                        rootScope.launch(kotlinx.coroutines.Dispatchers.IO) { org.olcbox.app.vpn.VpnHideModule.reboot() }
                    }) { Text(s.rebootNow) }
                },
                dismissButton = {
                    TextButton(onClick = { showRebootDialog = false }) { Text(s.rebootLater) }
                }
            )
        }
        RoutingToggleRow(
            title = s.shareHotspotTitle,
            subtitle = s.shareHotspotSubtitle,
            checked = settings.shareVpnHotspot
        ) { enabled ->
            onChanged(settings.copy(shareVpnHotspot = enabled))
            // Trigger the superuser prompt right away so the user grants root before connecting.
            if (enabled) {
                rootScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val ok = runCatching {
                        val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
                        p.inputStream.bufferedReader().use { it.readText() }
                        p.waitFor() == 0
                    }.getOrDefault(false)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context, if (ok) s.rootGranted else s.rootDenied, android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
        Text(
            text = s.shareHotspotDisclaimer,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

/** Builds a Cookie header from a raw header string or a Netscape cookies.txt export. */
private fun cookiesFromFile(text: String): String {
    val trimmed = text.trim()
    // Netscape cookies.txt: tab-separated lines with domain/flag/path/secure/expiry/name/value.
    val pairs = trimmed.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size >= 7 && parts[5].isNotBlank()) "${parts[5]}=${parts[6]}" else null
        }
        .toList()
    return if (pairs.isNotEmpty()) pairs.joinToString("; ") else trimmed
}

@Composable
private fun UrlSchemesContent(onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val s = LocalStrings.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = s.urlSchemes,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        SettingsSectionLabel(s.urlSchemeAddConfig)
        UrlSchemeRow("yptun://import/{CONFIG}", clipboard)
        SettingsSectionLabel(s.urlSchemeAddSubscription)
        UrlSchemeRow("yptun://import/{URL}", clipboard)
        SettingsSectionLabel(s.urlSchemeControl)
        UrlSchemeRow("yptun://control/start", clipboard)
        UrlSchemeRow("yptun://control/stop", clipboard)
        UrlSchemeRow("yptun://control/restart", clipboard)
    }
}

@Composable
private fun UrlSchemeRow(scheme: String, clipboard: androidx.compose.ui.platform.ClipboardManager) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = scheme,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = { clipboard.setText(AnnotatedString(scheme)) }) {
                Icon(
                    imageVector = Icons.Outlined.ContentPaste,
                    contentDescription = "Copy",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private sealed class AppSettingsRoute(val depth: Int) {
    object Hub : AppSettingsRoute(0)
    object ConnectionSettings : AppSettingsRoute(1)
    object ConnectionMode : AppSettingsRoute(1)
    object SocksProxy : AppSettingsRoute(1)
    object SplitTunneling : AppSettingsRoute(1)
    object Routing : AppSettingsRoute(1)
    object RoutingProfiles : AppSettingsRoute(1)
    object Traffic : AppSettingsRoute(1)
    object Language : AppSettingsRoute(1)
    object Appearance : AppSettingsRoute(1)
    object Notifications : AppSettingsRoute(1)
    object Privacy : AppSettingsRoute(1)
    object Advanced : AppSettingsRoute(1)
    object UrlSchemes : AppSettingsRoute(1)
    object Experimental : AppSettingsRoute(1)
    object About : AppSettingsRoute(1)
    object Updates : AppSettingsRoute(1)
    object ApplicationLogs : AppSettingsRoute(1)
    data class AppList(val list: AndroidSplitTunnelList) : AppSettingsRoute(2)
}

private fun settingsRouteFor(page: String): AppSettingsRoute? = when (page) {
        "ConnectionSettings" -> AppSettingsRoute.ConnectionSettings
        "ConnectionMode" -> AppSettingsRoute.ConnectionMode
        "SocksProxy" -> AppSettingsRoute.SocksProxy
        "SplitTunneling" -> AppSettingsRoute.SplitTunneling
        "RoutingProfiles" -> AppSettingsRoute.RoutingProfiles
        "Traffic" -> AppSettingsRoute.Traffic
        "Language" -> AppSettingsRoute.Language
        "Appearance" -> AppSettingsRoute.Appearance
        "Notifications" -> AppSettingsRoute.Notifications
        "Privacy" -> AppSettingsRoute.Privacy
        "Advanced" -> AppSettingsRoute.Advanced
        "UrlSchemes" -> AppSettingsRoute.UrlSchemes
        "Experimental" -> AppSettingsRoute.Experimental
        "About" -> AppSettingsRoute.About
        "Updates" -> AppSettingsRoute.Updates
        "ApplicationLogs" -> AppSettingsRoute.ApplicationLogs
        else -> null
}

private fun AppSettingsInitialRoute.toRoute(): AppSettingsRoute {
    return when (this) {
        AppSettingsInitialRoute.Hub -> AppSettingsRoute.Hub
        AppSettingsInitialRoute.SplitTunneling -> AppSettingsRoute.SplitTunneling
    }
}

private fun AndroidConnectionMode.label(): String {
    return when (this) {
        AndroidConnectionMode.Tun -> "TUN"
        AndroidConnectionMode.Proxy -> "Proxy"
        AndroidConnectionMode.Tproxy -> "TProxy"
    }
}

private fun Long.formatDateTime(): String {
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(this))
}

private fun AndroidConnectionMode.shortLabel(): String {
    return when (this) {
        AndroidConnectionMode.Tun -> "TUN"
        AndroidConnectionMode.Proxy -> "SOCKS"
        AndroidConnectionMode.Tproxy -> "TPROXY"
    }
}

private fun AndroidConnectionMode.subtitle(): String {
    val s = stringsFor(LocalizationState.effective)
    return when (this) {
        AndroidConnectionMode.Tun -> s.fullTunnel
        AndroidConnectionMode.Proxy -> s.localSocksProxy
        AndroidConnectionMode.Tproxy -> "Transparent proxy (root)"
    }
}

private fun AndroidConnectionMode.settingsSummary(): String {
    val s = stringsFor(LocalizationState.effective)
    return when (this) {
        AndroidConnectionMode.Tun -> "TUN · ${s.fullTunnel}"
        AndroidConnectionMode.Proxy -> "SOCKS · ${s.localSocksProxy}"
        AndroidConnectionMode.Tproxy -> "TPROXY · transparent proxy (root)"
    }
}

private fun AndroidConnectionMode.description(): String {
    val s = stringsFor(LocalizationState.effective)
    return when (this) {
        AndroidConnectionMode.Tun -> s.systemVpnInterface
        AndroidConnectionMode.Proxy -> s.localSocksEndpoint
        AndroidConnectionMode.Tproxy ->
            "Transparent-proxy inbound (TCP+UDP) on the LAN — redirect traffic here via iptables TPROXY. Requires root."
    }
}

private fun AndroidConnectionMode.icon() = when (this) {
    AndroidConnectionMode.Tun -> Icons.Outlined.Shield
    AndroidConnectionMode.Proxy -> Icons.Rounded.Public
    AndroidConnectionMode.Tproxy -> Icons.Rounded.Public
}

private fun AndroidSplitTunnelSettings.settingsSummary(): String {
    return when (mode) {
        AndroidSplitTunnelMode.AllApps -> stringsFor(LocalizationState.effective).allApps
        AndroidSplitTunnelMode.ProxySelected -> if (proxyPackages.isEmpty()) {
            stringsFor(LocalizationState.effective).selectedAppsOnly
        } else {
            stringsFor(LocalizationState.effective).onlyCount(proxyPackages.size)
        }

        AndroidSplitTunnelMode.BypassSelected -> if (bypassPackages.isEmpty()) {
            stringsFor(LocalizationState.effective).bypassSelected
        } else {
            stringsFor(LocalizationState.effective).bypassedCount(bypassPackages.size)
        }
    }
}

private fun AndroidSplitTunnelSettings.packagesFor(list: AndroidSplitTunnelList): Set<String> {
    return when (list) {
        AndroidSplitTunnelList.Proxy -> proxyPackages
        AndroidSplitTunnelList.Bypass -> bypassPackages
    }
}

private fun AndroidSplitTunnelMode.title(): String {
    return when (this) {
        AndroidSplitTunnelMode.AllApps -> stringsFor(LocalizationState.effective).allApps
        AndroidSplitTunnelMode.ProxySelected -> stringsFor(LocalizationState.effective).selectedAppsOnly
        AndroidSplitTunnelMode.BypassSelected -> stringsFor(LocalizationState.effective).bypassSelected
    }
}

private fun AndroidSplitTunnelMode.subtitle(settings: AndroidSplitTunnelSettings): String {
    return when (this) {
        AndroidSplitTunnelMode.AllApps -> stringsFor(LocalizationState.effective).everyAppUsesYptun
        AndroidSplitTunnelMode.ProxySelected -> if (settings.proxyPackages.isEmpty()) {
            stringsFor(LocalizationState.effective).chooseAppsUseYptun
        } else {
            stringsFor(LocalizationState.effective).useYptunCount(settings.proxyPackages.size)
        }

        AndroidSplitTunnelMode.BypassSelected -> if (settings.bypassPackages.isEmpty()) {
            stringsFor(LocalizationState.effective).chooseAppsBypass
        } else {
            stringsFor(LocalizationState.effective).bypassYptunCount(settings.bypassPackages.size)
        }
    }
}

private fun AndroidSplitTunnelMode.statusTitle(settings: AndroidSplitTunnelSettings): String {
    return when (this) {
        AndroidSplitTunnelMode.AllApps -> stringsFor(LocalizationState.effective).allAppsUseYptunStatus
        AndroidSplitTunnelMode.ProxySelected -> if (settings.proxyPackages.isEmpty()) {
            stringsFor(LocalizationState.effective).noAppsSelectedStatus
        } else {
            stringsFor(LocalizationState.effective).onlyUseYptunCount(settings.proxyPackages.size)
        }

        AndroidSplitTunnelMode.BypassSelected -> if (settings.bypassPackages.isEmpty()) {
            stringsFor(LocalizationState.effective).noAppsBypassStatus
        } else {
            stringsFor(LocalizationState.effective).bypassYptunCount(settings.bypassPackages.size)
        }
    }
}

private fun AndroidSplitTunnelMode.icon() = when (this) {
    AndroidSplitTunnelMode.AllApps -> Icons.Outlined.Shield
    AndroidSplitTunnelMode.ProxySelected -> Icons.Outlined.Shield
    AndroidSplitTunnelMode.BypassSelected -> Icons.Outlined.Apps
}

private fun AndroidSplitTunnelList.title(): String {
    val s = stringsFor(LocalizationState.effective)
    return when (this) {
        AndroidSplitTunnelList.Proxy -> s.appsUsingYptun
        AndroidSplitTunnelList.Bypass -> s.bypassedApps
    }
}

private fun AndroidSplitTunnelList.selectionSubtitle(count: Int): String {
    val s = stringsFor(LocalizationState.effective)
    return when (this) {
        AndroidSplitTunnelList.Proxy -> s.useYptunCount(count)
        AndroidSplitTunnelList.Bypass -> s.bypassedCount(count)
    }
}

private fun Set<String>.russianBypassPresetValue(
    autoCount: Int,
    selectedMatchedCount: Int,
    presetActive: Boolean
): String {
    val s = stringsFor(LocalizationState.effective)
    return when {
        isEmpty() -> s.ruBypassNoMatches
        !presetActive -> s.ruBypassMatchedByPackage(size)
        selectedMatchedCount == 0 -> s.ruBypassNoneSelected
        autoCount == 0 -> s.ruBypassAlreadySelected(selectedMatchedCount)
        autoCount == selectedMatchedCount -> s.ruBypassAutoBypassed(autoCount)
        else -> s.ruBypassAutoManual(autoCount, selectedMatchedCount - autoCount)
    }
}

private fun String.matchesRussianBypassPackage(): Boolean {
    val packageName = lowercase()
    return packageName in RUSSIAN_BYPASS_PACKAGE_NAMES ||
            RUSSIAN_BYPASS_PACKAGE_PREFIXES.any { packageName.startsWith(it) }
}

private fun Set<String>.activeListValue(requireSelection: Boolean): String {
    val s = stringsFor(LocalizationState.effective)
    return when {
        isNotEmpty() -> appCount(size)
        requireSelection -> s.selectionRequired
        else -> s.noBypassedAppsValue
    }
}

private fun splitTunnelStatusSubtitle(
    selectedMode: AndroidConnectionMode,
    isConnectionActive: Boolean
): String {
    val s = stringsFor(LocalizationState.effective)
    return when {
        selectedMode != AndroidConnectionMode.Tun -> s.savedForTunMode
        isConnectionActive -> s.appliesWhenSettingsClose
        else -> s.tunModeRoutingRule
    }
}

private fun String.initials(): String {
    val words = trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words.first().take(2).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

private fun appCount(count: Int): String {
    return stringsFor(LocalizationState.effective).appsCount(count)
}

private data class AndroidAppListEntry(
    val app: AndroidInstalledApp,
    val labelSortKey: String,
    val packageSortKey: String
)

private const val MAX_PROXY_USERNAME_LENGTH = 64
private const val MAX_PROXY_PASSWORD_LENGTH = 64
private const val MAX_PROXY_PORT_LENGTH = 5
private val RUSSIAN_BYPASS_PACKAGE_PREFIXES = listOf(
    "ru.",
    // Russian apps published under a reversed "ru.com"/"*.ru" domain land as com.ru.* — count them too.
    "com.ru.",
    "com.yandex."
)
private val RUSSIAN_BYPASS_PACKAGE_NAMES = setOf(
    "ru.sberbankmobile",
    "ru.ozon.app.android",
    "ru.avito",
    "ru.vtb24.mobilebanking.android",
    "ru.tinkoff.mb"
)
