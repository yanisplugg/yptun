package org.olcbox.app.ui.features.locations.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.data.model.AppBehaviorSettings
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.util.parseEmojiAndName

/**
 * How a ping result is rendered in the location list — one of [AppBehaviorSettings.PING_RESULT_MODES].
 * Provided once near the app root (Android: from the persisted app-behavior settings). Defaults to
 * the classic ms display so iOS/desktop keep their existing look until they wire it up too.
 */
val LocalPingResultDisplay = staticCompositionLocalOf { AppBehaviorSettings.PING_RESULT_TIME }

/**
 * Whether the subscription group header shows the expiry date ("до дд.мм.гггг") under the
 * last-refresh line. Provided near the app root from the persisted app-behavior toggle; off by
 * default so other platforms keep their look until they wire it up.
 */
val LocalShowSubscriptionExpiry = staticCompositionLocalOf { false }

/**
 * Whether subscription headers show a "live/total" badge (servers that answered the last ping pass).
 * Provided near the app root from the persisted app-behavior toggle; off by default.
 */
val LocalShowSubscriptionAliveCount = staticCompositionLocalOf { false }

/**
 * When true, a location row that HAS a description hides its protocol/IP "endpoint" subtitle (showing
 * the description in its place). Rows without a description always show the endpoint. On by default.
 */
val LocalHideEndpointWhenDescription = staticCompositionLocalOf { true }

/** A live throughput sample (bytes per second) for the active connection. */
data class SpeedSample(val downBytesPerSec: Long, val upBytesPerSec: Long)

/**
 * Live down/up throughput of the active connection, or null when not connected OR the "speed on
 * home" toggle is off. When non-null it's rendered as a compact one-line readout under the SELECTED
 * location's subtitle. Provided near the app root; null by default so other platforms keep their look.
 */
val LocalConnectedSpeed = staticCompositionLocalOf<SpeedSample?> { null }

/** Fixed "success green" for the ping tick — theme-independent so it's always clearly green. */
private val PingOkGreen = Color(0xFF22C55E)

/** Download / upload accent colours for the Home speed line (match the notification's green/blue). */
private val SpeedDownGreen = Color(0xFF22C55E)
private val SpeedUpBlue = Color(0xFF3B82F6)

/**
 * Formats a bytes/sec rate compactly (B/s, KB/s, MB/s) using integer math so it stays
 * multiplatform-safe (no String.format / locale).
 */
private fun formatSpeedRate(bytesPerSec: Long): String {
    val b = bytesPerSec.coerceAtLeast(0L)
    return when {
        b >= 1024L * 1024L -> {
            val tenths = b * 10L / (1024L * 1024L)
            "${tenths / 10}.${tenths % 10} MB/s"
        }
        b >= 1024L -> "${b / 1024L} KB/s"
        else -> "$b B/s"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LocationRow(
    location: LocationItem,
    isSelected: Boolean,
    isLoading: Boolean,
    pingMs: Int?,
    isError: Boolean = false,
    settingsEnabled: Boolean = true,
    selectionMode: Boolean = false,
    isChecked: Boolean = false,
    onSettingsClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    isPinned: Boolean = false,
    // When non-null, a pin toggle is shown (used for "own" custom locations).
    onTogglePinned: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        label = "locationRowContainer"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        label = "locationRowBorder"
    )
    val borderWidth = if (isSelected) 2.dp else 1.dp
    val textColor = MaterialTheme.colorScheme.onSurface

    val metadata = location.metadata
    val rawName = metadata?.name?.takeIf { it.isNotBlank() } ?: location.fullName
    val fallbackIcon = metadata?.icon?.takeIf { it.isNotBlank() }
        ?: metadata?.subscription?.icon?.takeIf { it.isNotBlank() }
        ?: ""
    val (emoji, parsedName) = parseEmojiAndName(rawName, fallbackIcon)
    val cleanName = parsedName.ifBlank { location.config?.displayName().orEmpty() }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .border(borderWidth, borderColor, RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    when {
                        onLongClick != null -> onLongClick()
                        settingsEnabled -> onSettingsClick()
                    }
                }
            )
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        if (emoji.isNotEmpty()) {
            Text(text = emoji, fontSize = 20.sp)
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = cleanName,
                color = textColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                // Show the FULL config name: no line cap, wrap onto as many lines as needed. The row
                // is heightIn(min = 76.dp) and grows with its content, so a long name reflows tidily
                // inside the weight(1f) column instead of overflowing (TextOverflow.Visible) and
                // colliding with the row below.
                overflow = TextOverflow.Clip,
                softWrap = true
            )

            // Subscription-supplied description (Happ `meta.serverDescription`), shown right under the
            // name and ABOVE the protocol/IP subtitle. Display-only; absent when the source has none.
            val description = location.config?.description?.takeIf { it.isNotBlank() }
            description?.let { desc ->
                Text(
                    text = desc,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = true
                )
            }

            // Protocol/IP "endpoint" subtitle — hidden when this location has a description and the
            // "hide endpoint when description" setting is on (default), so the description stands in for it.
            if (description == null || !LocalHideEndpointWhenDescription.current) {
                Text(
                    text = locationSubtitle(location),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = true
                )
            }

            // Live throughput line — only under the SELECTED row, and only when the user enabled
            // "speed on home" AND a connection is up (provider supplies null otherwise). A single
            // compact horizontal line so it slots in without growing the row.
            val speed = LocalConnectedSpeed.current
            if (isSelected && speed != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "↓ ${formatSpeedRate(speed.downBytesPerSec)}",
                        color = SpeedDownGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Clip
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "↑ ${formatSpeedRate(speed.upBytesPerSec)}",
                        color = SpeedUpBlue,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Clip
                    )
                }
            }
        }
        
        // VK-TURN has no meaningful latency probe (traffic is bonded over VK calls),
        // so show a neutral dash instead of a ms value or a false "Offline".
        val isVkTurn = location.config?.engine == org.olcbox.app.data.model.EngineType.VkTurn
        // dnstt (DNS tunnel) can't be probed directly either: a NULL ping = "not measurable", not
        // "offline". Show "—" instead of a red fail when there's no ms; a live end-to-end RTT still shows.
        val isDnstt = location.config?.engine == org.olcbox.app.data.model.EngineType.Dnstt
        // "Значок" mode shows a check/cross instead of the raw latency (per user setting).
        val iconResult = LocalPingResultDisplay.current == AppBehaviorSettings.PING_RESULT_ICON

        when {
            isVkTurn -> {
                Text(
                    text = "—",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            isLoading -> {
                ShimmeringPingSkeleton()
            }

            pingMs != null -> {
                if (iconResult) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = org.olcbox.app.ui.i18n.LocalStrings.current.pingOnline,
                        tint = PingOkGreen,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Text(
                        text = "$pingMs ms",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            isDnstt -> {
                Text(
                    text = "—",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            isError -> {
                if (iconResult) {
                    Icon(
                        imageVector = Icons.Rounded.Cancel,
                        contentDescription = org.olcbox.app.ui.i18n.LocalStrings.current.pingOffline,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Text(
                        text = org.olcbox.app.ui.i18n.LocalStrings.current.pingOffline,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        if (selectionMode) {
            // In multi-select mode the trailing controls collapse to a single checkbox.
            Checkbox(checked = isChecked, onCheckedChange = { onClick() })
            return@Row
        }

        if (onTogglePinned != null) {
            IconButton(
                onClick = onTogglePinned,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = "Pin",
                    tint = if (isPinned) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (settingsEnabled) {
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        LocationSelectionIndicator(isSelected = isSelected)
    }
}

private fun locationSubtitle(location: LocationItem): String {
    val config = location.config
    val metadata = location.metadata

    val engineParts = when (config?.engine) {
        EngineType.Standard -> listOfNotNull(
            protocolLabel(config.proxy?.type),
            config.proxy?.server
        )

        EngineType.Chain -> listOfNotNull(
            "Chain",
            protocolLabel(config.proxy?.type),
            config.proxy?.server
        )

        EngineType.VkTurn -> listOfNotNull(
            "VK-TURN",
            config.proxy?.server
        )

        EngineType.Dnstt -> listOfNotNull(
            "DNSTT",
            config.dnstt?.domain?.takeIf { it.isNotBlank() }
        )

        else -> listOf(
            config?.providerName()
                ?: LocationConfig.providerDisplayName(LocationConfig.DEFAULT_BYPASS_PROVIDER),
            config?.transportName()
                ?: LocationConfig.transportDisplayName(LocationConfig.DEFAULT_TRANSPORT)
        )
    }

    return (engineParts + listOfNotNull(
        metadata?.comment?.takeIf { it.isNotBlank() },
        metadata?.ip?.takeIf { it.isNotBlank() }?.let { "IP $it" },
        quotaText(metadata?.used, metadata?.available)
    )).joinToString(" · ")
}

private fun protocolLabel(type: String?): String = when (type) {
    ProxyProfile.TYPE_VLESS -> "VLESS"
    ProxyProfile.TYPE_VMESS -> "VMess"
    ProxyProfile.TYPE_TROJAN -> "Trojan"
    ProxyProfile.TYPE_SHADOWSOCKS -> "Shadowsocks"
    null, "" -> "Proxy"
    else -> type.uppercase()
}

private fun quotaText(used: String?, available: String?): String? {
    return when {
        !used.isNullOrBlank() && !available.isNullOrBlank() -> "$used used · $available available"
        !used.isNullOrBlank() -> "$used used"
        !available.isNullOrBlank() -> "$available available"
        else -> null
    }
}

@Composable
private fun LocationSelectionIndicator(isSelected: Boolean) {
    if (isSelected) {
        Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = "Selected location",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
    } else {
        Surface(
            modifier = Modifier.size(24.dp),
            shape = CircleShape,
            color = Color.Transparent,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
        ) {
            Box(modifier = Modifier.size(24.dp))
        }
    }
}

/**
 * Compact half-width card used when the "2-column layout" setting is on. Same selection/ping/settings
 * behaviour as [LocationRow] but laid out vertically (name on top, ping + actions on the bottom) so two
 * fit side-by-side. [modifier] carries the grid cell sizing (fills its half of the Row).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LocationGridCell(
    location: LocationItem,
    isSelected: Boolean,
    isLoading: Boolean,
    pingMs: Int?,
    isError: Boolean = false,
    settingsEnabled: Boolean = true,
    selectionMode: Boolean = false,
    isChecked: Boolean = false,
    onSettingsClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    isPinned: Boolean = false,
    onTogglePinned: (() -> Unit)? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        label = "gridCellContainer"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        label = "gridCellBorder"
    )
    val borderWidth = if (isSelected) 2.dp else 1.dp
    val textColor = MaterialTheme.colorScheme.onSurface

    val metadata = location.metadata
    val rawName = metadata?.name?.takeIf { it.isNotBlank() } ?: location.fullName
    val fallbackIcon = metadata?.icon?.takeIf { it.isNotBlank() }
        ?: metadata?.subscription?.icon?.takeIf { it.isNotBlank() }
        ?: ""
    val (emoji, parsedName) = parseEmojiAndName(rawName, fallbackIcon)
    val cleanName = parsedName.ifBlank { location.config?.displayName().orEmpty() }

    Column(
        modifier = modifier
            .heightIn(min = 96.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .border(borderWidth, borderColor, RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    when {
                        onLongClick != null -> onLongClick()
                        settingsEnabled -> onSettingsClick()
                    }
                }
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Top) {
                if (emoji.isNotEmpty()) {
                    Text(text = emoji, fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = cleanName,
                    color = textColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    // Cap at 2 lines here (unlike the full-width row) so paired cells stay aligned.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = true,
                    modifier = Modifier.weight(1f)
                )
            }

            // Subscription description (Happ meta.serverDescription) shown right under the name — the
            // grid cell used to drop it entirely ("не видно описаний"). Falls back to the protocol/IP
            // endpoint when there's no description (same rule as the full row).
            val description = location.config?.description?.takeIf { it.isNotBlank() }
            description?.let { desc ->
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = desc,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = true
                )
            }
            if (description == null || !LocalHideEndpointWhenDescription.current) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = locationSubtitle(location),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = true
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            CompactPingIndicator(
                location = location,
                isLoading = isLoading,
                pingMs = pingMs,
                isError = isError
            )

            if (selectionMode) {
                Checkbox(checked = isChecked, onCheckedChange = { onClick() })
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onTogglePinned != null) {
                        IconButton(onClick = onTogglePinned, modifier = Modifier.size(30.dp)) {
                            Icon(
                                imageVector = if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                contentDescription = "Pin",
                                tint = if (isPinned) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    if (settingsEnabled) {
                        IconButton(onClick = onSettingsClick, modifier = Modifier.size(30.dp)) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    if (isSelected) {
                        Spacer(modifier = Modifier.width(2.dp))
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = "Selected location",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Compact ping readout for [LocationGridCell] — mirrors the full row's ping states in a smaller form. */
@Composable
private fun CompactPingIndicator(
    location: LocationItem,
    isLoading: Boolean,
    pingMs: Int?,
    isError: Boolean
) {
    val isVkTurn = location.config?.engine == EngineType.VkTurn
    // dnstt is an obfuscated DNS tunnel: it has no directly-probeable endpoint, so a NULL ping means
    // "not measurable" (disconnected), NOT "offline" — showing a red fail was wrong. Render "—" instead
    // when there's no ping; a real end-to-end RTT (measured through the live tunnel) still shows as ms.
    val isDnstt = location.config?.engine == EngineType.Dnstt
    val iconResult = LocalPingResultDisplay.current == AppBehaviorSettings.PING_RESULT_ICON
    when {
        isVkTurn -> Text(
            text = "—",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        isLoading -> ShimmeringPingSkeleton()

        pingMs != null -> if (iconResult) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = org.olcbox.app.ui.i18n.LocalStrings.current.pingOnline,
                tint = PingOkGreen,
                modifier = Modifier.size(18.dp)
            )
        } else {
            Text(
                text = "$pingMs ms",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        isDnstt -> Text(
            text = "—",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        isError -> if (iconResult) {
            Icon(
                imageVector = Icons.Rounded.Cancel,
                contentDescription = org.olcbox.app.ui.i18n.LocalStrings.current.pingOffline,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
        } else {
            Text(
                text = org.olcbox.app.ui.i18n.LocalStrings.current.pingOffline,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.error
            )
        }

        else -> Spacer(modifier = Modifier.size(1.dp))
    }
}

@Composable
private fun ShimmeringPingSkeleton() {
    val transition = rememberInfiniteTransition(label = "shimmer_transition")
    val translateAnim by transition.animateFloat(
        initialValue = -50f,
        targetValue = 150f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_anim"
    )

    val brush = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.05f),
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.05f),
        ),
        start = Offset(translateAnim, 0f),
        end = Offset(translateAnim + 50f, 50f)
    )

    Box(
        modifier = Modifier
            .width(42.dp)
            .height(18.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(brush)
    )
}