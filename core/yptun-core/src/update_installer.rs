//! Port of `JvmUpdateInstaller.kt` (jvmMain update): installs an `AppUpdateInfo` on desktop,
//! preferring a binary delta, then a silent Windows install, then handing the file to the OS.
//!
//! * Everything the JVM got from globals or libraries comes from `UpdateInstallerHost` (HTTP
//!   stream, `Desktop.open`, zip bundle, FbF applier, `appendToYptunLog`) and
//!   `DesktopUpdateEnvironment` (OS, app data dir, portable mode, app image root, launcher,
//!   elevation).
//! * Stage/progress callbacks are called directly; hopping to the UI thread is the caller's job.

use std::fs;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};

use crate::delta_bundle::{self, DeltaBundle, DeltaPlan, FbfApplier};
use crate::desktop_native_assets::DesktopOs;
use crate::desktop_self_update::{
    is_writable, schedule_installer, schedule_replace_portable, schedule_swap,
};
use crate::update::{AppUpdateAsset, AppUpdateInfo};
use crate::update_stage::{UpdateStage, UpdateStageKind};
use crate::util::is_blank;

/// What `install` actually did, so the caller knows whether to restart.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DesktopUpdateOutcome {
    /// The full installer was downloaded and handed to the OS; the user drives it from here.
    InstallerOpened(String),
    /// An update was staged. The app must shut down cleanly and exit for it to land.
    RestartRequired(String),
}

/// The platform side of the installer.
pub trait UpdateInstallerHost {
    /// Opens `url` for download (Kotlin: 10 s connect / 60 s read timeouts). Returns the
    /// Content-Length when the server sent one, and the body.
    fn open_download(&self, url: &str) -> Result<(Option<i64>, Box<dyn Read>), String>;
    /// `Desktop.open(file)`, else `Desktop.browse(asset.download_url)`, else
    /// `Err(no_system_handler_error(&asset.name))`.
    fn open_with_system(&self, file: &Path, asset: &AppUpdateAsset) -> Result<(), String>;
    /// Opens a downloaded delta bundle (zip) for `delta_bundle::stage`.
    fn open_delta_bundle(&self, file: &Path) -> Result<Box<dyn DeltaBundle>, String>;
    /// The FbF patch applier for `delta_bundle::stage`.
    fn fbf_applier(&self) -> Result<Box<dyn FbfApplier>, String>;
    /// `appendToYptunLog`.
    fn log(&self, line: &str);
}

pub fn no_system_handler_error(asset_name: &str) -> String {
    format!("No system file handler available for {asset_name}")
}

/// What the Kotlin installer read from `DesktopPaths`, `DesktopRuntimeMode`, `DesktopAppImage` and
/// `DesktopElevation`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DesktopUpdateEnvironment {
    pub os: DesktopOs,
    pub app_data_dir: PathBuf,
    /// `DesktopRuntimeMode.isPortable`.
    pub is_portable: bool,
    /// `DesktopRuntimeMode.portableExecutable()`.
    pub portable_executable: Option<PathBuf>,
    /// `DesktopAppImage.installDir()`.
    pub install_dir: Option<PathBuf>,
    /// `DesktopAppImage.launcher()`.
    pub launcher: Option<PathBuf>,
    /// `DesktopElevation.isElevated()`.
    pub is_elevated: bool,
}

pub const NOT_AN_APP_IMAGE: &str =
    "not running from an installed app image (portable or development run)";

const MB: i64 = 1_048_576;
const BUFFER_SIZE: usize = 8 * 1024;

/// `JvmUpdateInstaller`.
#[derive(Debug, Clone)]
pub struct DesktopUpdateInstaller {
    directory: PathBuf,
}

impl DesktopUpdateInstaller {
    /// Downloads go to `<app_data_dir>/updates`.
    pub fn new(app_data_dir: &Path) -> Self {
        Self::with_directory(app_data_dir.join("updates"))
    }

    pub fn with_directory(directory: PathBuf) -> Self {
        Self { directory }
    }

    pub fn directory(&self) -> &Path {
        &self.directory
    }

    /// Installs `info`, preferring a binary delta. Any delta failure is logged and falls through to
    /// the full download.
    pub fn install(
        &self,
        info: &AppUpdateInfo,
        env: &DesktopUpdateEnvironment,
        host: &dyn UpdateInstallerHost,
        on_stage: &mut dyn FnMut(UpdateStage),
        on_progress: &mut dyn FnMut(f32),
    ) -> Result<DesktopUpdateOutcome, String> {
        // A portable has no installed app image to patch and no installer to run: the new
        // single-file portable .exe replaces the one the user started.
        if env.is_portable && is_portable_exe(&info.asset) {
            return self.replace_portable(&info.asset, env, host, on_stage, on_progress);
        }
        if let Some(delta) = &info.delta_asset {
            match self.apply_delta(delta, env, host, on_stage, on_progress) {
                Ok(outcome) => return Ok(outcome),
                Err(e) => host.log(&format!("update: delta {} not applied: {e}", delta.name)),
            }
        }
        if info.delta_asset.is_none() {
            host.log(&format!(
                "update: no delta bundle for this install \u{2014} downloading {} in full",
                info.asset.name
            ));
        }
        self.install_full(&info.asset, env, host, on_stage, on_progress)
    }

    /// Downloads `asset` and hands it to the OS.
    pub fn download_and_open(
        &self,
        asset: &AppUpdateAsset,
        host: &dyn UpdateInstallerHost,
        on_progress: &mut dyn FnMut(f32),
    ) -> Result<String, String> {
        self.open_installer(asset, host, on_progress)
    }

    fn install_full(
        &self,
        asset: &AppUpdateAsset,
        env: &DesktopUpdateEnvironment,
        host: &dyn UpdateInstallerHost,
        on_stage: &mut dyn FnMut(UpdateStage),
        on_progress: &mut dyn FnMut(f32),
    ) -> Result<DesktopUpdateOutcome, String> {
        let lower = asset.name.to_lowercase();
        let silent = env.os == DesktopOs::Windows
            && !is_portable_exe(asset)
            && (lower.ends_with(".exe") || lower.ends_with(".msi"));
        if !silent {
            return Ok(DesktopUpdateOutcome::InstallerOpened(
                self.open_installer(asset, host, on_progress)?,
            ));
        }
        on_stage(UpdateStage::new(UpdateStageKind::DownloadInstaller, 1, 2));
        let file = self.download(asset, host, on_progress)?;
        on_stage(UpdateStage::new(UpdateStageKind::Install, 2, 2));
        schedule_installer(
            &env.app_data_dir,
            env.os,
            &file,
            env.launcher.as_deref(),
            env.is_elevated,
        )?;
        Ok(DesktopUpdateOutcome::RestartRequired(
            "Update ready - installing and restarting YPtun".to_string(),
        ))
    }

    fn replace_portable(
        &self,
        asset: &AppUpdateAsset,
        env: &DesktopUpdateEnvironment,
        host: &dyn UpdateInstallerHost,
        on_stage: &mut dyn FnMut(UpdateStage),
        on_progress: &mut dyn FnMut(f32),
    ) -> Result<DesktopUpdateOutcome, String> {
        on_stage(UpdateStage::new(UpdateStageKind::DownloadInstaller, 1, 2));
        let file = self.download(asset, host, on_progress)?;
        on_stage(UpdateStage::new(UpdateStageKind::Install, 2, 2));
        let target = env.portable_executable.as_deref();
        if target.is_none() {
            let name = file.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
            host.log(&format!(
                "update: portable .exe path unknown - running the downloaded {name} instead"
            ));
        }
        schedule_replace_portable(&env.app_data_dir, env.os, &file, target)?;
        Ok(DesktopUpdateOutcome::RestartRequired(
            "Update ready - restarting YPtun".to_string(),
        ))
    }

    fn open_installer(
        &self,
        asset: &AppUpdateAsset,
        host: &dyn UpdateInstallerHost,
        on_progress: &mut dyn FnMut(f32),
    ) -> Result<String, String> {
        let file = self.download(asset, host, on_progress)?;
        host.open_with_system(&file, asset)?;
        Ok(format!("Opening {}", asset.name))
    }

    fn apply_delta(
        &self,
        delta: &AppUpdateAsset,
        env: &DesktopUpdateEnvironment,
        host: &dyn UpdateInstallerHost,
        on_stage: &mut dyn FnMut(UpdateStage),
        on_progress: &mut dyn FnMut(f32),
    ) -> Result<DesktopUpdateOutcome, String> {
        let root_dir = env.install_dir.clone().ok_or_else(|| NOT_AN_APP_IMAGE.to_string())?;
        on_stage(UpdateStage::new(UpdateStageKind::DownloadPatch, 1, 3));
        let bundle = self.download(delta, host, on_progress)?;
        on_stage(UpdateStage::new(UpdateStageKind::ApplyPatch, 2, 3));
        on_progress(0.0);
        // Staged inside the image when we may write there (every commit is a same-volume rename);
        // otherwise in our data dir, and the elevated swapper moves the files in.
        let staging_dir = if is_writable(&root_dir) {
            root_dir.join(".yptun-update")
        } else {
            self.directory.join("staging")
        };
        let temp_dir = self.directory.join("patch-tmp");
        let staged = stage_bundle(host, &root_dir, &bundle, &staging_dir, &temp_dir, on_progress);
        if staged.is_err() {
            let _ = fs::remove_dir_all(&staging_dir);
        }
        let _ = fs::remove_file(&bundle);
        let _ = fs::remove_dir_all(&temp_dir);
        let plan = staged?;
        on_progress(1.0);
        on_stage(UpdateStage::new(UpdateStageKind::Restart, 3, 3));
        schedule_swap(&env.app_data_dir, env.os, &plan, env.launcher.as_deref())?;
        Ok(DesktopUpdateOutcome::RestartRequired(
            "Update ready \u{2014} restarting YPtun".to_string(),
        ))
    }

    /// Downloads `asset` into the updates directory, checks the length and the published SHA-256
    /// (a mismatching file is deleted) and returns the file.
    pub fn download(
        &self,
        asset: &AppUpdateAsset,
        host: &dyn UpdateInstallerHost,
        on_progress: &mut dyn FnMut(f32),
    ) -> Result<PathBuf, String> {
        fs::create_dir_all(&self.directory).map_err(|e| e.to_string())?;
        let tail = asset.name.rsplit('/').next().unwrap_or("");
        let name = if is_blank(tail) { "olcbox-update" } else { tail };
        let target = self.directory.join(name);
        let (length, mut input) = host.open_download(&asset.download_url)?;
        let total = length
            .filter(|l| *l > 0)
            .or(asset.size_bytes)
            .unwrap_or(-1);
        let mut copied: i64 = 0;
        {
            let mut output = fs::File::create(&target).map_err(|e| e.to_string())?;
            let mut buffer = vec![0u8; BUFFER_SIZE];
            loop {
                let read = input.read(&mut buffer).map_err(|e| e.to_string())?;
                if read == 0 {
                    break;
                }
                output.write_all(&buffer[..read]).map_err(|e| e.to_string())?;
                copied += read as i64;
                if total > 0 {
                    on_progress(((copied as f64 / total as f64) as f32).clamp(0.0, 1.0));
                }
            }
        }
        // A connection dropped mid-stream ends as a clean EOF, not an error.
        if total > 0 && copied != total {
            let _ = fs::remove_file(&target);
            return Err(format!(
                "Download interrupted ({} of {} MB) \u{2014} try again",
                copied / MB,
                total / MB
            ));
        }
        match &asset.sha256 {
            Some(expected) => {
                let actual = delta_bundle::sha256_file(&target).map_err(|e| e.to_string())?;
                if !actual.eq_ignore_ascii_case(expected) {
                    let _ = fs::remove_file(&target);
                    return Err(format!(
                        "Checksum mismatch for {} - the download is corrupt, try again",
                        asset.name
                    ));
                }
            }
            None => host.log(&format!(
                "update: no SHA-256 published for {} - integrity not verified",
                asset.name
            )),
        }
        on_progress(1.0);
        Ok(target)
    }
}

fn stage_bundle(
    host: &dyn UpdateInstallerHost,
    root_dir: &Path,
    bundle: &Path,
    staging_dir: &Path,
    temp_dir: &Path,
    on_progress: &mut dyn FnMut(f32),
) -> Result<DeltaPlan, String> {
    let mut opened = host.open_delta_bundle(bundle)?;
    let mut applier = host.fbf_applier()?;
    delta_bundle::stage(
        root_dir,
        opened.as_mut(),
        applier.as_mut(),
        staging_dir,
        temp_dir,
        &mut |done, total| on_progress(done as f32 / total as f32),
    )
}

/// A single-file portable build: the name has "portable" and ends with ".exe" (any case).
pub fn is_portable_exe(asset: &AppUpdateAsset) -> bool {
    let n = asset.name.to_lowercase();
    n.contains("portable") && n.ends_with(".exe")
}
