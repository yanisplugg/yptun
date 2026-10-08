package org.olcbox.app.vpn.csqtt

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberCsqttServerInstaller(): CsqttServerInstaller = remember { UnsupportedCsqttServerInstaller }

private object UnsupportedCsqttServerInstaller : CsqttServerInstaller {
    override suspend fun install(options: CsqttInstallOptions, onLog: (String) -> Unit): Result<CsqttInstallResult> =
        Result.failure(UnsupportedOperationException("Установка csqtt-сервера доступна только в приложении для Android и ПК"))
}
