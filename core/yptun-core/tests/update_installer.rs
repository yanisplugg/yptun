use std::cell::RefCell;
use std::fs;
use std::io::{Cursor, Read};
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use yptun_core::delta_bundle::{DeltaBundle, FbfApplier};
use yptun_core::desktop_native_assets::DesktopOs;
use yptun_core::update::{AppUpdateAsset, AppUpdateInfo, ReleaseChannel};
use yptun_core::update_installer::{
    is_portable_exe, DesktopUpdateEnvironment, DesktopUpdateInstaller, DesktopUpdateOutcome,
    UpdateInstallerHost,
};
use yptun_core::update_stage::{UpdateStage, UpdateStageKind};

fn temp_dir(tag: &str) -> PathBuf {
    let nanos = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
    let d = std::env::temp_dir().join(format!("yptun-{tag}-{}-{nanos}", std::process::id()));
    fs::create_dir_all(&d).unwrap();
    d
}

struct FakeHost {
    body: Vec<u8>,
    length: Option<i64>,
    logs: RefCell<Vec<String>>,
    opened: RefCell<Vec<PathBuf>>,
}

impl FakeHost {
    fn new(body: &[u8], length: Option<i64>) -> Self {
        Self { body: body.to_vec(), length, logs: RefCell::new(vec![]), opened: RefCell::new(vec![]) }
    }
}

impl UpdateInstallerHost for FakeHost {
    fn open_download(&self, _url: &str) -> Result<(Option<i64>, Box<dyn Read>), String> {
        Ok((self.length, Box::new(Cursor::new(self.body.clone()))))
    }
    fn open_with_system(&self, file: &Path, _asset: &AppUpdateAsset) -> Result<(), String> {
        self.opened.borrow_mut().push(file.to_path_buf());
        Ok(())
    }
    fn open_delta_bundle(&self, _file: &Path) -> Result<Box<dyn DeltaBundle>, String> {
        Err("no zip".into())
    }
    fn fbf_applier(&self) -> Result<Box<dyn FbfApplier>, String> {
        Err("no fbf".into())
    }
    fn log(&self, line: &str) {
        self.logs.borrow_mut().push(line.to_string());
    }
}

fn asset(name: &str, sha: Option<&str>) -> AppUpdateAsset {
    AppUpdateAsset {
        name: name.to_string(),
        download_url: String::from("https://dl.invalid/update"),
        sha256: sha.map(String::from),
        ..Default::default()
    }
}

fn info(asset: AppUpdateAsset, delta: Option<AppUpdateAsset>) -> AppUpdateInfo {
    AppUpdateInfo {
        channel: ReleaseChannel::Stable,
        version: "1.0.0".into(),
        html_url: String::new(),
        published_at: None,
        asset,
        is_update_available: true,
        delta_asset: delta,
    }
}

fn env(dir: &Path, os: DesktopOs) -> DesktopUpdateEnvironment {
    DesktopUpdateEnvironment {
        os,
        app_data_dir: dir.to_path_buf(),
        is_portable: false,
        portable_executable: None,
        install_dir: None,
        launcher: None,
        is_elevated: false,
    }
}

const HELLO_SHA: &str = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

#[test]
fn download_verifies_checksum() {
    let dir = temp_dir("dl-ok");
    let inst = DesktopUpdateInstaller::new(&dir);
    let host = FakeHost::new(b"hello", Some(5));
    let mut last = -1.0f32;
    let file = inst
        .download(&asset("a/b/setup.deb", Some(&HELLO_SHA.to_uppercase())), &host, &mut |p| last = p)
        .unwrap();
    assert_eq!(file, dir.join("updates").join("setup.deb"));
    assert_eq!(fs::read(&file).unwrap(), b"hello");
    assert_eq!(last, 1.0);
    assert!(host.logs.borrow().is_empty());
    fs::remove_dir_all(&dir).ok();
}

#[test]
fn download_rejects_bad_checksum_and_short_body() {
    let dir = temp_dir("dl-bad");
    let inst = DesktopUpdateInstaller::new(&dir);
    let host = FakeHost::new(b"hello", None);
    let err = inst.download(&asset("x.deb", Some("00")), &host, &mut |_| {}).unwrap_err();
    assert_eq!(err, "Checksum mismatch for x.deb - the download is corrupt, try again");
    assert!(!dir.join("updates").join("x.deb").exists());

    let short = FakeHost::new(b"hello", Some(10));
    let err = inst.download(&asset("y.deb", None), &short, &mut |_| {}).unwrap_err();
    assert_eq!(err, "Download interrupted (0 of 0 MB) \u{2014} try again");
    assert!(!dir.join("updates").join("y.deb").exists());
    fs::remove_dir_all(&dir).ok();
}

#[test]
fn download_without_checksum_logs_and_names_blank() {
    let dir = temp_dir("dl-nosha");
    let inst = DesktopUpdateInstaller::new(&dir);
    let host = FakeHost::new(b"hello", None);
    let file = inst.download(&asset("dir/", None), &host, &mut |_| {}).unwrap();
    assert_eq!(file, dir.join("updates").join("olcbox-update"));
    assert_eq!(
        host.logs.borrow().as_slice(),
        ["update: no SHA-256 published for dir/ - integrity not verified".to_string()]
    );
    fs::remove_dir_all(&dir).ok();
}

#[test]
fn install_without_delta_opens_installer() {
    let dir = temp_dir("inst-full");
    let inst = DesktopUpdateInstaller::new(&dir);
    let host = FakeHost::new(b"hello", None);
    let out = inst
        .install(&info(asset("YPtun.deb", Some(HELLO_SHA)), None), &env(&dir, DesktopOs::Linux), &host, &mut |_| {}, &mut |_| {})
        .unwrap();
    assert_eq!(out, DesktopUpdateOutcome::InstallerOpened("Opening YPtun.deb".into()));
    assert_eq!(
        host.logs.borrow()[0],
        "update: no delta bundle for this install \u{2014} downloading YPtun.deb in full"
    );
    assert_eq!(host.opened.borrow().as_slice(), [dir.join("updates").join("YPtun.deb")]);
    fs::remove_dir_all(&dir).ok();
}

#[test]
fn delta_outside_app_image_falls_back() {
    let dir = temp_dir("inst-delta");
    let inst = DesktopUpdateInstaller::new(&dir);
    let host = FakeHost::new(b"hello", None);
    let mut stages: Vec<UpdateStage> = vec![];
    let out = inst
        .install(
            &info(asset("YPtun.dmg", Some(HELLO_SHA)), Some(asset("d.yptd", None))),
            &env(&dir, DesktopOs::MacOs),
            &host,
            &mut |s| stages.push(s),
            &mut |_| {},
        )
        .unwrap();
    assert_eq!(out, DesktopUpdateOutcome::InstallerOpened("Opening YPtun.dmg".into()));
    assert!(stages.is_empty());
    assert_eq!(
        host.logs.borrow().as_slice(),
        ["update: delta d.yptd not applied: not running from an installed app image (portable or development run)".to_string()]
    );
    fs::remove_dir_all(&dir).ok();
}

#[test]
fn portable_replace_is_windows_only() {
    let dir = temp_dir("inst-portable");
    let inst = DesktopUpdateInstaller::new(&dir);
    let host = FakeHost::new(b"hello", None);
    let mut e = env(&dir, DesktopOs::Linux);
    e.is_portable = true;
    let mut stages: Vec<UpdateStage> = vec![];
    let err = inst
        .install(&info(asset("YPtun-1.0.0-Portable.EXE", None), None), &e, &host, &mut |s| stages.push(s), &mut |_| {})
        .unwrap_err();
    assert_eq!(err, "portable self-update is Windows-only");
    assert_eq!(
        stages,
        vec![
            UpdateStage::new(UpdateStageKind::DownloadInstaller, 1, 2),
            UpdateStage::new(UpdateStageKind::Install, 2, 2)
        ]
    );
    assert!(host.logs.borrow().iter().any(|l| l == "update: portable .exe path unknown - running the downloaded YPtun-1.0.0-Portable.EXE instead"));
    assert!(is_portable_exe(&asset("YPtun-portable.exe", None)));
    assert!(!is_portable_exe(&asset("YPtun-setup.exe", None)));
    fs::remove_dir_all(&dir).ok();
}
