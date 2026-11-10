//! free-turn-proxy exit node installer, ported from `FreeturnServerInstaller.kt` and
//! `SshFreeturnServerInstaller.kt`.
//!
//! Detects the VPS architecture, uploads the bundled server binary, then runs ONE install script
//! that provisions the tunnel exit (kernel WireGuard via wg-quick, or userspace AmneziaWG with
//! random obfuscation) as the server's `-connect` backend and starts the server as a systemd
//! service. Keys, the obf key and the AmneziaWG parameters are generated ON the VPS and echoed back
//! on a `RESULT::` line, so no untrusted value ever lands in the shell - only checked numbers and
//! the filtered obf-profile token are interpolated.
//!
//! The script is **idempotent and port-scoped**: the unit (`freeturn-server-<port>`) and the exit
//! (`ftwg<port>` / `ftawg<port>`, each in its own `10.7.N.0/24` with its own listen port) are keyed
//! on the freeturn port. Re-running tears that port's install down completely first, different
//! ports coexist on one VPS, and switching a port between WireGuard and AmneziaWG removes the other
//! backend.

use super::ssh::{one_shot, upload, Ssh, SshTarget};
use super::{
    detect_arch, fail, go_arch, load_server_binary_gz, output_lines, require, require_ssh,
};
use super::{InstallError, ServerBinarySource};
use crate::util::{is_blank, kotlin_lines, trim_indent};

const REMOTE_GZ: &str = "/tmp/freeturn-server.gz";
const REMOTE_AWG_GZ: &str = "/tmp/amneziawg-go.gz";

/// What the VPS uses as the tunnel exit behind the freeturn server.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum FreeturnExit {
    /// Kernel WireGuard through wg-quick.
    #[default]
    WireGuard,
    /// Userspace amneziawg-go with random obfuscation parameters (Jc/Jmin/Jmax/S1/S2/H1-H4): even
    /// if the TURN relay is unwrapped, there is no recognisable WireGuard handshake inside. Shipped
    /// as a second binary; no kernel module needed.
    AmneziaWG,
}

impl FreeturnExit {
    pub const ALL: [Self; 2] = [Self::WireGuard, Self::AmneziaWG];

    pub fn label(self) -> &'static str {
        match self {
            Self::WireGuard => "WireGuard",
            Self::AmneziaWG => "AmneziaWG",
        }
    }
}

/// AmneziaWG obfuscation parameters generated on the VPS; the client must use the same ones.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct FreeturnAwgParams {
    pub jc: String,
    pub jmin: String,
    pub jmax: String,
    pub s1: String,
    pub s2: String,
    pub h1: String,
    pub h2: String,
    pub h3: String,
    pub h4: String,
}

/// Inputs of the one-tap install: SSH access plus the public UDP port the freeturn server binds.
#[derive(Clone, PartialEq, Eq)]
pub struct FreeturnInstallOptions {
    pub host: String,
    pub ssh_port: i32,
    pub login: String,
    pub ssh_password: String,
    /// PEM/OpenSSH private key for SSH publickey auth; when set it is used instead of the password.
    pub ssh_key: String,
    /// Passphrase for an encrypted [`ssh_key`](Self::ssh_key); empty for an unencrypted key.
    pub ssh_key_passphrase: String,
    /// Public UDP port the free-turn-proxy server binds (the `freeturn://` peer port).
    pub freeturn_port: i32,
    /// Wire obfuscation profile the server runs with (must match the client).
    pub obf_profile: String,
    /// DNS handed to the client in the generated WireGuard config.
    pub dns: String,
    pub exit: FreeturnExit,
}

impl Default for FreeturnInstallOptions {
    fn default() -> Self {
        Self {
            host: String::new(),
            ssh_port: 22,
            login: "root".into(),
            ssh_password: String::new(),
            ssh_key: String::new(),
            ssh_key_passphrase: String::new(),
            freeturn_port: 56000,
            obf_profile: "rtpopus".into(),
            dns: "1.1.1.1".into(),
            exit: FreeturnExit::WireGuard,
        }
    }
}

impl FreeturnInstallOptions {
    fn target(&self) -> SshTarget {
        SshTarget {
            host: self.host.clone(),
            port: self.ssh_port,
            login: self.login.clone(),
            password: self.ssh_password.clone(),
            private_key: self.ssh_key.clone(),
            passphrase: self.ssh_key_passphrase.clone(),
        }
    }

    fn validate(&self) -> Result<SshTarget, InstallError> {
        let target = self.target();
        require_ssh(&target)?;
        require(
            (1..=65535).contains(&self.freeturn_port),
            "Некорректный порт freeturn",
        )?;
        Ok(target)
    }
}

/// What the install produced, to build the client `freeturn://` link from: the obf key, the
/// WireGuard halves the client needs and the listener port. `status` is a human summary.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FreeturnInstallResult {
    pub obf_key: String,
    pub server_wg_public_key: String,
    pub client_wg_private_key: String,
    /// Client tunnel address, e.g. `10.7.3.2/32`.
    pub client_wg_address: String,
    pub freeturn_port: i32,
    pub status: String,
    /// Only for [`FreeturnExit::AmneziaWG`].
    pub awg: Option<FreeturnAwgParams>,
}

/// Installs (or reinstalls) the server, streaming progress through `on_log`.
pub fn install(
    ssh: &dyn Ssh,
    binaries: &dyn ServerBinarySource,
    options: &FreeturnInstallOptions,
    on_log: &dyn Fn(&str),
) -> Result<FreeturnInstallResult, InstallError> {
    let target = options.validate()?;
    let arch = detect_arch(ssh, &target, on_log, go_arch, "x86_64 или aarch64")?;

    let gz = load_server_binary_gz(binaries, &format!("freeturn/freeturn-server-linux-{arch}"))?;
    on_log(&format!(
        "Загрузка freeturn-сервера ({} КБ, по частям)…",
        gz.len() / 1024
    ));
    upload(ssh, &target, &gz, REMOTE_GZ, on_log)?;

    if options.exit == FreeturnExit::AmneziaWG {
        let awg_gz =
            load_server_binary_gz(binaries, &format!("freeturn/amneziawg-go-linux-{arch}"))?;
        on_log(&format!(
            "Загрузка amneziawg-go ({} КБ, по частям)…",
            awg_gz.len() / 1024
        ));
        upload(ssh, &target, &awg_gz, REMOTE_AWG_GZ, on_log)?;
    }
    on_log(&format!(
        "Бинарники загружены, ставлю {} + службу…",
        options.exit.label()
    ));

    let output = one_shot(ssh, &target, &build_install_script(options), on_log, false)?;
    output_lines(&output, |l| !l.starts_with("RESULT::")).for_each(on_log);

    let Some(result) = parse_result(&output, options)? else {
        return fail("Установка завершилась, но сервер не вернул ключи (RESULT). См. лог выше.");
    };
    on_log(&format!(
        "Готово: freeturn-server на {}:{}",
        options.host, options.freeturn_port
    ));
    Ok(result)
}

/// Removes what [`install`] set up on `options.freeturn_port`: the service, either tunnel exit,
/// the configs and the firewall rule. Only the address, SSH access and the port are used.
pub fn uninstall(
    ssh: &dyn Ssh,
    options: &FreeturnInstallOptions,
    on_log: &dyn Fn(&str),
) -> Result<String, InstallError> {
    let target = options.validate()?;
    let port = options.freeturn_port;
    on_log(&format!("Удаляю freeturn с {}:{port}…", options.host));
    let output = one_shot(ssh, &target, &build_uninstall_script(port), on_log, false)?;
    output_lines(&output, |l| !l.starts_with("REMOVED::")).for_each(on_log);
    // The script runs under `set -e`, so the marker at its end means it got there without errors.
    if !output.contains(&format!("REMOVED::{port}")) {
        return fail("Сервер не подтвердил удаление — см. лог выше");
    }
    Ok(format!(
        "freeturn на :{port} удалён (служба, туннель, конфиги, правило фаервола)"
    ))
}

/// Pulls `RESULT::<obfKey>|<serverPub>|<clientPriv>|<clientAddr>[|jc,jmin,jmax,s1,s2,h1,h2,h3,h4]`
/// out of the output; the fifth field comes only from an AmneziaWG exit.
fn parse_result(
    output: &str,
    options: &FreeturnInstallOptions,
) -> Result<Option<FreeturnInstallResult>, InstallError> {
    let Some(line) = kotlin_lines(output)
        .into_iter()
        .map(str::trim)
        .find(|l| l.starts_with("RESULT::"))
    else {
        return Ok(None);
    };
    let parts: Vec<&str> = line["RESULT::".len()..].split('|').collect();
    if parts.len() < 4 {
        return Ok(None);
    }
    let (key, server_pub, client_priv, client_addr) = (parts[0], parts[1], parts[2], parts[3]);
    if is_blank(key) || is_blank(server_pub) || is_blank(client_priv) {
        return Ok(None);
    }
    let awg = parts
        .get(4)
        .map(|p| p.split(',').collect::<Vec<_>>())
        .filter(|it| it.len() == 9)
        .map(|it| FreeturnAwgParams {
            jc: it[0].into(),
            jmin: it[1].into(),
            jmax: it[2].into(),
            s1: it[3].into(),
            s2: it[4].into(),
            h1: it[5].into(),
            h2: it[6].into(),
            h3: it[7].into(),
            h4: it[8].into(),
        });
    if options.exit == FreeturnExit::AmneziaWG && awg.is_none() {
        return fail(
            "AmneziaWG-выход установлен, но параметры обфускации не вернулись — см. лог выше",
        );
    }
    Ok(Some(FreeturnInstallResult {
        obf_key: key.into(),
        server_wg_public_key: server_pub.into(),
        client_wg_private_key: client_priv.into(),
        client_wg_address: if is_blank(client_addr) {
            "10.7.1.2/32".into()
        } else {
            client_addr.into()
        },
        freeturn_port: options.freeturn_port,
        status: format!(
            "freeturn-server ({}) установлен и запущен на :{}",
            options.exit.label(),
            options.freeturn_port
        ),
        awg,
    }))
}

/// The remote install script (ONE shell command). The backend subnet and port are computed here
/// from the freeturn port (`net`, `wg_port`) - the same formula the shell used, so existing
/// installs keep their numbers.
pub fn build_install_script(options: &FreeturnInstallOptions) -> String {
    let port = options.freeturn_port;
    // Restrict the profile to a known-safe token set (it lands bare in the unit file).
    let prof: String = options
        .obf_profile
        .to_lowercase()
        .chars()
        .filter(|c| c.is_alphanumeric())
        .collect();
    let prof = if is_blank(&prof) {
        "rtpopus".to_string()
    } else {
        prof
    };
    let net = port % 250 + 1;
    let wg_port = 51820 + net;
    let awg = options.exit == FreeturnExit::AmneziaWG;

    let backend = if awg {
        awg_backend(port, net, wg_port)
    } else {
        wg_backend(port, net, wg_port)
    };
    let result_tail = if awg {
        "|$jc,$jmin,$jmax,$s1,$s2,$h1,$h2,$h3,$h4"
    } else {
        ""
    };
    let exit_label = if awg {
        format!("AmneziaWG ftawg{port}")
    } else {
        format!("WireGuard ftwg{port}")
    };

    let preamble = trim_indent(&format!(
        r#"
        set -e
        gunzip -f /tmp/freeturn-server.gz
        install -m 0755 /tmp/freeturn-server /usr/local/bin/freeturn-server
        rm -f /tmp/freeturn-server
        mkdir -p /etc/wireguard
        wan=$(ip route show default 2>/dev/null | awk '/default/ {{print $5; exit}}')
        [ -n "$wan" ] || wan=eth0
        port={port}
        svc=freeturn-server-$port
    "#
    ));

    let tail = trim_indent(&format!(
        r#"
        key=$(/usr/local/bin/freeturn-server -gen-obf-key | tr -d '\r\n ')
        cat > /etc/systemd/system/$svc.service <<UNIT
        [Unit]
        Description=Free Turn Proxy Server (port {port})
        After=network-online.target
        Wants=network-online.target
        [Service]
        ExecStart=/usr/local/bin/freeturn-server -listen 0.0.0.0:{port} -connect 127.0.0.1:{wg_port} -mode udp -obf-profile {prof} -obf-key $key
        Restart=always
        RestartSec=3
        LimitNOFILE=1048576
        [Install]
        WantedBy=multi-user.target
        UNIT
        if command -v ufw >/dev/null 2>&1; then ufw allow {port}/udp || true; fi
        if command -v firewall-cmd >/dev/null 2>&1; then firewall-cmd --add-port={port}/udp --permanent && firewall-cmd --reload || true; fi
        systemctl daemon-reload
        systemctl enable --now $svc
        sleep 1
        systemctl is-active $svc >/dev/null && echo "Служба $svc активна на порту {port} (выход {exit_label} 10.7.{net}.0/24:{wg_port}, NAT->$wan)"
        echo "RESULT::$key|$spub|$cpriv|10.7.{net}.2/32{result_tail}"
    "#
    ));

    [preamble, teardown_block(port), backend, tail].join("\n")
}

/// Tears down everything an install may have left on THIS port: the freeturn service, both possible
/// backends (kernel `ftwg<port>` and userspace `ftawg<port>`), their configs and the UDP port.
/// Expects the caller to have set the shell variables `port` and `svc`. Shared by reinstall (which
/// is why it is idempotent and survives switching the exit type) and uninstall, so the two cannot
/// drift apart.
pub fn teardown_block(port: i32) -> String {
    trim_indent(&format!(
        r#"
        # --- Idempotent teardown: снимаем службу и ОБА возможных бэкенда этого порта, чтобы
        #     переустановка (в том числе со сменой типа выхода) не оставила старый туннель жить. ---
        if systemctl list-unit-files 2>/dev/null | grep -q "^$svc.service" || [ -f /etc/systemd/system/$svc.service ]; then
          echo "Найдена служба $svc — останавливаю и убираю её"
        fi
        systemctl disable --now $svc 2>/dev/null || true
        rm -f /etc/systemd/system/$svc.service
        systemctl stop wg-quick@ftwg{port} 2>/dev/null || true
        wg-quick down ftwg{port} 2>/dev/null || true
        rm -f /etc/wireguard/ftwg{port}.conf
        systemctl disable --now ftawg{port} 2>/dev/null || true
        rm -f /etc/systemd/system/ftawg{port}.service /usr/local/bin/ftawg{port}.sh /etc/amneziawg/ftawg{port}.uapi
        ip link del ftawg{port} 2>/dev/null || true
        # Legacy single-server install (old script used the fixed names freeturn-server/ftwg) — tear it
        # down too, but ONLY if it was bound to THIS port, so a different-port legacy server survives.
        if [ -f /etc/systemd/system/freeturn-server.service ] && grep -q "0.0.0.0:$port " /etc/systemd/system/freeturn-server.service; then
          systemctl disable --now freeturn-server 2>/dev/null || true
          rm -f /etc/systemd/system/freeturn-server.service
          systemctl stop wg-quick@ftwg 2>/dev/null || true; wg-quick down ftwg 2>/dev/null || true
          rm -f /etc/wireguard/ftwg.conf
        fi
        # Free the UDP port in case a stray process still holds it.
        command -v fuser >/dev/null 2>&1 && fuser -k $port/udp 2>/dev/null || true
        systemctl daemon-reload
    "#
    ))
}

/// Full removal: the same teardown plus the firewall rules and - when NO freeturn service is left
/// on the machine - the shared binaries (removing them unconditionally would kill neighbour servers
/// on other ports).
pub fn build_uninstall_script(port: i32) -> String {
    let head = trim_indent(&format!(
        r#"
        set -e
        port={port}
        svc=freeturn-server-$port
    "#
    ));
    let tail = trim_indent(&format!(
        r#"
        if command -v ufw >/dev/null 2>&1; then ufw delete allow {port}/udp || true; fi
        if command -v firewall-cmd >/dev/null 2>&1; then firewall-cmd --remove-port={port}/udp --permanent && firewall-cmd --reload || true; fi
        rmdir /etc/amneziawg 2>/dev/null || true
        # Бинарники общие на все порты — сносим, только если других служб freeturn не осталось.
        if ! ls /etc/systemd/system/freeturn-server-*.service >/dev/null 2>&1; then
          rm -f /usr/local/bin/freeturn-server /usr/local/bin/amneziawg-go
          echo "Других серверов freeturn не осталось — удалил и бинарники"
        else
          echo "На VPS остались другие серверы freeturn — общие бинарники не трогаю"
        fi
        systemctl daemon-reload
        echo "REMOVED::{port}"
    "#
    ));
    [head, teardown_block(port), tail].join("\n")
}

/// Kernel WireGuard through wg-quick - the original, proven path.
fn wg_backend(port: i32, net: i32, wg_port: i32) -> String {
    trim_indent(&format!(
        r#"
        command -v wg >/dev/null 2>&1 || {{ apt-get update -y && apt-get install -y wireguard-tools; }}
        iface=ftwg{port}
        spriv=$(wg genkey); spub=$(printf '%s' "$spriv" | wg pubkey)
        cpriv=$(wg genkey); cpub=$(printf '%s' "$cpriv" | wg pubkey)
        umask 077
        cat > /etc/wireguard/$iface.conf <<EOF
        [Interface]
        Address = 10.7.{net}.1/24
        ListenPort = {wg_port}
        PrivateKey = $spriv
        PostUp = sysctl -w net.ipv4.ip_forward=1
        PostUp = iptables -t nat -A POSTROUTING -s 10.7.{net}.0/24 -o $wan -j MASQUERADE
        PostUp = iptables -A FORWARD -i $iface -j ACCEPT
        PostUp = iptables -A FORWARD -o $iface -j ACCEPT
        PostDown = iptables -t nat -D POSTROUTING -s 10.7.{net}.0/24 -o $wan -j MASQUERADE
        PostDown = iptables -D FORWARD -i $iface -j ACCEPT
        PostDown = iptables -D FORWARD -o $iface -j ACCEPT

        [Peer]
        PublicKey = $cpub
        AllowedIPs = 10.7.{net}.2/32
        EOF
        systemctl enable --now wg-quick@$iface
    "#
    ))
}

/// AmneziaWG exit: userspace amneziawg-go plus random obfuscation parameters.
///
/// Neither the kernel module nor awg-tools is needed. Keys and parameters reach the device through
/// its UAPI socket (`/var/run/amneziawg/<iface>.sock`): plain `wg setconf` rejects the jc/s1/h1
/// fields, and without them a plain, recognisable WireGuard would travel inside the TURN relay.
/// Address, link-up and NAT are done by hand - wg-quick only drives kernel interfaces.
///
/// `S1 + 56 != S2` is an AmneziaWG requirement (otherwise init and response packets become
/// indistinguishable); H1..H4 are consecutive from a random base - they must differ and must not be
/// plain WireGuard's 1..4.
fn awg_backend(port: i32, net: i32, wg_port: i32) -> String {
    trim_indent(&format!(
        r#"
        gunzip -f /tmp/amneziawg-go.gz
        install -m 0755 /tmp/amneziawg-go /usr/local/bin/amneziawg-go
        rm -f /tmp/amneziawg-go
        command -v wg >/dev/null 2>&1 || {{ apt-get update -y && apt-get install -y wireguard-tools; }}
        command -v socat >/dev/null 2>&1 || {{ apt-get update -y && apt-get install -y socat; }}
        [ -c /dev/net/tun ] || {{ echo "На VPS нет /dev/net/tun — AmneziaWG-выход не поднять (OpenVZ/LXC без TUN)"; exit 1; }}
        iface=ftawg{port}
        rnd() {{ od -An -N4 -tu4 /dev/urandom | tr -d ' \n'; }}
        jc=$(( $(rnd) % 6 + 3 )); jmin=40; jmax=70
        s1=$(( $(rnd) % 50 + 15 )); s2=$(( $(rnd) % 50 + 15 ))
        # `[ ... ] && ...` под `set -e` роняет скрипт, когда условие ЛОЖНО, а это обычный случай.
        if [ $(( $s1 + 56 )) -eq "$s2" ]; then s2=$(( $s2 + 1 )); fi
        hb=$(( $(rnd) % 1000000000 + 5 ))
        h1=$hb; h2=$(( $hb + 1 )); h3=$(( $hb + 2 )); h4=$(( $hb + 3 ))
        spriv=$(wg genkey); spub=$(printf '%s' "$spriv" | wg pubkey)
        cpriv=$(wg genkey); cpub=$(printf '%s' "$cpriv" | wg pubkey)
        # UAPI принимает ключи в hex, а wg genkey печатает base64.
        b64hex() {{ printf '%s' "$1" | base64 -d | od -An -tx1 | tr -d ' \n'; }}
        umask 077
        mkdir -p /etc/amneziawg
        printf 'set=1\nprivate_key=%s\nlisten_port=%s\njc=%s\njmin=%s\njmax=%s\ns1=%s\ns2=%s\nh1=%s\nh2=%s\nh3=%s\nh4=%s\nreplace_peers=true\npublic_key=%s\nallowed_ip=10.7.{net}.2/32\n\n' "$(b64hex "$spriv")" "{wg_port}" "$jc" "$jmin" "$jmax" "$s1" "$s2" "$h1" "$h2" "$h3" "$h4" "$(b64hex "$cpub")" > /etc/amneziawg/$iface.uapi
        cat > /usr/local/bin/$iface.sh <<RUNNER
        #!/bin/sh
        set -e
        mkdir -p /var/run/amneziawg
        rm -f /var/run/amneziawg/$iface.sock
        WG_PROCESS_FOREGROUND=1 /usr/local/bin/amneziawg-go -f $iface &
        pid=\$!
        i=0
        while [ ! -S /var/run/amneziawg/$iface.sock ] && [ \$i -lt 50 ]; do sleep 0.2; i=\$(( \$i + 1 )); done
        socat - UNIX-CONNECT:/var/run/amneziawg/$iface.sock < /etc/amneziawg/$iface.uapi
        ip addr add 10.7.{net}.1/24 dev $iface 2>/dev/null || true
        # 1280 - безопасный MTU для WireGuard поверх TURN (столько же берёт сам freeturn в своём
        # встроенном туннеле): иначе полноразмерный кадр режется по дороге и теряется.
        ip link set $iface mtu 1280
        ip link set $iface up
        sysctl -w net.ipv4.ip_forward=1 >/dev/null
        wan=\$(ip route show default 2>/dev/null | awk '/default/ {{print \$5; exit}}')
        [ -n "\$wan" ] || wan=eth0
        iptables -t nat -C POSTROUTING -s 10.7.{net}.0/24 -o \$wan -j MASQUERADE 2>/dev/null || iptables -t nat -A POSTROUTING -s 10.7.{net}.0/24 -o \$wan -j MASQUERADE
        iptables -C FORWARD -i $iface -j ACCEPT 2>/dev/null || iptables -A FORWARD -i $iface -j ACCEPT
        iptables -C FORWARD -o $iface -j ACCEPT 2>/dev/null || iptables -A FORWARD -o $iface -j ACCEPT
        wait \$pid
        RUNNER
        chmod +x /usr/local/bin/$iface.sh
        cat > /etc/systemd/system/$iface.service <<AWGUNIT
        [Unit]
        Description=AmneziaWG exit for free-turn-proxy (port {port})
        After=network-online.target
        Wants=network-online.target
        [Service]
        ExecStart=/usr/local/bin/$iface.sh
        Restart=always
        RestartSec=3
        [Install]
        WantedBy=multi-user.target
        AWGUNIT
        systemctl daemon-reload
        systemctl enable --now $iface
        sleep 1
        systemctl is-active $iface >/dev/null || {{ echo "AmneziaWG-интерфейс $iface не поднялся"; journalctl -u $iface -n 20 --no-pager 2>/dev/null || true; exit 1; }}
        # Спрашиваем у самого устройства, что в него доехало: пустой ответ = UAPI-конфиг не применился,
        # и туннель будет молча не работать (сокет есть, ключей и обфускации нет).
        awgstate=$(printf 'get=1\n\n' | socat - UNIX-CONNECT:/var/run/amneziawg/$iface.sock 2>/dev/null || true)
        echo "$awgstate" | grep -q '^listen_port=' || {{ echo "AmneziaWG: устройство не приняло конфиг (UAPI пуст)"; exit 1; }}
        echo "AmneziaWG $iface: $(echo "$awgstate" | grep -c '^public_key=') пир(ов), listen_port=$(echo "$awgstate" | sed -n 's/^listen_port=//p'), jc=$jc s1=$s1 s2=$s2"
    "#
    ))
}
