package org.olcbox.app.ui.features.home.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.ui.features.locations.PingsState
import org.olcbox.app.ui.features.locations.components.LocationRow
import org.olcbox.app.ui.features.locations.components.RefreshButton

/** Reserved pin key for the non-subscription "Свои локации" group (it has no subscription URL). */
const val CUSTOM_GROUP_KEY = "__custom_locations__"

@Composable
fun LocationSelectorScreen(
    modifier: Modifier = Modifier,
    onRefreshClick: (targetLocationIds: List<String>) -> Unit,
    onAddSubscriptionClick: () -> Unit,
    onAddLocationClick: () -> Unit,
    hasLoaded: Boolean = true,
    locations: List<LocationItem>,
    selectedLocationId: String?,
    pingsState: PingsState,
    onLocationSelected: (String) -> Unit,
    onLocationSettingsClick: (String) -> Unit,
    onDeleteSubscription: (List<String>) -> Unit = {},
    collapsedGroups: Set<String> = emptySet(),
    pinnedGroups: List<String> = emptyList(),
    pingSortedGroups: Set<String> = emptySet(),
    pingSortDescendingGroups: Set<String> = emptySet(),
    onToggleGroupCollapsed: (String) -> Unit = {},
    onToggleGroupPinned: (String) -> Unit = {},
    onToggleGroupPingSort: (String) -> Unit = {}
) {
    Column(modifier = modifier.fillMaxWidth()) {
        val subscriptionLocations = locations.filter { !it.subscriptionUrl.isNullOrBlank() }
        val subscriptionGroups = subscriptionLocations
            .groupBy { it.subscriptionGroupKey() }
            .values
            .toList()
            // Pinned groups float to the top, preserving pin order; the rest stay put.
            .sortedBy { group ->
                val key = group.firstOrNull()?.subscriptionGroupKey()
                val pinIndex = pinnedGroups.indexOf(key)
                if (pinIndex >= 0) pinIndex else Int.MAX_VALUE
            }
        val customLocations = locations.filter { it.subscriptionUrl.isNullOrBlank() }

        // --- Bulk-selection (long-press to multi-select subscriptions & inbounds, then delete) ---
        val selectedIds = remember { mutableStateListOf<String>() }
        var selectionMode by remember { mutableStateOf(false) }
        fun exitSelection() {
            selectionMode = false
            selectedIds.clear()
        }
        fun toggleSelected(id: String) {
            if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
            if (selectedIds.isEmpty()) selectionMode = false
        }
        fun startSelection(id: String) {
            selectionMode = true
            if (id !in selectedIds) selectedIds.add(id)
        }
        // Drop stale ids if the underlying list changed (e.g. after a delete).
        val knownIds = locations.map { it.storageId }.toSet()
        selectedIds.retainAll(knownIds)

        if (locations.isEmpty()) {
            // Don't flash the "add your first config" card during the initial async load — only show
            // it once we know the store is actually empty.
            if (hasLoaded) {
                RelaySetupCard(
                    onAddSubscriptionClick = onAddSubscriptionClick,
                    onAddLocationClick = onAddLocationClick
                )
            }
            return@Column
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (selectionMode) {
                // Selection action bar: shows the count, a delete-all-selected button and a close.
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { exitSelection() }) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = org.olcbox.app.ui.i18n.LocalStrings.current.cancel,
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = org.olcbox.app.ui.i18n.LocalStrings.current.selectedCount(selectedIds.size),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).padding(start = 4.dp)
                        )
                        IconButton(
                            onClick = {
                                val ids = selectedIds.toList()
                                if (ids.isNotEmpty()) onDeleteSubscription(ids)
                                exitSelection()
                            },
                            enabled = selectedIds.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = org.olcbox.app.ui.i18n.LocalStrings.current.delete,
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            Text(
                text = org.olcbox.app.ui.i18n.LocalStrings.current.configurations,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp)
            )

            // The custom-locations section. Individual inbounds inside it can be pinned (not the whole
            // group) — pinned ones float to the top of this section. Pin state is stored per storageId
            // in the same pinned set used for subscription groups.
            val customSection: @Composable () -> Unit = {
                if (customLocations.isNotEmpty()) {
                    // Pinned inbounds first (in pin order), the rest keep their natural order.
                    val orderedCustom = customLocations.sortedBy {
                        val i = pinnedGroups.indexOf(it.storageId)
                        if (i >= 0) i else Int.MAX_VALUE
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LocationGroupHeader(
                                title = org.olcbox.app.ui.i18n.LocalStrings.current.customLocations,
                                modifier = Modifier.weight(1f)
                            )

                            val customIds = customLocations.map { it.storageId }
                            val isCustomRefreshing = pingsState is PingsState.Loading &&
                                    pingsState.pendingLocationIds.any { it in customIds }

                            RefreshButton(
                                isRefreshing = isCustomRefreshing,
                                onClick = { onRefreshClick(customIds) },
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            orderedCustom.forEach { location ->
                                key(location.storageId) {
                                    LocationSelectorRow(
                                        location = location,
                                        selectedLocationId = selectedLocationId,
                                        pingsState = pingsState,
                                        onLocationSelected = onLocationSelected,
                                        onLocationSettingsClick = onLocationSettingsClick,
                                        selectionMode = selectionMode,
                                        isChecked = location.storageId in selectedIds,
                                        onToggleSelect = ::toggleSelected,
                                        onStartSelection = ::startSelection,
                                        // Per-inbound pin (custom locations only).
                                        showPin = true,
                                        isPinned = location.storageId in pinnedGroups,
                                        onTogglePin = { onToggleGroupPinned(location.storageId) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            subscriptionGroups.forEachIndexed { index, group ->
                val groupIds = group.map { it.storageId }
                val groupKey = group.firstOrNull()?.subscriptionGroupKey() ?: ""
                val isCollapsed = groupKey in collapsedGroups
                val isPinned = groupKey in pinnedGroups
                val isPingSorted = groupKey in pingSortedGroups
                val isPingDescending = groupKey in pingSortDescendingGroups
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Tapping the title (with its chevron) collapses/expands the list.
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onToggleGroupCollapsed(groupKey) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isCollapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                                    contentDescription = if (isCollapsed) "Expand" else "Collapse",
                                    modifier = Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                SubscriptionGroupHeader(
                                    locations = group,
                                    isPinned = isPinned,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            val isGroupRefreshing = pingsState is PingsState.Loading &&
                                    pingsState.pendingLocationIds.any { it in groupIds }

                            RefreshButton(
                                isRefreshing = isGroupRefreshing,
                                onClick = { onRefreshClick(groupIds) },
                                tint = MaterialTheme.colorScheme.primary
                            )

                            // Overflow menu: pin, sort-by-ping, delete.
                            SubscriptionGroupMenu(
                                isPinned = isPinned,
                                isPingSorted = isPingSorted,
                                isPingDescending = isPingDescending,
                                onTogglePin = { onToggleGroupPinned(groupKey) },
                                onTogglePingSort = { onToggleGroupPingSort(groupKey) },
                                onDelete = { onDeleteSubscription(groupIds) }
                            )
                        }

                        if (!isCollapsed) {
                            Spacer(modifier = Modifier.height(8.dp))

                            TrafficProgressBar(location = group.firstOrNull())

                            val orderedGroup = if (isPingSorted) {
                                group.sortedWith(pingComparator(pingsState, isPingDescending))
                            } else {
                                group
                            }

                            Column(
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                orderedGroup.forEach { location ->
                                    key(location.storageId) {
                                        LocationSelectorRow(
                                            location = location,
                                            selectedLocationId = selectedLocationId,
                                            pingsState = pingsState,
                                            onLocationSelected = onLocationSelected,
                                            onLocationSettingsClick = onLocationSettingsClick,
                                            selectionMode = selectionMode,
                                            isChecked = location.storageId in selectedIds,
                                            onToggleSelect = ::toggleSelected,
                                            onStartSelection = ::startSelection
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            customSection()

            FilledTonalButton(
                onClick = onAddLocationClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = org.olcbox.app.ui.i18n.LocalStrings.current.addCustomLocation,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            if (subscriptionLocations.isEmpty()) {
                FilledTonalButton(
                    onClick = onAddSubscriptionClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = org.olcbox.app.ui.i18n.LocalStrings.current.addSubscription,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun RelaySetupCard(
    onAddSubscriptionClick: () -> Unit,
    onAddLocationClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = org.olcbox.app.ui.i18n.LocalStrings.current.addRelaySetup,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp)
        )

        SetupActionRow(
            title = org.olcbox.app.ui.i18n.LocalStrings.current.addSubscription,
            subtitle = org.olcbox.app.ui.i18n.LocalStrings.current.importHint,
            icon = Icons.Outlined.QrCodeScanner,
            prominent = true,
            onClick = onAddSubscriptionClick
        )

        SetupActionRow(
            title = org.olcbox.app.ui.i18n.LocalStrings.current.createCustomLocation,
            subtitle = org.olcbox.app.ui.i18n.LocalStrings.current.createCustomLocationSubtitle,
            icon = Icons.Outlined.Add,
            onClick = onAddLocationClick
        )
    }
}

@Composable
private fun SetupActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    prominent: Boolean = false,
    onClick: () -> Unit
) {
    val containerColor = if (prominent) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    val borderColor = if (prominent) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    val contentColor = if (prominent) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
                color = if (prominent) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                contentColor = if (prominent) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(imageVector = icon, contentDescription = null)
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.72f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun TrafficProgressBar(location: LocationItem?) {
    val subscription = location?.metadata?.subscription ?: return
    val used = subscription.used?.takeIf { it.isNotBlank() }
    val available = subscription.available?.takeIf { it.isNotBlank() }
    if (used == null && available == null) return

    val usedBytes = parseTrafficBytes(used)
    val totalBytes = parseTrafficBytes(available)
    val fraction = if (usedBytes != null && totalBytes != null && totalBytes > 0L) {
        (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }

    val text = when {
        used != null && available != null -> "$used / $available"
        used != null -> used
        else -> available!!
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        // Filled portion: exact fraction when total is known, otherwise full pill.
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction ?: 1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary)
                .align(Alignment.CenterStart)
        )
        Text(
            text = text,
            color = if (fraction == null || fraction > 0.5f) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Parses a human-readable traffic string ("230,4 GB", "1.5 TB", "512 MB") into bytes.
 * Returns null for unlimited/unparseable values (e.g. "∞", "Unlimited", "").
 */
private fun parseTrafficBytes(raw: String?): Long? {
    val text = raw?.trim()?.lowercase() ?: return null
    if (text.isEmpty() || text.contains("∞") || text.contains("unlim")) return null

    val match = Regex("([0-9]+(?:[.,][0-9]+)?)\\s*([kmgtp]?i?b)?").find(text) ?: return null
    val number = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
    val unit = match.groupValues[2]

    val multiplier = when {
        unit.startsWith("t") -> 1024.0 * 1024 * 1024 * 1024
        unit.startsWith("g") -> 1024.0 * 1024 * 1024
        unit.startsWith("m") -> 1024.0 * 1024
        unit.startsWith("k") -> 1024.0
        unit.startsWith("p") -> 1024.0 * 1024 * 1024 * 1024 * 1024
        else -> 1.0
    }
    return (number * multiplier).toLong()
}

@Composable
private fun SubscriptionGroupMenu(
    isPinned: Boolean,
    isPingSorted: Boolean,
    isPingDescending: Boolean,
    onTogglePin: () -> Unit,
    onTogglePingSort: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val s = org.olcbox.app.ui.i18n.LocalStrings.current

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = "More",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(if (isPinned) s.groupUnpinFromTop else s.groupPinToTop) },
                leadingIcon = {
                    Icon(
                        imageVector = if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = null
                    )
                },
                onClick = {
                    onTogglePin()
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text(s.groupSortByPing) },
                leadingIcon = {
                    Icon(imageVector = Icons.Outlined.Sort, contentDescription = null)
                },
                trailingIcon = if (isPingSorted) {
                    {
                        Icon(
                            imageVector = if (isPingDescending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                } else null,
                onClick = {
                    onTogglePingSort()
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = {
                    Text(s.groupDelete, color = MaterialTheme.colorScheme.error)
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                onClick = {
                    onDelete()
                    expanded = false
                }
            )
        }
    }
}

@Composable
private fun LocationGroupHeader(
    title: String,
    modifier: Modifier = Modifier,
    isPinned: Boolean = false
) {
    Row(
        modifier = modifier.padding(top = 2.dp, start = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isPinned) {
            Icon(
                imageVector = Icons.Filled.PushPin,
                contentDescription = "Pinned",
                modifier = Modifier
                    .size(20.dp)
                    .padding(end = 4.dp),
                tint = MaterialTheme.colorScheme.error
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SubscriptionGroupHeader(
    locations: List<LocationItem>,
    isPinned: Boolean = false,
    modifier: Modifier = Modifier
) {
    val first = locations.firstOrNull()
    val title = first?.subscriptionTitle().orEmpty().ifBlank { org.olcbox.app.ui.i18n.LocalStrings.current.subscriptionsSection }
    val details = first?.subscriptionDetails()

    Column(modifier = modifier.padding(start = 4.dp, top = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isPinned) {
                Icon(
                    imageVector = Icons.Filled.PushPin,
                    contentDescription = "Pinned",
                    modifier = Modifier
                        .size(20.dp)
                        .padding(end = 4.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (!details.isNullOrBlank()) {
            Text(
                text = details,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun LocationSelectorRow(
    location: LocationItem,
    selectedLocationId: String?,
    pingsState: PingsState,
    onLocationSelected: (String) -> Unit,
    onLocationSettingsClick: (String) -> Unit,
    selectionMode: Boolean = false,
    isChecked: Boolean = false,
    onToggleSelect: (String) -> Unit = {},
    onStartSelection: (String) -> Unit = {},
    showPin: Boolean = false,
    isPinned: Boolean = false,
    onTogglePin: () -> Unit = {}
) {
    val pingMs = pingsState.pingFor(location.storageId)
    val isLoading = pingsState.isChecking(location.storageId)
    val isOffline = pingsState.isOffline(location.storageId)

    // Stable per-row callbacks: without remember(), each ping tick allocates fresh lambdas, which
    // forces every LocationRow to recompose (300+ rows → scroll jank when a ping pass starts).
    // In selection mode a tap toggles the checkbox; otherwise it selects the location as usual.
    val onClick = remember(location.storageId, selectionMode, onLocationSelected, onToggleSelect) {
        {
            if (selectionMode) onToggleSelect(location.storageId)
            else onLocationSelected(location.storageId)
        }
    }
    val onSettings = remember(location.storageId, onLocationSettingsClick) {
        { onLocationSettingsClick(location.storageId) }
    }
    // Long-press enters selection mode and selects this row (or toggles if already selecting).
    val onLongPress = remember(location.storageId, selectionMode, onStartSelection, onToggleSelect) {
        {
            if (selectionMode) onToggleSelect(location.storageId)
            else onStartSelection(location.storageId)
        }
    }

    LocationRow(
        location = location,
        isSelected = selectedLocationId == location.storageId,
        isLoading = isLoading,
        isError = isOffline,
        pingMs = pingMs,
        selectionMode = selectionMode,
        isChecked = isChecked,
        showPin = showPin,
        isPinned = isPinned,
        onTogglePin = onTogglePin,
        onSettingsClick = onSettings,
        onLongClick = onLongPress,
        onClick = onClick
    )
}

/**
 * Orders locations fastest-first: known pings ascending, then not-yet-measured,
 * then offline (null ping) at the very bottom.
 */
private fun pingComparator(pingsState: PingsState, descending: Boolean = false): Comparator<LocationItem> {
    return Comparator { a, b ->
        val ra = rankFor(pingsState, a)
        val rb = rankFor(pingsState, b)
        // Offline / not-yet-measured always sink to the bottom regardless of direction.
        val aSpecial = ra >= Long.MAX_VALUE - 1
        val bSpecial = rb >= Long.MAX_VALUE - 1
        when {
            aSpecial || bSpecial -> ra.compareTo(rb)
            descending -> rb.compareTo(ra)
            else -> ra.compareTo(rb)
        }
    }
}

private fun rankFor(pingsState: PingsState, location: LocationItem): Long {
    val offline = pingsState.isOffline(location.storageId)
    if (offline) return Long.MAX_VALUE
    val ping = pingsState.pingFor(location.storageId)
    // Not measured yet sits just above offline but below any real ping.
    return ping?.toLong() ?: (Long.MAX_VALUE - 1)
}

private fun PingsState.pingFor(locationId: String): Int? {
    return when (this) {
        PingsState.Idle -> null

        is PingsState.Loading -> {
            if (currentPings.containsKey(locationId)) {
                currentPings[locationId]
            } else {
                lastPings?.get(locationId)
            }
        }

        is PingsState.Success -> {
            pings[locationId]
        }

        is PingsState.Error -> {
            lastPings?.get(locationId)
        }
    }
}

private fun PingsState.isChecking(locationId: String): Boolean {
    return this is PingsState.Loading && locationId in pendingLocationIds
}

private fun PingsState.isOffline(locationId: String): Boolean {
    return when (this) {
        PingsState.Idle -> false

        is PingsState.Loading -> {
            currentPings.containsKey(locationId) && currentPings[locationId] == null
        }

        is PingsState.Success -> {
            pings.containsKey(locationId) && pings[locationId] == null
        }

        is PingsState.Error -> false
    }
}

private fun LocationItem.subscriptionGroupKey(): String {
    return listOfNotNull(
        metadata?.subscription?.name?.takeIf { it.isNotBlank() },
        subscriptionUrl?.trim()?.takeIf { it.isNotBlank() }
    ).joinToString("|").ifBlank { storageId }
}

private fun LocationItem.subscriptionTitle(): String {
    val subscription = metadata?.subscription

    return listOfNotNull(
        subscription?.icon?.takeIf { it.isNotBlank() },
        subscription?.name?.takeIf { it.isNotBlank() }
            ?: org.olcbox.app.ui.i18n.stringsFor(org.olcbox.app.ui.i18n.LocalizationState.effective).subscriptionsSection
    ).joinToString(" ")
}

private fun LocationItem.subscriptionDetails(): String? {
    val subscription = metadata?.subscription ?: return null

    return listOfNotNull(
        quotaText(subscription.used, subscription.available),
        subscription.refresh?.takeIf { it.isNotBlank() }?.let { "Refresh $it" }
    ).joinToString(" · ").takeIf { it.isNotBlank() }
}

private fun quotaText(used: String?, available: String?): String? {
    return when {
        !used.isNullOrBlank() && !available.isNullOrBlank() -> "$used used · $available available"
        !used.isNullOrBlank() -> "$used used"
        !available.isNullOrBlank() -> "$available available"
        else -> null
    }
}

private fun plural(value: Long, unit: String): String {
    return "$value $unit${if (value == 1L) "" else "s"}"
}

private const val MINUTE_MILLIS = 60_000L
private const val HOUR_MILLIS = 60 * MINUTE_MILLIS
private const val DAY_MILLIS = 24 * HOUR_MILLIS
