use std::fs;
use std::path::PathBuf;

use yptun_core::portable_data::*;

fn temp(name: &str) -> PathBuf {
    let nanos = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos();
    let d = std::env::temp_dir().join(format!("yptun-pd-{}-{}-{}", name, std::process::id(), nanos));
    fs::create_dir_all(&d).unwrap();
    d
}

fn hex(s: &str) -> Vec<u8> {
    (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect()
}

fn fill(dir: &PathBuf) {
    fs::write(dir.join("profiles.json"), "[1,2,3]").unwrap();
    fs::write(dir.join("device_identity"), "id-123").unwrap();
    fs::write(dir.join("notes.txt"), "skip").unwrap();
    fs::create_dir_all(dir.join("settings")).unwrap();
    fs::write(dir.join("settings").join("ui.json"), "{\"connectionMode\":\"proxy\"}").unwrap();
    fs::create_dir_all(dir.join("cache")).unwrap();
    fs::write(dir.join("cache").join("geo.json"), "skip").unwrap();
}

#[test]
fn included_paths() {
    assert!(included("profiles.json"));
    assert!(included("device_identity"));
    assert!(included("settings/ui.json"));
    assert!(included("settings\\ui.json"));
    assert!(included("settings/anything"));
    assert!(!included("settings/a/b.json"));
    assert!(!included("cache/geo.json"));
    assert!(!included("notes.txt"));
    assert!(!included("../x.json"));
    assert!(!included("/abs.json"));
    assert!(!included("yptun.log"));
}

#[test]
fn gcm_known_vectors() {
    let key = [0u8; 32];
    let nonce = [0u8; 12];
    assert_eq!(aes256_gcm_encrypt(&key, &nonce, &[], &[]), hex("530f8afbc74536b9a963b4f1c4cb738b"));
    assert_eq!(
        aes256_gcm_encrypt(&key, &nonce, &[], &[0u8; 16]),
        hex("cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919")
    );
    let sealed = aes256_gcm_encrypt(&key, &nonce, b"aad", b"hello world, longer than one block!");
    assert_eq!(aes256_gcm_decrypt(&key, &nonce, b"aad", &sealed).unwrap(), b"hello world, longer than one block!".to_vec());
    assert!(aes256_gcm_decrypt(&key, &nonce, b"other", &sealed).is_err());
}

#[test]
fn seal_and_open() {
    let payload = b"some zip bytes".to_vec();
    let sealed = seal(&payload).unwrap();
    assert_eq!(&sealed[..4], PORTABLE_DATA_MAGIC);
    assert_eq!(sealed.len(), 4 + 12 + payload.len() + 16);
    assert_eq!(open(&sealed).unwrap(), payload);
    let mut tampered = sealed.clone();
    let last = tampered.len() - 1;
    tampered[last] ^= 1;
    assert!(open(&tampered).is_err());
    let mut body = sealed.clone();
    body[20] ^= 1;
    assert!(open(&body).is_err());
    let mut foreign = sealed.clone();
    foreign[0] = b'X';
    assert_eq!(open(&foreign).unwrap_err(), "not a YPtun data file");
    assert_eq!(open(b"YPD1short").unwrap_err(), "not a YPtun data file");
    let a = seal_with_nonce(b"x", &[7u8; 12]);
    let b = seal_with_nonce(b"x", &[7u8; 12]);
    assert_eq!(a, b);
    assert_ne!(seal(b"x").unwrap(), seal(b"x").unwrap());
}

#[test]
fn zip_roundtrip() {
    let entries = vec![
        (String::from("a.json"), b"{}".to_vec()),
        (String::from("settings/ui.json"), vec![b'z'; 5000]),
        (String::from("empty.json"), Vec::new()),
    ];
    let zip = zip_entries(&entries).unwrap();
    assert_eq!(&zip[..4], &[0x50, 0x4b, 0x03, 0x04]);
    let items = unzip_entries(&zip).unwrap();
    assert_eq!(items.len(), 3);
    for (item, (name, data)) in items.iter().zip(entries.iter()) {
        assert_eq!(&item.name, name);
        assert_eq!(&item.data, data);
        assert!(!item.is_directory);
    }
    assert!(zip.len() < 5000);
    assert!(unzip_entries(b"garbage").is_err());
    let mut broken = zip.clone();
    let at = 30 + "a.json".len();
    broken[at] ^= 0xFF;
    assert!(unzip_entries(&broken).is_err());
}

#[test]
fn pack_and_unpack() {
    let src = temp("src");
    fill(&src);
    let names: Vec<String> = included_files(&src).into_iter().map(|(n, _)| n).collect();
    assert_eq!(names, vec!["device_identity", "profiles.json", "settings/ui.json"]);
    let sig = signature(&src);
    assert!(sig.starts_with("device_identity:6:"));
    assert_eq!(sig.matches('|').count(), 2);
    let zip = pack(&src).unwrap();
    let dst = temp("dst");
    unpack_into(&dst, &zip).unwrap();
    assert_eq!(fs::read_to_string(dst.join("profiles.json")).unwrap(), "[1,2,3]");
    assert_eq!(fs::read_to_string(dst.join("device_identity")).unwrap(), "id-123");
    assert_eq!(fs::read_to_string(dst.join("settings").join("ui.json")).unwrap(), "{\"connectionMode\":\"proxy\"}");
    assert!(!dst.join("notes.txt").exists());
    clear_included(&dst);
    assert!(included_files(&dst).is_empty());
    let _ = fs::remove_dir_all(&src);
    let _ = fs::remove_dir_all(&dst);
}

#[test]
fn unpack_refuses_foreign_entries() {
    let dst = temp("evil");
    let evil = zip_entries(&[(String::from("evil.txt"), b"x".to_vec())]).unwrap();
    assert_eq!(unpack_into(&dst, &evil).unwrap_err(), "unexpected entry in the portable data: evil.txt");
    let up = zip_entries(&[(String::from("../up.json"), b"x".to_vec())]).unwrap();
    assert!(unpack_into(&dst, &up).is_err());
    assert!(!dst.parent().unwrap().join("up.json").exists());
    let _ = fs::remove_dir_all(&dst);
}

#[test]
fn saver_writes_only_on_change() {
    let dir = temp("saver");
    fill(&dir);
    let out = temp("saver-out");
    let bundle = out.join(PORTABLE_DATA_FILE_NAME);
    let saver = Saver::new(dir.clone(), bundle.clone());
    assert!(saver.save_if_changed());
    assert!(!saver.save_if_changed());
    assert!(!out.join("YPtun.data.tmp").exists());
    let restored = temp("restored");
    unpack_into(&restored, &open(&fs::read(&bundle).unwrap()).unwrap()).unwrap();
    assert_eq!(fs::read_to_string(restored.join("device_identity")).unwrap(), "id-123");
    fs::write(dir.join("extra.json"), "1").unwrap();
    assert!(saver.save_if_changed());
    let empty = temp("saver-empty");
    assert!(!Saver::new(empty.clone(), empty.join(PORTABLE_DATA_FILE_NAME)).save_if_changed());
    for d in [dir, out, restored, empty] {
        let _ = fs::remove_dir_all(d);
    }
}

#[test]
fn decide_portable_dir() {
    let standard = temp("standard");
    let (d, b) = decide(standard.clone(), false, None, &|| -> std::io::Result<PathBuf> { Ok(PathBuf::from("unused")) });
    assert_eq!((d, b), (standard.clone(), None));
    let (d, b) = decide(standard.clone(), true, None, &|| -> std::io::Result<PathBuf> { Ok(PathBuf::from("unused")) });
    assert_eq!((d, b), (standard.clone(), None));

    let exe_dir = temp("exe");
    let exe = exe_dir.join("YPtun.exe");
    fs::write(&exe, "").unwrap();
    let (d, b) = decide(standard.clone(), true, Some(&exe), &|| -> std::io::Result<PathBuf> { Ok(PathBuf::from("unused")) });
    assert_eq!(d, standard);
    assert_eq!(b, Some(exe_dir.join(PORTABLE_DATA_FILE_NAME)));

    let src = temp("bundle-src");
    fill(&src);
    fs::write(exe_dir.join(PORTABLE_DATA_FILE_NAME), seal(&pack(&src).unwrap()).unwrap()).unwrap();
    let private = temp("private");
    fs::write(private.join("stale.json"), "old").unwrap();
    let p2 = private.clone();
    let (d, _) = decide(standard.clone(), true, Some(&exe), &move || -> std::io::Result<PathBuf> { Ok(p2.clone()) });
    assert_eq!(d, private);
    assert!(!private.join("stale.json").exists());
    assert_eq!(fs::read_to_string(private.join("profiles.json")).unwrap(), "[1,2,3]");

    fs::write(exe_dir.join(PORTABLE_DATA_FILE_NAME), b"broken").unwrap();
    let p3 = private.clone();
    let (d, b) = decide(standard.clone(), true, Some(&exe), &move || -> std::io::Result<PathBuf> { Ok(p3.clone()) });
    assert_eq!(d, standard);
    assert_eq!(b, Some(exe_dir.join(PORTABLE_DATA_FILE_NAME)));
    assert!(exe_dir.join("YPtun.data.bad").is_file());
    assert!(!exe_dir.join(PORTABLE_DATA_FILE_NAME).exists());
    for d in [standard, exe_dir, src, private] {
        let _ = fs::remove_dir_all(d);
    }
}
