package org.olcbox.app.ui.activities

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.AltRoute
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
import androidx.compose.material3.HorizontalDivider
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var route by remember(initialRoute) { mutableStateOf(initialRoute.toRoute()) }
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

            else -> AppSettingsRoute.Hub
        }
    }

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
                    selectedMode = selectedMode,
                    dynamicThemeEnabled = dynamicThemeEnabled,
                    updateSettings = updateSettings,
                    subscriptionsCount = subscriptions.size,
                    enabled = enabled,
                    hwid = hwid,
                    onDynamicThemeChanged = onDynamicThemeChanged,
                    onAccentColorSelected = onAccentColorSelected,
                    onTextColorSelected = onTextColorSelected,
                    onBackgroundColorSelected = onBackgroundColorSelected,
                    onConnectionSettingsClick = { route = AppSettingsRoute.ConnectionSettings },
                    onRoutingClick = { route = AppSettingsRoute.Routing },
                    onRoutingProfilesClick = { route = AppSettingsRoute.RoutingProfiles },
                    onTrafficClick = { route = AppSettingsRoute.Traffic },
                    onApplicationClick = { route = AppSettingsRoute.Application },
                    onPingClick = { route = AppSettingsRoute.Ping },
                    onUrlSchemesClick = { route = AppSettingsRoute.UrlSchemes },
                    onSubscriptionsSharingClick = { route = AppSettingsRoute.SubscriptionsSharing },
                    onUpdatesClick = { route = AppSettingsRoute.Updates },
                    onApplicationLogsClick = { route = AppSettingsRoute.ApplicationLogs },
                    experimentalUnlocked = appBehavior.experimentalUnlocked,
                    onExperimentalClick = { route = AppSettingsRoute.Experimental }
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

                AppSettingsRoute.Application -> ApplicationBehaviorContent(
                    settings = appBehavior,
                    language = language,
                    onBack = { route = AppSettingsRoute.Hub },
                    onChanged = onAppBehaviorChanged,
                    onLanguageChanged = onLanguageChanged
                )

                AppSettingsRoute.Ping -> PingSettingsContent(
                    settings = appBehavior,
                    onBack = { route = AppSettingsRoute.Hub },
                    onChanged = onAppBehaviorChanged
                )

                AppSettingsRoute.UrlSchemes -> UrlSchemesContent(
                    onBack = { route = AppSettingsRoute.Hub }
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
                    onBack = { route = AppSettingsRoute.Hub },
                    onSaveClick = onSaveLogsClick,
                    onShareClick = onShareLogsClick
                )

                AppSettingsRoute.SubscriptionsSharing -> SubscriptionsSharingSettingsContent(
                    subscriptions = subscriptions,
                    onBack = { route = AppSettingsRoute.Hub },
                    onCopyConfigClick = onCopyConfigClick,
                    onShareClick = onSubscriptionShareClick,
                    onRefreshClick = onSubscriptionRefreshClick
                )

                AppSettingsRoute.Updates -> UpdatesSettingsContent(
                    settings = updateSettings,
                    statusText = updateStatusText,
                    downloadProgress = updateDownloadProgress,
                    onBack = { route = AppSettingsRoute.Hub },
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
    onAccentColorSelected: (Color?) -> Unit,
    onTextColorSelected: (Color?) -> Unit,
    onBackgroundColorSelected: (Color?) -> Unit
) {
    var showAccentPicker by remember { mutableStateOf(false) }
    var showBackgroundPicker by remember { mutableStateOf(false) }
    val s = LocalStrings.current

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
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
            ThemeState.textPresets.forEach { color ->
                val selected = currentText == color
                ColorSwatch(
                    color = color ?: MaterialTheme.colorScheme.onSurface,
                    selected = selected
                ) { onTextColorSelected(color) }
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
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
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

@Composable
private fun AppSettingsHubContent(
    selectedMode: AndroidConnectionMode,
    dynamicThemeEnabled: Boolean,
    updateSettings: AppUpdateSettings,
    subscriptionsCount: Int,
    enabled: Boolean,
    hwid: String,
    onDynamicThemeChanged: (Boolean) -> Unit,
    onAccentColorSelected: (androidx.compose.ui.graphics.Color?) -> Unit,
    onTextColorSelected: (androidx.compose.ui.graphics.Color?) -> Unit,
    onBackgroundColorSelected: (androidx.compose.ui.graphics.Color?) -> Unit,
    onConnectionSettingsClick: () -> Unit,
    onRoutingClick: () -> Unit,
    onRoutingProfilesClick: () -> Unit,
    onTrafficClick: () -> Unit,
    onApplicationClick: () -> Unit,
    onPingClick: () -> Unit,
    onUrlSchemesClick: () -> Unit,
    onSubscriptionsSharingClick: () -> Unit,
    onUpdatesClick: () -> Unit,
    onApplicationLogsClick: () -> Unit,
    experimentalUnlocked: Boolean = false,
    onExperimentalClick: () -> Unit = {}
) {
    val s = LocalStrings.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SettingsSheetHeader(
            icon = Icons.Outlined.Settings,
            title = s.settings,
            subtitle = selectedMode.shortLabel()
        )

        Spacer(Modifier.height(8.dp))

        SettingsSwitchRow(
            title = s.dynamicTheme,
            value = if (dynamicThemeEnabled) s.dynamicThemeOn else s.dynamicThemeOff,
            icon = Icons.Outlined.Palette,
            checked = dynamicThemeEnabled,
            enabled = true,
            onCheckedChange = onDynamicThemeChanged
        )

        if (!dynamicThemeEnabled) {
            ThemeColorSection(
                onAccentColorSelected = onAccentColorSelected,
                onTextColorSelected = onTextColorSelected,
                onBackgroundColorSelected = onBackgroundColorSelected
            )
        }

        // --- Маршрутизация (профили) + настройки трафика ---
        // The legacy standalone "Routing & rules" screen has been folded into routing profiles.
        SettingsGroupCard {
            SettingsGroupRow(
                title = s.routingTitle,
                subtitle = s.routingProfilesSubtitle,
                icon = Icons.Outlined.AltRoute,
                enabled = true,
                onClick = onRoutingProfilesClick
            )
            SettingsGroupDivider()
            SettingsGroupRow(
                title = s.trafficSettings,
                subtitle = s.trafficSettingsSubtitle,
                icon = Icons.Outlined.Tune,
                enabled = true,
                onClick = onTrafficClick
            )
        }

        // --- Подключение / подписки / журнал ---
        SettingsGroupCard {
            SettingsGroupRow(
                title = s.connectionSettings,
                subtitle = s.connectionSettingsSubtitle,
                icon = selectedMode.icon(),
                enabled = enabled,
                onClick = onConnectionSettingsClick
            )
            SettingsGroupDivider()
            SettingsGroupRow(
                title = s.subscriptionsSharing,
                subtitle = subscriptionsCount.subscriptionSummary(),
                icon = Icons.Outlined.Share,
                enabled = true,
                onClick = onSubscriptionsSharingClick
            )
            SettingsGroupDivider()
            SettingsGroupRow(
                title = s.updates,
                subtitle = "${LocalStrings.current.releaseChannelLabel} · ${LocalStrings.current.hoursShort(updateSettings.intervalHours)}",
                icon = Icons.Outlined.Refresh,
                enabled = true,
                onClick = onUpdatesClick
            )
            SettingsGroupDivider()
            SettingsGroupRow(
                title = s.applicationSettings,
                subtitle = s.applicationSettingsSubtitle,
                icon = Icons.Outlined.Settings,
                enabled = true,
                onClick = onApplicationClick
            )
            SettingsGroupDivider()
            SettingsGroupRow(
                title = s.pingSettings,
                subtitle = s.pingSettingsSubtitle,
                icon = Icons.Outlined.Bolt,
                enabled = true,
                onClick = onPingClick
            )
            SettingsGroupDivider()
            SettingsGroupRow(
                title = s.urlSchemes,
                subtitle = s.urlSchemesSubtitle,
                icon = Icons.Outlined.Share,
                enabled = true,
                onClick = onUrlSchemesClick
            )
            SettingsGroupDivider()
            SettingsGroupRow(
                title = s.logs,
                subtitle = s.logsSubtitle,
                icon = Icons.Outlined.History,
                enabled = true,
                onClick = onApplicationLogsClick
            )
            if (experimentalUnlocked) {
                SettingsGroupDivider()
                SettingsGroupRow(
                    title = s.experimental,
                    subtitle = s.experimentalSubtitle,
                    icon = Icons.Outlined.Tune,
                    enabled = true,
                    onClick = onExperimentalClick
                )
            }
        }

        // --- ИНФОРМАЦИЯ ---
        SettingsSectionLabel(s.info)
        val hwidClipboard = LocalClipboardManager.current
        SettingsGroupCard {
            SettingsGroupRow(
                title = s.version(CurrentAppInfo.value.version),
                icon = Icons.Outlined.Settings,
                enabled = true,
                showChevron = false
            )
            SettingsGroupDivider()
            val xrayVer = remember { runCatching { xraybridge.Xraybridge.version() }.getOrNull()?.ifBlank { null } ?: "—" }
            val singboxVer = remember { runCatching { libbox.Libbox.version() }.getOrNull()?.ifBlank { null } ?: "—" }
            val vkturnVer = remember { runCatching { freeturn.Freeturn.version() }.getOrNull()?.ifBlank { null } ?: "—" }
            val olcrtcVer = remember { runCatching { mobile.Mobile.version() }.getOrNull()?.ifBlank { null } ?: "—" }
            SettingsGroupRow(
                title = s.xrayVersion(xrayVer),
                icon = Icons.Outlined.Tune,
                enabled = true,
                showChevron = false
            )
            SettingsGroupDivider()
            SettingsGroupRow(
                title = s.singboxVersion(singboxVer),
                icon = Icons.Outlined.Tune,
                enabled = true,
                showChevron = false
            )
            SettingsGroupDivider()
            SettingsGroupRow(
                title = s.olcrtcVersion(olcrtcVer),
                icon = Icons.Outlined.Tune,
                enabled = true,
                showChevron = false
            )
            SettingsGroupDivider()
            SettingsGroupRow(
                title = s.vkturnVersion(vkturnVer),
                icon = Icons.Outlined.Tune,
                enabled = true,
                showChevron = false
            )
            SettingsGroupDivider()
            SettingsGroupRow(
                title = s.hwid(hwid.ifBlank { "—" }),
                icon = Icons.Rounded.Key,
                enabled = hwid.isNotBlank(),
                showChevron = false,
                onClick = if (hwid.isNotBlank()) {
                    { hwidClipboard.setText(AnnotatedString(hwid)) }
                } else null
            )
        }

        // --- Сообщество / помощь ---
        val communityUriHandler = LocalUriHandler.current
        SettingsGroupCard {
            SettingsGroupRow(
                title = s.community,
                subtitle = "t.me/YPtun",
                icon = Icons.Rounded.Person,
                enabled = true,
                onClick = { communityUriHandler.openUri("https://t.me/YPtun") }
            )
            SettingsGroupDivider()
            SettingsGroupRow(
                title = s.howToConnect,
                icon = Icons.Outlined.Shield,
                enabled = true,
                onClick = { communityUriHandler.openUri("https://t.me/YPtun") }
            )
        }

        Spacer(Modifier.height(4.dp))
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
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 32.dp)
    ) {
        val s = LocalStrings.current
        SettingsDetailHeader(
            title = s.connectionSettings,
            subtitle = selectedMode.settingsSummary(),
            onBack = onBack
        )

        Spacer(Modifier.height(20.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsNavigationRow(
                title = s.connectionMode,
                value = selectedMode.settingsSummary(),
                icon = selectedMode.icon(),
                enabled = enabled,
                onClick = onConnectionModeClick
            )
            SettingsNavigationRow(
                title = s.socks5Proxy,
                value = "${proxySettings.host}:${proxySettings.port}",
                icon = Icons.Rounded.Public,
                enabled = enabled,
                onClick = onProxySettingsClick
            )
            SettingsNavigationRow(
                title = s.splitTunneling,
                value = splitTunnelSettings.settingsSummary(),
                icon = Icons.Outlined.Apps,
                enabled = enabled,
                onClick = onSplitTunnelingClick
            )

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
                    // Stacked full-width so neither button's label gets clipped on narrow screens.
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
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
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Send,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(s.telegramProxyOpen)
                        }
                        OutlinedButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(tgLink))
                                Toast.makeText(context, s.telegramProxyLinkCopied, Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(s.telegramProxyCopyLink)
                        }
                    }
                }
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
    val options = listOf(AndroidConnectionMode.Tun, AndroidConnectionMode.Proxy)
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
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            LogLines(
                logs = logs,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(14.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
private fun SubscriptionsSharingSettingsContent(
    subscriptions: List<SubscriptionShareItem>,
    onBack: () -> Unit,
    onCopyConfigClick: () -> Unit,
    onShareClick: (String) -> Unit,
    onRefreshClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 620.dp)
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 12.dp)
    ) {
        SettingsDetailHeader(
            title = LocalStrings.current.subscriptionsSharing,
            subtitle = subscriptions.size.subscriptionSummary(),
            onBack = onBack
        )

        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 500.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SettingsSectionLabel(LocalStrings.current.currentConfig)

            SettingsNavigationRow(
                title = LocalStrings.current.copyFullConfig,
                value = "→ clipboard",
                icon = Icons.Outlined.ContentPaste,
                enabled = true,
                showChevron = false,
                onClick = onCopyConfigClick
            )

            SettingsSectionLabel(LocalStrings.current.subscriptionsSection)

            if (subscriptions.isEmpty()) {
                EmptyAppsState(
                    title = LocalStrings.current.noSubscriptions,
                    subtitle = LocalStrings.current.noSubscriptionsSubtitle
                )
            } else {
                subscriptions.forEach { item ->
                    SubscriptionShareRow(
                        item = item,
                        onShareClick = { onShareClick(item.url) },
                        onRefreshClick = { onRefreshClick(item.url) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscriptionShareRow(
    item: SubscriptionShareItem,
    onShareClick: () -> Unit,
    onRefreshClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.url,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Text(
                text = item.subscriptionSummary(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Panel announcement (Remnawave/Happ `announce` header), shown verbatim to the user.
            item.announce?.takeIf { it.isNotBlank() }?.let { announce ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = announce,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            val subUriHandler = LocalUriHandler.current
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onShareClick) {
                    Text(LocalStrings.current.qrShare)
                }
                TextButton(onClick = onRefreshClick) {
                    Text(LocalStrings.current.refresh)
                }
                // Panel-advertised links (Remnawave/Happ `profile-web-page-url` / `support-url` headers).
                item.webPageUrl?.takeIf { it.isNotBlank() }?.let { web ->
                    TextButton(onClick = { runCatching { subUriHandler.openUri(web) } }) {
                        Text(LocalStrings.current.subscriptionWebPage)
                    }
                }
                item.supportUrl?.takeIf { it.isNotBlank() }?.let { support ->
                    TextButton(onClick = { runCatching { subUriHandler.openUri(support) } }) {
                        Text(LocalStrings.current.subscriptionSupport)
                    }
                }
            }
        }
    }
}

/** A rounded card that visually groups several [SettingsGroupRow]s with thin dividers. */
@Composable
private fun SettingsGroupCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column { content() }
    }
}

/** A single row inside a [SettingsGroupCard]: blue leading icon, title, optional subtitle, trailing slot. */
@Composable
private fun SettingsGroupRow(
    title: String,
    icon: ImageVector,
    subtitle: String? = null,
    enabled: Boolean = true,
    showChevron: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(26.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (trailing != null) {
            trailing()
        } else if (showChevron && onClick != null) {
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/** Thin divider between rows inside a group card. */
@Composable
private fun SettingsGroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
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
            .height(72.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
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
            .height(72.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
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
private fun SettingsSheetHeader(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HeaderIcon(icon = icon)

        Spacer(Modifier.width(14.dp))

        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
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
private fun HeaderIcon(icon: ImageVector) {
    Surface(
        modifier = Modifier.size(46.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.padding(11.dp)
        )
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
            MaterialTheme.colorScheme.outlineVariant
        },
        label = "splitTunnelRoutingOptionBorder"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(74.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
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
                    MaterialTheme.colorScheme.surfaceVariant
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
            MaterialTheme.colorScheme.outlineVariant
        },
        label = "selectableSettingsCardBorder"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(82.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
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
                    MaterialTheme.colorScheme.surfaceVariant
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
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 2.dp)
    )
}

@Composable
private fun SplitTunnelStatusCard(
    settings: AndroidSplitTunnelSettings,
    selectedMode: AndroidConnectionMode,
    isConnectionActive: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
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
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
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
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
            else -> colors.outlineVariant
        },
        label = "splitTunnelAppRowBorder"
    )
    val iconContainerColor by animateColorAsState(
        targetValue = when {
            autoSelected -> colors.surfaceContainerHighest
            selected -> colors.primary
            else -> colors.surfaceVariant
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
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
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
                    color = colors.surfaceContainerHighest,
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
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
        shape = RoundedCornerShape(16.dp),
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
            .clip(RoundedCornerShape(16.dp))
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

@Composable
private fun ApplicationBehaviorContent(
    settings: AppBehaviorSettings,
    language: AppLanguage,
    onBack: () -> Unit,
    onChanged: (AppBehaviorSettings) -> Unit,
    onLanguageChanged: (AppLanguage) -> Unit
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
                text = s.applicationSettings,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        RoutingToggleRow(
            title = s.autoConnectTitle,
            subtitle = s.autoConnectSubtitle,
            checked = settings.autoConnectOnLaunch
        ) { onChanged(settings.copy(autoConnectOnLaunch = it)) }

        RoutingToggleRow(
            title = s.energySaverTitle,
            subtitle = s.energySaverSubtitle,
            checked = settings.energySaver
        ) { onChanged(settings.copy(energySaver = it)) }

        RoutingToggleRow(
            title = s.confirmDeleteTitle,
            subtitle = s.confirmDeleteSubtitle,
            checked = settings.confirmBeforeDelete
        ) { onChanged(settings.copy(confirmBeforeDelete = it)) }

        RoutingToggleRow(
            title = s.notifSpeed,
            subtitle = s.notifSpeedSubtitle,
            checked = settings.showSpeedInNotification
        ) { onChanged(settings.copy(showSpeedInNotification = it)) }

        RoutingToggleRow(
            title = "Комнаты / серверы в уведомлении",
            subtitle = "Показывать «подключено/всего» комнат (olcRTC мультикомната) или серверов (VK-TURN мультисервер freeturn)",
            checked = settings.showRoomsInNotification
        ) { onChanged(settings.copy(showRoomsInNotification = it)) }

        RoutingToggleRow(
            title = s.showSubscriptionExpiryTitle,
            subtitle = s.showSubscriptionExpirySubtitle,
            checked = settings.showSubscriptionExpiry
        ) { onChanged(settings.copy(showSubscriptionExpiry = it)) }

        RoutingToggleRow(
            title = "Уведомлять об окончании подписки",
            subtitle = "Локальное уведомление за несколько дней до конца подписки.",
            checked = settings.notifySubscriptionExpiry
        ) { onChanged(settings.copy(notifySubscriptionExpiry = it)) }

        RoutingToggleRow(
            title = "Уведомления от панели",
            subtitle = "Показывать системное уведомление, когда владелец панели присылает объявление.",
            checked = settings.notifyPanelAnnouncements
        ) { onChanged(settings.copy(notifyPanelAnnouncements = it)) }

        RoutingToggleRow(
            title = "Автоустановка на VPS",
            subtitle = "Выполняет развёртывание движка на VPS.",
            checked = settings.allowVpsAutoInstall
        ) { onChanged(settings.copy(allowVpsAutoInstall = it)) }

        // Subscription User-Agent selector is intentionally NOT rendered here (no UI). The default —
        // YPtun main fetch + automatic FakeDNS enrichment from a Happ-UA fetch — is correct for everyone.
        // The selector code is preserved (unused) in [SubscriptionUserAgentSelector] below and can be
        // dropped back into this screen if it ever needs to be exposed again.

        // App-wide engine for VLESS-like transports. Applies only when a server's own core is "Auto";
        // an explicit per-server choice overrides it (xhttp/raw-Xray always force Xray regardless).
        SettingsSectionLabel(s.globalEngineLabel)
        Text(
            text = s.globalEngineSubtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val engineOptions = listOf(
                ProxyCore.Auto to s.globalEngineAuto,
                ProxyCore.SingBox to "sing-box",
                ProxyCore.Xray to "Xray",
            )
            engineOptions.forEach { (core, title) ->
                FilterChip(
                    selected = settings.globalProxyCore == core,
                    onClick = { onChanged(settings.copy(globalProxyCore = core)) },
                    label = { Text(title) }
                )
            }
        }

        SettingsSectionLabel(s.language)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val options = listOf(
                AppLanguage.System to "Авто / Auto",
                AppLanguage.Russian to "🇷🇺 Русский",
                AppLanguage.English to "🇺🇸 English",
                AppLanguage.Persian to "🇮🇷 فارسی",
                AppLanguage.Chinese to "🇨🇳 中文"
            )
            options.forEach { (lang, title) ->
                FilterChip(
                    selected = language == lang,
                    onClick = { onLanguageChanged(lang) },
                    label = { Text(title) }
                )
            }
        }
    }
}

/**
 * Subscription User-Agent selector. PRESERVED but intentionally NOT shown anywhere (no UI). The fetch
 * defaults to YPtun + automatic FakeDNS enrichment from a Happ-UA request, which is right for everyone;
 * this lets the choice be re-exposed later (drop a call into AppBehaviorSettingsContent) without
 * rewriting it. Suppress the "unused" lint since keeping it is deliberate.
 */
@Suppress("unused")
@Composable
private fun SubscriptionUserAgentSelector(
    settings: AppBehaviorSettings,
    onChanged: (AppBehaviorSettings) -> Unit
) {
    val s = LocalStrings.current
    SettingsSectionLabel(s.subscriptionUserAgentLabel)
    Text(
        text = s.subscriptionUserAgentSubtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val uaOptions = listOf(
            AppBehaviorSettings.SUB_UA_HAPP to "Happ/1.0",
            AppBehaviorSettings.SUB_UA_YPTUN to "YPtun",
        )
        uaOptions.forEach { (mode, title) ->
            FilterChip(
                selected = settings.subscriptionUserAgent == mode,
                onClick = { onChanged(settings.copy(subscriptionUserAgent = mode)) },
                label = { Text(title) }
            )
        }
    }
}

/** Dedicated «Пинг» screen: how locations/inbounds are probed + the proxy-probe target URL. */
@Composable
private fun PingSettingsContent(
    settings: AppBehaviorSettings,
    onBack: () -> Unit,
    onChanged: (AppBehaviorSettings) -> Unit
) {
    val s = LocalStrings.current
    val isProxyMode = settings.pingMode == AppBehaviorSettings.PING_PROXY_GET ||
        settings.pingMode == AppBehaviorSettings.PING_PROXY_HEAD
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
                text = s.pingSettings,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        SettingsSectionLabel(s.pingMethod)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val pingOptions = listOf(
                AppBehaviorSettings.PING_AUTO to s.pingModeAuto,
                AppBehaviorSettings.PING_TCP to s.pingModeTcp,
                AppBehaviorSettings.PING_ICMP to s.pingModeIcmp,
                AppBehaviorSettings.PING_PROXY_GET to s.pingModeProxyGet,
                AppBehaviorSettings.PING_PROXY_HEAD to s.pingModeProxyHead,
            )
            pingOptions.forEach { (mode, title) ->
                FilterChip(
                    selected = settings.pingMode == mode,
                    onClick = { onChanged(settings.copy(pingMode = mode)) },
                    label = { Text(title) }
                )
            }
        }
        // The target URL is meaningful ONLY for the proxy GET/HEAD probes; TCP/ICMP probe the
        // location's own server, so the field is disabled for those modes.
        OutlinedTextField(
            value = settings.pingUrl,
            onValueChange = { onChanged(settings.copy(pingUrl = it)) },
            label = { Text(s.pingTarget) },
            placeholder = { Text(s.pingTargetHint) },
            singleLine = true,
            enabled = isProxyMode,
            modifier = Modifier.fillMaxWidth()
        )

        SettingsSectionLabel(s.pingResultLabel)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val resultOptions = listOf(
                AppBehaviorSettings.PING_RESULT_TIME to s.pingResultTime,
                AppBehaviorSettings.PING_RESULT_ICON to s.pingResultIcon,
            )
            resultOptions.forEach { (mode, title) ->
                FilterChip(
                    selected = settings.pingResultDisplay == mode,
                    onClick = { onChanged(settings.copy(pingResultDisplay = mode)) },
                    label = { Text(title) }
                )
            }
        }

        RoutingToggleRow(
            title = s.savePingResultsTitle,
            subtitle = s.savePingResultsSubtitle,
            checked = settings.savePingResults
        ) { onChanged(settings.copy(savePingResults = it)) }

        RoutingToggleRow(
            title = s.showAliveCountTitle,
            subtitle = s.showAliveCountSubtitle,
            checked = settings.showSubscriptionAliveCount
        ) { onChanged(settings.copy(showSubscriptionAliveCount = it)) }

        PingParallelismRow(
            value = settings.effectivePingParallelism(),
            onChange = { onChanged(settings.copy(pingParallelism = it)) }
        )
    }
}

/** Stepper for the parallel-ping count (ping speed): default 5, range 1..20. */
@Composable
private fun PingParallelismRow(value: Int, onChange: (Int) -> Unit) {
    val s = LocalStrings.current
    val min = AppBehaviorSettings.MIN_PING_PARALLELISM
    val max = AppBehaviorSettings.MAX_PING_PARALLELISM
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = s.pingThreadsTitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = s.pingThreadsSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedButton(
            onClick = { onChange((value - 1).coerceIn(min, max)) },
            enabled = value > min,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(40.dp)
        ) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(40.dp)
        )
        OutlinedButton(
            onClick = { onChange((value + 1).coerceIn(min, max)) },
            enabled = value < max,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(40.dp)
        ) { Text("+", style = MaterialTheme.typography.titleLarge) }
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
        shape = RoundedCornerShape(14.dp),
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
    object SubscriptionsSharing : AppSettingsRoute(1)
    object Routing : AppSettingsRoute(1)
    object RoutingProfiles : AppSettingsRoute(1)
    object Traffic : AppSettingsRoute(1)
    object Application : AppSettingsRoute(1)
    object Ping : AppSettingsRoute(1)
    object UrlSchemes : AppSettingsRoute(1)
    object Updates : AppSettingsRoute(1)
    object ApplicationLogs : AppSettingsRoute(1)
    object Experimental : AppSettingsRoute(1)
    data class AppList(val list: AndroidSplitTunnelList) : AppSettingsRoute(2)
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
    }
}

private fun Int.subscriptionSummary(): String {
    return when (this) {
        0 -> "No HTTPS subscriptions"
        1 -> "1 HTTPS subscription"
        else -> "$this HTTPS subscriptions"
    }
}

private fun SubscriptionShareItem.subscriptionSummary(): String {
    val interval = updateIntervalHours?.let { "every ${it}h" } ?: "default interval"
    val count = when (locationCount) {
        1 -> "1 location"
        else -> "$locationCount locations"
    }
    val refresh = lastRefreshAtEpochMs?.let { "last refresh ${it.formatDateTime()}" } ?: "not refreshed yet"
    return "$interval · $count · $refresh"
}

private fun Long.formatDateTime(): String {
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(this))
}

private fun AndroidConnectionMode.shortLabel(): String {
    return when (this) {
        AndroidConnectionMode.Tun -> "TUN"
        AndroidConnectionMode.Proxy -> "SOCKS"
    }
}

private fun AndroidConnectionMode.subtitle(): String {
    val s = stringsFor(LocalizationState.effective)
    return when (this) {
        AndroidConnectionMode.Tun -> s.fullTunnel
        AndroidConnectionMode.Proxy -> s.localSocksProxy
    }
}

private fun AndroidConnectionMode.settingsSummary(): String {
    val s = stringsFor(LocalizationState.effective)
    return when (this) {
        AndroidConnectionMode.Tun -> "TUN · ${s.fullTunnel}"
        AndroidConnectionMode.Proxy -> "SOCKS · ${s.localSocksProxy}"
    }
}

private fun AndroidConnectionMode.description(): String {
    val s = stringsFor(LocalizationState.effective)
    return when (this) {
        AndroidConnectionMode.Tun -> s.systemVpnInterface
        AndroidConnectionMode.Proxy -> s.localSocksEndpoint
    }
}

private fun AndroidConnectionMode.icon() = when (this) {
    AndroidConnectionMode.Tun -> Icons.Outlined.Shield
    AndroidConnectionMode.Proxy -> Icons.Rounded.Public
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
        selectedMode == AndroidConnectionMode.Proxy -> s.savedForTunMode
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
