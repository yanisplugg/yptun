package org.olcbox.app.vpn.freeturn

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberFreeturnServerInstaller(): FreeturnServerInstaller = remember { UnsupportedFreeturnServerInstaller }

private object UnsupportedFreeturnServerInstaller : FreeturnServerInstaller {
    override suspend fun install(options: FreeturnInstallOptions, onLog: (String) -> Unit): Result<FreeturnInstallResult> =
        Result.failure(UnsupportedOperationException("Установка freeturn-сервера доступна только в Android-приложении"))
}
