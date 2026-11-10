use std::cell::RefCell;
use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;
use std::time::{SystemTime, UNIX_EPOCH};

use yptun_core::asn_resolver::*;

struct Mock {
    routes: HashMap<String, HttpResponse>,
    calls: RefCell<Vec<String>>,
}

impl Mock {
    fn new() -> Self {
        Self { routes: HashMap::new(), calls: RefCell::new(Vec::new()) }
    }
    fn ok(mut self, url: &str, body: &str) -> Self {
        self.routes.insert(
            url.to_string(),
            HttpResponse { status: 200, location: None, body: body.to_string() },
        );
        self
    }
    fn status(mut self, url: &str, status: u16, location: Option<&str>) -> Self {
        self.routes.insert(
            url.to_string(),
            HttpResponse { status, location: location.map(str::to_string), body: String::new() },
        );
        self
    }
    fn calls(&self) -> Vec<String> {
        self.calls.borrow().clone()
    }
}

impl AsnHttp for Mock {
    fn get(&self, url: &str) -> Result<HttpResponse, String> {
        self.calls.borrow_mut().push(url.to_string());
        self.routes.get(url).cloned().ok_or_else(|| "unreachable".to_string())
    }
}

fn temp_dir(tag: &str) -> PathBuf {
    let nanos = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
    let d = std::env::temp_dir().join(format!("yptun-asn-{tag}-{}-{nanos}", std::process::id()));
    let _ = fs::remove_dir_all(&d);
    fs::create_dir_all(&d).unwrap();
    d
}

fn v(items: &[&str]) -> Vec<String> {
    items.iter().map(|s| s.to_string()).collect()
}

#[test]
fn cidr_cleaning_and_urls() {
    assert_eq!(clean_cidr("  1.0.0.0/24 "), Some("1.0.0.0/24".to_string()));
    assert_eq!(clean_cidr("2606:4700::/32"), Some("2606:4700::/32".to_string()));
    assert_eq!(clean_cidr("# comment 1.0.0.0/24"), None);
    assert_eq!(clean_cidr(""), None);
    assert_eq!(clean_cidr("garbage"), None);
    assert_eq!(clean_cidr("10/8"), None);
    assert_eq!(clean_cidr("1.1.1.1"), None);

    assert_eq!(asn_number("AS13335"), "13335");
    assert_eq!(asn_number(" 15169 "), "15169");
    assert_eq!(asn_number("x"), "");

    assert_eq!(normalize_base("  "), DEFAULT_BASE);
    assert_eq!(normalize_base("https://m/x//"), "https://m/x");
    assert_eq!(ipv4_url("https://m", "1"), "https://m/as/1/ipv4-aggregated.txt");
    assert_eq!(ipv6_url("https://m", "1"), "https://m/as/1/ipv6-aggregated.txt");
}

#[test]
fn redirect_resolution() {
    let cur = "https://a.com/x/y?z=1";
    assert_eq!(resolve_redirect(cur, "https://b.org/c"), "https://b.org/c");
    assert_eq!(resolve_redirect(cur, "//cdn.net/f"), "https://cdn.net/f");
    assert_eq!(resolve_redirect(cur, "/p/q"), "https://a.com/p/q");
    assert_eq!(resolve_redirect(cur, "r.txt"), "https://a.com/x/r.txt");
    assert_eq!(resolve_redirect(cur, "?v=2"), "https://a.com/x/y?v=2");
    assert_eq!(resolve_redirect("https://a.com", "r"), "https://a.com/r");
}

#[test]
fn download_follows_redirects_and_fails_cleanly() {
    let m = Mock::new()
        .status("https://h.com/a", 302, Some("/b"))
        .ok("https://h.com/b", "1.0.0.0/24\n# x\n\n2.0.0.0/16\r\n")
        .status("https://h.com/404", 404, None)
        .status("https://h.com/loop", 301, Some("https://h.com/loop"))
        .status("https://h.com/noloc", 302, None);
    assert_eq!(download(&m, "https://h.com/a").unwrap(), v(&["1.0.0.0/24", "2.0.0.0/16"]));
    assert_eq!(m.calls(), v(&["https://h.com/a", "https://h.com/b"]));

    assert_eq!(
        download(&m, "https://h.com/404").unwrap_err(),
        "HTTP 404 for https://h.com/404"
    );

    let before = m.calls().len();
    assert_eq!(
        download(&m, "https://h.com/loop").unwrap_err(),
        "too many redirects for https://h.com/loop"
    );
    assert_eq!(m.calls().len() - before, MAX_REDIRECTS + 1);

    assert!(download(&m, "https://h.com/noloc").is_err());
    assert_eq!(download(&m, "https://h.com/missing").unwrap_err(), "unreachable");
}

#[test]
fn ensure_uses_memo_then_disk_then_network() {
    let dir = temp_dir("ensure");
    let m = Mock::new()
        .ok("https://m/as/13335/ipv4-aggregated.txt", "1.1.1.0/24\n1.0.0.0/24\n")
        .ok("https://m/as/13335/ipv6-aggregated.txt", "2606:4700::/32\n1.1.1.0/24\n");
    let mut r = AsnResolver::new(&dir);

    assert!(r.ensure(&[], "https://m", &m).is_empty());
    assert!(m.calls().is_empty());

    let asns = v(&["AS13335", "15169", "x"]);
    let out = r.ensure(&asns, "https://m/", &m);
    let expected = v(&["1.1.1.0/24", "1.0.0.0/24", "2606:4700::/32"]);
    assert_eq!(out.len(), 1);
    assert_eq!(out.get("13335"), Some(&expected));
    assert_eq!(m.calls().len(), 4);
    assert_eq!(
        fs::read_to_string(r.cache_file("13335")).unwrap(),
        "1.1.1.0/24\n1.0.0.0/24\n2606:4700::/32"
    );
    assert!(!r.cache_file("15169").exists());

    // Memo: no new requests.
    let again = r.ensure(&v(&["13335"]), "https://m", &m);
    assert_eq!(again.get("13335"), Some(&expected));
    assert_eq!(m.calls().len(), 4);

    // Fresh resolver, same dir: served from disk.
    let empty = Mock::new();
    let mut r2 = AsnResolver::new(&dir);
    let disk = r2.ensure(&v(&["13335"]), "https://m", &empty);
    assert_eq!(disk.get("13335"), Some(&expected));
    assert!(empty.calls().is_empty());

    let _ = fs::remove_dir_all(&dir);
}

#[test]
fn ensure_caps_huge_lists() {
    let dir = temp_dir("cap");
    let body: Vec<String> =
        (0..MAX_CIDRS_PER_ASN + 5).map(|i| format!("10.{}.{}.0/24", i / 256, i % 256)).collect();
    let m = Mock::new().ok("https://m/as/7/ipv4-aggregated.txt", &body.join("\n"));
    let mut r = AsnResolver::new(&dir);
    let out = r.ensure(&v(&["7"]), "https://m", &m);
    assert_eq!(out.get("7").map(Vec::len), Some(MAX_CIDRS_PER_ASN));
    let _ = fs::remove_dir_all(&dir);
}
