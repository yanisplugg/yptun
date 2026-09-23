package org.olcbox.app.vpn.masterdns

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.vpn.ssh.SshTarget
import org.olcbox.app.vpn.ssh.ServerBinarySource
import org.olcbox.app.vpn.ssh.loadServerBinaryGz
import org.olcbox.app.vpn.ssh.shellSingleQuote
import org.olcbox.app.vpn.ssh.sshOneShot
import org.olcbox.app.vpn.ssh.sshUpload

/**
 * SSH-based MasterDnsVPN server installer. Connects with password or key auth, detects the VPS
 * architecture (`uname -m`), streams the matching bundled server binary (gzip asset) into /tmp via a
 * plain exec channel (no SFTP — minimal VPS images often lack the subsystem), then runs the install
 * script as a single shell command: it places the binary in /usr/local/bin, writes the server config,
 * generates a PERSISTENT encryption key (only when one doesn't exist yet, so the key stays valid
 * across reinstalls), writes a systemd unit and starts it. The script prints the key on a
 * `MASTERDNS_KEY=` line, which is parsed out and returned. The binaries live in assets/masterdns/
 * (see masterdns/build-masterdns-server.ps1).
 */
internal class SshMasterDnsServerInstaller(private val binaries: ServerBinarySource) : MasterDnsServerInstaller {

    override suspend fun install(
        options: MasterDnsInstallOptions,
        onLog: (String) -> Unit
    ): Result<MasterDnsInstallResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(options.host.isNotBlank()) { "Не указан IP/хост VPS" }
            require(options.sshKey.isNotBlank() || options.sshPassword.isNotBlank()) {
                "Укажи пароль SSH или SSH-ключ"
            }
            require(options.domain.isNotBlank()) { "Не указан домен туннеля" }

            // This VPS resets the link the moment a 2nd channel is opened on a connection, so EVERY
            // step is its own fresh connection running one small command (the only thing that worked).
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

            val gz = loadServerBinaryGz(binaries, "masterdns/masterdns-server-linux-$goArch")
            onLog("Загрузка сервера (${gz.size / 1024} КБ, по частям)…")
            sshUpload(target, gz, REMOTE_GZ, onLog)
            onLog("Бинарник загружен, ставлю службу и генерирую ключ…")

            val output = sshOneShot(target, buildInstallScript(options), onLog)

            var key = ""
            output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                val marker = line.substringAfter("MASTERDNS_KEY=", "")
                if (marker.isNotEmpty()) key = marker.trim()
                else onLog(line)
            }
            if (key.isBlank()) {
                val remoteError = output.lineSequence().firstOrNull { it.trim().startsWith("ОШИБКА:") }
                error(remoteError?.trim()?.removePrefix("ОШИБКА:")?.trim() ?: "Не удалось получить ключ шифрования с сервера")
            }
            onLog("Ключ шифрования получен")

            MasterDnsInstallResult(
                encryptionKey = key,
                message = "MasterDNS-сервер установлен и запущен на ${options.host}:${options.udpPort} " +
                    "(резолвер: ${options.host}:${options.udpPort})"
            )
        }
    }

    private companion object {
        const val REMOTE_GZ = "/tmp/masterdns-server.gz"
    }
}

/**
 * The remote install script. Decompresses + installs the binary, writes the server config, generates a
 * PERSISTENT encryption key the first time (`-genkey` is a no-op when `encrypt_key.txt` already
 * exists, so the key survives a reinstall), writes a systemd unit, opens the UDP port on any common
 * firewall (best-effort), starts the service and prints the key on a `MASTERDNS_KEY=` line.
 *
 * `USE_EXTERNAL_SOCKS5 = false` makes the server its own internet exit, so no second daemon is needed.
 * Single-quoted values are escaped so an awkward domain can't break out of the shell quoting.
 */
internal fun buildInstallScript(options: MasterDnsInstallOptions): String {
    val udp = options.udpPort
    val domain = options.domain.shellSingleQuote()
    val encryption = options.encryptionMethod.coerceIn(0, 5)
    // Only IP[:port] tokens survive, so nothing typed here can break out of the TOML/heredoc.
    val upstream = options.dnsUpstream.split(',', ' ', '\n', ';')
        .map { it.trim() }
        .filter { it.matches(Regex("""^[0-9A-Fa-f.:\[\]]+$""")) }
        .map { if (it.count { c -> c == ':' } == 0) "$it:53" else it }
        .ifEmpty { listOf("1.1.1.1:53", "8.8.8.8:53") }
        .joinToString(", ") { "\"$it\"" }
    val compression = if (options.allowCompression) "[0, 1, 2, 3]" else "[0]"
    val logLevel = options.logLevel.uppercase().takeIf { it in setOf("DEBUG", "INFO", "WARN", "ERROR") } ?: "INFO"
    val freePort = if (options.freePort) 1 else 0
    val regenKey = if (options.regenerateKey) 1 else 0
    return """
        set -e
        gunzip -f /tmp/masterdns-server.gz
        # Our own (possibly crash-looping) instance must not count as "port busy", and a plain
        # `enable --now` would NOT restart an already-running unit, silently keeping the old config.
        if [ -f /etc/systemd/system/masterdns-server.service ] || [ -x /usr/local/bin/masterdns-server ]; then
          echo "Найдена прежняя установка MasterDNS — переустанавливаю (ключ сохраняется, если не выбран новый)"
          systemctl disable --now masterdns-server >/dev/null 2>&1 || true
          rm -f /etc/systemd/system/masterdns-server.service
        fi
        holder=${'$'}(ss -Hulpn "sport = :$udp" 2>/dev/null | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2)
        if [ -n "${'$'}holder" ]; then
          unit=${'$'}(ps -o unit= -p "${'$'}holder" 2>/dev/null | tr -d ' ')
          name=${'$'}(ps -o comm= -p "${'$'}holder" 2>/dev/null)
          if [ "$freePort" = 1 ] && [ -n "${'$'}unit" ] && [ "${'$'}unit" != "-" ]; then
            echo "UDP-порт $udp занят (${'$'}name, ${'$'}unit) — останавливаю и отключаю ${'$'}unit"
            systemctl disable --now "${'$'}unit" || true
            sleep 1
          else
            echo "ОШИБКА: UDP-порт $udp уже занят процессом ${'$'}name (pid ${'$'}holder${'$'}{unit:+, служба ${'$'}unit})."
            echo "Выбери другой UDP-порт или включи «Освободить порт»."
            exit 1
          fi
        fi
        install -m 0755 /tmp/masterdns-server /usr/local/bin/masterdns-server
        rm -f /tmp/masterdns-server
        mkdir -p /etc/masterdns
        cat > /etc/masterdns/server_config.toml <<CONFIG
        PROTOCOL_TYPE = "SOCKS5"
        DOMAIN = [$domain]
        UDP_HOST = ""
        UDP_PORT = $udp
        DATA_ENCRYPTION_METHOD = $encryption
        ENCRYPTION_KEY_FILE = "/etc/masterdns/encrypt_key.txt"
        SUPPORTED_UPLOAD_COMPRESSION_TYPES = $compression
        SUPPORTED_DOWNLOAD_COMPRESSION_TYPES = $compression
        DNS_UPSTREAM_SERVERS = [$upstream]
        USE_EXTERNAL_SOCKS5 = false
        LOG_LEVEL = "$logLevel"
        CONFIG
        if [ "$regenKey" = 1 ]; then rm -f /etc/masterdns/encrypt_key.txt; echo "Старый ключ удалён, генерирую новый"; fi
        /usr/local/bin/masterdns-server -config /etc/masterdns/server_config.toml -genkey -nowait
        chmod 600 /etc/masterdns/encrypt_key.txt
        cat > /etc/systemd/system/masterdns-server.service <<UNIT
        [Unit]
        Description=MasterDnsVPN Server
        After=network-online.target
        Wants=network-online.target
        [Service]
        ExecStart=/usr/local/bin/masterdns-server -config /etc/masterdns/server_config.toml -nowait
        Restart=always
        RestartSec=3
        LimitNOFILE=1048576
        [Install]
        WantedBy=multi-user.target
        UNIT
        # Open the port on whatever firewall is in charge: ufw, firewalld, else raw iptables/ip6tables
        # (a VPS with `-P INPUT DROP` and no front-end would silently eat every query).
        if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
          ufw allow $udp/udp >/dev/null && echo "ufw: открыт UDP $udp"
        elif command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
          firewall-cmd --add-port=$udp/udp --permanent >/dev/null && firewall-cmd --reload >/dev/null && echo "firewalld: открыт UDP $udp"
        fi
        for ipt in iptables ip6tables; do
          if command -v ${'$'}ipt >/dev/null 2>&1 && ! ${'$'}ipt -C INPUT -p udp --dport $udp -j ACCEPT 2>/dev/null; then
            ${'$'}ipt -I INPUT -p udp --dport $udp -j ACCEPT 2>/dev/null && echo "${'$'}ipt: открыт UDP $udp"
          fi
        done
        if command -v netfilter-persistent >/dev/null 2>&1; then netfilter-persistent save >/dev/null 2>&1 || true; fi
        systemctl daemon-reload
        systemctl enable masterdns-server
        systemctl restart masterdns-server
        sleep 3
        # is-active is true even for a crash loop between restarts — check the socket itself.
        if ! ss -Hulpn "sport = :$udp" 2>/dev/null | grep -q masterdns; then
          echo "ОШИБКА: сервер не слушает UDP-порт $udp. Журнал:"
          journalctl -u masterdns-server --no-pager -n 12 -o cat 2>/dev/null || true
          exit 1
        fi
        echo "Служба masterdns-server слушает UDP-порт $udp"
        echo "MASTERDNS_KEY=${'$'}(cat /etc/masterdns/encrypt_key.txt)"
    """.trimIndent()
}
