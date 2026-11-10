package org.olcbox.app.vpn.turnable

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.vpn.ssh.ServerBinarySource
import org.olcbox.app.vpn.ssh.SshTarget
import org.olcbox.app.vpn.ssh.loadServerBinaryGz
import org.olcbox.app.vpn.ssh.sshOneShot
import org.olcbox.app.vpn.ssh.sshUpload
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal class SshTurnableServerInstaller(
    private val binaries: ServerBinarySource,
) : TurnableServerInstaller {
    override suspend fun install(
        options: TurnableInstallOptions,
        onLog: (String) -> Unit,
    ): Result<TurnableInstallResult> = withContext(Dispatchers.IO) {
        runCatching {
            val target = validatedTarget(options)
            onLog("Определяю архитектуру VPS…")
            val machine = sshOneShot(target, "uname -m", onLog, logProgress = true).trim()
            val arch = when {
                machine.contains("aarch64") || machine.contains("arm64") -> "arm64"
                machine.contains("x86_64") || machine.contains("amd64") -> "amd64"
                else -> error("Неподдерживаемая архитектура VPS: '$machine' (нужен x86_64 или aarch64)")
            }
            val gz = loadServerBinaryGz(binaries, "turnable/turnable-linux-$arch")
            onLog("Загрузка Turnable (${gz.size / 1024} КБ)…")
            sshUpload(target, gz, REMOTE_GZ, onLog)
            onLog("Ставлю Turnable + WireGuard и запускаю systemd-службы…")
            val output = sshOneShot(target, buildTurnableInstallScript(options), onLog)
            output.lineSequence().map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("RESULT::") }
                .forEach(onLog)
            parseResult(output, options)
                ?: error("Установка завершилась, но Turnable не вернул клиентский конфиг (RESULT)")
        }
    }

    override suspend fun uninstall(
        options: TurnableInstallOptions,
        onLog: (String) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val target = validatedTarget(options, requireCallId = false)
            val output = sshOneShot(target, buildTurnableUninstallScript(options.turnablePort), onLog)
            output.lineSequence().map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("REMOVED::") }
                .forEach(onLog)
            require(output.contains("REMOVED::${options.turnablePort}")) {
                "Turnable не подтвердил удаление — см. лог выше"
            }
            "Turnable на :${options.turnablePort} удалён"
        }
    }

    private fun validatedTarget(options: TurnableInstallOptions, requireCallId: Boolean = true): SshTarget {
        require(options.host.isNotBlank()) { "Не указан IP/хост VPS" }
        require(options.sshKey.isNotBlank() || options.sshPassword.isNotBlank()) {
            "Укажи пароль SSH или SSH-ключ"
        }
        require(options.turnablePort in 1024..65535) { "Некорректный порт Turnable" }
        if (requireCallId) require(options.callId.isNotBlank()) { "Не указан VK call id" }
        return SshTarget(
            options.host,
            options.sshPort,
            options.login,
            options.sshPassword,
            privateKey = options.sshKey,
            passphrase = options.sshKeyPassphrase,
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun parseResult(output: String, options: TurnableInstallOptions): TurnableInstallResult? {
        val line = output.lineSequence().map(String::trim)
            .firstOrNull { it.startsWith("RESULT::") } ?: return null
        val parts = line.removePrefix("RESULT::").split('|')
        if (parts.size < 4) return null
        val clientConfig = runCatching { Base64.decode(parts[0]).decodeToString() }.getOrNull().orEmpty()
        if (!clientConfig.startsWith("turnable://", ignoreCase = true) ||
            parts[1].isBlank() || parts[2].isBlank() || parts[3].isBlank()
        ) return null
        return TurnableInstallResult(
            clientConfig = clientConfig,
            serverWgPublicKey = parts[1],
            clientWgPrivateKey = parts[2],
            clientWgAddress = parts[3],
            turnablePort = options.turnablePort,
            status = "Turnable + WireGuard установлены на ${options.host}:${options.turnablePort}",
        )
    }

    private companion object {
        const val REMOTE_GZ = "/tmp/turnable.gz"
    }
}
