package org.olcbox.app.vpn.openflux

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.data.model.OpenFluxConfig
import org.olcbox.app.vpn.ssh.ServerBinarySource
import org.olcbox.app.vpn.ssh.SshTarget
import org.olcbox.app.vpn.ssh.loadServerBinaryGz
import org.olcbox.app.vpn.ssh.sshOneShot
import org.olcbox.app.vpn.ssh.sshUploadInChunks

/**
 * SSH-based OpenFlux exit-node installer — the same one-command-per-connection shape as the other
 * installers (see SshSupport): `uname -m`, the gzip'd binary in base64 slices, then one install script.
 */
internal class SshOpenFluxServerInstaller(private val binaries: ServerBinarySource) : OpenFluxServerInstaller {

    override suspend fun install(
        options: OpenFluxInstallOptions,
        onLog: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(options.host.isNotBlank()) { "Не указан IP/хост VPS" }
            require(options.sshKey.isNotBlank() || options.sshPassword.isNotBlank()) {
                "Укажи пароль SSH или SSH-ключ"
            }
            if (options.transport == OpenFluxConfig.TRANSPORT_MAX) {
                require(options.exitMaxToken.isNotBlank()) { "Не указан токен MAX выходной ноды" }
            } else {
                require(options.docUrl.startsWith("http", ignoreCase = true)) { "Не указана ссылка на Яндекс Документ" }
            }
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

            val gz = loadServerBinaryGz(binaries, "openflux/openflux-server-linux-$goArch")
            onLog("Загрузка выходной ноды (${gz.size / 1024} КБ, по частям)…")
            sshUploadInChunks(target, gz, REMOTE_GZ, onLog)
            onLog("Бинарник загружен, ставлю службу…")

            val output = sshOneShot(target, buildOpenFluxInstallScript(options), onLog)
            output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach(onLog)
            "Выходная нода OpenFlux запущена на ${options.host}"
        }
    }

    private companion object {
        const val REMOTE_GZ = "/tmp/openflux.gz"
    }
}

/** A value for a systemd EnvironmentFile: double-quoted, with `\` and `"` escaped. */
private fun envFileValue(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "") + "\""

/**
 * The remote install script. The carrier coordinates live in a root-only EnvironmentFile and reach the
 * binary as `${VAR}` words: a Yandex Docs URL is full of `%XX`, which systemd would read as unit
 * specifiers if it sat on the ExecStart line, and a MAX token must not be world-readable.
 *
 * The exit node forwards through raw sockets, so the kernel must not answer the forwarded flows with
 * RST — upstream's `iptables -A OUTPUT -p tcp --tcp-flags RST RST -j DROP`. It drops EVERY outgoing RST
 * of the VPS, so it is tied to the service here (ExecStartPre adds it once, ExecStopPost removes it)
 * instead of being left on the machine for good.
 */
internal fun buildOpenFluxInstallScript(options: OpenFluxInstallOptions): String {
    val transport = options.transport.takeIf { it in OpenFluxConfig.TRANSPORTS } ?: OpenFluxConfig.TRANSPORT_YANDEX
    val rst = "OUTPUT -p tcp --tcp-flags RST RST -j DROP"
    val execArgs = if (transport == OpenFluxConfig.TRANSPORT_MAX) {
        "--exit-node --transport ${'$'}{OPENFLUX_TRANSPORT}"
    } else {
        "--exit-node --transport ${'$'}{OPENFLUX_TRANSPORT} --url ${'$'}{OPENFLUX_DOC_URL}"
    }
    return """
        set -e
        gunzip -f /tmp/openflux.gz
        if ! command -v iptables >/dev/null 2>&1; then
          (apt-get install -y iptables || dnf install -y iptables || yum install -y iptables) >/dev/null 2>&1 || true
        fi
        command -v iptables >/dev/null 2>&1 || { echo "ОШИБКА: на VPS нет iptables, выходной ноде он нужен"; exit 1; }
        systemctl stop openflux >/dev/null 2>&1 || true
        install -m 0755 /tmp/openflux /usr/local/bin/openflux
        rm -f /tmp/openflux
        mkdir -p /etc/openflux
        chmod 700 /etc/openflux
        cat > /etc/openflux/openflux.env <<'ENVFILE'
        OPENFLUX_TRANSPORT=${envFileValue(transport)}
        OPENFLUX_DOC_URL=${envFileValue(options.docUrl.trim())}
        OPENFLUX_MAX_TOKEN=${envFileValue(options.exitMaxToken.trim())}
        ENVFILE
        chmod 600 /etc/openflux/openflux.env
        cat > /etc/systemd/system/openflux.service <<'UNIT'
        [Unit]
        Description=OpenFlux exit node
        After=network-online.target
        Wants=network-online.target
        [Service]
        EnvironmentFile=/etc/openflux/openflux.env
        ExecStartPre=/bin/sh -c 'iptables -C $rst 2>/dev/null || iptables -A $rst'
        ExecStart=/usr/local/bin/openflux $execArgs
        ExecStopPost=/bin/sh -c 'iptables -D $rst 2>/dev/null || true'
        Restart=always
        RestartSec=3
        [Install]
        WantedBy=multi-user.target
        UNIT
        systemctl daemon-reload
        systemctl enable --now openflux
        sleep 2
        systemctl is-active openflux && echo "Служба openflux активна (транспорт: $transport)"
    """.trimIndent()
}
