package org.olcbox.app.ui.features.locations

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
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.ProxyCore
import org.olcbox.app.data.model.ProxyProfile
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
        title = { Text("Location settings") },
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
                    label = "Name",
                    placeholder = "Location name",
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

            if (config.engine == EngineType.Standard || config.engine == EngineType.Chain) {
                item {
                    ProxyField(
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
            }

            if (config.engine != EngineType.Standard) {
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
                    label = "Encryption key",
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
    val options = listOf(EngineType.Stealth, EngineType.Standard, EngineType.Chain)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionTitle(title = "Engine", subtitle = engineSubtitle(selected))

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
            title = "Proxy",
            subtitle = "Paste a vless/vmess/trojan/ss link or a sing-box outbound JSON"
        )
        OutlinedTextField(
            value = link,
            onValueChange = onChange,
            label = { Text("Proxy link or config") },
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
            title = "Core",
            subtitle = "Auto picks Xray for xhttp, otherwise sing-box"
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
    ProxyCore.Auto -> "Auto"
    ProxyCore.SingBox -> "sing-box"
    ProxyCore.Xray -> "Xray"
}

private fun engineLabel(engine: EngineType): String = when (engine) {
    EngineType.Stealth -> "Stealth"
    EngineType.Standard -> "Standard"
    EngineType.Chain -> "Chain"
}

private fun engineSubtitle(engine: EngineType): String = when (engine) {
    EngineType.Stealth -> "olcRTC WebRTC tunnel"
    EngineType.Standard -> "sing-box proxy (VLESS, VMess, Trojan, SS…)"
    EngineType.Chain -> "Proxy wrapped inside the olcRTC tunnel"
}

private fun engineProtocolLabel(type: String): String = when (type) {
    ProxyProfile.TYPE_VLESS -> "VLESS"
    ProxyProfile.TYPE_VMESS -> "VMess"
    ProxyProfile.TYPE_TROJAN -> "Trojan"
    ProxyProfile.TYPE_SHADOWSOCKS -> "Shadowsocks"
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
        SectionTitle(title = "Connection type")

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
            title = "VP8 options",
            subtitle = "Fine-tune stream performance"
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
                Text("Save", fontSize = 16.sp, fontWeight = FontWeight.Medium)
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
