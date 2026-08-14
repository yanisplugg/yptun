package org.olcbox.app.ui.activities

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.olcbox.app.data.model.RoutingProfile
import org.olcbox.app.data.model.RoutingProfilesState
import org.olcbox.app.ui.i18n.LocalStrings
import org.olcbox.app.vpn.GeoUpdateStatus

/**
 * Management screen for Happ-style routing profiles: list/select a global profile, create/import/edit
 * profiles (six selector buckets + order/strategy/global-proxy), and download the geo `.dat` files.
 * Self-contained (uses Material3 directly); an inline editor handles a single profile at a time.
 */
@Composable
internal fun RoutingProfilesContent(
    state: RoutingProfilesState,
    geoStatus: GeoUpdateStatus?,
    onBack: () -> Unit,
    onSaveProfile: (RoutingProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onSetGlobal: (String) -> Unit,
    onImportLink: (String) -> Boolean,
    onSetGeoSources: (String, String) -> Unit,
    onUpdateGeo: () -> Unit,
    // When embedded as a tab, skip the own Back-row header (the host already shows one).
    embedded: Boolean = false,
) {
    val s = LocalStrings.current
    val clipboard = LocalClipboardManager.current
    // null = list view; non-null = editing that profile (blank id → a brand-new one).
    var editing by remember { mutableStateOf<RoutingProfile?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var showShareDialog by remember { mutableStateOf(false) }
    var shareImportText by remember { mutableStateOf("") }

    if (showShareDialog) {
        AlertDialog(
            onDismissRequest = { showShareDialog = false; shareImportText = "" },
            title = { Text(s.routingShareAllTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(s.routingShareAllDesc, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(state.toRoutingLink())) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(s.routingExportCopy)
                    }
                    OutlinedTextField(
                        value = shareImportText,
                        onValueChange = { shareImportText = it; importError = null },
                        label = { Text(s.routingImportPaste) },
                        placeholder = { Text(RoutingProfilesState.ROUTING_LINK_PREFIX + "…") },
                        singleLine = false,
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    importError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = shareImportText.isNotBlank(),
                    onClick = {
                        if (onImportLink(shareImportText.trim())) {
                            showShareDialog = false; shareImportText = ""; importError = null
                        } else {
                            importError = s.routingImportInvalid
                        }
                    }
                ) { Text(s.routingImportApply) }
            },
            dismissButton = {
                TextButton(onClick = { showShareDialog = false; shareImportText = "" }) { Text(s.cancel) }
            }
        )
    }

    BackHandler(enabled = editing != null) { editing = null }

    if (editing != null) {
        RoutingProfileEditor(
            profile = editing!!,
            onBack = { editing = null },
            onSave = { onSaveProfile(it); editing = null },
            onDelete = if (editing!!.id.isNotBlank()) {
                { onDeleteProfile(editing!!.id); editing = null }
            } else null,
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!embedded) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = s.routingProfiles,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // One-tap export/import of the WHOLE routing setup as a yptun://routing link.
        OutlinedButton(onClick = { showShareDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Share, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(s.routingShareAllButton)
        }

        // --- Global profile selector ---
        SectionLabel(s.routingProfileGlobal)
        Text(
            s.routingProfileGlobalHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val globalLabel = state.profileById(state.globalProfileId)?.displayName() ?: s.routingProfileNone
        LabeledDropdown(label = globalLabel) { dismiss ->
            DropdownMenuItem(
                text = { Text(s.routingProfileNone) },
                onClick = { onSetGlobal(""); dismiss() },
            )
            state.profiles.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.displayName()) },
                    onClick = { onSetGlobal(p.id); dismiss() },
                )
            }
        }

        // --- Profiles list ---
        SectionLabel(s.routingProfiles)
        if (state.profiles.isEmpty()) {
            Text(
                s.routingProfileEmpty,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.profiles.forEach { profile ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { editing = profile },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            profile.displayName(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            s.routingProfileRuleCount(profile.ruleCount()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (profile.id == state.globalProfileId && profile.id.isNotBlank()) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = s.routingProfileGlobal,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        OutlinedButton(onClick = { showAddDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(s.routingProfileAdd)
        }

        // --- Geo databases ---
        SectionLabel(s.routingGeoDatabases)
        Text(
            s.routingGeoDatabasesDesc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var geoipUrl by remember(state.geoipUrl) { mutableStateOf(state.geoipUrl) }
        var geositeUrl by remember(state.geositeUrl) { mutableStateOf(state.geositeUrl) }
        OutlinedTextField(
            value = geoipUrl,
            onValueChange = { geoipUrl = it },
            label = { Text(s.routingGeoipUrl) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = geositeUrl,
            onValueChange = { geositeUrl = it },
            label = { Text(s.routingGeositeUrl) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val statusLine = when (geoStatus) {
            is GeoUpdateStatus.Running -> s.routingGeoUpdating
            is GeoUpdateStatus.Failed -> geoStatus.message
            is GeoUpdateStatus.Success -> s.routingGeoUpdated(formatTimestamp(geoStatus.timestampMs))
            null -> if (state.geoLastUpdated > 0) s.routingGeoUpdated(formatTimestamp(state.geoLastUpdated)) else s.routingGeoNever
        }
        Text(
            statusLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = {
                onSetGeoSources(geoipUrl, geositeUrl)
                onUpdateGeo()
            },
            enabled = geoStatus !is GeoUpdateStatus.Running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (geoStatus is GeoUpdateStatus.Running) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.Download, contentDescription = null)
            }
            Spacer(Modifier.width(8.dp))
            Text(s.routingGeoUpdate)
        }
    }

    if (showAddDialog) {
        var link by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false; importError = null },
            title = { Text(s.routingProfileAdd) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = link,
                        onValueChange = { link = it; importError = null },
                        label = { Text(s.routingProfileImportLink) },
                        placeholder = { Text(s.routingProfilePasteHint) },
                        minLines = 2,
                        isError = importError != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    importError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = link.isNotBlank(),
                    onClick = {
                        if (onImportLink(link.trim())) {
                            showAddDialog = false
                            importError = null
                        } else {
                            importError = s.routingProfileInvalidLink
                        }
                    },
                ) { Text(s.routingProfileImportLink) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddDialog = false
                    importError = null
                    editing = RoutingProfile(name = s.routingProfileNewName)
                }) { Text(s.routingProfileNewName) }
            },
        )
    }
}

@Composable
private fun RoutingProfileEditor(
    profile: RoutingProfile,
    onBack: () -> Unit,
    onSave: (RoutingProfile) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val s = LocalStrings.current
    val clipboard = LocalClipboardManager.current
    var name by remember { mutableStateOf(profile.name) }
    var globalProxy by remember { mutableStateOf(profile.globalProxy) }
    var routeOrder by remember { mutableStateOf(profile.routeOrder.ifBlank { RoutingProfile.ROUTE_ORDERS.first() }) }
    var domainStrategy by remember { mutableStateOf(profile.domainStrategy.ifBlank { RoutingProfile.DOMAIN_STRATEGIES.first() }) }
    var proxySites by remember { mutableStateOf(listToText(profile.proxySites)) }
    var proxyIp by remember { mutableStateOf(listToText(profile.proxyIp)) }
    var directSites by remember { mutableStateOf(listToText(profile.directSites)) }
    var directIp by remember { mutableStateOf(listToText(profile.directIp)) }
    var blockSites by remember { mutableStateOf(listToText(profile.blockSites)) }
    var blockIp by remember { mutableStateOf(listToText(profile.blockIp)) }
    var geoipUrl by remember { mutableStateOf(profile.geoipUrl) }
    var geositeUrl by remember { mutableStateOf(profile.geositeUrl) }
    // Expert (per-core) overrides.
    var expertEnabled by remember { mutableStateOf(profile.expertEnabled) }
    var xrayDomainStrategy by remember { mutableStateOf(profile.xrayDomainStrategy.ifBlank { RoutingProfile.XRAY_DOMAIN_STRATEGIES.first() }) }
    var xraySniffing by remember { mutableStateOf(profile.xraySniffing) }
    var xrayRouteOnly by remember { mutableStateOf(profile.xrayRouteOnly) }
    var singboxDomainStrategy by remember { mutableStateOf(profile.singboxDomainStrategy) }
    var singboxSniff by remember { mutableStateOf(profile.singboxSniff) }
    var singboxResolve by remember { mutableStateOf(profile.singboxResolve) }

    fun assemble(): RoutingProfile = profile.copy(
        name = name.trim(),
        globalProxy = globalProxy,
        routeOrder = routeOrder,
        domainStrategy = domainStrategy,
        proxySites = linesToList(proxySites),
        proxyIp = linesToList(proxyIp),
        directSites = linesToList(directSites),
        directIp = linesToList(directIp),
        blockSites = linesToList(blockSites),
        blockIp = linesToList(blockIp),
        geoipUrl = geoipUrl.trim(),
        geositeUrl = geositeUrl.trim(),
        expertEnabled = expertEnabled,
        xrayDomainStrategy = xrayDomainStrategy,
        xraySniffing = xraySniffing,
        xrayRouteOnly = xrayRouteOnly,
        singboxDomainStrategy = singboxDomainStrategy,
        singboxSniff = singboxSniff,
        singboxResolve = singboxResolve,
    )

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
                text = name.ifBlank { s.routingProfileNewName },
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(s.routingProfileName) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(s.routingProfileGlobalProxy, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    s.routingProfileGlobalProxyDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = globalProxy, onCheckedChange = { globalProxy = it })
        }

        SectionLabel(s.routingProfileRouteOrder)
        LabeledDropdown(label = routeOrder) { dismiss ->
            RoutingProfile.ROUTE_ORDERS.forEach { order ->
                DropdownMenuItem(text = { Text(order) }, onClick = { routeOrder = order; dismiss() })
            }
        }

        SectionLabel(s.routingProfileDomainStrategy)
        LabeledDropdown(label = domainStrategy) { dismiss ->
            RoutingProfile.DOMAIN_STRATEGIES.forEach { st ->
                DropdownMenuItem(text = { Text(st) }, onClick = { domainStrategy = st; dismiss() })
            }
        }

        BucketField(s.routingProxySites, s.routingSelectorsHint, proxySites) { proxySites = it }
        BucketField(s.routingProxyIp, s.routingIpSelectorsHint, proxyIp) { proxyIp = it }
        BucketField(s.routingDirectSites, s.routingSelectorsHint, directSites) { directSites = it }
        BucketField(s.routingDirectIp, s.routingIpSelectorsHint, directIp) { directIp = it }
        BucketField(s.routingBlockSites, s.routingSelectorsHint, blockSites) { blockSites = it }
        BucketField(s.routingBlockIp, s.routingIpSelectorsHint, blockIp) { blockIp = it }

        SectionLabel(s.routingGeoDatabases)
        OutlinedTextField(
            value = geoipUrl,
            onValueChange = { geoipUrl = it },
            label = { Text(s.routingGeoipUrl) },
            placeholder = { Text(RoutingProfile.DEFAULT_GEOIP_URL) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = geositeUrl,
            onValueChange = { geositeUrl = it },
            label = { Text(s.routingGeositeUrl) },
            placeholder = { Text(RoutingProfile.DEFAULT_GEOSITE_URL) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // --- Expert: fine-grained, per-core routing controls ---
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(s.routingExpert, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    s.routingExpertDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = expertEnabled, onCheckedChange = { expertEnabled = it })
        }

        if (expertEnabled) {
            SectionLabel("Xray")
            SectionLabel(s.routingProfileDomainStrategy)
            LabeledDropdown(label = xrayDomainStrategy) { dismiss ->
                RoutingProfile.XRAY_DOMAIN_STRATEGIES.forEach { st ->
                    DropdownMenuItem(text = { Text(st) }, onClick = { xrayDomainStrategy = st; dismiss() })
                }
            }
            ExpertToggle(s.routingExpertSniffing, s.routingExpertSniffingDesc, xraySniffing) { xraySniffing = it }
            ExpertToggle(s.routingExpertRouteOnly, s.routingExpertRouteOnlyDesc, xrayRouteOnly) { xrayRouteOnly = it }

            SectionLabel("sing-box")
            SectionLabel(s.routingProfileDomainStrategy)
            LabeledDropdown(label = singboxDomainStrategy.ifBlank { s.routingExpertInherit }) { dismiss ->
                RoutingProfile.SINGBOX_DOMAIN_STRATEGIES.forEach { st ->
                    DropdownMenuItem(
                        text = { Text(st.ifBlank { s.routingExpertInherit }) },
                        onClick = { singboxDomainStrategy = st; dismiss() },
                    )
                }
            }
            ExpertToggle(s.routingExpertSniffing, s.routingExpertSniffingDesc, singboxSniff) { singboxSniff = it }
            ExpertToggle(s.routingExpertResolve, s.routingExpertResolveDesc, singboxResolve) { singboxResolve = it }
        }

        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = { clipboard.setText(AnnotatedString(assemble().toHappLink())) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(s.routingProfileShare)
        }

        OutlinedButton(onClick = { onSave(assemble()) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Check, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(s.saveAndApply)
        }
        if (onDelete != null) {
            TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text(s.routingProfileDelete, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun BucketField(label: String, hint: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(hint) },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ExpertToggle(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** A read-only outlined field that opens a dropdown of choices on tap. */
@Composable
private fun LabeledDropdown(label: String, items: @Composable (dismiss: () -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(label, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items { expanded = false }
        }
    }
}

private fun listToText(list: List<String>): String = list.joinToString("\n")

private fun linesToList(text: String): List<String> =
    text.split('\n', '\r', ',', ';').map { it.trim() }.filter { it.isNotEmpty() }

/** Compact local timestamp (yyyy-MM-dd HH:mm) without pulling in a formatter dependency. */
private fun formatTimestamp(ms: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    fun p(n: Int) = n.toString().padStart(2, '0')
    return "${cal.get(java.util.Calendar.YEAR)}-${p(cal.get(java.util.Calendar.MONTH) + 1)}-" +
        "${p(cal.get(java.util.Calendar.DAY_OF_MONTH))} " +
        "${p(cal.get(java.util.Calendar.HOUR_OF_DAY))}:${p(cal.get(java.util.Calendar.MINUTE))}"
}
