//! Routing: Happ-compatible routing profiles, the global routing/traffic settings, structured
//! sing-box rules and ASN selectors (port of `RoutingProfile`, `RoutingProfilesState`,
//! `RoutingRules`, `SingBoxRule`, `TrafficSettings`, `Asn`, `HappRoutingParser`).

use std::collections::BTreeMap;

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use serde::{Deserialize, Deserializer, Serialize};
use serde_json::Value;

use crate::subscription::decode_base64_chunk;

/// Kotlin's lenient JSON accepts `"true"`/`"false"` for booleans; Happ links use them.
fn lenient_bool<'de, D: Deserializer<'de>>(d: D) -> Result<bool, D::Error> {
    Ok(match Value::deserialize(d)? {
        Value::Bool(b) => b,
        Value::String(s) => s.trim().eq_ignore_ascii_case("true") || s.trim() == "1",
        Value::Number(n) => n.as_i64().unwrap_or(0) != 0,
        _ => false,
    })
}

fn yes() -> bool {
    true
}

// ---------------- ASN ----------------

pub mod asn {
    use std::collections::BTreeMap;

    pub fn is_selector(selector: &str) -> bool {
        let t = selector.trim();
        t.len() >= 4 && t[..4].eq_ignore_ascii_case("asn:")
    }

    /// `asn:13335` / `AS13335` / `13335` → `13335`; `None` when not a plain numeric ASN.
    pub fn normalize(raw: &str) -> Option<String> {
        let mut v = raw.trim();
        if v.len() >= 4 && v[..4].eq_ignore_ascii_case("asn:") {
            v = v[4..].trim();
        }
        if v.len() >= 2 && v[..2].eq_ignore_ascii_case("as") {
            v = v[2..].trim();
        }
        (!v.is_empty() && v.bytes().all(|b| b.is_ascii_digit())).then(|| v.to_string())
    }

    /// The distinct ASN numbers of a selector list, first-seen order.
    pub fn collect<'a>(selectors: impl IntoIterator<Item = &'a String>) -> Vec<String> {
        let mut out: Vec<String> = Vec::new();
        for n in selectors.into_iter().filter(|s| is_selector(s)).filter_map(|s| normalize(s)) {
            if !out.contains(&n) {
                out.push(n);
            }
        }
        out
    }

    /// Replaces every `asn:N` with its CIDRs (unresolved → nothing); order preserved.
    pub fn expand(selectors: &[String], cidrs_by_asn: &BTreeMap<String, Vec<String>>) -> Vec<String> {
        if !selectors.iter().any(|s| is_selector(s)) {
            return selectors.to_vec();
        }
        selectors
            .iter()
            .flat_map(|s| {
                if !is_selector(s) {
                    vec![s.clone()]
                } else {
                    normalize(s).and_then(|n| cidrs_by_asn.get(&n).cloned()).unwrap_or_default()
                }
            })
            .collect()
    }

    pub const COMMON_PRESETS: [(&str, &str); 10] = [
        ("Telegram", "62041"), ("Cloudflare", "13335"), ("Google", "15169"), ("Meta (FB/IG/WA)", "32934"),
        ("Discord", "49544"), ("Twitter / X", "13414"), ("Amazon AWS", "16509"), ("Microsoft", "8075"),
        ("Netflix", "2906"), ("Akamai", "20940"),
    ];
}

// ---------------- RoutingProfile ----------------

pub const NONE_ID: &str = "__none__";
pub const DEFAULT_RU_DIRECT_ID: &str = "default-ru-direct";
pub const DEFAULT_GEOIP_URL: &str = "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download/geoip.dat";
pub const DEFAULT_GEOSITE_URL: &str = "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download/geosite.dat";
pub const ROUTE_ORDERS: [&str; 4] = ["block-direct-proxy", "block-proxy-direct", "direct-block-proxy", "proxy-block-direct"];
pub const DOMAIN_STRATEGIES: [&str; 3] = ["IPIfNonMatch", "IPOnDemand", "AsIs"];
pub const XRAY_DOMAIN_STRATEGIES: [&str; 3] = ["AsIs", "IPIfNonMatch", "IPOnDemand"];
pub const SINGBOX_DOMAIN_STRATEGIES: [&str; 5] = ["", "prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only"];

/// A named, Happ-compatible routing profile (the JSON of `happ://routing/add/<base64url-json>`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct RoutingProfile {
    pub name: String,
    #[serde(rename = "blockip")]
    pub block_ip: Vec<String>,
    #[serde(rename = "blocksites")]
    pub block_sites: Vec<String>,
    #[serde(rename = "directip")]
    pub direct_ip: Vec<String>,
    #[serde(rename = "directsites")]
    pub direct_sites: Vec<String>,
    #[serde(rename = "proxyip")]
    pub proxy_ip: Vec<String>,
    #[serde(rename = "proxysites")]
    pub proxy_sites: Vec<String>,
    #[serde(rename = "dnshosts")]
    pub dns_hosts: BTreeMap<String, String>,
    #[serde(rename = "remotednsdomain")]
    pub remote_dns_domain: String,
    #[serde(rename = "remotednsip")]
    pub remote_dns_ip: String,
    #[serde(rename = "remotednstype")]
    pub remote_dns_type: String,
    #[serde(rename = "domesticdnsdomain")]
    pub domestic_dns_domain: String,
    #[serde(rename = "domesticdnsip")]
    pub domestic_dns_ip: String,
    #[serde(rename = "domesticdnstype")]
    pub domestic_dns_type: String,
    #[serde(rename = "fakedns", deserialize_with = "lenient_bool")]
    pub fake_dns: bool,
    #[serde(rename = "domainstrategy")]
    pub domain_strategy: String,
    /// Bucket order, dash-separated, e.g. "block-direct-proxy".
    #[serde(rename = "routeorder")]
    pub route_order: String,
    /// Everything not matched by direct/block goes through the proxy.
    #[serde(rename = "globalproxy", deserialize_with = "lenient_bool")]
    pub global_proxy: bool,
    #[serde(rename = "geoipurl")]
    pub geoip_url: String,
    #[serde(rename = "geositeurl")]
    pub geosite_url: String,
    #[serde(rename = "expert", deserialize_with = "lenient_bool")]
    pub expert_enabled: bool,
    #[serde(rename = "xraydomainstrategy")]
    pub xray_domain_strategy: String,
    #[serde(rename = "xraysniffing", deserialize_with = "lenient_bool")]
    pub xray_sniffing: bool,
    #[serde(rename = "xrayrouteonly", deserialize_with = "lenient_bool")]
    pub xray_route_only: bool,
    #[serde(rename = "singboxdomainstrategy")]
    pub singbox_domain_strategy: String,
    #[serde(rename = "singboxsniff", deserialize_with = "lenient_bool")]
    pub singbox_sniff: bool,
    #[serde(rename = "singboxresolve", deserialize_with = "lenient_bool")]
    pub singbox_resolve: bool,
    /// Local-only storage id; not part of the Happ format.
    #[serde(rename = "_id")]
    pub id: String,
}

impl Default for RoutingProfile {
    fn default() -> Self {
        Self {
            name: String::new(),
            block_ip: vec![],
            block_sites: vec![],
            direct_ip: vec![],
            direct_sites: vec![],
            proxy_ip: vec![],
            proxy_sites: vec![],
            dns_hosts: BTreeMap::new(),
            remote_dns_domain: String::new(),
            remote_dns_ip: String::new(),
            remote_dns_type: String::new(),
            domestic_dns_domain: String::new(),
            domestic_dns_ip: String::new(),
            domestic_dns_type: String::new(),
            fake_dns: false,
            domain_strategy: "IPIfNonMatch".into(),
            route_order: "block-direct-proxy".into(),
            global_proxy: true,
            geoip_url: String::new(),
            geosite_url: String::new(),
            expert_enabled: false,
            xray_domain_strategy: String::new(),
            xray_sniffing: true,
            xray_route_only: false,
            singbox_domain_strategy: String::new(),
            singbox_sniff: true,
            singbox_resolve: false,
            id: String::new(),
        }
    }
}

fn starts_ci(s: &str, prefix: &str) -> bool {
    s.len() >= prefix.len() && s.is_char_boundary(prefix.len()) && s[..prefix.len()].eq_ignore_ascii_case(prefix)
}

/// Kotlin's `encodeDefaults = false` for a flat struct.
fn without_defaults<T: Serialize + Default>(value: &T) -> Value {
    let mut v = serde_json::to_value(value).unwrap();
    if let (Value::Object(map), Value::Object(def)) = (&mut v, serde_json::to_value(T::default()).unwrap()) {
        map.retain(|k, x| def.get(k) != Some(x));
    }
    v
}

impl RoutingProfile {
    fn ip_buckets(&self) -> impl Iterator<Item = &String> {
        self.block_ip.iter().chain(&self.direct_ip).chain(&self.proxy_ip)
    }
    fn site_buckets(&self) -> impl Iterator<Item = &String> {
        self.block_sites.iter().chain(&self.direct_sites).chain(&self.proxy_sites)
    }

    /// Any CIDR/`geoip:` matcher: sing-box must `resolve` sniffed domains for them to match.
    pub fn uses_ip_rules(&self) -> bool {
        self.ip_buckets().any(|s| !s.trim().is_empty())
    }

    pub fn referenced_asns(&self) -> Vec<String> {
        asn::collect(self.ip_buckets())
    }

    pub fn expand_asn(&self, cidrs_by_asn: &BTreeMap<String, Vec<String>>) -> Self {
        if self.referenced_asns().is_empty() {
            return self.clone();
        }
        Self {
            block_ip: asn::expand(&self.block_ip, cidrs_by_asn),
            direct_ip: asn::expand(&self.direct_ip, cidrs_by_asn),
            proxy_ip: asn::expand(&self.proxy_ip, cidrs_by_asn),
            ..self.clone()
        }
    }

    pub fn needs_geo_files(&self) -> bool {
        self.site_buckets().any(|s| starts_ci(s, "geosite:")) || self.ip_buckets().any(|s| starts_ci(s, "geoip:"))
    }

    /// Every `geoip:`/`geosite:` selector removed (Xray fallback when the `.dat` files are missing).
    pub fn without_geo_selectors(&self) -> Self {
        let strip = |l: &Vec<String>| -> Vec<String> {
            l.iter().filter(|s| !starts_ci(s.trim(), "geoip:") && !starts_ci(s.trim(), "geosite:")).cloned().collect()
        };
        Self {
            block_ip: strip(&self.block_ip),
            block_sites: strip(&self.block_sites),
            direct_ip: strip(&self.direct_ip),
            direct_sites: strip(&self.direct_sites),
            proxy_ip: strip(&self.proxy_ip),
            proxy_sites: strip(&self.proxy_sites),
            ..self.clone()
        }
    }

    pub fn display_name(&self) -> String {
        if self.name.trim().is_empty() {
            "Routing profile".into()
        } else {
            self.name.clone()
        }
    }

    pub fn rule_count(&self) -> usize {
        self.ip_buckets().count() + self.site_buckets().count()
    }

    /// `happ://routing/add/<base64url-json>` (defaults and the local id left out).
    pub fn to_happ_link(&self) -> String {
        let json = without_defaults(&Self { id: String::new(), ..self.clone() }).to_string();
        format!("{HAPP_SCHEME}{}", URL_SAFE_NO_PAD.encode(json))
    }

    /// The built-in "Russia direct" profile seeded on first run.
    pub fn russia_direct() -> Self {
        Self {
            name: "Россия напрямую".into(),
            direct_sites: vec!["domain:ru".into()],
            direct_ip: vec!["geoip:ru".into()],
            id: DEFAULT_RU_DIRECT_ID.into(),
            ..Default::default()
        }
    }

    /// Buckets in `route_order` (unknown tokens ignored; none known → block, direct, proxy).
    pub fn ordered_buckets(&self) -> Vec<&'static str> {
        let out: Vec<&'static str> = self
            .route_order
            .split('-')
            .filter_map(|t| ["block", "direct", "proxy"].into_iter().find(|b| t.trim().eq_ignore_ascii_case(b)))
            .collect();
        if out.is_empty() {
            vec!["block", "direct", "proxy"]
        } else {
            out
        }
    }
}

// ---------------- Happ / routing:// links ----------------

pub const HAPP_SCHEME: &str = "happ://routing/add/";
const ROUTING_SCHEMES: [&str; 3] = ["routing://routing/add/", "routing://add/", "routing://"];
const ROUTING_KEYS: [&str; 10] =
    ["directsites", "proxysites", "blocksites", "directip", "proxyip", "blockip", "routeorder", "globalproxy", "dnshosts", "domainstrategy"];

fn scheme_prefix(link: &str) -> Option<&'static str> {
    let t = link.trim();
    if starts_ci(t, HAPP_SCHEME) {
        return Some(HAPP_SCHEME);
    }
    ROUTING_SCHEMES.into_iter().find(|s| starts_ci(t, s))
}

pub fn is_routing_link(link: &str) -> bool {
    scheme_prefix(link).is_some()
}

/// Happ writes PascalCase keys (`DirectSites`, `GlobalProxy`); the profile keys are lower case.
fn lowercase_keys(v: Value) -> Option<serde_json::Map<String, Value>> {
    match v {
        Value::Object(m) => Some(m.into_iter().map(|(k, v)| (k.to_ascii_lowercase(), v)).collect()),
        _ => None,
    }
}

/// Raw routing JSON: an object carrying routing-profile keys.
pub fn is_routing_json(text: &str) -> bool {
    let t = text.trim();
    t.starts_with('{')
        && serde_json::from_str::<Value>(t)
            .ok()
            .and_then(lowercase_keys)
            .is_some_and(|m| m.keys().any(|k| ROUTING_KEYS.contains(&k.as_str())))
}

pub fn looks_like_routing_profile(text: &str) -> bool {
    is_routing_link(text) || is_routing_json(text)
}

fn decode_profile(json: &str) -> Option<RoutingProfile> {
    let map = lowercase_keys(serde_json::from_str(json.trim()).ok()?)?;
    serde_json::from_value(Value::Object(map)).ok()
}

/// A `happ://routing/add/…` or `routing://…` link → profile.
pub fn parse_routing_link(link: &str) -> Option<RoutingProfile> {
    let t = link.trim();
    let scheme = scheme_prefix(t)?;
    let rest = &t[scheme.len()..];
    let payload = rest.split('#').next()?.split('?').next()?.trim();
    if payload.is_empty() {
        return None;
    }
    decode_profile(&decode_base64_chunk(payload)?)
}

pub fn parse_routing_json(text: &str) -> Option<RoutingProfile> {
    is_routing_json(text).then(|| decode_profile(text)).flatten()
}

/// Either form (link or raw JSON).
pub fn parse_routing_profile(text: &str) -> Option<RoutingProfile> {
    parse_routing_link(text).or_else(|| parse_routing_json(text))
}

// ---------------- RoutingProfilesState ----------------

pub const ROUTING_LINK_PREFIX: &str = "yptun://routing/";

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct RoutingProfilesState {
    pub profiles: Vec<RoutingProfile>,
    /// Applied to every connection by default; blank = none.
    pub global_profile_id: String,
    pub geoip_url: String,
    pub geosite_url: String,
    pub singbox_geosite_base: String,
    pub singbox_geoip_base: String,
    /// epoch-ms of the last successful `.dat` download (0 = never).
    pub geo_last_updated: i64,
}

impl Default for RoutingProfilesState {
    fn default() -> Self {
        Self {
            profiles: vec![],
            global_profile_id: String::new(),
            geoip_url: DEFAULT_GEOIP_URL.into(),
            geosite_url: DEFAULT_GEOSITE_URL.into(),
            singbox_geosite_base: String::new(),
            singbox_geoip_base: String::new(),
            geo_last_updated: 0,
        }
    }
}

impl RoutingProfilesState {
    /// Per-location id → global id → none (the `__none__` sentinel disables routing).
    pub fn resolve(&self, location_profile_id: Option<&str>) -> Option<&RoutingProfile> {
        let own = location_profile_id.map(str::trim).unwrap_or("");
        let id = if own.is_empty() { self.global_profile_id.as_str() } else { own };
        if id.trim().is_empty() || id == NONE_ID {
            return None;
        }
        self.profiles.iter().find(|p| p.id == id)
    }

    pub fn profile_by_id(&self, id: Option<&str>) -> Option<&RoutingProfile> {
        let id = id.filter(|s| !s.trim().is_empty())?;
        self.profiles.iter().find(|p| p.id == id)
    }

    pub fn any_needs_geo_files(&self) -> bool {
        self.profiles.iter().any(RoutingProfile::needs_geo_files)
    }

    /// The whole routing setup as `yptun://routing/<base64url-json>`.
    pub fn to_routing_link(&self) -> String {
        let mut v = without_defaults(self);
        if let Some(Value::Array(list)) = v.get_mut("profiles") {
            for p in list.iter_mut() {
                if let Ok(profile) = serde_json::from_value::<RoutingProfile>(p.clone()) {
                    *p = without_defaults(&profile);
                }
            }
        }
        format!("{ROUTING_LINK_PREFIX}{}", URL_SAFE_NO_PAD.encode(v.to_string()))
    }

    pub fn from_routing_link(link: &str) -> Option<Self> {
        let t = link.trim();
        if !starts_ci(t, ROUTING_LINK_PREFIX) {
            return None;
        }
        let payload = t[ROUTING_LINK_PREFIX.len()..].split('#').next()?.split('?').next()?.trim();
        if payload.is_empty() {
            return None;
        }
        serde_json::from_str(&decode_base64_chunk(payload)?).ok()
    }
}

// ---------------- SingBoxRule ----------------

pub const OUT_PROXY: &str = "proxy";
pub const OUT_DIRECT: &str = "direct";
pub const OUT_BLOCK: &str = "block";
pub const ACTION_ROUTE: &str = "route";
pub const ACTION_ROUTE_OPTIONS: &str = "route-options";
pub const ACTION_SNIFF: &str = "sniff";
pub const ACTION_RESOLVE: &str = "resolve";
pub const ACTION_HIJACK_DNS: &str = "hijack-dns";
pub const ACTION_REJECT: &str = "reject";
pub const ACTIONS: [&str; 6] = [ACTION_ROUTE, ACTION_ROUTE_OPTIONS, ACTION_SNIFF, ACTION_RESOLVE, ACTION_HIJACK_DNS, ACTION_REJECT];
pub const OUTBOUNDS: [&str; 3] = [OUT_PROXY, OUT_DIRECT, OUT_BLOCK];
pub const RULE_NETWORKS: [&str; 3] = ["", "tcp", "udp"];
pub const RULE_PROTOCOLS: [&str; 4] = ["http", "tls", "quic", "bittorrent"];
pub const NETWORK_TYPES: [&str; 4] = ["wifi", "cellular", "ethernet", "other"];

/// One v2rayNG-style sing-box rule: populated fields are ANDed, entries inside a field ORed.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct SingBoxRule {
    pub name: String,
    pub action: String,
    pub outbound: String,
    pub domains: Vec<String>,
    pub ip: Vec<String>,
    pub source: Vec<String>,
    pub port: String,
    pub source_port: String,
    pub network: String,
    pub network_type: Vec<String>,
    pub protocol: Vec<String>,
    pub client: Vec<String>,
    pub network_is_expensive: bool,
    pub clash_mode: String,
    pub package_names: Vec<String>,
    pub package_regex: Vec<String>,
    #[serde(default = "yes")]
    pub enabled: bool,
}

impl Default for SingBoxRule {
    fn default() -> Self {
        Self {
            name: String::new(),
            action: ACTION_ROUTE.into(),
            outbound: OUT_PROXY.into(),
            domains: vec![],
            ip: vec![],
            source: vec![],
            port: String::new(),
            source_port: String::new(),
            network: String::new(),
            network_type: vec![],
            protocol: vec![],
            client: vec![],
            network_is_expensive: false,
            clash_mode: String::new(),
            package_names: vec![],
            package_regex: vec![],
            enabled: true,
        }
    }
}

impl SingBoxRule {
    /// At least one matcher (otherwise the rule would match everything).
    pub fn has_matcher(&self) -> bool {
        !self.domains.is_empty()
            || !self.ip.is_empty()
            || !self.source.is_empty()
            || !self.port.trim().is_empty()
            || !self.source_port.trim().is_empty()
            || !self.network.trim().is_empty()
            || !self.protocol.is_empty()
            || !self.network_type.is_empty()
            || !self.client.is_empty()
            || self.network_is_expensive
            || !self.clash_mode.trim().is_empty()
            || !self.package_names.is_empty()
            || !self.package_regex.is_empty()
    }

    pub fn list_to_text(list: &[String]) -> String {
        list.join("\n")
    }

    pub fn text_to_list(text: &str) -> Vec<String> {
        text.split(['\n', '\r', ',', ';']).map(str::trim).filter(|s| !s.is_empty()).map(str::to_string).collect()
    }

    pub fn collect_asns(rules: &[SingBoxRule]) -> Vec<String> {
        asn::collect(rules.iter().flat_map(|r| &r.ip))
    }

    pub fn expand_asn(rules: &[SingBoxRule], cidrs_by_asn: &BTreeMap<String, Vec<String>>) -> Vec<SingBoxRule> {
        rules
            .iter()
            .map(|r| {
                if r.ip.iter().any(|s| asn::is_selector(s)) {
                    SingBoxRule { ip: asn::expand(&r.ip, cidrs_by_asn), ..r.clone() }
                } else {
                    r.clone()
                }
            })
            .collect()
    }

    /// Merges installed packages matching `package_regex` into `package_names` (invalid patterns ignored).
    pub fn expand_package_regex(rules: &[SingBoxRule], installed: &[String]) -> Vec<SingBoxRule> {
        rules
            .iter()
            .map(|r| {
                let regexes: Vec<regex_lite::Regex> = r.package_regex.iter().filter_map(|p| regex_lite::Regex::new(p).ok()).collect();
                if regexes.is_empty() {
                    return r.clone();
                }
                let mut names = r.package_names.clone();
                for pkg in installed.iter().filter(|p| regexes.iter().any(|re| re.is_match(p))) {
                    if !names.contains(pkg) {
                        names.push(pkg.clone());
                    }
                }
                SingBoxRule { package_names: names, ..r.clone() }
            })
            .collect()
    }
}

// ---------------- RoutingRules ----------------

/// Global routing rules (toggles + advanced sing-box JSON + structured rules).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct RoutingRules {
    #[serde(default = "yes")]
    pub bypass_lan: bool,
    pub block_ads: bool,
    pub bypass_russia: bool,
    pub direct_domains: Vec<String>,
    pub block_domains: Vec<String>,
    /// A verbatim sing-box `route.rules` array. Blank = unused.
    pub custom_rules_json: String,
    /// A verbatim sing-box `route.rule_set` array. Blank = unused.
    pub custom_rule_sets_json: String,
    pub rules: Vec<SingBoxRule>,
}

impl Default for RoutingRules {
    fn default() -> Self {
        Self {
            bypass_lan: true,
            block_ads: false,
            bypass_russia: false,
            direct_domains: vec![],
            block_domains: vec![],
            custom_rules_json: String::new(),
            custom_rule_sets_json: String::new(),
            rules: vec![],
        }
    }
}

impl RoutingRules {
    /// Free text (newline/comma/space/semicolon separated) → clean domain suffixes.
    pub fn parse_domains(text: &str) -> Vec<String> {
        text.split(['\n', '\r', ',', ' ', ';'])
            .map(|s| {
                let s = s.trim();
                let s = s.strip_prefix("https://").unwrap_or(s);
                let s = s.strip_prefix("http://").unwrap_or(s);
                s.trim_end_matches('/').to_string()
            })
            .filter(|s| !s.is_empty())
            .collect()
    }

    pub fn domains_to_text(list: &[String]) -> String {
        list.join("\n")
    }

    /// Blank or a JSON array.
    pub fn is_valid_rules_json(text: &str) -> bool {
        text.trim().is_empty() || matches!(serde_json::from_str::<Value>(text), Ok(Value::Array(_)))
    }
}

// ---------------- TrafficSettings ----------------

pub const STRATEGIES: [&str; 4] = ["prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only"];
pub const MUX_PROTOCOLS: [&str; 3] = ["h2mux", "smux", "yamux"];

/// Global traffic/transport tuning ("Настройки трафика").
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct TrafficSettings {
    pub remote_dns: String,
    pub remote_dns2: String,
    pub direct_dns: String,
    pub domain_strategy: String,
    pub mux_enabled: bool,
    pub mux_protocol: String,
    pub mux_max_connections: i32,
    pub fragment_enabled: bool,
    pub fragment_packets: String,
    pub fragment_length: String,
    pub fragment_interval: String,
    pub mtu: i32,
    pub block_ru_domains: bool,
    /// Vestigial: FakeDNS is per-config now; `normalized()` forces it off.
    pub fake_dns_enabled: bool,
}

impl Default for TrafficSettings {
    fn default() -> Self {
        Self {
            remote_dns: "8.8.8.8".into(),
            remote_dns2: String::new(),
            direct_dns: "77.88.8.8".into(),
            domain_strategy: "ipv4_only".into(),
            mux_enabled: false,
            mux_protocol: "h2mux".into(),
            mux_max_connections: 8,
            fragment_enabled: false,
            fragment_packets: "tlshello".into(),
            fragment_length: "50-100".into(),
            fragment_interval: "10-20".into(),
            mtu: 1500,
            block_ru_domains: false,
            fake_dns_enabled: false,
        }
    }
}

fn or_default(s: &str, d: &str) -> String {
    let t = s.trim();
    if t.is_empty() {
        d.into()
    } else {
        t.into()
    }
}

impl TrafficSettings {
    pub fn normalized(&self) -> Self {
        let direct = self.direct_dns.trim();
        Self {
            fake_dns_enabled: false,
            remote_dns: or_default(&self.remote_dns, "8.8.8.8"),
            remote_dns2: self.remote_dns2.trim().into(),
            direct_dns: if direct.is_empty() || direct == "223.5.5.5" { "77.88.8.8".into() } else { direct.into() },
            domain_strategy: if STRATEGIES.contains(&self.domain_strategy.as_str()) { self.domain_strategy.clone() } else { "ipv4_only".into() },
            mux_protocol: if MUX_PROTOCOLS.contains(&self.mux_protocol.as_str()) { self.mux_protocol.clone() } else { "h2mux".into() },
            mux_max_connections: self.mux_max_connections.clamp(1, 64),
            fragment_packets: or_default(&self.fragment_packets, "tlshello"),
            fragment_length: or_default(&self.fragment_length, "50-100"),
            fragment_interval: or_default(&self.fragment_interval, "10-20"),
            mtu: self.mtu.clamp(1280, 9000),
            ..self.clone()
        }
    }

    /// Xray DNS `queryStrategy` for `domain_strategy` (prefer_ipv4 is treated as ipv4_only).
    pub fn xray_query_strategy(&self) -> &'static str {
        match self.domain_strategy.as_str() {
            "ipv4_only" | "prefer_ipv4" => "UseIPv4",
            "ipv6_only" => "UseIPv6",
            _ => "UseIP",
        }
    }
}
