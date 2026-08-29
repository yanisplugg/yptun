package org.olcbox.app.vpn.freeturn

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
actual fun rememberFreeturnServerInstaller(): FreeturnServerInstaller {
    val context = LocalContext.current.applicationContext
    return remember { AndroidFreeturnServerInstaller(context) }
}

/**
 * SSH-based free-turn-proxy server installer. Connects with password auth, detects the VPS
 * architecture (`uname -m`), streams the matching bundled server binary (gzip asset) into /tmp via a
 * plain exec channel (no SFTP — minimal VPS images often lack it), then runs ONE install script that
 * provisions the tunnel exit (WireGuard or AmneziaWG) as the `-connect` backend and launches the
 * server as a systemd service. The script generates the keypair, the obf key and — for AmneziaWG —
 * the obfuscation parameters server-side and echoes them back on a `RESULT::` line so the app can
 * compose the client `freeturn://` link. Mirrors [org.olcbox.app.vpn.wdtt.AndroidWdttServerInstaller];
 * binaries live in assets/freeturn/.
 */
internal class AndroidFreeturnServerInstaller(private val context: Context) : FreeturnServerInstaller {

    override suspend fun install(
        options: FreeturnInstallOptions,
        onLog: (String) -> Unit
    ): Result<FreeturnInstallResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(options.host.isNotBlank()) { "Не указан IP/хост VPS" }
            require(options.sshKey.isNotBlank() || options.sshPassword.isNotBlank()) {
                "Укажи пароль SSH или SSH-ключ"
            }
            require(options.freeturnPort in 1..65535) { "Некорректный порт freeturn" }

            // This VPS resets the link the moment a 2nd channel is opened on a connection, so EVERY
            // step is its own fresh connection running one command (same as the WDTT installer).
            val target = SshTarget(
                options.host, options.sshPort, options.login, options.sshPassword,
                privateKey = options.sshKey, passphrase = options.sshKeyPassphrase,
            )

            onLog("Определяю архитектуру VPS…")
            val machine = sshOneShot(target, "uname -m", onLog, logProgress = true).trim()
            val goArch = when {
                machine.contains("aarch64") || machine.contains("arm64") -> "arm64"
                machine.contains("x86_64") || machine.contains("amd64") -> "amd64"
                else -> error("Неподдерживаемая архитектура VPS: '$machine' (нужен x86_64 или aarch64)")
            }
            onLog("Архитектура VPS: $machine → $goArch")

            val gz = loadServerBinaryGz(context, "freeturn/freeturn-server-linux-$goArch")
            onLog("Загрузка freeturn-сервера (${gz.size / 1024} КБ, по частям)…")
            sshUploadInChunks(target, gz, REMOTE_GZ, onLog)

            if (options.exit == FreeturnExit.AmneziaWG) {
                val awgGz = loadServerBinaryGz(context, "freeturn/amneziawg-go-linux-$goArch")
                onLog("Загрузка amneziawg-go (${awgGz.size / 1024} КБ, по частям)…")
                sshUploadInChunks(target, awgGz, REMOTE_AWG_GZ, onLog)
            }
            onLog("Бинарники загружены, ставлю ${options.exit.label()} + службу…")

            val output = sshOneShot(target, buildInstallScript(options), onLog)
            output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("RESULT::") }
                .forEach(onLog)

            val result = parseResult(output, options)
                ?: error("Установка завершилась, но сервер не вернул ключи (RESULT). См. лог выше.")
            onLog("Готово: freeturn-server на ${options.host}:${options.freeturnPort}")
            result
        }
    }

    /**
     * Pulls `RESULT::<obfKey>|<serverPub>|<clientPriv>|<clientAddr>[|jc,jmin,jmax,s1,s2,h1,h2,h3,h4]`
     * out of the output. Пятое поле появляется только у AmneziaWG-выхода.
     */
    private fun parseResult(output: String, options: FreeturnInstallOptions): FreeturnInstallResult? {
        val line = output.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("RESULT::") } ?: return null
        val parts = line.removePrefix("RESULT::").split('|')
        if (parts.size < 4) return null
        val (key, serverPub, clientPriv, clientAddr) = parts
        if (key.isBlank() || serverPub.isBlank() || clientPriv.isBlank()) return null
        val awg = parts.getOrNull(4)?.split(',')?.takeIf { it.size == 9 }?.let {
            FreeturnAwgParams(it[0], it[1], it[2], it[3], it[4], it[5], it[6], it[7], it[8])
        }
        if (options.exit == FreeturnExit.AmneziaWG && awg == null) {
            error("AmneziaWG-выход установлен, но параметры обфускации не вернулись — см. лог выше")
        }
        return FreeturnInstallResult(
            obfKey = key,
            serverWgPublicKey = serverPub,
            clientWgPrivateKey = clientPriv,
            clientWgAddress = clientAddr.ifBlank { "10.7.1.2/32" },
            freeturnPort = options.freeturnPort,
            status = "freeturn-server (${options.exit.label()}) установлен и запущен на :${options.freeturnPort}",
            awg = awg,
        )
    }

    private companion object {
        const val REMOTE_GZ = "/tmp/freeturn-server.gz"
        const val REMOTE_AWG_GZ = "/tmp/amneziawg-go.gz"
    }
}

private fun FreeturnExit.label(): String =
    if (this == FreeturnExit.AmneziaWG) "AmneziaWG" else "WireGuard"
