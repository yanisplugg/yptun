package org.olcbox.app.vpn.turnable

import androidx.compose.runtime.Composable

data class TurnableInstallOptions(
    val host: String,
    val sshPort: Int = 22,
    val login: String = "root",
    val sshPassword: String = "",
    val sshKey: String = "",
    val sshKeyPassphrase: String = "",
    val turnablePort: Int = 56000,
    /** VK call/conversation id accepted by Turnable's vk.com platform handler. */
    val callId: String,
    val peers: Int = 5,
    val dns: String = "1.1.1.1",
)

data class TurnableInstallResult(
    val clientConfig: String,
    val serverWgPublicKey: String,
    val clientWgPrivateKey: String,
    val clientWgAddress: String,
    val turnablePort: Int,
    val status: String,
)

interface TurnableServerInstaller {
    suspend fun install(options: TurnableInstallOptions, onLog: (String) -> Unit): Result<TurnableInstallResult>

    suspend fun uninstall(options: TurnableInstallOptions, onLog: (String) -> Unit): Result<String> =
        Result.failure(UnsupportedOperationException("Удаление Turnable доступно только в Android-приложении"))
}

/** Android has the real SSH installer; desktop/iOS stubs keep their builds independent. */
@Composable
expect fun rememberTurnableServerInstaller(): TurnableServerInstaller

/** Idempotent, port-scoped Turnable + WireGuard server deployment. */
internal fun buildTurnableInstallScript(options: TurnableInstallOptions): String {
    require(options.turnablePort in 1024..65535) { "Некорректный порт Turnable" }
    val host = options.host.trim()
    require(host.length in 1..253 && host.all { char ->
        char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in ".:[]-"
    }) { "Некорректный IP/хост VPS" }
    val callId = options.callId.trim()
    require(callId.matches(Regex("[A-Za-z0-9_-]{3,256}"))) {
        "Некорректный VK call id (допустимы буквы, цифры, _ и -)"
    }
    val dns = options.dns.trim().filter { it.isLetterOrDigit() || it in ".:-,[]" }.ifBlank { "1.1.1.1" }
    val peers = options.peers.coerceIn(1, 32)
    val port = options.turnablePort
    val net = port % 250 + 1
    val wgPort = 51820 + net
    val d = "$"
    return """
        set -e
        gunzip -f /tmp/turnable.gz
        install -m 0755 /tmp/turnable /usr/local/bin/turnable
        rm -f /tmp/turnable
        command -v wg >/dev/null 2>&1 || { apt-get update -y && apt-get install -y wireguard-tools; }
        mkdir -p /etc/turnable /etc/wireguard
        port=$port
        svc=turnable-${d}port
        iface=trwg${d}port
        cfg=/etc/turnable/${d}port.json
        wan=${d}(ip route show default 2>/dev/null | awk '/default/ {print ${d}5; exit}')
        [ -n "${d}wan" ] || wan=eth0

        systemctl disable --now ${d}svc 2>/dev/null || true
        rm -f /etc/systemd/system/${d}svc.service
        systemctl stop wg-quick@${d}iface 2>/dev/null || true
        wg-quick down ${d}iface 2>/dev/null || true
        rm -f /etc/wireguard/${d}iface.conf "${d}cfg"
        command -v fuser >/dev/null 2>&1 && fuser -k ${d}port/udp 2>/dev/null || true

        spriv=${d}(wg genkey); spub=${d}(printf '%s' "${d}spriv" | wg pubkey)
        cpriv=${d}(wg genkey); cpub=${d}(printf '%s' "${d}cpriv" | wg pubkey)
        umask 077
        cat > /etc/wireguard/${d}iface.conf <<WGEOF
        [Interface]
        Address = 10.9.$net.1/24
        ListenPort = $wgPort
        PrivateKey = ${d}spriv
        PostUp = sysctl -w net.ipv4.ip_forward=1
        PostUp = iptables -t nat -A POSTROUTING -s 10.9.$net.0/24 -o ${d}wan -j MASQUERADE
        PostUp = iptables -A FORWARD -i ${d}iface -j ACCEPT
        PostUp = iptables -A FORWARD -o ${d}iface -j ACCEPT
        PostDown = iptables -t nat -D POSTROUTING -s 10.9.$net.0/24 -o ${d}wan -j MASQUERADE
        PostDown = iptables -D FORWARD -i ${d}iface -j ACCEPT
        PostDown = iptables -D FORWARD -o ${d}iface -j ACCEPT

        [Peer]
        PublicKey = ${d}cpub
        AllowedIPs = 10.9.$net.2/32
        WGEOF
        systemctl enable --now wg-quick@${d}iface

        keys=${d}(/usr/local/bin/turnable config keygen --json)
        tpriv=${d}(printf '%s' "${d}keys" | sed -n 's/.*"priv_key":"\([^"]*\)".*/\1/p')
        tpub=${d}(printf '%s' "${d}keys" | sed -n 's/.*"pub_key":"\([^"]*\)".*/\1/p')
        uuid=${d}(cat /proc/sys/kernel/random/uuid)
        [ -n "${d}tpriv" ] && [ -n "${d}tpub" ] && [ -n "${d}uuid" ]
        cat > "${d}cfg" <<JSONEOF
        {
          "servers": {"main": {
            "type":"relay", "platform_id":"vk.com", "call_id":"$callId",
            "pub_key":"${d}tpub", "priv_key":"${d}tpriv", "proto":"dtls",
            "listen_addr":"0.0.0.0:$port", "public_ip":"$host", "cloak":"none",
            "provider":"provider_main"
          }},
          "providers": {"provider_main": {"type":"raw",
            "routes":[{"id":"wireguard", "address":"127.0.0.1", "port":$wgPort,
              "socket":"udp", "transport":"mpath", "encryption":"handshake", "name":"WireGuard"}],
            "users":[{"uuid":"${d}uuid", "allowed_routes":["wireguard"],
              "type":"relay", "peers":$peers}]
          }}
        }
        JSONEOF

        cat > /etc/systemd/system/${d}svc.service <<UNIT
        [Unit]
        Description=Turnable relay server (port $port)
        After=network-online.target wg-quick@${d}iface.service
        Wants=network-online.target
        Requires=wg-quick@${d}iface.service
        [Service]
        Type=simple
        WorkingDirectory=/etc/turnable
        ExecStart=/usr/local/bin/turnable server -c ${d}cfg
        Restart=always
        RestartSec=3
        LimitNOFILE=1048576
        [Install]
        WantedBy=multi-user.target
        UNIT
        if command -v ufw >/dev/null 2>&1; then ufw allow $port/udp || true; fi
        if command -v firewall-cmd >/dev/null 2>&1; then firewall-cmd --add-port=$port/udp --permanent && firewall-cmd --reload || true; fi
        systemctl daemon-reload
        systemctl enable --now ${d}svc
        sleep 1
        systemctl is-active ${d}svc >/dev/null
        client=${d}(/usr/local/bin/turnable config generate main "${d}uuid" wireguard -c "${d}cfg" | tail -n1)
        [ -n "${d}client" ]
        client64=${d}(printf '%s' "${d}client" | base64 | tr -d '\r\n')
        echo "Turnable ${d}svc активен на :$port; WireGuard 10.9.$net.0/24 -> ${d}wan"
        echo "RESULT::${d}client64|${d}spub|${d}cpriv|10.9.$net.2/32"
    """.trimIndent()
}

internal fun buildTurnableUninstallScript(port: Int): String {
    require(port in 1024..65535)
    val d = "$"
    return """
        set -e
        port=$port
        svc=turnable-${d}port
        iface=trwg${d}port
        systemctl disable --now ${d}svc 2>/dev/null || true
        rm -f /etc/systemd/system/${d}svc.service /etc/turnable/${d}port.json
        systemctl stop wg-quick@${d}iface 2>/dev/null || true
        wg-quick down ${d}iface 2>/dev/null || true
        rm -f /etc/wireguard/${d}iface.conf
        command -v fuser >/dev/null 2>&1 && fuser -k ${d}port/udp 2>/dev/null || true
        if command -v ufw >/dev/null 2>&1; then ufw delete allow ${d}port/udp || true; fi
        if command -v firewall-cmd >/dev/null 2>&1; then firewall-cmd --remove-port=${d}port/udp --permanent && firewall-cmd --reload || true; fi
        if ! ls /etc/systemd/system/turnable-*.service >/dev/null 2>&1; then
          rm -f /usr/local/bin/turnable
          rmdir /etc/turnable 2>/dev/null || true
        fi
        systemctl daemon-reload
        echo "REMOVED::${d}port"
    """.trimIndent()
}
