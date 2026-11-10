//! olcRTC server installer (`mode: srv`), ported from `OlcRtcServerInstaller.kt` and
//! `SshOlcRtcServerInstaller.kt`.
//!
//! An olcRTC server listens on no port: it joins a room of the chosen video-call service itself and
//! opens the outbound connections from there. The "server" is therefore one process per room - as
//! many rooms as the client runs (multi-room), and they must match one to one. The encryption key is
//! generated ON the VPS and comes back on a `RESULT::` line; both sides must use the same key.

use std::sync::LazyLock;

use regex_lite::Regex;

use super::ssh::{one_shot, upload, Ssh, SshTarget};
use super::{
    detect_arch, fail, go_arch, load_server_binary_gz, output_lines, require, require_ssh,
};
use super::{InstallError, ServerBinarySource};
use crate::util::{is_blank, kotlin_lines, trim_indent};

const REMOTE_GZ: &str = "/tmp/olcrtc.gz";

/// Services the vendored binary supports (`internal/auth`). wbstream is left out: it needs a WB
/// account token the installer does not ask for.
pub const OLCRTC_PROVIDERS: [&str; 5] = ["mtslink", "vkcalls", "salutejazz", "jitsi", "telemost"];

/// Transports shared by the server and the client.
pub const OLCRTC_TRANSPORTS: [&str; 4] =
    ["datachannel", "vp8channel", "seichannel", "videochannel"];

/// At most this many rooms - as many as the client's multi-room handles.
pub const OLCRTC_MAX_ROOMS: usize = 5;

/// Rooms land in YAML on someone else's machine, so only what a room link is made of passes. Spaces,
/// quotes and newlines are rejected here rather than "escaped" later: any of them in the config is
/// either broken YAML or an injection.
static ROOM_ALLOWED: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[A-Za-z0-9._~:/?#\[\]@!&*+,;=%-]{1,300}$").unwrap());
static DNS_ALLOWED: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[A-Za-z0-9._:-]{3,64}:[0-9]{1,5}$").unwrap());

/// Inputs of the one-tap install.
#[derive(Clone, PartialEq, Eq)]
pub struct OlcRtcInstallOptions {
    pub host: String,
    pub ssh_port: i32,
    pub login: String,
    pub ssh_password: String,
    /// PEM/OpenSSH private key for SSH publickey auth; when set it is used instead of the password.
    pub ssh_key: String,
    /// Passphrase for an encrypted [`ssh_key`](Self::ssh_key); empty for an unencrypted key.
    pub ssh_key_passphrase: String,
    /// Video-call service, one of [`OLCRTC_PROVIDERS`].
    pub provider: String,
    /// Transport inside the call, one of [`OLCRTC_TRANSPORTS`].
    pub transport: String,
    /// Rooms, one per process - exactly the ones the client runs.
    pub rooms: Vec<String>,
    /// The server's DNS resolver, `host:port`.
    pub dns: String,
}

impl Default for OlcRtcInstallOptions {
    fn default() -> Self {
        Self {
            host: String::new(),
            ssh_port: 22,
            login: "root".into(),
            ssh_password: String::new(),
            ssh_key: String::new(),
            ssh_key_passphrase: String::new(),
            provider: "jitsi".into(),
            transport: "vp8channel".into(),
            rooms: Vec::new(),
            dns: "8.8.8.8:53".into(),
        }
    }
}

impl OlcRtcInstallOptions {
    fn validate(&self, need_rooms: bool) -> Result<SshTarget, InstallError> {
        let target = SshTarget {
            host: self.host.clone(),
            port: self.ssh_port,
            login: self.login.clone(),
            password: self.ssh_password.clone(),
            private_key: self.ssh_key.clone(),
            passphrase: self.ssh_key_passphrase.clone(),
        };
        require_ssh(&target)?;
        if need_rooms {
            require(
                self.rooms.iter().any(|r| !is_blank(r)),
                "Не задана ни одна комната",
            )?;
        }
        Ok(target)
    }
}

/// What the install produced: the shared key (generated on the VPS) and the rooms actually brought
/// up - all a client location needs; olcRTC has no address or port, everything goes through the room.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OlcRtcInstallResult {
    pub crypto_key: String,
    pub rooms: Vec<String>,
    pub provider: String,
    pub transport: String,
    pub status: String,
}

/// Installs (or reinstalls) the server, streaming progress through `on_log`.
pub fn install(
    ssh: &dyn Ssh,
    binaries: &dyn ServerBinarySource,
    options: &OlcRtcInstallOptions,
    on_log: &dyn Fn(&str),
) -> Result<OlcRtcInstallResult, InstallError> {
    let target = options.validate(true)?;
    // Built BEFORE the upload: it also checks rooms, transport and DNS, and there is no point
    // pushing 11 MB to the VPS to fail on a typo in a link afterwards.
    let script = build_install_script(options)?;

    let arch = detect_arch(ssh, &target, on_log, go_arch, "x86_64 или aarch64")?;

    let gz = load_server_binary_gz(binaries, &format!("olcrtc/olcrtc-linux-{arch}"))?;
    on_log(&format!(
        "Загрузка olcRTC ({} КБ, по частям)…",
        gz.len() / 1024
    ));
    upload(ssh, &target, &gz, REMOTE_GZ, on_log)?;
    on_log(&format!(
        "Бинарник загружен, ставлю {} комнат(ы)…",
        options.rooms.len()
    ));

    let output = one_shot(ssh, &target, &script, on_log, false)?;
    output_lines(&output, |l| !l.starts_with("RESULT::")).for_each(on_log);

    let key = kotlin_lines(&output)
        .into_iter()
        .map(str::trim)
        .find(|l| l.starts_with("RESULT::"))
        .map(|l| &l["RESULT::".len()..])
        .filter(|k| {
            k.len() == 64
                && k.chars()
                    .all(|c| c.is_ascii_digit() || ('a'..='f').contains(&c))
        });
    let Some(key) = key else {
        return fail("Сервер не вернул ключ шифрования (RESULT). См. лог выше.");
    };

    on_log(&format!("Готово: olcRTC поднят на {}", options.host));
    Ok(OlcRtcInstallResult {
        crypto_key: key.to_string(),
        rooms: options.rooms.iter().map(|r| r.trim().to_string()).collect(),
        provider: options.provider.clone(),
        transport: options.transport.clone(),
        status: format!(
            "olcRTC установлен: {} комнат(ы), {}/{}",
            options.rooms.len(),
            options.provider,
            options.transport
        ),
    })
}

/// Removes everything [`install`] set up: every room's service, the configs, the key and the binary.
pub fn uninstall(
    ssh: &dyn Ssh,
    options: &OlcRtcInstallOptions,
    on_log: &dyn Fn(&str),
) -> Result<String, InstallError> {
    let target = options.validate(false)?;
    on_log(&format!("Удаляю olcRTC с {}…", options.host));
    let output = one_shot(ssh, &target, &build_uninstall_script(), on_log, false)?;
    output_lines(&output, |l| !l.starts_with("REMOVED::")).for_each(on_log);
    // The script runs under `set -e`, so the marker at its end means it got there without errors.
    if !output.contains("REMOVED::olcrtc") {
        return fail("Сервер не подтвердил удаление — см. лог выше");
    }
    Ok("olcRTC удалён: службы всех комнат, конфиги, ключ и бинарник".into())
}

/// Checks a room link (see [`ROOM_ALLOWED`]) and returns it trimmed.
pub fn sanitize_room(room: &str) -> Result<String, InstallError> {
    let trimmed = room.trim();
    require(
        ROOM_ALLOWED.is_match(trimmed),
        &format!("Недопустимая ссылка на комнату: «{room}»"),
    )?;
    Ok(trimmed.to_string())
}

/// `host:port` - it lands in the config too, so it is checked, not trusted. Blank means 8.8.8.8:53.
pub fn sanitize_dns(dns: &str) -> Result<String, InstallError> {
    let trimmed = if is_blank(dns) {
        "8.8.8.8:53"
    } else {
        dns.trim()
    };
    require(DNS_ALLOWED.is_match(trimmed), "DNS должен быть host:port")?;
    Ok(trimmed.to_string())
}

/// Room names for jitsi, where entering a room creates it (no provider in the vendored tree
/// implements room creation, so `mode: gen` does not work for them). The other services hand out
/// the link themselves and the user pastes it.
pub fn generate_jitsi_rooms(instance: &str, count: i32) -> Vec<String> {
    let host = instance.trim().trim_end_matches('/');
    let host = if is_blank(host) {
        "https://meet.jit.si"
    } else {
        host
    };
    let host = if host.starts_with("http://") || host.starts_with("https://") {
        host.to_string()
    } else {
        format!("https://{host}")
    };
    const ALPHABET: &[u8] = b"abcdefghijklmnopqrstuvwxyz0123456789";
    (0..count.clamp(1, OLCRTC_MAX_ROOMS as i32))
        .map(|_| {
            let name: String = (0..16)
                .map(|_| ALPHABET[super::random_below(ALPHABET.len())] as char)
                .collect();
            format!("{host}/olc{name}")
        })
        .collect()
}

/// The install script (one SSH command). Idempotent: it first removes the previous install
/// completely (see [`teardown`]), then sets up one process per room.
///
/// The key is generated ON the VPS into its own `olcrtc.key` and the configs point at it through
/// `crypto.key_file`, so the config heredoc can be quoted (`'YAML'`): the shell never looks inside
/// it, and neither the key nor a room link can expand or break anything.
pub fn build_install_script(options: &OlcRtcInstallOptions) -> Result<String, InstallError> {
    let provider = options.provider.to_lowercase();
    require(
        OLCRTC_PROVIDERS.contains(&provider.as_str()),
        &format!("Неизвестный сервис: {provider}"),
    )?;
    let transport = options.transport.to_lowercase();
    require(
        OLCRTC_TRANSPORTS.contains(&transport.as_str()),
        &format!("Неизвестный транспорт: {transport}"),
    )?;
    let dns = sanitize_dns(&options.dns)?;
    let rooms = options
        .rooms
        .iter()
        .map(|r| sanitize_room(r))
        .collect::<Result<Vec<_>, _>>()?;
    require(!rooms.is_empty(), "Нужна хотя бы одна комната")?;
    require(
        rooms.len() <= OLCRTC_MAX_ROOMS,
        &format!("Максимум {OLCRTC_MAX_ROOMS} комнат"),
    )?;

    let head = trim_indent(
        r#"
        set -e
        gunzip -f /tmp/olcrtc.gz
        install -m 0755 /tmp/olcrtc /usr/local/bin/olcrtc
        rm -f /tmp/olcrtc
    "#,
    );

    let key = trim_indent(
        r#"
        mkdir -p /etc/olcrtc
        umask 077
        key=$(openssl rand -hex 32 2>/dev/null || od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
        printf '%s' "$key" > /etc/olcrtc/olcrtc.key
        chmod 600 /etc/olcrtc/olcrtc.key
    "#,
    );

    let units = rooms.iter().enumerate().map(|(index, room)| {
        let n = index + 1;
        trim_indent(&format!(
            r#"
        cat > /etc/olcrtc/srv-{n}.yaml <<'YAML'
        mode: srv
        auth:
          provider: {provider}
        room:
          id: "{room}"
        crypto:
          key_file: "olcrtc.key"
        net:
          transport: {transport}
          dns: "{dns}"
        liveness:
          interval: 10s
          timeout: 15s
          failures: 4
        YAML
        cat > /etc/systemd/system/olcrtc-srv-{n}.service <<UNIT
        [Unit]
        Description=olcRTC server (room {n})
        After=network-online.target
        Wants=network-online.target
        [Service]
        ExecStart=/usr/local/bin/olcrtc /etc/olcrtc/srv-{n}.yaml
        Restart=always
        RestartSec=5
        LimitNOFILE=1048576
        [Install]
        WantedBy=multi-user.target
        UNIT
        systemctl enable --now olcrtc-srv-{n}
        "#
        ))
    });

    let room_numbers = (1..=rooms.len())
        .map(|n| n.to_string())
        .collect::<Vec<_>>()
        .join(" ");
    let room_count = rooms.len();
    let tail = trim_indent(&format!(
        r#"
        systemctl daemon-reload
        sleep 2
        up=0
        for n in {room_numbers}; do
          if systemctl is-active olcrtc-srv-$n >/dev/null 2>&1; then
            up=$(( up + 1 ))
          else
            echo "Служба olcrtc-srv-$n не поднялась:"
            journalctl -u olcrtc-srv-$n -n 10 --no-pager 2>/dev/null || true
          fi
        done
        echo "Запущено комнат: $up из {room_count}"
        [ "$up" -gt 0 ] || {{ echo "Ни одна комната не поднялась"; exit 1; }}
        echo "RESULT::$key"
    "#
    ));

    let mut parts = vec![head, teardown(), key];
    parts.extend(units);
    parts.push(tail);
    Ok(parts.join("\n"))
}

/// Stops and removes every olcRTC service and its config. One block for install and uninstall:
/// otherwise a reinstall with fewer rooms would leave stray processes sitting in old rooms.
pub fn teardown() -> String {
    trim_indent(
        r#"
        for f in /etc/systemd/system/olcrtc-srv-*.service; do
          [ -e "$f" ] || continue
          u=$(basename "$f" .service)
          systemctl disable --now "$u" 2>/dev/null || true
          rm -f "$f"
        done
        rm -f /etc/olcrtc/srv-*.yaml
        systemctl daemon-reload
    "#,
    )
}

/// Full removal: services, configs, key and binary. The binary lives at our own path
/// (`/usr/local/bin/olcrtc`; the olcrtc-inbound panel keeps its own), so nobody else is affected.
pub fn build_uninstall_script() -> String {
    let tail = trim_indent(
        r#"
        rm -rf /etc/olcrtc
        rm -f /usr/local/bin/olcrtc
        echo "REMOVED::olcrtc"
    "#,
    );
    ["set -e".to_string(), teardown(), tail].join("\n")
}
