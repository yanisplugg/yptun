package org.olcbox.app.ui.features.locations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.ui.components.SettingsCaption
import org.olcbox.app.ui.components.SettingsCard
import org.olcbox.app.ui.components.SettingsCardToggle

/**
 * Hand-made Hysteria 2 configuration: every parameter of the Hysteria 2 server as a manual form.
 * [onEdit] applies changes to the current profile.
 */
@Composable
internal fun Hysteria2Editor(proxy: ProxyProfile?, enabled: Boolean, onEdit: ((ProxyProfile) -> ProxyProfile) -> Unit) {
    val p = proxy ?: ProxyProfile(type = ProxyProfile.TYPE_HYSTERIA2, serverPort = 443, security = ProxyProfile.SECURITY_TLS)

    @Composable
    fun field(label: String, value: String, keyboard: KeyboardType = KeyboardType.Text, placeholder: String = "", set: (ProxyProfile, String) -> ProxyProfile) {
        OutlinedTextField(
            value = value,
            onValueChange = { v -> onEdit { set(it, v.trim()) } },
            label = { Text(label) },
            placeholder = if (placeholder.isEmpty()) null else ({ Text(placeholder) }),
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsCaption("Hysteria 2 — ручная настройка")
        SettingsCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                field("Адрес сервера", p.server, KeyboardType.Uri, placeholder = "hy2.example.com") { x, v -> x.copy(server = v) }
                field("Порт", p.serverPort.takeIf { it > 0 }?.toString().orEmpty(), KeyboardType.Number, placeholder = "443") { x, v ->
                    x.copy(serverPort = v.filter(Char::isDigit).take(5).toIntOrNull() ?: 0)
                }
                field("Пароль (auth)", p.password, KeyboardType.Password, placeholder = "password") { x, v -> x.copy(password = v) }
                field("SNI (Server Name Indication)", p.sni, placeholder = "По умолчанию совпадает с адресом") { x, v -> x.copy(sni = v) }

                var alpn by remember { mutableStateOf(p.alpn.joinToString(",")) }
                OutlinedTextField(
                    value = alpn,
                    onValueChange = { v ->
                        alpn = v
                        onEdit { it.copy(alpn = v.split(',').map(String::trim).filter(String::isNotEmpty)) }
                    },
                    label = { Text("ALPN (через запятую)") },
                    placeholder = { Text("h3") },
                    enabled = enabled,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                field("Порт-хоппинг (mport / ports)", p.hy2Ports, placeholder = "например: 443,5000-6000") { x, v -> x.copy(hy2Ports = v) }
                field("Тип обфускации (obfs)", p.hy2Obfs, placeholder = "salamander или оставьте пустым") { x, v -> x.copy(hy2Obfs = v) }
                if (p.hy2Obfs.isNotBlank()) {
                    field("Пароль обфускации", p.hy2ObfsPassword, KeyboardType.Password, placeholder = "obfs-password") { x, v -> x.copy(hy2ObfsPassword = v) }
                }
                field("Скорость отдачи (Upload, Mbps)", p.hy2UpMbps.takeIf { it > 0 }?.toString().orEmpty(), KeyboardType.Number, placeholder = "0 = авто") { x, v ->
                    x.copy(hy2UpMbps = v.filter(Char::isDigit).toIntOrNull() ?: 0)
                }
                field("Скорость загрузки (Download, Mbps)", p.hy2DownMbps.takeIf { it > 0 }?.toString().orEmpty(), KeyboardType.Number, placeholder = "0 = авто") { x, v ->
                    x.copy(hy2DownMbps = v.filter(Char::isDigit).toIntOrNull() ?: 0)
                }
            }
            SettingsCardToggle(
                title = "Разрешить небезопасный TLS",
                subtitle = "allowInsecure — не проверять сертификат сервера",
                checked = p.allowInsecure,
                enabled = enabled,
                onCheckedChange = { on -> onEdit { it.copy(allowInsecure = on) } },
            )
        }
    }
}

/** Check whether this proxy profile represents a manually editable Hysteria2 configuration. */
internal fun ProxyProfile?.isManuallyEditableHysteria2(): Boolean =
    this?.type == ProxyProfile.TYPE_HYSTERIA2 && rawXrayConfig.isNullOrBlank() && rawOutbound.isNullOrBlank()
