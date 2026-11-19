package org.olcbox.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.Color
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
    val waveTransition = rememberInfiniteTransition(label = "waveGlow")
    val waveOffset1 by waveTransition.animateFloat(
        initialValue = -3.5f,
        targetValue = 3.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "waveOffset1"
    )
    val waveOffset2 by waveTransition.animateFloat(
        initialValue = 2.5f,
        targetValue = -2.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "waveOffset2"
    )
    val waveAlpha by waveTransition.animateFloat(
        initialValue = 0.20f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "waveAlpha"
    )
    val primaryColor = MaterialTheme.colorScheme.primary

    // A round ring with the round action button in its middle; the ring takes a tint of the accent
    // while connected. When the wave effect is on, the glow gently bobs up and down like floating micro-waves.
    Box(
        modifier = modifier
            .size(132.dp)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (showWaveGlow) {
            // Soft secondary halo floating with offset phase
            Box(
                modifier = Modifier
                    .size(136.dp)
                    .offset(y = waveOffset2.dp)
                    .clip(CircleShape)
                    .background(primaryColor.copy(alpha = waveAlpha * 0.40f))
            )
        }

        // Primary outer ring (default clean ring, floating smoothly up and down when wave effect is on)
        Box(
            modifier = Modifier
                .size(132.dp)
                .offset(y = if (showWaveGlow) waveOffset1.dp else 0.dp)
                .clip(CircleShape)
                .background(ringColor)
        )

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
