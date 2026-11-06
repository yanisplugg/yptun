//! qWDTT server installer, ported from `WdttServerInstaller.kt` and `SshWdttServerInstaller.kt`.
//!
//! Deploys the way the qWDTT app itself does: its own `deploy.sh` (bundled as `wdtt/deploy.sh` -
//! distro detection, prerequisites, sysctl, NAT/firewall, systemd unit, admin TLS) is uploaded with
//! the server binary and run with the ports and secrets in its environment. Around it, what that
//! script does not cover and a real VPS needs:
//!  - an older WDTT under ANY unit name (the pre-Plus `wdtt-server.service`, a hand-started one...)
//!    still holding the port is stopped, its unit and binary removed - deploy.sh only knows
//!    `wdtt.service`;
//!  - a WDTT Plus database is moved aside: the qWDTT server stops dead on a `passwords.json` it
//!    cannot read, and clients fetch their WireGuard config from the server on every start anyway;
//!  - ports another program holds (the freeturn server also defaults to 56000) are skipped: DTLS,
//!    the internal WireGuard and the admin port each move to the next free one, and the location
//!    follows;
//!  - "installed" means the service stays up: deploy.sh checks `is-active` after 2 s, which a crash
//!    loop passes, so it is re-checked after a few seconds with `NRestarts` (journal on failure).
//!
//! The WDTT password must match the one in the location editor: the WRAP key is HKDF-derived from it
//! on BOTH sides, so a mismatch silently fails to connect.

use base64::Engine as _;

use super::ssh::{one_shot, upload, Ssh, SshTarget};
use super::{
    detect_arch, fail, go_arch, load_server_binary_gz, output_lines, random_bytes, require,
    require_ssh,
};
use super::{DeployReport, InstallError, ServerBinarySource};
use crate::transports::WDTT_DEFAULT_RAW_PORT;
use crate::util::{is_blank, kotlin_lines, shell_single_quote, trim_indent};

const REMOTE_GZ: &str = "/tmp/wdtt-server.gz";
const REMOTE_SCRIPT: &str = "/tmp/deploy.sh";
const DEPLOY_SCRIPT_ASSET: &str = "wdtt/deploy.sh";

pub const PORTS_MARKER: &str = "WDTT_PORTS=";
pub const VERIFY_OK: &str = "WDTT_SERVICE_STABLE";

/// Inputs of the one-tap install: SSH access plus the DTLS port and the connection password the
/// server is launched with.
#[derive(Clone, PartialEq, Eq)]
pub struct WdttInstallOptions {
    pub host: String,
    pub ssh_port: i32,
    pub login: String,
    pub ssh_password: String,
    /// PEM/OpenSSH private key for SSH publickey auth; when set it is used instead of the password.
    pub ssh_key: String,
    /// Passphrase for an encrypted [`ssh_key`](Self::ssh_key); empty for an unencrypted key.
    pub ssh_key_passphrase: String,
    /// DTLS listener port the server binds (the client's wdtt-server port).
    pub wdtt_port: i32,
    pub wdtt_password: String,
    /// DNS handed to clients in the WireGuard config the server distributes.
    pub dns: String,
}

impl Default for WdttInstallOptions {
    fn default() -> Self {
        Self {
            host: String::new(),
            ssh_port: 22,
            login: "root".into(),
            ssh_password: String::new(),
            ssh_key: String::new(),
            ssh_key_passphrase: String::new(),
            wdtt_port: 56000,
            wdtt_password: String::new(),
            dns: "1.1.1.1".into(),
        }
    }
}

/// `port` is the DTLS port the server really runs on: the requested one, or the next free one when
/// something else (typically the freeturn server, also on 56000 by default) holds it. The location
/// must be switched to it or the client dials the wrong service. `raw_port` is the same for the Raw
/// mode's `-listen-raw` port (0 = not reported).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WdttInstallResult {
    pub message: String,
    pub port: i32,
    pub raw_port: i32,
}

/// The ports the prepare step picked.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct WdttPorts {
    pub dtls: i32,
    pub wg: i32,
    pub admin: i32,
    pub raw: i32,
}

/// Installs (or upgrades) the server, streaming progress through `on_log`.
pub fn install(
    ssh: &dyn Ssh,
    binaries: &dyn ServerBinarySource,
    options: &WdttInstallOptions,
    on_log: &dyn Fn(&str),
) -> Result<WdttInstallResult, InstallError> {
    let target = SshTarget {
        host: options.host.clone(),
        port: options.ssh_port,
        login: options.login.clone(),
        password: options.ssh_password.clone(),
        private_key: options.ssh_key.clone(),
        passphrase: options.ssh_key_passphrase.clone(),
    };
    require_ssh(&target)?;
    require(!is_blank(&options.wdtt_password), "Не указан пароль WDTT")?;

    let arch = detect_arch(ssh, &target, on_log, go_arch, "x86_64 или aarch64")?;

    on_log("Готовлю VPS: старый WDTT, база WDTT Plus, свободные порты…");
    let prepared = one_shot(
        ssh,
        &target,
        &build_prepare_script(options.wdtt_port),
        on_log,
        false,
    )?;
    output_lines(&prepared, |l| !l.starts_with(PORTS_MARKER)).for_each(on_log);
    let Some(ports) = parse_ports(&prepared) else {
        return fail(format!(
            "VPS не сообщил свободные порты:\n{}",
            prepared.trim()
        ));
    };
    on_log(&format!(
        "Порты: DTLS {}/udp, WireGuard {}/udp (только локально), админка {}/tcp, Raw {}/udp",
        ports.dtls, ports.wg, ports.admin, ports.raw
    ));

    let gz = load_server_binary_gz(binaries, &format!("wdtt/wdtt-server-linux-{arch}"))?;
    on_log(&format!(
        "Загрузка сервера qWDTT ({} КБ, по частям)…",
        gz.len() / 1024
    ));
    upload(ssh, &target, &gz, REMOTE_GZ, on_log)?;
    // CR stripped: a Windows checkout can hand the asset over with CRLF, and bash on the VPS then
    // dies on the first line ("syntax error near {\r").
    let Some(script) = binaries.bytes(DEPLOY_SCRIPT_ASSET) else {
        return fail(format!("В сборке нет установщика {DEPLOY_SCRIPT_ASSET}"));
    };
    let script = String::from_utf8_lossy(&script).replace('\r', "");
    on_log("Загрузка установщика qWDTT…");
    upload(ssh, &target, script.as_bytes(), REMOTE_SCRIPT, on_log)?;

    on_log("Установка qWDTT (пакеты, сеть, служба) — может занять пару минут…");
    let output = one_shot(
        ssh,
        &target,
        &build_deploy_command(options, &ports, &random_token()),
        on_log,
        false,
    )?;
    let report = read_deploy_output(&output);
    report.lines.iter().for_each(|l| on_log(l));
    if !report.ok {
        let code = report.exit_code.map_or("?".to_string(), |c| c.to_string());
        return fail(format!(
            "Установщик qWDTT не завершился успехом (код {code}). Подробности — в строках журнала выше."
        ));
    }

    on_log("Проверяю, что служба держится…");
    let verify = one_shot(ssh, &target, &build_verify_script(), on_log, false)?;
    if !verify.contains(VERIFY_OK) {
        return fail(format!(
            "Служба wdtt не держится после запуска. Журнал сервера:\n{}",
            verify.trim()
        ));
    }

    Ok(WdttInstallResult {
        message: format!(
            "qWDTT установлен и запущен на {}:{} (Raw — {})",
            options.host, ports.dtls, ports.raw
        ),
        port: ports.dtls,
        raw_port: ports.raw,
    })
}

/// 24 random bytes as lowercase hex: the admin token.
fn random_token() -> String {
    random_bytes(24)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

/// The last `WDTT_PORTS=dtls|wg|admin|raw` line; None unless all four are valid ports.
pub fn parse_ports(output: &str) -> Option<WdttPorts> {
    let line = kotlin_lines(output)
        .into_iter()
        .map(str::trim)
        .rev()
        .find(|l| l.starts_with(PORTS_MARKER))?;
    let parts: Vec<Option<i32>> = line[PORTS_MARKER.len()..]
        .split('|')
        .map(|p| p.trim().parse().ok())
        .collect();
    if parts.len() != 4
        || parts
            .iter()
            .any(|p| !p.is_some_and(|p| (1..=65535).contains(&p)))
    {
        return None;
    }
    let p = |i: usize| parts[i].unwrap();
    Some(WdttPorts {
        dtls: p(0),
        wg: p(1),
        admin: p(2),
        raw: p(3),
    })
}

/// Runs before deploy.sh: takes down every older WDTT (any unit name) still holding the DTLS or the
/// WG port, sets a WDTT Plus database aside, then picks free ports and prints
/// `WDTT_PORTS=dtls|wg|admin|raw`. The raw port (qWDTT 1.4 "Raw", `-listen-raw`) is always enabled,
/// so either client mode works.
pub fn build_prepare_script(requested_port: i32) -> String {
    let raw_port = WDTT_DEFAULT_RAW_PORT;
    let ports_marker = PORTS_MARKER;
    trim_indent(&format!(
        r#"
        set -e
        systemctl stop wdtt >/dev/null 2>&1 || true
        udp_pids() {{ ss -Hulnp "sport = :$1" 2>/dev/null | grep -o 'pid=[0-9]*' | cut -d= -f2 | sort -u; }}
        udp_busy() {{ ss -Hulnp "sport = :$1" 2>/dev/null | grep -q .; }}
        tcp_busy() {{ ss -Htlnp "sport = :$1" 2>/dev/null | grep -q .; }}
        if [ -f /etc/systemd/system/wdtt-server.service ]; then
          echo "Найден старый WDTT (wdtt-server.service) — удаляю"
          systemctl disable --now wdtt-server >/dev/null 2>&1 || true
          rm -f /etc/systemd/system/wdtt-server.service
        fi
        for P in {requested_port} 56001; do
          for pid in $(udp_pids $P); do
            exe=$(readlink -f /proc/$pid/exe 2>/dev/null || true)
            unit=$(grep -o '[^/]*[.]service' /proc/$pid/cgroup 2>/dev/null | tail -1 || true)
            case "$(basename "$exe") $unit" in *wdtt*|*wg-turn*|*WDTT*) ;; *) continue ;; esac
            echo "Найден старый WDTT на порту $P ($exe $unit) — удаляю"
            if [ -n "$unit" ] && [ "$unit" != "wdtt.service" ]; then
              frag=$(systemctl show -p FragmentPath --value "$unit" 2>/dev/null || true)
              systemctl disable --now "$unit" >/dev/null 2>&1 || true
              if [ -n "$frag" ]; then rm -f "$frag"; fi
            fi
            kill $pid 2>/dev/null || true
            if [ "$exe" != /usr/local/bin/wdtt-server ]; then rm -f "$exe"; fi
          done
        done
        systemctl daemon-reload >/dev/null 2>&1 || true
        sleep 1
        if [ -f /etc/wdtt/passwords.json ] && grep -q '"main_password"' /etc/wdtt/passwords.json 2>/dev/null; then
          aside="/etc/wdtt.pre-qwdtt-$(date +%Y%m%d%H%M%S)"
          mv /etc/wdtt "$aside"
          echo "База WDTT Plus отложена в $aside (qWDTT её не читает)"
        fi
        DTLS={requested_port}
        if udp_busy $DTLS; then
          owner=$(ss -Hulnp "sport = :$DTLS" 2>/dev/null | grep -o 'users:(("[^"]*' | head -1 | cut -d'"' -f2)
          while udp_busy $DTLS; do DTLS=$((DTLS+1)); done
          echo "Порт {requested_port}/udp занят (${{owner:-другой программой}}) — qWDTT встанет на $DTLS"
        fi
        WG=56001
        while [ "$WG" = "$DTLS" ] || udp_busy $WG; do WG=$((WG+1)); done
        ADMIN=56002
        while [ "$ADMIN" = "$DTLS" ] || [ "$ADMIN" = "$WG" ] || tcp_busy $ADMIN; do ADMIN=$((ADMIN+1)); done
        RAW={raw_port}
        while [ "$RAW" = "$DTLS" ] || [ "$RAW" = "$WG" ] || [ "$RAW" = "$ADMIN" ] || udp_busy $RAW; do RAW=$((RAW+1)); done
        echo "{ports_marker}$DTLS|$WG|$ADMIN|$RAW"
    "#
    ))
}

/// Unpacks the binary, writes the secrets the way the qWDTT app does (files in /tmp that deploy.sh
/// moves into /etc/wdtt and deletes) and runs deploy.sh. Always exits 0 and reports deploy.sh's own
/// exit code as `WDTT_DEPLOY_EXIT=<n>`, so its output reaches the log even when it fails.
pub fn build_deploy_command(
    options: &WdttInstallOptions,
    ports: &WdttPorts,
    admin_token: &str,
) -> String {
    let b64 = base64::engine::general_purpose::STANDARD;
    let password = b64.encode(options.wdtt_password.as_bytes());
    let token = b64.encode(admin_token.as_bytes());
    let servers: Vec<&str> = options
        .dns
        .split([',', ' ', ';'])
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .collect();
    let dns = shell_single_quote(&if servers.is_empty() {
        "1.1.1.1".to_string()
    } else {
        servers.join(",")
    });
    let (dtls, wg, admin, raw, ssh_port) = (
        ports.dtls,
        ports.wg,
        ports.admin,
        ports.raw,
        options.ssh_port,
    );
    trim_indent(&format!(
        r#"
        set -e
        gunzip -f /tmp/wdtt-server.gz
        printf '%s' '{password}' | base64 -d > /tmp/wdtt-main.password
        printf '%s' '{token}' | base64 -d > /tmp/wdtt-admin.token
        : > /tmp/wdtt-bot.token
        chmod 600 /tmp/wdtt-main.password /tmp/wdtt-admin.token /tmp/wdtt-bot.token
        set +e
        env WDTT_ADMIN_ID= WDTT_DNS_SERVERS={dns} WDTT_DTLS_PORT={dtls} WDTT_WG_PORT={wg} WDTT_ADMIN_PORT={admin} WDTT_RAW_PORT={raw} WDTT_SSH_PORT={ssh_port} bash /tmp/deploy.sh 2>&1
        echo "WDTT_DEPLOY_EXIT=$?"
        rm -f /tmp/deploy.sh
        exit 0
    "#
    ))
}

/// Checks a few seconds later that the service did not fall into a restart loop.
pub fn build_verify_script() -> String {
    let verify_ok = VERIFY_OK;
    trim_indent(&format!(
        r#"
        sleep 6
        if systemctl is-active --quiet wdtt && [ "$(systemctl show -p NRestarts --value wdtt)" = "0" ]; then
          echo "{verify_ok}"
        else
          echo "Состояние: $(systemctl is-active wdtt 2>/dev/null), перезапусков: $(systemctl show -p NRestarts --value wdtt 2>/dev/null)"
          journalctl -u wdtt -n 30 --no-pager -o cat 2>/dev/null || true
        fi
    "#
    ))
}

/// deploy.sh's output for the log: colours stripped, its `WDTT_PROGRESS|x|step` lines turned into
/// the step names, apt noise and the secrets markers left out. Success = its own `WDTT_DEPLOY_OK`
/// marker and exit code 0.
pub fn read_deploy_output(output: &str) -> DeployReport {
    super::read_deploy_output(output, "WDTT", |line| {
        line.starts_with("WDTT_ADMIN_PIN|").then_some(None)
    })
}
