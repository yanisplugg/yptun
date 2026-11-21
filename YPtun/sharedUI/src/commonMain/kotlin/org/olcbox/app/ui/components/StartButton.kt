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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    val waveOffset1 by if (showWaveGlow) {
        waveTransition.animateFloat(
            initialValue = -6.5f,
            targetValue = 6.5f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "waveOffset1"
        )
    } else {
        remember { mutableStateOf(0f) }
    }
    val waveOffset2 by if (showWaveGlow) {
        waveTransition.animateFloat(
            initialValue = 5.0f,
            targetValue = -5.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1300, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "waveOffset2"
        )
    } else {
        remember { mutableStateOf(0f) }
    }
    val waveAlpha by if (showWaveGlow) {
        waveTransition.animateFloat(
            initialValue = 0.28f,
            targetValue = 0.60f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "waveAlpha"
        )
    } else {
        remember { mutableStateOf(0f) }
    }
    val waveGlowSize by if (showWaveGlow) {
        waveTransition.animateFloat(
            initialValue = 140f,
            targetValue = 148f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "waveGlowSize"
        )
    } else {
        remember { mutableStateOf(132f) }
    }
    val primaryColor = MaterialTheme.colorScheme.primary

    // A round ring with the round action button in its middle; the ring takes a tint of the accent
    // while connected. When the wave effect is on, the glow visibly undulates and floats up and down like living waves.
    Box(
        modifier = modifier.size(132.dp),
        contentAlignment = Alignment.Center
    ) {
        if (showWaveGlow) {
            // Outer luminous blue water glow / aura (expanding & floating like gentle waves)
            Box(
                modifier = Modifier
                    .size(waveGlowSize.dp)
                    .offset(y = waveOffset2.dp)
                    .clip(CircleShape)
                    .background(primaryColor.copy(alpha = waveAlpha * 0.45f))
            )

            // Inner vibrant wave halo
            Box(
                modifier = Modifier
                    .size(138.dp)
                    .offset(y = (waveOffset1 * 0.7f).dp)
                    .clip(CircleShape)
                    .background(primaryColor.copy(alpha = waveAlpha * 0.75f))
            )
        }

        // Primary outer ring (default clean ring, floating smoothly up and down when wave effect is on).
        // Bounded to CircleShape before clickable so touch ripple / hold indication is circular instead of a square box.
        Box(
            modifier = Modifier
                .size(132.dp)
                .offset(y = if (showWaveGlow) waveOffset1.dp else 0.dp)
                .clip(CircleShape)
                .background(ringColor)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
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
}
