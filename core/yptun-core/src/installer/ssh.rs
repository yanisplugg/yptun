//! The SSH layer every installer runs on, ported from `SshSupport.kt`.
//!
//! The platform supplies only the wire ([`Ssh`]: "log in and run one command", "log in and write one
//! file over SFTP"); the core keeps what decides whether an install succeeds or fails the same way on
//! every platform: credential clean-up, the progress log, the hints shown when a login is refused,
//! the exit-code check, and the upload strategy with its checksum verification.
//!
//! **Every call is its own fresh connection.** One real VPS resets the link the moment a SECOND
//! channel is opened on a connection, so reusing a session for several commands - or streaming a
//! binary through one channel's stdin - always died there. A login per step is slower but works
//! everywhere, and it is what the Kotlin installers did.

use base64::Engine as _;
use sha2::{Digest, Sha256};

use super::InstallError;
use crate::util::{is_blank, shell_single_quote};

/// Connect timeout the platform should use for the TCP connect, the SSH handshake and opening a
/// channel (the Kotlin installers' `SSH_CONNECT_TIMEOUT_MS`).
pub const SSH_CONNECT_TIMEOUT_MS: u32 = 25_000;

/// Chunk size of the fallback upload, in base64 characters. The whole command is one argv element to
/// the server's `sh -c`, and Linux caps a single argument at `MAX_ARG_STRLEN` = 128 KiB, so the
/// chunk (plus its `printf '%s' '…' | base64 -d >> …` wrapper) stays comfortably under that. A
/// multiple of 4, so every chunk decodes on its own and the decodes concatenate to the original.
const UPLOAD_CHUNK: usize = 100_000;

/// Connection parameters reused for every one-shot step of an install.
#[derive(Clone, PartialEq, Eq, Default)]
pub struct SshTarget {
    pub host: String,
    pub port: i32,
    /// SSH user; blank means `root`.
    pub login: String,
    pub password: String,
    /// PEM/OpenSSH private key for publickey auth; when non-blank it takes precedence over
    /// [`password`](Self::password).
    pub private_key: String,
    /// Passphrase unlocking an encrypted [`private_key`](Self::private_key); empty for an
    /// unencrypted key.
    pub passphrase: String,
}

impl std::fmt::Debug for SshTarget {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SshTarget")
            .field("host", &self.host)
            .field("port", &self.port)
            .field("login", &self.login)
            .finish_non_exhaustive()
    }
}

/// How the platform authenticates one connection; built by the core from an [`SshTarget`].
#[derive(Clone, PartialEq, Eq)]
pub enum SshAuth {
    /// Offer the `password` method AND `keyboard-interactive` (password-over-PAM - some servers
    /// gate password logins through it only), in that order. Answer every keyboard-interactive
    /// prompt with this password; acknowledge rounds without prompts (banners) without counting
    /// them, and give up after 4 answered rounds so a wrong password still fails cleanly before the
    /// server's `MaxAuthTries`. Whitespace around it was already removed.
    Password(String),
    /// Offer `publickey` only. `private_key` is PEM or OpenSSH text (already trimmed); `passphrase`
    /// unlocks an encrypted key and is empty for a plain one.
    Key {
        private_key: String,
        passphrase: String,
    },
}

impl std::fmt::Debug for SshAuth {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::Password(_) => "Password(..)",
            Self::Key { .. } => "Key(..)",
        })
    }
}

/// One login, as handed to [`Ssh`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SshLogin {
    pub host: String,
    pub port: i32,
    pub user: String,
    pub auth: SshAuth,
}

/// What a remote command left behind.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SshOutput {
    /// The command's exit status; -1 when the server reported none.
    pub exit_code: i32,
    /// stdout and stderr merged into one text, decoded as UTF-8 (invalid bytes replaced).
    pub output: String,
}

/// Why a connection failed. The core turns these into the user-facing messages.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SshError {
    /// The private key could not be read (malformed, not a private key, wrong passphrase).
    Key(String),
    /// The server refused the credentials. Prefer the library's own text when it names the methods
    /// the server offered (JSch: `Auth fail for methods 'publickey,password'`) - it is shown verbatim.
    Auth(String),
    /// Anything else: name resolution, TCP, timeout, the channel or the SFTP subsystem.
    Other(String),
}

/// The platform's SSH client. Both calls open a FRESH connection, do one thing and disconnect; both
/// block until done.
///
/// For both: accept the server's host key without asking (VPSes rarely have a known key on first
/// contact; the authentication still protects the channel), authenticate as described on
/// [`SshAuth`], and honour [`SSH_CONNECT_TIMEOUT_MS`]. When `ssh_log` is set, stream the client's
/// own protocol negotiation (info level and above: offered methods, where it broke) into it, one
/// message per call - that is how a refused login becomes diagnosable.
pub trait Ssh {
    /// Runs `command` on a single `exec` channel (no stdin, no pty) and waits for it to finish.
    fn exec(
        &self,
        login: &SshLogin,
        command: &str,
        ssh_log: Option<&dyn Fn(&str)>,
    ) -> Result<SshOutput, SshError>;

    /// Writes `data` to `remote_path` (overwriting it) on a single SFTP channel.
    fn sftp_put(&self, login: &SshLogin, data: &[u8], remote_path: &str) -> Result<(), SshError>;
}

/// Kotlin `String.length`: the text's length in UTF-16 code units.
fn utf16_len(s: &str) -> usize {
    s.encode_utf16().count()
}

/// Kotlin `String.take(n)`: at most `n` UTF-16 code units (a surrogate pair is never split here).
fn take_utf16(s: &str, n: usize) -> &str {
    let mut units = 0;
    for (i, c) in s.char_indices() {
        units += c.len_utf16();
        if units > n {
            return &s[..i];
        }
    }
    s
}

/// Resolves the login for `target` (Kotlin `openSshSession` up to `connect`): `root` for a blank
/// user, the password trimmed, and - when `log_progress` - the clean-up warning and the
/// "connecting" line.
fn resolve_login(target: &SshTarget, on_log: &dyn Fn(&str), log_progress: bool) -> SshLogin {
    let user = if is_blank(&target.login) {
        "root"
    } else {
        target.login.as_str()
    };
    let use_key = !is_blank(&target.private_key);
    let password = target.password.trim();
    let raw_len = utf16_len(&target.password);
    let len = utf16_len(password);
    if log_progress && !use_key && len != raw_len {
        on_log(&format!(
            "⚠ Пароль содержал пробелы/перевод строки по краям — убрал их \
             (было {raw_len}, стало {len} симв.). \
             Если пароль реально оканчивается пробелом — напиши, отключу очистку."
        ));
    }
    let auth = if use_key {
        SshAuth::Key {
            private_key: target.private_key.trim().to_string(),
            passphrase: target.passphrase.clone(),
        }
    } else {
        SshAuth::Password(password.to_string())
    };
    if log_progress {
        let how = if use_key {
            "ключ".to_string()
        } else {
            format!("пароль {len} симв.")
        };
        on_log(&format!(
            "Подключение к {}:{} (пользователь '{user}', {how})…",
            target.host, target.port
        ));
    }
    SshLogin {
        host: target.host.clone(),
        port: target.port,
        user: user.to_string(),
        auth,
    }
}

/// The user-facing message for a failed connection.
fn connect_error(login: &SshLogin, error: SshError) -> InstallError {
    let use_key = matches!(login.auth, SshAuth::Key { .. });
    let user = &login.user;
    InstallError::Failed(match error {
        SshError::Key(msg) => format!(
            "Не удалось прочитать SSH-ключ: {msg}. Убедись, что вставлен ПРИВАТНЫЙ ключ \
             целиком (строки BEGIN/END включительно). Если ключ с паролем — заполни поле «Пароль ключа»."
        ),
        SshError::Auth(msg) if use_key => format!(
            "Не удалось войти под '{user}' по ключу ({msg}). Проверь: \
             1) публичная часть ключа добавлена в ~/.ssh/authorized_keys на VPS для '{user}'; \
             2) вставлен ПРИВАТНЫЙ ключ целиком; 3) для ключа с паролем заполнено поле «Пароль ключа»; \
             4) на VPS PubkeyAuthentication yes. Детали — в логе выше (строки 'ssh:')."
        ),
        SshError::Auth(msg) => format!(
            "Не удалось войти под '{user}' ({msg}). Проверь по порядку: \
             1) в пароле нет лишних пробелов/символов (поле скрывает их за точками); \
             2) на VPS в /etc/ssh/sshd_config заданы PermitRootLogin yes, \
             PasswordAuthentication yes и KbdInteractiveAuthentication yes, затем \
             systemctl restart ssh. Подробности SSH-согласования — в логе выше (строки 'ssh:')."
        ),
        SshError::Other(msg) => msg,
    })
}

/// Opens a fresh connection, runs ONE small `command`, returns its merged output and closes; a
/// non-zero exit is an error carrying the output. `log_progress` is true only for the first call of
/// an install so the login diagnostics show once instead of per upload chunk.
pub fn one_shot(
    ssh: &dyn Ssh,
    target: &SshTarget,
    command: &str,
    on_log: &dyn Fn(&str),
    log_progress: bool,
) -> Result<String, InstallError> {
    let login = resolve_login(target, on_log, log_progress);
    let ssh_log = |m: &str| on_log(&format!("ssh: {m}"));
    let negotiation: Option<&dyn Fn(&str)> = if log_progress { Some(&ssh_log) } else { None };
    let out = ssh
        .exec(&login, command, negotiation)
        .map_err(|e| connect_error(&login, e))?;
    if out.exit_code != 0 {
        return Err(InstallError::Failed(format!(
            "Команда завершилась с кодом {}:\n{}",
            out.exit_code,
            out.output.trim()
        )));
    }
    Ok(out.output)
}

/// Lands `data` at `remote_path`: **one SFTP transfer first**, chunked base64 only if that fails.
///
/// The chunked path costs one full SSH login per 100 KB - a 20 MB core is ~270 logins and minutes
/// of wall time - and exists for the one server that resets the link on a second channel. Either
/// way the landed file is verified against `data` before this returns: a truncated upload used to
/// reach `gunzip` as a corrupt binary with no hint of why.
pub fn upload(
    ssh: &dyn Ssh,
    target: &SshTarget,
    data: &[u8],
    remote_path: &str,
    on_log: &dyn Fn(&str),
) -> Result<(), InstallError> {
    let via_sftp = upload_via_sftp(ssh, target, data, remote_path, on_log)
        .and_then(|()| verify_upload(ssh, target, data, remote_path, on_log));
    let Err(e) = via_sftp else { return Ok(()) };
    on_log(&format!(
        "…одним файлом не вышло ({}), грузим частями",
        take_utf16(e.message(), 200)
    ));
    upload_in_chunks(ssh, target, data, remote_path, on_log)?;
    verify_upload(ssh, target, data, remote_path, on_log)
}

/// One fresh connection, one SFTP channel, the whole file in one `put`.
fn upload_via_sftp(
    ssh: &dyn Ssh,
    target: &SshTarget,
    data: &[u8],
    remote_path: &str,
    on_log: &dyn Fn(&str),
) -> Result<(), InstallError> {
    on_log(&format!(
        "…заливаю {} КБ одним файлом (SFTP)",
        data.len() / 1024
    ));
    let login = resolve_login(target, on_log, false);
    ssh.sftp_put(&login, data, remote_path)
        .map_err(|e| connect_error(&login, e))
}

/// Confirms the remote file is byte-for-byte what was sent: sha256sum when the server has it (every
/// mainstream image does), otherwise the byte count - which still catches the realistic failure, a
/// transfer that stopped early.
fn verify_upload(
    ssh: &dyn Ssh,
    target: &SshTarget,
    data: &[u8],
    remote_path: &str,
    on_log: &dyn Fn(&str),
) -> Result<(), InstallError> {
    let q = shell_single_quote(remote_path);
    let probe = one_shot(
        ssh,
        target,
        &format!(
            "if command -v sha256sum >/dev/null 2>&1; then sha256sum < {q} | cut -d' ' -f1; \
             else printf 'size:'; wc -c < {q}; fi"
        ),
        on_log,
        false,
    )?;
    let probe = probe.trim();
    let expected = if probe.starts_with("size:") {
        format!("size:{}", data.len())
    } else {
        Sha256::digest(data)
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect()
    };
    let actual = probe.replace(' ', "");
    if !actual.eq_ignore_ascii_case(&expected) {
        return Err(InstallError::Failed(format!(
            "Загруженный файл не совпал с исходным (ожидали {expected}, получили {actual})"
        )));
    }
    Ok(())
}

/// Fallback: truncate the file, then append the base64-decoded bytes chunk by chunk - each chunk a
/// separate small `printf '…' | base64 -d >> file` command on its OWN fresh connection.
fn upload_in_chunks(
    ssh: &dyn Ssh,
    target: &SshTarget,
    data: &[u8],
    remote_path: &str,
    on_log: &dyn Fn(&str),
) -> Result<(), InstallError> {
    let q = shell_single_quote(remote_path);
    one_shot(ssh, target, &format!(": > {q}"), on_log, false)?;
    let b64 = base64::engine::general_purpose::STANDARD.encode(data);
    let total = b64.len().div_ceil(UPLOAD_CHUNK);
    let mut index = 0;
    let mut sent = 0;
    while index < b64.len() {
        let end = (index + UPLOAD_CHUNK).min(b64.len());
        let part = &b64[index..end];
        one_shot(
            ssh,
            target,
            &format!("printf '%s' '{part}' | base64 -d >> {q}"),
            on_log,
            false,
        )?;
        index = end;
        sent += 1;
        on_log(&format!(
            "…загружено {sent}/{total} ({}%)",
            end as u64 * 100 / b64.len() as u64
        ));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn take_counts_utf16_units() {
        assert_eq!(take_utf16("абв", 2), "аб");
        assert_eq!(take_utf16("a😀b", 2), "a");
        assert_eq!(take_utf16("a😀b", 3), "a😀");
        assert_eq!(take_utf16("ab", 200), "ab");
        assert_eq!(utf16_len("a😀"), 3);
    }

    #[test]
    fn secrets_stay_out_of_debug_output() {
        let target = SshTarget {
            host: "h".into(),
            password: "hunter2".into(),
            private_key: "-----BEGIN KEY".into(),
            passphrase: "s3cr3t".into(),
            ..Default::default()
        };
        let login = SshLogin {
            host: "h".into(),
            port: 22,
            user: "root".into(),
            auth: SshAuth::Key {
                private_key: "-----BEGIN KEY".into(),
                passphrase: "s3cr3t".into(),
            },
        };
        let password = SshAuth::Password("hunter2".into());
        for text in [
            format!("{target:?}"),
            format!("{login:?}"),
            format!("{password:?}"),
        ] {
            assert!(
                !text.contains("hunter2") && !text.contains("BEGIN") && !text.contains("s3cr3t"),
                "{text}"
            );
        }
    }
}
