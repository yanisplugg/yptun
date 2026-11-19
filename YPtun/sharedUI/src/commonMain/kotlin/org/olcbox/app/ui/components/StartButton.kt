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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
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
    val waveTransition = rememberInfiniteTransition(label = "circularWaterWaves")
    val waveProgress by waveTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
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
                val midRadius = 55.dp.toPx()
                val ringThickness = 22.dp.toPx()
                val phase = waveProgress * 2f * PI.toFloat()

                // 1. Rotating water stream along the circle (counter-clockwise: right -> top -> left)
                rotate(degrees = -waveProgress * 360f, pivot = center) {
                    drawCircle(
                        brush = Brush.sweepGradient(
                            0.00f to primaryColor.copy(alpha = 0.20f),
                            0.16f to primaryColor.copy(alpha = 0.70f),
                            0.33f to primaryColor.copy(alpha = 0.25f),
                            0.50f to primaryColor.copy(alpha = 0.85f),
                            0.66f to primaryColor.copy(alpha = 0.30f),
                            0.83f to primaryColor.copy(alpha = 0.75f),
                            1.00f to primaryColor.copy(alpha = 0.20f),
                            center = center
                        ),
                        radius = midRadius,
                        style = Stroke(width = ringThickness)
                    )
                }

                // 2. Main wave crests undulating and swimming counter-clockwise (right -> left)
                val wave1Path = Path().apply {
                    val steps = 120
                    for (i in 0..steps) {
                        val theta = i * (2f * PI.toFloat() / steps)
                        val r = midRadius + 5.5.dp.toPx() * sin(3f * theta + phase)
                        val x = center.x + r * cos(theta)
                        val y = center.y + r * sin(theta)
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                drawPath(
                    path = wave1Path,
                    color = primaryColor.copy(alpha = 0.55f),
                    style = Stroke(width = 3.dp.toPx())
                )

                // 3. Shimmering water crest highlights (slightly offset for natural wave depth)
                val wave2Path = Path().apply {
                    val steps = 120
                    for (i in 0..steps) {
                        val theta = i * (2f * PI.toFloat() / steps)
                        val r = midRadius + 4.dp.toPx() * sin(3f * theta + phase + 1.2f)
                        val x = center.x + r * cos(theta)
                        val y = center.y + r * sin(theta)
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                drawPath(
                    path = wave2Path,
                    color = Color.White.copy(alpha = 0.40f),
                    style = Stroke(width = 1.8.dp.toPx())
                )

                // 4. Secondary outer wave ripples
                val wave3Path = Path().apply {
                    val steps = 120
                    for (i in 0..steps) {
                        val theta = i * (2f * PI.toFloat() / steps)
                        val r = midRadius + 3.5.dp.toPx() * sin(4f * theta + phase * 1.2f + 2.4f)
                        val x = center.x + r * cos(theta)
                        val y = center.y + r * sin(theta)
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                drawPath(
                    path = wave3Path,
                    color = primaryColor.copy(alpha = 0.35f),
                    style = Stroke(width = 2.dp.toPx())
                )
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
