package org.olcbox.app.vpn.masterdns

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberMasterDnsServerInstaller(): MasterDnsServerInstaller = remember { UnsupportedMasterDnsServerInstaller }

private object UnsupportedMasterDnsServerInstaller : MasterDnsServerInstaller {
    override suspend fun install(options: MasterDnsInstallOptions, onLog: (String) -> Unit): Result<MasterDnsInstallResult> =
        Result.failure(UnsupportedOperationException("Установка MasterDNS-сервера доступна только в Android-приложении"))
}
