//! OpenFlux exit-node installer, ported from `OpenFluxServerInstaller.kt` and
//! `SshOpenFluxServerInstaller.kt`: detects the architecture, uploads the bundled binary, keeps the
//! carrier secrets in a root-only EnvironmentFile and runs the node as a systemd service.

use super::ssh::{one_shot, upload, Ssh, SshTarget};
use super::{detect_arch, fail, go_arch, load_server_binary_gz, require, require_ssh};
use super::{InstallError, ServerBinarySource};
use crate::share_link::starts_ci;
use crate::transports::{OF_AVITO, OF_MAX, OF_TRANSPORTS, OF_YANDEX};
use crate::util::{is_blank, kotlin_lines, trim_indent};

const REMOTE_GZ: &str = "/tmp/openflux.gz";

/// Inputs of the one-tap install: SSH access plus the carrier the node logs into.
#[derive(Clone, PartialEq, Eq)]
pub struct OpenFluxInstallOptions {
    pub host: String,
    pub ssh_port: i32,
    pub login: String,
    pub ssh_password: String,
    /// PEM/OpenSSH private key for SSH publickey auth; when set it is used instead of the password.
    pub ssh_key: String,
    /// Passphrase for an encrypted [`ssh_key`](Self::ssh_key); empty for an unencrypted key.
    pub ssh_key_passphrase: String,
    /// One of [`OF_TRANSPORTS`]; anything else installs the Yandex carrier. Avito Code is not
    /// installable (it needs the account cookies).
    pub transport: String,
    /// The shared document / room link (every carrier but MAX).
    pub doc_url: String,
    /// The exit node's own MAX token (the MAX carrier only).
    pub exit_max_token: String,
}

impl Default for OpenFluxInstallOptions {
    fn default() -> Self {
        Self {
            host: String::new(),
            ssh_port: 22,
            login: "root".into(),
            ssh_password: String::new(),
            ssh_key: String::new(),
            ssh_key_passphrase: String::new(),
            transport: OF_YANDEX.into(),
            doc_url: String::new(),
            exit_max_token: String::new(),
        }
    }
}

/// Installs (or upgrades) the exit node. `Ok` carries the line for the user.
pub fn install(
    ssh: &dyn Ssh,
    binaries: &dyn ServerBinarySource,
    options: &OpenFluxInstallOptions,
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
        options.transport != OF_AVITO,
        "Для Avito Code нода ставится вручную (ей нужны cookie аккаунта и она сама печатает список комнат): \
         по инструкции OpenFlux, потом вставь список комнат в приложение",
    )?;
    if options.transport == OF_MAX {
        require(
            !is_blank(&options.exit_max_token),
            "Не указан токен MAX выходной ноды",
        )?;
    } else {
        require(
            starts_ci(&options.doc_url, "http"),
            "Не указана ссылка на документ/комнату",
        )?;
    }

    let arch = detect_arch(ssh, &target, on_log, go_arch, "x86_64 или aarch64")?;

    let gz = load_server_binary_gz(binaries, &format!("openflux/openflux-server-linux-{arch}"))?;
    on_log(&format!(
        "Загрузка выходной ноды ({} КБ, по частям)…",
        gz.len() / 1024
    ));
    upload(ssh, &target, &gz, REMOTE_GZ, on_log)?;
    on_log("Бинарник загружен, ставлю службу…");

    let output = one_shot(ssh, &target, &build_install_script(options), on_log, false)?;
    let lines = kotlin_lines(&output);
    lines
        .iter()
        .map(|l| l.trim())
        .filter(|l| !l.is_empty())
        .for_each(on_log);
    if let Some(line) = lines
        .iter()
        .map(|l| l.trim())
        .find(|l| l.starts_with("ОШИБКА:"))
    {
        return fail(line["ОШИБКА:".len()..].trim());
    }
    Ok(format!(
        "Выходная нода OpenFlux запущена на {}",
        options.host
    ))
}

/// A value for a systemd EnvironmentFile: double-quoted, with `\` and `"` escaped.
fn env_file_value(value: &str) -> String {
    format!(
        "\"{}\"",
        value
            .replace('\\', "\\\\")
            .replace('"', "\\\"")
            .replace('\n', "")
    )
}

/// The remote install script. The carrier coordinates live in a root-only EnvironmentFile and reach
/// the binary as `${VAR}` words: a Yandex Docs URL is full of `%XX`, which systemd would read as unit
/// specifiers if it sat on the ExecStart line, and a MAX token must not be world-readable.
///
/// The exit node forwards through raw sockets, so the kernel must not answer the forwarded flows with
/// RST. The old l3 exit needed a host-wide `iptables -A OUTPUT -p tcp --tcp-flags RST RST -j DROP`;
/// the l4 one does not, so a rule left over by an old install is removed.
pub fn build_install_script(options: &OpenFluxInstallOptions) -> String {
    let transport = if OF_TRANSPORTS.contains(&options.transport.as_str()) {
        options.transport.as_str()
    } else {
        OF_YANDEX
    };
    let rst = "OUTPUT -p tcp --tcp-flags RST RST -j DROP";
    let exec_args = if transport == OF_MAX {
        "--role=exit --mode=l4 --transport ${OPENFLUX_TRANSPORT}"
    } else {
        "--role=exit --mode=l4 --transport ${OPENFLUX_TRANSPORT} --url ${OPENFLUX_DOC_URL}"
    };
    let transport_value = env_file_value(transport);
    let doc_url = env_file_value(options.doc_url.trim());
    let max_token = env_file_value(options.exit_max_token.trim());
    trim_indent(&format!(
        r#"
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
          while iptables -D {rst} 2>/dev/null; do :; done
        fi
        install -m 0755 /tmp/openflux /usr/local/bin/openflux
        rm -f /tmp/openflux
        mkdir -p /etc/openflux
        chmod 700 /etc/openflux
        cat > /etc/openflux/openflux.env <<'ENVFILE'
        OPENFLUX_TRANSPORT={transport_value}
        OPENFLUX_DOC_URL={doc_url}
        OPENFLUX_MAX_TOKEN={max_token}
        ENVFILE
        chmod 600 /etc/openflux/openflux.env
        cat > /etc/systemd/system/openflux.service <<'UNIT'
        [Unit]
        Description=OpenFlux exit node
        After=network-online.target
        Wants=network-online.target
        [Service]
        EnvironmentFile=/etc/openflux/openflux.env
        ExecStart=/usr/local/bin/openflux {exec_args}
        Restart=always
        RestartSec=60
        [Install]
        WantedBy=multi-user.target
        UNIT
        systemctl daemon-reload
        systemctl enable --now openflux
        # Upstream exits on a failed carrier login; with Restart=always the unit still looks "active",
        # so read the journal for the verdict (RestartSec=60 keeps a failing node from hammering the
        # carrier, which only deepens a captcha).
        sleep 8
        fail=$(journalctl -u openflux --since "-15s" -o cat 2>/dev/null | grep "Failed to start transport" | tail -1)
        if [ -n "$fail" ]; then
          if echo "$fail" | grep -q captcha; then
            echo "ОШИБКА: Яндекс показывает IP этого VPS капчу — нода не может войти в документ. Попробуй другой VPS или транспорт (Mail.ru, cups.online)."
          else
            echo "ОШИБКА: нода не подключилась к площадке: ${{fail#*Failed to start transport: }}"
          fi
          exit 1
        fi
        systemctl is-active openflux && echo "Служба openflux активна (транспорт: {transport})"
    "#
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn env_file_values_are_quoted_and_escaped() {
        assert_eq!(env_file_value(r#"a\b"c"#), r#""a\\b\"c""#);
        assert_eq!(env_file_value("x\ny"), "\"xy\"");
    }

    #[test]
    fn unknown_transport_installs_yandex() {
        let options = OpenFluxInstallOptions {
            transport: "bogus".into(),
            ..Default::default()
        };
        assert!(build_install_script(&options)
            .lines()
            .any(|l| l == "OPENFLUX_TRANSPORT=\"yandex\""));
    }
}
