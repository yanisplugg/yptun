package org.olcbox.app.ui.features.locations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.ui.components.SettingsCaption
import org.olcbox.app.ui.components.SettingsCard
import org.olcbox.app.ui.components.SettingsCardToggle

/**
 * Hand-made NaïveProxy configuration: every parameter of the Naïve server as a manual form.
 * [onEdit] applies changes to the current profile.
 */
@Composable
internal fun NaiveEditor(proxy: ProxyProfile?, enabled: Boolean, onEdit: ((ProxyProfile) -> ProxyProfile) -> Unit) {
    val p = proxy ?: ProxyProfile(type = ProxyProfile.TYPE_NAIVE, serverPort = 443)

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
        SettingsCaption("NaïveProxy — ручная настройка")
        SettingsCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                field("Адрес сервера", p.server, KeyboardType.Uri, placeholder = "naive.example.com") { x, v -> x.copy(server = v) }
                field("Порт", p.serverPort.takeIf { it > 0 }?.toString().orEmpty(), KeyboardType.Number, placeholder = "443") { x, v ->
                    x.copy(serverPort = v.filter(Char::isDigit).take(5).toIntOrNull() ?: 0)
                }
                field("Имя пользователя (логин)", p.username, placeholder = "user") { x, v -> x.copy(username = v) }
                field("Пароль", p.password, KeyboardType.Password, placeholder = "password") { x, v -> x.copy(password = v) }
                field("SNI (Server Name Indication)", p.sni, placeholder = "По умолчанию совпадает с адресом") { x, v -> x.copy(sni = v) }
            }
            SettingsCardToggle(
                title = "QUIC (HTTP/3)",
                subtitle = "naive+quic:// вместо naive+https://",
                checked = p.naiveQuic,
                enabled = enabled,
                onCheckedChange = { on -> onEdit { it.copy(naiveQuic = on) } },
            )
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

/** Check whether this proxy profile represents a manually editable Naïve configuration. */
internal fun ProxyProfile?.isManuallyEditableNaive(): Boolean =
    this?.type == ProxyProfile.TYPE_NAIVE && rawXrayConfig.isNullOrBlank() && rawOutbound.isNullOrBlank()
