package org.olcbox.app.vpn.freeturn

import androidx.compose.runtime.Composable

/**
 * Inputs for the one-tap free-turn-proxy server install on a VPS. SSH access (host/login/password)
 * plus the freeturn public listener port the server binds. The installer also provisions a local
 * WireGuard server (the freeturn `-connect` backend) and hands back the keys needed to build the
 * client `freeturn://` link — mirroring what the Flask panel does (`_panel/server.py`).
 */
enum class FreeturnExit {
    /** Ядерный WireGuard через wg-quick — как было. */
    WireGuard,

    /**
     * AmneziaWG: userspace amneziawg-go + случайные параметры обфускации (Jc/Jmin/Jmax/S1/S2/H1-H4).
     * Прячет уже САМ туннель внутри TURN-релея: даже если VK/DPI разберёт релей, внутри лежит не
     * узнаваемый WireGuard-хендшейк. Ставится вторым бинарником, ядерный модуль не нужен.
     */
    AmneziaWG
}

/** Параметры обфускации AmneziaWG, сгенерированные на VPS; должны совпадать с клиентом. */
data class FreeturnAwgParams(
    val jc: String,
    val jmin: String,
    val jmax: String,
    val s1: String,
    val s2: String,
    val h1: String,
    val h2: String,
    val h3: String,
    val h4: String
)

data class FreeturnInstallOptions(
    val host: String,
    val sshPort: Int = 22,
    val login: String = "root",
    val sshPassword: String = "",
    /** PEM/OpenSSH private key for SSH publickey auth; when set it is used instead of [sshPassword]. */
    val sshKey: String = "",
    /** Passphrase for an encrypted [sshKey]; empty for an unencrypted key. */
    val sshKeyPassphrase: String = "",
    /** Public UDP port the free-turn-proxy server binds (the freeturn:// peer port; default 56000). */
    val freeturnPort: Int = 56000,
    /** Wire obfuscation profile the server runs with (must match the client). */
    val obfProfile: String = "rtpopus",
    /** DNS handed to the client in the generated WireGuard config. */
    val dns: String = "1.1.1.1",
    /** Каким делать выход на VPS: обычный WireGuard или AmneziaWG с обфускацией. */
    val exit: FreeturnExit = FreeturnExit.WireGuard,
)

/**
 * The artefacts the freeturn install produces, used to build the client `freeturn://` link: the
 * obfuscation key + the WireGuard keypair halves the client needs (server public key, client
 * private key, client tunnel address) and the public listener port. [status] is a human summary.
 */
data class FreeturnInstallResult(
    val obfKey: String,
    val serverWgPublicKey: String,
    val clientWgPrivateKey: String,
    /** Client tunnel address, e.g. `10.7.3.2/32`. */
    val clientWgAddress: String,
    val freeturnPort: Int,
    val status: String,
    /** Заполнено только для [FreeturnExit.AmneziaWG]; для обычного WireGuard — null. */
    val awg: FreeturnAwgParams? = null,
)

/**
 * Installs (or upgrades) the free-turn-proxy server on a remote VPS over SSH: detects the
 * architecture, uploads the bundled server binary, provisions a persistent WireGuard exit
 * (wg-quick + NAT) as the `-connect` backend, then runs the server as a systemd service. The
 * generated WireGuard client keys + obf key come back in [FreeturnInstallResult] so the app can
 * compose the `freeturn://` link. Implemented per platform — only Android ships a real
 * implementation (SSH client + bundled binary asset).
 */
interface FreeturnServerInstaller {
    /**
     * Runs the full install, streaming human-readable progress through [onLog]. Returns the
     * artefacts on success, or a [Result.failure] carrying the SSH/install error.
     */
    suspend fun install(options: FreeturnInstallOptions, onLog: (String) -> Unit): Result<FreeturnInstallResult>

    /**
     * Сносит с VPS то, что поставил [install] на порту [FreeturnInstallOptions.freeturnPort]: службу,
     * туннельный выход (любой из двух), конфиги и правило фаервола. Из настроек нужны только адрес,
     * доступ по SSH и порт. Реализация по умолчанию отвечает отказом — платформы без SSH-клиента
     * удалять тоже не умеют.
     */
    suspend fun uninstall(options: FreeturnInstallOptions, onLog: (String) -> Unit): Result<String> =
        Result.failure(UnsupportedOperationException("Удаление freeturn-сервера доступно только в Android-приложении"))
}

/**
 * Platform factory for the [FreeturnServerInstaller]. Android returns a real SSH-based installer;
 * other platforms return one that fails with an "Android only" message (the feature targets the
 * Android client).
 */
@Composable
expect fun rememberFreeturnServerInstaller(): FreeturnServerInstaller

/**
 * The remote install script (ONE shell command). It is **idempotent and port-scoped**: the systemd
 * unit (`freeturn-server-<port>`) and the tunnel exit (`ftwg<port>` for WireGuard, `ftawg<port>` for
 * AmneziaWG, each in its own `10.7.N.0/24` with a per-port listen port) are keyed on the freeturn
 * port. If that exact service is already installed on this port it is fully torn down (service +
 * interface + config + any stray process on the UDP port) and reinstalled from scratch — so
 * re-running the installer always yields a clean server, different ports can coexist on one VPS (for
 * same-VPS multi-server), and switching a port between WireGuard and AmneziaWG removes the other
 * backend instead of leaving it running.
 *
 * Подсеть и порт бэкенда считаются из порта freeturn здесь, в Kotlin (`net`, `wgPort`), а не в шелле:
 * это чистая арифметика по уже проверенному Int, и так AmneziaWG-ветка (которая сама пишет
 * runner-скрипт) остаётся читаемой. Формула та же, что была в шелле, номера у существующих установок
 * не меняются.
 *
 * Из Kotlin подставляются только числа и проверенный токен obf-профиля; ключи и параметры обфускации
 * генерируются на VPS, так что в шелл не попадает ни одного недоверенного значения.
 */
internal fun buildInstallScript(options: FreeturnInstallOptions): String {
    val port = options.freeturnPort
    // Restrict the profile to a known-safe token set (it lands bare in the unit file).
    val prof = options.obfProfile.lowercase().filter { it.isLetterOrDigit() }.ifBlank { "rtpopus" }
    val net = port % 250 + 1
    val wgPort = 51820 + net
    val awg = options.exit == FreeturnExit.AmneziaWG
    val d = "$" // одиночный доллар shell-переменной внутри raw string

    val backend = if (awg) awgBackend(port, net, wgPort) else wgBackend(port, net, wgPort)
    val resultTail =
        if (awg) "|${d}jc,${d}jmin,${d}jmax,${d}s1,${d}s2,${d}h1,${d}h2,${d}h3,${d}h4" else ""
    val exitLabel = if (awg) "AmneziaWG ftawg$port" else "WireGuard ftwg$port"

    val preamble = """
        set -e
        gunzip -f /tmp/freeturn-server.gz
        install -m 0755 /tmp/freeturn-server /usr/local/bin/freeturn-server
        rm -f /tmp/freeturn-server
        mkdir -p /etc/wireguard
        wan=${d}(ip route show default 2>/dev/null | awk '/default/ {print ${d}5; exit}')
        [ -n "${d}wan" ] || wan=eth0
        port=$port
        svc=freeturn-server-${d}port
    """.trimIndent()

    val tail = """
        key=${d}(/usr/local/bin/freeturn-server -gen-obf-key | tr -d '\r\n ')
        cat > /etc/systemd/system/${d}svc.service <<UNIT
        [Unit]
        Description=Free Turn Proxy Server (port $port)
        After=network-online.target
        Wants=network-online.target
        [Service]
        ExecStart=/usr/local/bin/freeturn-server -listen 0.0.0.0:$port -connect 127.0.0.1:$wgPort -mode udp -obf-profile $prof -obf-key ${d}key
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
        systemctl is-active ${d}svc >/dev/null && echo "Служба ${d}svc активна на порту $port (выход $exitLabel 10.7.$net.0/24:$wgPort, NAT->${d}wan)"
        echo "RESULT::${d}key|${d}spub|${d}cpriv|10.7.$net.2/32$resultTail"
    """.trimIndent()

    return listOf(preamble, teardownBlock(port), backend, tail).joinToString("\n")
}

/**
 * Снос всего, что установщик мог оставить на ЭТОМ порту: служба freeturn, оба возможных бэкенда
 * (ядерный `ftwg<port>` и userspace `ftawg<port>`), их конфиги и занятый UDP-порт. Ожидает, что
 * вызывающий уже задал shell-переменные `port` и `svc`.
 *
 * Один и тот же блок используется и переустановкой (поэтому она идемпотентна и переживает смену типа
 * выхода), и удалением — чтобы эти два пути не разъезжались.
 */
internal fun teardownBlock(port: Int): String {
    val d = "$"
    return """
        # --- Idempotent teardown: снимаем службу и ОБА возможных бэкенда этого порта, чтобы
        #     переустановка (в том числе со сменой типа выхода) не оставила старый туннель жить. ---
        if systemctl list-unit-files 2>/dev/null | grep -q "^${d}svc.service" || [ -f /etc/systemd/system/${d}svc.service ]; then
          echo "Найдена служба ${d}svc — останавливаю и убираю её"
        fi
        systemctl disable --now ${d}svc 2>/dev/null || true
        rm -f /etc/systemd/system/${d}svc.service
        systemctl stop wg-quick@ftwg$port 2>/dev/null || true
        wg-quick down ftwg$port 2>/dev/null || true
        rm -f /etc/wireguard/ftwg$port.conf
        systemctl disable --now ftawg$port 2>/dev/null || true
        rm -f /etc/systemd/system/ftawg$port.service /usr/local/bin/ftawg$port.sh /etc/amneziawg/ftawg$port.uapi
        ip link del ftawg$port 2>/dev/null || true
        # Legacy single-server install (old script used the fixed names freeturn-server/ftwg) — tear it
        # down too, but ONLY if it was bound to THIS port, so a different-port legacy server survives.
        if [ -f /etc/systemd/system/freeturn-server.service ] && grep -q "0.0.0.0:${d}port " /etc/systemd/system/freeturn-server.service; then
          systemctl disable --now freeturn-server 2>/dev/null || true
          rm -f /etc/systemd/system/freeturn-server.service
          systemctl stop wg-quick@ftwg 2>/dev/null || true; wg-quick down ftwg 2>/dev/null || true
          rm -f /etc/wireguard/ftwg.conf
        fi
        # Free the UDP port in case a stray process still holds it.
        command -v fuser >/dev/null 2>&1 && fuser -k ${d}port/udp 2>/dev/null || true
        systemctl daemon-reload
    """.trimIndent()
}

/**
 * Полное удаление freeturn с VPS: тот же teardown плюс правила фаервола и — если на машине не
 * осталось НИ ОДНОЙ службы freeturn — общие бинарники. Бинарники общие для всех портов, поэтому
 * снести их безусловно значило бы убить соседние серверы на том же VPS.
 */
internal fun buildUninstallScript(port: Int): String {
    val d = "$"
    val head = """
        set -e
        port=$port
        svc=freeturn-server-${d}port
    """.trimIndent()
    val tail = """
        if command -v ufw >/dev/null 2>&1; then ufw delete allow $port/udp || true; fi
        if command -v firewall-cmd >/dev/null 2>&1; then firewall-cmd --remove-port=$port/udp --permanent && firewall-cmd --reload || true; fi
        rmdir /etc/amneziawg 2>/dev/null || true
        # Бинарники общие на все порты — сносим, только если других служб freeturn не осталось.
        if ! ls /etc/systemd/system/freeturn-server-*.service >/dev/null 2>&1; then
          rm -f /usr/local/bin/freeturn-server /usr/local/bin/amneziawg-go
          echo "Других серверов freeturn не осталось — удалил и бинарники"
        else
          echo "На VPS остались другие серверы freeturn — общие бинарники не трогаю"
        fi
        systemctl daemon-reload
        echo "REMOVED::$port"
    """.trimIndent()
    return listOf(head, teardownBlock(port), tail).joinToString("\n")
}

/** Ядерный WireGuard через wg-quick — исходный, проверенный путь. */
private fun wgBackend(port: Int, net: Int, wgPort: Int): String {
    val d = "$"
    return """
        command -v wg >/dev/null 2>&1 || { apt-get update -y && apt-get install -y wireguard-tools; }
        iface=ftwg$port
        spriv=${d}(wg genkey); spub=${d}(printf '%s' "${d}spriv" | wg pubkey)
        cpriv=${d}(wg genkey); cpub=${d}(printf '%s' "${d}cpriv" | wg pubkey)
        umask 077
        cat > /etc/wireguard/${d}iface.conf <<EOF
        [Interface]
        Address = 10.7.$net.1/24
        ListenPort = $wgPort
        PrivateKey = ${d}spriv
        PostUp = sysctl -w net.ipv4.ip_forward=1
        PostUp = iptables -t nat -A POSTROUTING -s 10.7.$net.0/24 -o ${d}wan -j MASQUERADE
        PostUp = iptables -A FORWARD -i ${d}iface -j ACCEPT
        PostUp = iptables -A FORWARD -o ${d}iface -j ACCEPT
        PostDown = iptables -t nat -D POSTROUTING -s 10.7.$net.0/24 -o ${d}wan -j MASQUERADE
        PostDown = iptables -D FORWARD -i ${d}iface -j ACCEPT
        PostDown = iptables -D FORWARD -o ${d}iface -j ACCEPT

        [Peer]
        PublicKey = ${d}cpub
        AllowedIPs = 10.7.$net.2/32
        EOF
        systemctl enable --now wg-quick@${d}iface
    """.trimIndent()
}

/**
 * AmneziaWG-выход: userspace amneziawg-go + случайные параметры обфускации.
 *
 * Ядерный модуль и awg-tools не нужны. Ключи и параметры уезжают в устройство через UAPI-сокет
 * (`/var/run/amneziawg/<iface>.sock`): обычный `wg setconf` для этого не годится — он не знает полей
 * jc/s1/h1 и отвергает их, а без них внутри TURN-релея поедет обычный, узнаваемый WireGuard. Адрес,
 * поднятие интерфейса и NAT делаем руками: wg-quick тоже умеет только ядерный интерфейс.
 *
 * `S1 + 56 != S2` — требование AmneziaWG (иначе init- и response-пакеты становятся неразличимы);
 * H1..H4 берём подряд от случайной базы — они обязаны быть различны и не равны 1..4 обычного WG.
 */
private fun awgBackend(port: Int, net: Int, wgPort: Int): String {
    val d = "$"
    return """
        gunzip -f /tmp/amneziawg-go.gz
        install -m 0755 /tmp/amneziawg-go /usr/local/bin/amneziawg-go
        rm -f /tmp/amneziawg-go
        command -v wg >/dev/null 2>&1 || { apt-get update -y && apt-get install -y wireguard-tools; }
        command -v socat >/dev/null 2>&1 || { apt-get update -y && apt-get install -y socat; }
        [ -c /dev/net/tun ] || { echo "На VPS нет /dev/net/tun — AmneziaWG-выход не поднять (OpenVZ/LXC без TUN)"; exit 1; }
        iface=ftawg$port
        rnd() { od -An -N4 -tu4 /dev/urandom | tr -d ' \n'; }
        jc=${d}(( ${d}(rnd) % 6 + 3 )); jmin=40; jmax=70
        s1=${d}(( ${d}(rnd) % 50 + 15 )); s2=${d}(( ${d}(rnd) % 50 + 15 ))
        # `[ ... ] && ...` под `set -e` роняет скрипт, когда условие ЛОЖНО, а это обычный случай.
        if [ ${d}(( ${d}s1 + 56 )) -eq "${d}s2" ]; then s2=${d}(( ${d}s2 + 1 )); fi
        hb=${d}(( ${d}(rnd) % 1000000000 + 5 ))
        h1=${d}hb; h2=${d}(( ${d}hb + 1 )); h3=${d}(( ${d}hb + 2 )); h4=${d}(( ${d}hb + 3 ))
        spriv=${d}(wg genkey); spub=${d}(printf '%s' "${d}spriv" | wg pubkey)
        cpriv=${d}(wg genkey); cpub=${d}(printf '%s' "${d}cpriv" | wg pubkey)
        # UAPI принимает ключи в hex, а wg genkey печатает base64.
        b64hex() { printf '%s' "${d}1" | base64 -d | od -An -tx1 | tr -d ' \n'; }
        umask 077
        mkdir -p /etc/amneziawg
        printf 'set=1\nprivate_key=%s\nlisten_port=%s\njc=%s\njmin=%s\njmax=%s\ns1=%s\ns2=%s\nh1=%s\nh2=%s\nh3=%s\nh4=%s\nreplace_peers=true\npublic_key=%s\nallowed_ip=10.7.$net.2/32\n\n' "${d}(b64hex "${d}spriv")" "$wgPort" "${d}jc" "${d}jmin" "${d}jmax" "${d}s1" "${d}s2" "${d}h1" "${d}h2" "${d}h3" "${d}h4" "${d}(b64hex "${d}cpub")" > /etc/amneziawg/${d}iface.uapi
        cat > /usr/local/bin/${d}iface.sh <<RUNNER
        #!/bin/sh
        set -e
        mkdir -p /var/run/amneziawg
        rm -f /var/run/amneziawg/${d}iface.sock
        WG_PROCESS_FOREGROUND=1 /usr/local/bin/amneziawg-go -f ${d}iface &
        pid=\${d}!
        i=0
        while [ ! -S /var/run/amneziawg/${d}iface.sock ] && [ \${d}i -lt 50 ]; do sleep 0.2; i=\${d}(( \${d}i + 1 )); done
        socat - UNIX-CONNECT:/var/run/amneziawg/${d}iface.sock < /etc/amneziawg/${d}iface.uapi
        ip addr add 10.7.$net.1/24 dev ${d}iface 2>/dev/null || true
        # 1280 - безопасный MTU для WireGuard поверх TURN (столько же берёт сам freeturn в своём
        # встроенном туннеле): иначе полноразмерный кадр режется по дороге и теряется.
        ip link set ${d}iface mtu 1280
        ip link set ${d}iface up
        sysctl -w net.ipv4.ip_forward=1 >/dev/null
        wan=\${d}(ip route show default 2>/dev/null | awk '/default/ {print \${d}5; exit}')
        [ -n "\${d}wan" ] || wan=eth0
        iptables -t nat -C POSTROUTING -s 10.7.$net.0/24 -o \${d}wan -j MASQUERADE 2>/dev/null || iptables -t nat -A POSTROUTING -s 10.7.$net.0/24 -o \${d}wan -j MASQUERADE
        iptables -C FORWARD -i ${d}iface -j ACCEPT 2>/dev/null || iptables -A FORWARD -i ${d}iface -j ACCEPT
        iptables -C FORWARD -o ${d}iface -j ACCEPT 2>/dev/null || iptables -A FORWARD -o ${d}iface -j ACCEPT
        wait \${d}pid
        RUNNER
        chmod +x /usr/local/bin/${d}iface.sh
        cat > /etc/systemd/system/${d}iface.service <<AWGUNIT
        [Unit]
        Description=AmneziaWG exit for free-turn-proxy (port $port)
        After=network-online.target
        Wants=network-online.target
        [Service]
        ExecStart=/usr/local/bin/${d}iface.sh
        Restart=always
        RestartSec=3
        [Install]
        WantedBy=multi-user.target
        AWGUNIT
        systemctl daemon-reload
        systemctl enable --now ${d}iface
        sleep 1
        systemctl is-active ${d}iface >/dev/null || { echo "AmneziaWG-интерфейс ${d}iface не поднялся"; journalctl -u ${d}iface -n 20 --no-pager 2>/dev/null || true; exit 1; }
        # Спрашиваем у самого устройства, что в него доехало: пустой ответ = UAPI-конфиг не применился,
        # и туннель будет молча не работать (сокет есть, ключей и обфускации нет).
        awgstate=${d}(printf 'get=1\n\n' | socat - UNIX-CONNECT:/var/run/amneziawg/${d}iface.sock 2>/dev/null || true)
        echo "${d}awgstate" | grep -q '^listen_port=' || { echo "AmneziaWG: устройство не приняло конфиг (UAPI пуст)"; exit 1; }
        echo "AmneziaWG ${d}iface: ${d}(echo "${d}awgstate" | grep -c '^public_key=') пир(ов), listen_port=${d}(echo "${d}awgstate" | sed -n 's/^listen_port=//p'), jc=${d}jc s1=${d}s1 s2=${d}s2"
    """.trimIndent()
}
