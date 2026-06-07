package org.olcbox.app.ui.features.locations

import org.olcbox.app.ui.i18n.LocalStrings
import org.olcbox.app.ui.i18n.LocalizationState
import org.olcbox.app.ui.i18n.stringsFor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
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
import org.olcbox.app.data.model.EngineType
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
                // Main proxy (основной аутбаунд) — the location's primary outbound, ALWAYS applied.
                item {
                    ProxyField(
                        title = LocalStrings.current.mainProxySection,
                        subtitle = LocalStrings.current.proxySectionSubtitle,
                        link = viewModel.editingProxyLink,
                        currentProxy = config.proxy,
                        error = viewModel.proxyError,
                        enabled = !isSaving,
                        onChange = viewModel::onProxyLinkChanged
                    )
                }
                item {
                    CoreSelector(
                        selected = config.core,
                        enabled = !isSaving,
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
                    onChange = viewModel::updateVkTurnDraft
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

@OptIn(ExperimentalMaterial3Api::class)
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
        EngineType.VkTurn
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionTitle(title = LocalStrings.current.engineSection, subtitle = engineSubtitle(selected))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, engine ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    selected = selected == engine,
                    onClick = { onSelected(engine) },
                    enabled = enabled,
                    label = {
                        Text(
                            text = engineLabel(engine),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }
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
 * Detailed VK-TURN (freeturn + WireGuard) editor. Every field feeds [VkTurnDraft]; the view model
 * rebuilds the freeturn:// link and the sing-box WireGuard outbound from it on each change.
 */
private fun LazyListScope.vkTurnSection(
    draft: VkTurnDraft,
    enabled: Boolean,
    onChange: ((VkTurnDraft) -> VkTurnDraft) -> Unit
) {
    item {
        VkTurnLinksField(
            value = draft.vkLink,
            enabled = enabled,
            onChange = { v -> onChange { it.copy(vkLink = v) } }
        )
    }

    item {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionTitle(
                title = LocalStrings.current.freeturnTransportSection,
                subtitle = LocalStrings.current.freeturnTransportSubtitle
            )
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
            SettingsDropdown(
                label = LocalStrings.current.transportToRelay,
                selectedValue = draft.transport.ifBlank { "tcp" },
                options = listOf("tcp", "udp"),
                enabled = enabled,
                onValueSelected = { v -> onChange { it.copy(transport = v) } },
                valueLabel = { it }
            )
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
            SettingsDropdown(
                label = LocalStrings.current.obfuscationProfile,
                selectedValue = draft.obfProfile.ifBlank { "rtpopus" },
                options = listOf("none", "rtpopus"),
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
            // Bonding (TCP striping) is only valid for the proxy/tcp exit — freeturn rejects it in
            // udp mode. For WireGuard/AmneziaWG (udp), aggregation comes from "streams" + multiple
            // VK call links, so the switch is hidden there to avoid a start failure.
            if (draft.outbound == VkTurnConfig.OUTBOUND_PROXY) {
                VkTurnSwitchRow(
                    label = LocalStrings.current.bondingMultipath,
                    checked = draft.bond,
                    enabled = enabled,
                    onCheckedChange = { v -> onChange { it.copy(bond = v) } }
                )
            }
        }
    }

    if (draft.outbound != VkTurnConfig.OUTBOUND_PROXY) item {
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
    onSelected: (ProxyCore) -> Unit
) {
    val options = listOf(ProxyCore.Auto, ProxyCore.SingBox, ProxyCore.Xray)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionTitle(
            title = LocalStrings.current.coreSection,
            subtitle = LocalStrings.current.coreSubtitle
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, core ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    selected = selected == core,
                    onClick = { onSelected(core) },
                    enabled = enabled,
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
}

private fun engineSubtitle(engine: EngineType): String = when (engine) {
    EngineType.Stealth -> "olcRTC WebRTC tunnel"
    EngineType.Standard -> "sing-box proxy (VLESS, VMess, Trojan, SS…)"
    EngineType.Chain -> "Proxy wrapped inside the olcRTC tunnel"
    EngineType.VkTurn -> "WireGuard over a VK TURN tunnel (free-turn-proxy)"
}

private fun engineProtocolLabel(type: String): String = when (type) {
    ProxyProfile.TYPE_VLESS -> "VLESS"
    ProxyProfile.TYPE_VMESS -> "VMess"
    ProxyProfile.TYPE_TROJAN -> "Trojan"
    ProxyProfile.TYPE_SHADOWSOCKS -> "Shadowsocks"
    ProxyProfile.TYPE_AMNEZIAWG -> "AmneziaWG"
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
