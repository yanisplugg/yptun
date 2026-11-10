use std::fs;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};

use flate2::write::GzEncoder;
use flate2::Compression;
use serde_json::json;
use sha2::{Digest, Sha256};
use yptun_core::delta_bundle::{self as delta, FbfApplier, MemoryBundle, Op};

/// Test applier: new = old ++ delta.
struct AppendApplier;

impl FbfApplier for AppendApplier {
    fn apply_delta(&mut self, old: &Path, d: &mut dyn Read, out: &mut dyn Write, temp_dir: &Path) -> Result<(), String> {
        assert!(temp_dir.is_dir());
        let mut data = fs::read(old).map_err(|e| e.to_string())?;
        d.read_to_end(&mut data).map_err(|e| e.to_string())?;
        out.write_all(&data).map_err(|e| e.to_string())
    }
}

fn sha(b: &[u8]) -> String {
    Sha256::digest(b).iter().map(|x| format!("{x:02x}")).collect()
}

fn gz(b: &[u8]) -> Vec<u8> {
    let mut e = GzEncoder::new(Vec::new(), Compression::default());
    e.write_all(b).unwrap();
    e.finish().unwrap()
}

fn temp_dir(tag: &str) -> PathBuf {
    let p = std::env::temp_dir().join(format!("yptun-delta-{}-{}", tag, std::process::id()));
    let _ = fs::remove_dir_all(&p);
    fs::create_dir_all(&p).unwrap();
    p
}

fn op(kind: &str, from: &str, to: &str, from_sha: &str, to_sha: &str, payload: &str) -> Op {
    Op { op: kind.into(), from: from.into(), to: to.into(), from_sha: from_sha.into(), to_sha: to_sha.into(), payload: payload.into() }
}

fn bundle(ops: serde_json::Value, payloads: &[(&str, Vec<u8>)]) -> MemoryBundle {
    let mut b = MemoryBundle::default();
    let manifest = json!({"format": 3, "from": "1.0", "to": "1.1", "target": "windows-x64", "ops": ops});
    b.entries.insert("manifest.json".into(), manifest.to_string().into_bytes());
    for (k, v) in payloads {
        b.entries.insert((*k).into(), v.clone());
    }
    b
}

/// root/app/old-1.jar = "OLD", root/app/gone.jar, root/app/YPtun.cfg = "cfg1"
fn install(tag: &str) -> PathBuf {
    let root = temp_dir(tag).join("image");
    fs::create_dir_all(root.join("app")).unwrap();
    fs::write(root.join("app/old-1.jar"), b"OLD").unwrap();
    fs::write(root.join("app/gone.jar"), b"G").unwrap();
    fs::write(root.join("app/YPtun.cfg"), b"cfg1").unwrap();
    root
}

#[test]
fn parse_manifest_rules() {
    let m = delta::parse_manifest("\u{FEFF}{\"from\":\"1\",\"extra\":true,\"ops\":[{\"op\":\"delete\",\"from\":\"app/a.jar\"}]}".as_bytes()).unwrap();
    assert_eq!(m.format, 3);
    assert_eq!(m.from, "1");
    assert_eq!(m.to, "");
    assert_eq!(m.ops[0], op("delete", "app/a.jar", "", "", "", ""));
    let m = delta::parse_manifest(br#"{"format":3,"ops":[{"op":"patch","fromSha":"AA","toSha":"bb","payload":"p"}]}"#).unwrap();
    assert_eq!((m.ops[0].from_sha.as_str(), m.ops[0].to_sha.as_str()), ("AA", "bb"));
    assert_eq!(delta::parse_manifest(br#"{"format":2}"#), Err("unsupported delta format 2".to_string()));
    assert!(delta::parse_manifest(b"not json").is_err());
}

#[test]
fn ordering_puts_deletes_and_cfg_last() {
    let ops = vec![
        op("delete", "app/a.jar", "", "", "", ""),
        op("add", "", "app/YPtun.cfg", "", "", "c"),
        op("patch", "app/x-1.jar", "app/x-2.jar", "", "", "p"),
        op("add", "", "app/y.jar", "", "", "y"),
    ];
    let names: Vec<String> = delta::ordered_ops(&ops).iter().map(|o| format!("{}:{}{}", o.op, o.from, o.to)).collect();
    assert_eq!(names, vec!["patch:app/x-1.jarapp/x-2.jar", "add:app/y.jar", "delete:app/a.jar", "add:app/YPtun.cfg"]);
}

#[test]
fn safe_join_rules() {
    let root = temp_dir("safe");
    assert_eq!(delta::safe_join(&root, "app/x.jar").unwrap(), root.join("app").join("x.jar"));
    assert_eq!(delta::safe_join(&root, "app/./a/../b.jar").unwrap(), root.join("app").join("b.jar"));
    let esc = Err("delta bundle path escapes the installation".to_string());
    assert_eq!(delta::safe_join(&root, "../evil"), esc);
    assert_eq!(delta::safe_join(&root, "app/../../evil"), esc);
    let _ = fs::remove_dir_all(&root);
}

#[test]
fn stage_builds_the_plan() {
    let root = install("stage");
    let work = root.parent().unwrap().to_path_buf();
    let staging = work.join("staging");
    fs::create_dir_all(&staging).unwrap();
    fs::write(staging.join("junk"), b"x").unwrap();
    let ops = json!([
        {"op": "add", "to": "app/YPtun.cfg", "toSha": sha(b"cfg2"), "payload": "c"},
        {"op": "delete", "from": "app/gone.jar"},
        {"op": "patch", "from": "app/old-1.jar", "to": "app/new-2.jar", "fromSha": sha(b"OLD").to_uppercase(), "toSha": sha(b"OLD+NEW"), "payload": "p1"}
    ]);
    let mut b = bundle(ops, &[("c", b"cfg2".to_vec()), ("p1", gz(b"+NEW"))]);
    let mut steps: Vec<(usize, usize)> = Vec::new();
    let plan = delta::stage(&root, &mut b, &mut AppendApplier, &staging, &work.join("tmp"), &mut |d: usize, t: usize| steps.push((d, t))).unwrap();
    assert_eq!(steps, vec![(0, 3), (1, 3), (2, 3)]);
    assert!(!staging.join("junk").exists());
    assert_eq!(
        plan.moves,
        vec![
            (staging.join("app").join("new-2.jar"), root.join("app").join("new-2.jar")),
            (staging.join("app").join("YPtun.cfg"), root.join("app").join("YPtun.cfg")),
        ]
    );
    assert_eq!(plan.deletions, vec![root.join("app").join("old-1.jar"), root.join("app").join("gone.jar")]);
    assert_eq!(fs::read(staging.join("app/new-2.jar")).unwrap(), b"OLD+NEW");
    assert_eq!(plan.root_dir, root);
    assert_eq!(fs::read(root.join("app/YPtun.cfg")).unwrap(), b"cfg1");
    let _ = fs::remove_dir_all(&work);
}

#[test]
fn stage_refuses_what_does_not_fit() {
    let root = install("refuse");
    let work = root.parent().unwrap().to_path_buf();
    let run = |ops: serde_json::Value, payloads: &[(&str, Vec<u8>)]| {
        let mut b = bundle(ops, payloads);
        delta::stage(&root, &mut b, &mut AppendApplier, &work.join("staging"), &work.join("tmp"), &mut |_: usize, _: usize| {}).unwrap_err()
    };
    assert_eq!(
        run(json!([{"op": "patch", "from": "app/old-1.jar", "to": "app/n.jar", "fromSha": sha(b"other"), "toSha": "", "payload": "p"}]), &[]),
        "installed app/old-1.jar is not this patch's base"
    );
    assert_eq!(run(json!([{"op": "patch", "from": "app/nope.jar", "to": "app/n.jar"}]), &[]), "installed file missing: app/nope.jar");
    assert_eq!(
        run(json!([{"op": "patch", "from": "app/old-1.jar", "to": "app/n.jar", "fromSha": sha(b"OLD"), "toSha": sha(b"x"), "payload": "p"}]), &[("p", gz(b"+N"))]),
        "rebuilt app/n.jar does not match the published file"
    );
    assert_eq!(
        run(json!([{"op": "add", "to": "app/YPtun.cfg", "toSha": sha(b"other"), "payload": "c"}]), &[("c", b"cfg2".to_vec())]),
        "rebuilt app/YPtun.cfg does not match the published file"
    );
    assert_eq!(run(json!([{"op": "add", "to": "app/z.jar", "payload": "c"}]), &[]), "missing payload c");
    assert_eq!(run(json!([{"op": "move", "from": "a"}]), &[]), "unknown delta operation 'move'");
    assert_eq!(run(json!([{"op": "delete", "from": "../../etc/passwd"}]), &[]), "delta bundle path escapes the installation");
    assert_eq!(run(json!([{"op": "add", "to": "../x", "payload": "c"}]), &[("c", vec![1])]), "delta bundle path escapes the installation");

    let mut empty = MemoryBundle::default();
    assert_eq!(
        delta::stage(&root, &mut empty, &mut AppendApplier, &work.join("staging"), &work.join("tmp"), &mut |_: usize, _: usize| {}).unwrap_err(),
        "delta bundle has no manifest.json"
    );
    let _ = fs::remove_dir_all(&work);
}

#[test]
fn android_apk_patch() {
    let dir = temp_dir("apk");
    fs::write(dir.join("base.apk"), b"APK1").unwrap();
    fs::write(dir.join("p.gz"), gz(b"->2")).unwrap();
    let tmp = dir.join(delta::ANDROID_PATCH_TMP);
    let out = delta::apply_apk_patch(&mut AppendApplier, &dir.join("base.apk"), &dir.join("p.gz"), &dir.join("new.apk"), &tmp).unwrap();
    assert_eq!(out, dir.join("new.apk"));
    assert_eq!(fs::read(&out).unwrap(), b"APK1->2");
    assert!(!tmp.exists());
    assert_eq!(delta::sha256_file(&out).unwrap(), sha(b"APK1->2"));
    let _ = fs::remove_dir_all(&dir);
}
