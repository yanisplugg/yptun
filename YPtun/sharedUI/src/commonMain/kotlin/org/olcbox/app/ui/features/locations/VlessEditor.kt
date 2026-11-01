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

private val NETWORKS = listOf("tcp", "ws", "grpc", "httpupgrade", "xhttp", "http")
private val SECURITIES = listOf(ProxyProfile.SECURITY_NONE, ProxyProfile.SECURITY_TLS, ProxyProfile.SECURITY_REALITY)
private val FLOWS = listOf("", "xtls-rprx-vision")
private val FINGERPRINTS = listOf("", "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized")
private val XHTTP_MODES = listOf("", "auto", "packet-up", "stream-up", "stream-one")

/**
 * Hand-made VLESS config (Happ / v2rayN style): every field of the main proxy as a form. [onEdit] applies a
 * change to the current profile (a fresh VLESS one when there is none yet); the screen only shows this
 * for an empty or plain-VLESS main proxy — a raw sing-box/Xray JSON bypasses the typed fields.
 */
@Composable
internal fun VlessEditor(proxy: ProxyProfile?, enabled: Boolean, onEdit: ((ProxyProfile) -> ProxyProfile) -> Unit) {
    val p = proxy ?: ProxyProfile()
    val tls = p.security == ProxyProfile.SECURITY_TLS
    val reality = p.security == ProxyProfile.SECURITY_REALITY
    val xhttp = p.network == ProxyProfile.NETWORK_XHTTP
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
    @Composable
    fun dropdown(label: String, value: String, options: List<String>, set: (ProxyProfile, String) -> ProxyProfile) {
        SettingsDropdown(label, value, options, enabled, { v -> onEdit { set(it, v) } }) { it.ifEmpty { "—" } }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsCaption("VLESS — ручная настройка")
        SettingsCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                field("Адрес сервера", p.server, KeyboardType.Uri) { x, v -> x.copy(server = v) }
                field("Порт", p.serverPort.takeIf { it > 0 }?.toString().orEmpty(), KeyboardType.Number) { x, v ->
                    x.copy(serverPort = v.filter(Char::isDigit).take(5).toIntOrNull() ?: 0)
                }
                field("UUID", p.uuid) { x, v -> x.copy(uuid = v) }
                dropdown("Flow", p.flow, FLOWS) { x, v -> x.copy(flow = v) }
                field("Encryption (vlessenc)", p.vlessEncryption, placeholder = "none") { x, v ->
                    x.copy(vlessEncryption = if (v == "none") "" else v)
                }

                dropdown("Транспорт", p.network, NETWORKS) { x, v -> x.copy(network = v) }
                if (p.network != ProxyProfile.NETWORK_TCP) {
                    field(if (p.network == ProxyProfile.NETWORK_GRPC) "serviceName" else "Path", p.path) { x, v -> x.copy(path = v) }
                    if (p.network != ProxyProfile.NETWORK_GRPC) field("Host", p.host) { x, v -> x.copy(host = v) }
                }
                if (xhttp) {
                    dropdown("xhttp mode", p.xhttpMode, XHTTP_MODES) { x, v -> x.copy(xhttpMode = v) }
                    // Not trimmed-per-keystroke JSON editor: a plain field, the core validates it.
                    field("xhttp extra (JSON)", p.xhttpExtra, placeholder = "{\"xPaddingBytes\":\"100-1000\"}") { x, v -> x.copy(xhttpExtra = v) }
                }

                dropdown("Безопасность", p.security, SECURITIES) { x, v -> x.copy(security = v) }
                if (tls || reality) {
                    field("SNI", p.sni) { x, v -> x.copy(sni = v) }
                    dropdown("Fingerprint (uTLS)", p.fingerprint, FINGERPRINTS) { x, v -> x.copy(fingerprint = v) }
                }
                if (tls) {
                    // Own text state: re-joining the parsed list on every keystroke would eat the typed comma.
                    var alpn by remember { mutableStateOf(p.alpn.joinToString(",")) }
                    OutlinedTextField(
                        value = alpn,
                        onValueChange = { v ->
                            alpn = v
                            onEdit { it.copy(alpn = v.split(',').map(String::trim).filter(String::isNotEmpty)) }
                        },
                        label = { Text("ALPN (через запятую)") },
                        placeholder = { Text("h2,http/1.1") },
                        enabled = enabled,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    field("Pinned cert SHA-256", p.pinnedCertSha256) { x, v -> x.copy(pinnedCertSha256 = v) }
                    field("ECH config", p.echConfigList) { x, v -> x.copy(echConfigList = v) }
                }
                if (reality) {
                    field("Public key (pbk)", p.realityPublicKey) { x, v -> x.copy(realityPublicKey = v) }
                    field("Short ID (sid)", p.realityShortId) { x, v -> x.copy(realityShortId = v) }
                    field("SpiderX (spx)", p.realitySpiderX) { x, v -> x.copy(realitySpiderX = v) }
                    field("mldsa65 verify (pqv)", p.realityMldsa65Verify) { x, v -> x.copy(realityMldsa65Verify = v) }
                }
            }
            if (tls) {
                SettingsCardToggle(
                    title = "Разрешить небезопасный TLS",
                    subtitle = "allowInsecure — не проверять сертификат",
                    checked = p.allowInsecure,
                    enabled = enabled,
                    onCheckedChange = { on -> onEdit { it.copy(allowInsecure = on) } },
                )
            }
        }
    }
}

/** No proxy yet, or a plain VLESS one — a raw sing-box/Xray JSON bypasses the typed fields, so it isn't editable here. */
internal fun ProxyProfile?.isManuallyEditableVless(): Boolean =
    this == null || (type == ProxyProfile.TYPE_VLESS && rawXrayConfig.isNullOrBlank() && rawOutbound.isNullOrBlank())
