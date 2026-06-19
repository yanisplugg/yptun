package org.olcbox.app.vpn.wdtt

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberWdttServerInstaller(): WdttServerInstaller = remember { UnsupportedWdttServerInstaller }

private object UnsupportedWdttServerInstaller : WdttServerInstaller {
    override suspend fun install(options: WdttInstallOptions, onLog: (String) -> Unit): Result<String> =
        Result.failure(UnsupportedOperationException("Установка wdtt-сервера доступна только в Android-приложении"))
}
