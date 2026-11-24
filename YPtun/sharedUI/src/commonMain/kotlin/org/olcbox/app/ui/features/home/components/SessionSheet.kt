package org.olcbox.app.ui.features.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.data.model.WarpMode
import org.olcbox.app.data.model.WarpProtocol
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.data.session.SessionInfo
import org.olcbox.app.data.session.SpeedPhase
import org.olcbox.app.data.session.SpeedProgress
import org.olcbox.app.data.session.fetchHostGeoInfo
import org.olcbox.app.data.session.fetchSessionInfo
import org.olcbox.app.data.session.runSpeedTest
import org.olcbox.app.ui.components.SettingsCaption
import org.olcbox.app.ui.components.SettingsCard
import org.olcbox.app.ui.components.SettingsCardDivider
import org.olcbox.app.ui.components.SettingsNote
import org.olcbox.app.ui.i18n.LocalStrings
import org.olcbox.app.ui.i18n.Strings
import kotlin.math.roundToInt

/**
 * «Сессия»: where this connection exits to (address, provider, place) and how fast it is. Both go
 * through the live tunnel via [proxy], and nothing is requested until the sheet is opened.
 */
@Composable
fun SessionSheet(
    proxy: () -> SubscriptionFetchProxy?,
    activeLocation: () -> LocationConfig? = { null },
    warpIngress: Pair<String, Int>? = null,
    onDismiss: () -> Unit
) {
    val s = LocalStrings.current
    var info by remember { mutableStateOf<SessionInfo?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableStateOf(0) }
    LaunchedEffect(refreshTrigger) {
        loading = true
        failed = false
        runCatching { fetchSessionInfo(proxy()) }
            .onSuccess { info = it; failed = false }
            .onFailure { if (it is CancellationException) throw it; failed = true }
        loading = false
    }

    var speed by remember { mutableStateOf(SpeedProgress()) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val running = speed.phase == SpeedPhase.Ping || speed.phase == SpeedPhase.Download || speed.phase == SpeedPhase.Upload

    var showIngress by remember { mutableStateOf(false) }
    val locationConfig = remember(activeLocation) { activeLocation() }
    val (ingressHost, ingressPort) = warpIngress?.let { (host, port) -> host to port.toString() }
        ?: extractIngressHostAndPort(locationConfig)
    var ingressGeo by remember(ingressHost) { mutableStateOf<SessionInfo?>(null) }
    var ingressGeoLoading by remember(ingressHost) { mutableStateOf(false) }

    LaunchedEffect(showIngress, ingressHost) {
        if (showIngress && ingressGeo == null && !ingressHost.isNullOrBlank()) {
            ingressGeoLoading = true
            runCatching { fetchHostGeoInfo(ingressHost, proxy()) }
                .onSuccess { ingressGeo = it }
                .onFailure { if (it is CancellationException) throw it }
            ingressGeoLoading = false
        }
    }

    HomeSettingsSheet(
        title = s.sessionTitle,
        subtitle = s.sessionSubtitleExit,
        onDismiss = onDismiss
    ) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
        else Spacer(Modifier.height(10.dp))
        SettingsCard {
            val countryVal = info?.country?.let { c ->
                val flag = org.olcbox.app.util.countryCodeToEmojiFlag(c)
                if (flag != null) "$flag $c" else c
            }
            val rows = listOf(
                "IPv4" to info?.ipv4, "IPv6" to info?.ipv6, "ASN" to info?.asn,
                s.sessionProvider to info?.provider, s.sessionCountry to countryVal,
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
        if (failed && !loading) {
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = { refreshTrigger++ },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(s.refresh, fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            OutlinedButton(
                onClick = { showIngress = !showIngress },
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                Icon(
                    imageVector = if (showIngress) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (showIngress) s.sessionHideIngress else s.sessionShowIngress,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        if (showIngress) {
            Spacer(Modifier.height(8.dp))
            SettingsCaption(s.sessionIngressTitle)
            if (ingressGeoLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
            }
            SettingsCard {
                val ingressProtocol = resolveIngressProtocol(locationConfig)
                val ingressTransport = if (warpIngress != null) "UDP" else resolveIngressTransport(locationConfig)
                val warpModeStr = resolveWarpModeString(locationConfig, s)

                val ingressRows = mutableListOf<Pair<String, String?>>()
                if (!ingressHost.isNullOrBlank()) {
                    ingressRows.add(s.sessionIngressServer to ingressHost)
                }
                if (!ingressPort.isNullOrBlank()) {
                    ingressRows.add(s.sessionIngressPort to ingressPort)
                }
                ingressRows.add(s.sessionIngressProtocol to ingressProtocol)
                if (!ingressTransport.isNullOrBlank()) {
                    ingressRows.add(s.sessionIngressTransport to ingressTransport)
                }
                if (!warpModeStr.isNullOrBlank()) {
                    ingressRows.add(s.sessionIngressWarp to warpModeStr)
                }
                val isHostIpv4 = ingressHost != null && ":" !in ingressHost && ingressHost.count { it == '.' } == 3
                val isHostIpv6 = ingressHost != null && ":" in ingressHost
                val ipv4Val = ingressGeo?.ipv4 ?: if (isHostIpv4) ingressHost else null
                val ipv6Val = ingressGeo?.ipv6 ?: if (isHostIpv6) ingressHost else null

                ingressRows.add("IPv4" to ipv4Val)
                ingressRows.add("IPv6" to ipv6Val)
                ingressRows.add("ASN" to ingressGeo?.asn)
                ingressRows.add(s.sessionProvider to ingressGeo?.provider)
                val ingressCountryVal = ingressGeo?.country?.let { c ->
                    val flag = org.olcbox.app.util.countryCodeToEmojiFlag(c)
                    if (flag != null) "$flag $c" else c
                }
                ingressRows.add(s.sessionCountry to ingressCountryVal)
                ingressRows.add(s.sessionCity to ingressGeo?.city)
                ingressRows.add(s.sessionRegion to ingressGeo?.region)
                ingressRows.add(s.sessionLatitude to ingressGeo?.latitude)
                ingressRows.add(s.sessionLongitude to ingressGeo?.longitude)
                ingressRows.forEachIndexed { i, (label, value) ->
                    if (i > 0) SettingsCardDivider()
                    SessionRow(label, value)
                }
            }
            SettingsNote(s.sessionIngressNote)
        }

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

private fun extractIngressHostAndPort(config: LocationConfig?): Pair<String?, String?> {
    if (config == null) return null to null
    val p = config.proxy
    if (p != null) {
        if (p.server.isNotBlank()) {
            return p.server to (p.serverPort.takeIf { it > 0 }?.toString() ?: "")
        }
        if (p.awgConfig.isNotBlank()) {
            val endpointLine = p.awgConfig.lineSequence()
                .firstOrNull { it.trim().startsWith("Endpoint", ignoreCase = true) }
            if (endpointLine != null) {
                val value = endpointLine.substringAfter('=').trim()
                val host = value.substringBeforeLast(':').trim().trim('[', ']')
                val port = value.substringAfterLast(':', "").trim()
                return host to port
            }
        }
        if (!p.rawOutbound.isNullOrBlank()) {
            runCatching {
                val obj = Json.parseToJsonElement(p.rawOutbound).jsonObject
                val server = obj["server"]?.jsonPrimitive?.contentOrNull
                val port = obj["server_port"]?.jsonPrimitive?.contentOrNull
                if (!server.isNullOrBlank()) return server to (port ?: "")
            }
        }
    }
    if (config.engine == EngineType.Stealth) {
        return config.bypassProvider to "443"
    }
    if (config.engine == EngineType.VkTurn) {
        return "VK Calls" to "443"
    }
    if (config.engine == EngineType.MasterDns) {
        return (config.masterDns?.domainList()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "DNS") to "53"
    }
    return null to null
}

private fun resolveIngressProtocol(config: LocationConfig?): String {
    if (config == null) return "—"
    return when (config.engine) {
        EngineType.Stealth -> "olcRTC (${config.bypassProvider})"
        EngineType.Chain -> "olcRTC + ${(config.proxy?.type?.uppercase() ?: "PROXY")}"
        EngineType.VkTurn -> "VK-TURN (WebRTC)"
        EngineType.MasterDns -> "MasterDNS (DNS Tunnel)"
        EngineType.OpenFlux -> "OpenFlux"
        EngineType.Snolc -> "SNOLC"
        EngineType.Zapret -> "Zapret (DPI)"
        EngineType.Standard -> {
            val p = config.proxy
            val baseType = when (p?.type?.lowercase()) {
                ProxyProfile.TYPE_AMNEZIAWG -> "AmneziaWG"
                "wireguard" -> "WireGuard"
                ProxyProfile.TYPE_HYSTERIA2 -> "Hysteria 2"
                ProxyProfile.TYPE_MIERU -> "Mieru"
                ProxyProfile.TYPE_TRUSTTUNNEL -> "TrustTunnel"
                ProxyProfile.TYPE_NAIVE -> "NaïveProxy"
                ProxyProfile.TYPE_SHADOWSOCKS -> "Shadowsocks"
                ProxyProfile.TYPE_TROJAN -> "Trojan"
                ProxyProfile.TYPE_VMESS -> "VMess"
                ProxyProfile.TYPE_VLESS -> "VLESS"
                else -> p?.type?.uppercase()?.takeIf { it.isNotBlank() } ?: "Standard"
            }
            if (config.warpEnabled) {
                val proto = if (config.warpProtocol == WarpProtocol.Awg) "AWG" else "WG"
                if (config.warpMode == WarpMode.Client) {
                    "WARP ($proto) → $baseType"
                } else {
                    "$baseType → WARP ($proto)"
                }
            } else {
                baseType
            }
        }
    }
}

private fun resolveIngressTransport(config: LocationConfig?): String? {
    if (config == null) return null
    return when (config.engine) {
        EngineType.Stealth, EngineType.Chain -> config.transport
        EngineType.Standard -> {
            val p = config.proxy ?: return null
            val net = p.network.uppercase()
            val sec = when (p.security.lowercase()) {
                "reality" -> "Reality"
                "tls" -> "TLS"
                else -> null
            }
            when {
                sec != null -> "$net ($sec)"
                p.type == ProxyProfile.TYPE_AMNEZIAWG || p.type == "wireguard" -> "UDP"
                else -> net.takeIf { it.isNotBlank() }
            }
        }
        else -> null
    }
}

private fun resolveWarpModeString(config: LocationConfig?, s: Strings): String? {
    if (config == null || !config.warpEnabled) return null
    val proto = if (config.warpProtocol == WarpProtocol.Awg) "AmneziaWG" else "WireGuard"
    val mode = if (config.warpMode == WarpMode.Client) s.warpModeClient else s.warpModeServer
    return "$mode ($proto)"
}

@Composable
private fun SessionRow(label: String, value: String?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
        Spacer(Modifier.width(16.dp))
        val textValue = value ?: "—"
        val (emoji, rest) = org.olcbox.app.util.parseEmojiAndName(textValue)
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (emoji.isNotBlank()) {
                Text(
                    text = emoji,
                    fontFamily = org.olcbox.app.ui.theme.emojiFontFamily(),
                    fontSize = 16.sp
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = if (emoji.isNotBlank()) rest else textValue,
                color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
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
