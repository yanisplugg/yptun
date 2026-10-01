package org.olcbox.app.vpn.snolc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberSnolcServerInstaller(): SnolcServerInstaller = remember { UnsupportedSnolcServerInstaller }

private object UnsupportedSnolcServerInstaller : SnolcServerInstaller {
    override suspend fun install(options: SnolcInstallOptions, onLog: (String) -> Unit): Result<String> =
        Result.failure(UnsupportedOperationException("Установка snolc доступна в приложении для Android и ПК"))
}
