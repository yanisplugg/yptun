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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SyncDisabled
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.data.model.CustomGroup
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.ui.features.locations.PingsState
import org.olcbox.app.ui.features.locations.components.LocationRow
import org.olcbox.app.ui.features.locations.components.RefreshButton

/**
 * Emits the configuration list directly into the hosting [LazyListScope] (the Home screen is one
 * big LazyColumn). This is what keeps long subscriptions smooth: each server row is its own lazy
 * item, so only the handful of visible rows are composed/laid-out and they are recycled on scroll —
 * instead of materializing all 300+ rows at once inside a plain verticalScroll Column (which janked
 * badly while scrolling, especially during a ping pass).
 *
 * Items use stable keys (group key / storageId) so Compose preserves and recycles them correctly.
 */
fun LazyListScope.locationSelectorContent(
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
    // Toggle a single subscription's automatic refresh (keyed by its URL).
    onSetSubscriptionAutoUpdate: (subscriptionUrl: String, enabled: Boolean) -> Unit = { _, _ -> },
    // Bulk multi-select (long-press): hoisted to the host screen.
    selectionMode: Boolean = false,
    selectedIds: List<String> = emptyList(),
    onToggleSelect: (String) -> Unit = {},
    onStartSelection: (String) -> Unit = {},
    collapsedGroups: Set<String> = emptySet(),
    pinnedGroups: List<String> = emptyList(),
    pingSortedGroups: Set<String> = emptySet(),
    pingSortDescendingGroups: Set<String> = emptySet(),
    pinnedCustomLocations: List<String> = emptyList(),
    customLocationsPingSorted: Boolean = false,
    customLocationsPingSortDescending: Boolean = false,
    onToggleGroupCollapsed: (String) -> Unit = {},
    onToggleGroupPinned: (String) -> Unit = {},
    onToggleGroupPingSort: (String) -> Unit = {},
    onToggleCustomLocationPinned: (String) -> Unit = {},
    onToggleCustomLocationsPingSort: () -> Unit = {},
    // User-created folders that reorganise the list (members move inside them).
    customGroups: List<CustomGroup> = emptyList(),
    onToggleFolderCollapsed: (String) -> Unit = {},
    onToggleFolderPinned: (String) -> Unit = {},
    onRenameFolder: (CustomGroup) -> Unit = {},
    onDeleteFolder: (String) -> Unit = {},
    // Open the "choose folder" picker for the given member keys (move/remove a whole subscription).
    onRequestMoveToFolder: (List<String>) -> Unit = {}
) {
    if (locations.isEmpty()) {
        // Don't flash the "add your first config" card during the initial async load — only show
        // it once we know the store is actually empty.
        if (hasLoaded) {
            item(key = "relay-setup") {
                RelaySetupCard(
                    onAddSubscriptionClick = onAddSubscriptionClick,
                    onAddLocationClick = onAddLocationClick
                )
            }
        } else {
            // Initial async decode of the saved configs can take a moment when there are many of
            // them; show a spinner so the list area doesn't sit blank (looking frozen) until it loads.
            item(key = "locations-loading") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
        return
    }

    val subscriptionLocations = locations.filter { !it.subscriptionUrl.isNullOrBlank() }
    val allCustomLocations = locations.filter { it.subscriptionUrl.isNullOrBlank() }

    // Folder membership: whole subscriptions (by group key) and individual custom locations (by id)
    // that the user filed into a [CustomGroup]. These are pulled OUT of the default sections and
    // rendered inside their folder instead.
    val folderSubKeys = customGroups.flatMap { folder ->
        folder.members.filter { it.startsWith(CustomGroup.MEMBER_SUB_PREFIX) }
            .map { it.removePrefix(CustomGroup.MEMBER_SUB_PREFIX) }
    }.toSet()
    val folderLocIds = customGroups.flatMap { folder ->
        folder.members.filter { it.startsWith(CustomGroup.MEMBER_LOC_PREFIX) }
            .map { it.removePrefix(CustomGroup.MEMBER_LOC_PREFIX) }
    }.toSet()

    fun groupsOf(list: List<LocationItem>): List<List<LocationItem>> = list
        .groupBy { it.subscriptionGroupKey() }
        .values
        .toList()
        // Pinned groups float to the top, preserving pin order; the rest stay put.
        .sortedBy { group ->
            val key = group.firstOrNull()?.subscriptionGroupKey()
            val pinIndex = pinnedGroups.indexOf(key)
            if (pinIndex >= 0) pinIndex else Int.MAX_VALUE
        }

    val subscriptionGroups = groupsOf(
        subscriptionLocations.filter { it.subscriptionGroupKey() !in folderSubKeys }
    )
    val customLocations = allCustomLocations.filter { it.storageId !in folderLocIds }

    item(key = "configurations-label") {
        Text(
            text = org.olcbox.app.ui.i18n.LocalStrings.current.configurations,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp)
        )
    }

    // One subscription group (header + rows). A local function so folders can render their member
    // subscriptions with the exact same UI as the top-level section.
    fun renderSubscriptionGroup(group: List<LocationItem>) {
        val groupIds = group.map { it.storageId }
        val groupKey = group.firstOrNull()?.subscriptionGroupKey() ?: ""
        val isCollapsed = groupKey in collapsedGroups
        val isPinned = groupKey in pinnedGroups
        val isPingSorted = groupKey in pingSortedGroups
        val isPingDescending = groupKey in pingSortDescendingGroups

        item(key = "group-header-$groupKey") {
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
                                pingsState = pingsState,
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

                        // Overflow menu: pin, sort-by-ping, auto-update, delete.
                        val groupAutoUpdate = group.none { it.metadata?.subscription?.autoUpdateEnabled == false }
                        val groupSubUrl = group.firstOrNull()?.subscriptionUrl
                        val groupWebPageUrl = group.firstNotNullOfOrNull {
                            it.metadata?.subscription?.webPageUrl?.takeIf { url -> url.isNotBlank() }
                        }
                        SubscriptionGroupMenu(
                            isPinned = isPinned,
                            isPingSorted = isPingSorted,
                            isPingDescending = isPingDescending,
                            autoUpdateEnabled = groupAutoUpdate,
                            onTogglePin = { onToggleGroupPinned(groupKey) },
                            onTogglePingSort = { onToggleGroupPingSort(groupKey) },
                            onToggleAutoUpdate = {
                                groupSubUrl?.let { onSetSubscriptionAutoUpdate(it, !groupAutoUpdate) }
                            },
                            onMoveToFolder = { onRequestMoveToFolder(listOf(CustomGroup.subMember(groupKey))) },
                            onDelete = { onDeleteSubscription(groupIds) },
                            subscriptionPageUrl = groupWebPageUrl
                        )
                    }

                    if (!isCollapsed) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TrafficProgressBar(location = group.firstOrNull())
                    }
                }
            }
        }

        if (!isCollapsed) {
            val orderedGroup = if (isPingSorted) {
                group.sortedWith(pingComparator(pingsState, isPingDescending))
            } else {
                group
            }

            items(
                items = orderedGroup,
                key = { "row-${it.storageId}" }
            ) { location ->
                LocationSelectorRow(
                    location = location,
                    selectedLocationId = selectedLocationId,
                    pingsState = pingsState,
                    onLocationSelected = onLocationSelected,
                    onLocationSettingsClick = onLocationSettingsClick,
                    selectionMode = selectionMode,
                    isChecked = location.storageId in selectedIds,
                    onToggleSelect = onToggleSelect,
                    onStartSelection = onStartSelection
                )
            }
        }
    }

    // ── Folders (user-created groups) first: pinned folders float to the top, then declaration order.
    customGroups
        .sortedByDescending { it.pinned }
        .forEach { folder ->
            val subKeys = folder.members
                .filter { it.startsWith(CustomGroup.MEMBER_SUB_PREFIX) }
                .map { it.removePrefix(CustomGroup.MEMBER_SUB_PREFIX) }
                .toSet()
            val locIds = folder.members
                .filter { it.startsWith(CustomGroup.MEMBER_LOC_PREFIX) }
                .map { it.removePrefix(CustomGroup.MEMBER_LOC_PREFIX) }
                .toSet()
            val memberSubGroups = groupsOf(
                subscriptionLocations.filter { it.subscriptionGroupKey() in subKeys }
            )
            val memberCustom = allCustomLocations.filter { it.storageId in locIds }
            val memberIds = memberSubGroups.flatten().map { it.storageId } + memberCustom.map { it.storageId }

            // The whole folder is ONE item: a single tinted Surface (the group's colour) holding the
            // header AND all members, so an expanded folder reads as one continuous container with no
            // gaps between its subscriptions/locations. (Folders hold few items, so non-lazy is fine.)
            item(key = "folder-${folder.id}") {
                val isFolderRefreshing = pingsState is PingsState.Loading &&
                        pingsState.pendingLocationIds.any { it in memberIds }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FolderGroupHeader(
                            folder = folder,
                            memberCount = memberSubGroups.size + memberCustom.size,
                            isRefreshing = isFolderRefreshing,
                            onToggleCollapsed = { onToggleFolderCollapsed(folder.id) },
                            onRefresh = { onRefreshClick(memberIds) },
                            onTogglePin = { onToggleFolderPinned(folder.id) },
                            onRename = { onRenameFolder(folder) },
                            onDelete = { onDeleteFolder(folder.id) }
                        )

                        if (!folder.collapsed) {
                            // Member subscriptions: each a card inside the folder fill.
                            memberSubGroups.forEach { mGroup ->
                                val mIds = mGroup.map { it.storageId }
                                val mKey = mGroup.firstOrNull()?.subscriptionGroupKey() ?: ""
                                val mCollapsed = mKey in collapsedGroups
                                val mPinned = mKey in pinnedGroups
                                val mPingSorted = mKey in pingSortedGroups
                                val mPingDesc = mKey in pingSortDescendingGroups
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainer
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable { onToggleGroupCollapsed(mKey) },
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = if (mCollapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                                                    contentDescription = if (mCollapsed) "Expand" else "Collapse",
                                                    modifier = Modifier.size(22.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                SubscriptionGroupHeader(
                                                    locations = mGroup,
                                                    pingsState = pingsState,
                                                    isPinned = mPinned,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                            val mRefreshing = pingsState is PingsState.Loading &&
                                                    pingsState.pendingLocationIds.any { it in mIds }
                                            RefreshButton(
                                                isRefreshing = mRefreshing,
                                                onClick = { onRefreshClick(mIds) },
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            val mAutoUpdate = mGroup.none { it.metadata?.subscription?.autoUpdateEnabled == false }
                                            val mSubUrl = mGroup.firstOrNull()?.subscriptionUrl
                                            val mWebPageUrl = mGroup.firstNotNullOfOrNull {
                                                it.metadata?.subscription?.webPageUrl?.takeIf { url -> url.isNotBlank() }
                                            }
                                            SubscriptionGroupMenu(
                                                isPinned = mPinned,
                                                isPingSorted = mPingSorted,
                                                isPingDescending = mPingDesc,
                                                autoUpdateEnabled = mAutoUpdate,
                                                onTogglePin = { onToggleGroupPinned(mKey) },
                                                onTogglePingSort = { onToggleGroupPingSort(mKey) },
                                                onToggleAutoUpdate = {
                                                    mSubUrl?.let { onSetSubscriptionAutoUpdate(it, !mAutoUpdate) }
                                                },
                                                onMoveToFolder = { onRequestMoveToFolder(listOf(CustomGroup.subMember(mKey))) },
                                                onDelete = { onDeleteSubscription(mIds) },
                                                subscriptionPageUrl = mWebPageUrl
                                            )
                                        }
                                        if (!mCollapsed) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            TrafficProgressBar(location = mGroup.firstOrNull())
                                            val ordered = if (mPingSorted) {
                                                mGroup.sortedWith(pingComparator(pingsState, mPingDesc))
                                            } else {
                                                mGroup
                                            }
                                            ordered.forEach { location ->
                                                LocationSelectorRow(
                                                    location = location,
                                                    selectedLocationId = selectedLocationId,
                                                    pingsState = pingsState,
                                                    onLocationSelected = onLocationSelected,
                                                    onLocationSettingsClick = onLocationSettingsClick,
                                                    selectionMode = selectionMode,
                                                    isChecked = location.storageId in selectedIds,
                                                    onToggleSelect = onToggleSelect,
                                                    onStartSelection = onStartSelection
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Member custom locations: rows directly on the folder fill.
                            memberCustom.forEach { location ->
                                LocationSelectorRow(
                                    location = location,
                                    selectedLocationId = selectedLocationId,
                                    pingsState = pingsState,
                                    onLocationSelected = onLocationSelected,
                                    onLocationSettingsClick = onLocationSettingsClick,
                                    isPinned = location.storageId in pinnedCustomLocations,
                                    onTogglePinned = { onToggleCustomLocationPinned(location.storageId) },
                                    selectionMode = selectionMode,
                                    isChecked = location.storageId in selectedIds,
                                    onToggleSelect = onToggleSelect,
                                    onStartSelection = onStartSelection
                                )
                            }

                            if (memberIds.isEmpty()) {
                                Text(
                                    text = org.olcbox.app.ui.i18n.LocalStrings.current.folderEmpty,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                                    modifier = Modifier.padding(start = 10.dp, top = 2.dp, bottom = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

    // ── Then the un-foldered subscription groups.
    subscriptionGroups.forEach { renderSubscriptionGroup(it) }

    if (customLocations.isNotEmpty()) {
        item(key = "custom-header") {
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

                // Overflow menu: sort-by-ping for the whole "own locations" section.
                CustomLocationsMenu(
                    isPingSorted = customLocationsPingSorted,
                    isPingDescending = customLocationsPingSortDescending,
                    onTogglePingSort = onToggleCustomLocationsPingSort
                )
            }
        }

        // Pinned custom locations float to the top (in pin order); optionally ping-sorted.
        val orderedCustom = run {
            val base = if (customLocationsPingSorted) {
                customLocations.sortedWith(pingComparator(pingsState, customLocationsPingSortDescending))
            } else {
                customLocations
            }
            base.sortedBy { loc ->
                val pinIndex = pinnedCustomLocations.indexOf(loc.storageId)
                if (pinIndex >= 0) pinIndex else Int.MAX_VALUE
            }
        }

        items(
            items = orderedCustom,
            key = { "custom-row-${it.storageId}" }
        ) { location ->
            LocationSelectorRow(
                location = location,
                selectedLocationId = selectedLocationId,
                pingsState = pingsState,
                onLocationSelected = onLocationSelected,
                onLocationSettingsClick = onLocationSettingsClick,
                isPinned = location.storageId in pinnedCustomLocations,
                onTogglePinned = { onToggleCustomLocationPinned(location.storageId) },
                selectionMode = selectionMode,
                isChecked = location.storageId in selectedIds,
                onToggleSelect = onToggleSelect,
                onStartSelection = onStartSelection
            )
        }
    }

    item(key = "add-location-button") {
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
    }

    if (subscriptionLocations.isEmpty()) {
        item(key = "add-subscription-button") {
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
    autoUpdateEnabled: Boolean,
    onTogglePin: () -> Unit,
    onTogglePingSort: () -> Unit,
    onToggleAutoUpdate: () -> Unit,
    onMoveToFolder: () -> Unit,
    onDelete: () -> Unit,
    subscriptionPageUrl: String? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val s = org.olcbox.app.ui.i18n.LocalStrings.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

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
            // Remnawave `profile-web-page-url` — the panel's subscription/management page.
            if (!subscriptionPageUrl.isNullOrBlank()) {
                DropdownMenuItem(
                    text = { Text(s.visitSubscriptionPage) },
                    leadingIcon = { Icon(Icons.Outlined.OpenInNew, contentDescription = null) },
                    onClick = {
                        runCatching { uriHandler.openUri(subscriptionPageUrl) }
                        expanded = false
                    }
                )
            }
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
                text = { Text(s.groupAutoUpdate) },
                leadingIcon = {
                    Icon(
                        imageVector = if (autoUpdateEnabled) Icons.Outlined.Sync else Icons.Outlined.SyncDisabled,
                        contentDescription = null
                    )
                },
                trailingIcon = if (autoUpdateEnabled) {
                    {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                } else null,
                onClick = {
                    onToggleAutoUpdate()
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text(s.moveToFolder) },
                leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                onClick = {
                    onMoveToFolder()
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
private fun CustomLocationsMenu(
    isPingSorted: Boolean,
    isPingDescending: Boolean,
    onTogglePingSort: () -> Unit
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
        }
    }
}

/** Header for a user-created folder ([CustomGroup]): chevron + name + member count, refresh-all, menu. */
@Composable
private fun FolderGroupHeader(
    folder: CustomGroup,
    memberCount: Int,
    isRefreshing: Boolean,
    onToggleCollapsed: () -> Unit,
    onRefresh: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    // No own Surface — the folder block already provides the tinted container background.
    Row(
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onToggleCollapsed() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (folder.collapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                    contentDescription = if (folder.collapsed) "Expand" else "Collapse",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = if (folder.pinned) Icons.Filled.PushPin else Icons.Outlined.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = memberCount.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
            }

            RefreshButton(
                isRefreshing = isRefreshing,
                onClick = onRefresh,
                tint = MaterialTheme.colorScheme.primary
            )

            FolderGroupMenu(
                isPinned = folder.pinned,
                onTogglePin = onTogglePin,
                onRename = onRename,
                onDelete = onDelete
            )
    }
}

@Composable
private fun FolderGroupMenu(
    isPinned: Boolean,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val s = org.olcbox.app.ui.i18n.LocalStrings.current

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = "More",
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(if (isPinned) s.groupUnpinFromTop else s.groupPinToTop) },
                leadingIcon = {
                    Icon(if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, contentDescription = null)
                },
                onClick = { onTogglePin(); expanded = false }
            )
            DropdownMenuItem(
                text = { Text(s.folderRename) },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                onClick = { onRename(); expanded = false }
            )
            DropdownMenuItem(
                text = { Text(s.folderDelete, color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = { onDelete(); expanded = false }
            )
        }
    }
}

@Composable
private fun LocationGroupHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 2.dp, start = 4.dp)
    )
}

@Composable
private fun SubscriptionGroupHeader(
    locations: List<LocationItem>,
    pingsState: PingsState,
    isPinned: Boolean = false,
    modifier: Modifier = Modifier
) {
    val first = locations.firstOrNull()
    val title = first?.subscriptionTitle().orEmpty().ifBlank { org.olcbox.app.ui.i18n.LocalStrings.current.subscriptionsSection }
    val info = first?.subscriptionInfo()

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
            // Red "!" badge when the subscription expires within 2 days; tap reveals the exact date.
            if (info?.expiryUrgent == true && info.expiryDateTime != null) {
                Spacer(modifier = Modifier.width(6.dp))
                ExpiryWarningBadge(
                    dateTime = info.expiryDateTime,
                    daysLeft = info.daysLeft ?: 0L
                )
            }
            // Optional "live/total" badge: servers that answered the last ping pass, gated on the toggle.
            if (org.olcbox.app.ui.features.locations.components.LocalShowSubscriptionAliveCount.current &&
                locations.isNotEmpty()
            ) {
                val alive = locations.count { pingsState.pingFor(it.storageId) != null }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "$alive/${locations.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (alive > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        if (info != null) {
            // Stacked, small font so nothing gets squeezed: last-refresh time on top, auto-refresh
            // interval beneath it.
            info.updatedAt?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
            info.interval?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Optional expiry date ("до дд.мм.гггг"), gated on the app-settings toggle, shown last
            // (under the auto-refresh interval line).
            if (org.olcbox.app.ui.features.locations.components.LocalShowSubscriptionExpiry.current) {
                info.expiryUntil?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Small red circled-"!" badge for a subscription that expires within 2 days. Tapping it opens a tiny
 * popup spelling out the exact end date and how many days are left.
 */
@Composable
private fun ExpiryWarningBadge(dateTime: String, daysLeft: Long) {
    val s = org.olcbox.app.ui.i18n.LocalStrings.current
    var showDetail by remember { mutableStateOf(false) }

    Box {
        Icon(
            imageVector = Icons.Filled.Error,
            contentDescription = s.subscriptionExpiringSoon,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .clickable { showDetail = true }
        )
        DropdownMenu(
            expanded = showDetail,
            onDismissRequest = { showDetail = false }
        ) {
            Text(
                text = s.subscriptionExpiryFull(dateTime, daysLeft),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
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
    isPinned: Boolean = false,
    onTogglePinned: (() -> Unit)? = null,
    selectionMode: Boolean = false,
    isChecked: Boolean = false,
    onToggleSelect: (String) -> Unit = {},
    onStartSelection: (String) -> Unit = {}
) {
    val pingMs = pingsState.pingFor(location.storageId)
    val isLoading = pingsState.isChecking(location.storageId)
    val isOffline = pingsState.isOffline(location.storageId)

    // Stable per-row callbacks. Keyed ONLY by storageId so their identity survives ping ticks: the
    // parent recomposes every ~150ms during a ping pass and reallocates onLocationSelected/Settings,
    // so keying remember() on those (instead of storageId) would invalidate every tick and force all
    // 300+ rows to recompose → scroll jank. rememberUpdatedState keeps calling the latest callback.
    val latestSelected by rememberUpdatedState(onLocationSelected)
    val latestSettings by rememberUpdatedState(onLocationSettingsClick)
    val latestToggle by rememberUpdatedState(onToggleSelect)
    val latestStart by rememberUpdatedState(onStartSelection)
    // In selection mode a tap toggles the checkbox; otherwise it selects the location as usual.
    val onClick = remember(location.storageId, selectionMode) {
        { if (selectionMode) latestToggle(location.storageId) else latestSelected(location.storageId) }
    }
    val onSettings = remember(location.storageId) { { latestSettings(location.storageId) } }
    // Long-press starts/extends the multi-selection.
    val onLongPress = remember(location.storageId, selectionMode) {
        { if (selectionMode) latestToggle(location.storageId) else latestStart(location.storageId) }
    }

    LocationRow(
        location = location,
        isSelected = selectedLocationId == location.storageId,
        isLoading = isLoading,
        isError = isOffline,
        pingMs = pingMs,
        selectionMode = selectionMode,
        isChecked = isChecked,
        onSettingsClick = onSettings,
        onLongClick = onLongPress,
        onClick = onClick,
        isPinned = isPinned,
        onTogglePinned = onTogglePinned
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

internal fun LocationItem.subscriptionGroupKey(): String {
    return listOfNotNull(
        metadata?.subscription?.name?.takeIf { it.isNotBlank() },
        subscriptionUrl?.trim()?.takeIf { it.isNotBlank() }
    ).joinToString("|").ifBlank { storageId }
}

/**
 * The [CustomGroup] member key this location contributes when filed into a folder: a whole
 * subscription is keyed by its group key, a custom location by its storage id.
 */
internal fun LocationItem.folderMemberKey(): String =
    if (!subscriptionUrl.isNullOrBlank()) {
        CustomGroup.subMember(subscriptionGroupKey())
    } else {
        CustomGroup.locMember(storageId)
    }

private fun LocationItem.subscriptionTitle(): String {
    val subscription = metadata?.subscription

    return listOfNotNull(
        subscription?.icon?.takeIf { it.isNotBlank() },
        subscription?.name?.takeIf { it.isNotBlank() }
            ?: org.olcbox.app.ui.i18n.stringsFor(org.olcbox.app.ui.i18n.LocalizationState.effective).subscriptionsSection
    ).joinToString(" ")
}

/**
 * Subscription header content. The second header row shows the last-refresh time on the left and the
 * auto-refresh interval on the right. The expiry no longer takes a line of its own — when ≤2 days
 * remain it surfaces as a red warning badge by the title ([expiryUrgent]); tapping it reveals the
 * full [expiryDateTime] / [daysLeft] detail.
 */
private data class SubscriptionInfo(
    val expiryDateTime: String?,
    /** Date-only "до дд.мм.гггг" line, shown when the "show expiry" toggle is on. */
    val expiryUntil: String?,
    val daysLeft: Long?,
    val expiryUrgent: Boolean,
    val interval: String?,
    val updatedAt: String?
)

@OptIn(kotlin.time.ExperimentalTime::class)
private fun LocationItem.subscriptionInfo(): SubscriptionInfo? {
    val subscription = metadata?.subscription ?: return null
    val s = org.olcbox.app.ui.i18n.stringsFor(org.olcbox.app.ui.i18n.LocalizationState.effective)
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    // End date (with time) + days-left; flag as urgent (red badge) when ≤2 days remain (incl. expired).
    var daysLeft: Long? = null
    val expiryDateTime = subscription.expiresAtEpochMs?.let {
        daysLeft = (it - now).floorDiv(DAY_MILLIS)
        org.olcbox.app.util.IsoTime.formatLocalDateTime(it)
    }
    val expiryUntil = subscription.expiresAtEpochMs?.let {
        s.subscriptionUntil(org.olcbox.app.util.IsoTime.formatLocalDate(it))
    }
    val expiryUrgent = daysLeft?.let { it <= 2 } ?: false
    val interval = subscription.updateIntervalHours?.let { s.subscriptionEvery(it) }
    // Last successful refresh, shown on the left of the second header row.
    val updatedAt = subscription.lastRefreshAtEpochMs?.let {
        s.subscriptionUpdatedAt(org.olcbox.app.util.IsoTime.formatLocalDateTime(it))
    }

    val info = SubscriptionInfo(
        expiryDateTime = expiryDateTime,
        expiryUntil = expiryUntil,
        daysLeft = daysLeft,
        expiryUrgent = expiryUrgent,
        interval = interval,
        updatedAt = updatedAt
    )
    val empty = expiryDateTime == null &&
        info.interval.isNullOrBlank() && info.updatedAt.isNullOrBlank()
    return if (empty) null else info
}

private fun plural(value: Long, unit: String): String {
    return "$value $unit${if (value == 1L) "" else "s"}"
}

private const val MINUTE_MILLIS = 60_000L
private const val HOUR_MILLIS = 60 * MINUTE_MILLIS
private const val DAY_MILLIS = 24 * HOUR_MILLIS
