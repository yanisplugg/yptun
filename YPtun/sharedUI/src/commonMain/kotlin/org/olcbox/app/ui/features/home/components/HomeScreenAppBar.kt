package org.olcbox.app.ui.features.home.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreenAppBar(
    onHistoryClick: () -> Unit = {},
    showAppSettingsButton: Boolean = false,
    onAppSettingsClick: () -> Unit = {},
    showSplitTunnelingButton: Boolean = false,
    onSplitTunnelingClick: () -> Unit = {},
    onAddClick: () -> Unit = {},
    showOverflowMenu: Boolean = false,
    onDeleteUnreachable: () -> Unit = {},
    onDeleteDuplicates: () -> Unit = {},
    onDeleteAllSubscriptions: () -> Unit = {},
    onDeleteAllConfigs: () -> Unit = {},
    // Settings that change the home screen itself live in this menu, next to the list they affect.
    onListViewClick: (() -> Unit)? = null,
    onPingSettingsClick: (() -> Unit)? = null,
    onCreateGroupClick: (() -> Unit)? = null,
    // Desktop only: a way out of the app from the main window (the tray menu can be unavailable).
    onExitClick: (() -> Unit)? = null
) {
    var overflowExpanded by remember { mutableStateOf(false) }
    CenterAlignedTopAppBar(
        title = {
            Text(
                text = "YPtun",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        navigationIcon = {
            if (showAppSettingsButton) {
                IconButton(onClick = onAppSettingsClick) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Application settings",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            } else {
                IconButton(onClick = onHistoryClick) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = "History",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        actions = {
            if (showSplitTunnelingButton) {
                IconButton(onClick = onSplitTunnelingClick) {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = "Split tunneling",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            IconButton(onClick = onAddClick) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = "Add configuration",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            if (onExitClick != null) {
                IconButton(onClick = onExitClick) {
                    Icon(
                        imageVector = Icons.Outlined.PowerSettingsNew,
                        contentDescription = org.olcbox.app.ui.i18n.LocalStrings.current.exitApp,
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            val hasViewItems = onListViewClick != null || onPingSettingsClick != null || onCreateGroupClick != null
            if (showOverflowMenu || hasViewItems) {
                IconButton(onClick = { overflowExpanded = true }) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "More",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                DropdownMenu(
                    expanded = overflowExpanded,
                    onDismissRequest = { overflowExpanded = false }
                ) {
                    val s = org.olcbox.app.ui.i18n.LocalStrings.current
                    onListViewClick?.let { open ->
                        DropdownMenuItem(
                            text = { Text(s.listViewTitle) },
                            leadingIcon = { Icon(Icons.Outlined.GridView, contentDescription = null) },
                            onClick = {
                                overflowExpanded = false
                                open()
                            }
                        )
                    }
                    onPingSettingsClick?.let { open ->
                        DropdownMenuItem(
                            text = { Text(s.pingSettingsTitle) },
                            leadingIcon = { Icon(Icons.Outlined.Bolt, contentDescription = null) },
                            onClick = {
                                overflowExpanded = false
                                open()
                            }
                        )
                    }
                    onCreateGroupClick?.let { create ->
                        DropdownMenuItem(
                            text = { Text(s.createFolder) },
                            leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                            onClick = {
                                overflowExpanded = false
                                create()
                            }
                        )
                    }
                    if (showOverflowMenu) {
                        if (hasViewItems) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Text(
                                text = s.menuCleanupSection.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(org.olcbox.app.ui.i18n.LocalStrings.current.menuDeleteUnreachable) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                            onClick = {
                                overflowExpanded = false
                                onDeleteUnreachable()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(org.olcbox.app.ui.i18n.LocalStrings.current.menuDeleteDuplicates) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                            onClick = {
                                overflowExpanded = false
                                onDeleteDuplicates()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(org.olcbox.app.ui.i18n.LocalStrings.current.menuDeleteAllSubscriptions) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                            onClick = {
                                overflowExpanded = false
                                onDeleteAllSubscriptions()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(org.olcbox.app.ui.i18n.LocalStrings.current.menuDeleteAllConfigs) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                            onClick = {
                                overflowExpanded = false
                                onDeleteAllConfigs()
                            }
                        )
                    }
                }
            }
        }
    )
}
