package org.olcbox.app.ui.features.locations.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Manual "ping all" action shown in a group header. Rendered as a static speedometer (gauge) icon —
 * no text label, no spinning — a single tap kicks off a ping pass. (The subscription re-download
 * lives elsewhere.)
 */
@Composable
fun RefreshButton(
    isRefreshing: Boolean,
    onClick: () -> Unit,
    tint: Color
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Speed,
            contentDescription = "Ping",
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
    }
}
