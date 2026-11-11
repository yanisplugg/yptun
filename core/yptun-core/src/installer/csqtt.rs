//! csqtt server installer, ported from `CsqttServerInstaller.kt` and `SshCsqttServerInstaller.kt`.
//!
//! Deploys the way the csqtt app itself does: its own `deploy.sh` (bundled as `csqtt/deploy.sh` -
//! distro detection, prerequisites, sysctl, NAT/firewall, TLS, systemd unit) is uploaded with the
//! server binary for the VPS architecture, plus the two small files the script expects next to them
//! (web panel login, main password), and run as root. Around it: a port another program holds (UDP
//! peer port, TCP web port) is moved to the next free one and the location follows; and "installed"
//! means the service stays up - deploy.sh checks once, and it is checked again a few seconds later
//! (restart counter), with the journal on failure.
//!
//! The connection password must match the one in the location: the WRAP key is derived from it on
//! BOTH sides.

use base64::Engine as _;

use super::ssh::{one_shot, upload, Ssh, SshTarget};
use super::{
    detect_arch, fail, load_server_binary_gz, output_lines, random_below, require, require_ssh,
};
use super::{DeployReport, InstallError, ServerBinarySource};
use crate::util::{is_blank, kotlin_lines, trim_indent};

const REMOTE_GZ: &str = "/tmp/.csqtt-upload-server.gz";
const REMOTE_SCRIPT: &str = "/tmp/deploy.sh";
const DEPLOY_SCRIPT_ASSET: &str = "csqtt/deploy.sh";

pub const PORTS_MARKER: &str = "CSQTT_PORTS=";
pub const VERIFY_OK: &str = "CSQTT_SERVICE_STABLE";

/// Inputs of the one-tap install: SSH access plus the ports and the connection password the server
/// is launched with.
#[derive(Clone, PartialEq, Eq)]
pub struct CsqttInstallOptions {
    pub host: String,
    pub ssh_port: i32,
    pub login: String,
    pub ssh_password: String,
    /// PEM/OpenSSH private key for SSH publickey auth; when set it is used instead of the password.
    pub ssh_key: String,
    /// Passphrase for an encrypted [`ssh_key`](Self::ssh_key); empty for an unencrypted key.
    pub ssh_key_passphrase: String,
    /// UDP port the clients dial (csqtt's "peer" port).
    pub peer_port: i32,
    /// HTTPS port of the server's web panel.
    pub web_port: i32,
    /// The connection password.
    pub password: String,
    /// Web panel login / password; blank = `admin` / generated, returned in the result.
    pub web_login: String,
    pub web_password: String,
}

impl Default for CsqttInstallOptions {
    fn default() -> Self {
        Self {
            host: String::new(),
            ssh_port: 22,
            login: "root".into(),
            ssh_password: String::new(),
            ssh_key: String::new(),
            ssh_key_passphrase: String::new(),
            peer_port: 46000,
            web_port: 46002,
            password: String::new(),
            web_login: String::new(),
            web_password: String::new(),
        }
    }
}

/// `peer_port` / `web_port` are what the server really runs on: the requested ones, or the next free
/// ones when another program holds them (the location then has to dial `peer_port`). The web panel
/// credentials (generated when none were asked for) manage keys for more clients and hashes.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CsqttInstallResult {
    pub message: String,
    pub peer_port: i32,
    pub web_port: i32,
    pub web_login: String,
    pub web_password: String,
}

/// The ports the prepare step picked.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CsqttPorts {
    pub peer: i32,
    pub web: i32,
}

/// Installs (or upgrades) the server, streaming progress through `on_log`.
pub fn install(
    ssh: &dyn Ssh,
    binaries: &dyn ServerBinarySource,
    options: &CsqttInstallOptions,
    on_log: &dyn Fn(&str),
) -> Result<CsqttInstallResult, InstallError> {
    let target = SshTarget {
        host: options.host.clone(),
        port: options.ssh_port,
        login: options.login.clone(),
        password: options.ssh_password.clone(),
        private_key: options.ssh_key.clone(),
        passphrase: options.ssh_key_passphrase.clone(),
    };
    require_ssh(&target)?;
    require(!is_blank(&options.password), "Не указан пароль csqtt")?;
    require(
        !options.password.chars().any(char::is_whitespace),
        "В пароле csqtt не должно быть пробелов",
    )?;

    let arch = detect_arch(
        ssh,
        &target,
        on_log,
        server_arch,
        "x86_64, aarch64 или armv7",
    )?;

    on_log("Готовлю VPS: свободные порты…");
    let prepared = one_shot(
        ssh,
        &target,
        &as_root(&build_prepare_script(options.peer_port, options.web_port)),
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
        "Порты: peer {}/udp, веб-панель {}/tcp",
        ports.peer, ports.web
    ));

    let gz = load_server_binary_gz(binaries, &format!("csqtt/csqtt-server-linux-{arch}"))?;
    on_log(&format!("Загрузка сервера csqtt ({} КБ)…", gz.len() / 1024));
    upload(ssh, &target, &gz, REMOTE_GZ, on_log)?;
    // CR stripped: a Windows checkout can hand the asset over with CRLF, and bash on the VPS then
    // dies on the first line.
    let Some(script) = binaries.bytes(DEPLOY_SCRIPT_ASSET) else {
        return fail(format!("В сборке нет установщика {DEPLOY_SCRIPT_ASSET}"));
    };
    let script = String::from_utf8_lossy(&script).replace('\r', "");
    on_log("Загрузка установщика csqtt…");
    upload(ssh, &target, script.as_bytes(), REMOTE_SCRIPT, on_log)?;

    let web_login = if is_blank(&options.web_login) {
        "admin".to_string()
    } else {
        options.web_login.clone()
    };
    let web_password = if is_blank(&options.web_password) {
        random_token(12)
    } else {
        options.web_password.clone()
    };
    on_log("Установка csqtt (пакеты, сеть, служба) — может занять несколько минут…");
    let command = as_root(&build_deploy_command(
        options,
        &ports,
        &web_login,
        &web_password,
        options.ssh_port,
    ));
    let output = one_shot(ssh, &target, &command, on_log, false)?;
    let report = read_deploy_output(&output);
    report.lines.iter().for_each(|l| on_log(l));
    if !report.ok {
        let code = report.exit_code.map_or("?".to_string(), |c| c.to_string());
        return fail(format!(
            "Установщик csqtt не завершился успехом (код {code}). Подробности — в строках журнала выше."
        ));
    }

    on_log("Проверяю, что служба держится…");
    let verify = one_shot(
        ssh,
        &target,
        &as_root(&build_verify_script()),
        on_log,
        false,
    )?;
    if !verify.contains(VERIFY_OK) {
        return fail(format!(
            "Служба csqtt не держится после запуска. Журнал сервера:\n{}",
            verify.trim()
        ));
    }

    Ok(CsqttInstallResult {
        message: format!(
            "csqtt установлен и запущен на {host}:{peer}. Веб-панель: https://{host}:{web} ({web_login} / {web_password}). \
             Основной пароль привязывается к первому подключившемуся устройству — для остальных создай ключи в веб-панели.",
            host = options.host,
            peer = ports.peer,
            web = ports.web
        ),
        peer_port: ports.peer,
        web_port: ports.web,
        web_login,
        web_password,
    })
}

/// `uname -m` → the suffix of the bundled server binary (`csqtt-server-linux-<arch>.gz`).
pub fn server_arch(machine: &str) -> Option<&'static str> {
    if machine.contains("aarch64") || machine.contains("arm64") {
        Some("arm64")
    } else if machine.contains("x86_64") || machine.contains("amd64") {
        Some("amd64")
    } else if machine.starts_with("armv7") || machine.starts_with("armv8l") || machine == "arm" {
        Some("armv7")
    } else {
        None
    }
}

/// The last `CSQTT_PORTS=peer|web` line; None unless both are valid ports.
pub fn parse_ports(output: &str) -> Option<CsqttPorts> {
    let line = kotlin_lines(output)
        .into_iter()
        .map(str::trim)
        .rev()
        .find(|l| l.starts_with(PORTS_MARKER))?;
    let parts: Vec<Option<i32>> = line[PORTS_MARKER.len()..]
        .split('|')
        .map(|p| p.trim().parse().ok())
        .collect();
    if parts.len() != 2
        || parts
            .iter()
            .any(|p| !p.is_some_and(|p| (1..=65535).contains(&p)))
    {
        return None;
    }
    Some(CsqttPorts {
        peer: parts[0].unwrap(),
        web: parts[1].unwrap(),
    })
}

/// Runs `script` as root: directly when the login is root, else through `sudo -n`. The script
/// travels as base64.
pub fn as_root(script: &str) -> String {
    let b64 = base64::engine::general_purpose::STANDARD.encode(script.as_bytes());
    format!(
        "printf '%s' '{b64}' | base64 -d > /tmp/.csqtt-step.sh; \
         if [ \"$(id -u)\" = \"0\" ]; then bash /tmp/.csqtt-step.sh; \
         elif command -v sudo >/dev/null 2>&1; then sudo -n bash /tmp/.csqtt-step.sh; \
         else echo 'нужны права root (или sudo без пароля)'; exit 1; fi; \
         rc=$?; rm -f /tmp/.csqtt-step.sh; exit $rc"
    )
}

/// Picks the ports before deploy.sh: the requested ones when free (or already held by the csqtt of a
/// previous install - a redeploy takes them over), else the next free ones. Prints
/// `CSQTT_PORTS=peer|web`.
pub fn build_prepare_script(peer_port: i32, web_port: i32) -> String {
    let ports_marker = PORTS_MARKER;
    trim_indent(&format!(
        r#"
        udp_owner() {{ ss -Hulnp "sport = :$1" 2>/dev/null | grep -o 'users:(("[^"]*' | head -1 | cut -d'"' -f2; }}
        tcp_owner() {{ ss -Htlnp "sport = :$1" 2>/dev/null | grep -o 'users:(("[^"]*' | head -1 | cut -d'"' -f2; }}
        udp_busy() {{ ss -Hulnp "sport = :$1" 2>/dev/null | grep -q .; }}
        tcp_busy() {{ ss -Htlnp "sport = :$1" 2>/dev/null | grep -q .; }}
        PEER={peer_port}
        while udp_busy $PEER && [ "$(udp_owner $PEER)" != csqtt ]; do
          echo "Порт $PEER/udp занят ($(udp_owner $PEER)) — пробую следующий"
          PEER=$((PEER+1))
        done
        WEB={web_port}
        while [ "$WEB" = "$PEER" ] || {{ tcp_busy $WEB && [ "$(tcp_owner $WEB)" != csqtt ]; }}; do
          echo "Порт $WEB/tcp занят ($(tcp_owner $WEB)) — пробую следующий"
          WEB=$((WEB+1))
        done
        echo "{ports_marker}$PEER|$WEB"
    "#
    ))
}

/// systemd `EnvironmentFile` value: double-quoted, backslash and quote escaped, newlines flattened.
pub fn systemd_environment_value(value: &str) -> String {
    let mut out = String::from("\"");
    for ch in value.chars() {
        match ch {
            '\\' => out.push_str("\\\\"),
            '"' => out.push_str("\\\""),
            '\n' | '\r' => out.push(' '),
            _ => out.push(ch),
        }
    }
    out.push('"');
    out
}

/// Unpacks the binary, writes the two files deploy.sh expects (web panel login, main password -
/// through base64, so no password can break the shell quoting) and runs `deploy.sh install`. Always
/// exits 0 and reports deploy.sh's own exit code as `CSQTT_DEPLOY_EXIT=<n>`, so its output reaches
/// the log on failure.
pub fn build_deploy_command(
    options: &CsqttInstallOptions,
    ports: &CsqttPorts,
    web_login: &str,
    web_password: &str,
    ssh_port: i32,
) -> String {
    let enc = |text: &str| base64::engine::general_purpose::STANDARD.encode(text.as_bytes());
    let env = format!(
        "CSQTT_WEB_USER={}\nCSQTT_WEB_PASS={}\n",
        systemd_environment_value(web_login),
        systemd_environment_value(web_password)
    );
    // An empty device id: the main password binds to the first device that connects with it.
    let overrides =
        serde_json::json!({ "main_password": options.password, "device_id": "" }).to_string();
    let (env_b64, overrides_b64, peer, web) = (enc(&env), enc(&overrides), ports.peer, ports.web);
    trim_indent(&format!(
        r#"
        set -e
        gunzip -f /tmp/.csqtt-upload-server.gz
        printf '%s' '{env_b64}' | base64 -d > /tmp/.csqtt-upload-web.env
        printf '%s' '{overrides_b64}' | base64 -d > /tmp/.csqtt-upload-overrides.json
        chmod 600 /tmp/.csqtt-upload-web.env /tmp/.csqtt-upload-overrides.json
        set +e
        env CSQTT_PEER_PORT={peer} CSQTT_SSH_PORT={ssh_port} CSQTT_WEB_PORT={web} CSQTT_DEPLOY_MODE=systemd bash /tmp/deploy.sh install 2>&1
        echo "CSQTT_DEPLOY_EXIT=$?"
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
        if systemctl is-active --quiet csqtt && [ "$(systemctl show -p NRestarts --value csqtt)" = "0" ]; then
          echo "{verify_ok}"
        else
          echo "Состояние: $(systemctl is-active csqtt 2>/dev/null), перезапусков: $(systemctl show -p NRestarts --value csqtt 2>/dev/null)"
          journalctl -u csqtt -n 30 --no-pager -o cat 2>/dev/null || true
        fi
    "#
    ))
}

/// deploy.sh's output for the log: colours stripped, its `CSQTT_PROGRESS|x|step` lines turned into
/// the step names, `CSQTT_DEPLOY_ERROR|phase|message` into a readable error, apt noise left out.
/// Success = its own `CSQTT_DEPLOY_OK` marker and exit code 0.
pub fn read_deploy_output(output: &str) -> DeployReport {
    super::read_deploy_output(output, "CSQTT", |line| {
        line.starts_with("CSQTT_DEPLOY_ERROR|").then(|| {
            let mut parts = line.splitn(3, '|').skip(1);
            let phase = parts.next().unwrap_or("");
            let message = parts.next().unwrap_or("");
            Some(format!("ОШИБКА ({phase}): {message}"))
        })
    })
}

/// A web panel password: `length` characters from an alphabet without look-alikes (no l/o/0/1/I/O).
fn random_token(length: usize) -> String {
    const ALPHABET: &[u8] = b"abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    (0..length)
        .map(|_| ALPHABET[random_below(ALPHABET.len())] as char)
        .collect()
}
