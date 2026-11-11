//! MasterDnsVPN server installer, ported from `MasterDnsServerInstaller.kt` and
//! `SshMasterDnsServerInstaller.kt`.
//!
//! The server runs in *direct mode*: the client points its resolver straight at `host:udp_port`, so
//! no NS delegation is needed and the domain is just a label both ends agree on. (Delegating a real
//! subdomain - an `A` plus an `NS` record, per the upstream README - lets the tunnel ride ordinary
//! public resolvers instead: install on UDP 53 and point the client's resolvers at public ones.)
//!
//! The encryption key is PERSISTENT: generated on the VPS the first time and kept across reinstalls
//! (unless a new one is asked for), so an already configured client keeps working. The script prints
//! it on a `MASTERDNS_KEY=` line.

use std::sync::LazyLock;

use regex_lite::Regex;

use super::ssh::{one_shot, upload, Ssh, SshTarget};
use super::{detect_arch, fail, go_arch, load_server_binary_gz, require, require_ssh};
use super::{InstallError, ServerBinarySource};
use crate::util::{is_blank, kotlin_lines, shell_single_quote, trim_indent};

const REMOTE_GZ: &str = "/tmp/masterdns-server.gz";

static UPSTREAM_TOKEN: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[0-9A-Fa-f.:\[\]]+$").unwrap());

/// Inputs of the one-tap install: SSH access plus the DNS listener port, the tunnel domain and the
/// payload cipher.
#[derive(Clone, PartialEq, Eq)]
pub struct MasterDnsInstallOptions {
    pub host: String,
    pub ssh_port: i32,
    pub login: String,
    pub ssh_password: String,
    /// PEM/OpenSSH private key for SSH publickey auth; when set it is used instead of the password.
    pub ssh_key: String,
    /// Passphrase for an encrypted [`ssh_key`](Self::ssh_key); empty for an unencrypted key.
    pub ssh_key_passphrase: String,
    /// UDP port the server binds for tunnel queries (the client's resolver port in direct mode).
    pub udp_port: i32,
    /// Tunnel domain - arbitrary in direct mode, but it must match the client's.
    pub domain: String,
    /// Payload cipher (`DATA_ENCRYPTION_METHOD`); the client must use the same value.
    pub encryption_method: i32,
    /// Throw the server's key away and generate a new one (every existing client must re-import).
    pub regenerate_key: bool,
    /// Upstream resolvers the server uses for DNS queries sent through the tunnel.
    pub dns_upstream: String,
    /// Let clients negotiate ZSTD/LZ4/ZLIB; off = only uncompressed payloads are accepted.
    pub allow_compression: bool,
    /// Stop and disable whatever systemd service holds `udp_port` (e.g. an old dnstt-server).
    pub free_port: bool,
    /// Server log verbosity: DEBUG, INFO, WARN, ERROR.
    pub log_level: String,
}

impl MasterDnsInstallOptions {
    pub const DEFAULT_UDP_PORT: i32 = 5300;
    pub const DEFAULT_DOMAIN: &'static str = "v.masterdns.net";
    /// XOR - the upstream default.
    pub const DEFAULT_ENCRYPTION_METHOD: i32 = 1;
    pub const DEFAULT_DNS_UPSTREAM: &'static str = "1.1.1.1:53, 8.8.8.8:53";
}

impl Default for MasterDnsInstallOptions {
    fn default() -> Self {
        Self {
            host: String::new(),
            ssh_port: 22,
            login: "root".into(),
            ssh_password: String::new(),
            ssh_key: String::new(),
            ssh_key_passphrase: String::new(),
            udp_port: Self::DEFAULT_UDP_PORT,
            domain: Self::DEFAULT_DOMAIN.into(),
            encryption_method: Self::DEFAULT_ENCRYPTION_METHOD,
            regenerate_key: false,
            dns_upstream: Self::DEFAULT_DNS_UPSTREAM.into(),
            allow_compression: true,
            free_port: false,
            log_level: "INFO".into(),
        }
    }
}

/// The server's encryption key (read back out of `encrypt_key.txt`) plus a status line. The key,
/// the domain and the resolver (`host:udp_port`) are everything the client needs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MasterDnsInstallResult {
    pub encryption_key: String,
    pub message: String,
}

/// Installs (or upgrades) the server, streaming progress through `on_log`.
pub fn install(
    ssh: &dyn Ssh,
    binaries: &dyn ServerBinarySource,
    options: &MasterDnsInstallOptions,
    on_log: &dyn Fn(&str),
) -> Result<MasterDnsInstallResult, InstallError> {
    let target = SshTarget {
        host: options.host.clone(),
        port: options.ssh_port,
        login: options.login.clone(),
        password: options.ssh_password.clone(),
        private_key: options.ssh_key.clone(),
        passphrase: options.ssh_key_passphrase.clone(),
    };
    require_ssh(&target)?;
    require(!is_blank(&options.domain), "Не указан домен туннеля")?;

    let arch = detect_arch(ssh, &target, on_log, go_arch, "x86_64 или aarch64")?;

    let gz = load_server_binary_gz(
        binaries,
        &format!("masterdns/masterdns-server-linux-{arch}"),
    )?;
    on_log(&format!(
        "Загрузка сервера ({} КБ, по частям)…",
        gz.len() / 1024
    ));
    upload(ssh, &target, &gz, REMOTE_GZ, on_log)?;
    on_log("Бинарник загружен, ставлю службу и генерирую ключ…");

    let output = one_shot(ssh, &target, &build_install_script(options), on_log, false)?;

    let mut key = String::new();
    for line in kotlin_lines(&output)
        .into_iter()
        .map(str::trim)
        .filter(|l| !l.is_empty())
    {
        match line
            .split_once("MASTERDNS_KEY=")
            .map(|(_, k)| k)
            .filter(|k| !k.is_empty())
        {
            Some(marker) => key = marker.trim().to_string(),
            None => on_log(line),
        }
    }
    if is_blank(&key) {
        let remote_error = kotlin_lines(&output)
            .into_iter()
            .map(str::trim)
            .find(|l| l.starts_with("ОШИБКА:"));
        return fail(match remote_error {
            Some(l) => l["ОШИБКА:".len()..].trim().to_string(),
            None => "Не удалось получить ключ шифрования с сервера".to_string(),
        });
    }
    on_log("Ключ шифрования получен");

    Ok(MasterDnsInstallResult {
        encryption_key: key,
        message: format!(
            "MasterDNS-сервер установлен и запущен на {host}:{port} (резолвер: {host}:{port})",
            host = options.host,
            port = options.udp_port
        ),
    })
}

/// The remote install script: installs the binary, writes the config, generates the persistent key
/// the first time (`-genkey` is a no-op when `encrypt_key.txt` exists), writes the systemd unit,
/// opens the UDP port on any common firewall (best effort), starts the service and prints the key on
/// a `MASTERDNS_KEY=` line.
///
/// `USE_EXTERNAL_SOCKS5 = false` makes the server its own internet exit - no second daemon needed.
/// The domain is single-quoted so an awkward one cannot break out of the shell quoting.
pub fn build_install_script(options: &MasterDnsInstallOptions) -> String {
    let udp = options.udp_port;
    let domain = shell_single_quote(&options.domain);
    let encryption = options.encryption_method.clamp(0, 5);
    // Only IP[:port] tokens survive, so nothing typed here can break out of the TOML/heredoc.
    let mut servers: Vec<String> = options
        .dns_upstream
        .split([',', ' ', '\n', ';'])
        .map(str::trim)
        .filter(|t| UPSTREAM_TOKEN.is_match(t))
        .map(|t| {
            if t.contains(':') {
                t.to_string()
            } else {
                format!("{t}:53")
            }
        })
        .collect();
    if servers.is_empty() {
        servers = vec!["1.1.1.1:53".into(), "8.8.8.8:53".into()];
    }
    let upstream = servers
        .iter()
        .map(|s| format!("\"{s}\""))
        .collect::<Vec<_>>()
        .join(", ");
    let compression = if options.allow_compression {
        "[0, 1, 2, 3]"
    } else {
        "[0]"
    };
    let log_level = options.log_level.to_uppercase();
    let log_level = if ["DEBUG", "INFO", "WARN", "ERROR"].contains(&log_level.as_str()) {
        log_level
    } else {
        "INFO".into()
    };
    let free_port = i32::from(options.free_port);
    let regen_key = i32::from(options.regenerate_key);
    trim_indent(&format!(
        r#"
        set -e
        gunzip -f /tmp/masterdns-server.gz
        # Our own (possibly crash-looping) instance must not count as "port busy", and a plain
        # `enable --now` would NOT restart an already-running unit, silently keeping the old config.
        if [ -f /etc/systemd/system/masterdns-server.service ] || [ -x /usr/local/bin/masterdns-server ]; then
          echo "Найдена прежняя установка MasterDNS — переустанавливаю (ключ сохраняется, если не выбран новый)"
          systemctl disable --now masterdns-server >/dev/null 2>&1 || true
          rm -f /etc/systemd/system/masterdns-server.service
        fi
        holder=$(ss -Hulpn "sport = :{udp}" 2>/dev/null | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2)
        if [ -n "$holder" ]; then
          unit=$(ps -o unit= -p "$holder" 2>/dev/null | tr -d ' ')
          name=$(ps -o comm= -p "$holder" 2>/dev/null)
          # On Ubuntu UDP 53 is systemd-resolved's local stub. Stopping the whole service would leave
          # /etc/resolv.conf pointing at 127.0.0.53 and the VPS itself without DNS, so drop only the stub.
          if [ "{free_port}" = 1 ] && [ "$unit" = "systemd-resolved.service" ]; then
            echo "UDP-порт {udp} занят локальным DNS systemd-resolved — отключаю только его слушатель на 53, DNS самого сервера продолжит работать"
            mkdir -p /etc/systemd/resolved.conf.d
            printf '[Resolve]\nDNSStubListener=no\n' > /etc/systemd/resolved.conf.d/masterdns.conf
            if grep -q '^nameserver 127\.0\.0\.53' /etc/resolv.conf 2>/dev/null; then
              ln -sf /run/systemd/resolve/resolv.conf /etc/resolv.conf
            fi
            systemctl restart systemd-resolved
            sleep 1
          elif [ "{free_port}" = 1 ] && [ -n "$unit" ] && [ "$unit" != "-" ]; then
            echo "UDP-порт {udp} занят ($name, $unit) — останавливаю и отключаю $unit"
            systemctl disable --now "$unit" || true
            sleep 1
          else
            echo "ОШИБКА: UDP-порт {udp} уже занят процессом $name (pid $holder${{unit:+, служба $unit}})."
            echo "Выбери другой UDP-порт или включи «Освободить порт»."
            exit 1
          fi
        fi
        install -m 0755 /tmp/masterdns-server /usr/local/bin/masterdns-server
        rm -f /tmp/masterdns-server
        mkdir -p /etc/masterdns
        cat > /etc/masterdns/server_config.toml <<CONFIG
        PROTOCOL_TYPE = "SOCKS5"
        DOMAIN = [{domain}]
        UDP_HOST = ""
        UDP_PORT = {udp}
        DATA_ENCRYPTION_METHOD = {encryption}
        ENCRYPTION_KEY_FILE = "/etc/masterdns/encrypt_key.txt"
        SUPPORTED_UPLOAD_COMPRESSION_TYPES = {compression}
        SUPPORTED_DOWNLOAD_COMPRESSION_TYPES = {compression}
        DNS_UPSTREAM_SERVERS = [{upstream}]
        USE_EXTERNAL_SOCKS5 = false
        LOG_LEVEL = "{log_level}"
        CONFIG
        if [ "{regen_key}" = 1 ]; then rm -f /etc/masterdns/encrypt_key.txt; echo "Старый ключ удалён, генерирую новый"; fi
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
          ufw allow {udp}/udp >/dev/null && echo "ufw: открыт UDP {udp}"
        elif command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
          firewall-cmd --add-port={udp}/udp --permanent >/dev/null && firewall-cmd --reload >/dev/null && echo "firewalld: открыт UDP {udp}"
        fi
        for ipt in iptables ip6tables; do
          if command -v $ipt >/dev/null 2>&1 && ! $ipt -C INPUT -p udp --dport {udp} -j ACCEPT 2>/dev/null; then
            $ipt -I INPUT -p udp --dport {udp} -j ACCEPT 2>/dev/null && echo "$ipt: открыт UDP {udp}"
          fi
        done
        if command -v netfilter-persistent >/dev/null 2>&1; then netfilter-persistent save >/dev/null 2>&1 || true; fi
        systemctl daemon-reload
        systemctl enable masterdns-server
        systemctl restart masterdns-server
        sleep 3
        # is-active is true even for a crash loop between restarts — check the socket itself.
        if ! ss -Hulpn "sport = :{udp}" 2>/dev/null | grep -q masterdns; then
          echo "ОШИБКА: сервер не слушает UDP-порт {udp}. Журнал:"
          journalctl -u masterdns-server --no-pager -n 12 -o cat 2>/dev/null || true
          exit 1
        fi
        echo "Служба masterdns-server слушает UDP-порт {udp}"
        if command -v getent >/dev/null 2>&1 && ! getent hosts github.com >/dev/null 2>&1; then
          echo "Внимание: на самом сервере не резолвятся имена (проверь /etc/resolv.conf) — туннель это не затрагивает"
        fi
        echo "MASTERDNS_KEY=$(cat /etc/masterdns/encrypt_key.txt)"
    "#
    ))
}
