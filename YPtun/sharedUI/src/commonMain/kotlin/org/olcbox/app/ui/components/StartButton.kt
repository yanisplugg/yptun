package org.olcbox.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.ui.theme.ThemeState

sealed class StartButtonState {
    object Idle : StartButtonState()
    object Loading : StartButtonState()
    object Success : StartButtonState()
}

@Composable
fun StartButton(
    modifier: Modifier = Modifier,
    isActive: Boolean,
    isLoading: Boolean,
    requiresSetup: Boolean = false,
    label: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val mainButtonColor by animateColorAsState(
        targetValue = when {
            isActive -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        label = "buttonColor"
    )

    val contentColor = when {
        isActive -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val ringColor by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        label = "ringColor"
    )

    val showWaveGlow = isActive && ThemeState.animatedConnectGlow
    val waveTransition = rememberInfiniteTransition(label = "waterRipple")
    val waveProgress by waveTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "waveProgress"
    )
    val primaryColor = MaterialTheme.colorScheme.primary

    // A round ring with the round action button in its middle; the ring takes a tint of the accent
    // while connected.
    Box(
        modifier = modifier
            .size(132.dp)
            .clip(CircleShape)
            .background(ringColor)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (showWaveGlow) {
            Canvas(modifier = Modifier.size(132.dp)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val innerRadius = 44.dp.toPx()
                val outerRadius = 66.dp.toPx()
                for (i in 0 until 3) {
                    val progress = (waveProgress + i * 0.333f) % 1f
                    val radius = innerRadius + (outerRadius - innerRadius) * progress
                    val alpha = (1f - progress) * 0.45f
                    drawCircle(
                        color = primaryColor.copy(alpha = alpha),
                        radius = radius,
                        center = center
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(mainButtonColor),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    color = contentColor,
                    strokeWidth = 3.dp
                )
            } else {
                Icon(
                    imageVector = when {
                        isActive -> Icons.Rounded.Pause
                        requiresSetup -> Icons.Rounded.Add
                        else -> Icons.Rounded.PowerSettingsNew
                    },
                    contentDescription = label ?: "Toggle",
                    tint = contentColor.copy(alpha = if (!enabled) 0.5f else 1f),
                    modifier = Modifier.size(44.dp)
                )
            }
        }
    }
}
