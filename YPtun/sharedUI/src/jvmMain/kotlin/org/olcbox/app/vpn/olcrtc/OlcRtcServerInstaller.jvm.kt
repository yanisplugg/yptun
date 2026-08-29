package org.olcbox.app.vpn.olcrtc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberOlcRtcServerInstaller(): OlcRtcServerInstaller = remember { UnsupportedOlcRtcServerInstaller }

private object UnsupportedOlcRtcServerInstaller : OlcRtcServerInstaller {
    override suspend fun install(options: OlcRtcInstallOptions, onLog: (String) -> Unit): Result<OlcRtcInstallResult> =
        Result.failure(UnsupportedOperationException("Установка olcRTC-сервера доступна только в Android-приложении"))
}
