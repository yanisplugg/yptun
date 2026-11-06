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

use flate2::write::GzEncoder;
use flate2::Compression;

pub mod ssh;

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

/// Kotlin `error(message)`.
pub(crate) fn fail<T>(message: impl Into<String>) -> Result<T, InstallError> {
    Err(InstallError::Failed(message.into()))
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
    fn go_arch_names() {
        assert_eq!(go_arch("x86_64"), Some("amd64"));
        assert_eq!(go_arch("aarch64"), Some("arm64"));
        assert_eq!(go_arch("armv7l"), None);
    }
}
