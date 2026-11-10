//! App self-update, ported from the Kotlin `AppUpdateService` / `AppUpdateSettings`: which GitHub
//! release asset fits this build, whether it is newer than the running version, the optional
//! binary-delta patch, and the persisted "when did we last check / what was already offered" state.
//!
//! The core decides; the platform downloads and installs. [`AppUpdateService::check`] makes one
//! blocking request through the platform's [`HttpFetcher`]: run it off the UI thread.

use std::cmp::Ordering;
use std::fmt;
use std::sync::{LazyLock, Mutex};

use regex_lite::Regex;
use serde::{Deserialize, Serialize};

use crate::repository::{HttpFetcher, HttpRequest};
use crate::util::is_blank;

/// Which releases the app follows. Settings always normalize to [`ReleaseChannel::Stable`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default, Serialize, Deserialize)]
pub enum ReleaseChannel {
    #[default]
    Stable,
    Nightly,
}

impl ReleaseChannel {
    /// The Kotlin enum name, also the serialized form.
    pub fn name(self) -> &'static str {
        match self {
            Self::Stable => "Stable",
            Self::Nightly => "Nightly",
        }
    }
}

/// Where releases are published.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReleaseMirror {
    pub name: String,
    pub repository_url: String,
}

impl ReleaseMirror {
    pub fn github() -> Self {
        Self {
            name: "GitHub".into(),
            repository_url: "https://github.com/yanisplugg/yptun".into(),
        }
    }

    /// `owner/repo` for the GitHub API.
    pub fn owner_repo(&self) -> String {
        let url = &self.repository_url;
        let url = url.strip_prefix("https://github.com/").unwrap_or(url);
        url.strip_suffix('/').unwrap_or(url).to_string()
    }
}

impl Default for ReleaseMirror {
    fn default() -> Self {
        Self::github()
    }
}

/// One downloadable release file.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct AppUpdateAsset {
    pub name: String,
    pub download_url: String,
    pub size_bytes: Option<i64>,
    pub updated_at: Option<String>,
    /// Lowercase hex SHA-256 of the asset as GitHub computed it on upload (the release API's
    /// `digest`). Installers check every download against it before running or applying anything.
    /// `None` when the release API did not report one.
    pub sha256: Option<String>,
}

impl AppUpdateAsset {
    pub fn new(
        name: impl Into<String>,
        download_url: impl Into<String>,
        size_bytes: Option<i64>,
    ) -> Self {
        Self {
            name: name.into(),
            download_url: download_url.into(),
            size_bytes,
            ..Self::default()
        }
    }
}

/// The result of a successful check.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AppUpdateInfo {
    pub channel: ReleaseChannel,
    pub version: String,
    pub html_url: String,
    pub published_at: Option<String>,
    pub asset: AppUpdateAsset,
    pub is_update_available: bool,
    /// Optional binary-delta asset that patches the CURRENT version up to [`Self::version`] for
    /// this build, so only a few MB are downloaded instead of the whole package. `None` when no
    /// matching patch is published (then [`Self::asset`] is downloaded in full). The reconstructed
    /// file is verified before install, so a missing or forged patch always degrades safely.
    pub delta_asset: Option<AppUpdateAsset>,
}

impl AppUpdateInfo {
    /// What will actually be downloaded first: the small patch when there is one, else the full file.
    pub fn download_asset(&self) -> &AppUpdateAsset {
        self.delta_asset.as_ref().unwrap_or(&self.asset)
    }

    /// Identifies one published build; what [`AppUpdateSettings`] remembers as seen/downloaded.
    pub fn identity(&self) -> String {
        [
            self.channel.name(),
            &self.version,
            self.published_at.as_deref().unwrap_or(""),
            &self.asset.name,
            &self
                .asset
                .size_bytes
                .map(|size| size.to_string())
                .unwrap_or_default(),
            self.asset.updated_at.as_deref().unwrap_or(""),
        ]
        .join("|")
    }

    pub fn is_downloaded(&self, settings: &AppUpdateSettings) -> bool {
        settings.last_downloaded_update_version.as_deref() == Some(self.identity().as_str())
    }

    /// Offer the update unless it was already downloaded or already offered ("Later").
    pub fn should_show_offer(&self, settings: &AppUpdateSettings) -> bool {
        self.is_update_available
            && !self.is_downloaded(settings)
            && settings.last_seen_update_version.as_deref() != Some(self.identity().as_str())
    }

    /// Like [`Self::should_show_offer`], but an update the user postponed is offered again once
    /// the next automatic check is due.
    pub fn should_show_offer_at(&self, settings: &AppUpdateSettings, now_epoch_ms: i64) -> bool {
        self.is_update_available
            && !self.is_downloaded(settings)
            && (settings.last_seen_update_version.as_deref() != Some(self.identity().as_str())
                || settings.is_update_check_due(now_epoch_ms))
    }
}

/// Why a check failed. The message is shown to the user as is.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum UpdateError {
    /// No HTTP response at all (offline, DNS, TLS, timeout, proxy down).
    Transport,
    /// GitHub answered with a non-2xx status.
    Http(u16),
    /// The release JSON did not decode.
    InvalidResponse(String),
    /// The stable channel found no published (non-draft) release.
    NoReleases { owner_repo: String },
    /// The release carries no asset this build can install.
    NoAsset {
        tag: String,
        tokens: Vec<String>,
        extensions: Vec<String>,
    },
}

impl fmt::Display for UpdateError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Transport => f.write_str("GitHub release request failed: no response"),
            Self::Http(code) => write!(f, "GitHub release request failed with HTTP {code}"),
            Self::InvalidResponse(message) => f.write_str(message),
            Self::NoReleases { owner_repo } => write!(f, "No releases published for {owner_repo}"),
            Self::NoAsset {
                tag,
                tokens,
                extensions,
            } => {
                write!(
                    f,
                    "No {} update asset in {tag}. Expected asset name containing {}",
                    tokens.join(" + "),
                    tokens.join(", ")
                )?;
                if !extensions.is_empty() {
                    write!(f, " and ending with one of: {}", extensions.join(", "))?;
                }
                Ok(())
            }
        }
    }
}

impl std::error::Error for UpdateError {}

/// Checks GitHub for a newer build of the running app.
pub struct AppUpdateService {
    fetcher: Box<dyn HttpFetcher>,
    mirror: ReleaseMirror,
    current_version: String,
    platform: UpdatePlatform,
    user_agent: String,
    installed_fingerprint: Option<Box<dyn Fn() -> Option<String> + Send + Sync>>,
    prefer_portable_asset: bool,
}

impl AppUpdateService {
    /// GitHub mirror, [`UpdatePlatform::current`], user agent `YPtun/<current_version>`.
    pub fn new(fetcher: Box<dyn HttpFetcher>, current_version: impl Into<String>) -> Self {
        let current_version = current_version.into();
        Self {
            fetcher,
            mirror: ReleaseMirror::github(),
            user_agent: format!("YPtun/{current_version}"),
            current_version,
            platform: UpdatePlatform::current(),
            installed_fingerprint: None,
            prefer_portable_asset: false,
        }
    }

    pub fn with_mirror(mut self, mirror: ReleaseMirror) -> Self {
        self.mirror = mirror;
        self
    }

    pub fn with_platform(mut self, platform: UpdatePlatform) -> Self {
        self.platform = platform;
        self
    }

    pub fn with_user_agent(mut self, user_agent: impl Into<String>) -> Self {
        self.user_agent = user_agent.into();
        self
    }

    /// SHA-256 (lowercase hex) of the file a delta patch would be applied to on THIS installation:
    /// the installed APK on Android, the app image on desktop. Returns `None` when it can't be read;
    /// patches are then matched by name alone.
    ///
    /// This is what tells "3.5.4 as published" apart from "3.5.4 as built locally", and an install
    /// off the universal APK from one off the arm64 one: same version, different bytes, and a
    /// patch generated against the other one can never apply. Only called once an update exists.
    pub fn with_installed_fingerprint(
        mut self,
        fingerprint: impl Fn() -> Option<String> + Send + Sync + 'static,
    ) -> Self {
        self.installed_fingerprint = Some(Box::new(fingerprint));
        self
    }

    /// Desktop portable build: download the single-file portable .exe (it replaces the file the
    /// user started), not the installer, and never a delta (a portable has no app image to patch).
    pub fn prefer_portable_asset(mut self, prefer: bool) -> Self {
        self.prefer_portable_asset = prefer;
        self
    }

    pub fn platform(&self) -> &UpdatePlatform {
        &self.platform
    }

    pub fn current_version(&self) -> &str {
        &self.current_version
    }

    /// Fetches the channel's release and picks this build's asset (and delta patch).
    /// `proxy`: `socks5://[user:pass@]host:port` when the request must go through the tunnel.
    pub fn check(
        &self,
        channel: ReleaseChannel,
        proxy: Option<&str>,
    ) -> Result<AppUpdateInfo, UpdateError> {
        let release = self.fetch_release(channel, proxy)?;
        let asset = select_asset(&release.assets, &self.platform, self.prefer_portable_asset)
            .ok_or_else(|| UpdateError::NoAsset {
                tag: release.tag_name.clone(),
                tokens: self.platform.asset_token(),
                extensions: self
                    .platform
                    .preferred_extensions()
                    .iter()
                    .map(|extension| extension.to_string())
                    .collect(),
            })?;
        let version = update_version(channel, &release.tag_name, &asset);
        let available = is_update_available(channel, &version, &self.current_version);
        let delta_asset = if available && !self.prefer_portable_asset {
            // Only hashed once an update actually exists, never on an up-to-date check.
            let base = self.installed_fingerprint.as_ref().and_then(|f| f());
            select_delta_asset(
                &release.assets,
                &self.platform,
                &self.current_version,
                &version,
                &asset.name,
                base.as_deref(),
            )
        } else {
            None
        };
        Ok(AppUpdateInfo {
            channel,
            version,
            html_url: release.html_url,
            published_at: release.published_at,
            asset,
            is_update_available: available,
            delta_asset,
        })
    }

    /// The release a check is based on: the newest non-draft one for [`ReleaseChannel::Stable`],
    /// the `nightly` tag for [`ReleaseChannel::Nightly`].
    pub fn fetch_release(
        &self,
        channel: ReleaseChannel,
        proxy: Option<&str>,
    ) -> Result<GithubRelease, UpdateError> {
        let base = format!("https://api.github.com/repos/{}", self.mirror.owner_repo());
        match channel {
            // The releases LIST (newest first), NOT /releases/latest: that one is a 404 when
            // every published release is flagged "pre-release".
            ReleaseChannel::Stable => {
                let body = self.get_body(&format!("{base}/releases?per_page=20"), proxy)?;
                let releases: Vec<GithubRelease> = decode(&body)?;
                releases
                    .into_iter()
                    .find(|release| !release.draft)
                    .ok_or_else(|| UpdateError::NoReleases {
                        owner_repo: self.mirror.owner_repo(),
                    })
            }
            ReleaseChannel::Nightly => {
                decode(&self.get_body(&format!("{base}/releases/tags/nightly"), proxy)?)
            }
        }
    }

    fn get_body(&self, url: &str, proxy: Option<&str>) -> Result<String, UpdateError> {
        // No HWID here: api.github.com has no use for it.
        let request = HttpRequest {
            url: url.to_string(),
            headers: vec![
                ("Accept".into(), "application/vnd.github+json".into()),
                ("User-Agent".into(), self.user_agent.clone()),
            ],
            proxy: proxy.map(str::to_string),
        };
        let response = self.fetcher.get(&request).ok_or(UpdateError::Transport)?;
        if !response.ok() {
            return Err(UpdateError::Http(response.status));
        }
        Ok(response.body)
    }
}

fn decode<T: serde::de::DeserializeOwned>(body: &str) -> Result<T, UpdateError> {
    serde_json::from_str(body).map_err(|e| UpdateError::InvalidResponse(e.to_string()))
}

/// The release asset this build installs: the device ABI's APK on Android, the installer for
/// this OS and arch on desktop (the portable .exe when `prefer_portable`).
pub fn select_asset(
    assets: &[GithubReleaseAsset],
    platform: &UpdatePlatform,
    prefer_portable: bool,
) -> Option<AppUpdateAsset> {
    let asset = if platform.os == "android" {
        select_android_asset(assets, platform)
    } else {
        select_desktop_asset(assets, platform, prefer_portable)
    };
    asset.map(GithubReleaseAsset::to_update_asset)
}

/// Finds the binary-delta patch that upgrades `from_version` to `to_version` for this build.
///
/// Convention: `YPtun-delta-<from>-<to>-<target>[-<base hash>].patch[.gz]`, where `<target>` is
/// the device ABI on Android (`arm64-v8a`, ...) and `<os>-<arch>` on desktop (`windows-amd64`,
/// ...). The target is taken from the already chosen `full_asset_name`, so the patch matches what
/// is installed. `base_sha256` is the installed file's hash ([`AppUpdateService::with_installed_fingerprint`]).
pub fn select_delta_asset(
    assets: &[GithubReleaseAsset],
    platform: &UpdatePlatform,
    from_version: &str,
    to_version: &str,
    full_asset_name: &str,
    base_sha256: Option<&str>,
) -> Option<AppUpdateAsset> {
    let from = from_version.strip_prefix('v').unwrap_or(from_version);
    let to = to_version.strip_prefix('v').unwrap_or(to_version);
    if is_blank(from) || is_blank(to) || from == to {
        return None;
    }
    let full_name = full_asset_name.to_lowercase();
    // Which patch belongs to the build the user actually has: the ABI on Android (plus
    // "universal": an install off the universal APK matches no ABI token), and "<os>-<arch>" (or
    // the bare arch spelling) on desktop.
    let targets: Vec<String> = if platform.os == "android" {
        let abi: Vec<String> = platform
            .android_arch_tokens()
            .into_iter()
            .filter(|token| full_name.contains(token))
            .map(str::to_string)
            .collect();
        if !abi.is_empty() {
            abi
        } else if full_name.contains("universal") {
            vec!["universal".to_string()]
        } else {
            vec![]
        }
    } else {
        platform
            .arch_tokens()
            .into_iter()
            .filter(|arch| full_name.contains(arch.as_str()))
            .flat_map(|arch| [format!("{}-{arch}", platform.os), arch])
            .collect()
    };
    let candidates: Vec<&GithubReleaseAsset> = assets
        .iter()
        .filter(|asset| {
            let name = asset.name.to_lowercase();
            (name.ends_with(".patch.gz") || name.ends_with(".patch"))
                && name.contains("delta")
                && name.contains(from)
                && name.contains(to)
        })
        .collect();
    // A patch name may end in "-<16 hex>": the SHA-256 prefix of the exact file it was generated
    // against. When the installed file is known, that beats every name heuristic. A tagged patch
    // whose base is NOT ours can only fail, so it is dropped instead of costing a wasted download.
    let local_base = base_sha256
        .map(str::to_lowercase)
        .filter(|base| base.chars().count() >= BASE_HASH_LENGTH);
    let eligible: Vec<&GithubReleaseAsset> = match &local_base {
        None => candidates,
        Some(base) => candidates
            .into_iter()
            .filter(|asset| {
                delta_base_hash_token(&asset.name)
                    .is_none_or(|declared| base.starts_with(&declared))
            })
            .collect(),
    };
    let exact_base_match = local_base.as_ref().and_then(|base| {
        eligible.iter().copied().find(|asset| {
            delta_base_hash_token(&asset.name).is_some_and(|declared| base.starts_with(&declared))
        })
    });
    let chosen = exact_base_match
        .or_else(|| {
            targets.iter().find_map(|target| {
                eligible
                    .iter()
                    .copied()
                    .find(|asset| asset.name.to_lowercase().contains(target.as_str()))
            })
        })
        .or(match eligible.as_slice() {
            [single] => Some(*single),
            _ => None,
        });
    chosen.map(GithubReleaseAsset::to_update_asset)
}

/// Length of the SHA-256 prefix a delta patch carries in its name: 16 hex characters = 64 bits.
/// Kept in sync with `scripts/patchgen/make-patch.ps1` and `make-desktop-patch.ps1`.
pub const BASE_HASH_LENGTH: usize = 16;

static DELTA_BASE_HASH: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"-([0-9a-f]{16})\.patch(\.gz)?$").unwrap());

static VERSION_TOKEN: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?:^|[-_])v?(\d+\.\d+\.\d+)(?:[-_.]|$)").unwrap());

/// The base-file SHA-256 prefix a patch declares in its name, or `None` for an older patch.
pub fn delta_base_hash_token(name: &str) -> Option<String> {
    DELTA_BASE_HASH
        .captures(&name.to_lowercase())
        .map(|captures| captures[1].to_string())
}

fn select_android_asset<'a>(
    assets: &'a [GithubReleaseAsset],
    platform: &UpdatePlatform,
) -> Option<&'a GithubReleaseAsset> {
    // Match on the ABI token alone: release assets are named like "YPtun-v2.1.0-arm64-v8a.apk"
    // and do NOT carry an "android" token.
    let preferred = platform.preferred_extensions();
    let apks: Vec<&GithubReleaseAsset> = assets
        .iter()
        .filter(|asset| asset.name.to_lowercase().ends_with(".apk"))
        .collect();
    let exact_abi = platform
        .android_arch_tokens()
        .into_iter()
        .find_map(|token| {
            let matching: Vec<&GithubReleaseAsset> = apks
                .iter()
                .copied()
                .filter(|asset| asset.name.to_lowercase().contains(token))
                .collect();
            select_preferred_asset(&matching, preferred)
        });
    if exact_abi.is_some() {
        return exact_abi;
    }
    // No ABI-specific APK for this device: fall back to a universal one (no known ABI token in its
    // name, e.g. "YPtun-v2.1.0-universal.apk").
    let universal: Vec<&GithubReleaseAsset> = apks
        .into_iter()
        .filter(|asset| {
            let name = asset.name.to_lowercase();
            !KNOWN_ANDROID_ARCH_TOKENS
                .iter()
                .any(|token| name.contains(token))
        })
        .collect();
    select_preferred_asset(&universal, preferred)
}

/// Desktop asset for this OS and arch. Real files are named like "YPtun-3.3.2-x64-installer.exe"
/// (no "windows", "x64" rather than "amd64"), so: match any spelling of the arch, refuse assets
/// that name a DIFFERENT os, and prefer one carrying our os token when the release has both.
fn select_desktop_asset<'a>(
    assets: &'a [GithubReleaseAsset],
    platform: &UpdatePlatform,
    prefer_portable: bool,
) -> Option<&'a GithubReleaseAsset> {
    let extensions = platform.preferred_extensions();
    let arch_tokens = platform.arch_tokens();
    let foreign_os_tokens: Vec<&str> = KNOWN_DESKTOP_OS_TOKENS
        .iter()
        .copied()
        .filter(|os| *os != platform.os)
        .collect();
    let candidates: Vec<&GithubReleaseAsset> = assets
        .iter()
        .filter(|asset| {
            let name = asset.name.to_lowercase();
            extensions.iter().any(|extension| name.ends_with(extension))
                && arch_tokens.iter().any(|arch| name.contains(arch.as_str()))
                && !foreign_os_tokens.iter().any(|os| name.contains(os))
        })
        .collect();
    // A release carries both the installer and the single-file portable .exe, both ending in
    // ".exe". The installer is the default; the portable only when the running copy IS one.
    let (portable, regular): (Vec<&GithubReleaseAsset>, Vec<&GithubReleaseAsset>) = candidates
        .into_iter()
        .partition(|asset| asset.name.to_lowercase().contains("portable"));
    let pool = if prefer_portable {
        let portable_exe: Vec<&GithubReleaseAsset> = portable
            .iter()
            .copied()
            .filter(|asset| asset.name.to_lowercase().ends_with(".exe"))
            .collect();
        if !portable_exe.is_empty() {
            portable_exe
        } else if !regular.is_empty() {
            regular
        } else {
            portable
        }
    } else if !regular.is_empty() {
        regular
    } else {
        portable
    };
    let named: Vec<&GithubReleaseAsset> = pool
        .iter()
        .copied()
        .filter(|asset| asset.name.to_lowercase().contains(platform.os.as_str()))
        .collect();
    select_preferred_asset(if named.is_empty() { &pool } else { &named }, extensions)
}

const KNOWN_DESKTOP_OS_TOKENS: [&str; 3] = ["windows", "linux", "macos"];

const KNOWN_ANDROID_ARCH_TOKENS: [&str; 7] = [
    "armeabi-v7a",
    "armeabi",
    "arm64-v8a",
    "arm64",
    "x86_64",
    "amd64",
    "x86",
];

fn select_preferred_asset<'a>(
    candidates: &[&'a GithubReleaseAsset],
    preferred_extensions: &[&str],
) -> Option<&'a GithubReleaseAsset> {
    preferred_extensions
        .iter()
        .find_map(|extension| {
            candidates
                .iter()
                .copied()
                .find(|asset| asset.name.to_lowercase().ends_with(extension))
        })
        .or_else(|| candidates.first().copied())
}

/// Whether `release_tag` is newer than the running version (`nightly` always is, on Nightly).
pub fn is_update_available(
    channel: ReleaseChannel,
    release_tag: &str,
    current_version: &str,
) -> bool {
    let release = release_tag.strip_prefix('v').unwrap_or(release_tag);
    if channel == ReleaseChannel::Nightly && release == "nightly" {
        return true;
    }
    compare_versions(release, current_version) == Ordering::Greater
}

/// Numeric comparison of `.`/`-`/`_`-separated parts; a non-numeric part counts as 0 and missing
/// trailing parts as 0 (`1.0` == `1.0.0`).
pub fn compare_versions(left: &str, right: &str) -> Ordering {
    let parts = |version: &str| -> Vec<i32> {
        version
            .split(['.', '-', '_'])
            .map(|part| part.parse().unwrap_or(0))
            .collect()
    };
    let (left, right) = (parts(left), parts(right));
    (0..left.len().max(right.len()))
        .map(|i| {
            let l = left.get(i).copied().unwrap_or(0);
            let r = right.get(i).copied().unwrap_or(0);
            l.cmp(&r)
        })
        .find(|ordering| ordering.is_ne())
        .unwrap_or(Ordering::Equal)
}

fn update_version(channel: ReleaseChannel, release_tag: &str, asset: &AppUpdateAsset) -> String {
    let tag = release_tag.strip_prefix('v').unwrap_or(release_tag);
    match channel {
        ReleaseChannel::Stable => tag.to_string(),
        // The nightly tag never changes; the asset name carries the build's version.
        ReleaseChannel::Nightly => version_token(&asset.name).unwrap_or_else(|| tag.to_string()),
    }
}

fn version_token(name: &str) -> Option<String> {
    VERSION_TOKEN
        .captures(name)
        .map(|captures| captures[1].to_string())
}

/// The OS and arch release assets are matched against.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct UpdatePlatform {
    /// `android`, `windows`, `macos`, `linux`, `ios`.
    pub os: String,
    /// `arm64`, `amd64`, `armeabi-v7a`, `x86`, ...
    pub arch: String,
}

impl UpdatePlatform {
    pub fn new(os: impl Into<String>, arch: impl Into<String>) -> Self {
        Self {
            os: os.into(),
            arch: arch.into(),
        }
    }

    /// The platform this library was compiled for, spelled the way the Kotlin apps reported it.
    pub fn current() -> Self {
        let os = std::env::consts::OS;
        let arch = match std::env::consts::ARCH {
            "x86_64" => "amd64",
            "aarch64" => "arm64",
            "arm" if os == "android" => "armeabi-v7a",
            other => other,
        };
        Self::new(os, arch)
    }

    /// What the "no asset" error says was looked for.
    pub fn asset_token(&self) -> Vec<String> {
        if self.os == "android" {
            vec!["android".to_string()]
        } else {
            vec![self.os.clone(), self.arch.clone()]
        }
    }

    /// Spellings of the arch a release asset may use: the same build ships as x64 and as amd64.
    pub fn arch_tokens(&self) -> Vec<String> {
        match self.arch.as_str() {
            "amd64" => vec!["amd64".into(), "x64".into(), "x86_64".into()],
            "arm64" => vec!["arm64".into(), "aarch64".into()],
            _ => vec![self.arch.clone()],
        }
    }

    /// ABI spellings in Android APK names; empty off Android.
    pub fn android_arch_tokens(&self) -> Vec<&'static str> {
        if self.os != "android" {
            return vec![];
        }
        match self.arch.to_lowercase().as_str() {
            "armeabi-v7a" | "armeabi" => vec!["armeabi-v7a", "armeabi"],
            "arm64" | "arm64-v8a" => vec!["arm64", "arm64-v8a"],
            "amd64" | "x86_64" => vec!["amd64", "x86_64"],
            "x86" => vec!["x86"],
            _ => vec![],
        }
    }

    /// Package extensions in order of preference (.deb is the shipped Linux package, AppImage a fallback).
    pub fn preferred_extensions(&self) -> &'static [&'static str] {
        match self.os.as_str() {
            "windows" => &[".msi", ".exe", ".zip"],
            "macos" => &[".dmg"],
            "linux" => &[".deb", ".appimage"],
            "android" => &[".apk"],
            _ => &[],
        }
    }
}

/// A release as the GitHub API returns it (unknown fields ignored).
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
pub struct GithubRelease {
    pub tag_name: String,
    pub html_url: String,
    #[serde(default)]
    pub published_at: Option<String>,
    #[serde(default)]
    pub draft: bool,
    #[serde(default)]
    pub prerelease: bool,
    #[serde(default)]
    pub assets: Vec<GithubReleaseAsset>,
}

/// One release file as the GitHub API returns it.
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
pub struct GithubReleaseAsset {
    pub name: String,
    pub browser_download_url: String,
    #[serde(default)]
    pub size: Option<i64>,
    #[serde(default)]
    pub updated_at: Option<String>,
    /// `sha256:<hex>`: GitHub's own checksum of the uploaded file.
    #[serde(default)]
    pub digest: Option<String>,
}

impl GithubReleaseAsset {
    pub fn new(name: impl Into<String>, browser_download_url: impl Into<String>) -> Self {
        Self {
            name: name.into(),
            browser_download_url: browser_download_url.into(),
            ..Self::default()
        }
    }

    pub fn to_update_asset(&self) -> AppUpdateAsset {
        AppUpdateAsset {
            name: self.name.clone(),
            download_url: self.browser_download_url.clone(),
            size_bytes: self.size,
            updated_at: self.updated_at.clone(),
            sha256: self.digest.as_deref().and_then(|digest| {
                let hex = digest
                    .strip_prefix("sha256:")
                    .unwrap_or(digest)
                    .trim()
                    .to_lowercase();
                (hex.len() == 64 && hex.chars().all(|c| matches!(c, '0'..='9' | 'a'..='f')))
                    .then_some(hex)
            }),
        }
    }
}

/// Persisted update state. The serialized names are the ones every Kotlin store used.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct AppUpdateSettings {
    #[serde(rename = "update_channel")]
    pub channel: ReleaseChannel,
    /// Hours between automatic checks; [`AppUpdateSettings::NEVER_HOURS`] disables them.
    #[serde(rename = "update_interval_hours")]
    pub interval_hours: i32,
    #[serde(rename = "last_update_check_at_epoch_ms")]
    pub last_check_at_epoch_ms: Option<i64>,
    /// [`AppUpdateInfo::identity`] of the last offered update ("Later" suppresses it).
    #[serde(rename = "last_seen_update_version")]
    pub last_seen_update_version: Option<String>,
    /// [`AppUpdateInfo::identity`] of the last downloaded update.
    #[serde(rename = "last_downloaded_update_version")]
    pub last_downloaded_update_version: Option<String>,
}

impl Default for AppUpdateSettings {
    fn default() -> Self {
        Self {
            channel: ReleaseChannel::Stable,
            interval_hours: Self::DEFAULT_INTERVAL_HOURS,
            last_check_at_epoch_ms: None,
            last_seen_update_version: None,
            last_downloaded_update_version: None,
        }
    }
}

impl AppUpdateSettings {
    pub const MIN_INTERVAL_HOURS: i32 = 1;
    pub const MAX_INTERVAL_HOURS: i32 = 24;
    pub const DEFAULT_INTERVAL_HOURS: i32 = 24;
    /// [`Self::interval_hours`] value meaning "never check automatically".
    pub const NEVER_HOURS: i32 = 0;
    pub const INTERVAL_PRESETS: [i32; 5] = [1, 6, 12, 24, Self::NEVER_HOURS];
    /// How often a running app asks "is a check due yet?": well under the shortest interval.
    pub const CHECK_TICK_MS: i64 = 15 * 60 * 1_000;

    /// Always the Stable channel (the release repo has no "nightly" tag; this also migrates
    /// settings persisted with the old Nightly default) and an interval within the presets' range.
    pub fn normalized(&self) -> Self {
        Self {
            channel: ReleaseChannel::Stable,
            interval_hours: if self.interval_hours <= 0 {
                Self::NEVER_HOURS
            } else {
                self.interval_hours
                    .clamp(Self::MIN_INTERVAL_HOURS, Self::MAX_INTERVAL_HOURS)
            },
            ..self.clone()
        }
    }

    /// "Never": no automatic checks at all (only the manual button).
    pub fn auto_check_disabled(&self) -> bool {
        self.interval_hours <= 0
    }

    pub fn is_update_check_due(&self, now_epoch_ms: i64) -> bool {
        if self.auto_check_disabled() {
            return false;
        }
        let Some(last_check) = self.last_check_at_epoch_ms else {
            return true;
        };
        let interval_ms = i64::from(
            self.interval_hours
                .clamp(Self::MIN_INTERVAL_HOURS, Self::MAX_INTERVAL_HOURS),
        ) * 60
            * 60
            * 1_000;
        now_epoch_ms.wrapping_sub(last_check) >= interval_ms
    }

    /// Reads what [`Self::to_json`] wrote (the desktop store's `update_settings.json`); unreadable
    /// input gives the defaults.
    pub fn from_json(json: &str) -> Self {
        serde_json::from_str::<Self>(json)
            .map(|settings| settings.normalized())
            .unwrap_or_default()
    }

    /// Normalized, every field present, pretty-printed with 4-space indents like the Kotlin store.
    pub fn to_json(&self) -> String {
        let mut out = Vec::new();
        let formatter = serde_json::ser::PrettyFormatter::with_indent(b"    ");
        let mut serializer = serde_json::Serializer::with_formatter(&mut out, formatter);
        self.normalized()
            .serialize(&mut serializer)
            .expect("settings serialize");
        String::from_utf8(out).expect("serde_json writes UTF-8")
    }
}

/// Where the platform keeps [`AppUpdateSettings`].
pub trait AppUpdateSettingsStore: Send + Sync {
    fn load(&self) -> AppUpdateSettings;
    fn save(&self, settings: &AppUpdateSettings);
}

/// Normalizes on save, like every real store.
#[derive(Debug, Default)]
pub struct InMemoryAppUpdateSettingsStore {
    value: Mutex<AppUpdateSettings>,
}

impl InMemoryAppUpdateSettingsStore {
    pub fn new(initial: AppUpdateSettings) -> Self {
        Self {
            value: Mutex::new(initial.normalized()),
        }
    }

    pub fn value(&self) -> AppUpdateSettings {
        self.value
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone()
    }
}

impl AppUpdateSettingsStore for InMemoryAppUpdateSettingsStore {
    fn load(&self) -> AppUpdateSettings {
        self.value()
    }

    fn save(&self, settings: &AppUpdateSettings) {
        *self
            .value
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner()) = settings.normalized();
    }
}

/// Which step of an update is running; the UI turns it into "Step N of M: ...".
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum UpdateStageKind {
    DownloadPatch,
    DownloadInstaller,
    ApplyPatch,
    Install,
    Restart,
}

/// `step` of `total`; progress within the step goes through a separate progress callback.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct UpdateStage {
    pub kind: UpdateStageKind,
    pub step: i32,
    pub total: i32,
}
