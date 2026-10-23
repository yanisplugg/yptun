package org.olcbox.app.ui.features.home.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.data.model.AppBehaviorSettings
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.ui.components.SettingsCaption
import org.olcbox.app.ui.components.SettingsCard
import org.olcbox.app.ui.components.SettingsCardDivider
import org.olcbox.app.ui.components.SettingsCardRow
import org.olcbox.app.ui.components.SettingsCardToggle
import org.olcbox.app.ui.components.SettingsChoiceChips
import org.olcbox.app.ui.i18n.LocalStrings
import org.olcbox.app.ui.i18n.Strings

private enum class AddPage { Main, FreeServers, Manual }

/**
 * «Добавить»: one field for a subscription link or a key (most people only ever need this), QR and file
 * under it, then the two ways for people without a subscription — free public servers and a hand-made
 * location. The free-server and manual pages open inside the same sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddConfigurationSheet(
    canScanQr: Boolean,
    onDismiss: () -> Unit,
    onScanQrClick: () -> Unit,
    onImportText: (String) -> Unit,
    onImportFileClick: () -> Unit,
    onStartFreeServers: (limit: Int) -> Unit,
    onCreateManual: (engine: EngineType, wireguard: Boolean) -> Unit,
    // The free-servers options (how many, which source); null starts the search with the saved ones.
    freeServersSettings: AppBehaviorSettings? = null,
    onFreeServersSettingsChanged: (AppBehaviorSettings) -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var page by remember { mutableStateOf(AddPage.Main) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        AnimatedContent(targetState = page, label = "addPage") { current ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                when (current) {
                    AddPage.Main -> AddMainPage(
                        canScanQr = canScanQr,
                        onScanQrClick = onScanQrClick,
                        onImportText = onImportText,
                        onImportFileClick = onImportFileClick,
                        onFreeServersClick = {
                            if (freeServersSettings != null) page = AddPage.FreeServers
                            else onStartFreeServers(AppBehaviorSettings.DEFAULT_FREE_SERVERS_LIMIT)
                        },
                        onManualClick = { page = AddPage.Manual }
                    )
                    AddPage.FreeServers -> freeServersSettings?.let { settings ->
                        FreeServersStartPage(
                            settings = settings,
                            onChanged = onFreeServersSettingsChanged,
                            onBack = { page = AddPage.Main },
                            onStart = { onStartFreeServers(settings.freeServersLimit) }
                        )
                    }
                    AddPage.Manual -> ManualTypePage(
                        onBack = { page = AddPage.Main },
                        onPick = onCreateManual
                    )
                }
            }
        }
    }
}

@Composable
private fun AddMainPage(
    canScanQr: Boolean,
    onScanQrClick: () -> Unit,
    onImportText: (String) -> Unit,
    onImportFileClick: () -> Unit,
    onFreeServersClick: () -> Unit,
    onManualClick: () -> Unit
) {
    val s = LocalStrings.current
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    val detected = remember(text) { describeImportText(text, s) }

    Text(
        text = s.addTitle,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(8.dp))
    // The clipboard is read only when the user taps «Вставить»: reading it on open would pop the
    // system's "pasted from clipboard" notice (Android 12+) or the paste permission prompt (iOS).
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(s.addLinkLabel) },
        placeholder = { Text("https://…  vless://…") },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
        minLines = 2,
        maxLines = 6,
        trailingIcon = {
            IconButton(onClick = { clipboard.getText()?.text?.let { text = it } }) {
                Icon(Icons.Outlined.ContentPaste, contentDescription = s.addPaste)
            }
        },
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    )
    if (detected != null) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(detected, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    } else {
        Text(
            text = s.addLinkHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
    Spacer(Modifier.height(6.dp))
    Button(
        onClick = { onImportText(text.trim()) },
        enabled = text.isNotBlank(),
        shape = RoundedCornerShape(26.dp),
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) {
        Text(s.addButton, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }

    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        if (canScanQr) {
            AddTile(title = s.addFromQr, icon = Icons.Outlined.QrCodeScanner, onClick = onScanQrClick, modifier = Modifier.weight(1f))
        }
        AddTile(title = s.addFromFile, icon = Icons.Outlined.FileOpen, onClick = onImportFileClick, modifier = Modifier.weight(1f))
    }

    SettingsCaption(s.addNoSubscriptionSection)
    SettingsCard {
        SettingsCardRow(
            title = s.freeServers,
            subtitle = s.addFreeServersSubtitle,
            icon = Icons.Outlined.Public,
            onClick = onFreeServersClick
        )
        SettingsCardDivider()
        SettingsCardRow(
            title = s.addManualTitle,
            subtitle = s.addManualSubtitle,
            icon = Icons.Outlined.Build,
            onClick = onManualClick
        )
    }
}

@Composable
private fun AddTile(title: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** What the pasted text looks like, in a few words — a hint, the importer decides for real. */
private fun describeImportText(text: String, s: Strings): String? {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return null
    if (lines.size > 1 && lines.all { it.contains("://") }) return s.addDetectedMany(lines.size)
    val first = lines.first()
    return when {
        first.startsWith("{") || first.startsWith("[") -> s.addDetectedJson
        first.startsWith("http://", true) || first.startsWith("https://", true) ->
            s.addDetectedSubscription(Regex("^https?://([^/?#:]+)", RegexOption.IGNORE_CASE).find(first)?.groupValues?.get(1).orEmpty())
        first.contains("://") -> s.addDetectedKey(first.substringBefore("://").uppercase())
        else -> null
    }
}

@Composable
private fun AddPageHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
        }
        Spacer(Modifier.width(4.dp))
        Text(text = title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Free servers: the warning, how many to check and from where, then «Найти серверы». */
@Composable
private fun FreeServersStartPage(
    settings: AppBehaviorSettings,
    onChanged: (AppBehaviorSettings) -> Unit,
    onBack: () -> Unit,
    onStart: () -> Unit
) {
    val s = LocalStrings.current
    AddPageHeader(title = s.freeServers, onBack = onBack)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                imageVector = Icons.Outlined.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = s.freeServersSourceNotice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }

    SettingsCaption(s.freeServersHowMany)
    SettingsChoiceChips(
        options = AppBehaviorSettings.FREE_SERVERS_LIMITS.map { limit ->
            limit to if (limit == AppBehaviorSettings.FREE_SERVERS_UNLIMITED) s.freeServersLimitUnlimited else limit.toString()
        },
        selected = settings.freeServersLimit
    ) { onChanged(settings.copy(freeServersLimit = it)) }
    Text(
        text = s.freeServersLimitSubtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    )

    SettingsCaption(s.freeServersSourceSection)
    SettingsCard {
        SettingsCardToggle(s.freeServersPagesTitle, s.freeServersPagesSubtitle, settings.freeServersFromPages) {
            onChanged(settings.copy(freeServersFromPages = it))
        }
    }

    Spacer(Modifier.height(10.dp))
    Button(
        onClick = onStart,
        shape = RoundedCornerShape(26.dp),
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) {
        Text(s.freeServersFind, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** «Создать вручную»: what the traffic goes through, in plain words; the form comes next. */
@Composable
private fun ManualTypePage(
    onBack: () -> Unit,
    onPick: (EngineType, Boolean) -> Unit
) {
    val s = LocalStrings.current
    // OpenFlux and Snolc have no iOS engine (same rule as the engine picker in the location screen).
    val ios = remember { org.olcbox.app.update.UpdatePlatform.current().os == "ios" }
    AddPageHeader(title = s.addManualTitle, onBack = onBack)
    Text(
        text = s.manualIntro,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    )

    SettingsCaption(s.manualViaServices)
    SettingsCard {
        SettingsCardRow(s.engineStealthName, s.engineStealthDesc, icon = Icons.Outlined.Videocam) { onPick(EngineType.Stealth, false) }
        SettingsCardDivider()
        SettingsCardRow(s.engineChainName, s.engineChainDesc, icon = Icons.Outlined.Videocam) { onPick(EngineType.Chain, false) }
        SettingsCardDivider()
        SettingsCardRow("VK-TURN", s.engineVkTurnDesc, icon = Icons.Rounded.Phone) { onPick(EngineType.VkTurn, false) }
        if (!ios) {
            SettingsCardDivider()
            SettingsCardRow("OpenFlux", s.engineOpenFluxDesc, icon = Icons.Outlined.Description) { onPick(EngineType.OpenFlux, false) }
        }
    }

    SettingsCaption(s.manualOwnServer)
    SettingsCard {
        SettingsCardRow("AmneziaWG / WireGuard", s.engineWireguardDesc, icon = Icons.Outlined.Shield) { onPick(EngineType.Standard, true) }
        if (!ios) {
            SettingsCardDivider()
            SettingsCardRow("Snolc", s.engineSnolcDesc, icon = Icons.Outlined.Dns) { onPick(EngineType.Snolc, false) }
        }
        SettingsCardDivider()
        SettingsCardRow("MasterDNS", s.engineMasterDnsDesc, icon = Icons.Outlined.Public) { onPick(EngineType.MasterDns, false) }
    }

    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(
            text = s.manualLinkNote,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
