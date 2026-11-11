use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;

use yptun_core::geo_assets::{self as geo, AsnResolver, GeoPlatform, HttpGet, HttpResponse, RedirectStyle};
use yptun_core::routing;

#[derive(Default)]
struct Fake {
    routes: HashMap<String, HttpResponse>,
    calls: Vec<String>,
}

impl Fake {
    fn ok(mut self, url: &str, body: Vec<u8>) -> Self {
        self.routes.insert(url.into(), HttpResponse { status: 200, location: None, body });
        self
    }
    fn status(mut self, url: &str, status: u16) -> Self {
        self.routes.insert(url.into(), HttpResponse { status, location: None, body: Vec::new() });
        self
    }
    fn redirect(mut self, url: &str, status: u16, to: &str) -> Self {
        self.routes.insert(url.into(), HttpResponse { status, location: Some(to.into()), body: Vec::new() });
        self
    }
}

impl HttpGet for Fake {
    fn get(&mut self, url: &str, _headers: &[(&str, &str)]) -> Result<HttpResponse, String> {
        self.calls.push(url.to_string());
        self.routes.get(url).cloned().ok_or_else(|| format!("no route {url}"))
    }
}

fn temp_dir(tag: &str) -> PathBuf {
    let p = std::env::temp_dir().join(format!("yptun-geo-{}-{}", tag, std::process::id()));
    let _ = fs::remove_dir_all(&p);
    fs::create_dir_all(&p).unwrap();
    p
}

fn chain(style_abs: bool) -> Fake {
    let mut f = Fake::default();
    for i in 0..6 {
        let to = if style_abs { format!("https://a/r{}", i + 1) } else { format!("r{}", i + 1) };
        f = f.redirect(&format!("https://a/r{i}"), 301, &to);
    }
    f.ok("https://a/r6", b"done".to_vec())
}

#[test]
fn platform_constants() {
    assert_eq!(GeoPlatform::Android.asset_dir_name(), "xray-assets");
    assert_eq!(GeoPlatform::Desktop.asset_dir_name(), "geo");
    assert_eq!(GeoPlatform::Ios.asset_dir_name(), "geo");
    assert_eq!(GeoPlatform::Android.min_dat_bytes(), 50_000);
    assert_eq!(GeoPlatform::Desktop.min_dat_bytes(), 65_536);
    assert_eq!(GeoPlatform::Android.default_geoip_url(), routing::DEFAULT_GEOIP_URL);
    assert_eq!(GeoPlatform::Android.default_geosite_url(), routing::DEFAULT_GEOSITE_URL);
    assert_eq!(GeoPlatform::Desktop.default_geoip_url(), geo::LOYALSOLDIER_GEOIP_URL);
    assert_eq!(GeoPlatform::Ios.default_geosite_url(), geo::LOYALSOLDIER_GEOSITE_URL);
    let (ip, site) = GeoPlatform::Desktop.resolve_urls("  ", "https://x/s.dat");
    assert_eq!(ip, geo::LOYALSOLDIER_GEOIP_URL);
    assert_eq!(site, "https://x/s.dat");
}

#[test]
fn resolve_url_cases() {
    assert_eq!(geo::resolve_url("https://h/a/b", "https://o/x"), "https://o/x");
    assert_eq!(geo::resolve_url("https://h/a/b?q=1", "/x"), "https://h/x");
    assert_eq!(geo::resolve_url("https://h/a/b", "//o/x"), "https://o/x");
    assert_eq!(geo::resolve_url("https://h/a/b", "c"), "https://h/a/c");
    assert_eq!(geo::resolve_url("https://h/a/b/c", "../d"), "https://h/a/d");
    assert_eq!(geo::resolve_url("https://h", "x"), "https://h/x");
    assert_eq!(geo::resolve_url("https://h/a/b", "?v=2"), "https://h/a/b?v=2");
}

#[test]
fn android_redirect_rules() {
    let mut f = Fake::default().redirect("https://a/x", 302, "/y").ok("https://a/y", b"ok".to_vec());
    assert_eq!(geo::fetch_following(&mut f, "https://a/x", &[], RedirectStyle::Android), Ok(b"ok".to_vec()));

    let mut f = chain(false);
    assert_eq!(
        geo::fetch_following(&mut f, "https://a/r0", &[], RedirectStyle::Android),
        Err("too many redirects for https://a/r0".to_string())
    );
    let mut f = chain(false);
    assert_eq!(geo::fetch_following(&mut f, "https://a/r1", &[], RedirectStyle::Android), Ok(b"done".to_vec()));

    let mut f = Fake::default().status("https://a/m", 300).status("https://a/n", 404);
    assert_eq!(
        geo::fetch_following(&mut f, "https://a/m", &[], RedirectStyle::Android),
        Err("HTTP 300 for https://a/m".to_string())
    );
    assert_eq!(
        geo::fetch_following(&mut f, "https://a/n", &[], RedirectStyle::Android),
        Err("HTTP 404 for https://a/n".to_string())
    );
}

#[test]
fn desktop_geo_redirect_rules() {
    let mut f = chain(true);
    assert_eq!(
        geo::fetch_following(&mut f, "https://a/r0", &[], RedirectStyle::DesktopGeo),
        Err("bad redirect from https://a/r5".to_string())
    );
    let mut f = chain(true);
    assert_eq!(geo::fetch_following(&mut f, "https://a/r1", &[], RedirectStyle::DesktopGeo), Ok(b"done".to_vec()));

    // Location is used as is (no relative resolution), exactly like JvmGeoAssets.
    let mut f = Fake::default().redirect("https://a/z", 302, "/w");
    assert!(geo::fetch_following(&mut f, "https://a/z", &[], RedirectStyle::DesktopGeo).is_err());
    assert_eq!(f.calls.last().map(String::as_str), Some("/w"));

    let mut f = Fake::default().status("https://a/e", 204);
    assert_eq!(geo::fetch_following(&mut f, "https://a/e", &[], RedirectStyle::DesktopGeo), Ok(Vec::new()));
    assert_eq!(
        geo::fetch_following(&mut f, "https://a/e", &[], RedirectStyle::Android),
        Err("HTTP 204 for https://a/e".to_string())
    );
}

#[test]
fn android_download_is_atomic() {
    let dir = temp_dir("android");
    let big = vec![7u8; 50_000];
    let mut f = Fake::default().ok("https://g/ip", big.clone()).ok("https://g/site", big.clone());
    let r = geo::download(&mut f, GeoPlatform::Android, &dir, "https://g/ip", "https://g/site");
    assert_eq!(r, geo::DownloadResult { success: true, bytes: 100_000, error: None });
    assert!(geo::has_assets(&dir, GeoPlatform::Android));
    assert!(!dir.join("geoip.dat.tmp").exists());

    let mut f = Fake::default().ok("https://g/ip", vec![1u8; 10]);
    let r = geo::download(&mut f, GeoPlatform::Android, &dir, "https://g/ip", "https://g/site");
    assert!(!r.success);
    assert_eq!(r.error.as_deref(), Some("downloaded file too small (10 bytes) — not a .dat?"));
    assert_eq!(fs::metadata(dir.join(geo::GEOIP_FILE)).unwrap().len(), 50_000);
    assert!(!dir.join("geoip.dat.tmp").exists());

    let mut f = Fake::default()
        .ok(routing::DEFAULT_GEOIP_URL, big.clone())
        .ok(routing::DEFAULT_GEOSITE_URL, big);
    assert!(geo::download(&mut f, GeoPlatform::Android, &dir, "", " ").success);
    let _ = fs::remove_dir_all(&dir);
}

#[test]
fn ensure_assets_per_platform() {
    let dir = temp_dir("ensure-present");
    fs::write(dir.join(geo::GEOIP_FILE), vec![0u8; 65_536]).unwrap();
    fs::write(dir.join(geo::GEOSITE_FILE), vec![0u8; 65_536]).unwrap();
    let mut f = Fake::default();
    assert!(geo::ensure_assets(&mut f, GeoPlatform::Desktop, &dir, "", ""));
    assert!(f.calls.is_empty());
    let _ = fs::remove_dir_all(&dir);

    let dir = temp_dir("ensure-desktop");
    let big = vec![1u8; 65_536];
    let mut f = Fake::default()
        .ok(geo::LOYALSOLDIER_GEOIP_URL, big.clone())
        .ok(geo::LOYALSOLDIER_GEOSITE_URL, big.clone());
    assert!(geo::ensure_assets(&mut f, GeoPlatform::Desktop, &dir, "", ""));
    assert!(!dir.join("geoip.dat.part").exists());
    let _ = fs::remove_dir_all(&dir);

    let dir = temp_dir("ensure-small");
    let mut f = Fake::default()
        .ok(geo::LOYALSOLDIER_GEOIP_URL, vec![1u8; 10])
        .ok(geo::LOYALSOLDIER_GEOSITE_URL, vec![1u8; 10]);
    assert!(!geo::ensure_assets(&mut f, GeoPlatform::Desktop, &dir, "", ""));
    let _ = fs::remove_dir_all(&dir);

    let dir = temp_dir("ensure-ios");
    fs::write(dir.join(geo::GEOIP_FILE), vec![0u8; 65_536]).unwrap();
    let mut f = Fake::default().ok(geo::LOYALSOLDIER_GEOSITE_URL, big);
    assert!(geo::ensure_assets(&mut f, GeoPlatform::Ios, &dir, "", ""));
    assert_eq!(f.calls, vec![geo::LOYALSOLDIER_GEOSITE_URL.to_string()]);
    let _ = fs::remove_dir_all(&dir);
}

#[test]
fn clean_cidr_and_helpers() {
    assert_eq!(geo::clean_cidr("  1.2.3.0/24 ").as_deref(), Some("1.2.3.0/24"));
    assert_eq!(geo::clean_cidr("2001:db8::/32").as_deref(), Some("2001:db8::/32"));
    assert_eq!(geo::clean_cidr("# comment"), None);
    assert_eq!(geo::clean_cidr(""), None);
    assert_eq!(geo::clean_cidr("1.2.3.4"), None);
    assert_eq!(geo::clean_cidr("abc/def"), None);
    assert_eq!(geo::asn_digits(" asn:13335 "), "13335");
    assert_eq!(geo::asn_digits("AS15169"), "15169");
    assert_eq!(geo::asn_base(""), geo::ASN_DEFAULT_BASE);
    assert_eq!(geo::asn_base("https://x//"), "https://x");
    assert_eq!(geo::asn_urls("https://x", "1")[1], "https://x/as/1/ipv6-aggregated.txt");
    assert_eq!(geo::asn_cache_file_name("62041"), "asn-62041.txt");
}

#[test]
fn asn_resolver_memo_disk_network() {
    let dir = temp_dir("asn").join(geo::ASN_CACHE_DIR);
    let mut f = Fake::default()
        .ok("https://x/as/13335/ipv4-aggregated.txt", b"# hdr\n1.1.1.0/24\n\n1.0.0.0/24\n".to_vec())
        .ok("https://x/as/13335/ipv6-aggregated.txt", b"2606:4700::/32\r\n1.1.1.0/24\n".to_vec());
    let resolver = AsnResolver::new(dir.clone(), GeoPlatform::Android);
    let asns = vec!["asn:13335".to_string(), "AS64512".to_string(), "  ".to_string()];
    let out = resolver.ensure(&mut f, &asns, "https://x/");
    let want = vec!["1.1.1.0/24".to_string(), "1.0.0.0/24".to_string(), "2606:4700::/32".to_string()];
    assert_eq!(out.len(), 1);
    assert_eq!(out.get("13335"), Some(&want));
    assert_eq!(fs::read_to_string(dir.join("asn-13335.txt")).unwrap(), want.join("\n"));

    let mut empty = Fake::default();
    assert_eq!(resolver.ensure(&mut empty, &asns[..1], "https://x/").get("13335"), Some(&want));
    assert!(empty.calls.is_empty());

    let fresh = AsnResolver::new(dir.clone(), GeoPlatform::Desktop);
    let mut empty = Fake::default();
    assert_eq!(fresh.ensure(&mut empty, &asns[..1], "https://x").get("13335"), Some(&want));
    assert!(empty.calls.is_empty());

    let expanded = routing::asn::expand(&["asn:13335".to_string(), "geoip:ru".to_string()], &out);
    assert_eq!(expanded, vec!["1.1.1.0/24", "1.0.0.0/24", "2606:4700::/32", "geoip:ru"]);

    let many: String = (0..20_001).map(|i| format!("10.{}.{}.0/24", i / 256, i % 256)).collect::<Vec<_>>().join("\n");
    let mut f = Fake::default().ok("https://x/as/1/ipv4-aggregated.txt", many.into_bytes());
    let out = resolver.ensure(&mut f, &["1".to_string()], "https://x");
    assert_eq!(out["1"].len(), geo::MAX_CIDRS_PER_ASN);
    let _ = fs::remove_dir_all(dir.parent().unwrap());
}
