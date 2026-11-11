//! `asn:13335` routing selectors -> the operator's CIDR list, resolved at config-build time so the
//! cores only ever see ordinary IP ranges. Port of JvmAsnResolver.kt (desktop counterpart of
//! Android's AsnResolver).
//!
//! Prefixes come from the ipverse `asn-ip` dataset (one aggregated file per ASN) and are cached
//! under `<appData>/asn-cache/`, so each ASN is fetched at most once. Every failure degrades
//! gracefully: an unresolvable ASN is omitted, the rest of the rule still applies.
//!
//! The HTTP GET itself is platform-side ([`AsnHttp`]); redirects are followed here, like the Kotlin
//! code did by hand. The process-lifetime memo lives in [`AsnResolver`], so keep one instance.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::fs;
use std::path::{Path, PathBuf};

use crate::util::{filter_digits, is_blank};

pub const DIR: &str = "asn-cache";

/// ipverse aggregated per-ASN prefix lists: `<base>/as/<n>/ipv4-aggregated.txt` (+ ipv6).
pub const DEFAULT_BASE: &str = "https://raw.githubusercontent.com/ipverse/asn-ip/master";

pub const CONNECT_TIMEOUT_MS: u64 = 15_000;
pub const READ_TIMEOUT_MS: u64 = 30_000;
pub const MAX_REDIRECTS: usize = 5;
pub const USER_AGENT: &str = "olcbox-vpn";

/// Don't let a pathological ASN file blow up memory / the config.
pub const MAX_CIDRS_PER_ASN: usize = 20_000;

/// One HTTP response, redirects NOT followed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HttpResponse {
    pub status: u16,
    /// `Location` header, if any.
    pub location: Option<String>,
    pub body: String,
}

/// Platform HTTP: a single GET with [`CONNECT_TIMEOUT_MS`] / [`READ_TIMEOUT_MS`], `User-Agent:
/// olcbox-vpn`, redirects not followed. `Err` for transport failures.
pub trait AsnHttp {
    fn get(&self, url: &str) -> Result<HttpResponse, String>;
}

/// Keeps only plausible CIDR lines (skips blanks and `#` comments in the source files).
pub fn clean_cidr(line: &str) -> Option<String> {
    let t = line.trim();
    if t.is_empty() || t.starts_with('#') {
        return None;
    }
    if t.contains('/') && (t.contains('.') || t.contains(':')) {
        Some(t.to_string())
    } else {
        None
    }
}

/// The ASN number of a selector value: its digits (`"AS13335"` -> `"13335"`); empty if none.
pub fn asn_number(raw: &str) -> String {
    filter_digits(raw.trim())
}

/// Blank -> [`DEFAULT_BASE`]; trailing slashes removed.
pub fn normalize_base(base_url: &str) -> String {
    let base = if is_blank(base_url) { DEFAULT_BASE } else { base_url };
    base.trim_end_matches('/').to_string()
}

pub fn ipv4_url(base: &str, n: &str) -> String {
    format!("{base}/as/{n}/ipv4-aggregated.txt")
}

pub fn ipv6_url(base: &str, n: &str) -> String {
    format!("{base}/as/{n}/ipv6-aggregated.txt")
}

fn has_scheme(s: &str) -> bool {
    match s.find(':') {
        Some(i) if i > 0 => {
            let scheme = &s[..i];
            scheme.chars().next().map_or(false, |c| c.is_ascii_alphabetic())
                && scheme
                    .chars()
                    .all(|c| c.is_ascii_alphanumeric() || c == '+' || c == '-' || c == '.')
        }
        _ => false,
    }
}

/// `URL(URL(current), location)`: absolute, scheme-relative (`//host/x`), absolute-path (`/x`),
/// query-only (`?x`) and relative (`x`) locations. Dot segments are kept as they are.
pub fn resolve_redirect(current: &str, location: &str) -> String {
    let loc = location.trim();
    if has_scheme(loc) {
        return loc.to_string();
    }
    let (scheme, rest) = match current.find("://") {
        Some(i) => (&current[..i], &current[i + 3..]),
        None => return loc.to_string(),
    };
    if let Some(r) = loc.strip_prefix("//") {
        return format!("{scheme}://{r}");
    }
    let auth_end = rest.find(|c: char| c == '/' || c == '?' || c == '#').unwrap_or(rest.len());
    let origin = format!("{scheme}://{}", &rest[..auth_end]);
    let tail = &rest[auth_end..];
    let path = &tail[..tail.find(|c: char| c == '?' || c == '#').unwrap_or(tail.len())];
    if loc.starts_with('/') {
        return format!("{origin}{loc}");
    }
    if loc.starts_with('?') {
        let p = if path.is_empty() { "/" } else { path };
        return format!("{origin}{p}{loc}");
    }
    let dir = match path.rfind('/') {
        Some(i) => &path[..=i],
        None => "/",
    };
    format!("{origin}{dir}{loc}")
}

/// GET [`url`] following up to [`MAX_REDIRECTS`] redirects; the cleaned CIDR lines on HTTP 200.
pub fn download(http: &dyn AsnHttp, url: &str) -> Result<Vec<String>, String> {
    let mut current = url.to_string();
    let mut redirects = 0usize;
    loop {
        let resp = http.get(&current)?;
        if (300..=399).contains(&resp.status) {
            let location = resp.location.as_deref().filter(|l| !is_blank(l));
            match location {
                Some(l) => {
                    redirects += 1;
                    if redirects > MAX_REDIRECTS {
                        return Err(format!("too many redirects for {url}"));
                    }
                    current = resolve_redirect(&current, l);
                    continue;
                }
                None => return Err(format!("too many redirects for {url}")),
            }
        }
        if resp.status != 200 {
            return Err(format!("HTTP {} for {}", resp.status, current));
        }
        return Ok(resp.body.lines().filter_map(clean_cidr).collect());
    }
}

/// IPv4 + IPv6 aggregated prefixes for ASN [`n`]; either may be empty, failures give empty.
pub fn fetch(http: &dyn AsnHttp, base: &str, n: &str) -> Vec<String> {
    let v4 = download(http, &ipv4_url(base, n)).unwrap_or_default();
    let v6 = download(http, &ipv6_url(base, n)).unwrap_or_default();
    let mut seen: HashSet<String> = HashSet::new();
    let mut out: Vec<String> = Vec::new();
    for c in v4.into_iter().chain(v6) {
        if seen.insert(c.clone()) {
            out.push(c);
        }
    }
    out
}

/// Resolver with the disk cache under `<appData>/asn-cache/` and an in-memory memo.
#[derive(Debug, Clone)]
pub struct AsnResolver {
    cache_dir: PathBuf,
    memory: HashMap<String, Vec<String>>,
}

impl AsnResolver {
    pub fn new(app_data_dir: &Path) -> Self {
        Self { cache_dir: app_data_dir.join(DIR), memory: HashMap::new() }
    }

    pub fn cache_dir(&self) -> &Path {
        &self.cache_dir
    }

    pub fn cache_file(&self, n: &str) -> PathBuf {
        self.cache_dir.join(format!("asn-{n}.txt"))
    }

    fn read_cache(path: &Path) -> Option<Vec<String>> {
        let meta = fs::metadata(path).ok()?;
        if !meta.is_file() || meta.len() == 0 {
            return None;
        }
        let text = fs::read_to_string(path).ok()?;
        let cidrs: Vec<String> = text.lines().filter_map(clean_cidr).collect();
        if cidrs.is_empty() {
            None
        } else {
            Some(cidrs)
        }
    }

    /// ASN number -> CIDRs for every entry of [`asns`] that resolves (memo -> disk -> network).
    /// Unresolvable ASNs are simply absent from the result.
    pub fn ensure(
        &mut self,
        asns: &[String],
        base_url: &str,
        http: &dyn AsnHttp,
    ) -> BTreeMap<String, Vec<String>> {
        let mut out = BTreeMap::new();
        if asns.is_empty() {
            return out;
        }
        let base = normalize_base(base_url);
        let _ = fs::create_dir_all(&self.cache_dir);
        for raw in asns {
            let n = asn_number(raw);
            if n.is_empty() {
                continue;
            }
            if let Some(c) = self.memory.get(&n) {
                out.insert(n, c.clone());
                continue;
            }
            let file = self.cache_file(&n);
            let cidrs = match Self::read_cache(&file) {
                Some(c) => c,
                None => {
                    let fetched = fetch(http, &base, &n);
                    if !fetched.is_empty() {
                        let _ = fs::write(&file, fetched.join("\n"));
                    }
                    fetched
                }
            };
            if !cidrs.is_empty() {
                let capped: Vec<String> = cidrs.into_iter().take(MAX_CIDRS_PER_ASN).collect();
                self.memory.insert(n.clone(), capped.clone());
                out.insert(n, capped);
            }
        }
        out
    }
}
