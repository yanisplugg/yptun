use std::fs;
use std::path::PathBuf;
use std::time::{SystemTime, UNIX_EPOCH};

use yptun_core::desktop_app_image::{installed_desktop_fingerprint, sha256, DesktopAppImage};
use yptun_core::desktop_native_assets::DesktopOs;

fn temp_dir(tag: &str) -> PathBuf {
    let nanos = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
    let d = std::env::temp_dir().join(format!("yptun-{tag}-{}-{nanos}", std::process::id()));
    fs::create_dir_all(&d).unwrap();
    d
}

/// `<root>/app/YPtun.jar`, `<root>/app/YPtun.cfg`, `<root>/runtime/`; returns the jar.
fn image(root: &PathBuf, app: &str) -> PathBuf {
    fs::create_dir_all(root.join(app)).unwrap();
    fs::create_dir_all(root.join("runtime")).unwrap();
    let jar = root.join(app).join("YPtun.jar");
    fs::write(&jar, "jar").unwrap();
    fs::write(root.join(app).join("YPtun.cfg"), "cp").unwrap();
    jar
}

#[test]
fn detects_installed_layout() {
    let root = temp_dir("img");
    let jar = image(&root, "app");
    let img = DesktopAppImage::from_running_jar(&jar).expect("image");
    assert_eq!(img.app_dir(), root.join("app").as_path());
    assert_eq!(img.install_dir(), root.as_path());
    let cfg = img.classpath_file().unwrap();
    assert_eq!(cfg, root.join("app").join("YPtun.cfg"));
    let fp = installed_desktop_fingerprint(Some(&jar)).unwrap();
    assert_eq!(fp, sha256(&cfg).unwrap());
    assert_eq!(fp.len(), 64);
    assert_eq!(installed_desktop_fingerprint(None), None);
    fs::remove_dir_all(&root).ok();
}

#[test]
fn rejects_development_runs() {
    let root = temp_dir("img-bad");
    // not a jar
    let txt = root.join("app").join("x.txt");
    image(&root, "app");
    fs::write(&txt, "t").unwrap();
    assert_eq!(DesktopAppImage::from_running_jar(&txt), None);
    // missing file
    assert_eq!(DesktopAppImage::from_running_jar(&root.join("app").join("nope.jar")), None);
    // jar not in app/
    let other = root.join("libs");
    fs::create_dir_all(&other).unwrap();
    fs::write(other.join("a.jar"), "j").unwrap();
    assert_eq!(DesktopAppImage::from_running_jar(&other.join("a.jar")), None);
    // no runtime/
    fs::remove_dir_all(root.join("runtime")).unwrap();
    assert_eq!(DesktopAppImage::from_running_jar(&root.join("app").join("YPtun.jar")), None);
    fs::remove_dir_all(&root).ok();
}

#[test]
fn sha256_known_vector() {
    let root = temp_dir("img-sha");
    let f = root.join("abc.txt");
    fs::write(&f, "abc").unwrap();
    assert_eq!(
        sha256(&f).unwrap(),
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    );
    fs::remove_dir_all(&root).ok();
}

#[test]
fn windows_launcher() {
    let root = temp_dir("img-win");
    let jar = image(&root, "app");
    let img = DesktopAppImage::from_running_jar(&jar).unwrap();
    assert_eq!(img.launcher(DesktopOs::Windows), None);
    fs::write(root.join("YPtun.exe"), "exe").unwrap();
    assert_eq!(img.launcher(DesktopOs::Windows), Some(root.join("YPtun.exe")));
    fs::remove_dir_all(&root).ok();
}

#[test]
fn linux_deb_launcher() {
    let base = temp_dir("img-deb");
    let lib = base.join("lib");
    let jar = image(&lib, "app");
    fs::create_dir_all(base.join("bin")).unwrap();
    fs::write(base.join("bin").join("YPtun"), "sh").unwrap();
    let img = DesktopAppImage::from_running_jar(&jar).unwrap();
    assert_eq!(img.launcher(DesktopOs::Linux), Some(base.join("bin").join("YPtun")));
    fs::remove_dir_all(&base).ok();
}
