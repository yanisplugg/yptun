package org.olcbox.app.vpn.openflux

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.data.model.OpenFluxCarrier
import org.olcbox.app.data.model.OpenFluxConfig
import org.olcbox.app.vpn.ssh.ServerBinarySource
import org.olcbox.app.vpn.ssh.SshTarget
import org.olcbox.app.vpn.ssh.loadServerBinaryGz
import org.olcbox.app.vpn.ssh.sshOneShot
import org.olcbox.app.vpn.ssh.sshUpload
import kotlin.random.Random

/**
 * SSH-based OpenFlux exit-node installer — the same one-command-per-connection shape as the other
 * installers (see SshSupport): `uname -m`, the gzip'd binary, then one install script.
 *
 * Supports single-transport nodes (including comma/newline-separated multi-room URLs via
 * OpenFlux's group stripe), auto-created `cupsonline` multi-rooms (`CUPS_ROOMS`), and
 * multi-transport sessions (`--transports` + `/etc/openflux/openflux.key`).
 */
internal class SshOpenFluxServerInstaller(private val binaries: ServerBinarySource) : OpenFluxServerInstaller {

    override suspend fun install(
        options: OpenFluxInstallOptions,
        onLog: (String) -> Unit
    ): Result<OpenFluxInstallResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(options.host.isNotBlank()) { "Не указан IP/хост VPS" }
            require(options.sshKey.isNotBlank() || options.sshPassword.isNotBlank()) {
                "Укажи пароль SSH или SSH-ключ"
            }
            require(
                options.transport != OpenFluxConfig.TRANSPORT_AVITO &&
                    options.carriers.none { it.type == OpenFluxConfig.TRANSPORT_AVITO }
            ) {
                "Для Avito Code нода ставится вручную (ей нужны cookie аккаунта и она сама печатает список комнат): " +
                    "по инструкции OpenFlux, потом вставь список комнат в приложение"
            }

            val resolved = prepareInstallOptions(options)
            val target = SshTarget(
                resolved.host, resolved.sshPort, resolved.login, resolved.sshPassword,
                privateKey = resolved.sshKey, passphrase = resolved.sshKeyPassphrase,
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
            sshUpload(target, gz, REMOTE_GZ, onLog)
            onLog("Бинарник загружен, ставлю службу…")

            val output = sshOneShot(target, buildOpenFluxInstallScript(resolved), onLog)
            output.lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith(ROOMS_MARK) }
                .forEach(onLog)
            output.lineSequence().firstOrNull { it.trim().startsWith("ОШИБКА:") }?.let {
                error(it.trim().removePrefix("ОШИБКА:").trim())
            }

            val capturedRooms = output.lineSequence().map { it.trim() }
                .firstOrNull { it.startsWith(ROOMS_MARK) }
                ?.removePrefix(ROOMS_MARK)
                ?.trim()
                .orEmpty()

            if (resolved.needsAutoCupsRooms() && capturedRooms.isBlank()) {
                error("cups.online не вернул список комнат. См. лог выше.")
            }

            if (resolved.carriers.isNotEmpty()) {
                val finalCarriers = resolved.carriers.map { c ->
                    if (c.type == OpenFluxConfig.TRANSPORT_CUPS && c.url.isBlank() && capturedRooms.isNotBlank()) {
                        c.copy(url = capturedRooms)
                    } else {
                        c
                    }
                }
                OpenFluxInstallResult(
                    status = "Мультитранспортная нода OpenFlux запущена на ${resolved.host} (${finalCarriers.size} канал.)",
                    carriers = finalCarriers,
                    secret = resolved.secret,
                )
            } else {
                val finalDocUrl = if (capturedRooms.isNotBlank()) capturedRooms else resolved.docUrl
                val roomsLabel = if (capturedRooms.isNotBlank()) " · ${resolved.cupsRoomsCount} комн. cups.online" else ""
                OpenFluxInstallResult(
                    status = "Выходная нода OpenFlux запущена на ${resolved.host}$roomsLabel",
                    docUrl = finalDocUrl,
                    secret = resolved.secret,
                )
            }
        }
    }

    private companion object {
        const val REMOTE_GZ = "/tmp/openflux.gz"
    }
}

private const val ROOMS_MARK = "RESULT_ROOMS::"

private fun prepareInstallOptions(options: OpenFluxInstallOptions): OpenFluxInstallOptions {
    val cupsRooms = options.cupsRoomsCount.coerceIn(
        OpenFluxInstallOptions.MIN_CUPS_ROOMS,
        OpenFluxInstallOptions.MAX_CUPS_ROOMS
    )
    if (options.carriers.isNotEmpty()) {
        val carriers = options.carriers
            .map { it.normalized() }
            .filter { it.type in OpenFluxConfig.SESSION_CARRIERS }
            .distinctBy { it.type }
            .map { c ->
                if (c.type == OpenFluxConfig.TRANSPORT_DIRECT) {
                    val port = c.dial.substringAfterLast(':', "").toIntOrNull()
                        ?.takeIf { it in 1..65535 } ?: OpenFluxInstallOptions.DEFAULT_DIRECT_PORT
                    c.copy(dial = "${options.host.trim()}:$port")
                } else {
                    c
                }
            }
        require(carriers.isNotEmpty()) { "Добавь хотя бы один канал для мультитранспорта" }
        carriers.forEach { c ->
            when (c.type) {
                OpenFluxConfig.TRANSPORT_DIRECT -> Unit // resolved above
                OpenFluxConfig.TRANSPORT_CUPS -> if (c.url.isNotBlank()) {
                    require(OpenFluxConfig.isValidCoordinate(c.type, c.url)) {
                        "Некорректная ссылка или список комнат для cups.online"
                    }
                }
                else -> require(OpenFluxConfig.isValidCoordinate(c.type, c.url)) {
                    "Не указана ссылка для канала ${OpenFluxConfig.carrierLabel(c.type)}"
                }
            }
        }
        val effectiveSecret = options.secret.trim().ifBlank { generateSecretHex() }
        return options.copy(
            cupsRoomsCount = cupsRooms,
            carriers = carriers,
            secret = effectiveSecret,
            context = options.context.trim(),
        )
    }

    val normalizedUrl = OpenFluxConfig.normalizeCoordinateList(options.docUrl)
    when (options.transport) {
        OpenFluxConfig.TRANSPORT_MAX -> {
            require(options.exitMaxToken.isNotBlank()) { "Не указан токен MAX выходной ноды" }
        }
        OpenFluxConfig.TRANSPORT_CUPS -> {
            if (normalizedUrl.isNotBlank()) {
                require(OpenFluxConfig.isValidCoordinate(OpenFluxConfig.TRANSPORT_CUPS, normalizedUrl)) {
                    "Некорректная ссылка или список комнат cups.online"
                }
            }
        }
        else -> {
            require(OpenFluxConfig.isValidCoordinate(options.transport, normalizedUrl)) {
                "Не указана ссылка на документ/комнату"
            }
        }
    }
    return options.copy(
        docUrl = normalizedUrl,
        cupsRoomsCount = cupsRooms,
        secret = options.secret.trim(),
        context = options.context.trim(),
    )
}

private fun OpenFluxInstallOptions.needsAutoCupsRooms(): Boolean = if (carriers.isNotEmpty()) {
    carriers.any { it.type == OpenFluxConfig.TRANSPORT_CUPS && it.url.isBlank() }
} else {
    transport == OpenFluxConfig.TRANSPORT_CUPS && docUrl.isBlank()
}

private fun generateSecretHex(): String =
    Random.nextBytes(32).joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }

/** A value for a systemd EnvironmentFile: double-quoted, with `\` and `"` escaped. */
private fun envFileValue(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "") + "\""

private fun carrierEnvVar(type: String): String = when (type) {
    OpenFluxConfig.TRANSPORT_YANDEX -> "OPENFLUX_YANDEX_URL"
    OpenFluxConfig.TRANSPORT_VYANDEX -> "OPENFLUX_VYANDEX_URL"
    OpenFluxConfig.TRANSPORT_MAILRU -> "OPENFLUX_MAILRU_URL"
    OpenFluxConfig.TRANSPORT_CUPS -> "OPENFLUX_CUPSONLINE_URL"
    OpenFluxConfig.TRANSPORT_DIRECT -> "OPENFLUX_DIRECT_LISTEN"
    else -> "OPENFLUX_${type.uppercase()}_URL"
}

/**
 * The remote install script. The carrier coordinates live in a root-only EnvironmentFile and reach the
 * binary as `${VAR}` words: a Yandex Docs URL is full of `%XX`, which systemd would read as unit
 * specifiers if it sat on the ExecStart line, and a MAX token / encryption key must not be world-readable.
 */
internal fun buildOpenFluxInstallScript(options: OpenFluxInstallOptions): String {
    val d = '$'
    val rst = "OUTPUT -p tcp --tcp-flags RST RST -j DROP"
    val cupsRooms = options.cupsRoomsCount.coerceIn(
        OpenFluxInstallOptions.MIN_CUPS_ROOMS,
        OpenFluxInstallOptions.MAX_CUPS_ROOMS
    )
    val secret = options.secret.trim()
    val context = options.context.trim()
    val carriers = options.carriers
        .map { it.normalized() }
        .filter { it.type in OpenFluxConfig.SESSION_CARRIERS }
        .distinctBy { it.type }
    val multiMode = carriers.isNotEmpty()
    val normalizedDocUrl = OpenFluxConfig.normalizeCoordinateList(options.docUrl)
    val transport = options.transport.takeIf { it in OpenFluxConfig.TRANSPORTS } ?: OpenFluxConfig.TRANSPORT_YANDEX
    val autoCups = if (multiMode) {
        carriers.any { it.type == OpenFluxConfig.TRANSPORT_CUPS && it.url.isBlank() }
    } else {
        transport == OpenFluxConfig.TRANSPORT_CUPS && normalizedDocUrl.isBlank()
    }

    val envLines = mutableListOf<String>()
    val execParts = mutableListOf("--role=exit", "--mode=l4")
    var directPortToOpen: Int? = null

    if (multiMode) {
        envLines += "OPENFLUX_TRANSPORTS=${envFileValue(carriers.joinToString(",") { "${it.type}:${it.priority}" })}"
        execParts += "--transports ${d}{OPENFLUX_TRANSPORTS}"
        carriers.forEach { c ->
            when (c.type) {
                OpenFluxConfig.TRANSPORT_DIRECT -> {
                    val port = c.dial.substringAfterLast(':', "").toIntOrNull()
                        ?.takeIf { it in 1..65535 } ?: OpenFluxInstallOptions.DEFAULT_DIRECT_PORT
                    directPortToOpen = port
                    envLines += "${carrierEnvVar(c.type)}=${envFileValue("0.0.0.0:$port")}"
                    execParts += "--direct-listen ${d}{${carrierEnvVar(c.type)}}"
                }
                else -> {
                    if (c.url.isNotBlank()) {
                        val envName = carrierEnvVar(c.type)
                        envLines += "$envName=${envFileValue(c.url)}"
                        execParts += "--${c.type}-url ${d}{$envName}"
                    }
                }
            }
        }
    } else {
        envLines += "OPENFLUX_TRANSPORT=${envFileValue(transport)}"
        envLines += "OPENFLUX_DOC_URL=${envFileValue(normalizedDocUrl)}"
        envLines += "OPENFLUX_MAX_TOKEN=${envFileValue(options.exitMaxToken.trim())}"
        execParts += "--transport ${d}{OPENFLUX_TRANSPORT}"
        if (transport != OpenFluxConfig.TRANSPORT_MAX && normalizedDocUrl.isNotBlank()) {
            execParts += "--url ${d}{OPENFLUX_DOC_URL}"
        }
    }

    if (autoCups) {
        envLines += "CUPS_ROOMS=${envFileValue(cupsRooms.toString())}"
    }
    if (secret.isNotBlank()) {
        if (context.isNotBlank()) {
            envLines += "OPENFLUX_SESSION_CONTEXT=${envFileValue(context)}"
        }
        execParts += "--encryption-key-file /etc/openflux/openflux.key"
        if (context.isNotBlank()) {
            execParts += "--session-context ${d}{OPENFLUX_SESSION_CONTEXT}"
        }
        if (options.negotiate) {
            execParts += "--negotiate"
        }
    }

    val execArgs = execParts.joinToString(" ")
    val envBlock = envLines.joinToString("\n")
    val keyBlock = if (secret.isNotBlank()) {
        """
        cat > /etc/openflux/openflux.key <<'KEYFILE'
        $secret
        KEYFILE
        chmod 600 /etc/openflux/openflux.key
        """.trimIndent() + "\n"
    } else {
        "rm -f /etc/openflux/openflux.key\n"
    }
    val ufwBlock = directPortToOpen?.let { port ->
        """
        if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
          ufw allow $port/tcp >/dev/null && echo "ufw: порт $port/tcp открыт"
        fi
        """.trimIndent() + "\n"
    }.orEmpty()

    val postStartBlock = if (autoCups) {
        val envUpdate = if (multiMode) {
            """
            printf 'OPENFLUX_CUPSONLINE_URL="%s"\n' "${d}rooms" >> /etc/openflux/openflux.env
            sed -i '/^ExecStart=/ s|${d}| --cupsonline-url ${d}{OPENFLUX_CUPSONLINE_URL}|' /etc/systemd/system/openflux.service
            """.trimIndent()
        } else {
            """
            sed -i "s|^OPENFLUX_DOC_URL=.*|OPENFLUX_DOC_URL=\"${d}rooms\"|" /etc/openflux/openflux.env
            sed -i '/^ExecStart=/ s|${d}| --url ${d}{OPENFLUX_DOC_URL}|' /etc/systemd/system/openflux.service
            """.trimIndent()
        }
        """
        rooms=""
        for i in ${d}(seq 1 45); do
          fail=${d}(journalctl -u openflux --since "-60s" -o cat 2>/dev/null | grep "Failed to start transport" | tail -1)
          if [ -n "${d}fail" ]; then
            echo "ОШИБКА: нода не подключилась к площадке: ${d}{fail#*Failed to start transport: }"
            exit 1
          fi
          rooms=${d}(journalctl -u openflux --since "-60s" -o cat 2>/dev/null | awk '/=== COPY THIS TO CLIENT ===/{buf="";flag=1;next} /===========================/{if(buf!="") last=buf; flag=0;next} flag{buf=buf ${d}0} END{print last}' | tr -d '\r\n ')
          if [ -n "${d}rooms" ]; then
            break
          fi
          if ! systemctl is-active --quiet openflux; then
            break
          fi
          sleep 1
        done
        if [ -z "${d}rooms" ]; then
          echo "ОШИБКА: cups.online не вернул список комнат за отведённое время"
          exit 1
        fi
        $envUpdate
        systemctl daemon-reload
        echo "${ROOMS_MARK}${d}rooms"
        systemctl is-active openflux && echo "Служба openflux активна (создано комнат cups.online: $cupsRooms)"
        """.trimIndent()
    } else {
        val statusLabel = if (multiMode) {
            "мультитранспорт: ${carriers.joinToString("+") { it.type }}"
        } else {
            "транспорт: $transport"
        }
        """
        sleep 8
        fail=${d}(journalctl -u openflux --since "-15s" -o cat 2>/dev/null | grep "Failed to start transport" | tail -1)
        if [ -n "${d}fail" ]; then
          if echo "${d}fail" | grep -q captcha; then
            echo "ОШИБКА: Яндекс показывает IP этого VPS капчу — нода не может войти в документ. Попробуй другой VPS или транспорт (Mail.ru, cups.online)."
          else
            echo "ОШИБКА: нода не подключилась к площадке: ${d}{fail#*Failed to start transport: }"
          fi
          exit 1
        fi
        systemctl is-active openflux && echo "Служба openflux активна ($statusLabel)"
        """.trimIndent()
    }

    return buildString {
        append(
            """
            set -e
            gunzip -f /tmp/openflux.gz
            # An earlier install (any version) is replaced wholesale: stop it, drop its unit and the
            # host-wide RST-drop rule the old l3 exit needed, then install fresh.
            if [ -f /etc/systemd/system/openflux.service ] || [ -x /usr/local/bin/openflux ]; then
              echo "Найдена прежняя установка OpenFlux — переустанавливаю"
              systemctl disable --now openflux >/dev/null 2>&1 || true
              rm -f /etc/systemd/system/openflux.service
            fi
            if command -v iptables >/dev/null 2>&1; then
              while iptables -D $rst 2>/dev/null; do :; done
            fi
            install -m 0755 /tmp/openflux /usr/local/bin/openflux
            rm -f /tmp/openflux
            mkdir -p /etc/openflux
            chmod 700 /etc/openflux
            """.trimIndent()
        )
        append('\n')
        append(keyBlock)
        append(ufwBlock)
        append("cat > /etc/openflux/openflux.env <<'ENVFILE'\n")
        append(envBlock)
        append("\nENVFILE\nchmod 600 /etc/openflux/openflux.env\n")
        append(
            """
            cat > /etc/systemd/system/openflux.service <<'UNIT'
            [Unit]
            Description=OpenFlux exit node
            After=network-online.target
            Wants=network-online.target
            [Service]
            EnvironmentFile=/etc/openflux/openflux.env
            ExecStart=/usr/local/bin/openflux $execArgs
            Restart=always
            RestartSec=60
            [Install]
            WantedBy=multi-user.target
            UNIT
            systemctl daemon-reload
            systemctl enable --now openflux
            """.trimIndent()
        )
        append('\n')
        append(postStartBlock)
    }
}
