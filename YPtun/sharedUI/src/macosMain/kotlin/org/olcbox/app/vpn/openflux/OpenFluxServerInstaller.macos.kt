package org.olcbox.app.vpn.openflux

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberOpenFluxServerInstaller(): OpenFluxServerInstaller = remember { UnsupportedOpenFluxServerInstaller }

private object UnsupportedOpenFluxServerInstaller : OpenFluxServerInstaller {
    override suspend fun install(options: OpenFluxInstallOptions, onLog: (String) -> Unit): Result<String> =
        Result.failure(UnsupportedOperationException("Установка OpenFlux доступна в приложении для Android и ПК"))
}
