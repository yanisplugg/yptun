package org.olcbox.app.ui.features.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.data.session.SessionInfo
import org.olcbox.app.data.session.SpeedPhase
import org.olcbox.app.data.session.SpeedProgress
import org.olcbox.app.data.session.fetchSessionInfo
import org.olcbox.app.data.session.runSpeedTest
import org.olcbox.app.ui.components.SettingsCaption
import org.olcbox.app.ui.components.SettingsCard
import org.olcbox.app.ui.components.SettingsCardDivider
import org.olcbox.app.ui.components.SettingsNote
import org.olcbox.app.ui.i18n.LocalStrings
import kotlin.math.roundToInt

/**
 * «Сессия»: where this connection exits to (address, provider, place) and how fast it is. Both go
 * through the live tunnel via [proxy], and nothing is requested until the sheet is opened.
 */
@Composable
fun SessionSheet(proxy: () -> SubscriptionFetchProxy?, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    var info by remember { mutableStateOf<SessionInfo?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { fetchSessionInfo(proxy()) }
            .onSuccess { info = it }
            .onFailure { if (it is CancellationException) throw it; failed = true }
        loading = false
    }

    var speed by remember { mutableStateOf(SpeedProgress()) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val running = speed.phase == SpeedPhase.Ping || speed.phase == SpeedPhase.Download || speed.phase == SpeedPhase.Upload

    HomeSettingsSheet(title = s.sessionTitle, onDismiss = onDismiss) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
        else Spacer(Modifier.height(10.dp))
        SettingsCard {
            val rows = listOf(
                "IPv4" to info?.ipv4, "IPv6" to info?.ipv6, "ASN" to info?.asn,
                s.sessionProvider to info?.provider, s.sessionCountry to info?.country,
                s.sessionCity to info?.city, s.sessionRegion to info?.region,
                s.sessionLatitude to info?.latitude, s.sessionLongitude to info?.longitude
            )
            rows.forEachIndexed { i, (label, value) ->
                if (i > 0) SettingsCardDivider()
                SessionRow(label, value)
            }
        }
        SettingsNote(
            text = if (failed) s.sessionFailed else s.sessionNote,
            color = if (failed) MaterialTheme.colorScheme.error else Color.Unspecified
        )

        SettingsCaption(s.speedTestTitle)
        SettingsCard {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Metric(s.speedPing, speed.pingMs?.toString(), s.speedMs, speed.phase == SpeedPhase.Ping)
                    Metric(s.speedDownload, speed.downMbps?.format(), s.speedMbps, speed.phase == SpeedPhase.Download)
                    Metric(s.speedUpload, speed.upMbps?.format(), s.speedMbps, speed.phase == SpeedPhase.Upload)
                }
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (speed.phase == SpeedPhase.Failed) {
                    Text(s.speedTestFailed, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                Button(
                    onClick = {
                        job?.cancel()
                        job = scope.launch { runSpeedTest(proxy()) { speed = it } }
                    },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text(if (speed.phase == SpeedPhase.Done || speed.phase == SpeedPhase.Failed) s.speedTestAgain else s.speedTestTitle)
                }
            }
        }
        SettingsNote(s.speedTestSubtitle)
    }
}

@Composable
private fun SessionRow(label: String, value: String?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
        Spacer(Modifier.width(16.dp))
        Text(
            text = value ?: "—",
            color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun RowScope.Metric(label: String, value: String?, unit: String, active: Boolean) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Text(
            text = value ?: "—",
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        Text(unit, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

private fun Double.format(): String = if (this >= 100) roundToInt().toString() else ((this * 10).roundToInt() / 10.0).toString()
