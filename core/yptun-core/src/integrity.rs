//! Signing-certificate check, the platform-independent part of `IntegrityGuard.kt`.
//!
//! Tamper-EVIDENT, not tamper-proof: the SHA-256 of the certificate an app (or a downloaded update
//! APK) is signed with is compared against the official YPtun release key. Two uses:
//!  1. Warn the user when the INSTALLED build isn't ours (casual repackaging). Whoever repackaged
//!     the apk can patch this check out too: an informational signal, not a security boundary.
//!  2. Vet a downloaded update APK BEFORE handing it to the installer: stops a man-in-the-middle on
//!     the update channel from slipping in a malicious APK.
//!
//! Reading the signatures stays on Android (`PackageManager`, `GET_SIGNING_CERTIFICATES` on API 28+,
//! `GET_SIGNATURES` before, `apkContentsSigners` with multiple signers, else
//! `signingCertificateHistory`); the platform passes the raw certificate bytes
//! (`Signature.toByteArray()`) here in the same order.

use sha2::{Digest, Sha256};

/// SHA-256 (lowercase hex, no separators) of the official YPtun release signing certificate
/// (`yptun-release.jks`, alias `yptun`, CN=YPtun). keytool prints it as `EA:6B:8C:6B:...:4E:D0`.
pub const OFFICIAL_SIGNING_SHA256: &str =
    "ea6b8c6b1353d9c11086cd7e75259210b26a1b11a6930292270eecccb19f4ed0";

/// Lowercase hex SHA-256 of `bytes`.
pub fn sha256_hex(bytes: &[u8]) -> String {
    Sha256::digest(bytes).iter().map(|b| format!("{b:02x}")).collect()
}

/// SHA-256 of the first signing certificate, or `None` when there are none (unreadable package).
pub fn signing_sha256<I, B>(signatures: I) -> Option<String>
where
    I: IntoIterator<Item = B>,
    B: AsRef<[u8]>,
{
    signatures.into_iter().next().map(|s| sha256_hex(s.as_ref()))
}

/// True when `sha256` is the official key's hash (case-insensitive). `None` (unreadable) is false.
pub fn matches_official(sha256: Option<&str>) -> bool {
    sha256.is_some_and(|s| s.eq_ignore_ascii_case(OFFICIAL_SIGNING_SHA256))
}

/// True when the certificates belong to the official key: the installed app
/// (`isOfficialInstalled`), a downloaded APK (`isOfficialApk`) or another installed package
/// (`isOfficialPackage`). No certificates means false (reject).
pub fn is_official<I, B>(signatures: I) -> bool
where
    I: IntoIterator<Item = B>,
    B: AsRef<[u8]>,
{
    matches_official(signing_sha256(signatures).as_deref())
}
