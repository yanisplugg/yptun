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
 * provisions a persistent WireGuard exit (wg-quick + NAT) as the `-connect` backend and launches the
 * server as a systemd service. The script generates the WireGuard keypair + obf key server-side and
 * echoes them back on a `RESULT::` line so the app can compose the client `freeturn://` link. Mirrors
 * [org.olcbox.app.vpn.wdtt.AndroidWdttServerInstaller]; binaries live in assets/freeturn/.
 */
internal class AndroidFreeturnServerInstaller(private val context: Context) : FreeturnServerInstaller {

    override suspend fun install(
        options: FreeturnInstallOptions,
        onLog: (String) -> Unit
    ): Result<FreeturnInstallResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(options.host.isNotBlank()) { "Не указан IP/хост VPS" }
            require(options.sshPassword.isNotBlank()) { "Не указан пароль SSH" }
            require(options.freeturnPort in 1..65535) { "Некорректный порт freeturn" }

            // This VPS resets the link the moment a 2nd channel is opened on a connection, so EVERY
            // step is its own fresh connection running one command (same as the WDTT installer).
            val target = SshTarget(options.host, options.sshPort, options.login, options.sshPassword)

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
            onLog("Бинарник загружен, ставлю WireGuard + службу…")

            val output = sshOneShot(target, buildInstallScript(options), onLog)
            output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("RESULT::") }
                .forEach(onLog)

            val result = parseResult(output, options.freeturnPort)
                ?: error("Установка завершилась, но сервер не вернул ключи (RESULT). См. лог выше.")
            onLog("Готово: freeturn-server на ${options.host}:${options.freeturnPort}")
            result
        }
    }

    /** Pulls the `RESULT::<obfKey>|<serverWgPub>|<clientWgPriv>|<clientAddr>` line out of the output. */
    private fun parseResult(output: String, port: Int): FreeturnInstallResult? {
        val line = output.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("RESULT::") } ?: return null
        val parts = line.removePrefix("RESULT::").split('|')
        if (parts.size < 4) return null
        val (key, serverPub, clientPriv, clientAddr) = parts
        if (key.isBlank() || serverPub.isBlank() || clientPriv.isBlank()) return null
        return FreeturnInstallResult(
            obfKey = key,
            serverWgPublicKey = serverPub,
            clientWgPrivateKey = clientPriv,
            clientWgAddress = clientAddr.ifBlank { "10.7.1.2/32" },
            freeturnPort = port,
            status = "freeturn-server установлен и запущен на :$port",
        )
    }

    private companion object {
        const val REMOTE_GZ = "/tmp/freeturn-server.gz"
    }
}

/**
 * The remote install script (ONE shell command). It is **idempotent and port-scoped**: the systemd
 * unit (`freeturn-server-<port>`) and the WireGuard exit (`ftwg<port>`, in its own `10.7.N.0/24` with
 * a per-port ListenPort) are keyed on the freeturn port. If that exact service is already installed on
 * this port it is fully torn down (service + wg interface + config + any stray process on the UDP
 * port) and reinstalled from scratch with the new keys/params — so re-running the installer always
 * yields a clean server, and different ports can coexist on one VPS (for same-VPS multi-server).
 *
 * Steps: install the binary, ensure wireguard-tools, tear down any prior install on this port (and a
 * legacy single `freeturn-server`/`ftwg` install if it was bound to this port), bring up the per-port
 * WireGuard exit with NAT/forwarding via wg-quick, generate the server+client keypair + obf key, then
 * write + start the freeturn-server unit (`-connect 127.0.0.1:<wgport> -mode udp`). It echoes a
 * `RESULT::` line carrying the keys + the client WG address so the app can build the freeturn:// link.
 *
 * Only the numeric port and the (validated) obf-profile are interpolated from Kotlin; everything else
 * is generated server-side, so no untrusted value reaches the shell.
 */
internal fun buildInstallScript(options: FreeturnInstallOptions): String {
    val port = options.freeturnPort
    // Restrict the profile to a known-safe token set (it lands bare in the unit file).
    val prof = options.obfProfile.lowercase().filter { it.isLetterOrDigit() }.ifBlank { "rtpopus" }
    return """
        set -e
        gunzip -f /tmp/freeturn-server.gz
        install -m 0755 /tmp/freeturn-server /usr/local/bin/freeturn-server
        rm -f /tmp/freeturn-server
        command -v wg >/dev/null 2>&1 || { apt-get update -y && apt-get install -y wireguard-tools; }
        mkdir -p /etc/wireguard
        wan=${'$'}(ip route show default 2>/dev/null | awk '/default/ {print ${'$'}5; exit}')
        [ -n "${'$'}wan" ] || wan=eth0
        port=$port
        svc=freeturn-server-${'$'}port
        iface=ftwg${'$'}port
        # Per-port private subnet + wg ListenPort so multiple freeturn servers can coexist on one VPS.
        net=${'$'}(( port % 250 + 1 ))
        wgport=${'$'}(( 51820 + net ))
        # --- Idempotent teardown: if THIS service is already installed on THIS port, remove it and
        #     reinstall from scratch with the new parameters (requirement: reinstall, don't stack). ---
        if systemctl list-unit-files 2>/dev/null | grep -q "^${'$'}svc.service" || [ -f /etc/systemd/system/${'$'}svc.service ]; then
          echo "Обнаружена служба ${'$'}svc — удаляю и переустанавливаю с новыми параметрами"
        fi
        systemctl disable --now ${'$'}svc 2>/dev/null || true
        rm -f /etc/systemd/system/${'$'}svc.service
        systemctl stop wg-quick@${'$'}iface 2>/dev/null || true
        wg-quick down ${'$'}iface 2>/dev/null || true
        rm -f /etc/wireguard/${'$'}iface.conf
        # Legacy single-server install (old script used the fixed names freeturn-server/ftwg) — tear it
        # down too, but ONLY if it was bound to THIS port, so a different-port legacy server survives.
        if [ -f /etc/systemd/system/freeturn-server.service ] && grep -q "0.0.0.0:${'$'}port " /etc/systemd/system/freeturn-server.service; then
          systemctl disable --now freeturn-server 2>/dev/null || true
          rm -f /etc/systemd/system/freeturn-server.service
          systemctl stop wg-quick@ftwg 2>/dev/null || true; wg-quick down ftwg 2>/dev/null || true
          rm -f /etc/wireguard/ftwg.conf
        fi
        # Free the UDP port in case a stray process still holds it.
        command -v fuser >/dev/null 2>&1 && fuser -k ${'$'}port/udp 2>/dev/null || true
        systemctl daemon-reload
        spriv=${'$'}(wg genkey); spub=${'$'}(printf '%s' "${'$'}spriv" | wg pubkey)
        cpriv=${'$'}(wg genkey); cpub=${'$'}(printf '%s' "${'$'}cpriv" | wg pubkey)
        umask 077
        cat > /etc/wireguard/${'$'}iface.conf <<EOF
        [Interface]
        Address = 10.7.${'$'}net.1/24
        ListenPort = ${'$'}wgport
        PrivateKey = ${'$'}spriv
        PostUp = sysctl -w net.ipv4.ip_forward=1
        PostUp = iptables -t nat -A POSTROUTING -s 10.7.${'$'}net.0/24 -o ${'$'}wan -j MASQUERADE
        PostUp = iptables -A FORWARD -i ${'$'}iface -j ACCEPT
        PostUp = iptables -A FORWARD -o ${'$'}iface -j ACCEPT
        PostDown = iptables -t nat -D POSTROUTING -s 10.7.${'$'}net.0/24 -o ${'$'}wan -j MASQUERADE
        PostDown = iptables -D FORWARD -i ${'$'}iface -j ACCEPT
        PostDown = iptables -D FORWARD -o ${'$'}iface -j ACCEPT

        [Peer]
        PublicKey = ${'$'}cpub
        AllowedIPs = 10.7.${'$'}net.2/32
        EOF
        systemctl enable --now wg-quick@${'$'}iface
        key=${'$'}(/usr/local/bin/freeturn-server -gen-obf-key | tr -d '\r\n ')
        cat > /etc/systemd/system/${'$'}svc.service <<UNIT
        [Unit]
        Description=Free Turn Proxy Server (port $port)
        After=network-online.target wg-quick@${'$'}iface.service
        Wants=network-online.target
        [Service]
        ExecStart=/usr/local/bin/freeturn-server -listen 0.0.0.0:$port -connect 127.0.0.1:${'$'}wgport -mode udp -obf-profile $prof -obf-key ${'$'}key
        Restart=always
        RestartSec=3
        LimitNOFILE=1048576
        [Install]
        WantedBy=multi-user.target
        UNIT
        if command -v ufw >/dev/null 2>&1; then ufw allow $port/udp || true; fi
        if command -v firewall-cmd >/dev/null 2>&1; then firewall-cmd --add-port=$port/udp --permanent && firewall-cmd --reload || true; fi
        systemctl daemon-reload
        systemctl enable --now ${'$'}svc
        sleep 1
        systemctl is-active ${'$'}svc >/dev/null && echo "Служба ${'$'}svc активна на порту $port (wg ${'$'}iface 10.7.${'$'}net.0/24:${'$'}wgport, NAT->${'$'}wan)"
        echo "RESULT::${'$'}key|${'$'}spub|${'$'}cpriv|10.7.${'$'}net.2/32"
    """.trimIndent()
}
