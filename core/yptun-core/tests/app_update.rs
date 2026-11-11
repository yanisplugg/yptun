//! Port of the Kotlin `AppUpdateServiceTest`, plus the request/error/settings behaviour the Kotlin
//! tests left to Ktor.

use std::cmp::Ordering;
use std::collections::HashMap;
use std::sync::atomic::{AtomicUsize, Ordering as AtomicOrdering};
use std::sync::{Arc, Mutex};

use yptun_core::repository::{HttpFetcher, HttpRequest, HttpResponse};
use yptun_core::update::*;

const HOUR_MS: i64 = 60 * 60 * 1_000;
const RELEASES_URL: &str = "https://api.github.com/repos/yanisplugg/yptun/releases?per_page=20";
const NIGHTLY_URL: &str = "https://api.github.com/repos/yanisplugg/yptun/releases/tags/nightly";

/// api.github.com stand-in: canned responses by URL, every request recorded. Unknown URL = no
/// response at all.
#[derive(Clone, Default)]
struct FakeGithub {
    responses: Arc<Mutex<HashMap<String, (u16, String)>>>,
    requests: Arc<Mutex<Vec<HttpRequest>>>,
}

impl FakeGithub {
    fn serving(url: &str, status: u16, body: &str) -> Self {
        let fake = Self::default();
        fake.responses
            .lock()
            .unwrap()
            .insert(url.to_string(), (status, body.to_string()));
        fake
    }

    fn requests(&self) -> Vec<HttpRequest> {
        self.requests.lock().unwrap().clone()
    }
}

impl HttpFetcher for FakeGithub {
    fn get(&self, request: &HttpRequest) -> Option<HttpResponse> {
        self.requests.lock().unwrap().push(request.clone());
        let responses = self.responses.lock().unwrap();
        let (status, body) = responses.get(&request.url)?;
        Some(HttpResponse {
            status: *status,
            headers: vec![],
            body: body.clone(),
        })
    }
}

fn asset(name: &str, url: &str) -> GithubReleaseAsset {
    GithubReleaseAsset::new(name, url)
}

fn names(asset: Option<AppUpdateAsset>) -> Option<String> {
    asset.map(|a| a.name)
}

#[test]
fn stable_version_comparison_uses_semver_parts() {
    assert!(is_update_available(
        ReleaseChannel::Stable,
        "v1.2.0",
        "1.1.9"
    ));
    assert!(!is_update_available(
        ReleaseChannel::Stable,
        "v1.0.0",
        "1.0.0"
    ));
    assert!(is_update_available(
        ReleaseChannel::Nightly,
        "nightly",
        "9.9.9"
    ));
    assert!(is_update_available(
        ReleaseChannel::Nightly,
        "1.0.2",
        "1.0.1"
    ));
    assert!(!is_update_available(
        ReleaseChannel::Nightly,
        "1.0.2",
        "1.0.2"
    ));
}

#[test]
fn update_settings_normalize_to_stable_channel() {
    let settings = AppUpdateSettings {
        channel: ReleaseChannel::Nightly,
        ..Default::default()
    };

    assert_eq!(ReleaseChannel::Stable, settings.normalized().channel);
}

#[test]
fn nightly_uses_version_from_asset_name_when_available() {
    let github = FakeGithub::serving(
        NIGHTLY_URL,
        200,
        r#"{
          "tag_name": "nightly",
          "html_url": "https://example/release",
          "published_at": "2026-05-13T12:00:00Z",
          "assets": [
            {
              "name": "Olcbox-1.0.42-android-release.apk",
              "browser_download_url": "https://example/app.apk",
              "size": 100,
              "updated_at": "2026-05-13T12:00:00Z"
            }
          ]
        }"#,
    );
    let service = AppUpdateService::new(Box::new(github), "1.0.42")
        .with_platform(UpdatePlatform::new("android", "arm64"));

    let info = service.check(ReleaseChannel::Nightly, None).unwrap();

    assert_eq!("1.0.42", info.version);
    assert!(!info.is_update_available);
}

#[test]
fn selects_preferred_asset_for_platform() {
    let assets = [
        asset(
            "Olcbox-1.0.0-windows-amd64-portable.zip",
            "https://example/zip",
        ),
        asset("Olcbox-1.0.0-windows-amd64.msi", "https://example/msi"),
    ];

    let selected = select_asset(&assets, &UpdatePlatform::new("windows", "amd64"), false);

    assert_eq!(
        Some("Olcbox-1.0.0-windows-amd64.msi".into()),
        names(selected)
    );
}

#[test]
fn selects_windows_asset_named_with_x64() {
    // Real release naming: "YPtun-3.3.2-x64-installer.exe": no "windows" token, "x64" instead of
    // "amd64". The old rule required both and matched nothing at all.
    let assets = [
        asset("YPtun-v3.3.2-arm64-v8a.apk", "https://example/apk"),
        asset("YPtun-3.3.2-x64-portable.exe", "https://example/portable"),
        asset("YPtun-3.3.2-x64-installer.exe", "https://example/installer"),
        asset("YPtun-3.3.2-arm64-installer.exe", "https://example/arm64"),
    ];

    let selected = select_asset(&assets, &UpdatePlatform::new("windows", "amd64"), false);

    // An installed copy must get the installer; the portable .exe is for a running portable.
    assert_eq!(
        Some("YPtun-3.3.2-x64-installer.exe".into()),
        names(selected)
    );
}

#[test]
fn portable_copy_gets_the_portable_exe() {
    let assets = [
        asset("YPtun-3.3.2-x64-installer.exe", "https://example/installer"),
        asset("YPtun-3.3.2-x64-portable.exe", "https://example/portable"),
        asset(
            "YPtun-3.3.2-arm64-portable.exe",
            "https://example/arm64-portable",
        ),
    ];

    let selected = select_asset(&assets, &UpdatePlatform::new("windows", "amd64"), true);

    assert_eq!(Some("YPtun-3.3.2-x64-portable.exe".into()), names(selected));
}

#[test]
fn release_asset_carries_github_sha256() {
    let hex = "ab".repeat(32);
    let with_digest = |digest: &str| GithubReleaseAsset {
        digest: Some(digest.to_string()),
        ..asset("a.exe", "u")
    };

    assert_eq!(
        Some(hex.clone()),
        with_digest(&format!("sha256:{hex}"))
            .to_update_asset()
            .sha256
    );
    assert_eq!(None, with_digest("sha1:abc").to_update_asset().sha256);
    assert_eq!(None, asset("a.exe", "u").to_update_asset().sha256);
    // Trimmed and lowercased like the Kotlin one; anything but 64 hex characters is refused.
    assert_eq!(
        Some(hex.clone()),
        with_digest(&format!("sha256: {} ", hex.to_uppercase()))
            .to_update_asset()
            .sha256
    );
    assert_eq!(
        None,
        with_digest(&format!("sha256:{}", &hex[1..]))
            .to_update_asset()
            .sha256
    );
    assert_eq!(
        None,
        with_digest(&format!("sha256:{}zz", &hex[2..]))
            .to_update_asset()
            .sha256
    );
}

#[test]
fn windows_on_arm_does_not_take_the_x64_build() {
    let assets = [
        asset("YPtun-3.3.2-x64-installer.exe", "https://example/x64"),
        asset("YPtun-3.3.2-arm64-installer.exe", "https://example/arm64"),
    ];

    let selected = select_asset(&assets, &UpdatePlatform::new("windows", "arm64"), false);

    assert_eq!(
        Some("YPtun-3.3.2-arm64-installer.exe".into()),
        names(selected)
    );
}

#[test]
fn windows_ignores_linux_and_android_assets() {
    let assets = [
        asset("YPtun-v3.3.2-x86_64.apk", "https://example/apk"),
        asset("YPtun-3.3.2-linux-amd64.deb", "https://example/deb"),
    ];

    assert_eq!(
        None,
        select_asset(&assets, &UpdatePlatform::new("windows", "amd64"), false)
    );
}

#[test]
fn linux_takes_the_deb_of_its_arch() {
    let assets = [
        asset("YPtun-3.5.1-amd64.AppImage", "https://example/appimage"),
        asset("YPtun-3.5.1-amd64.deb", "https://example/deb-amd64"),
        asset("YPtun-3.5.1-arm64.deb", "https://example/deb-arm64"),
        asset("YPtun-3.5.1-x64-installer.exe", "https://example/exe"),
    ];

    assert_eq!(
        Some("YPtun-3.5.1-amd64.deb".into()),
        names(select_asset(
            &assets,
            &UpdatePlatform::new("linux", "amd64"),
            false
        ))
    );
    assert_eq!(
        Some("YPtun-3.5.1-arm64.deb".into()),
        names(select_asset(
            &assets,
            &UpdatePlatform::new("linux", "arm64"),
            false
        ))
    );
}

#[test]
fn universal_apk_install_takes_the_universal_delta() {
    let assets = [
        asset(
            "YPtun-delta-3.3.2-3.4.4-arm64-v8a.patch.gz",
            "https://example/arm64",
        ),
        asset(
            "YPtun-delta-3.3.2-3.4.4-universal.patch.gz",
            "https://example/universal",
        ),
    ];

    let selected = select_delta_asset(
        &assets,
        &UpdatePlatform::new("android", "arm64"),
        "3.3.2",
        "3.4.4",
        "YPtun-v3.4.4-universal.apk",
        None,
    );

    assert_eq!(
        Some("YPtun-delta-3.3.2-3.4.4-universal.patch.gz".into()),
        names(selected)
    );
}

/// The real shape of the universal-install bug: on an arm64 phone `select_asset` always returns
/// the arm64 APK, so the ABI heuristic pointed at the arm64 patch even when the user had
/// installed the universal one. Only the installed APK's own hash can tell them apart.
#[test]
fn delta_is_picked_by_the_installed_hash_not_the_abi() {
    let universal_base = "b".repeat(64);
    let universal_patch = format!(
        "YPtun-delta-3.5.4-3.5.5-universal-{}.patch.gz",
        "b".repeat(16)
    );
    let assets = [
        asset(
            &format!(
                "YPtun-delta-3.5.4-3.5.5-arm64-v8a-{}.patch.gz",
                "a".repeat(16)
            ),
            "https://example/arm64",
        ),
        asset(&universal_patch, "https://example/universal"),
    ];

    let selected = select_delta_asset(
        &assets,
        &UpdatePlatform::new("android", "arm64"),
        "3.5.4",
        "3.5.5",
        "YPtun-v3.5.5-arm64-v8a.apk",
        Some(&universal_base),
    );

    assert_eq!(Some(universal_patch), names(selected));
}

/// A locally built 3.5.4 has the published 3.5.4's version and none of its bytes.
#[test]
fn delta_from_another_build_of_the_same_version_is_skipped() {
    let assets = [asset(
        &format!(
            "YPtun-delta-3.5.4-3.5.5-arm64-v8a-{}.patch.gz",
            "a".repeat(16)
        ),
        "https://example/arm64",
    )];

    let selected = select_delta_asset(
        &assets,
        &UpdatePlatform::new("android", "arm64"),
        "3.5.4",
        "3.5.5",
        "YPtun-v3.5.5-arm64-v8a.apk",
        Some(&"c".repeat(64)),
    );

    assert_eq!(None, selected);
}

/// Releases published before the hash suffix existed must keep resolving by name alone.
#[test]
fn untagged_deltas_still_resolve_when_the_base_hash_is_known() {
    let assets = [
        asset(
            "YPtun-delta-3.5.4-3.5.5-arm64-v8a.patch.gz",
            "https://example/arm64",
        ),
        asset(
            "YPtun-delta-3.5.4-3.5.5-universal.patch.gz",
            "https://example/universal",
        ),
    ];

    let selected = select_delta_asset(
        &assets,
        &UpdatePlatform::new("android", "arm64"),
        "3.5.4",
        "3.5.5",
        "YPtun-v3.5.5-arm64-v8a.apk",
        Some(&"c".repeat(64)),
    );

    assert_eq!(
        Some("YPtun-delta-3.5.4-3.5.5-arm64-v8a.patch.gz".into()),
        names(selected)
    );
}

#[test]
fn desktop_delta_is_picked_by_the_installed_image_hash() {
    let mine = format!(
        "YPtun-delta-3.5.4-3.5.5-windows-amd64-{}.patch",
        "d".repeat(16)
    );
    let assets = [
        asset(
            &format!(
                "YPtun-delta-3.5.4-3.5.5-windows-amd64-{}.patch",
                "a".repeat(16)
            ),
            "https://example/stale",
        ),
        asset(&mine, "https://example/mine"),
    ];

    let selected = select_delta_asset(
        &assets,
        &UpdatePlatform::new("windows", "amd64"),
        "3.5.4",
        "3.5.5",
        "YPtun-3.5.5-x64-installer.exe",
        Some(&"d".repeat(64)),
    );

    assert_eq!(Some(mine), names(selected));
}

#[test]
fn selects_nightly_android_apk() {
    let selected = select_asset(
        &[asset(
            "olcbox-nightly-android-arm64.apk",
            "https://example/app.apk",
        )],
        &UpdatePlatform::new("android", "arm64"),
        false,
    );

    assert_eq!(
        Some("https://example/app.apk"),
        selected.as_ref().map(|a| a.download_url.as_str())
    );
}

#[test]
fn prefers_android_abi_specific_apk() {
    let selected = select_asset(
        &[
            asset(
                "Olcbox-1.0.70-android-release.apk",
                "https://example/universal.apk",
            ),
            asset(
                "Olcbox-1.0.70-android-armeabi-v7a-release.apk",
                "https://example/armeabi.apk",
            ),
        ],
        &UpdatePlatform::new("android", "armeabi-v7a"),
        false,
    );

    assert_eq!(
        Some("https://example/armeabi.apk"),
        selected.as_ref().map(|a| a.download_url.as_str())
    );
}

#[test]
fn falls_back_to_universal_android_apk() {
    let selected = select_asset(
        &[
            asset(
                "Olcbox-1.0.70-android-armeabi-v7a-release.apk",
                "https://example/armeabi.apk",
            ),
            asset(
                "Olcbox-1.0.70-android-release.apk",
                "https://example/universal.apk",
            ),
        ],
        &UpdatePlatform::new("android", "arm64"),
        false,
    );

    assert_eq!(
        Some("https://example/universal.apk"),
        selected.as_ref().map(|a| a.download_url.as_str())
    );
}

#[test]
fn selects_release_apk_without_android_token() {
    let assets = [
        asset("YPtun-v2.1.0-arm64-v8a.apk", "https://example/arm64.apk"),
        asset(
            "YPtun-v2.1.0-armeabi-v7a.apk",
            "https://example/armeabi.apk",
        ),
        asset("YPtun-v2.1.0-x86_64.apk", "https://example/x86_64.apk"),
        asset(
            "YPtun-v2.1.0-universal.apk",
            "https://example/universal.apk",
        ),
    ];
    let url = |assets: &[GithubReleaseAsset], arch: &str| {
        select_asset(assets, &UpdatePlatform::new("android", arch), false).map(|a| a.download_url)
    };

    assert_eq!(
        Some("https://example/arm64.apk".into()),
        url(&assets, "arm64")
    );
    assert_eq!(
        Some("https://example/armeabi.apk".into()),
        url(&assets, "armeabi-v7a")
    );
    // A device whose ABI has no dedicated apk falls back to the universal build.
    let no_abi_assets = [
        asset("YPtun-v2.1.0-arm64-v8a.apk", "https://example/arm64.apk"),
        asset(
            "YPtun-v2.1.0-universal.apk",
            "https://example/universal.apk",
        ),
    ];
    assert_eq!(
        Some("https://example/universal.apk".into()),
        url(&no_abi_assets, "armeabi-v7a")
    );
}

#[test]
fn update_settings_persist_and_due_check_uses_interval() {
    let settings = AppUpdateSettings {
        channel: ReleaseChannel::Stable,
        interval_hours: 6,
        last_check_at_epoch_ms: Some(1_000),
        last_seen_update_version: Some("Stable:2.1.0:apk".into()),
        last_downloaded_update_version: None,
    };
    let store = InMemoryAppUpdateSettingsStore::default();

    store.save(&settings);

    assert_eq!(settings, store.load());
    assert!(!store.load().is_update_check_due(1_000 + 5 * HOUR_MS));
    assert!(store.load().is_update_check_due(1_000 + 6 * HOUR_MS));
}

#[test]
fn never_interval_disables_automatic_checks() {
    let store = InMemoryAppUpdateSettingsStore::default();
    store.save(&AppUpdateSettings {
        interval_hours: AppUpdateSettings::NEVER_HOURS,
        last_check_at_epoch_ms: Some(1_000),
        ..Default::default()
    });

    let loaded = store.load();
    assert_eq!(AppUpdateSettings::NEVER_HOURS, loaded.interval_hours);
    assert!(loaded.auto_check_disabled());
    assert!(!loaded.is_update_check_due(1_000 + 1000 * HOUR_MS));
}

#[test]
fn later_suppresses_same_update_until_next_interval_or_new_version() {
    let asset = AppUpdateAsset::new("olcbox-android.apk", "https://example/app.apk", Some(100));
    let info = AppUpdateInfo {
        channel: ReleaseChannel::Nightly,
        version: "nightly".into(),
        html_url: "https://example/release".into(),
        published_at: None,
        asset: asset.clone(),
        is_update_available: true,
        delta_asset: None,
    };
    let settings = AppUpdateSettings {
        channel: ReleaseChannel::Nightly,
        interval_hours: 6,
        last_check_at_epoch_ms: Some(10_000),
        last_seen_update_version: Some(info.identity()),
        last_downloaded_update_version: None,
    };

    assert!(!info.should_show_offer_at(&settings, 10_000 + 2 * HOUR_MS));
    assert!(info.should_show_offer_at(&settings, 10_000 + 6 * HOUR_MS));
    let renamed = AppUpdateInfo {
        asset: AppUpdateAsset {
            name: "olcbox-android-new.apk".into(),
            ..asset
        },
        ..info
    };
    assert!(renamed.should_show_offer_at(&settings, 10_001));
}

#[test]
fn downloaded_update_does_not_show_offer_again() {
    let asset = AppUpdateAsset {
        updated_at: Some("2026-05-13T10:00:00Z".into()),
        ..AppUpdateAsset::new(
            "olcbox-nightly-android.apk",
            "https://example/app.apk",
            Some(100),
        )
    };
    let info = AppUpdateInfo {
        channel: ReleaseChannel::Nightly,
        version: "nightly".into(),
        html_url: "https://example/release".into(),
        published_at: Some("2026-05-13T10:01:00Z".into()),
        asset: asset.clone(),
        is_update_available: true,
        delta_asset: None,
    };
    let settings = AppUpdateSettings {
        channel: ReleaseChannel::Nightly,
        interval_hours: 1,
        last_check_at_epoch_ms: Some(10_000),
        last_seen_update_version: Some(info.identity()),
        last_downloaded_update_version: Some(info.identity()),
    };

    assert!(!info.should_show_offer(&settings));
    assert!(!info.should_show_offer_at(&settings, 10_000 + 2 * HOUR_MS));
    let rebuilt = AppUpdateInfo {
        asset: AppUpdateAsset {
            updated_at: Some("2026-05-13T11:00:00Z".into()),
            ..asset
        },
        ..info
    };
    assert!(rebuilt.should_show_offer_at(&settings, 10_001));
}

// ---- Beyond the Kotlin test: what Ktor and the platform code covered there. ----

const STABLE_RELEASES: &str = r#"[
  {
    "tag_name": "v3.6.0",
    "html_url": "https://github.com/yanisplugg/yptun/releases/tag/v3.6.0",
    "published_at": "2026-06-01T10:00:00Z",
    "draft": true,
    "assets": [{"name": "YPtun-v3.6.0-arm64-v8a.apk", "browser_download_url": "https://example/draft.apk"}]
  },
  {
    "id": 42,
    "tag_name": "v3.5.5",
    "html_url": "https://github.com/yanisplugg/yptun/releases/tag/v3.5.5",
    "published_at": "2026-05-20T10:00:00Z",
    "draft": false,
    "prerelease": true,
    "assets": [
      {
        "name": "YPtun-v3.5.5-arm64-v8a.apk",
        "browser_download_url": "https://example/arm64.apk",
        "size": 52428800,
        "updated_at": "2026-05-20T10:05:00Z",
        "digest": "sha256:cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd",
        "content_type": "application/vnd.android.package-archive"
      },
      {"name": "YPtun-v3.5.5-universal.apk", "browser_download_url": "https://example/universal.apk"},
      {"name": "YPtun-delta-3.5.4-3.5.5-arm64-v8a-aaaaaaaaaaaaaaaa.patch.gz", "browser_download_url": "https://example/arm64.patch.gz", "size": 3145728},
      {"name": "YPtun-delta-3.5.4-3.5.5-universal-bbbbbbbbbbbbbbbb.patch.gz", "browser_download_url": "https://example/universal.patch.gz"},
      {"name": "YPtun-3.5.5-x64-installer.exe", "browser_download_url": "https://example/installer.exe"},
      {"name": "YPtun-3.5.5-x64-portable.exe", "browser_download_url": "https://example/portable.exe"},
      {"name": "YPtun-delta-3.5.4-3.5.5-windows-amd64-dddddddddddddddd.patch", "browser_download_url": "https://example/windows.patch"}
    ]
  }
]"#;

fn android_service(github: &FakeGithub, version: &str) -> AppUpdateService {
    AppUpdateService::new(Box::new(github.clone()), version)
        .with_platform(UpdatePlatform::new("android", "arm64"))
}

#[test]
fn stable_check_reads_the_newest_published_release_with_github_headers() {
    let github = FakeGithub::serving(RELEASES_URL, 200, STABLE_RELEASES);

    let info = android_service(&github, "3.5.4")
        .check(ReleaseChannel::Stable, Some("socks5://u:p@127.0.0.1:10808"))
        .unwrap();

    // The draft 3.6.0 is skipped; a pre-release still counts as published.
    assert_eq!("3.5.5", info.version);
    assert!(info.is_update_available);
    assert_eq!(
        "https://github.com/yanisplugg/yptun/releases/tag/v3.5.5",
        info.html_url
    );
    assert_eq!(Some("2026-05-20T10:00:00Z".into()), info.published_at);
    assert_eq!(
        AppUpdateAsset {
            name: "YPtun-v3.5.5-arm64-v8a.apk".into(),
            download_url: "https://example/arm64.apk".into(),
            size_bytes: Some(52_428_800),
            updated_at: Some("2026-05-20T10:05:00Z".into()),
            sha256: Some("cd".repeat(32)),
        },
        info.asset
    );
    // No installed fingerprint: the ABI names the patch.
    assert_eq!(
        Some("YPtun-delta-3.5.4-3.5.5-arm64-v8a-aaaaaaaaaaaaaaaa.patch.gz"),
        info.delta_asset.as_ref().map(|a| a.name.as_str())
    );
    assert_eq!(
        "https://example/arm64.patch.gz",
        info.download_asset().download_url
    );

    let requests = github.requests();
    assert_eq!(1, requests.len());
    assert_eq!(RELEASES_URL, requests[0].url);
    assert_eq!(
        vec![
            (
                "Accept".to_string(),
                "application/vnd.github+json".to_string()
            ),
            ("User-Agent".to_string(), "YPtun/3.5.4".to_string()),
        ],
        requests[0].headers
    );
    assert_eq!(
        Some("socks5://u:p@127.0.0.1:10808".to_string()),
        requests[0].proxy
    );
}

#[test]
fn installed_fingerprint_is_read_only_when_an_update_exists() {
    let github = FakeGithub::serving(RELEASES_URL, 200, STABLE_RELEASES);
    let reads = Arc::new(AtomicUsize::new(0));
    let fingerprint = |reads: &Arc<AtomicUsize>| {
        let reads = reads.clone();
        move || {
            reads.fetch_add(1, AtomicOrdering::SeqCst);
            Some("B".repeat(64))
        }
    };

    let up_to_date = android_service(&github, "3.5.5")
        .with_installed_fingerprint(fingerprint(&reads))
        .check(ReleaseChannel::Stable, None)
        .unwrap();
    assert!(!up_to_date.is_update_available);
    assert_eq!(None, up_to_date.delta_asset);
    assert_eq!(0, reads.load(AtomicOrdering::SeqCst));

    // The universal install's hash wins over the arm64 ABI (and is compared case-insensitively).
    let outdated = android_service(&github, "3.5.4")
        .with_installed_fingerprint(fingerprint(&reads))
        .check(ReleaseChannel::Stable, None)
        .unwrap();
    assert_eq!(1, reads.load(AtomicOrdering::SeqCst));
    assert_eq!(
        Some("YPtun-delta-3.5.4-3.5.5-universal-bbbbbbbbbbbbbbbb.patch.gz"),
        outdated.delta_asset.as_ref().map(|a| a.name.as_str())
    );
}

#[test]
fn portable_copy_never_gets_a_delta() {
    let github = FakeGithub::serving(RELEASES_URL, 200, STABLE_RELEASES);
    let reads = Arc::new(AtomicUsize::new(0));
    let counter = reads.clone();
    let windows = |github: &FakeGithub| {
        AppUpdateService::new(Box::new(github.clone()), "3.5.4")
            .with_platform(UpdatePlatform::new("windows", "amd64"))
    };

    // Desktop patches are found by the installed image's hash: the installer says "x64", the
    // patch "windows-amd64", so by name alone there is no match and the full installer is used.
    let by_name = windows(&github)
        .check(ReleaseChannel::Stable, None)
        .unwrap();
    assert_eq!("YPtun-3.5.5-x64-installer.exe", by_name.asset.name);
    assert_eq!(None, by_name.delta_asset);
    let installed = windows(&github)
        .with_installed_fingerprint(|| Some("d".repeat(64)))
        .check(ReleaseChannel::Stable, None)
        .unwrap();
    assert_eq!(
        Some("YPtun-delta-3.5.4-3.5.5-windows-amd64-dddddddddddddddd.patch"),
        installed.delta_asset.as_ref().map(|a| a.name.as_str())
    );

    let portable = windows(&github)
        .prefer_portable_asset(true)
        .with_installed_fingerprint(move || {
            counter.fetch_add(1, AtomicOrdering::SeqCst);
            None
        })
        .check(ReleaseChannel::Stable, None)
        .unwrap();
    assert_eq!("YPtun-3.5.5-x64-portable.exe", portable.asset.name);
    assert!(portable.is_update_available);
    assert_eq!(None, portable.delta_asset);
    assert_eq!(0, reads.load(AtomicOrdering::SeqCst));
}

#[test]
fn stable_without_a_published_release_fails() {
    for body in [
        "[]",
        r#"[{"tag_name": "v9.9.9", "html_url": "h", "draft": true}]"#,
    ] {
        let github = FakeGithub::serving(RELEASES_URL, 200, body);

        let error = android_service(&github, "3.5.4")
            .check(ReleaseChannel::Stable, None)
            .unwrap_err();

        assert_eq!(
            UpdateError::NoReleases {
                owner_repo: "yanisplugg/yptun".into()
            },
            error
        );
        assert_eq!(
            "No releases published for yanisplugg/yptun",
            error.to_string()
        );
    }
}

#[test]
fn failed_requests_report_why() {
    let not_found = FakeGithub::serving(NIGHTLY_URL, 404, r#"{"message": "Not Found"}"#);
    let error = android_service(&not_found, "3.5.4")
        .check(ReleaseChannel::Nightly, None)
        .unwrap_err();
    assert_eq!(UpdateError::Http(404), error);
    assert_eq!(
        "GitHub release request failed with HTTP 404",
        error.to_string()
    );

    let offline = FakeGithub::default();
    assert_eq!(
        Err(UpdateError::Transport),
        android_service(&offline, "3.5.4").check(ReleaseChannel::Stable, None)
    );

    let garbage = FakeGithub::serving(RELEASES_URL, 200, "<html>rate limited</html>");
    assert!(matches!(
        android_service(&garbage, "3.5.4").check(ReleaseChannel::Stable, None),
        Err(UpdateError::InvalidResponse(_))
    ));

    // tag_name and html_url are required, like in the Kotlin model.
    let incomplete = FakeGithub::serving(NIGHTLY_URL, 200, r#"{"tag_name": "nightly"}"#);
    assert!(matches!(
        android_service(&incomplete, "3.5.4").check(ReleaseChannel::Nightly, None),
        Err(UpdateError::InvalidResponse(_))
    ));
}

#[test]
fn missing_asset_error_names_what_was_expected() {
    let release = r#"{"tag_name": "v3.5.5", "html_url": "h", "assets": [{"name": "YPtun-v3.5.5-arm64-v8a.apk", "browser_download_url": "u"}]}"#;
    let github = FakeGithub::serving(NIGHTLY_URL, 200, release);
    let check = |platform: UpdatePlatform| {
        AppUpdateService::new(Box::new(github.clone()), "3.5.4")
            .with_platform(platform)
            .check(ReleaseChannel::Nightly, None)
            .unwrap_err()
            .to_string()
    };

    assert_eq!(
        "No windows + amd64 update asset in v3.5.5. Expected asset name containing windows, amd64 \
         and ending with one of: .msi, .exe, .zip",
        check(UpdatePlatform::new("windows", "amd64"))
    );
    assert_eq!(
        "No ios + arm64 update asset in v3.5.5. Expected asset name containing ios, arm64",
        check(UpdatePlatform::new("ios", "arm64"))
    );
}

#[test]
fn nightly_without_a_versioned_asset_is_always_an_update() {
    let release = r#"{"tag_name": "nightly", "html_url": "h", "assets": [{"name": "olcbox-nightly-android-arm64.apk", "browser_download_url": "u"}]}"#;
    let github = FakeGithub::serving(NIGHTLY_URL, 200, release);

    let info = android_service(&github, "99.0.0")
        .check(ReleaseChannel::Nightly, None)
        .unwrap();

    assert_eq!("nightly", info.version);
    assert!(info.is_update_available);
    assert_eq!(None, info.delta_asset);
    assert_eq!(NIGHTLY_URL, github.requests()[0].url);
}

#[test]
fn custom_mirror_and_user_agent_are_used() {
    let url = "https://api.github.com/repos/someone/fork/releases/tags/nightly";
    let github = FakeGithub::serving(url, 200, r#"{"tag_name": "nightly", "html_url": "h"}"#);
    let mirror = ReleaseMirror {
        name: "Fork".into(),
        repository_url: "https://github.com/someone/fork/".into(),
    };
    assert_eq!("someone/fork", mirror.owner_repo());
    assert_eq!("yanisplugg/yptun", ReleaseMirror::github().owner_repo());

    let release = AppUpdateService::new(Box::new(github.clone()), "1.0.0")
        .with_mirror(mirror)
        .with_user_agent("YPtun-iOS/1.0.0")
        .fetch_release(ReleaseChannel::Nightly, None)
        .unwrap();

    assert_eq!("nightly", release.tag_name);
    assert!(release.assets.is_empty());
    let requests = github.requests();
    assert_eq!(url, requests[0].url);
    assert_eq!(None, requests[0].proxy);
    assert!(requests[0]
        .headers
        .contains(&("User-Agent".to_string(), "YPtun-iOS/1.0.0".to_string())));
}

#[test]
fn version_comparison_details() {
    assert_eq!(Ordering::Equal, compare_versions("1.0", "1.0.0"));
    assert_eq!(Ordering::Greater, compare_versions("1.10.0", "1.9.9"));
    assert_eq!(Ordering::Less, compare_versions("2", "10"));
    assert_eq!(Ordering::Equal, compare_versions("1.0.0-beta", "1.0.0"));
    assert_eq!(Ordering::Greater, compare_versions("1.0.0-2", "1.0.0_1"));
    assert_eq!(Ordering::Equal, compare_versions("", "0"));
    assert!(!is_update_available(
        ReleaseChannel::Stable,
        "nightly",
        "1.0.0"
    ));
    // Only the release tag loses its "v"; the running version is compared as the app reports it.
    assert!(is_update_available(
        ReleaseChannel::Stable,
        "v3.5.5",
        "3.5.4"
    ));
    assert!(is_update_available(
        ReleaseChannel::Stable,
        "v1.0.0",
        "v9.0.0"
    ));
    assert!(is_update_available(
        ReleaseChannel::Stable,
        "3.5.10",
        "3.5.9"
    ));
}

#[test]
fn delta_needs_a_real_version_change_and_matching_names() {
    let assets = [asset("YPtun-delta-3.5.4-3.5.5-arm64-v8a.patch.gz", "u")];
    let pick = |from: &str, to: &str| {
        names(select_delta_asset(
            &assets,
            &UpdatePlatform::new("android", "arm64"),
            from,
            to,
            "YPtun-v3.5.5-arm64-v8a.apk",
            None,
        ))
    };

    assert_eq!(None, pick("3.5.5", "v3.5.5"));
    assert_eq!(None, pick(" ", "3.5.5"));
    assert_eq!(None, pick("3.5.3", "3.5.5"));
    assert_eq!(
        Some("YPtun-delta-3.5.4-3.5.5-arm64-v8a.patch.gz".into()),
        pick("v3.5.4", "3.5.5")
    );
    // A short "hash" is no hash: names decide.
    assert_eq!(
        Some("YPtun-delta-3.5.4-3.5.5-arm64-v8a.patch.gz".into()),
        names(select_delta_asset(
            &assets,
            &UpdatePlatform::new("android", "arm64"),
            "3.5.4",
            "3.5.5",
            "YPtun-v3.5.5-arm64-v8a.apk",
            Some("abc"),
        ))
    );
}

#[test]
fn delta_base_hash_token_reads_the_name_suffix() {
    assert_eq!(
        Some("0123456789abcdef".into()),
        delta_base_hash_token("YPtun-delta-3.5.4-3.5.5-arm64-v8a-0123456789ABCDEF.patch.gz")
    );
    assert_eq!(
        Some("d".repeat(16)),
        delta_base_hash_token(&format!(
            "YPtun-delta-3.5.4-3.5.5-windows-amd64-{}.patch",
            "d".repeat(16)
        ))
    );
    assert_eq!(
        None,
        delta_base_hash_token("YPtun-delta-3.5.4-3.5.5-arm64-v8a.patch.gz")
    );
    assert_eq!(
        None,
        delta_base_hash_token("YPtun-delta-3.5.4-3.5.5-0123456789abcde.patch")
    );
    assert_eq!(16, BASE_HASH_LENGTH);
}

#[test]
fn update_platform_spellings() {
    let android = UpdatePlatform::new("android", "arm64-v8a");
    assert_eq!(vec!["android".to_string()], android.asset_token());
    assert_eq!(vec!["arm64", "arm64-v8a"], android.android_arch_tokens());
    assert_eq!(&[".apk"], android.preferred_extensions());
    assert_eq!(
        vec!["armeabi-v7a", "armeabi"],
        UpdatePlatform::new("android", "ARMEABI").android_arch_tokens()
    );
    assert!(UpdatePlatform::new("android", "mips")
        .android_arch_tokens()
        .is_empty());

    let windows = UpdatePlatform::new("windows", "amd64");
    assert_eq!(vec!["windows", "amd64"], windows.asset_token());
    assert_eq!(vec!["amd64", "x64", "x86_64"], windows.arch_tokens());
    assert!(windows.android_arch_tokens().is_empty());
    assert_eq!(&[".msi", ".exe", ".zip"], windows.preferred_extensions());
    assert_eq!(
        vec!["arm64", "aarch64"],
        UpdatePlatform::new("macos", "arm64").arch_tokens()
    );
    assert_eq!(
        &[".deb", ".appimage"],
        UpdatePlatform::new("linux", "amd64").preferred_extensions()
    );
    assert!(UpdatePlatform::new("ios", "arm64")
        .preferred_extensions()
        .is_empty());

    let current = UpdatePlatform::current();
    assert_eq!(std::env::consts::OS, current.os);
    assert!(!["x86_64", "aarch64", ""].contains(&current.arch.as_str()));
}

#[test]
fn settings_json_matches_the_desktop_store() {
    let settings = AppUpdateSettings {
        channel: ReleaseChannel::Nightly,
        interval_hours: 6,
        last_check_at_epoch_ms: Some(1_747_130_400_000),
        last_seen_update_version: Some("Stable|3.5.5|p|a.apk|1|u".into()),
        last_downloaded_update_version: None,
    };

    let json = settings.to_json();

    assert_eq!(
        "{\n    \"update_channel\": \"Stable\",\n    \"update_interval_hours\": 6,\n    \
         \"last_update_check_at_epoch_ms\": 1747130400000,\n    \"last_seen_update_version\": \
         \"Stable|3.5.5|p|a.apk|1|u\",\n    \"last_downloaded_update_version\": null\n}",
        json
    );
    assert_eq!(settings.normalized(), AppUpdateSettings::from_json(&json));
    // What older builds wrote: partial, the stale Nightly channel, out-of-range intervals.
    assert_eq!(
        AppUpdateSettings {
            interval_hours: 24,
            ..Default::default()
        },
        AppUpdateSettings::from_json(
            r#"{"update_channel": "Nightly", "update_interval_hours": 100, "extra": 1}"#
        )
    );
    assert_eq!(
        AppUpdateSettings::NEVER_HOURS,
        AppUpdateSettings::from_json(r#"{"update_interval_hours": -5}"#).interval_hours
    );
    assert_eq!(
        AppUpdateSettings::default(),
        AppUpdateSettings::from_json("not json")
    );
    assert_eq!(
        AppUpdateSettings::default(),
        AppUpdateSettings::from_json(r#"{"update_channel": "Beta"}"#)
    );
}

#[test]
fn settings_constants_and_store_normalization() {
    assert_eq!([1, 6, 12, 24, 0], AppUpdateSettings::INTERVAL_PRESETS);
    assert_eq!(900_000, AppUpdateSettings::CHECK_TICK_MS);
    let defaults = AppUpdateSettings::default();
    assert_eq!(24, defaults.interval_hours);
    assert!(defaults.is_update_check_due(0));

    let store = InMemoryAppUpdateSettingsStore::new(AppUpdateSettings {
        channel: ReleaseChannel::Nightly,
        interval_hours: 48,
        ..Default::default()
    });
    assert_eq!(ReleaseChannel::Stable, store.value().channel);
    assert_eq!(24, store.value().interval_hours);
}

#[test]
fn identity_lists_every_build_detail() {
    let info = AppUpdateInfo {
        channel: ReleaseChannel::Stable,
        version: "3.5.5".into(),
        html_url: "h".into(),
        published_at: None,
        asset: AppUpdateAsset::new("YPtun-v3.5.5-arm64-v8a.apk", "u", None),
        is_update_available: true,
        delta_asset: None,
    };

    assert_eq!(
        "Stable|3.5.5||YPtun-v3.5.5-arm64-v8a.apk||",
        info.identity()
    );
    assert_eq!("u", info.download_asset().download_url);
    let stage = UpdateStage {
        kind: UpdateStageKind::DownloadPatch,
        step: 1,
        total: 3,
    };
    assert_eq!(UpdateStageKind::DownloadPatch, stage.kind);
}
