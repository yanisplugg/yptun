//! Geo and ASN databases (port of GeoAssetManager, JvmGeoAssets, IosGeoAssets, AsnResolver,
//! JvmAsnResolver, GeoUpdateStatus).
//!
//! * Files: xray-core's geoip.dat / geosite.dat (v2ray format) for geoip:/geosite: selectors, plus a
//!   per-ASN prefix cache that turns asn:N selectors into plain CIDRs at config-build time.
//! * Where: Android <filesDir>/xray-assets/, desktop <appData>/geo/, iOS <App Group>/geo/;
//!   ASN cache <base>/asn-cache/asn-<n>.txt.
//! * When: [ensure_assets] on the connect path downloads only what is missing; [download] is the
//!   manual "update now" (always refetches; on Android a bad download never replaces a good file).
//!
//! The core does no networking itself: the platform supplies an [HttpGet] that performs one GET and
//! does not follow redirects. Redirects are followed here with the same rules as the Kotlin code
//! (an iOS client may follow them on its own, like NSData does — that is fine too).

use std::collections::{BTreeMap, HashMap};
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use crate::routing;

pub const GEOIP_FILE: &str = "geoip.dat";
pub const GEOSITE_FILE: &str = "geosite.dat";
pub const MAX_REDIRECTS: u32 = 5;
pub const USER_AGENT: &str = "olcbox-vpn";

/// Defaults used by the desktop and iOS apps (Android uses the routing-profile defaults).
pub const LOYALSOLDIER_GEOIP_URL: &str =
    "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat";
pub const LOYALSOLDIER_GEOSITE_URL: &str =
    "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat";

pub const ASN_CACHE_DIR: &str = "asn-cache";
/// ipverse aggregated per-ASN prefix lists: <base>/as/<n>/ipv4-aggregated.txt (+ ipv6).
pub const ASN_DEFAULT_BASE: &str = "https://raw.githubusercontent.com/ipverse/asn-ip/master";
/// Don't let a pathological ASN file blow up memory / the config.
pub const MAX_CIDRS_PER_ASN: usize = 20_000;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum GeoPlatform {
    Android,
    Desktop,
    Ios,
}

/// How a redirect chain is followed (the Kotlin implementations differ slightly).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RedirectStyle {
    /// GeoAssetManager / AsnResolver: only 301/302/303/307/308, relative Location resolved,
    /// success only on 200.
    Android,
    /// JvmGeoAssets: any 3xx, Location used as is, success on any 2xx.
    DesktopGeo,
    /// JvmAsnResolver: any 3xx, relative Location resolved, success only on 200.
    DesktopAsn,
    /// IosGeoAssets (NSData): any 3xx followed, any final status accepted.
    Ios,
}

impl GeoPlatform {
    /// Folder name under the platform's private storage.
    pub fn asset_dir_name(self) -> &'static str {
        match self {
            GeoPlatform::Android => "xray-assets",
            GeoPlatform::Desktop | GeoPlatform::Ios => "geo",
        }
    }

    /// Minimum plausible size of a real .dat (guards against an HTML error page being saved).
    pub fn min_dat_bytes(self) -> u64 {
        match self {
            GeoPlatform::Android => 50_000,
            GeoPlatform::Desktop | GeoPlatform::Ios => 64 * 1024,
        }
    }

    /// (connect, read) timeouts in ms; iOS uses the system defaults.
    pub fn timeouts_ms(self) -> Option<(u32, u32)> {
        match self {
            GeoPlatform::Android => Some((20_000, 60_000)),
            GeoPlatform::Desktop => Some((15_000, 60_000)),
            GeoPlatform::Ios => None,
        }
    }

    pub fn default_geoip_url(self) -> &'static str {
        match self {
            GeoPlatform::Android => routing::DEFAULT_GEOIP_URL,
            GeoPlatform::Desktop | GeoPlatform::Ios => LOYALSOLDIER_GEOIP_URL,
        }
    }

    pub fn default_geosite_url(self) -> &'static str {
        match self {
            GeoPlatform::Android => routing::DEFAULT_GEOSITE_URL,
            GeoPlatform::Desktop | GeoPlatform::Ios => LOYALSOLDIER_GEOSITE_URL,
        }
    }

    /// Blank URLs fall back to the platform defaults (Kotlin ifBlank).
    pub fn resolve_urls(self, geoip_url: &str, geosite_url: &str) -> (String, String) {
        let pick = |u: &str, d: &str| if u.trim().is_empty() { d.to_string() } else { u.to_string() };
        (pick(geoip_url, self.default_geoip_url()), pick(geosite_url, self.default_geosite_url()))
    }

    pub fn geo_headers(self) -> &'static [(&'static str, &'static str)] {
        match self {
            GeoPlatform::Android => &[("User-Agent", USER_AGENT), ("Accept", "application/octet-stream")],
            GeoPlatform::Desktop | GeoPlatform::Ios => &[],
        }
    }

    pub fn geo_redirect_style(self) -> RedirectStyle {
        match self {
            GeoPlatform::Android => RedirectStyle::Android,
            GeoPlatform::Desktop => RedirectStyle::DesktopGeo,
            GeoPlatform::Ios => RedirectStyle::Ios,
        }
    }

    /// iOS has no Kotlin ASN resolver; it shares the desktop rules.
    pub fn asn_redirect_style(self) -> RedirectStyle {
        match self {
            GeoPlatform::Android => RedirectStyle::Android,
            GeoPlatform::Desktop | GeoPlatform::Ios => RedirectStyle::DesktopAsn,
        }
    }
}

/// Transient state of an on-demand geo-database download.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum GeoUpdateStatus {
    Running,
    /// Refreshed at timestamp_ms; bytes total fetched.
    Success { timestamp_ms: i64, bytes: u64 },
    Failed { message: String },
}

/// Result of a (re)download attempt; bytes is the total fetched on success.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DownloadResult {
    pub success: bool,
    pub bytes: u64,
    pub error: Option<String>,
}

// ---------------- HTTP ----------------

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HttpResponse {
    pub status: u16,
    /// The Location header, if any.
    pub location: Option<String>,
    pub body: Vec<u8>,
}

/// One GET request, no redirect following. Implemented by each platform app.
pub trait HttpGet {
    fn get(&mut self, url: &str, headers: &[(&str, &str)]) -> Result<HttpResponse, String>;
}

/// GET url following redirects by the given rules; returns the final body.
pub fn fetch_following(
    http: &mut dyn HttpGet,
    url: &str,
    headers: &[(&str, &str)],
    style: RedirectStyle,
) -> Result<Vec<u8>, String> {
    let mut current = url.to_string();
    let mut redirects: u32 = 0;
    loop {
        let resp = http.get(&current, headers)?;
        let code = resp.status;
        let is_redirect = match style {
            RedirectStyle::Android => matches!(code, 301 | 302 | 303 | 307 | 308),
            _ => (300..=399).contains(&code),
        };
        if is_redirect {
            let location = resp.location.unwrap_or_default();
            if style == RedirectStyle::DesktopGeo {
                let ok = !location.trim().is_empty() && redirects < MAX_REDIRECTS;
                redirects += 1;
                if !ok {
                    return Err(format!("bad redirect from {current}"));
                }
                current = location;
            } else {
                redirects += 1;
                if location.trim().is_empty() || redirects > MAX_REDIRECTS {
                    return Err(format!("too many redirects for {url}"));
                }
                current = resolve_url(&current, &location);
            }
            continue;
        }
        let ok = match style {
            RedirectStyle::DesktopGeo => (200..=299).contains(&code),
            RedirectStyle::Ios => true,
            _ => code == 200,
        };
        if !ok {
            return Err(format!("HTTP {code} for {current}"));
        }
        return Ok(resp.body);
    }
}

/// Resolves a Location header against the current URL (like java.net.URL(URL, String)).
pub fn resolve_url(base: &str, location: &str) -> String {
    let loc = location.trim();
    if let Some(i) = loc.find("://") {
        let scheme = &loc[..i];
        if !scheme.is_empty() && scheme.chars().all(|c: char| c.is_ascii_alphanumeric() || c == '+' || c == '-' || c == '.') {
            return loc.to_string();
        }
    }
    let (scheme, rest) = match base.find("://") {
        Some(i) => (&base[..i], &base[i + 3..]),
        None => return loc.to_string(),
    };
    if let Some(r) = loc.strip_prefix("//") {
        return format!("{scheme}://{r}");
    }
    let auth_end = rest.find(|c: char| c == '/' || c == '?' || c == '#').unwrap_or(rest.len());
    let authority = &rest[..auth_end];
    let path_q = &rest[auth_end..];
    let path_end = path_q.find(|c: char| c == '?' || c == '#').unwrap_or(path_q.len());
    let path = &path_q[..path_end];
    if loc.starts_with('?') || loc.starts_with('#') {
        let p = if path.is_empty() { "/" } else { path };
        return format!("{scheme}://{authority}{p}{loc}");
    }
    let joined = if loc.starts_with('/') {
        loc.to_string()
    } else {
        let dir = match path.rfind('/') {
            Some(i) => &path[..=i],
            None => "/",
        };
        format!("{dir}{loc}")
    };
    let tail_at = joined.find(|c: char| c == '?' || c == '#').unwrap_or(joined.len());
    let (p, tail) = joined.split_at(tail_at);
    format!("{scheme}://{authority}{}{tail}", remove_dot_segments(p))
}

fn remove_dot_segments(path: &str) -> String {
    let segs: Vec<&str> = path.split('/').collect();
    let mut out: Vec<&str> = Vec::new();
    for (i, s) in segs.iter().enumerate().skip(1) {
        let last = i == segs.len() - 1;
        match *s {
            "." => {
                if last {
                    out.push("");
                }
            }
            ".." => {
                out.pop();
                if last {
                    out.push("");
                }
            }
            _ => out.push(s),
        }
    }
    format!("/{}", out.join("/"))
}

// ---------------- geoip.dat / geosite.dat ----------------

/// <base>/<platform dir>, created if missing.
pub fn asset_dir(base: &Path, platform: GeoPlatform) -> io::Result<PathBuf> {
    let dir = base.join(platform.asset_dir_name());
    fs::create_dir_all(&dir)?;
    Ok(dir)
}

fn file_size(path: &Path) -> u64 {
    fs::metadata(path).map(|m| if m.is_file() { m.len() } else { 0 }).unwrap_or(0)
}

/// True when both databases are present and non-trivial in size.
pub fn has_assets(dir: &Path, platform: GeoPlatform) -> bool {
    let min = platform.min_dat_bytes();
    file_size(&dir.join(GEOIP_FILE)) >= min && file_size(&dir.join(GEOSITE_FILE)) >= min
}

/// Writes body to target through a temp file in the same folder, then renames it into place.
/// Android also rejects bodies smaller than a real .dat (the old file stays untouched).
pub fn install_dat(target: &Path, body: &[u8], platform: GeoPlatform) -> Result<u64, String> {
    let total = body.len() as u64;
    let dir = target.parent().ok_or_else(|| "no parent dir".to_string())?;
    let name = target.file_name().and_then(|n| n.to_str()).unwrap_or("asset");
    let err = |e: io::Error| e.to_string();
    match platform {
        GeoPlatform::Android => {
            let tmp = dir.join(format!("{name}.tmp"));
            fs::write(&tmp, body).map_err(err)?;
            if total < platform.min_dat_bytes() {
                let _ = fs::remove_file(&tmp);
                return Err(format!("downloaded file too small ({total} bytes) — not a .dat?"));
            }
            if target.exists() {
                let _ = fs::remove_file(target);
            }
            if fs::rename(&tmp, target).is_err() {
                fs::copy(&tmp, target).map_err(err)?;
                let _ = fs::remove_file(&tmp);
            }
        }
        GeoPlatform::Desktop | GeoPlatform::Ios => {
            let tmp = dir.join(format!("{name}.part"));
            fs::write(&tmp, body).map_err(err)?;
            if let Err(e) = fs::rename(&tmp, target) {
                let _ = fs::remove_file(&tmp);
                return Err(e.to_string());
            }
        }
    }
    Ok(total)
}

fn download_to(http: &mut dyn HttpGet, platform: GeoPlatform, url: &str, target: &Path) -> Result<u64, String> {
    let body = fetch_following(http, url, platform.geo_headers(), platform.geo_redirect_style())?;
    install_dat(target, &body, platform)
}

/// Downloads both databases (blank URLs → defaults). On failure the stored files stay as they were.
pub fn download(
    http: &mut dyn HttpGet,
    platform: GeoPlatform,
    dir: &Path,
    geoip_url: &str,
    geosite_url: &str,
) -> DownloadResult {
    let _ = fs::create_dir_all(dir);
    let (ip, site) = platform.resolve_urls(geoip_url, geosite_url);
    let run = |http: &mut dyn HttpGet| -> Result<u64, String> {
        let a = download_to(http, platform, &ip, &dir.join(GEOIP_FILE))?;
        let b = download_to(http, platform, &site, &dir.join(GEOSITE_FILE))?;
        Ok(a + b)
    };
    match run(http) {
        Ok(bytes) => DownloadResult { success: true, bytes, error: None },
        Err(e) => DownloadResult { success: false, bytes: 0, error: Some(e) },
    }
}

/// Connect path: true if both databases are present afterwards, downloading only when missing.
pub fn ensure_assets(
    http: &mut dyn HttpGet,
    platform: GeoPlatform,
    dir: &Path,
    geoip_url: &str,
    geosite_url: &str,
) -> bool {
    if has_assets(dir, platform) {
        return true;
    }
    let _ = fs::create_dir_all(dir);
    match platform {
        GeoPlatform::Android => download(http, platform, dir, geoip_url, geosite_url).success,
        GeoPlatform::Desktop => {
            let (ip, site) = platform.resolve_urls(geoip_url, geosite_url);
            download_to(http, platform, &ip, &dir.join(GEOIP_FILE))
                .and_then(|_| download_to(http, platform, &site, &dir.join(GEOSITE_FILE)))
                .map(|_| has_assets(dir, platform))
                .unwrap_or(false)
        }
        GeoPlatform::Ios => {
            let (ip, site) = platform.resolve_urls(geoip_url, geosite_url);
            for (url, file) in [(ip, GEOIP_FILE), (site, GEOSITE_FILE)] {
                let target = dir.join(file);
                if file_size(&target) >= platform.min_dat_bytes() {
                    continue;
                }
                let _ = download_to(http, platform, &url, &target);
            }
            has_assets(dir, platform)
        }
    }
}

// ---------------- ASN → CIDR ----------------

/// Keeps only plausible CIDR lines (skips blanks and # comments in the source files).
pub fn clean_cidr(line: &str) -> Option<String> {
    let t = line.trim();
    if t.is_empty() || t.starts_with('#') {
        return None;
    }
    (t.contains('/') && (t.contains('.') || t.contains(':'))).then(|| t.to_string())
}

/// asn:13335 / AS13335 / 13335 → 13335 (digits only, like Kotlin filter(Char::isDigit);
/// non-ASCII digits are dropped since they can't appear in the dataset URL anyway).
pub fn asn_digits(raw: &str) -> String {
    raw.trim().chars().filter(|c| c.is_ascii_digit()).collect()
}

/// Blank → default, trailing slashes removed.
pub fn asn_base(base_url: &str) -> String {
    let b = if base_url.trim().is_empty() { ASN_DEFAULT_BASE } else { base_url };
    b.trim_end_matches('/').to_string()
}

pub fn asn_urls(base: &str, n: &str) -> [String; 2] {
    [format!("{base}/as/{n}/ipv4-aggregated.txt"), format!("{base}/as/{n}/ipv6-aggregated.txt")]
}

pub fn asn_cache_file_name(n: &str) -> String {
    format!("asn-{n}.txt")
}

/// Resolves ASNs to CIDRs: memory → disk cache → network. Keep one instance per app process.
/// Every failure degrades gracefully: an unresolvable ASN is simply absent from the result.
pub struct AsnResolver {
    cache_dir: PathBuf,
    platform: GeoPlatform,
    memory: Mutex<HashMap<String, Vec<String>>>,
}

impl AsnResolver {
    /// cache_dir is the full asn-cache folder (e.g. <filesDir>/asn-cache).
    pub fn new(cache_dir: PathBuf, platform: GeoPlatform) -> Self {
        AsnResolver { cache_dir, platform, memory: Mutex::new(HashMap::new()) }
    }

    pub fn cache_dir(&self) -> &Path {
        &self.cache_dir
    }

    /// ASN number → CIDRs for every entry of asns that resolves. Feed the result to
    /// routing::asn::expand.
    pub fn ensure(&self, http: &mut dyn HttpGet, asns: &[String], base_url: &str) -> BTreeMap<String, Vec<String>> {
        let mut out = BTreeMap::new();
        if asns.is_empty() {
            return out;
        }
        let base = asn_base(base_url);
        let _ = fs::create_dir_all(&self.cache_dir);
        for raw in asns {
            let n = asn_digits(raw);
            if n.is_empty() {
                continue;
            }
            let hit = self.memory.lock().unwrap().get(&n).cloned();
            if let Some(hit) = hit {
                out.insert(n, hit);
                continue;
            }
            let cache_file = self.cache_dir.join(asn_cache_file_name(&n));
            let cidrs = match read_cached(&cache_file) {
                Some(c) => c,
                None => {
                    let fetched = self.fetch(http, &base, &n);
                    if !fetched.is_empty() {
                        let _ = fs::write(&cache_file, fetched.join("\n"));
                    }
                    fetched
                }
            };
            if !cidrs.is_empty() {
                let capped: Vec<String> = cidrs.into_iter().take(MAX_CIDRS_PER_ASN).collect();
                self.memory.lock().unwrap().insert(n.clone(), capped.clone());
                out.insert(n, capped);
            }
        }
        out
    }

    /// IPv4 + IPv6 aggregated prefixes for ASN n; either may be empty.
    fn fetch(&self, http: &mut dyn HttpGet, base: &str, n: &str) -> Vec<String> {
        let mut all: Vec<String> = Vec::new();
        for url in asn_urls(base, n) {
            let list = fetch_following(http, &url, &[("User-Agent", USER_AGENT)], self.platform.asn_redirect_style())
                .map(|body| String::from_utf8_lossy(&body).lines().filter_map(clean_cidr).collect::<Vec<_>>())
                .unwrap_or_default();
            for c in list {
                if !all.contains(&c) {
                    all.push(c);
                }
            }
        }
        all
    }
}

fn read_cached(file: &Path) -> Option<Vec<String>> {
    if file_size(file) == 0 {
        return None;
    }
    let text = fs::read_to_string(file).ok()?;
    let list: Vec<String> = text.lines().filter_map(clean_cidr).collect();
    (!list.is_empty()).then_some(list)
}
