package org.olcbox.app.vpn.dnstt

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberDnsttServerInstaller(): DnsttServerInstaller = remember { UnsupportedDnsttServerInstaller }

private object UnsupportedDnsttServerInstaller : DnsttServerInstaller {
    override suspend fun install(options: DnsttInstallOptions, onLog: (String) -> Unit): Result<DnsttInstallResult> =
        Result.failure(UnsupportedOperationException("Установка dnstt-сервера доступна только в Android-приложении"))
}
