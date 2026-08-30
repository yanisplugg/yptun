package org.olcbox.app.vpn.olcrtc

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.vpn.ssh.SshTarget
import org.olcbox.app.vpn.ssh.loadServerBinaryGz
import org.olcbox.app.vpn.ssh.sshOneShot
import org.olcbox.app.vpn.ssh.sshUploadInChunks

@Composable
actual fun rememberOlcRtcServerInstaller(): OlcRtcServerInstaller {
    val context = LocalContext.current.applicationContext
    return remember { AndroidOlcRtcServerInstaller(context) }
}

/**
 * SSH-установщик olcRTC-сервера. Тот же приём, что у freeturn/WDTT/dnstt: определяем архитектуру,
 * заливаем бандленный бинарник кусками через exec-канал (SFTP на голых VPS-образах часто нет), а
 * дальше одна команда раскладывает конфиги и systemd-юниты — по одному на комнату.
 *
 * Ключ шифрования генерируется НА сервере и возвращается строкой `RESULT::` — клиент подставляет его
 * в локацию, потому что обе стороны обязаны шифровать одним ключом.
 */
internal class AndroidOlcRtcServerInstaller(private val context: Context) : OlcRtcServerInstaller {

    override suspend fun install(
        options: OlcRtcInstallOptions,
        onLog: (String) -> Unit
    ): Result<OlcRtcInstallResult> = withContext(Dispatchers.IO) {
        runCatching {
            val target = validated(options)
            // Скрипт собираем ДО заливки: он же проверяет комнаты, транспорт и DNS, и незачем гнать
            // 11 МБ на VPS, чтобы потом упасть на опечатке в ссылке.
            val script = buildOlcRtcInstallScript(options)

            onLog("Определяю архитектуру VPS…")
            val goArch = detectArch(target, onLog)

            val gz = loadServerBinaryGz(context, "olcrtc/olcrtc-linux-$goArch")
            onLog("Загрузка olcRTC (${gz.size / 1024} КБ, по частям)…")
            sshUploadInChunks(target, gz, REMOTE_GZ, onLog)
            onLog("Бинарник загружен, ставлю ${options.rooms.size} комнат(ы)…")

            val output = sshOneShot(target, script, onLog)
            output.lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("RESULT::") }
                .forEach(onLog)

            val key = output.lineSequence().map { it.trim() }
                .firstOrNull { it.startsWith("RESULT::") }
                ?.removePrefix("RESULT::")
                ?.takeIf { it.length == 64 && it.all { c -> c.isDigit() || c in 'a'..'f' } }
                ?: error("Сервер не вернул ключ шифрования (RESULT). См. лог выше.")

            onLog("Готово: olcRTC поднят на ${options.host}")
            OlcRtcInstallResult(
                cryptoKey = key,
                rooms = options.rooms.map { it.trim() },
                provider = options.provider,
                transport = options.transport,
                status = "olcRTC установлен: ${options.rooms.size} комнат(ы), ${options.provider}/${options.transport}",
            )
        }
    }

    override suspend fun uninstall(
        options: OlcRtcInstallOptions,
        onLog: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val target = validated(options, needRooms = false)
            onLog("Удаляю olcRTC с ${options.host}…")
            val output = sshOneShot(target, buildOlcRtcUninstallScript(), onLog)
            output.lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("REMOVED::") }
                .forEach(onLog)
            // Скрипт идёт под `set -e`, поэтому маркер в конце = дошли до конца без ошибок.
            require(output.contains("REMOVED::olcrtc")) { "Сервер не подтвердил удаление — см. лог выше" }
            "olcRTC удалён: службы всех комнат, конфиги, ключ и бинарник"
        }
    }

    private fun validated(options: OlcRtcInstallOptions, needRooms: Boolean = true): SshTarget {
        require(options.host.isNotBlank()) { "Не указан IP/хост VPS" }
        require(options.sshKey.isNotBlank() || options.sshPassword.isNotBlank()) {
            "Укажи пароль SSH или SSH-ключ"
        }
        if (needRooms) require(options.rooms.any { it.isNotBlank() }) { "Не задана ни одна комната" }
        // Соединение на КАЖДУЮ команду: этот VPS роняет линк, как только на нём открывают второй
        // канал (та же причина, что в установщиках freeturn и WDTT).
        return SshTarget(
            options.host, options.sshPort, options.login, options.sshPassword,
            privateKey = options.sshKey, passphrase = options.sshKeyPassphrase,
        )
    }

    private suspend fun detectArch(target: SshTarget, onLog: (String) -> Unit): String {
        val machine = sshOneShot(target, "uname -m", onLog, logProgress = true).trim()
        val goArch = when {
            machine.contains("aarch64") || machine.contains("arm64") -> "arm64"
            machine.contains("x86_64") || machine.contains("amd64") -> "amd64"
            else -> error("Неподдерживаемая архитектура VPS: '$machine' (нужен x86_64 или aarch64)")
        }
        onLog("Архитектура VPS: $machine → $goArch")
        return goArch
    }

    private companion object {
        const val REMOTE_GZ = "/tmp/olcrtc.gz"
    }
}
