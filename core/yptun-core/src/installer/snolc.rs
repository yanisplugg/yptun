//! snolc exit-node installer, ported from `SnolcServerInstaller.kt` and `SshSnolcServerInstaller.kt`:
//! the same shape as the OpenFlux one - `uname -m`, the gzip'd binary, one install script. The node's
//! Noise keys survive a reinstall, so links already handed out keep working.

use super::ssh::{one_shot, upload, Ssh, SshTarget};
use super::{detect_arch, fail, go_arch, load_server_binary_gz, require, require_ssh};
use super::{InstallError, ServerBinarySource};
use crate::snolc;
use crate::transports::{SnolcConfig, SNOLC_DEFAULT_PORT};
use crate::util::{kotlin_lines, trim_indent};

const REMOTE_GZ: &str = "/tmp/snolc.gz";

/// The line the install script prints the node's public key on.
pub const KEY_MARK: &str = "SNOLC_PUBLIC_KEY=";

/// Inputs of the one-tap install: SSH access plus the TCP port the node listens on.
#[derive(Clone, PartialEq, Eq)]
pub struct SnolcInstallOptions {
    pub host: String,
    pub ssh_port: i32,
    pub login: String,
    pub ssh_password: String,
    /// PEM/OpenSSH private key for SSH publickey auth; when set it is used instead of the password.
    pub ssh_key: String,
    /// Passphrase for an encrypted [`ssh_key`](Self::ssh_key); empty for an unencrypted key.
    pub ssh_key_passphrase: String,
    pub listen_port: i32,
}

impl Default for SnolcInstallOptions {
    fn default() -> Self {
        Self {
            host: String::new(),
            ssh_port: 22,
            login: "root".into(),
            ssh_password: String::new(),
            ssh_key: String::new(),
            ssh_key_passphrase: String::new(),
            listen_port: SNOLC_DEFAULT_PORT,
        }
    }
}

/// Installs (or upgrades) the node. `Ok` carries the `snolc://host:port?key=…` link of the node.
pub fn install(
    ssh: &dyn Ssh,
    binaries: &dyn ServerBinarySource,
    options: &SnolcInstallOptions,
    on_log: &dyn Fn(&str),
) -> Result<String, InstallError> {
    let target = SshTarget {
        host: options.host.clone(),
        port: options.ssh_port,
        login: options.login.clone(),
        password: options.ssh_password.clone(),
        private_key: options.ssh_key.clone(),
        passphrase: options.ssh_key_passphrase.clone(),
    };
    require_ssh(&target)?;
    require(
        (1..=65535).contains(&options.listen_port),
        "Порт вне диапазона 1–65535",
    )?;

    let arch = detect_arch(ssh, &target, on_log, go_arch, "x86_64 или aarch64")?;

    let gz = load_server_binary_gz(binaries, &format!("snolc/snolc-server-linux-{arch}"))?;
    on_log(&format!(
        "Загрузка snolc ({} КБ, по частям)…",
        gz.len() / 1024
    ));
    upload(ssh, &target, &gz, REMOTE_GZ, on_log)?;
    on_log("Бинарник загружен, ставлю службу…");

    let output = one_shot(
        ssh,
        &target,
        &build_install_script(options.listen_port),
        on_log,
        false,
    )?;
    let lines: Vec<&str> = kotlin_lines(&output).into_iter().map(str::trim).collect();
    lines
        .iter()
        .filter(|l| !l.is_empty() && !l.starts_with(KEY_MARK))
        .for_each(|l| on_log(l));
    if let Some(line) = lines.iter().find(|l| l.starts_with("ОШИБКА:")) {
        return fail(line["ОШИБКА:".len()..].trim());
    }
    let Some(key) = lines
        .iter()
        .find_map(|l| l.strip_prefix(KEY_MARK))
        .map(str::trim)
    else {
        return fail("Нода запущена, но публичный ключ не получен");
    };
    let config = SnolcConfig {
        host: options.host.trim().to_string(),
        port: options.listen_port,
        public_key: key.to_string(),
        ..Default::default()
    };
    if !config.is_complete() {
        return fail(format!("Нода вернула некорректный ключ: {key}"));
    }
    Ok(config.to_uri(""))
}

/// The remote script. Keys survive a reinstall; the config is rewritten every time so a new `port`
/// takes effect. An earlier install is replaced wholesale.
pub fn build_install_script(port: i32) -> String {
    let writes = snolc::server(port, "/etc/snolc/priv.hex", "/var/lib/snolc")
        .iter()
        .map(|(path, body)| {
            format!(
                "cat > /etc/snolc/{path} <<'SNOLCEOF'\n{}\nSNOLCEOF",
                body.trim_end()
            )
        })
        .collect::<Vec<_>>()
        .join("\n");
    let key_mark = KEY_MARK;
    trim_indent(
        r#"
        set -e
        gunzip -f /tmp/snolc.gz
        systemctl disable --now snolc >/dev/null 2>&1 || true
        install -m 0755 /tmp/snolc /usr/local/bin/snolc
        rm -f /tmp/snolc
        mkdir -p /etc/snolc/modules /var/lib/snolc
        chmod 700 /etc/snolc
        if [ ! -s /etc/snolc/priv.hex ]; then
          snolc keygen /etc/snolc/priv.hex /etc/snolc/pub.hex >/dev/null
        fi
        chmod 600 /etc/snolc/priv.hex
    "#,
    ) + "\n"
        + &writes
        + "\n"
        + &trim_indent(&format!(
            r#"
        snolc validate /etc/snolc/snolc.toml >/dev/null || {{ echo "ОШИБКА: snolc не принял конфигурацию"; exit 1; }}
        cat > /etc/systemd/system/snolc.service <<'UNIT'
        [Unit]
        Description=snolc exit node
        After=network-online.target
        Wants=network-online.target
        [Service]
        ExecStart=/usr/local/bin/snolc run /etc/snolc/snolc.toml
        Restart=always
        RestartSec=5
        LimitNOFILE=65536
        [Install]
        WantedBy=multi-user.target
        UNIT
        systemctl daemon-reload
        systemctl enable --now snolc
        if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
          ufw allow {port}/tcp >/dev/null && echo "ufw: порт {port}/tcp открыт"
        fi
        sleep 2
        systemctl is-active --quiet snolc || {{ echo "ОШИБКА: служба snolc не запустилась: $(journalctl -u snolc -n 5 -o cat --no-pager | tail -3)"; exit 1; }}
        (ss -ltn 2>/dev/null | grep -q ":{port} ") || {{ echo "ОШИБКА: порт {port} не слушается"; exit 1; }}
        echo "Служба snolc активна, слушает порт {port}"
        echo "{key_mark}$(cat /etc/snolc/pub.hex)"
    "#
        ))
}
