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

/** Fixed "success green" for the ping tick — theme-independent so it's always clearly green. */
private val PingOkGreen = Color(0xFF22C55E)

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
            Text(text = emoji, fontSize = 20.sp, fontFamily = org.olcbox.app.ui.theme.emojiFontFamily())
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = cleanName,
                color = textColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Visible,
                softWrap = true
            )

            Text(
                text = locationSubtitle(location),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Visible,
                softWrap = true
            )
        }
        
        // VK-TURN has no meaningful latency probe (traffic is bonded over VK calls),
        // so show a neutral dash instead of a ms value or a false "Offline".
        val isVkTurn = location.config?.engine == org.olcbox.app.data.model.EngineType.VkTurn
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