package org.olcbox.app.ui.features.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.data.model.AppBehaviorSettings
import org.olcbox.app.ui.components.SettingsCaption
import org.olcbox.app.ui.components.SettingsCard
import org.olcbox.app.ui.components.SettingsCardDivider
import org.olcbox.app.ui.components.SettingsCardToggle
import org.olcbox.app.ui.components.SettingsChoiceChips
import org.olcbox.app.ui.i18n.LocalStrings

/*
 * Settings that change what the home screen shows live next to it (the ⋮ menu), not three screens away
 * in the app settings: how the list looks and how servers are pinged.
 */

/** Bottom sheet with a title and a scrolling column of cards. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeSettingsSheet(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            content()
        }
    }
}

/** «Вид списка»: the buttons around Connect, how servers and subscriptions are drawn, delete confirmation. */
@Composable
fun ListViewSheet(
    settings: AppBehaviorSettings,
    onChanged: (AppBehaviorSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val s = LocalStrings.current
    HomeSettingsSheet(title = s.listViewTitle, subtitle = s.listViewSubtitle, onDismiss = onDismiss) {
        SettingsCaption(s.homeButtonsSection)
        SettingsCard {
            SettingsCardToggle(s.showAutoButtonTitle, s.showAutoButtonSubtitle, settings.showAutoButton) {
                onChanged(settings.copy(showAutoButton = it))
            }
            SettingsCardDivider()
            SettingsCardToggle(s.showGroupAutoPickTitle, s.showGroupAutoPickSubtitle, settings.showGroupAutoPick) {
                onChanged(settings.copy(showGroupAutoPick = it))
            }
            SettingsCardDivider()
            SettingsCardToggle(s.speedOnHomeTitle, s.speedOnHomeSubtitle, settings.showSpeedOnHome) {
                onChanged(settings.copy(showSpeedOnHome = it))
            }
        }

        SettingsCaption(s.homeListSection)
        SettingsCard {
            SettingsCardToggle(s.twoColumnLayoutTitle, s.twoColumnLayoutSubtitle, settings.twoColumnLayout) {
                onChanged(settings.copy(twoColumnLayout = it))
            }
            SettingsCardDivider()
            SettingsCardToggle(
                s.hideEndpointWhenDescriptionTitle,
                s.hideEndpointWhenDescriptionSubtitle,
                settings.hideEndpointWhenDescription
            ) { onChanged(settings.copy(hideEndpointWhenDescription = it)) }
        }

        SettingsCaption(s.homeSubscriptionsSection)
        SettingsCard {
            SettingsCardToggle(s.showSubscriptionIconsTitle, s.showSubscriptionIconsSubtitle, settings.showSubscriptionIcons) {
                onChanged(settings.copy(showSubscriptionIcons = it))
            }
            SettingsCardDivider()
            SettingsCardToggle(s.showSubscriptionExpiryTitle, s.showSubscriptionExpirySubtitle, settings.showSubscriptionExpiry) {
                onChanged(settings.copy(showSubscriptionExpiry = it))
            }
            SettingsCardDivider()
            SettingsCardToggle(
                s.showSubscriptionDescriptionTitle,
                s.showSubscriptionDescriptionSubtitle,
                settings.showSubscriptionDescription
            ) { onChanged(settings.copy(showSubscriptionDescription = it)) }
            if (settings.showSubscriptionDescription) {
                Text(
                    text = s.subscriptionDescriptionLinesTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                SettingsChoiceChips(
                    options = listOf(3 to "3", 5 to "5", 0 to s.subscriptionDescriptionLinesUnlimited),
                    selected = settings.subscriptionDescriptionLines,
                    inCard = true
                ) { onChanged(settings.copy(subscriptionDescriptionLines = it)) }
            }
        }

        SettingsCaption(s.deletionSection)
        SettingsCard {
            SettingsCardToggle(s.confirmDeleteTitle, s.confirmDeleteSubtitle, settings.confirmBeforeDelete) {
                onChanged(settings.copy(confirmBeforeDelete = it))
            }
        }
    }
}

/** «Настройки пинга»: how locations are probed and how the result is shown. */
@Composable
fun PingSettingsSheet(
    settings: AppBehaviorSettings,
    onChanged: (AppBehaviorSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val s = LocalStrings.current
    val isProxyMode = settings.pingMode == AppBehaviorSettings.PING_PROXY_GET ||
        settings.pingMode == AppBehaviorSettings.PING_PROXY_HEAD
    HomeSettingsSheet(title = s.pingSettingsTitle, onDismiss = onDismiss) {
        SettingsCaption(s.pingMethod)
        SettingsChoiceChips(
            options = listOf(
                AppBehaviorSettings.PING_AUTO to s.pingModeAuto,
                AppBehaviorSettings.PING_TCP to s.pingModeTcp,
                AppBehaviorSettings.PING_ICMP to s.pingModeIcmp,
                AppBehaviorSettings.PING_PROXY_GET to s.pingModeProxyGet,
                AppBehaviorSettings.PING_PROXY_HEAD to s.pingModeProxyHead,
            ),
            selected = settings.pingMode
        ) { onChanged(settings.copy(pingMode = it)) }
        // The target URL means something only for the proxy GET/HEAD probes; TCP/ICMP probe the
        // location's own server, so the field is disabled for those modes.
        OutlinedTextField(
            value = settings.pingUrl,
            onValueChange = { onChanged(settings.copy(pingUrl = it)) },
            label = { Text(s.pingTarget) },
            placeholder = { Text(s.pingTargetHint) },
            singleLine = true,
            enabled = isProxyMode,
            modifier = Modifier.fillMaxWidth()
        )

        SettingsCaption(s.pingResultLabel)
        SettingsChoiceChips(
            options = listOf(
                AppBehaviorSettings.PING_RESULT_TIME to s.pingResultTime,
                AppBehaviorSettings.PING_RESULT_ICON to s.pingResultIcon,
                AppBehaviorSettings.PING_RESULT_BOTH to s.pingResultBoth,
            ),
            selected = settings.pingResultDisplay
        ) { onChanged(settings.copy(pingResultDisplay = it)) }
        SettingsCard {
            SettingsCardToggle(s.showAliveCountTitle, s.showAliveCountSubtitle, settings.showSubscriptionAliveCount) {
                onChanged(settings.copy(showSubscriptionAliveCount = it))
            }
            SettingsCardDivider()
            SettingsCardToggle(s.savePingResultsTitle, s.savePingResultsSubtitle, settings.savePingResults) {
                onChanged(settings.copy(savePingResults = it))
            }
            SettingsCardDivider()
            SettingsCardToggle(s.showPingControlsTitle, s.showPingControlsSubtitle, settings.showPingControls) {
                onChanged(settings.copy(showPingControls = it))
            }
        }

        SettingsCaption(s.pingSpeedSection)
        SettingsCard {
            PingParallelismRow(
                value = settings.effectivePingParallelism(),
                onChange = { onChanged(settings.copy(pingParallelism = it)) }
            )
            SettingsCardDivider()
            PingTimeoutRow(
                seconds = settings.pingTimeoutSec.coerceIn(
                    AppBehaviorSettings.MIN_PING_TIMEOUT_SEC,
                    AppBehaviorSettings.MAX_PING_TIMEOUT_SEC
                ),
                onChange = { onChanged(settings.copy(pingTimeoutSec = it)) }
            )
        }

        SettingsCaption(s.pingPrecisionTitle)
        Text(
            text = s.pingPrecisionSubtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SettingsChoiceChips(
            options = listOf(1 to s.pingPrecisionSingle, 2 to s.pingPrecisionDouble, 3 to s.pingPrecisionTriple),
            selected = settings.effectivePingPrecision()
        ) { onChanged(settings.copy(pingPrecision = it)) }
    }
}

/** Slider for the ping timeout. */
@Composable
private fun PingTimeoutRow(seconds: Int, onChange: (Int) -> Unit) {
    val s = LocalStrings.current
    val min = AppBehaviorSettings.MIN_PING_TIMEOUT_SEC
    val max = AppBehaviorSettings.MAX_PING_TIMEOUT_SEC
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(s.pingTimeoutTitle, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(s.pingTimeoutSubtitle, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = s.secondsShort(seconds),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = seconds.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(min, max)) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = max - min - 1
        )
    }
}

/** Stepper for how many locations are pinged at once. */
@Composable
private fun PingParallelismRow(value: Int, onChange: (Int) -> Unit) {
    val s = LocalStrings.current
    val min = AppBehaviorSettings.MIN_PING_PARALLELISM
    val max = AppBehaviorSettings.MAX_PING_PARALLELISM
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(s.pingThreadsTitle, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(s.pingThreadsSubtitle, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(
            onClick = { onChange((value - 1).coerceIn(min, max)) },
            enabled = value > min,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(40.dp)
        ) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(40.dp)
        )
        OutlinedButton(
            onClick = { onChange((value + 1).coerceIn(min, max)) },
            enabled = value < max,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(40.dp)
        ) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}
