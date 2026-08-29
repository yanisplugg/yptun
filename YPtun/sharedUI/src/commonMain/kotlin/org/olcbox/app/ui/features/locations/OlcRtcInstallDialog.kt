package org.olcbox.app.ui.features.locations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.ui.features.locations.components.SshAuthFields
import org.olcbox.app.vpn.olcrtc.OLCRTC_MAX_ROOMS
import org.olcbox.app.vpn.olcrtc.OLCRTC_PROVIDERS
import org.olcbox.app.vpn.olcrtc.OLCRTC_TRANSPORTS
import org.olcbox.app.vpn.olcrtc.OlcRtcInstallOptions
import org.olcbox.app.vpn.olcrtc.generateJitsiRooms
import org.olcbox.app.vpn.olcrtc.rememberOlcRtcServerInstaller

/**
 * Одноклик-установка olcRTC-сервера на VPS: тот же сценарий, что у freeturn, но у olcRTC нет ни
 * адреса, ни порта — сервер сам заходит в комнату. Поэтому «установить» здесь значит поднять по
 * процессу на комнату, а в локацию вернуть комнаты и общий ключ шифрования.
 *
 * Комнаты: у jitsi комната создаётся самим фактом захода, поэтому имена генерируются кнопкой из
 * адреса инстанса. У telemost/wbstream ссылку выдаёт сам сервис, её вставляют руками — по одной в
 * строке, сколько комнат, столько и процессов.
 */
@Composable
internal fun OlcRtcInstallDialog(
    config: LocationConfig,
    onApply: (provider: String, transport: String, rooms: List<String>, key: String) -> Unit,
    onDismiss: () -> Unit
) {
    val installer = rememberOlcRtcServerInstaller()
    val scope = rememberCoroutineScope()

    var ip by remember { mutableStateOf("") }
    var sshPort by remember { mutableStateOf("22") }
    var login by remember { mutableStateOf("root") }
    var password by remember { mutableStateOf("") }
    var useKey by remember { mutableStateOf(false) }
    var sshKey by remember { mutableStateOf("") }
    var keyPassphrase by remember { mutableStateOf("") }

    var provider by remember {
        mutableStateOf(config.bypassProvider.takeIf { it in OLCRTC_PROVIDERS } ?: "jitsi")
    }
    var transport by remember {
        mutableStateOf(config.transport.takeIf { it in OLCRTC_TRANSPORTS } ?: "vp8channel")
    }
    var jitsiInstance by remember { mutableStateOf("https://meet.jit.si") }
    var roomsText by remember { mutableStateOf(config.id) }
    var dns by remember { mutableStateOf("8.8.8.8:53") }

    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<String>?>(null) }
    var removed by remember { mutableStateOf(false) }
    val log = remember { mutableStateListOf<String>() }
    val logScroll = rememberScrollState()

    val rooms = roomsText.lines().map { it.trim() }.filter { it.isNotBlank() }.take(OLCRTC_MAX_ROOMS)
    val succeeded = result?.isSuccess == true
    val hasSshAccess = ip.isNotBlank() && (if (useKey) sshKey.isNotBlank() else password.isNotBlank())
    val canInstall = !running && hasSshAccess && rooms.isNotEmpty()

    fun options() = OlcRtcInstallOptions(
        host = ip.trim(),
        sshPort = sshPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22,
        login = login.ifBlank { "root" },
        sshPassword = if (useKey) "" else password,
        sshKey = if (useKey) sshKey else "",
        sshKeyPassphrase = if (useKey) keyPassphrase else "",
        provider = provider,
        transport = transport,
        rooms = rooms,
        dns = dns,
    )

    androidx.compose.runtime.LaunchedEffect(log.size) {
        if (log.isNotEmpty()) logScroll.scrollTo(logScroll.maxValue)
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("Установка olcRTC на VPS") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Подключусь к VPS по SSH и подниму по процессу olcRTC на каждую комнату " +
                        "(${rooms.size.coerceAtLeast(1)} шт., $provider/$transport). Ключ шифрования " +
                        "сгенерируется на сервере и подставится в локацию вместе с комнатами.",
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
                SettingsDropdown(
                    label = "Сервис",
                    selectedValue = provider,
                    options = OLCRTC_PROVIDERS,
                    enabled = !running,
                    onValueSelected = { provider = it },
                    valueLabel = { it }
                )
                SettingsDropdown(
                    label = "Транспорт",
                    selectedValue = transport,
                    options = OLCRTC_TRANSPORTS,
                    enabled = !running,
                    onValueSelected = { transport = it },
                    valueLabel = { it }
                )
                if (provider == "jitsi") {
                    // У jitsi комната появляется при первом заходе, отдельного API создания нет —
                    // поэтому имена просто генерируются, а инстанс выбирает пользователь.
                    OutlinedTextField(
                        value = jitsiInstance,
                        onValueChange = { jitsiInstance = it.trim() },
                        label = { Text("Инстанс jitsi") },
                        singleLine = true,
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..OLCRTC_MAX_ROOMS).forEach { n ->
                            TextButton(
                                enabled = !running,
                                onClick = { roomsText = generateJitsiRooms(jitsiInstance, n).joinToString("\n") }
                            ) { Text("$n") }
                        }
                    }
                }
                OutlinedTextField(
                    value = roomsText,
                    onValueChange = { roomsText = it },
                    label = { Text("Комнаты (по одной в строке, до $OLCRTC_MAX_ROOMS)") },
                    enabled = !running,
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dns,
                    onValueChange = { dns = it.trim() },
                    label = { Text("DNS сервера") },
                    singleLine = true,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth()
                )
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
                        result?.getOrNull().orEmpty() +
                            if (removed) "" else "\nКомнаты и ключ подставлены в локацию.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            if (succeeded && !removed) {
                TextButton(onClick = onDismiss) { Text("Готово") }
            } else {
                TextButton(
                    enabled = canInstall,
                    onClick = {
                        running = true
                        result = null
                        removed = false
                        log.clear()
                        scope.launch {
                            val res = installer.install(options()) { line -> log.add(line) }
                            res.getOrNull()?.let { ok ->
                                onApply(ok.provider, ok.transport, ok.rooms, ok.cryptoKey)
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
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Сносит службы ВСЕХ комнат сразу: у olcRTC нет порта, по которому их можно было бы
                // различать, а держать на VPS осиротевший процесс в чужой комнате — худшее из зол.
                TextButton(
                    enabled = !running && hasSshAccess,
                    onClick = {
                        running = true
                        result = null
                        removed = true
                        log.clear()
                        scope.launch {
                            val res = installer.uninstall(options()) { line -> log.add(line) }
                            res.exceptionOrNull()?.let { log.add("ОШИБКА: ${it.message}") }
                            result = res
                            running = false
                        }
                    }
                ) { Text("Удалить с VPS") }
                TextButton(onClick = onDismiss, enabled = !running) {
                    Text(if (succeeded) "Закрыть" else "Отмена")
                }
            }
        }
    )
}
