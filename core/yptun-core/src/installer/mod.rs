//! VPS auto-installers: one-tap server setup over SSH for the self-hosted transports (freeturn,
//! olcRTC, MasterDNS, WDTT, csqtt, OpenFlux, snolc), ported from the Kotlin `Ssh*ServerInstaller`s.
//! The remote scripts are byte-identical to the ones the Kotlin apps sent.
//!
//! The core owns everything but the wire: input validation, the remote shell scripts, the upload
//! strategy (SFTP first, chunked base64 as the fallback, checksum verification), parsing what the
//! VPS prints, and the human-readable progress log. The platform supplies the SSH client
//! ([`ssh::Ssh`]) and the bundled server executables ([`ServerBinarySource`]).
//!
//! Every call blocks until the install is done (minutes on a slow link): run it off the UI thread.

use std::io::Write;
use std::sync::LazyLock;

use flate2::write::GzEncoder;
use flate2::Compression;
use regex_lite::Regex;

pub mod csqtt;
pub mod freeturn;
pub mod masterdns;
pub mod olcrtc;
pub mod openflux;
pub mod snolc;
pub mod ssh;
pub mod wdtt;

use ssh::{one_shot, Ssh, SshTarget};

use crate::util::is_blank;

/// Why an install did not finish. The message is written for the user.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum InstallError {
    /// The options are unusable; nothing was sent to the VPS.
    InvalidInput(String),
    /// SSH or the remote install failed.
    Failed(String),
}

impl InstallError {
    pub fn message(&self) -> &str {
        match self {
            Self::InvalidInput(m) | Self::Failed(m) => m,
        }
    }
}

impl std::fmt::Display for InstallError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.message())
    }
}

impl std::error::Error for InstallError {}

/// Kotlin `require(condition) { message }`.
pub(crate) fn require(condition: bool, message: &str) -> Result<(), InstallError> {
    if condition {
        Ok(())
    } else {
        Err(InstallError::InvalidInput(message.to_string()))
    }
}

/// Kotlin `error(message)`.
pub(crate) fn fail<T>(message: impl Into<String>) -> Result<T, InstallError> {
    Err(InstallError::Failed(message.into()))
}

/// The checks every installer starts with: a host, and a password or a key.
pub(crate) fn require_ssh(target: &SshTarget) -> Result<(), InstallError> {
    require(!is_blank(&target.host), "Не указан IP/хост VPS")?;
    require(
        !is_blank(&target.private_key) || !is_blank(&target.password),
        "Укажи пароль SSH или SSH-ключ",
    )
}

/// Where the bundled server binaries come from: Android reads them out of its assets, the other apps
/// out of their bundle. `None` when the build has no such file.
pub trait ServerBinarySource {
    fn bytes(&self, path: &str) -> Option<Vec<u8>>;
}

impl<F: Fn(&str) -> Option<Vec<u8>>> ServerBinarySource for F {
    fn bytes(&self, path: &str) -> Option<Vec<u8>> {
        self(path)
    }
}

/// Loads a bundled gzip'd server binary as gzip bytes ready to upload. The build ships it as
/// `<base_path>.gz`, but Android's asset packaging DECOMPRESSES `.gz` assets and stores the raw file
/// under `<base_path>` - so the plain name is tried first, then `.gz`, and a raw binary is gzipped
/// here so the upload stays small and the server-side `gunzip` still works.
pub fn load_server_binary_gz(
    binaries: &dyn ServerBinarySource,
    base_path: &str,
) -> Result<Vec<u8>, InstallError> {
    match binaries
        .bytes(base_path)
        .or_else(|| binaries.bytes(&format!("{base_path}.gz")))
    {
        Some(raw) => Ok(gzip_if_needed(raw)),
        None => fail(format!("В сборке нет бинарника сервера ({base_path}[.gz])")),
    }
}

/// Passes gzip bytes through, gzips anything else.
pub fn gzip_if_needed(raw: Vec<u8>) -> Vec<u8> {
    if raw.starts_with(&[0x1f, 0x8b]) {
        return raw;
    }
    let mut gz = GzEncoder::new(Vec::new(), Compression::default());
    gz.write_all(&raw)
        .and_then(|()| gz.finish())
        .expect("gzip into memory cannot fail")
}

/// `uname -m` → the suffix of the bundled Go binaries; None for anything but x86_64/aarch64.
pub fn go_arch(machine: &str) -> Option<&'static str> {
    if machine.contains("aarch64") || machine.contains("arm64") {
        Some("arm64")
    } else if machine.contains("x86_64") || machine.contains("amd64") {
        Some("amd64")
    } else {
        None
    }
}

/// The first step of every install: `uname -m` on the first (verbose) connection, mapped through
/// `arch` (None = unsupported, reported with `wanted`, e.g. "x86_64 или aarch64").
pub(crate) fn detect_arch(
    ssh: &dyn Ssh,
    target: &SshTarget,
    on_log: &dyn Fn(&str),
    arch: fn(&str) -> Option<&'static str>,
    wanted: &str,
) -> Result<&'static str, InstallError> {
    on_log("Определяю архитектуру VPS…");
    let machine = one_shot(ssh, target, "uname -m", on_log, true)?;
    let machine = machine.trim();
    let Some(arch) = arch(machine) else {
        return fail(format!(
            "Неподдерживаемая архитектура VPS: '{machine}' (нужен {wanted})"
        ));
    };
    on_log(&format!("Архитектура VPS: {machine} → {arch}"));
    Ok(arch)
}

/// `len` bytes from the OS CSPRNG.
pub(crate) fn random_bytes(len: usize) -> Vec<u8> {
    let mut buf = vec![0u8; len];
    getrandom::getrandom(&mut buf).expect("the OS random source is unavailable");
    buf
}

/// A uniformly random number in `0..n` (Kotlin `Random.nextInt(n)`), without modulo bias.
pub(crate) fn random_below(n: usize) -> usize {
    let n = u32::try_from(n).expect("range fits u32");
    assert!(n > 0, "empty range");
    let zone = u32::MAX / n * n;
    loop {
        let x = u32::from_le_bytes(random_bytes(4).try_into().unwrap());
        if x < zone {
            return (x % n) as usize;
        }
    }
}

static ANSI: LazyLock<Regex> = LazyLock::new(|| Regex::new("\u{1B}\\[[0-9;]*[A-Za-z]").unwrap());

/// What a bundled `deploy.sh` (qWDTT, csqtt) reported, for the log.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeployReport {
    /// Its own `<TAG>_DEPLOY_OK` marker AND exit code 0.
    pub ok: bool,
    pub exit_code: Option<i32>,
    pub lines: Vec<String>,
}

/// Reads a deploy.sh transcript: colours stripped, `<TAG>_DEPLOY_EXIT=<n>` and `<TAG>_DEPLOY_OK`
/// picked up, `<TAG>_PROGRESS|x|step` turned into "• step", apt/dpkg noise dropped. `special` sees
/// the remaining lines first: `Some(None)` drops one, `Some(Some(text))` logs `text` instead.
pub(crate) fn read_deploy_output(
    output: &str,
    tag: &str,
    special: impl Fn(&str) -> Option<Option<String>>,
) -> DeployReport {
    let (exit_marker, ok_marker, progress) = (
        format!("{tag}_DEPLOY_EXIT="),
        format!("{tag}_DEPLOY_OK"),
        format!("{tag}_PROGRESS|"),
    );
    let mut exit_code = None;
    let mut ok = false;
    let mut lines = Vec::new();
    for raw in crate::util::kotlin_lines(output) {
        let line = ANSI.replace_all(raw, "");
        let line = line.trim();
        if line.is_empty() {
            continue;
        } else if line.starts_with(&exit_marker) {
            exit_code = line.split_once('=').and_then(|(_, c)| c.parse().ok());
        } else if line == ok_marker {
            ok = true;
        } else if line.starts_with(&progress) {
            if let Some(step) = line.split('|').nth(2).filter(|s| !is_blank(s)) {
                lines.push(format!("• {step}"));
            }
        } else if let Some(replacement) = special(line) {
            lines.extend(replacement);
        } else if !is_apt_noise(line) {
            lines.push(line.to_string());
        }
    }
    DeployReport {
        ok: ok && exit_code == Some(0),
        exit_code,
        lines,
    }
}

/// apt/dpkg progress lines deploy.sh passes through.
fn is_apt_noise(line: &str) -> bool {
    [
        "Get:",
        "Hit:",
        "Ign:",
        "Reading ",
        "Selecting ",
        "Preparing ",
        "Unpacking ",
        "Setting up ",
        "Processing ",
        "(Reading database",
    ]
    .iter()
    .any(|p| line.starts_with(p))
}

/// Kotlin `output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && keep(it) }`.
pub(crate) fn output_lines<'a>(
    output: &'a str,
    keep: impl Fn(&str) -> bool + 'a,
) -> impl Iterator<Item = &'a str> + 'a {
    crate::util::kotlin_lines(output)
        .into_iter()
        .map(str::trim)
        .filter(move |l| !l.is_empty() && keep(l))
}

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::read::GzDecoder;
    use std::io::Read;

    #[test]
    fn binaries_are_found_plain_first_and_always_come_back_gzipped() {
        let gz = gzip_if_needed(b"ELF".to_vec());
        let gz2 = gz.clone();
        let only_gz = move |p: &str| (p == "x/bin.gz").then(|| gz2.clone());
        assert_eq!(load_server_binary_gz(&only_gz, "x/bin").unwrap(), gz);
        let plain = |p: &str| (p == "x/bin").then(|| b"ELF".to_vec());
        let mut text = String::new();
        GzDecoder::new(&load_server_binary_gz(&plain, "x/bin").unwrap()[..])
            .read_to_string(&mut text)
            .unwrap();
        assert_eq!(text, "ELF");
        let none = |_: &str| None;
        assert_eq!(
            load_server_binary_gz(&none, "x/bin").unwrap_err().message(),
            "В сборке нет бинарника сервера (x/bin[.gz])"
        );
    }

    #[test]
    fn random_numbers_stay_in_range() {
        assert_eq!(random_bytes(24).len(), 24);
        let mut seen = [false; 3];
        for _ in 0..300 {
            seen[random_below(3)] = true;
        }
        assert_eq!(seen, [true; 3]);
        assert_eq!(random_below(1), 0);
    }

    #[test]
    fn go_arch_names() {
        assert_eq!(go_arch("x86_64"), Some("amd64"));
        assert_eq!(go_arch("aarch64"), Some("arm64"));
        assert_eq!(go_arch("armv7l"), None);
    }
}
