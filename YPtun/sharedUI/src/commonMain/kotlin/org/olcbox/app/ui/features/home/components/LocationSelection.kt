package org.olcbox.app.ui.features.home.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SyncDisabled
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import org.jetbrains.compose.resources.decodeToImageBitmap
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
import org.olcbox.app.data.model.SubscriptionMetadata
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.ui.features.locations.PingsState
import org.olcbox.app.ui.features.locations.components.LocationRow
import org.olcbox.app.ui.features.locations.components.AutoPickButton
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
    // Ping a subscription's servers and connect to the best one (spinner while [autoPickRunning]).
    onAutoPickClick: (targetLocationIds: List<String>) -> Unit = {},
    autoPickRunning: Boolean = false,
    onAddSubscriptionClick: () -> Unit,
    onAddLocationClick: () -> Unit,
    hasLoaded: Boolean = true,
    // Render server/location cards in a 2-column grid (headers stay full-width). App-settings toggle.
    twoColumns: Boolean = false,
    locations: List<LocationItem>,
    selectedLocationId: String?,
    pingsState: PingsState,
    onLocationSelected: (String) -> Unit,
    onLocationSettingsClick: (String) -> Unit,
    onDeleteSubscription: (List<String>) -> Unit = {},
    // Toggle a single subscription's automatic refresh (keyed by its URL).
    onSetSubscriptionAutoUpdate: (subscriptionUrl: String, enabled: Boolean) -> Unit = { _, _ -> },
    // Re-download a single subscription now (keyed by its URL), triggered from its overflow menu.
    onRefreshSubscription: (subscriptionUrl: String) -> Unit = {},
    // Give a subscription (keyed by its URL) the user's own name; blank restores the panel's.
    onRenameSubscription: (subscriptionUrl: String, name: String) -> Unit = { _, _ -> },
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
        // Free-servers list: drawn like a folder — one green-tinted container holding header AND rows.
        val isFree = group.firstOrNull()?.subscriptionUrl?.trim() == org.olcbox.app.ui.features.home.FREE_SERVERS_URL

        if (isCollapsed) {
            item(key = "group-header-$groupKey") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = if (isFree) {
                        androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surfaceContainer, androidx.compose.ui.graphics.Color(0xFF43A047), 0.22f)
                    } else MaterialTheme.colorScheme.surfaceContainer
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
                                    imageVector = Icons.Outlined.ExpandMore,
                                    contentDescription = "Expand",
                                    modifier = Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                SubscriptionGroupHeader(
                                    locations = group,
                                    pingsState = pingsState,
                                    isPinned = isPinned,
                                    onToggleCollapse = { onToggleGroupCollapsed(groupKey) },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            val isGroupRefreshing = pingsState is PingsState.Loading &&
                                    pingsState.pendingLocationIds.any { it in groupIds }

                            AutoPickButton(
                                isRunning = autoPickRunning,
                                onClick = { onAutoPickClick(groupIds) },
                                tint = MaterialTheme.colorScheme.primary
                            )
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
                                onToggleAutoUpdate = if (!isFree) {
                                    { groupSubUrl?.let { onSetSubscriptionAutoUpdate(it, !groupAutoUpdate) } }
                                } else null,
                                onRefreshSubscription = groupSubUrl?.let { url -> { onRefreshSubscription(url) } },
                                currentName = group.firstOrNull()?.metadata?.subscription?.displayName().orEmpty(),
                                onRename = groupSubUrl?.takeIf { !isFree }?.let { url -> { name -> onRenameSubscription(url, name) } },
                                onMoveToFolder = { onRequestMoveToFolder(listOf(CustomGroup.subMember(groupKey))) },
                                onDelete = { onDeleteSubscription(groupIds) },
                                subscriptionPageUrl = groupWebPageUrl
                            )
                        }
                    }
                }
            }
        } else {
            val orderedGroup = if (isPingSorted) {
                group.sortedWith(pingComparator(pingsState, isPingDescending))
            } else {
                group
            }

            // Expanded subscription is wrapped in a single container (secondaryContainer),
            // giving it a continuous container background holding the header and all server cards together.
            item(key = "group-$groupKey") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = if (isFree) {
                        androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.secondaryContainer, androidx.compose.ui.graphics.Color(0xFF43A047), 0.22f)
                    } else MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = if (isFree) {
                                androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surfaceContainer, androidx.compose.ui.graphics.Color(0xFF43A047), 0.15f)
                            } else MaterialTheme.colorScheme.surfaceContainer
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
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onToggleGroupCollapsed(groupKey) },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.ExpandLess,
                                            contentDescription = "Collapse",
                                            modifier = Modifier.size(22.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        SubscriptionGroupHeader(
                                            locations = group,
                                            pingsState = pingsState,
                                            isPinned = isPinned,
                                            onToggleCollapse = { onToggleGroupCollapsed(groupKey) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }

                                    val isGroupRefreshing = pingsState is PingsState.Loading &&
                                            pingsState.pendingLocationIds.any { it in groupIds }

                                    AutoPickButton(
                                        isRunning = autoPickRunning,
                                        onClick = { onAutoPickClick(groupIds) },
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    RefreshButton(
                                        isRefreshing = isGroupRefreshing,
                                        onClick = { onRefreshClick(groupIds) },
                                        tint = MaterialTheme.colorScheme.primary
                                    )

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
                                        onToggleAutoUpdate = if (!isFree) {
                                            { groupSubUrl?.let { onSetSubscriptionAutoUpdate(it, !groupAutoUpdate) } }
                                        } else null,
                                        onRefreshSubscription = groupSubUrl?.let { url -> { onRefreshSubscription(url) } },
                                        currentName = group.firstOrNull()?.metadata?.subscription?.displayName().orEmpty(),
                                        onRename = groupSubUrl?.takeIf { !isFree }?.let { url -> { name -> onRenameSubscription(url, name) } },
                                        onMoveToFolder = { onRequestMoveToFolder(listOf(CustomGroup.subMember(groupKey))) },
                                        onDelete = { onDeleteSubscription(groupIds) },
                                        subscriptionPageUrl = groupWebPageUrl
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                val subLoc = group.firstOrNull { it.metadata?.subscription?.announce?.isNotBlank() == true } ?: group.firstOrNull()
                                TrafficProgressBar(location = subLoc)
                            }
                        }

                        LocationCardsColumn(orderedGroup, twoColumns) { location, cellModifier ->
                            LocationSelectorRow(
                                location = location,
                                selectedLocationId = selectedLocationId,
                                pingsState = pingsState,
                                onLocationSelected = onLocationSelected,
                                onLocationSettingsClick = onLocationSettingsClick,
                                selectionMode = selectionMode,
                                isChecked = location.storageId in selectedIds,
                                onToggleSelect = onToggleSelect,
                                onStartSelection = onStartSelection,
                                twoColumns = twoColumns,
                                modifier = cellModifier
                            )
                        }
                    }
                }
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
                            autoPickRunning = autoPickRunning,
                            onToggleCollapsed = { onToggleFolderCollapsed(folder.id) },
                            onRefresh = { onRefreshClick(memberIds) },
                            onAutoPick = { onAutoPickClick(memberIds) },
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
                                val mSubUrl = mGroup.firstOrNull()?.subscriptionUrl
                                val mIsFree = mSubUrl?.trim() == org.olcbox.app.ui.features.home.FREE_SERVERS_URL
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
                                                    onToggleCollapse = { onToggleGroupCollapsed(mKey) },
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                            val mRefreshing = pingsState is PingsState.Loading &&
                                                    pingsState.pendingLocationIds.any { it in mIds }
                                            AutoPickButton(
                                                isRunning = autoPickRunning,
                                                onClick = { onAutoPickClick(mIds) },
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            RefreshButton(
                                                isRefreshing = mRefreshing,
                                                onClick = { onRefreshClick(mIds) },
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            val mAutoUpdate = mGroup.none { it.metadata?.subscription?.autoUpdateEnabled == false }
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
                                                onToggleAutoUpdate = if (!mIsFree) {
                                                    { mSubUrl?.let { onSetSubscriptionAutoUpdate(it, !mAutoUpdate) } }
                                                } else null,
                                                onRefreshSubscription = mSubUrl?.let { url -> { onRefreshSubscription(url) } },
                                                currentName = mGroup.firstOrNull()?.metadata?.subscription?.displayName().orEmpty(),
                                                onRename = mSubUrl?.takeIf { !mIsFree }?.let { url -> { name -> onRenameSubscription(url, name) } },
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
                                            LocationCardsColumn(ordered, twoColumns) { location, cellModifier ->
                                                LocationSelectorRow(
                                                    location = location,
                                                    selectedLocationId = selectedLocationId,
                                                    pingsState = pingsState,
                                                    onLocationSelected = onLocationSelected,
                                                    onLocationSettingsClick = onLocationSettingsClick,
                                                    selectionMode = selectionMode,
                                                    isChecked = location.storageId in selectedIds,
                                                    onToggleSelect = onToggleSelect,
                                                    onStartSelection = onStartSelection,
                                                    twoColumns = twoColumns,
                                                    modifier = cellModifier
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Member custom locations: rows directly on the folder fill. Pinned ones
                            // float to the top of the folder (in pin order) — same rule as the
                            // top-level "own locations" section. Without this the in-folder pin toggle
                            // flipped the icon but never reordered the item ("закрепление внутри группы").
                            val orderedMemberCustom = memberCustom.sortedBy { loc ->
                                val pinIndex = pinnedCustomLocations.indexOf(loc.storageId)
                                if (pinIndex >= 0) pinIndex else Int.MAX_VALUE
                            }
                            LocationCardsColumn(orderedMemberCustom, twoColumns) { location, cellModifier ->
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
                                    onStartSelection = onStartSelection,
                                    twoColumns = twoColumns,
                                    modifier = cellModifier
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

                AutoPickButton(
                    isRunning = autoPickRunning,
                    onClick = { onAutoPickClick(customIds) },
                    tint = MaterialTheme.colorScheme.primary
                )
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

        locationCards(orderedCustom, twoColumns, keyPrefix = "custom-row") { location, cellModifier ->
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
                onStartSelection = onStartSelection,
                twoColumns = twoColumns,
                modifier = cellModifier
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

    // Remnawave/Happ `support-url` header: icon button right of the bar, hidden when the panel gives none.
    val supportUrl = subscription.supportUrl?.takeIf { it.isNotBlank() }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val s = org.olcbox.app.ui.i18n.LocalStrings.current

    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
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
        if (supportUrl != null) {
            IconButton(
                onClick = { runCatching { uriHandler.openUri(supportUrl) } },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = if (isTelegramLink(supportUrl)) TelegramIcon else Icons.Outlined.Public,
                    contentDescription = s.subscriptionSupport,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// Decoded icons by URL + disk stamp, so a refreshed subscription (new file) re-decodes while a plain
// recomposition never re-reads the disk copy.
private val subscriptionIconCache = mutableMapOf<String, androidx.compose.ui.graphics.painter.Painter?>()

@Composable
private fun SubscriptionIcon(url: String) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val stamp = org.olcbox.app.data.datasource.SubscriptionIconDisk.stamp(url)
    val targetPx = (20 * density.density).toInt().coerceAtLeast(1)
    val key = "$url#$stamp#$targetPx"
    val painter by androidx.compose.runtime.produceState(subscriptionIconCache[key], key) {
        if (!subscriptionIconCache.containsKey(key)) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                runCatching {
                    // Disk copy (written on subscription refresh); download only if it is missing.
                    val bytes = org.olcbox.app.data.datasource.SubscriptionIconDisk.read(url) ?: run {
                        val client = org.olcbox.app.data.datasource.createProxyHttpClient()
                        try {
                            org.olcbox.app.data.datasource.downloadSubscriptionIcon(client, url)
                        } finally {
                            client.close()
                        }
                    }?.also { org.olcbox.app.data.datasource.SubscriptionIconDisk.write(url, it) }
                    if (bytes == null || bytes.isEmpty()) return@runCatching null
                    if (bytes.decodeToString(endIndex = minOf(bytes.size, 512)).contains("<svg")) {
                        org.olcbox.app.data.datasource.decodeSvgPainter(bytes, density) // vector: sharp at any size
                    } else {
                        // Shrunk once to the on-screen size: drawing a 4000px source every frame froze the UI.
                        androidx.compose.ui.graphics.painter.BitmapPainter(
                            bytes.decodeToImageBitmap().downscaledTo(targetPx),
                            filterQuality = androidx.compose.ui.graphics.FilterQuality.High
                        )
                    }
                }.getOrNull()
            }
            subscriptionIconCache[key] = value
        }
    }
    painter?.let {
        androidx.compose.foundation.Image(
            painter = it,
            contentDescription = null,
            modifier = Modifier
                .padding(end = 6.dp)
                .size(20.dp)
                .clip(RoundedCornerShape(5.dp))
        )
    }
}

// Halving steps (each <= 2x, High quality) keep a huge source sharp instead of aliasing a one-shot shrink.
private fun androidx.compose.ui.graphics.ImageBitmap.downscaledTo(targetPx: Int): androidx.compose.ui.graphics.ImageBitmap {
    var cur = this
    while (maxOf(cur.width, cur.height) > targetPx) {
        val k = maxOf(0.5f, targetPx.toFloat() / maxOf(cur.width, cur.height))
        val w = (cur.width * k).toInt().coerceAtLeast(1)
        val h = (cur.height * k).toInt().coerceAtLeast(1)
        val out = androidx.compose.ui.graphics.ImageBitmap(w, h)
        androidx.compose.ui.graphics.Canvas(out).drawImageRect(
            image = cur,
            srcSize = androidx.compose.ui.unit.IntSize(cur.width, cur.height),
            dstSize = androidx.compose.ui.unit.IntSize(w, h),
            paint = androidx.compose.ui.graphics.Paint().apply {
                filterQuality = androidx.compose.ui.graphics.FilterQuality.High
            }
        )
        cur = out
    }
    return cur
}

private fun isTelegramLink(url: String): Boolean {
    val u = url.trim().lowercase()
    val host = u.substringAfter("://", "").substringBefore('/').substringBefore('?').removePrefix("www.")
    return u.startsWith("tg:") || host == "t.me" || host == "telegram.me"
}

// Telegram logo (Simple Icons, 24x24), drawn here because Material has no brand icons.
private val TelegramIcon: androidx.compose.ui.graphics.vector.ImageVector by lazy {
    androidx.compose.ui.graphics.vector.ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f
    ).addPath(
        pathData = androidx.compose.ui.graphics.vector.PathParser().parsePathString(
            "M11.944 0A12 12 0 0 0 0 12a12 12 0 0 0 12 12 12 12 0 0 0 12-12A12 12 0 0 0 12 0a12 12 0 0 0-.056 0zm4.962 7.224c.1-.002.321.023.465.14a.506.506 0 0 1 .171.325c.016.093.036.306.02.472-.18 1.898-.962 6.502-1.36 8.627-.168.9-.499 1.201-.82 1.23-.696.065-1.225-.46-1.9-.902-1.056-.693-1.653-1.124-2.678-1.8-1.185-.78-.417-1.21.258-1.91.177-.184 3.247-2.977 3.307-3.23.007-.032.014-.15-.056-.212s-.174-.041-.249-.024c-.106.024-1.793 1.14-5.061 3.345-.48.33-.913.49-1.302.48-.428-.008-1.252-.241-1.865-.44-.752-.245-1.349-.374-1.297-.789.027-.216.325-.437.893-.663 3.498-1.524 5.83-2.529 6.998-3.014 3.332-1.386 4.025-1.627 4.476-1.635z"
        ).toNodes(),
        fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black)
    ).build()
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
    autoUpdateEnabled: Boolean = true,
    onTogglePin: () -> Unit,
    onTogglePingSort: () -> Unit,
    onToggleAutoUpdate: (() -> Unit)? = null,
    // Non-null only when this group is backed by a subscription URL we can re-download.
    onRefreshSubscription: (() -> Unit)? = null,
    // Current visible name (prefills the dialog) and the rename action; null hides the menu entry.
    currentName: String = "",
    onRename: ((String) -> Unit)? = null,
    onMoveToFolder: () -> Unit,
    onDelete: () -> Unit,
    subscriptionPageUrl: String? = null
) {
    var expanded by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val s = org.olcbox.app.ui.i18n.LocalStrings.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    if (renaming && onRename != null) {
        var draft by remember { mutableStateOf(currentName) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(s.renameSubscription) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    supportingText = { Text(s.renameSubscriptionHint) }
                )
            },
            confirmButton = {
                TextButton(onClick = { onRename(draft); renaming = false }) { Text(s.save) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = false }) { Text(s.cancel) }
            }
        )
    }

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
            if (onRename != null) {
                DropdownMenuItem(
                    text = { Text(s.renameSubscription) },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    onClick = {
                        expanded = false
                        renaming = true
                    }
                )
            }
            // Re-download just this subscription now (distinct from the ping RefreshButton next to it).
            if (onRefreshSubscription != null) {
                DropdownMenuItem(
                    text = { Text(s.refreshThisSubscription) },
                    leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                    onClick = {
                        onRefreshSubscription()
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
            if (onToggleAutoUpdate != null) {
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
            }
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
    autoPickRunning: Boolean = false,
    onToggleCollapsed: () -> Unit,
    onRefresh: () -> Unit,
    onAutoPick: () -> Unit = {},
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

            AutoPickButton(
                isRunning = autoPickRunning,
                onClick = onAutoPick,
                tint = MaterialTheme.colorScheme.primary
            )
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
private fun SubscriptionAnnounceText(
    announce: String,
    onToggleCollapse: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    val primaryColor = MaterialTheme.colorScheme.primary

    val annotatedString = remember(announce, primaryColor) {
        buildAnnotatedString {
            val linkRegex = Regex("""(https?://[^\s]+|[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}|t\.me/[^\s]+|tg://[^\s]+|(?<![a-zA-Z0-9._%+-])@[A-Za-z0-9_]{3,32}(?!\.[a-zA-Z])|\b(?:[a-zA-Z0-9-]+\.)+(?:com|online|org|net|ru|io|me|dev|app|site|space|top|xyz|pro|su|info|biz)(?:/[^\s]*)?\b)""")
            var lastIndex = 0
            val matches = linkRegex.findAll(announce)
            for (match in matches) {
                val start = match.range.first
                val end = match.range.last + 1
                if (start > lastIndex) {
                    append(announce.substring(lastIndex, start))
                }
                var rawLink = match.value
                var trailingPunct = ""
                while (rawLink.isNotEmpty() && (rawLink.endsWith(".") || rawLink.endsWith(",") ||
                            rawLink.endsWith("!") || rawLink.endsWith("?") ||
                            rawLink.endsWith(")") || rawLink.endsWith("\"") || rawLink.endsWith("'"))) {
                    trailingPunct = rawLink.takeLast(1) + trailingPunct
                    rawLink = rawLink.dropLast(1)
                }
                val targetUrl = when {
                    rawLink.startsWith("http://") || rawLink.startsWith("https://") -> rawLink
                    rawLink.contains("@") && !rawLink.startsWith("@") -> "mailto:$rawLink"
                    rawLink.startsWith("t.me/") -> "https://$rawLink"
                    rawLink.startsWith("tg://") -> rawLink
                    rawLink.startsWith("@") -> "https://t.me/${rawLink.removePrefix("@")}"
                    else -> "https://$rawLink"
                }
                pushStringAnnotation(tag = "URL", annotation = targetUrl)
                pushStyle(
                    SpanStyle(
                        color = primaryColor,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = TextDecoration.Underline
                    )
                )
                append(rawLink)
                pop()
                pop()
                if (trailingPunct.isNotEmpty()) {
                    append(trailingPunct)
                }
                lastIndex = end
            }
            if (lastIndex < announce.length) {
                append(announce.substring(lastIndex))
            }
        }
    }

    ClickableText(
        text = annotatedString,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 11.sp,
            lineHeight = 14.sp,
            color = MaterialTheme.colorScheme.primary
        ),
        maxLines = org.olcbox.app.ui.features.locations.components.LocalSubscriptionDescriptionLines.current
            .let { if (it > 0) it else Int.MAX_VALUE },
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(top = 2.dp),
        onClick = { offset ->
            val clickedAnnotation = annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset).firstOrNull()
            if (clickedAnnotation != null) {
                runCatching { uriHandler.openUri(clickedAnnotation.item) }
            } else {
                onToggleCollapse?.invoke()
            }
        }
    )
}

@Composable
private fun SubscriptionGroupHeader(
    locations: List<LocationItem>,
    pingsState: PingsState,
    isPinned: Boolean = false,
    onToggleCollapse: (() -> Unit)? = null,
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
            // Panel-provided icon: the `profile-icon` header, else `<subscription page origin>/logo.png`
            // (where Remnawave pages usually keep it). Drawn only when enabled AND the image loads.
            if (org.olcbox.app.ui.features.locations.components.LocalShowSubscriptionIcons.current) {
                val sub = first?.metadata?.subscription
                val iconSrc = sub?.iconUrl?.takeIf { it.isNotBlank() }
                    ?: sub?.webPageUrl?.let { Regex("^https?://[^/?#]+").find(it.trim())?.value }?.plus("/logo.png")
                iconSrc?.let { SubscriptionIcon(it) }
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

        // The panel's own description for this subscription (Remnawave/Happ `announce` header),
        // right under the title like Happ. Gated on the app-settings toggle (off by default) and
        // independent of [info], so a sub that carries ONLY a description still shows it.
        if (org.olcbox.app.ui.features.locations.components.LocalShowSubscriptionDescription.current) {
            first?.metadata?.subscription?.announce?.takeIf { it.isNotBlank() }?.let { announceText ->
                SubscriptionAnnounceText(
                    announce = announceText,
                    onToggleCollapse = onToggleCollapse
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
    // Desktop: the popup follows the mouse pointer (hover in / out); touch: tap shows it for a few seconds.
    // It is a NON-focusable Popup: a focusable DropdownMenu grabs the pointer, the badge loses hover,
    // the menu closes, hover returns - visible flicker.
    val hoverSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    androidx.compose.runtime.LaunchedEffect(hovered) { showDetail = hovered }
    androidx.compose.runtime.LaunchedEffect(showDetail) {
        if (showDetail && !hovered) {
            kotlinx.coroutines.delay(3_000)
            showDetail = false
        }
    }

    Box {
        Icon(
            imageVector = Icons.Filled.Error,
            contentDescription = s.subscriptionExpiringSoon,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .hoverable(hoverSource)
                .clickable { showDetail = !showDetail }
        )
        if (showDetail) {
            val gapPx = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.roundToPx() }
            androidx.compose.ui.window.Popup(
                // Fully BELOW the badge with a gap: any overlap makes the popup steal the hover -> flicker.
                popupPositionProvider = remember(gapPx) {
                    object : androidx.compose.ui.window.PopupPositionProvider {
                        override fun calculatePosition(
                            anchorBounds: androidx.compose.ui.unit.IntRect,
                            windowSize: androidx.compose.ui.unit.IntSize,
                            layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                            popupContentSize: androidx.compose.ui.unit.IntSize
                        ) = androidx.compose.ui.unit.IntOffset(
                            anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0),
                            anchorBounds.bottom + gapPx
                        )
                    }
                },
                properties = androidx.compose.ui.window.PopupProperties(focusable = false)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shadowElevation = 4.dp
                ) {
                    Text(
                        text = s.subscriptionExpiryFull(dateTime, daysLeft),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * Lays out a list of location cards into the hosting [LazyListScope]: one lazy item per card in
 * single-column mode, or one lazy item per PAIR (a Row of two half-width cells) in [twoColumns] mode.
 * [keyPrefix] keeps the Compose item keys stable/unique across sections.
 */
private fun LazyListScope.locationCards(
    items: List<LocationItem>,
    twoColumns: Boolean,
    keyPrefix: String,
    cell: @Composable (LocationItem, Modifier) -> Unit
) {
    if (twoColumns) {
        val pairs = items.chunked(2)
        items(
            items = pairs,
            key = { pair -> "$keyPrefix-pair-${pair.first().storageId}" }
        ) { pair ->
            // height(IntrinsicSize.Max) + fillMaxHeight on the cells makes both tiles in the pair grow
            // to the taller one's height, so paired tiles are always the same size ("плитки одинакового
            // размера"). The lone-odd cell keeps its natural height (its Spacer partner has no content).
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    cell(pair[0], Modifier.fillMaxWidth().fillMaxHeight())
                }
                if (pair.size > 1) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        cell(pair[1], Modifier.fillMaxWidth().fillMaxHeight())
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    } else {
        items(
            items = items,
            key = { "$keyPrefix-${it.storageId}" }
        ) { location -> cell(location, Modifier.fillMaxWidth()) }
    }
}

/** Non-lazy [locationCards] for cards that live inside a folder's [Column] (already a single item). */
@Composable
private fun LocationCardsColumn(
    items: List<LocationItem>,
    twoColumns: Boolean,
    cell: @Composable (LocationItem, Modifier) -> Unit
) {
    if (twoColumns) {
        items.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    cell(pair[0], Modifier.fillMaxWidth().fillMaxHeight())
                }
                if (pair.size > 1) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        cell(pair[1], Modifier.fillMaxWidth().fillMaxHeight())
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    } else {
        items.forEach { location -> cell(location, Modifier.fillMaxWidth()) }
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
    onStartSelection: (String) -> Unit = {},
    twoColumns: Boolean = false,
    modifier: Modifier = Modifier
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

    if (twoColumns) {
        org.olcbox.app.ui.features.locations.components.LocationGridCell(
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
            onTogglePinned = onTogglePinned,
            modifier = modifier
        )
    } else {
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

/** What the user sees as the subscription's name: their own rename, else the panel's. */
private fun SubscriptionMetadata.displayName(): String? =
    customName?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() }

private fun LocationItem.subscriptionTitle(): String {
    val subscription = metadata?.subscription

    return listOfNotNull(
        subscription?.icon?.takeIf { it.isNotBlank() },
        subscription?.displayName()
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
