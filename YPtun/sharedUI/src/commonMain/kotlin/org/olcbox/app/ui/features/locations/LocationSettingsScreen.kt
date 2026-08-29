package org.olcbox.app.ui.features.locations

import org.olcbox.app.ui.i18n.LocalStrings
import org.olcbox.app.ui.i18n.LocalizationState
import org.olcbox.app.ui.i18n.stringsFor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.MeetingRoom
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import org.olcbox.app.ui.features.locations.components.SshAuthFields
import org.olcbox.app.vpn.wdtt.WdttInstallOptions
import org.olcbox.app.vpn.wdtt.rememberWdttServerInstaller
import org.olcbox.app.vpn.freeturn.FreeturnExit
import org.olcbox.app.vpn.freeturn.FreeturnInstallOptions
import org.olcbox.app.vpn.freeturn.rememberFreeturnServerInstaller
import org.olcbox.app.vpn.dnstt.DnsttInstallOptions
import org.olcbox.app.vpn.dnstt.rememberDnsttServerInstaller
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import org.olcbox.app.data.importer.VkTurnDraft
import org.olcbox.app.data.model.AdvancedCoreConfig
import org.olcbox.app.data.model.DnsttConfig
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.ExtraRoom
import org.olcbox.app.data.model.RoutingProfile
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.ProxyCore
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.data.model.VkTurnConfig
import org.olcbox.app.ui.components.PingButton
import org.olcbox.app.ui.features.home.HomeScreenViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationSettingsTopBar(
    shareEnabled: Boolean,
    onBack: () -> Unit,
    onShare: () -> Unit
) {
    TopAppBar(
        title = { Text(LocalStrings.current.locationSettingsTitle) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }
        },
        actions = {
            IconButton(
                onClick = onShare,
                enabled = shareEnabled,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(48.dp),
                shape = CircleShape
            ) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = "Share location"
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationSettingsScreen(
    viewModel: LocationViewModel,
    homeViewModel: HomeScreenViewModel,
    allowVpsAutoInstall: Boolean = false,
    onShareLocationRequested: (LocationConfig) -> Unit = {},
    onBack: () -> Unit
) {
    val s = LocalStrings.current
    val config = viewModel.editingConfig
    val name = viewModel.editingName
    val isSaving = viewModel.isSaving
    val normalizedTransport = LocationConfig.normalizeTransport(
        config.transport,
        config.bypassProvider
    )
    val density = LocalDensity.current
    val isKeyboardVisible = WindowInsets.ime.getBottom(density) > 0

    var showWdttInstall by remember { mutableStateOf(false) }
    if (showWdttInstall) {
        WdttInstallDialog(
            draft = viewModel.editingVkTurn,
            onApplyDraft = { update -> viewModel.updateVkTurnDraft(update) },
            onDismiss = { showWdttInstall = false }
        )
    }

    var showFreeturnInstall by remember { mutableStateOf(false) }
    if (showFreeturnInstall) {
        FreeturnInstallDialog(
            draft = viewModel.editingVkTurn,
            onApplyDraft = { update -> viewModel.updateVkTurnDraft(update) },
            onDismiss = { showFreeturnInstall = false }
        )
    }

    var showDnsttInstall by remember { mutableStateOf(false) }
    if (showDnsttInstall) {
        DnsttInstallDialog(
            config = viewModel.editingDnstt,
            onApplyConfig = { update -> viewModel.updateDnstt(update) },
            onDismiss = { showDnsttInstall = false }
        )
    }

    Scaffold(
        topBar = {
            LocationSettingsTopBar(
                shareEnabled = viewModel.isFormValid && !isSaving,
                onBack = onBack,
                onShare = { onShareLocationRequested(viewModel.editingConfig) }
            )
        },
        bottomBar = {
            if (!isKeyboardVisible) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ActionsBar(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        showDelete = viewModel.editingId != null,
                        isSaving = isSaving,
                        isFormValid = viewModel.isFormValid,
                        onDelete = {
                            viewModel.editingId?.let { id ->
                                viewModel.deleteLocation(id) { onBack() }
                            } ?: onBack()
                        },
                        onSave = {
                            viewModel.saveEditing {
                                homeViewModel.loadCurrentConfig()
                                homeViewModel.restartVpnIfRunning()
                                onBack()
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SettingsTextField(
                    value = name,
                    onValueChange = viewModel::onNameChanged,
                    label = s.fieldName,
                    placeholder = s.locationNamePlaceholder,
                    enabled = !isSaving,
                    isError = viewModel.nameError != null,
                    supportingText = viewModel.nameError,
                    leadingIcon = Icons.Rounded.Public,
                    onClear = { viewModel.onNameChanged("") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
            }

            item {
                EngineSelector(
                    selected = config.engine,
                    enabled = !isSaving,
                    onSelected = viewModel::onEngineChanged
                )
            }

            // Routing profile applies wherever a rule-capable core runs (sing-box / Xray): every
            // engine except pure Stealth (olcRTC has no routing layer).
            if (config.engine != EngineType.Stealth) {
                item {
                    RoutingProfileSelector(
                        selected = config.routingProfileId,
                        profiles = homeViewModel.routingProfileChoices(),
                        enabled = !isSaving,
                        onSelected = viewModel::onRoutingProfileChanged
                    )
                }
            }

            if (config.engine == EngineType.Standard || config.engine == EngineType.Chain) {
                // Main proxy (основной аутбаунд) is the always-on primary outbound, but its editor field
                // is hidden by request — it comes from the imported/subscription config (config.proxy)
                // and is applied as-is. Only the core options + the optional second (cascade) proxy show.
                item {
                    CoreSelector(
                        selected = config.core,
                        enabled = !isSaving,
                        // xhttp / FakeDNS configs can only run on xray-core — lock out sing-box.
                        singBoxLocked = config.requiresXray(),
                        onSelected = viewModel::onCoreChanged
                    )
                }
                // Advanced core options appear only when a specific core (not Auto) is chosen.
                if (config.core != ProxyCore.Auto) {
                    item {
                        AdvancedCoreSection(
                            core = config.core,
                            advanced = config.advanced ?: AdvancedCoreConfig(),
                            enabled = !isSaving,
                            onChange = viewModel::updateAdvanced
                        )
                    }
                }
                // Optional SECOND (cascade) proxy. Toggling it on reveals a second link chained ON TOP
                // of the main: traffic → main → second. Toggling off clears it (→ main only).
                item {
                    var additionalOn by remember(config.proxy2 != null) {
                        mutableStateOf(config.proxy2 != null)
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        VkTurnSwitchRow(
                            label = LocalStrings.current.enableAdditionalProxy,
                            checked = additionalOn,
                            enabled = !isSaving,
                            onCheckedChange = { on ->
                                additionalOn = on
                                if (!on) viewModel.onProxy2LinkChanged("")
                            }
                        )
                        if (additionalOn) {
                            ProxyField(
                                title = LocalStrings.current.additionalProxySection,
                                subtitle = LocalStrings.current.additionalProxySubtitle,
                                link = viewModel.editingProxy2Link,
                                currentProxy = config.proxy2,
                                error = viewModel.proxy2Error,
                                enabled = !isSaving,
                                onChange = viewModel::onProxy2LinkChanged
                            )
                        }
                    }
                }
            }

            if (config.engine == EngineType.VkTurn) {
                vkTurnSection(
                    draft = viewModel.editingVkTurn,
                    enabled = !isSaving,
                    showAutoInstall = allowVpsAutoInstall,
                    onChange = viewModel::updateVkTurnDraft,
                    onWdttAutoInstall = { showWdttInstall = true },
                    onFreeturnAutoInstall = { showFreeturnInstall = true }
                )
            }

            if (config.engine == EngineType.Dnstt) {
                dnsttSection(
                    config = viewModel.editingDnstt,
                    enabled = !isSaving,
                    showAutoInstall = allowVpsAutoInstall,
                    onChange = viewModel::updateDnstt,
                    onDnsttAutoInstall = { showDnsttInstall = true }
                )
            }

            if (config.engine == EngineType.Stealth || config.engine == EngineType.Chain) {
            item {
                ConnectionTypePicker(
                    selectedProvider = config.bypassProvider,
                    serviceProvider = viewModel.editingServiceProvider,
                    enabled = !isSaving,
                    onProviderSelected = viewModel::onBypassProviderChanged
                )
            }

            if (!isJitsiProvider(config.bypassProvider)) {
                item {
                    ProviderPicker(
                        selectedProvider = config.bypassProvider,
                        enabled = !isSaving,
                        onProviderSelected = viewModel::onBypassProviderChanged
                    )
                }
            }

            if (LocationConfig.supportedTransportsForProvider(config.bypassProvider).size > 1) {
                item {
                    TransportPicker(
                        selectedProvider = config.bypassProvider,
                        selectedTransport = config.transport,
                        enabled = !isSaving,
                        onTransportSelected = viewModel::onTransportChanged
                    )
                }
            }

            if (normalizedTransport == LocationConfig.TRANSPORT_VP8CHANNEL) {
                item {
                    Vp8OptionsCard(
                        fps = config.vp8Fps,
                        batch = config.vp8Batch,
                        enabled = !isSaving,
                        onFpsChanged = viewModel::onVp8FpsChanged,
                        onBatchChanged = viewModel::onVp8BatchChanged
                    )
                }
            }

            item {
                SettingsTextField(
                    value = config.id,
                    onValueChange = viewModel::onServerChanged,
                    label = roomIdLabel(config.bypassProvider),
                    placeholder = roomIdPlaceholder(config.bypassProvider),
                    enabled = !isSaving,
                    isError = viewModel.serverError != null,
                    supportingText = viewModel.serverError,
                    leadingIcon = Icons.Rounded.MeetingRoom,
                    onClear = { viewModel.onServerChanged("") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = roomKeyboardType(config.bypassProvider),
                        imeAction = ImeAction.Next
                    )
                )
            }

            item {
                SettingsTextField(
                    value = config.key,
                    onValueChange = viewModel::onPasswordChanged,
                    label = LocalStrings.current.encryptionKey,
                    placeholder = "64 hex characters",
                    enabled = !isSaving,
                    isError = viewModel.keyError != null,
                    supportingText = viewModel.keyError,
                    leadingIcon = Icons.Rounded.Key,
                    onClear = { viewModel.onPasswordChanged("") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
            }

            item {
                MultiRoomSection(
                    enabled = config.multiRoomEnabled,
                    rooms = config.extraRooms,
                    mainProvider = config.bypassProvider,
                    saving = isSaving,
                    isChain = config.engine == EngineType.Chain,
                    bondEnabled = config.multiRoomBond,
                    bondPort = config.bondPort.takeIf { it > 0 }?.toString() ?: "",
                    onToggle = viewModel::onMultiRoomToggle,
                    onAdd = viewModel::onExtraRoomAdd,
                    onChange = viewModel::onExtraRoomChanged,
                    onRemove = viewModel::onExtraRoomRemoved,
                    onBondToggle = viewModel::onMultiRoomBondToggle,
                    onBondPortChange = viewModel::onBondPortChanged
                )
            }

            }

            item {
                PingButton(
                    homeViewModel = homeViewModel,
                    configGetter = { viewModel.editingConfig }
                )
            }
        }
    }
}

/** Multi-room (Stealth/Chain): toggle + up to [LocationConfig.MAX_EXTRA_ROOMS] extra rooms (provider/room/key). */
@Composable
private fun MultiRoomSection(
    enabled: Boolean,
    rooms: List<ExtraRoom>,
    mainProvider: String,
    saving: Boolean,
    isChain: Boolean,
    bondEnabled: Boolean,
    bondPort: String,
    onToggle: (Boolean) -> Unit,
    onAdd: () -> Unit,
    onChange: (Int, (ExtraRoom) -> ExtraRoom) -> Unit,
    onRemove: (Int) -> Unit,
    onBondToggle: (Boolean) -> Unit,
    onBondPortChange: (String) -> Unit
) {
    val providers = listOf(
        LocationConfig.PROVIDER_TELEMOST,
        LocationConfig.PROVIDER_WB_STREAM,
        LocationConfig.PROVIDER_JITSI
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionTitle(
            title = "Мультикомната",
            subtitle = "До ${LocationConfig.MAX_EXTRA_ROOMS + 1} комнат одновременно — скорость складывается (балансировка по соединениям)"
        )
        VkTurnSwitchRow(
            label = "Включить мультикомнату",
            checked = enabled,
            enabled = !saving,
            onCheckedChange = onToggle
        )
        if (enabled) {
            // Stage-2 bond (Chain only): aggregate the SINGLE proxy flow across rooms instead of
            // round-robining connections. Needs the bond reassembler running on the olcRTC server.
            if (isChain) {
                VkTurnSwitchRow(
                    label = "Бондинг потока (Stage-2)",
                    checked = bondEnabled,
                    enabled = !saving,
                    onCheckedChange = onBondToggle
                )
                if (bondEnabled) {
                    VkTurnField(
                        value = bondPort,
                        onValueChange = { v -> onBondPortChange(v.filter(Char::isDigit)) },
                        label = "Порт bond-сервера",
                        placeholder = "${LocationConfig.DEFAULT_BOND_PORT} (по умолчанию) — на сервере olcRTC",
                        enabled = !saving,
                        keyboardType = KeyboardType.Number
                    )
                }
            }
            rooms.forEachIndexed { index, room ->
                val prov = room.provider.ifBlank { mainProvider }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Комната ${index + 2}",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onRemove(index) }, enabled = !saving) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Удалить")
                        }
                    }
                    SettingsDropdown(
                        label = "Провайдер",
                        selectedValue = prov,
                        options = providers,
                        enabled = !saving,
                        onValueSelected = { v -> onChange(index) { it.copy(provider = v, transport = "") } },
                        valueLabel = { it }
                    )
                    // Per-room transport: each room may run a DIFFERENT transport than the main one (the
                    // data model + multiRoomSpecs already honour ExtraRoom.transport). Only offered when
                    // the provider actually supports more than one, mirroring the main transport row.
                    if (LocationConfig.supportedTransportsForProvider(prov).size > 1) {
                        TransportPicker(
                            selectedProvider = prov,
                            selectedTransport = room.transport,
                            enabled = !saving,
                            onTransportSelected = { v -> onChange(index) { it.copy(transport = v) } }
                        )
                    }
                    SettingsTextField(
                        value = room.room,
                        onValueChange = { v -> onChange(index) { it.copy(room = v) } },
                        label = roomIdLabel(prov),
                        placeholder = roomIdPlaceholder(prov),
                        enabled = !saving,
                        isError = false,
                        supportingText = null,
                        leadingIcon = Icons.Rounded.MeetingRoom,
                        onClear = { onChange(index) { it.copy(room = "") } },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = roomKeyboardType(prov),
                            imeAction = ImeAction.Next
                        )
                    )
                    SettingsTextField(
                        value = room.key,
                        onValueChange = { v -> onChange(index) { it.copy(key = v) } },
                        label = LocalStrings.current.encryptionKey,
                        placeholder = "64 hex",
                        enabled = !saving,
                        isError = false,
                        supportingText = null,
                        leadingIcon = Icons.Rounded.Key,
                        visualTransformation = PasswordVisualTransformation(),
                        onClear = { onChange(index) { it.copy(key = "") } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                    )
                }
            }
            if (rooms.size < LocationConfig.MAX_EXTRA_ROOMS) {
                Button(onClick = onAdd, enabled = !saving) {
                    Text("Добавить комнату")
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(
    title: String,
    subtitle: String? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EngineSelector(
    selected: EngineType,
    enabled: Boolean,
    onSelected: (EngineType) -> Unit
) {
    val options = listOf(
        EngineType.Stealth,
        EngineType.Standard,
        EngineType.Chain,
        EngineType.VkTurn,
        EngineType.Dnstt
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionTitle(title = LocalStrings.current.engineSection, subtitle = engineSubtitle(selected))

        // ONE cohesive 2×2 block: a single rounded outline drawn ONCE around all four engines, with thin
        // inner dividers between the cells — no per-row pills, no offset hack, no seam/gap. Two rows of
        // two equal-width cells; the row height tracks the tallest cell (IntrinsicSize.Min) so the
        // vertical divider spans it. clip() rounds the selected-cell highlight to the block's corners.
        val shape = RoundedCornerShape(20.dp)
        val outline = MaterialTheme.colorScheme.outline
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .border(BorderStroke(1.dp, outline), shape)
        ) {
            options.chunked(2).forEachIndexed { rowIndex, rowOptions ->
                if (rowIndex > 0) HorizontalDivider(color = outline)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                ) {
                    rowOptions.forEachIndexed { colIndex, engine ->
                        if (colIndex > 0) VerticalDivider(color = outline)
                        EngineCell(
                            label = engineLabel(engine),
                            selected = selected == engine,
                            enabled = enabled,
                            onClick = { onSelected(engine) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/** A single segment of the 2×2 engine block: fills its half, highlights (filled + check) when selected. */
@Composable
private fun EngineCell(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val content = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = modifier
            .fillMaxHeight()
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = label,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyField(
    title: String,
    subtitle: String,
    link: String,
    currentProxy: ProxyProfile?,
    error: String?,
    enabled: Boolean,
    onChange: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SectionTitle(
            title = title,
            subtitle = subtitle
        )
        OutlinedTextField(
            value = link,
            onValueChange = onChange,
            label = { Text(LocalStrings.current.proxyLinkOrConfig) },
            placeholder = { Text("vless://…  or  { \"type\": … }") },
            enabled = enabled,
            isError = error != null,
            supportingText = {
                val text = error
                    ?: currentProxy?.let { "${engineProtocolLabel(it.type)} · ${it.displayName()}" }
                if (text != null) Text(text)
            },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * dnstt (DNS tunnel) editor: tunnel domain, the server's Noise public key and a UDP DNS resolver.
 * The dnstt client raises a local listener that transparently forwards each TCP connection through
 * DNS TXT queries to the dnstt-server, which relays to its upstream SOCKS5 — so the local port
 * behaves as that SOCKS5 and the TUN bridge consumes it directly.
 */
private fun LazyListScope.dnsttSection(
    config: DnsttConfig,
    enabled: Boolean,
    showAutoInstall: Boolean,
    onChange: ((DnsttConfig) -> DnsttConfig) -> Unit,
    onDnsttAutoInstall: () -> Unit
) {
    item {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionTitle(
                title = "DNSTT — туннель через DNS",
                subtitle = "Домен и публичный ключ от твоего dnstt-сервера; резолвер — любой UDP-DNS, который его дотягивает"
            )
            VkTurnField(
                value = config.domain,
                onValueChange = { v -> onChange { it.copy(domain = v.trim()) } },
                label = "Домен туннеля",
                placeholder = "t.example.com",
                enabled = enabled,
                keyboardType = KeyboardType.Uri
            )
            VkTurnField(
                value = config.pubKey,
                onValueChange = { v -> onChange { it.copy(pubKey = v.trim()) } },
                label = "Публичный ключ сервера (hex)",
                placeholder = "Noise public key, 64 hex-символа",
                enabled = enabled
            )
            VkTurnField(
                value = config.resolver,
                onValueChange = { v -> onChange { it.copy(resolver = v.trim()) } },
                label = "DNS-резолвер (UDP)",
                placeholder = "1.1.1.1:53",
                enabled = enabled,
                keyboardType = KeyboardType.Uri
            )
            // Auto-install the dnstt-server on a VPS (direct mode: resolver→VPS:port). Hidden unless
            // the user enabled "Автоустановка на VPS" in app settings (off by default).
            if (showAutoInstall) {
                OutlinedButton(
                    onClick = onDnsttAutoInstall,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Автоустановка на VPS")
                }
            }
        }
    }

    // Optional proxy chained ON TOP of the dnstt tunnel: traffic → dnstt → proxy → internet (the
    // proxy server is dialled THROUGH the dnstt SOCKS), so the public exit is the proxy. Same idea as
    // "proxy over VK-TURN".
    item {
        var proxyOn by remember(config.proxyLink.isNotBlank()) {
            mutableStateOf(config.proxyLink.isNotBlank())
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionTitle(
                title = "Прокси поверх DNSTT",
                subtitle = "VLESS/Trojan/SS, который дозванивается ЧЕРЕЗ dnstt-туннель — выходной IP будет прокси, а не dnstt-сервер"
            )
            VkTurnSwitchRow(
                label = "Прокси поверх DNSTT",
                checked = proxyOn,
                enabled = enabled,
                onCheckedChange = { on ->
                    proxyOn = on
                    if (!on) onChange { it.copy(proxyLink = "") }
                }
            )
            if (proxyOn) {
                OutlinedTextField(
                    value = config.proxyLink,
                    onValueChange = { v -> onChange { it.copy(proxyLink = v) } },
                    label = { Text(LocalStrings.current.proxyLink) },
                    placeholder = { Text("vless://… / trojan://… / ss://…") },
                    enabled = enabled,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                CoreSelector(
                    selected = config.proxyCore,
                    enabled = enabled,
                    onSelected = { v -> onChange { it.copy(proxyCore = v) } }
                )
            }
        }
    }
}

/**
 * Detailed VK-TURN (freeturn + WireGuard) editor. Every field feeds [VkTurnDraft]; the view model
 * rebuilds the freeturn:// link and the sing-box WireGuard outbound from it on each change.
 */
private fun LazyListScope.vkTurnSection(
    draft: VkTurnDraft,
    enabled: Boolean,
    showAutoInstall: Boolean,
    onChange: ((VkTurnDraft) -> VkTurnDraft) -> Unit,
    onWdttAutoInstall: () -> Unit,
    onFreeturnAutoInstall: () -> Unit
) {
    item {
        VkTurnLinksField(
            value = draft.vkLink,
            enabled = enabled,
            onChange = { v -> onChange { it.copy(vkLink = v) } }
        )
    }

    // VK-TURN transport core: freeturn (default) vs WDTT. WDTT aggregates a single WG flow across the
    // VK call-links via chunk-affinity dispatch; it dials its own wdtt-server and reuses the WG exit below.
    item {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionTitle(
                title = "Транспортное ядро VK-TURN",
                subtitle = "freeturn — стандартный клиент; WDTT — агрегация одного WG-потока по звонкам (chunk-dispatch)"
            )
            SettingsDropdown(
                label = "Ядро",
                selectedValue = draft.core.ifBlank { VkTurnConfig.CORE_FREETURN },
                options = listOf(VkTurnConfig.CORE_FREETURN, VkTurnConfig.CORE_WDTT),
                enabled = enabled,
                onValueSelected = { v -> onChange { it.copy(core = v) } },
                valueLabel = { if (it == VkTurnConfig.CORE_WDTT) "WDTT (агрегация по звонкам)" else "freeturn (стандарт)" }
            )
            if (draft.core == VkTurnConfig.CORE_WDTT) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    VkTurnField(
                        value = draft.wdttPeer,
                        onValueChange = { v -> onChange { it.copy(wdttPeer = v.trim()) } },
                        label = "IP сервера WDTT",
                        placeholder = "203.0.113.7",
                        enabled = enabled,
                        keyboardType = KeyboardType.Uri,
                        modifier = Modifier.weight(2f)
                    )
                    VkTurnField(
                        value = draft.wdttPort,
                        onValueChange = { v -> onChange { it.copy(wdttPort = v.filter(Char::isDigit)) } },
                        label = "Порт",
                        placeholder = "56000",
                        enabled = enabled,
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f)
                    )
                }
                VkTurnField(
                    value = draft.wdttPassword,
                    onValueChange = { v -> onChange { it.copy(wdttPassword = v) } },
                    label = "Пароль WDTT",
                    placeholder = "ключ WRAP выводится из пароля",
                    enabled = enabled
                )
                // Auto-install the wdtt-server on a VPS (opens an SSH connect dialog). Hidden unless the
                // user enabled "Автоустановка на VPS" in app settings (off by default — SSH deploy is advanced).
                if (showAutoInstall) {
                    OutlinedButton(
                        onClick = onWdttAutoInstall,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Автоустановка на VPS")
                    }
                }
                SettingsDropdown(
                    label = "TLS-отпечаток (VK auth)",
                    selectedValue = draft.wdttFingerprint.ifBlank { "chrome" },
                    options = listOf("chrome", "firefox", "safari", "ios", "android"),
                    enabled = enabled,
                    onValueSelected = { v -> onChange { it.copy(wdttFingerprint = v) } },
                    valueLabel = { it }
                )
                VkTurnField(
                    value = draft.wdttWorkers,
                    onValueChange = { v -> onChange { it.copy(wdttWorkers = v.filter(Char::isDigit)) } },
                    label = "Воркеры WDTT (0 — по умолчанию)",
                    placeholder = "0 — авто; кратно 9, максимум 108",
                    enabled = enabled,
                    keyboardType = KeyboardType.Number
                )
            }
        }
    }

    // freeturn-only transport/exit section — entirely hidden for the WDTT core (it connects purely by the
    // wdtt-server IP[:port] above and fetches its WireGuard config from the server).
    if (draft.core != VkTurnConfig.CORE_WDTT) item {
        val isWdtt = false
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionTitle(
                title = LocalStrings.current.freeturnTransportSection,
                subtitle = LocalStrings.current.freeturnTransportSubtitle
            )
            // freeturn-specific peer/transport/obfuscation fields — hidden for the WDTT core, which uses
            // its own wdtt-server + password block above.
            if (!isWdtt) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    VkTurnField(
                        value = draft.peerHost,
                        onValueChange = { v -> onChange { it.copy(peerHost = v) } },
                        label = LocalStrings.current.serverHost,
                        placeholder = "203.0.113.7",
                        enabled = enabled,
                        keyboardType = KeyboardType.Uri,
                        modifier = Modifier.weight(2f)
                    )
                    VkTurnField(
                        value = draft.peerPort,
                        onValueChange = { v -> onChange { it.copy(peerPort = v.filter(Char::isDigit)) } },
                        label = LocalStrings.current.port,
                        placeholder = "56000",
                        enabled = enabled,
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f)
                    )
                }
                // Auto-install the free-turn-proxy server (+ WireGuard) on a VPS and fill the peer/keys
                // from the result. Gated on the same "Автоустановка на VPS" app setting as WDTT.
                if (showAutoInstall) {
                    OutlinedButton(
                        onClick = onFreeturnAutoInstall,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Установить freeturn на VPS")
                    }
                }
                SettingsDropdown(
                    label = LocalStrings.current.transportToRelay,
                    selectedValue = draft.transport.ifBlank { "tcp" },
                    options = listOf("tcp", "udp"),
                    enabled = enabled,
                    onValueSelected = { v -> onChange { it.copy(transport = v) } },
                    valueLabel = { it }
                )
            }
            // Exit outbound. WireGuard/AmneziaWG are UDP (freeturn udprelay); a proxy exit is TCP
            // (freeturn tcpfwd). The freeturn payload mode is derived from this choice in the
            // composer, so it is set here too to keep the stored draft consistent.
            SettingsDropdown(
                label = LocalStrings.current.modeTunnelPayload,
                selectedValue = draft.outbound.ifBlank { VkTurnConfig.OUTBOUND_WIREGUARD },
                options = listOf(
                    VkTurnConfig.OUTBOUND_WIREGUARD,
                    VkTurnConfig.OUTBOUND_AMNEZIAWG,
                    VkTurnConfig.OUTBOUND_PROXY,
                ),
                enabled = enabled,
                onValueSelected = { v ->
                    onChange { it.copy(outbound = v, mode = if (v == VkTurnConfig.OUTBOUND_PROXY) "tcp" else "udp") }
                },
                valueLabel = { o ->
                    when (o) {
                        VkTurnConfig.OUTBOUND_WIREGUARD -> "WireGuard (udp)"
                        VkTurnConfig.OUTBOUND_AMNEZIAWG -> "AmneziaWG (udp)"
                        else -> "Proxy · vless/trojan/ss (tcp)"
                    }
                }
            )
            // Obfuscation + stream count are freeturn-only knobs; the WDTT core sets its own obf/WRAP
            // from the password and scales via "workers" above.
            if (!isWdtt) {
                SettingsDropdown(
                    label = LocalStrings.current.obfuscationProfile,
                    selectedValue = draft.obfProfile.ifBlank { "rtpopus" },
                    // The obf profile MUST match the server (panel): rtpopus2 needs a freeturn 1.3+ server,
                    // rtpopus3 a 1.4+ one — pick one the panel/VPS also speaks, else the obf desyncs.
                    options = listOf("none", "rtpopus", "rtpopus2", "rtpopus3"),
                    enabled = enabled,
                    onValueSelected = { v -> onChange { it.copy(obfProfile = v) } },
                    valueLabel = { it }
                )
                VkTurnField(
                    value = draft.obfKey,
                    onValueChange = { v -> onChange { it.copy(obfKey = v) } },
                    label = LocalStrings.current.obfuscationKey,
                    placeholder = "64 hex characters",
                    enabled = enabled
                )
                VkTurnField(
                    value = draft.streams,
                    onValueChange = { v -> onChange { it.copy(streams = v.filter(Char::isDigit)) } },
                    label = LocalStrings.current.streamsParallel,
                    placeholder = "10 (default) — more = faster, more VK churn",
                    enabled = enabled,
                    keyboardType = KeyboardType.Number
                )
                // Multi-server: a toggle enables running extra freeturn:// servers (one per line, up to
                // 5) ALONGSIDE the primary, load-balanced per connection. WireGuard exit only. The VK
                // call links are PARTITIONED across servers, so for a real speed gain add MORE VK call
                // links above (each server takes a share); one shared call can't be split for more speed.
                if (draft.outbound == VkTurnConfig.OUTBOUND_WIREGUARD) {
                    VkTurnSwitchRow(
                        label = "Несколько серверов (ускорение)",
                        checked = draft.freeturnMultiServer,
                        enabled = enabled,
                        onCheckedChange = { v -> onChange { it.copy(freeturnMultiServer = v) } }
                    )
                    if (draft.freeturnMultiServer) {
                        val extraLines = draft.extraFreeturnUris.split('\n')
                            .map { it.trim() }.filter { it.startsWith("freeturn://", ignoreCase = true) }
                        val overCap = extraLines.size > 5
                        OutlinedTextField(
                            value = draft.extraFreeturnUris,
                            onValueChange = { v -> onChange { it.copy(extraFreeturnUris = v) } },
                            label = { Text("Доп. серверы freeturn (до 5, по одной ссылке в строке)") },
                            placeholder = { Text("freeturn://…") },
                            enabled = enabled,
                            minLines = 2,
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            if (overCap) "Слишком много серверов — будут использованы первые 5 доп. (всего 6)."
                            else "Всего серверов: ${extraLines.size + 1}. Для прироста скорости добавь больше VK-ссылок выше — они делятся между серверами.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (overCap) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // WireGuard/AmneziaWG key block — hidden for the WDTT core (keys come from the server, not the user).
    if (draft.core != VkTurnConfig.CORE_WDTT && draft.outbound != VkTurnConfig.OUTBOUND_PROXY) item {
        val isAwg = draft.outbound == VkTurnConfig.OUTBOUND_AMNEZIAWG
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionTitle(
                title = if (isAwg) "AmneziaWG" else "WireGuard",
                subtitle = LocalStrings.current.wireguardSubtitle
            )
            VkTurnField(
                value = draft.wgPrivateKey,
                onValueChange = { v -> onChange { it.copy(wgPrivateKey = v.trim()) } },
                label = LocalStrings.current.privateKey,
                placeholder = "client private key (base64)",
                enabled = enabled
            )
            VkTurnField(
                value = draft.wgPeerPublicKey,
                onValueChange = { v -> onChange { it.copy(wgPeerPublicKey = v.trim()) } },
                label = LocalStrings.current.peerPublicKey,
                placeholder = "server public key (base64)",
                enabled = enabled
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                VkTurnField(
                    value = draft.wgAddress,
                    onValueChange = { v -> onChange { it.copy(wgAddress = v.trim()) } },
                    label = LocalStrings.current.addressField,
                    placeholder = "10.7.1.2/32",
                    enabled = enabled,
                    modifier = Modifier.weight(2f)
                )
                VkTurnField(
                    value = draft.wgMtu,
                    onValueChange = { v -> onChange { it.copy(wgMtu = v.filter(Char::isDigit)) } },
                    label = "MTU",
                    placeholder = "1280",
                    enabled = enabled,
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                VkTurnField(
                    value = draft.wgDns,
                    onValueChange = { v -> onChange { it.copy(wgDns = v.trim()) } },
                    label = LocalStrings.current.dns,
                    placeholder = "1.1.1.1",
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                )
                VkTurnField(
                    value = draft.listenPort,
                    onValueChange = { v -> onChange { it.copy(listenPort = v.filter(Char::isDigit)) } },
                    label = LocalStrings.current.listenPort,
                    placeholder = "9000",
                    enabled = enabled,
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                VkTurnField(
                    value = draft.wgAllowedIps,
                    onValueChange = { v -> onChange { it.copy(wgAllowedIps = v.trim()) } },
                    label = LocalStrings.current.allowedIps,
                    placeholder = "0.0.0.0/0",
                    enabled = enabled,
                    modifier = Modifier.weight(2f)
                )
                VkTurnField(
                    value = draft.wgKeepalive,
                    onValueChange = { v -> onChange { it.copy(wgKeepalive = v.filter(Char::isDigit)) } },
                    label = "Keepalive",
                    placeholder = "25",
                    enabled = enabled,
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f)
                )
            }
            if (isAwg) {
                // AmneziaWG obfuscation knobs (Jc/Jmin/Jmax/S1/S2/H1..H4) — must match the server.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    VkTurnField(draft.awgJc, { v -> onChange { it.copy(awgJc = v.filter(Char::isDigit)) } }, "Jc", "4", enabled, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                    VkTurnField(draft.awgJmin, { v -> onChange { it.copy(awgJmin = v.filter(Char::isDigit)) } }, "Jmin", "40", enabled, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                    VkTurnField(draft.awgJmax, { v -> onChange { it.copy(awgJmax = v.filter(Char::isDigit)) } }, "Jmax", "70", enabled, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    VkTurnField(draft.awgS1, { v -> onChange { it.copy(awgS1 = v.filter(Char::isDigit)) } }, "S1", "0", enabled, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                    VkTurnField(draft.awgS2, { v -> onChange { it.copy(awgS2 = v.filter(Char::isDigit)) } }, "S2", "0", enabled, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    VkTurnField(draft.awgH1, { v -> onChange { it.copy(awgH1 = v.filter(Char::isDigit)) } }, "H1", "1", enabled, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                    VkTurnField(draft.awgH2, { v -> onChange { it.copy(awgH2 = v.filter(Char::isDigit)) } }, "H2", "2", enabled, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                    VkTurnField(draft.awgH3, { v -> onChange { it.copy(awgH3 = v.filter(Char::isDigit)) } }, "H3", "3", enabled, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                    VkTurnField(draft.awgH4, { v -> onChange { it.copy(awgH4 = v.filter(Char::isDigit)) } }, "H4", "4", enabled, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                }
            }
        }
    }

    // Proxy exit (vless/vmess/trojan/ss) — used when outbound == proxy; dialled THROUGH the
    // freeturn TCP listener (mode=tcp).
    if (draft.outbound == VkTurnConfig.OUTBOUND_PROXY) item {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionTitle(
                title = LocalStrings.current.proxyOverVkturn,
                subtitle = LocalStrings.current.proxyOverVkturnSubtitle
            )
            OutlinedTextField(
                value = draft.outboundProxyLink,
                onValueChange = { v -> onChange { it.copy(outboundProxyLink = v) } },
                label = { Text(LocalStrings.current.proxyLink) },
                placeholder = { Text("vless://… / trojan://… / ss://…") },
                enabled = enabled,
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            CoreSelector(
                selected = draft.proxyCore,
                enabled = enabled,
                onSelected = { v -> onChange { it.copy(proxyCore = v) } }
            )
        }
    }

    // Optional proxy chained ON TOP of the WireGuard tunnel (WG outbound only). A toggle enables or
    // disables it; turning it off clears the link so the composer falls back to plain WireGuard.
    if (draft.outbound == VkTurnConfig.OUTBOUND_WIREGUARD) item {
        var proxyEnabled by remember(draft.chainProxyLink.isNotBlank()) {
            mutableStateOf(draft.chainProxyLink.isNotBlank())
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionTitle(
                title = LocalStrings.current.proxyOverVkturn,
                subtitle = LocalStrings.current.proxyOverVkturnSubtitle
            )
            VkTurnSwitchRow(
                label = LocalStrings.current.enableProxy,
                checked = proxyEnabled,
                enabled = enabled,
                onCheckedChange = { on ->
                    proxyEnabled = on
                    if (!on) onChange { it.copy(chainProxyLink = "") }
                }
            )
            if (proxyEnabled) {
                OutlinedTextField(
                    value = draft.chainProxyLink,
                    onValueChange = { v -> onChange { it.copy(chainProxyLink = v) } },
                    label = { Text(LocalStrings.current.proxyLink) },
                    placeholder = { Text("vless://… / trojan://… / ss://…") },
                    enabled = enabled,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                CoreSelector(
                    selected = draft.proxyCore,
                    enabled = enabled,
                    onSelected = { v -> onChange { it.copy(proxyCore = v) } }
                )
            }
        }
    }
}

/**
 * Live install-log area shared by the WDTT and DNSTT installer dialogs. The lines are both
 * hand-selectable (wrapped in a [SelectionContainer]) and copyable in one tap via the "Копировать"
 * button — so the user can paste the full SSH log when reporting an install problem.
 */
@Composable
private fun InstallLogView(log: List<String>, logScroll: ScrollState) {
    if (log.isEmpty()) return
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Лог установки",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        TextButton(onClick = { clipboard.setText(AnnotatedString(log.joinToString("\n"))) }) {
            Text("Копировать")
        }
    }
    SelectionContainer {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp)
                .verticalScroll(logScroll)
        ) {
            log.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = LocalContentColor.current
                )
            }
        }
    }
}

/**
 * One-tap WDTT server installer. Collects SSH access to the VPS and, on confirm, connects over SSH,
 * uploads the bundled wdtt-server binary matching the VPS architecture and runs it as a systemd
 * service ([rememberWdttServerInstaller]). Progress is streamed live into a log area. The WDTT
 * listener port + connection password come from the location draft (the password MUST match what
 * the location uses — the WRAP key derives from it on both sides).
 */
@Composable
private fun WdttInstallDialog(
    draft: VkTurnDraft,
    onApplyDraft: (((VkTurnDraft) -> VkTurnDraft)) -> Unit,
    onDismiss: () -> Unit
) {
    val installer = rememberWdttServerInstaller()
    val scope = rememberCoroutineScope()
    var ip by remember { mutableStateOf(draft.wdttPeer) }
    var sshPort by remember { mutableStateOf("22") }
    var login by remember { mutableStateOf("root") }
    var password by remember { mutableStateOf("") }
    var useKey by remember { mutableStateOf(false) }
    var sshKey by remember { mutableStateOf("") }
    var keyPassphrase by remember { mutableStateOf("") }
    // WDTT server params are editable here (prefilled from the location draft). On success they're
    // written back into the draft so the location and the server stay in sync — the WRAP key derives
    // from the password on both sides, so a mismatch silently fails to connect.
    var wdttPortText by remember { mutableStateOf(draft.wdttPort.ifBlank { "56000" }) }
    var wdttPass by remember { mutableStateOf(draft.wdttPassword) }
    var dns by remember { mutableStateOf(draft.wgDns.ifBlank { "1.1.1.1" }) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<String>?>(null) }
    val log = remember { mutableStateListOf<String>() }
    val logScroll = rememberScrollState()

    val port = wdttPortText.ifBlank { "56000" }.toIntOrNull()?.takeIf { it in 1..65535 } ?: 56000
    val succeeded = result?.isSuccess == true

    // Keep the log view pinned to the newest line as it streams in.
    androidx.compose.runtime.LaunchedEffect(log.size) {
        if (log.isNotEmpty()) logScroll.scrollTo(logScroll.maxValue)
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("Автоустановка WDTT на VPS") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Подключусь к VPS по SSH, загружу и запущу wdtt-сервер на порту $port. " +
                        "Порт, пароль и DNS можно изменить ниже — они сохранятся в настройки локации.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it.trim() },
                    label = { Text("IP/хост VPS") },
                    singleLine = true,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = login,
                        onValueChange = { login = it.trim() },
                        label = { Text("Логин SSH") },
                        singleLine = true,
                        enabled = !running,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = sshPort,
                        onValueChange = { v -> sshPort = v.filter(Char::isDigit) },
                        label = { Text("Порт") },
                        singleLine = true,
                        enabled = !running,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(96.dp)
                    )
                }
                SshAuthFields(
                    useKey = useKey,
                    onUseKeyChange = { useKey = it },
                    password = password,
                    onPasswordChange = { password = it },
                    privateKey = sshKey,
                    onPrivateKeyChange = { sshKey = it },
                    passphrase = keyPassphrase,
                    onPassphraseChange = { keyPassphrase = it },
                    enabled = !running,
                )
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = wdttPass,
                        onValueChange = { wdttPass = it },
                        label = { Text("Пароль WDTT") },
                        singleLine = true,
                        enabled = !running,
                        modifier = Modifier.weight(2f)
                    )
                    OutlinedTextField(
                        value = wdttPortText,
                        onValueChange = { v -> wdttPortText = v.filter(Char::isDigit) },
                        label = { Text("Порт WDTT") },
                        singleLine = true,
                        enabled = !running,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = dns,
                    onValueChange = { dns = it.trim() },
                    label = { Text("DNS для клиента") },
                    singleLine = true,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth()
                )
                if (wdttPass.isBlank()) {
                    Text(
                        "Укажи «Пароль WDTT» — он должен совпадать с паролем локации.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                InstallLogView(log, logScroll)
                result?.exceptionOrNull()?.let { err ->
                    Text(
                        err.message ?: "Ошибка установки",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (succeeded) {
                    Text(
                        result?.getOrNull().orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            if (succeeded) {
                TextButton(onClick = onDismiss) { Text("Готово") }
            } else {
                TextButton(
                    enabled = !running && ip.isNotBlank() &&
                        (if (useKey) sshKey.isNotBlank() else password.isNotBlank()) && wdttPass.isNotBlank(),
                    onClick = {
                        running = true
                        result = null
                        log.clear()
                        // Persist the edited server params back into the location draft so the client
                        // connects with the exact port/password/DNS the server was just launched with.
                        onApplyDraft { d ->
                            d.copy(
                                wdttPeer = ip.trim(),
                                wdttPort = port.toString(),
                                wdttPassword = wdttPass,
                                wgDns = dns.ifBlank { "1.1.1.1" }
                            )
                        }
                        scope.launch {
                            val res = installer.install(
                                WdttInstallOptions(
                                    host = ip.trim(),
                                    sshPort = sshPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22,
                                    login = login.ifBlank { "root" },
                                    sshPassword = if (useKey) "" else password,
                                    sshKey = if (useKey) sshKey else "",
                                    sshKeyPassphrase = if (useKey) keyPassphrase else "",
                                    wdttPort = port,
                                    wdttPassword = wdttPass,
                                    dns = dns.ifBlank { "1.1.1.1" },
                                )
                            ) { line -> log.add(line) }
                            // Fold the final error into the log too, so the copied log includes it.
                            res.exceptionOrNull()?.let { log.add("ОШИБКА: ${it.message}") }
                            result = res
                            running = false
                        }
                    }
                ) {
                    if (running) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (running) "Установка…" else "Установить")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !running) {
                Text(if (succeeded) "Закрыть" else "Отмена")
            }
        }
    )
}

/**
 * One-tap free-turn-proxy server installer. Collects SSH access to the VPS + the public freeturn
 * port, and on confirm connects over SSH, uploads the bundled freeturn-server binary, provisions a
 * persistent WireGuard exit and runs the server as a systemd service ([rememberFreeturnServerInstaller]).
 * Progress streams live into a log area. On success the returned obf key + WireGuard keys are written
 * straight into the location draft (peer/keys), so the freeturn:// link is rebuilt by the composer.
 */
@Composable
private fun FreeturnInstallDialog(
    draft: VkTurnDraft,
    onApplyDraft: (((VkTurnDraft) -> VkTurnDraft)) -> Unit,
    onDismiss: () -> Unit
) {
    val installer = rememberFreeturnServerInstaller()
    val scope = rememberCoroutineScope()
    var ip by remember { mutableStateOf(draft.peerHost) }
    var sshPort by remember { mutableStateOf("22") }
    var login by remember { mutableStateOf("root") }
    var password by remember { mutableStateOf("") }
    var useKey by remember { mutableStateOf(false) }
    var sshKey by remember { mutableStateOf("") }
    var keyPassphrase by remember { mutableStateOf("") }
    // freeturn public listener port (the freeturn:// peer port); prefilled from the draft or 56000.
    var ftPortText by remember { mutableStateOf(draft.peerPort.ifBlank { "56000" }) }
    var dns by remember { mutableStateOf(draft.wgDns.ifBlank { "1.1.1.1" }) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<String>?>(null) }
    // Последняя операция была удалением — тогда не врём про «ключи подставлены в локацию».
    var removed by remember { mutableStateOf(false) }
    val log = remember { mutableStateListOf<String>() }
    val logScroll = rememberScrollState()

    val port = ftPortText.ifBlank { "56000" }.toIntOrNull()?.takeIf { it in 1..65535 } ?: 56000
    // Obfuscation profile the server is launched with — must match what the client uses. Editable;
    // prefilled from the location draft (default rtpopus). rtpopus2/3 need a freeturn 1.3+/1.4+ server,
    // which the bundled binary is, so all are installable.
    var obfProfile by remember { mutableStateOf(draft.obfProfile.ifBlank { "rtpopus" }) }
    // Чем поднимать выход на VPS. AmneziaWG прячет сам туннель ВНУТРИ TURN-релея (Jc/S1/S2/H1-H4
    // генерируются на сервере и приезжают в локацию), ставится userspace-бинарником amneziawg-go —
    // ядерный модуль и awg-tools не нужны. Требует /dev/net/tun, как и обычный WireGuard.
    var exit by remember { mutableStateOf(FreeturnExit.WireGuard) }
    val succeeded = result?.isSuccess == true
    // Установка и удаление требуют одного и того же: адреса и доступа по SSH.
    val canRun = !running && ip.isNotBlank() && (if (useKey) sshKey.isNotBlank() else password.isNotBlank())

    androidx.compose.runtime.LaunchedEffect(log.size) {
        if (log.isNotEmpty()) logScroll.scrollTo(logScroll.maxValue)
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("Установка freeturn на VPS") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Подключусь к VPS по SSH, подниму выход (${if (exit == FreeturnExit.AmneziaWG) "AmneziaWG" else "WireGuard"}) " +
                        "и запущу free-turn-proxy сервер на порту $port (obf $obfProfile). " +
                        "Ключи и obf-key подставятся в локацию.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it.trim() },
                    label = { Text("IP/хост VPS") },
                    singleLine = true,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = login,
                        onValueChange = { login = it.trim() },
                        label = { Text("Логин SSH") },
                        singleLine = true,
                        enabled = !running,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = sshPort,
                        onValueChange = { v -> sshPort = v.filter(Char::isDigit) },
                        label = { Text("Порт") },
                        singleLine = true,
                        enabled = !running,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(96.dp)
                    )
                }
                SshAuthFields(
                    useKey = useKey,
                    onUseKeyChange = { useKey = it },
                    password = password,
                    onPasswordChange = { password = it },
                    privateKey = sshKey,
                    onPrivateKeyChange = { sshKey = it },
                    passphrase = keyPassphrase,
                    onPassphraseChange = { keyPassphrase = it },
                    enabled = !running,
                )
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = ftPortText,
                        onValueChange = { v -> ftPortText = v.filter(Char::isDigit) },
                        label = { Text("Порт freeturn") },
                        singleLine = true,
                        enabled = !running,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = dns,
                        onValueChange = { dns = it.trim() },
                        label = { Text("DNS клиента") },
                        singleLine = true,
                        enabled = !running,
                        modifier = Modifier.weight(1f)
                    )
                }
                SettingsDropdown(
                    label = "Выход на VPS",
                    selectedValue = exit.name,
                    options = FreeturnExit.entries.map { it.name },
                    enabled = !running,
                    onValueSelected = { picked ->
                        exit = FreeturnExit.entries.first { it.name == picked }
                    },
                    valueLabel = { if (it == FreeturnExit.AmneziaWG.name) "AmneziaWG (обфускация)" else "WireGuard" }
                )
                // Обфускация: профиль, с которым стартует сервер (rtpopus / rtpopus2 / rtpopus3).
                // Должен совпадать с клиентом — на успехе он подставляется в локацию.
                SettingsDropdown(
                    label = LocalStrings.current.obfuscationProfile,
                    selectedValue = obfProfile,
                    options = listOf("rtpopus", "rtpopus2", "rtpopus3"),
                    enabled = !running,
                    onValueSelected = { obfProfile = it },
                    valueLabel = { it }
                )
                InstallLogView(log, logScroll)
                result?.exceptionOrNull()?.let { err ->
                    Text(
                        err.message ?: "Ошибка установки",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (succeeded) {
                    Text(
                        result?.getOrNull().orEmpty() +
                            if (removed) "" else "\nКлючи подставлены в локацию.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            if (succeeded) {
                TextButton(onClick = onDismiss) { Text("Готово") }
            } else {
                TextButton(
                    enabled = canRun,
                    onClick = {
                        running = true
                        result = null
                        removed = false
                        log.clear()
                        scope.launch {
                            val res = installer.install(
                                FreeturnInstallOptions(
                                    host = ip.trim(),
                                    sshPort = sshPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22,
                                    login = login.ifBlank { "root" },
                                    sshPassword = if (useKey) "" else password,
                                    sshKey = if (useKey) sshKey else "",
                                    sshKeyPassphrase = if (useKey) keyPassphrase else "",
                                    freeturnPort = port,
                                    obfProfile = obfProfile,
                                    dns = dns.ifBlank { "1.1.1.1" },
                                    exit = exit,
                                )
                            ) { line -> log.add(line) }
                            // On success, write the obf key + WireGuard keys into the draft; the composer
                            // rebuilds the freeturn:// link + the WG outbound from these fields.
                            res.getOrNull()?.let { ok ->
                                onApplyDraft { d ->
                                    val base = d.copy(
                                        outbound = if (ok.awg != null) VkTurnConfig.OUTBOUND_AMNEZIAWG
                                        else VkTurnConfig.OUTBOUND_WIREGUARD,
                                        mode = "udp",
                                        obfProfile = obfProfile,
                                        obfKey = ok.obfKey,
                                        peerHost = ip.trim(),
                                        peerPort = ok.freeturnPort.toString(),
                                        wgPrivateKey = ok.clientWgPrivateKey,
                                        wgPeerPublicKey = ok.serverWgPublicKey,
                                        wgAddress = ok.clientWgAddress,
                                        wgDns = dns.ifBlank { "1.1.1.1" },
                                    )
                                    // Параметры AmneziaWG обязаны совпасть с сервером до единого числа,
                                    // поэтому берём ровно те, что он сгенерировал, а не дефолты.
                                    ok.awg?.let { a ->
                                        base.copy(
                                            awgJc = a.jc, awgJmin = a.jmin, awgJmax = a.jmax,
                                            awgS1 = a.s1, awgS2 = a.s2,
                                            awgH1 = a.h1, awgH2 = a.h2, awgH3 = a.h3, awgH4 = a.h4,
                                        )
                                    } ?: base
                                }
                            }
                            res.exceptionOrNull()?.let { log.add("ОШИБКА: ${it.message}") }
                            result = res.map { it.status }
                            running = false
                        }
                    }
                ) {
                    if (running) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (running) "Установка…" else "Установить")
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Снос того же порта: служба, туннельный выход (любой из двух), конфиги и правило
                // фаервола. Локацию не трогаем: ключи в ней ещё пригодятся, если сервер ставят заново.
                TextButton(
                    enabled = canRun,
                    onClick = {
                        running = true
                        result = null
                        removed = true
                        log.clear()
                        scope.launch {
                            val res = installer.uninstall(
                                FreeturnInstallOptions(
                                    host = ip.trim(),
                                    sshPort = sshPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22,
                                    login = login.ifBlank { "root" },
                                    sshPassword = if (useKey) "" else password,
                                    sshKey = if (useKey) sshKey else "",
                                    sshKeyPassphrase = if (useKey) keyPassphrase else "",
                                    freeturnPort = port,
                                )
                            ) { line -> log.add(line) }
                            res.exceptionOrNull()?.let { log.add("ОШИБКА: ${it.message}") }
                            result = res
                            running = false
                        }
                    }
                ) { Text("Удалить с VPS") }
                TextButton(onClick = onDismiss, enabled = !running) {
                    Text(if (succeeded) "Закрыть" else "Отмена")
                }
            }
        }
    )
}

/**
 * One-tap dnstt-server installer. Collects SSH access to the VPS plus the dnstt UDP port + tunnel
 * domain, and on confirm connects over SSH, uploads the bundled dnstt-server binary, generates a
 * persistent Noise keypair and runs it (with its built-in SOCKS5 exit) as a systemd service
 * ([rememberDnsttServerInstaller]). Progress streams live into a log area. On success the returned
 * public key + domain + resolver (`host:port`, direct mode) are written straight into the location.
 */
@Composable
private fun DnsttInstallDialog(
    config: DnsttConfig,
    onApplyConfig: (((DnsttConfig) -> DnsttConfig)) -> Unit,
    onDismiss: () -> Unit
) {
    val installer = rememberDnsttServerInstaller()
    val scope = rememberCoroutineScope()
    var ip by remember { mutableStateOf(deriveHost(config.resolver)) }
    var sshPort by remember { mutableStateOf("22") }
    var login by remember { mutableStateOf("root") }
    var password by remember { mutableStateOf("") }
    var useKey by remember { mutableStateOf(false) }
    var sshKey by remember { mutableStateOf("") }
    var keyPassphrase by remember { mutableStateOf("") }
    var udpPortText by remember { mutableStateOf(DnsttInstallOptions.DEFAULT_UDP_PORT.toString()) }
    var domain by remember { mutableStateOf(config.domain.ifBlank { DnsttInstallOptions.DEFAULT_DOMAIN }) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<org.olcbox.app.vpn.dnstt.DnsttInstallResult>?>(null) }
    val log = remember { mutableStateListOf<String>() }
    val logScroll = rememberScrollState()

    val udpPort = udpPortText.ifBlank { "5300" }.toIntOrNull()?.takeIf { it in 1..65535 }
        ?: DnsttInstallOptions.DEFAULT_UDP_PORT
    val succeeded = result?.isSuccess == true

    androidx.compose.runtime.LaunchedEffect(log.size) {
        if (log.isNotEmpty()) logScroll.scrollTo(logScroll.maxValue)
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("Автоустановка DNSTT на VPS") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Подключусь к VPS по SSH, загружу dnstt-сервер, сгенерирую ключ и запущу его на UDP-порту $udpPort " +
                        "со встроенным SOCKS5-выходом. Публичный ключ, домен и резолвер ($ip:$udpPort) подставятся в локацию.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it.trim() },
                    label = { Text("IP/хост VPS") },
                    singleLine = true,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = login,
                        onValueChange = { login = it.trim() },
                        label = { Text("Логин SSH") },
                        singleLine = true,
                        enabled = !running,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = sshPort,
                        onValueChange = { v -> sshPort = v.filter(Char::isDigit) },
                        label = { Text("Порт") },
                        singleLine = true,
                        enabled = !running,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(96.dp)
                    )
                }
                SshAuthFields(
                    useKey = useKey,
                    onUseKeyChange = { useKey = it },
                    password = password,
                    onPasswordChange = { password = it },
                    privateKey = sshKey,
                    onPrivateKeyChange = { sshKey = it },
                    passphrase = keyPassphrase,
                    onPassphraseChange = { keyPassphrase = it },
                    enabled = !running,
                )
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = domain,
                        onValueChange = { domain = it.trim() },
                        label = { Text("Домен туннеля") },
                        singleLine = true,
                        enabled = !running,
                        modifier = Modifier.weight(2f)
                    )
                    OutlinedTextField(
                        value = udpPortText,
                        onValueChange = { v -> udpPortText = v.filter(Char::isDigit) },
                        label = { Text("UDP-порт") },
                        singleLine = true,
                        enabled = !running,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                InstallLogView(log, logScroll)
                result?.exceptionOrNull()?.let { err ->
                    Text(
                        err.message ?: "Ошибка установки",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (succeeded) {
                    Text(
                        result?.getOrNull()?.message.orEmpty() + "\nКлюч и резолвер подставлены в локацию.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            if (succeeded) {
                TextButton(onClick = onDismiss) { Text("Готово") }
            } else {
                TextButton(
                    enabled = !running && ip.isNotBlank() &&
                        (if (useKey) sshKey.isNotBlank() else password.isNotBlank()) && domain.isNotBlank(),
                    onClick = {
                        running = true
                        result = null
                        log.clear()
                        scope.launch {
                            val res = installer.install(
                                DnsttInstallOptions(
                                    host = ip.trim(),
                                    sshPort = sshPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22,
                                    login = login.ifBlank { "root" },
                                    sshPassword = if (useKey) "" else password,
                                    sshKey = if (useKey) sshKey else "",
                                    sshKeyPassphrase = if (useKey) keyPassphrase else "",
                                    udpPort = udpPort,
                                    domain = domain.trim(),
                                )
                            ) { line -> log.add(line) }
                            // On success, write the server's public key + domain + resolver into the
                            // location so the dnstt client connects to the freshly installed server.
                            res.getOrNull()?.let { ok ->
                                onApplyConfig { c ->
                                    c.copy(
                                        domain = domain.trim(),
                                        pubKey = ok.publicKey,
                                        resolver = "${ip.trim()}:$udpPort"
                                    )
                                }
                            }
                            // Fold the final error into the log too, so the copied log includes it.
                            res.exceptionOrNull()?.let { log.add("ОШИБКА: ${it.message}") }
                            result = res
                            running = false
                        }
                    }
                ) {
                    if (running) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (running) "Установка…" else "Установить")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !running) {
                Text(if (succeeded) "Закрыть" else "Отмена")
            }
        }
    )
}

/** Extracts the host portion of a `host:port` resolver string (or returns it unchanged). */
private fun deriveHost(resolver: String): String {
    val trimmed = resolver.trim()
    if (trimmed.isBlank()) return ""
    val idx = trimmed.lastIndexOf(':')
    return if (idx > 0) trimmed.substring(0, idx) else trimmed
}

/**
 * VK call links: one primary field plus a toggle revealing up to 4 more (5 total). The links are
 * stored newline-joined in [value]; each extra call is an independent VK call → more bandwidth
 * (freeturn fans the tunnel's TURN streams across them).
 */
@Composable
private fun VkTurnLinksField(
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit
) {
    val maxLinks = 5
    val lines = value.split("\n")
    fun line(i: Int) = lines.getOrElse(i) { "" }
    fun setLine(i: Int, v: String) {
        val list = MutableList(maxLinks) { line(it) }
        list[i] = v
        onChange(list.joinToString("\n").trimEnd('\n'))
    }
    var expanded by remember {
        mutableStateOf((1 until maxLinks).any { value.split("\n").getOrElse(it) { "" }.isNotBlank() })
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SectionTitle(
            title = LocalStrings.current.vkCallLinksSection,
            subtitle = LocalStrings.current.vkCallLinksSubtitle
        )
        VkTurnField(
            value = line(0),
            onValueChange = { setLine(0, it) },
            label = LocalStrings.current.vkCallLink,
            placeholder = "https://vk.com/call/join/…",
            enabled = enabled,
            isError = line(0).isNotBlank() && !line(0).contains("/call/join/")
        )
        VkTurnSwitchRow(LocalStrings.current.additionalCalls, expanded, enabled) { expanded = it }
        if (expanded) {
            for (i in 1 until maxLinks) {
                VkTurnField(
                    value = line(i),
                    onValueChange = { setLine(i, it) },
                    label = LocalStrings.current.vkCallLinkNumbered(i + 1),
                    placeholder = "https://vk.com/call/join/…",
                    enabled = enabled,
                    isError = line(i).isNotBlank() && !line(i).contains("/call/join/")
                )
            }
        }
    }
}

@Composable
private fun VkTurnField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    enabled: Boolean,
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        enabled = enabled,
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = ImeAction.Next
        ),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun VkTurnSwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(end = 12.dp)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** Per-location advanced options for the chosen proxy core (mux / TFO / sniff / TLS fragment). */
@Composable
private fun AdvancedCoreSection(
    core: ProxyCore,
    advanced: AdvancedCoreConfig,
    enabled: Boolean,
    onChange: ((AdvancedCoreConfig) -> AdvancedCoreConfig) -> Unit
) {
    var expanded by remember { mutableStateOf(advanced != AdvancedCoreConfig()) }
    val coreName = if (core == ProxyCore.Xray) "Xray" else "sing-box"
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        VkTurnSwitchRow(LocalStrings.current.advancedCoreSettings(coreName), expanded, enabled) { expanded = it }
        if (expanded) {
            VkTurnSwitchRow(LocalStrings.current.muxMultiplex, advanced.muxEnabled, enabled) { v ->
                onChange { it.copy(muxEnabled = v) }
            }
            if (advanced.muxEnabled) {
                if (core == ProxyCore.SingBox) {
                    SettingsDropdown(
                        label = LocalStrings.current.muxProtocol,
                        selectedValue = advanced.muxProtocol.ifBlank { "h2mux" },
                        options = listOf("h2mux", "smux", "yamux"),
                        enabled = enabled,
                        onValueSelected = { v -> onChange { it.copy(muxProtocol = v) } },
                        valueLabel = { it }
                    )
                }
                VkTurnField(
                    value = advanced.muxMaxStreams.toString(),
                    onValueChange = { v ->
                        onChange { it.copy(muxMaxStreams = v.filter(Char::isDigit).toIntOrNull() ?: 8) }
                    },
                    label = LocalStrings.current.maxStreamsField,
                    placeholder = "8",
                    enabled = enabled,
                    keyboardType = KeyboardType.Number
                )
            }
            VkTurnSwitchRow("TCP Fast Open", advanced.tcpFastOpen, enabled) { v -> // technical term, kept
                onChange { it.copy(tcpFastOpen = v) }
            }
            VkTurnSwitchRow(LocalStrings.current.sniffDestination, advanced.sniff, enabled) { v ->
                onChange { it.copy(sniff = v) }
            }
            VkTurnSwitchRow(LocalStrings.current.tlsFragmentXray, advanced.tlsFragment, enabled) { v ->
                onChange { it.copy(tlsFragment = v) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CoreSelector(
    selected: ProxyCore,
    enabled: Boolean,
    singBoxLocked: Boolean = false,
    onSelected: (ProxyCore) -> Unit
) {
    val options = listOf(ProxyCore.Auto, ProxyCore.SingBox, ProxyCore.Xray)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionTitle(
            title = LocalStrings.current.coreSection,
            subtitle = if (singBoxLocked) {
                LocalStrings.current.coreSubtitleXrayOnly
            } else {
                LocalStrings.current.coreSubtitle
            }
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, core ->
                // sing-box is disabled for xhttp / FakeDNS configs (xray-core only).
                val itemEnabled = enabled && !(singBoxLocked && core == ProxyCore.SingBox)
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    selected = selected == core,
                    onClick = { onSelected(core) },
                    enabled = itemEnabled,
                    label = {
                        Text(
                            text = coreLabel(core),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }
    }
}

private fun coreLabel(core: ProxyCore): String = when (core) {
    ProxyCore.Auto -> stringsFor(LocalizationState.effective).coreAuto
    ProxyCore.SingBox -> "sing-box"
    ProxyCore.Xray -> "Xray"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutingProfileSelector(
    selected: String,
    profiles: List<RoutingProfile>,
    enabled: Boolean,
    onSelected: (String) -> Unit
) {
    val s = LocalStrings.current
    // "" = follow the global profile; NONE_ID = explicitly none; otherwise a specific profile id.
    val options = listOf("", RoutingProfile.NONE_ID) + profiles.map { it.id }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionTitle(title = s.locationRoutingProfile)
        SettingsDropdown(
            label = s.locationRoutingProfile,
            selectedValue = if (selected in options) selected else "",
            options = options,
            enabled = enabled,
            onValueSelected = onSelected,
            valueLabel = { id ->
                when (id) {
                    "" -> s.locationRoutingGlobalDefault
                    RoutingProfile.NONE_ID -> s.routingProfileNone
                    else -> profiles.firstOrNull { it.id == id }?.displayName() ?: id
                }
            }
        )
    }
}

private fun engineLabel(engine: EngineType): String = when (engine) {
    EngineType.Stealth -> "Stealth"
    EngineType.Standard -> "Standard"
    EngineType.Chain -> "Chain"
    EngineType.VkTurn -> "VK-TURN"
    EngineType.Dnstt -> "DNSTT"
}

private fun engineSubtitle(engine: EngineType): String = when (engine) {
    EngineType.Stealth -> "olcRTC WebRTC tunnel"
    EngineType.Standard -> "sing-box proxy (VLESS, VMess, Trojan, SS…)"
    EngineType.Chain -> "Proxy wrapped inside the olcRTC tunnel"
    EngineType.VkTurn -> "WireGuard over a VK TURN tunnel (free-turn-proxy)"
    EngineType.Dnstt -> "Туннель через DNS (dnstt: KCP + Noise)"
}

private fun engineProtocolLabel(type: String): String = when (type) {
    ProxyProfile.TYPE_VLESS -> "VLESS"
    ProxyProfile.TYPE_VMESS -> "VMess"
    ProxyProfile.TYPE_TROJAN -> "Trojan"
    ProxyProfile.TYPE_SHADOWSOCKS -> "Shadowsocks"
    ProxyProfile.TYPE_AMNEZIAWG -> "AmneziaWG"
    ProxyProfile.TYPE_TRUSTTUNNEL -> "Trust Tunnel"
    ProxyProfile.TYPE_HYSTERIA2 -> "Hysteria2"
    ProxyProfile.TYPE_NAIVE -> "Naive"
    else -> type.uppercase()
}

@Composable
private fun ConnectionTypePicker(
    selectedProvider: String,
    serviceProvider: String,
    enabled: Boolean,
    onProviderSelected: (String) -> Unit
) {
    val normalizedProvider = LocationConfig.normalizeProvider(selectedProvider)
    val selectedIsJitsi = isJitsiProvider(normalizedProvider)
    val normalizedServiceProvider = LocationConfig.normalizeProvider(serviceProvider)
    val options = listOf(ConnectionType.Service, ConnectionType.Jitsi)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionTitle(title = LocalStrings.current.connectionType)

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            options.forEachIndexed { index, type ->
                val selected = when (type) {
                    ConnectionType.Service -> !selectedIsJitsi
                    ConnectionType.Jitsi -> selectedIsJitsi
                }
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = options.size
                    ),
                    selected = selected,
                    onClick = {
                        onProviderSelected(
                            when (type) {
                                ConnectionType.Service -> normalizedServiceProvider
                                ConnectionType.Jitsi -> LocationConfig.PROVIDER_JITSI
                            }
                        )
                    },
                    enabled = enabled,
                    label = {
                        Text(
                            text = type.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun ProviderPicker(
    selectedProvider: String,
    enabled: Boolean,
    onProviderSelected: (String) -> Unit
) {
    val selected = LocationConfig.normalizeProvider(selectedProvider)
    val options = LocationConfig.supportedBypassProviders
        .filterNot { it == LocationConfig.PROVIDER_JITSI }

    SettingsDropdown(
        label = "Service",
        selectedValue = selected,
        options = options,
        enabled = enabled,
        onValueSelected = onProviderSelected,
        valueLabel = LocationConfig::providerDisplayName
    )
}

@Composable
private fun TransportPicker(
    selectedProvider: String,
    selectedTransport: String,
    enabled: Boolean,
    onTransportSelected: (String) -> Unit
) {
    val provider = LocationConfig.normalizeProvider(selectedProvider)
    val selected = LocationConfig.normalizeTransport(selectedTransport, provider)
    val options = LocationConfig.supportedTransportsForProvider(provider)

    SettingsDropdown(
        label = "Transport",
        selectedValue = selected,
        options = options,
        enabled = enabled,
        onValueSelected = onTransportSelected,
        valueLabel = LocationConfig::transportDisplayName
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDropdown(
    label: String,
    selectedValue: String,
    options: List<String>,
    enabled: Boolean,
    onValueSelected: (String) -> Unit,
    valueLabel: (String) -> String
) {
    var expanded by remember { mutableStateOf(false) }
    val canExpand = enabled && options.size > 1

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (canExpand) expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = valueLabel(selectedValue),
            onValueChange = {},
            label = { Text(label) },
            enabled = enabled,
            readOnly = true,
            singleLine = true,
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, canExpand)
                .fillMaxWidth()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(valueLabel(option)) },
                    onClick = {
                        onValueSelected(option)
                        expanded = false
                    },
                    leadingIcon = {
                        if (option == selectedValue) {
                            Icon(Icons.Rounded.Check, contentDescription = null)
                        }
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

@Composable
private fun Vp8OptionsCard(
    fps: Int,
    batch: Int,
    enabled: Boolean,
    onFpsChanged: (String) -> Unit,
    onBatchChanged: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionTitle(
            title = LocalStrings.current.vp8Options,
            subtitle = LocalStrings.current.vp8OptionsSubtitle
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NumericTextField(
                value = fps,
                label = "FPS",
                enabled = enabled,
                onValueChange = onFpsChanged,
                modifier = Modifier.weight(1f)
            )
            NumericTextField(
                value = batch,
                label = "Batch",
                enabled = enabled,
                onValueChange = onBatchChanged,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    enabled: Boolean,
    isError: Boolean,
    supportingText: String?,
    leadingIcon: ImageVector,
    onClear: () -> Unit,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions = KeyboardActions(),
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        enabled = enabled,
        isError = isError,
        singleLine = true,
        leadingIcon = { Icon(leadingIcon, contentDescription = null) },
        supportingText = supportingText?.let { { Text(it) } },
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            if (value.isNotEmpty() && enabled) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Close, contentDescription = "Clear")
                }
            }
        }
    )
}

@Composable
private fun NumericTextField(
    value: Int,
    label: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value.takeIf { it > 0 }?.toString().orEmpty(),
        onValueChange = onValueChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next
        ),
        modifier = modifier
    )
}

@Composable
private fun ActionsBar(
    modifier: Modifier = Modifier,
    showDelete: Boolean,
    isSaving: Boolean,
    isFormValid: Boolean,
    onDelete: () -> Unit,
    onSave: () -> Unit
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showDelete) {
            OutlinedIconButton(
                onClick = onDelete,
                modifier = Modifier.size(56.dp),
                enabled = !isSaving,
                shape = CircleShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete")
            }

            Spacer(modifier = Modifier.width(14.dp))
        }

        Button(
            onClick = onSave,
            modifier = Modifier
                .weight(1f)
                .height(56.dp),
            enabled = !isSaving && isFormValid,
            shape = CircleShape.copy(all = androidx.compose.foundation.shape.CornerSize(28.dp))
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Rounded.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(LocalStrings.current.save, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

private fun roomIdPlaceholder(provider: String): String {
    return when (LocationConfig.normalizeProvider(provider)) {
        LocationConfig.PROVIDER_TELEMOST -> "12345678901234"
        LocationConfig.PROVIDER_JAZZ -> "room id or any"
        LocationConfig.PROVIDER_WB_STREAM -> "123e4567-e89b-12d3-a456-426614174000"
        LocationConfig.PROVIDER_JITSI -> "https://meet.example.com/room"
        else -> "room id"
    }
}

private fun roomIdLabel(provider: String): String {
    return if (isJitsiProvider(provider)) "Room URL" else "Room ID"
}

private fun roomKeyboardType(provider: String): KeyboardType {
    return if (isJitsiProvider(provider)) KeyboardType.Uri else KeyboardType.Text
}

private fun isJitsiProvider(provider: String): Boolean {
    return LocationConfig.normalizeProvider(provider) == LocationConfig.PROVIDER_JITSI
}

private enum class ConnectionType(val label: String) {
    Service("Service"),
    Jitsi("Jitsi")
}
