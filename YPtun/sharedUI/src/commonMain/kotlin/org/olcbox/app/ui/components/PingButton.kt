package org.olcbox.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.ui.features.home.HomeScreenViewModel

sealed class PingState {
    object Idle : PingState()
    object Loading : PingState()
    data class Success(val latency: Long) : PingState()
    data class Error(val message: String) : PingState()
}

@Composable
fun PingButton(
    modifier: Modifier = Modifier,
    homeViewModel: HomeScreenViewModel,
    configGetter: () -> LocationConfig? = { null }
) {
    var pingState by remember { mutableStateOf<PingState>(PingState.Idle) }
    val s = org.olcbox.app.ui.i18n.LocalStrings.current

    // VK-TURN, MasterDNS, and Mieru have no off-tunnel latency probe — show a neutral status instead of false offline.
    val engine = configGetter()?.engine
    val isMieru = configGetter()?.proxy?.type == org.olcbox.app.data.model.ProxyProfile.TYPE_MIERU
    val isUnprobedOffTunnel = engine == org.olcbox.app.data.model.EngineType.VkTurn ||
        engine == org.olcbox.app.data.model.EngineType.MasterDns ||
        engine == org.olcbox.app.data.model.EngineType.OpenFlux ||
        engine == org.olcbox.app.data.model.EngineType.Snolc ||
        isMieru

    val descriptionText = when {
        pingState is PingState.Success -> "${s.notifConnected} ${(pingState as PingState.Success).latency}ms"
        pingState is PingState.Error && isUnprobedOffTunnel -> "?"
        pingState is PingState.Error -> s.pingOffline
        pingState is PingState.Loading -> s.pingChecking
        else -> s.pingVerify
    }

    val stateIcon: @Composable () -> Unit = {
        when (pingState) {
            is PingState.Error -> Icon(
                imageVector = if (isUnprobedOffTunnel) Icons.Outlined.Bolt else Icons.Rounded.PriorityHigh,
                contentDescription = null,
                tint = if (isUnprobedOffTunnel) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp)
            )

            is PingState.Loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp
                )
            }

            is PingState.Success -> Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(24.dp)
            )

            else -> Icon(
                imageVector = Icons.Outlined.Bolt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable(enabled = pingState !is PingState.Loading) {
                homeViewModel.viewModelScope.launch {
                    pingState = PingState.Loading
                    val config = configGetter()
                    val result = if (config != null) {
                        homeViewModel.performPingFor(config)
                    } else {
                        homeViewModel.performPing()
                    }

                    pingState = if (result != null) {
                        PingState.Success(result)
                    } else {
                        PingState.Error("Offline")
                    }
                }
            },
        shape = RoundedCornerShape(24.dp),
        color = if (pingState is PingState.Error && !isUnprobedOffTunnel) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = s.connectivityCheck,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = descriptionText,
                    fontSize = 13.sp,
                    color = if (pingState is PingState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    stateIcon()
                }
            }
        }
    }
}
