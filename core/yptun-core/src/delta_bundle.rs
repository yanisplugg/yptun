//! Delta updates (port of DesktopDeltaPatch and DeltaApkPatcher).
//!
//! Desktop: a bundle (YPtun-delta-<from>-<to>-<os>-<arch>.patch, a ZIP) holding manifest.json plus
//! one payload per operation rebuilds the changed files of the installed app image:
//! * patch  — a gzip File-by-File v1 patch turning from into to;
//! * add    — the new file verbatim (new dependency, YPtun.cfg, ...);
//! * delete — no payload; the old file goes away.
//! Every operation carries SHA-256 of what it expects and what it produces: nothing is applied unless
//! the installation is EXACTLY the bundle's base, and nothing reaches the swapper unless it came out
//! byte-identical to the published build. Anything under runtime/ is out of scope (full installer).
//!
//! Android: one gzip FbF patch applied to the installed APK rebuilds the new APK byte for byte, so it
//! keeps the original signature.
//!
//! The ZIP container and the FbF applier stay behind traits ([DeltaBundle], [FbfApplier]): for now
//! the platforms wrap java.util.zip / archive-patcher; a Rust applier can replace them later.

use std::fs;
use std::io::{self, BufReader, Read, Write};
use std::path::{Component, Path, PathBuf};

use flate2::read::GzDecoder;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

pub const FORMAT: i64 = 3;
pub const MANIFEST: &str = "manifest.json";
pub const OP_PATCH: &str = "patch";
pub const OP_ADD: &str = "add";
pub const OP_DELETE: &str = "delete";
/// Android scratch dir for the applier, relative to the app cache dir.
pub const ANDROID_PATCH_TMP: &str = "updates/patch-tmp";

fn default_format() -> i64 {
    FORMAT
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Manifest {
    #[serde(default = "default_format")]
    pub format: i64,
    #[serde(default)]
    pub from: String,
    #[serde(default)]
    pub to: String,
    #[serde(default)]
    pub target: String,
    #[serde(default)]
    pub ops: Vec<Op>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Op {
    pub op: String,
    #[serde(default)]
    pub from: String,
    #[serde(default)]
    pub to: String,
    #[serde(default)]
    pub from_sha: String,
    #[serde(default)]
    pub to_sha: String,
    #[serde(default)]
    pub payload: String,
}

/// What the swapper has to do once the app process exits.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DeltaPlan {
    /// staged file → its final place in the app directory.
    pub moves: Vec<(PathBuf, PathBuf)>,
    /// files in the app directory that the new build no longer has.
    pub deletions: Vec<PathBuf>,
    pub staging_dir: PathBuf,
    /// The installation being updated — decides whether the swapper needs elevation.
    pub root_dir: PathBuf,
}

/// Read access to the bundle's entries (the platform opens the ZIP).
pub trait DeltaBundle {
    fn entry(&mut self, name: &str) -> Option<Vec<u8>>;
}

/// A bundle already unpacked into memory.
#[derive(Clone, Debug, Default)]
pub struct MemoryBundle {
    pub entries: std::collections::HashMap<String, Vec<u8>>,
}

impl DeltaBundle for MemoryBundle {
    fn entry(&mut self, name: &str) -> Option<Vec<u8>> {
        self.entries.get(name).cloned()
    }
}

/// File-by-File v1 applier: applyDelta(oldBlob, rawDeltaIn, newBlobOut). The delta stream is
/// already gunzipped (gzip is only the transport wrapper).
pub trait FbfApplier {
    fn apply_delta(&mut self, old: &Path, delta: &mut dyn Read, out: &mut dyn Write, temp_dir: &Path) -> Result<(), String>;
}

fn ioe(e: io::Error) -> String {
    e.to_string()
}

/// Parses manifest.json (a leading BOM is tolerated, unknown keys ignored) and checks the format.
pub fn parse_manifest(bytes: &[u8]) -> Result<Manifest, String> {
    let text = String::from_utf8_lossy(bytes);
    let text = text.strip_prefix('\u{FEFF}').unwrap_or(&text);
    let manifest: Manifest = serde_json::from_str(text).map_err(|e| e.to_string())?;
    if manifest.format != FORMAT {
        return Err(format!("unsupported delta format {}", manifest.format));
    }
    Ok(manifest)
}

/// Deletions and the classpath file (*.cfg) go last (stable order otherwise): if a move fails
/// halfway, the installation is still the old, working one.
pub fn ordered_ops(ops: &[Op]) -> Vec<Op> {
    let mut out = ops.to_vec();
    out.sort_by_key(|o| o.op == OP_DELETE || o.to.ends_with(".cfg"));
    out
}

/// Lexical normalisation like java.nio Path.normalize (no filesystem access).
pub fn normalize_path(p: &Path) -> PathBuf {
    let mut out = PathBuf::new();
    for c in p.components() {
        match c {
            Component::CurDir => {}
            Component::ParentDir => match out.components().next_back() {
                Some(Component::Normal(_)) => {
                    out.pop();
                }
                Some(Component::RootDir) | Some(Component::Prefix(_)) => {}
                _ => out.push(".."),
            },
            other => out.push(other.as_os_str()),
        }
    }
    out
}

/// root.resolve(rel).normalize(), refusing anything that escapes root.
pub fn safe_join(root: &Path, rel: &str) -> Result<PathBuf, String> {
    let n = normalize_path(&root.join(rel));
    if n.starts_with(normalize_path(root)) {
        Ok(n)
    } else {
        Err("delta bundle path escapes the installation".to_string())
    }
}

/// Lowercase hex SHA-256 of a file.
pub fn sha256_file(path: &Path) -> io::Result<String> {
    let mut f = fs::File::open(path)?;
    let mut h = Sha256::new();
    let mut buf = vec![0u8; 64 * 1024];
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 {
            break;
        }
        h.update(&buf[..n]);
    }
    Ok(h.finalize().iter().map(|b| format!("{b:02x}")).collect())
}

fn verify(file: &Path, expected_sha: &str, name: &str) -> Result<(), String> {
    let sha = sha256_file(file).map_err(ioe)?;
    if sha.eq_ignore_ascii_case(expected_sha) {
        Ok(())
    } else {
        Err(format!("rebuilt {name} does not match the published file"))
    }
}

fn ensure_parent(path: &Path) -> Result<(), String> {
    match path.parent() {
        Some(p) => fs::create_dir_all(p).map_err(ioe),
        None => Ok(()),
    }
}

/// Rebuilds every changed file of root_dir (the image root: launcher, app/, runtime/) into
/// staging_dir and returns the plan to commit it. Errors when the bundle doesn't fit this
/// installation — the caller then falls back to the full installer.
pub fn stage(
    root_dir: &Path,
    bundle: &mut dyn DeltaBundle,
    applier: &mut dyn FbfApplier,
    staging_dir: &Path,
    temp_dir: &Path,
    on_step: &mut dyn FnMut(usize, usize),
) -> Result<DeltaPlan, String> {
    fs::create_dir_all(temp_dir).map_err(ioe)?;
    if staging_dir.exists() {
        let _ = fs::remove_dir_all(staging_dir);
    }
    fs::create_dir_all(staging_dir).map_err(ioe)?;

    let raw = bundle.entry(MANIFEST).ok_or_else(|| format!("delta bundle has no {MANIFEST}"))?;
    let manifest = parse_manifest(&raw)?;

    let mut moves: Vec<(PathBuf, PathBuf)> = Vec::new();
    let mut deletions: Vec<PathBuf> = Vec::new();
    let ordered = ordered_ops(&manifest.ops);
    let total = ordered.len();
    for (index, op) in ordered.iter().enumerate() {
        on_step(index, total);
        match op.op.as_str() {
            OP_PATCH => {
                let base = safe_join(root_dir, &op.from)?;
                if !base.exists() {
                    return Err(format!("installed file missing: {}", op.from));
                }
                let base_sha = sha256_file(&base).map_err(ioe)?;
                if !base_sha.eq_ignore_ascii_case(&op.from_sha) {
                    return Err(format!("installed {} is not this patch's base", op.from));
                }
                let staged = safe_join(staging_dir, &op.to)?;
                ensure_parent(&staged)?;
                let payload = bundle.entry(&op.payload).ok_or_else(|| format!("missing payload {}", op.payload))?;
                {
                    let mut out = fs::File::create(&staged).map_err(ioe)?;
                    let mut patch = GzDecoder::new(&payload[..]);
                    applier.apply_delta(&base, &mut patch, &mut out, temp_dir)?;
                    out.flush().map_err(ioe)?;
                }
                verify(&staged, &op.to_sha, &op.to)?;
                moves.push((staged, safe_join(root_dir, &op.to)?));
                if op.from != op.to {
                    deletions.push(base);
                }
            }
            OP_ADD => {
                let staged = safe_join(staging_dir, &op.to)?;
                ensure_parent(&staged)?;
                let payload = bundle.entry(&op.payload).ok_or_else(|| format!("missing payload {}", op.payload))?;
                fs::write(&staged, &payload).map_err(ioe)?;
                verify(&staged, &op.to_sha, &op.to)?;
                moves.push((staged, safe_join(root_dir, &op.to)?));
            }
            OP_DELETE => deletions.push(safe_join(root_dir, &op.from)?),
            other => return Err(format!("unknown delta operation '{other}'")),
        }
    }
    Ok(DeltaPlan { moves, deletions, staging_dir: staging_dir.to_path_buf(), root_dir: root_dir.to_path_buf() })
}

/// Android: rebuilds out_apk from the installed base_apk and a gzip FbF patch. temp_dir is the
/// applier's scratch space (<cacheDir>/updates/patch-tmp) and is removed afterwards either way.
pub fn apply_apk_patch(
    applier: &mut dyn FbfApplier,
    base_apk: &Path,
    patch_gz: &Path,
    out_apk: &Path,
    temp_dir: &Path,
) -> Result<PathBuf, String> {
    fs::create_dir_all(temp_dir).map_err(ioe)?;
    let result = apply_apk_inner(applier, base_apk, patch_gz, out_apk, temp_dir);
    let _ = fs::remove_dir_all(temp_dir);
    result.map(|_| out_apk.to_path_buf())
}

fn apply_apk_inner(applier: &mut dyn FbfApplier, base_apk: &Path, patch_gz: &Path, out_apk: &Path, temp_dir: &Path) -> Result<(), String> {
    let f = fs::File::open(patch_gz).map_err(ioe)?;
    let mut patch = GzDecoder::new(BufReader::new(f));
    let mut out = fs::File::create(out_apk).map_err(ioe)?;
    applier.apply_delta(base_apk, &mut patch, &mut out, temp_dir)?;
    out.flush().map_err(ioe)
}
