//! Port of `DesktopAppImage.kt` (jvmMain update): the layout of an installed YPtun desktop build
//! (a jpackage app image):
//!
//! * `<install>/YPtun.exe` -- launcher (deb: `/opt/yptun/bin/YPtun` with `lib/{app,runtime}`)
//! * `<install>/app/` -- the jars and `YPtun.cfg`
//! * `<install>/runtime/` -- the bundled JRE
//!
//! Kotlin found the running jar through the JVM code source; here the caller passes that path.
//! The OS check for the launcher is a parameter instead of `System.getProperty("os.name")`.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use crate::desktop_native_assets::DesktopOs;

pub const APP_DIR: &str = "app";
pub const RUNTIME_DIR: &str = "runtime";

/// An installed app image, found from the jar the app runs from.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DesktopAppImage {
    app_dir: PathBuf,
    install_dir: PathBuf,
}

impl DesktopAppImage {
    /// `runningJar()` + `appDir()`: the jar must be an existing file with a `.jar` extension, sit in
    /// a directory named `app` (any case), and that directory must have a `runtime/` sibling.
    /// None outside an installation (a Gradle `run`, an IDE, loose classes).
    pub fn from_running_jar(jar: &Path) -> Option<Self> {
        if !jar.exists() || jar.is_dir() {
            return None;
        }
        let is_jar = jar
            .extension()
            .and_then(|e| e.to_str())
            .is_some_and(|e| e.eq_ignore_ascii_case("jar"));
        if !is_jar {
            return None;
        }
        let app_dir = jar.parent()?;
        let named_app = app_dir
            .file_name()
            .and_then(|n| n.to_str())
            .is_some_and(|n| n.eq_ignore_ascii_case(APP_DIR));
        if !named_app {
            return None;
        }
        let install_dir = app_dir.parent()?;
        if !install_dir.join(RUNTIME_DIR).is_dir() {
            return None;
        }
        Some(Self {
            app_dir: app_dir.to_path_buf(),
            install_dir: install_dir.to_path_buf(),
        })
    }

    /// The `app/` directory: every jar plus `YPtun.cfg`, i.e. everything a delta update touches.
    pub fn app_dir(&self) -> &Path {
        &self.app_dir
    }

    /// The image root (the directory holding the launcher).
    pub fn install_dir(&self) -> &Path {
        &self.install_dir
    }

    /// The launcher executable to restart after an update, or None when it can't be found.
    pub fn launcher(&self, os: DesktopOs) -> Option<PathBuf> {
        let root = &self.install_dir;
        let candidates: Vec<PathBuf> = if os == DesktopOs::Windows {
            vec![root.join("YPtun.exe")]
        } else {
            // jpackage's deb layout is /opt/yptun/(bin/YPtun, lib/(app, runtime)): install_dir is
            // .../lib and the launcher sits one level above it.
            let mut v = Vec::new();
            if let Some(parent) = root.parent() {
                v.push(parent.join("bin").join("YPtun"));
            }
            v.push(root.join("bin").join("YPtun"));
            v.push(root.join("YPtun"));
            v
        };
        candidates.into_iter().find(|p| p.exists())
    }

    /// The classpath file (`app/YPtun.cfg`): the first `*.cfg` (any case) in `app/`. It names every
    /// jar by its content-hashed file name, so it is the cheap identity of an installed build.
    pub fn classpath_file(&self) -> Option<PathBuf> {
        let entries = fs::read_dir(&self.app_dir).ok()?;
        entries.flatten().map(|e| e.path()).find(|p| {
            p.file_name()
                .map(|n| n.to_string_lossy().to_lowercase().ends_with(".cfg"))
                .unwrap_or(false)
        })
    }
}

/// Lowercase hex SHA-256 of `path` (streamed in 64 KiB blocks).
pub fn sha256(path: &Path) -> io::Result<String> {
    crate::delta_bundle::sha256_file(path)
}

/// `installedDesktopFingerprint()`: SHA-256 of the classpath file of the installation `running_jar`
/// belongs to, or None outside an installed app image.
pub fn installed_desktop_fingerprint(running_jar: Option<&Path>) -> Option<String> {
    let image = DesktopAppImage::from_running_jar(running_jar?)?;
    sha256(&image.classpath_file()?).ok()
}
