package org.olcbox.app.ui.features.locations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.olcbox.app.data.model.OpenFluxCarrier
import org.olcbox.app.data.model.OpenFluxConfig
import org.olcbox.app.ui.features.locations.components.SshAuthFields
import org.olcbox.app.vpn.openflux.OpenFluxInstallOptions
import org.olcbox.app.vpn.openflux.rememberOpenFluxServerInstaller

private val CUPS_ROOM_PRESETS = listOf(1, 4, 8, 12, 16)

/**
 * One-tap OpenFlux exit-node installer ([rememberOpenFluxServerInstaller]) over SSH.
 * Supports single-transport multi-rooms (multiple links separated by newlines/commas via
 * OpenFlux's striped group, or auto-created `cups.online` N rooms whose packed room list is
 * written back into the location) and multi-transport sessions (`--transports` + key file).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OpenFluxInstallDialog(
    config: OpenFluxConfig,
    onApplyConfig: ((OpenFluxConfig) -> OpenFluxConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val installer = rememberOpenFluxServerInstaller()
    val scope = rememberCoroutineScope()

    var ip by remember { mutableStateOf("") }
    var sshPort by remember { mutableStateOf("22") }
    var login by remember { mutableStateOf("root") }
    var password by remember { mutableStateOf("") }
    var useKey by remember { mutableStateOf(false) }
    var sshKey by remember { mutableStateOf("") }
    var keyPassphrase by remember { mutableStateOf("") }

    var docUrlsText by remember {
        mutableStateOf(OpenFluxConfig.splitCoordinates(config.docUrl).joinToString("\n"))
    }
    var cupsRoomsText by remember {
        mutableStateOf(OpenFluxInstallOptions.DEFAULT_CUPS_ROOMS.toString())
    }
    var exitToken by remember { mutableStateOf("") }

    // Per-carrier editable coordinates when config.isSession() is active.
    val carrierTexts = remember(config.carriers) {
        mutableStateMapOf<String, String>().apply {
            config.carriers.forEach { c ->
                this[c.type] = if (c.type == OpenFluxConfig.TRANSPORT_DIRECT) {
                    c.dial.substringAfterLast(':', "")
                        .takeIf { it.toIntOrNull() in 1..65535 }
                        ?: OpenFluxInstallOptions.DEFAULT_DIRECT_PORT.toString()
                } else {
                    OpenFluxConfig.splitCoordinates(c.url).joinToString("\n")
                }
            }
        }
    }

    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<String>?>(null) }
    val log = remember { mutableStateListOf<String>() }
    val logScroll = rememberScrollState()

    val isSession = config.isSession()
    val usesMax = config.usesMax()
    val usesCups = if (isSession) {
        config.carriers.any { it.type == OpenFluxConfig.TRANSPORT_CUPS }
    } else {
        config.transport == OpenFluxConfig.TRANSPORT_CUPS
    }
    val hasAvito = config.usesCarrier(OpenFluxConfig.TRANSPORT_AVITO)
    val cupsRooms = cupsRoomsText.toIntOrNull()?.coerceIn(
        OpenFluxInstallOptions.MIN_CUPS_ROOMS,
        OpenFluxInstallOptions.MAX_CUPS_ROOMS
    )
    val cupsRoomCountValid = cupsRoomsText.isNotBlank() &&
        cupsRoomsText.toIntOrNull() in OpenFluxInstallOptions.MIN_CUPS_ROOMS..OpenFluxInstallOptions.MAX_CUPS_ROOMS

    val resolvedCarriers: List<OpenFluxCarrier> = remember(isSession, config.carriers, carrierTexts.toMap(), ip) {
        if (!isSession) {
            emptyList()
        } else {
            config.carriers.map { c ->
                val raw = carrierTexts[c.type].orEmpty()
                if (c.type == OpenFluxConfig.TRANSPORT_DIRECT) {
                    val port = raw.filter(Char::isDigit).toIntOrNull()
                        ?.takeIf { it in 1..65535 } ?: OpenFluxInstallOptions.DEFAULT_DIRECT_PORT
                    c.copy(dial = "${ip.trim().ifBlank { "0.0.0.0" }}:$port")
                } else {
                    c.copy(url = OpenFluxConfig.normalizeCoordinateList(raw))
                }
            }
        }
    }

    val carrierReady = when {
        hasAvito -> false
        isSession -> resolvedCarriers.isNotEmpty() && resolvedCarriers.all { c ->
            when (c.type) {
                OpenFluxConfig.TRANSPORT_DIRECT -> {
                    val portText = carrierTexts[c.type].orEmpty()
                    portText.toIntOrNull() in 1..65535
                }
                OpenFluxConfig.TRANSPORT_CUPS -> {
                    val raw = carrierTexts[c.type].orEmpty()
                    (raw.isBlank() && cupsRoomCountValid) || OpenFluxConfig.isValidCoordinate(c.type, raw)
                }
                else -> OpenFluxConfig.isValidCoordinate(c.type, carrierTexts[c.type].orEmpty())
            }
        }
        usesMax -> exitToken.isNotBlank()
        config.transport == OpenFluxConfig.TRANSPORT_CUPS ->
            (docUrlsText.isBlank() && cupsRoomCountValid) ||
                OpenFluxConfig.isValidCoordinate(OpenFluxConfig.TRANSPORT_CUPS, docUrlsText)
        else -> OpenFluxConfig.isValidCoordinate(config.transport, docUrlsText)
    }

    val succeeded = result?.isSuccess == true
    val hasSshAccess = ip.isNotBlank() && (if (useKey) sshKey.isNotBlank() else password.isNotBlank())

    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) logScroll.scrollTo(logScroll.maxValue)
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("Автоустановка выходной ноды OpenFlux") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Подключусь к VPS по SSH, загружу OpenFlux и запущу выходную ноду службой systemd " +
                        "(${config.summary()}).",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it.trim() },
                    label = { Text("IP/хост VPS") },
                    singleLine = true,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = login,
                        onValueChange = { login = it.trim() },
                        label = { Text("Логин SSH") },
                        singleLine = true,
                        enabled = !running,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = sshPort,
                        onValueChange = { v -> sshPort = v.filter(Char::isDigit) },
                        label = { Text("Порт") },
                        singleLine = true,
                        enabled = !running,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(96.dp)
                    )
                }
                SshAuthFields(
                    useKey = useKey,
                    onUseKeyChange = { useKey = it },
                    password = password,
                    onPasswordChange = { password = it },
                    privateKey = sshKey,
                    onPrivateKeyChange = { sshKey = it },
                    passphrase = keyPassphrase,
                    onPassphraseChange = { keyPassphrase = it },
                    enabled = !running,
                )
                HorizontalDivider()
                if (isSession) {
                    config.carriers.forEach { c ->
                        val label = OpenFluxConfig.carrierLabel(c.type)
                        if (c.type == OpenFluxConfig.TRANSPORT_DIRECT) {
                            OutlinedTextField(
                                value = carrierTexts[c.type].orEmpty(),
                                onValueChange = { v -> carrierTexts[c.type] = v.filter(Char::isDigit).take(5) },
                                label = { Text("$label — порт VPS") },
                                placeholder = { Text(OpenFluxInstallOptions.DEFAULT_DIRECT_PORT.toString()) },
                                singleLine = true,
                                enabled = !running,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            OutlinedTextField(
                                value = carrierTexts[c.type].orEmpty(),
                                onValueChange = { v -> carrierTexts[c.type] = v },
                                label = {
                                    Text(
                                        if (c.type == OpenFluxConfig.TRANSPORT_CUPS) {
                                            "$label — комнаты (пусто = создать)"
                                        } else {
                                            "$label — ссылки (по одной в строке)"
                                        }
                                    )
                                },
                                enabled = !running,
                                minLines = 1,
                                maxLines = 4,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    if (config.secret.isBlank()) {
                        Text(
                            "Ключ шифрования (AES-256-GCM) сгенерируется автоматически и подставится в локацию.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (usesMax) {
                    OutlinedTextField(
                        value = exitToken,
                        onValueChange = { exitToken = it.trim() },
                        label = { Text("Токен MAX выходной ноды") },
                        supportingText = { Text("Отдельный аккаунт, которому звонит клиент. Хранится только на VPS.") },
                        singleLine = true,
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = docUrlsText,
                        onValueChange = { docUrlsText = it },
                        label = {
                            Text(
                                when (config.transport) {
                                    OpenFluxConfig.TRANSPORT_MAILRU -> "Ссылки Mail.ru (одна или несколько)"
                                    OpenFluxConfig.TRANSPORT_CUPS -> "Комнаты cups.online (пусто = создать на VPS)"
                                    OpenFluxConfig.TRANSPORT_TELEMOST -> "Ссылки Телемоста (одна или несколько)"
                                    else -> "Ссылки на Яндекс Документ (одна или несколько)"
                                }
                            )
                        },
                        placeholder = {
                            Text(
                                when (config.transport) {
                                    OpenFluxConfig.TRANSPORT_MAILRU -> "https://cloud.mail.ru/public/…\nhttps://cloud.mail.ru/public/…"
                                    OpenFluxConfig.TRANSPORT_CUPS -> "Оставьте пустым для автосоздания\nили вставьте готовый список / ссылки"
                                    OpenFluxConfig.TRANSPORT_TELEMOST -> "https://telemost.yandex.ru/j/…\nhttps://telemost.yandex.ru/j/…"
                                    else -> "https://docs.yandex.ru/…\nhttps://docs.yandex.ru/…"
                                }
                            )
                        },
                        supportingText = {
                            Text(
                                if (config.transport == OpenFluxConfig.TRANSPORT_CUPS) {
                                    "Оставьте поле пустым — нода сама создаст выбранное число комнат на interview.cups.online и подставит их в локацию."
                                } else {
                                    "Можно указать несколько ссылок по одной в строке — нода и клиент объединят их в мультикомнаты для скорости."
                                }
                            )
                        },
                        enabled = !running,
                        minLines = 2,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (usesCups) {
                    val showCupsCount = if (isSession) {
                        carrierTexts[OpenFluxConfig.TRANSPORT_CUPS].isNullOrBlank()
                    } else {
                        docUrlsText.isBlank()
                    }
                    if (showCupsCount) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Количество комнат cups.online (мультикомнаты)",
                                style = MaterialTheme.typography.labelMedium
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                CUPS_ROOM_PRESETS.forEach { count ->
                                    FilterChip(
                                        selected = cupsRoomsText == count.toString(),
                                        onClick = { cupsRoomsText = count.toString() },
                                        enabled = !running,
                                        label = { Text("$count") }
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = cupsRoomsText,
                                onValueChange = { v -> cupsRoomsText = v.filter(Char::isDigit).take(2) },
                                label = { Text("Своё число комнат (${OpenFluxInstallOptions.MIN_CUPS_ROOMS}–${OpenFluxInstallOptions.MAX_CUPS_ROOMS})") },
                                singleLine = true,
                                enabled = !running,
                                isError = !cupsRoomCountValid,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                InstallLogView(log, logScroll)
                result?.exceptionOrNull()?.let { err ->
                    Text(
                        err.message ?: "Ошибка установки",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (succeeded) {
                    Text(
                        result?.getOrNull().orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            if (succeeded) {
                TextButton(onClick = onDismiss) { Text("Готово") }
            } else {
                TextButton(
                    enabled = !running && hasSshAccess && carrierReady,
                    onClick = {
                        running = true
                        result = null
                        log.clear()
                        val normalizedDocUrl = OpenFluxConfig.normalizeCoordinateList(docUrlsText)
                        if (!isSession && !usesMax && normalizedDocUrl.isNotBlank()) {
                            onApplyConfig { it.copy(docUrl = normalizedDocUrl) }
                        }
                        scope.launch {
                            val res = installer.install(
                                OpenFluxInstallOptions(
                                    host = ip.trim(),
                                    sshPort = sshPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22,
                                    login = login.ifBlank { "root" },
                                    sshPassword = if (useKey) "" else password,
                                    sshKey = if (useKey) sshKey else "",
                                    sshKeyPassphrase = if (useKey) keyPassphrase else "",
                                    transport = config.transport,
                                    docUrl = normalizedDocUrl,
                                    exitMaxToken = exitToken.trim(),
                                    cupsRoomsCount = cupsRooms ?: OpenFluxInstallOptions.DEFAULT_CUPS_ROOMS,
                                    carriers = resolvedCarriers,
                                    secret = config.secret.trim(),
                                    context = config.context.trim(),
                                    negotiate = config.negotiate,
                                )
                            ) { line -> log.add(line) }
                            res.getOrNull()?.let { ok ->
                                if (isSession) {
                                    onApplyConfig {
                                        it.copy(
                                            carriers = ok.carriers.ifEmpty { it.carriers },
                                            secret = ok.secret.ifBlank { it.secret },
                                        )
                                    }
                                } else if (!usesMax) {
                                    if (ok.docUrl.isNotBlank()) {
                                        docUrlsText = OpenFluxConfig.splitCoordinates(ok.docUrl).joinToString("\n")
                                    }
                                    onApplyConfig {
                                        it.copy(
                                            docUrl = ok.docUrl.ifBlank { it.docUrl },
                                            secret = ok.secret.ifBlank { it.secret },
                                        )
                                    }
                                }
                            }
                            res.exceptionOrNull()?.let { log.add("ОШИБКА: ${it.message}") }
                            result = res.map { it.status }
                            running = false
                        }
                    }
                ) {
                    if (running) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (running) "Установка…" else "Установить")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !running) {
                Text(if (succeeded) "Закрыть" else "Отмена")
            }
        }
    )
}
