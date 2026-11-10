//! Text → locations: everything `LocationsRepositoryImpl` does between "here is a pasted text or a
//! downloaded subscription body" and "here is a bundle to merge" (link families, raw Xray/sing-box
//! JSON, olcRTC subscriptions, exported bundles, panel metadata), plus the bundle merge and the
//! subscription refresh bookkeeping. No IO: the repository does the fetching and storage.

use std::collections::{BTreeMap, HashSet};

use serde_json::{json, Map, Value};

use crate::crypto;
use crate::isotime;
use crate::location::*;
use crate::model::*;
use crate::panel;
use crate::share_link;
use crate::subscription::{decode_base64_chunk, to_link_text};
use crate::transports::*;
use crate::uri::percent_decode;

type Obj = Map<String, Value>;

const OLCRTC_PREFIX: &str = "olcrtc://";
const UTF8_BOM: char = '\u{FEFF}';
const PROXY_PROTOCOLS: [&str; 4] = ["vless", "vmess", "trojan", "shadowsocks"];

/// Additive = a pasted config / subscription; Restore = a whole exported bundle (may replace by id).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ImportMode {
    Additive,
    Restore,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ParsedImport {
    pub bundle: LocationBundleV4,
    pub mode: ImportMode,
}

// ---------------- small helpers ----------------

fn not_blank(s: &str) -> bool {
    !s.trim().is_empty()
}

pub fn normalized_import_text(text: &str) -> String {
    text.trim().trim_start_matches(UTF8_BOM).trim().to_string()
}

pub fn is_http_url(text: &str) -> bool {
    let v = text.trim().to_lowercase();
    v.starts_with("http://") || v.starts_with("https://")
}

fn starts_ci(s: &str, p: &str) -> bool {
    s.len() >= p.len() && s.is_char_boundary(p.len()) && s[..p.len()].eq_ignore_ascii_case(p)
}

/// lowercase, runs of anything but `[a-z0-9_-]` → `_`, trimmed of `_`, ≤ 32 chars, never blank.
pub fn storage_slug(text: &str) -> String {
    let lower = text.to_lowercase();
    let mut out = String::new();
    let mut in_bad = false;
    for c in lower.chars() {
        if c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_' || c == '-' {
            out.push(c);
            in_bad = false;
        } else if !in_bad {
            out.push('_');
            in_bad = true;
        }
    }
    let t: String = out.trim_matches('_').chars().take(32).collect();
    if t.trim().is_empty() {
        "location".into()
    } else {
        t
    }
}

fn location_slug(c: &LocationConfig) -> String {
    let n = c.display_name();
    storage_slug(if n.trim().is_empty() { &c.id } else { &n })
}

/// Kotlin's `map { if (it.isLetterOrDigit()) it else '_' }` on a lower-cased endpoint.
fn endpoint_base(text: &str) -> String {
    text.to_lowercase().chars().map(|c| if c.is_alphanumeric() { c } else { '_' }).collect()
}

pub fn unique_storage_id(base: &str, used: &mut HashSet<String>) -> String {
    let base = storage_slug(base);
    let mut candidate = base.clone();
    let mut suffix = 2;
    while !used.insert(candidate.clone()) {
        candidate = format!("{base}_{suffix}");
        suffix += 1;
    }
    candidate
}

fn first_not_blank(values: &[Option<&str>]) -> String {
    values.iter().flatten().find(|v| not_blank(v)).map(|v| v.to_string()).unwrap_or_default()
}

fn s<'a>(o: &'a Obj, k: &str) -> Option<&'a str> {
    o.get(k).and_then(Value::as_str)
}

fn i(o: &Obj, k: &str) -> Option<i32> {
    o.get(k).and_then(Value::as_i64).and_then(|v| i32::try_from(v).ok())
}

fn bundle_of(entries: Vec<LocationEntry>) -> Option<LocationBundleV4> {
    if entries.is_empty() {
        return None;
    }
    Some(LocationBundleV4 { active_location_id: Some(entries[0].storage_id.clone()), locations: entries, ..Default::default() })
}

fn entry(storage_id: &str, location: &LocationConfig, subscription_url: Option<&str>, metadata: Option<LocationMetadata>) -> LocationEntry {
    LocationEntry::from(storage_id, location, subscription_url.map(str::to_string), metadata)
}

/// The entry with its location changed (subscription URL and metadata kept).
pub fn with_location(e: &LocationEntry, f: impl FnOnce(&mut LocationConfig)) -> LocationEntry {
    let mut loc = e.location();
    f(&mut loc);
    LocationEntry { ..LocationEntry::from(&e.storage_id, &loc, e.subscription_url.clone(), e.metadata.clone()) }
}

// ---------------- metadata helpers ----------------

pub fn with_subscription_interval(meta: Option<SubscriptionMetadata>, hours: Option<i32>) -> Option<SubscriptionMetadata> {
    let Some(h) = hours else { return meta };
    Some(SubscriptionMetadata { update_interval_hours: Some(h), ..meta.unwrap_or_default() }.normalized())
}

fn loc_meta_with_interval(meta: Option<LocationMetadata>, hours: Option<i32>) -> Option<LocationMetadata> {
    let Some(h) = hours else { return meta };
    let sub = meta.as_ref().and_then(|m| m.subscription.clone());
    Some(refresh_state(
        meta,
        h,
        sub.as_ref().and_then(|s| s.last_refresh_at_epoch_ms),
        sub.as_ref().and_then(|s| s.last_attempt_at_epoch_ms),
        sub.as_ref().map_or(true, |s| s.auto_update_enabled),
        sub.as_ref().and_then(|s| s.custom_name.clone()),
        sub.as_ref().and_then(|s| s.cascade_link.clone()),
    ))
}

/// The subscription part of `meta` with refresh bookkeeping replaced.
pub fn refresh_state(
    meta: Option<LocationMetadata>,
    interval: i32,
    last_refresh: Option<i64>,
    last_attempt: Option<i64>,
    auto_update: bool,
    custom_name: Option<String>,
    cascade_link: Option<String>,
) -> LocationMetadata {
    let m = meta.unwrap_or_default();
    let sub = m.subscription.clone().unwrap_or_default();
    LocationMetadata {
        subscription: Some(SubscriptionMetadata {
            update_interval_hours: Some(interval),
            last_refresh_at_epoch_ms: last_refresh,
            last_attempt_at_epoch_ms: last_attempt,
            auto_update_enabled: auto_update,
            custom_name,
            cascade_link,
            ..sub
        }),
        ..m
    }
    .normalized()
}

/// `meta` with only the subscription changed by `f`.
pub fn edit_subscription(meta: Option<LocationMetadata>, f: impl FnOnce(&mut SubscriptionMetadata)) -> LocationMetadata {
    let mut m = meta.unwrap_or_default();
    let mut sub = m.subscription.take().unwrap_or_default();
    f(&mut sub);
    m.subscription = Some(sub);
    m.normalized()
}

/// The panel's subscription metadata on an imported location; a family that built its own
/// metadata without a title (olcRTC's `#refresh:`) still joins the panel's subscription.
pub fn with_panel_subscription(e: LocationEntry, sub: Option<&LocationMetadata>) -> LocationEntry {
    let Some(panel) = sub.and_then(|s| s.subscription.clone()) else { return e };
    let Some(own) = e.metadata.as_ref().and_then(|m| m.subscription.clone()) else {
        return LocationEntry { metadata: sub.cloned(), ..e };
    };
    if own.name.as_deref().is_some_and(not_blank) {
        return e;
    }
    let merged = SubscriptionMetadata {
        update: panel.update.clone().or(own.update),
        refresh: panel.refresh.clone().or(own.refresh),
        color: panel.color.clone().or(own.color),
        icon: panel.icon.clone().or(own.icon),
        used: panel.used.clone().or(own.used),
        available: panel.available.clone().or(own.available),
        ..panel
    };
    let m = LocationMetadata { subscription: Some(merged), ..e.metadata.clone().unwrap_or_default() };
    LocationEntry { metadata: Some(m), ..e }
}

// ---------------- panel metadata ----------------

/// A header value Remnawave may send plain or `base64:`-prefixed.
pub fn decode_maybe_base64_header(raw: &str) -> Option<String> {
    let v = raw.trim();
    if v.is_empty() {
        return None;
    }
    let decoded = match v.strip_prefix("base64:") {
        Some(p) => decode_base64_chunk(p.trim()).unwrap_or_else(|| p.trim().to_string()),
        None => v.to_string(),
    };
    let d = decoded.trim();
    (!d.is_empty()).then(|| d.to_string())
}

/// "230.4 GB"-style traffic text.
pub fn format_traffic_bytes(bytes: i64) -> String {
    if bytes <= 0 {
        return "0 B".into();
    }
    let units = ["B", "KB", "MB", "GB", "TB", "PB"];
    let mut value = bytes as f64;
    let mut unit = 0;
    while value >= 1024.0 && unit < units.len() - 1 {
        value /= 1024.0;
        unit += 1;
    }
    let rounded = (value * 10.0) as i64 as f64 / 10.0;
    let text = if rounded % 1.0 == 0.0 { (rounded as i64).to_string() } else { format!("{rounded}") };
    format!("{text} {}", units[unit])
}

/// Response headers of a subscription fetch (already decoded).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct PanelHeaders {
    pub profile_title: Option<String>,
    pub user_info: Option<String>,
    pub support_url: Option<String>,
    pub web_page_url: Option<String>,
    pub icon_url: Option<String>,
    pub announce: Option<String>,
    pub provider_id: Option<String>,
}

/// Name/traffic/expiry/links from Remnawave/3x-ui/Marzban headers (`subscription-userinfo` etc.).
pub fn subscription_metadata_from_headers(h: &PanelHeaders) -> Option<SubscriptionMetadata> {
    let name = h.profile_title.as_deref().map(str::trim).filter(|v| !v.is_empty()).map(str::to_string);
    let (mut used, mut available, mut expires) = (None, None, None);
    if let Some(info) = h.user_info.as_deref().filter(|v| not_blank(v)) {
        let fields: BTreeMap<String, String> = info
            .split(';')
            .filter_map(|p| p.split_once('=').map(|(k, v)| (k.trim().to_lowercase(), v.trim().to_string())))
            .collect();
        let num = |k: &str| fields.get(k).and_then(|v| v.parse::<i64>().ok());
        let (upload, download, total) = (num("upload").unwrap_or(0), num("download").unwrap_or(0), num("total"));
        if fields.contains_key("upload") || fields.contains_key("download") || total.is_some() {
            used = Some(format_traffic_bytes(upload + download));
            available = Some(match total {
                Some(t) if t > 0 => format_traffic_bytes(t),
                _ => "∞".into(),
            });
        }
        expires = num("expire").filter(|e| *e > 0).map(|e| e * 1000);
    }
    let clean = |v: &Option<String>| v.as_deref().map(str::trim).filter(|v| !v.is_empty()).map(str::to_string);
    let icon = clean(&h.icon_url).filter(|u| u.starts_with("http://") || u.starts_with("https://"));
    let (support, web, announce, provider) = (clean(&h.support_url), clean(&h.web_page_url), clean(&h.announce), clean(&h.provider_id));
    if name.is_none() && used.is_none() && available.is_none() && expires.is_none() && support.is_none() && web.is_none() && icon.is_none() && announce.is_none() && provider.is_none() {
        return None;
    }
    Some(
        SubscriptionMetadata {
            name,
            used,
            available,
            expires_at_epoch_ms: expires,
            support_url: support,
            web_page_url: web,
            icon_url: icon,
            announce,
            provider_id: provider,
            ..Default::default()
        }
        .normalized(),
    )
}

/// Expiry + human traffic from a Remnawave JSON body (`{user:{…}}` or `/info`'s `{response:{user:{…}}}`).
pub fn subscription_metadata_from_body(content: &str) -> Option<SubscriptionMetadata> {
    let t = content.trim();
    if !t.starts_with('{') {
        return None;
    }
    let root: Value = serde_json::from_str(t).ok()?;
    let user = root.get("response").and_then(|r| r.get("user")).and_then(Value::as_object).or_else(|| root.get("user").and_then(Value::as_object))?;
    let expires = isotime::parse_iso_to_epoch_ms(s(user, "expiresAt"));
    let used = s(user, "trafficUsed").map(str::trim).filter(|v| !v.is_empty() && *v != "0").map(str::to_string);
    let limit = s(user, "trafficLimit").map(str::trim);
    let available = match limit {
        None | Some("") | Some("0") | Some("0 B") => used.as_ref().map(|_| "∞".to_string()),
        Some(l) => Some(l.to_string()),
    };
    if expires.is_none() && used.is_none() && available.is_none() {
        return None;
    }
    let m = SubscriptionMetadata { used, available, expires_at_epoch_ms: expires, ..Default::default() }.normalized();
    (!m.is_empty()).then_some(m)
}

/// Field-wise: `primary` wins, `secondary` fills gaps; the name prefers the header title.
pub fn merge_subscription_metadata(primary: Option<SubscriptionMetadata>, secondary: Option<SubscriptionMetadata>) -> Option<SubscriptionMetadata> {
    let (p, sec) = match (primary, secondary) {
        (None, s) => return s,
        (p, None) => return p,
        (Some(p), Some(s)) => (p, s),
    };
    let m = SubscriptionMetadata {
        name: sec.name.clone().or(p.name.clone()),
        update: p.update.or(sec.update),
        refresh: p.refresh.or(sec.refresh),
        color: p.color.or(sec.color),
        icon: p.icon.or(sec.icon),
        used: p.used.or(sec.used),
        available: p.available.or(sec.available),
        update_interval_hours: p.update_interval_hours.or(sec.update_interval_hours),
        last_refresh_at_epoch_ms: p.last_refresh_at_epoch_ms.or(sec.last_refresh_at_epoch_ms),
        expires_at_epoch_ms: p.expires_at_epoch_ms.or(sec.expires_at_epoch_ms),
        last_attempt_at_epoch_ms: p.last_attempt_at_epoch_ms.or(sec.last_attempt_at_epoch_ms),
        support_url: p.support_url.or(sec.support_url),
        web_page_url: p.web_page_url.or(sec.web_page_url),
        icon_url: p.icon_url.or(sec.icon_url),
        announce: p.announce.or(sec.announce),
        provider_id: p.provider_id.or(sec.provider_id),
        ..Default::default()
    }
    .normalized();
    (!m.is_empty()).then_some(m)
}

// ---------------- the import entry point ----------------

/// Parses `text` (already downloaded when it was a URL). Encrypted `yptun-enc://` lines are
/// decrypted and every resulting location is marked locked.
pub fn parse_import(text: &str, subscription_url: Option<&str>, update_interval_hours: Option<i32>, metadata: Option<SubscriptionMetadata>) -> Option<ParsedImport> {
    let decrypted = crypto::decrypt_subscription_lines(&to_link_text(text));
    let parsed = parse_import_plain(if decrypted.encrypted { &decrypted.text } else { text }, subscription_url, update_interval_hours, metadata)?;
    if !decrypted.encrypted {
        return Some(parsed);
    }
    let locations = parsed
        .bundle
        .locations
        .into_iter()
        .map(|e| {
            let m = LocationMetadata { locked: true, ..e.metadata.clone().unwrap_or_default() };
            LocationEntry { metadata: Some(m), ..e }.normalized()
        })
        .collect();
    Some(ParsedImport { bundle: LocationBundleV4 { locations, ..parsed.bundle }, ..parsed })
}

fn parse_import_plain(text: &str, sub_url: Option<&str>, interval: Option<i32>, metadata: Option<SubscriptionMetadata>) -> Option<ParsedImport> {
    // One subscription may mix link families: every line parser sees the same text.
    let link_text = to_link_text(text);
    let mut bundles: Vec<LocationBundleV4> = Vec::new();
    bundles.extend(parse_yptun_inbound_text(&link_text, sub_url));
    bundles.extend(parse_olcrtc_text(&link_text, sub_url, interval));
    bundles.extend(parse_freeturn_text(&link_text, sub_url));
    bundles.extend(parse_qwdtt_text(&link_text, sub_url));
    bundles.extend(parse_csqtt_text(&link_text, sub_url));
    bundles.extend(parse_openflux_text(&link_text, sub_url));
    bundles.extend(parse_masterdns_text(&link_text, sub_url));
    bundles.extend(parse_snolc_text(&link_text, sub_url));

    if bundles.is_empty() {
        if let Some(b) = parse_amneziawg_text(text, sub_url) {
            return Some(ParsedImport { bundle: b, mode: ImportMode::Additive });
        }
        // Full raw Xray config(s) before the proxy/sing-box parsers, or their dns/routing is lost.
        if let Some(b) = parse_raw_xray(text, sub_url) {
            return Some(ParsedImport { bundle: b, mode: ImportMode::Additive });
        }
    }
    bundles.extend(parse_proxy_text(&link_text, sub_url, metadata.as_ref()));

    if let Some(merged) = merge_parsed_bundles(bundles) {
        let sub = metadata.map(|m| LocationMetadata { subscription: Some(m), ..Default::default() });
        // `#desc:<name>=<text>` and `#order:<name>` panel lines (both percent-encoded).
        let lines: Vec<&str> = link_text.lines().map(str::trim).collect();
        let desc_by_name: BTreeMap<String, String> = lines
            .iter()
            .filter_map(|l| l.strip_prefix("#desc:"))
            .filter_map(|r| r.trim().split_once('='))
            .map(|(n, d)| (percent_decode(n.trim()), percent_decode(d.trim())))
            .collect();
        let order: Vec<String> = lines.iter().filter_map(|l| l.strip_prefix("#order:")).map(|n| percent_decode(n.trim())).collect();
        let mut locations = merged.locations.clone();
        if !order.is_empty() {
            locations.sort_by_key(|e| order.iter().position(|n| *n == e.name).unwrap_or(usize::MAX));
        }
        let locations = locations
            .into_iter()
            .map(|e| {
                let e = with_panel_subscription(e, sub.as_ref());
                match desc_by_name.get(&e.name) {
                    Some(d) if e.description.trim().is_empty() => LocationEntry { description: d.clone(), ..e },
                    _ => e,
                }
            })
            .collect();
        return Some(ParsedImport { bundle: LocationBundleV4 { locations, ..merged }, mode: ImportMode::Additive });
    }

    if let Some(b) = parse_raw_singbox(text, sub_url) {
        return Some(ParsedImport { bundle: b, mode: ImportMode::Additive });
    }
    if !text.starts_with('{') || !text.ends_with('}') {
        return None;
    }
    let root = match serde_json::from_str::<Value>(text) {
        Ok(Value::Object(o)) => o,
        _ => return None,
    };
    if let Some(b) = parse_bundle(&root, sub_url, interval) {
        return Some(ParsedImport { bundle: b, mode: ImportMode::Restore });
    }
    let e = parse_single_location(&root, None, sub_url)?;
    let e = LocationEntry { metadata: loc_meta_with_interval(e.metadata.clone(), interval), ..e }.normalized();
    Some(ParsedImport {
        bundle: LocationBundleV4 { active_location_id: Some(e.storage_id.clone()), locations: vec![e], ..Default::default() },
        mode: ImportMode::Additive,
    })
}

/// Concatenates one import's per-family bundles, keeping storage ids unique; the first is active.
fn merge_parsed_bundles(bundles: Vec<LocationBundleV4>) -> Option<LocationBundleV4> {
    if bundles.len() <= 1 {
        return bundles.into_iter().next();
    }
    let mut used = HashSet::new();
    let locations: Vec<LocationEntry> = bundles
        .into_iter()
        .flat_map(|b| b.locations)
        .map(|e| {
            let id = unique_storage_id(&e.storage_id, &mut used);
            LocationEntry { storage_id: id, ..e }
        })
        .collect();
    bundle_of(locations)
}

/// Merges an import into the current bundle. Additive keeps the current selection; Restore may
/// replace entries with the same id and brings its own active id.
pub fn merge_imported_bundle(current: Option<&LocationBundleV4>, imported: &LocationBundleV4, replace_matching: bool) -> LocationBundleV4 {
    let Some(cur) = current.map(LocationBundleV4::normalized).filter(|b| !b.locations.is_empty()) else { return imported.clone() };
    let current_ids: HashSet<String> = cur.locations.iter().map(|e| e.storage_id.clone()).collect();
    let mut existing = current_ids.clone();
    let by_id: BTreeMap<&str, &LocationEntry> = if replace_matching { imported.locations.iter().map(|e| (e.storage_id.as_str(), e)).collect() } else { BTreeMap::new() };
    let replaced: HashSet<&str> = by_id.keys().copied().filter(|k| current_ids.contains(*k)).collect();
    let mut merged: Vec<LocationEntry> = cur.locations.iter().map(|e| by_id.get(e.storage_id.as_str()).map(|n| (*n).clone()).unwrap_or_else(|| e.clone())).collect();
    let mut id_map: BTreeMap<String, String> = BTreeMap::new();
    for e in &imported.locations {
        if replace_matching && replaced.contains(e.storage_id.as_str()) {
            continue;
        }
        let id = unique_storage_id(&e.storage_id, &mut existing);
        id_map.insert(e.storage_id.clone(), id.clone());
        merged.push(LocationEntry { storage_id: id, ..e.clone() }.normalized());
    }
    let has = |id: &str| merged.iter().any(|e| e.storage_id == id);
    let imported_active = imported.active_location_id.as_ref().map(|id| id_map.get(id).cloned().unwrap_or_else(|| id.clone())).filter(|id| has(id));
    let current_active = cur.active_location_id.clone().filter(|id| has(id));
    let active = if replace_matching { imported_active.or(current_active) } else { current_active.or(imported_active) }.or_else(|| merged.first().map(|e| e.storage_id.clone()));
    LocationBundleV4 { active_location_id: active, locations: merged, ..cur }
}

// ---------------- link families ----------------

fn parse_yptun_inbound_text(text: &str, sub_url: Option<&str>) -> Option<LocationBundleV4> {
    if !text.contains(panel::YPTUN_INBOUND_PREFIX) {
        return None;
    }
    // One link wrapped across lines is still one link.
    let single: String = text.chars().filter(|c| !c.is_whitespace()).collect();
    let source = if single.starts_with(panel::YPTUN_INBOUND_PREFIX) && !single[1..].contains(panel::YPTUN_INBOUND_PREFIX) { single } else { text.to_string() };
    let mut used = HashSet::new();
    let entries = source
        .lines()
        .map(normalized_import_text)
        .filter(|l| l.starts_with(panel::YPTUN_INBOUND_PREFIX))
        .filter_map(|l| panel::parse_inbound(&l))
        .enumerate()
        .map(|(i, c)| {
            let loc = c.normalized();
            let base = location_slug_or(&loc, i);
            let id = unique_storage_id(&format!("imported_{base}"), &mut used);
            entry(&id, &loc, sub_url, None)
        })
        .collect();
    bundle_of(entries)
}

/// Kotlin's `storageSlug().ifBlank { "location_N" }`: the slug is never blank, so always the slug.
fn location_slug_or(loc: &LocationConfig, _index: usize) -> String {
    location_slug(loc)
}

fn parse_subscription_field(value: &str) -> Option<(String, String)> {
    let (k, v) = value.split_once(':')?;
    if k.is_empty() {
        return None;
    }
    Some((k.trim().to_lowercase(), v.trim().to_string()))
}

fn subscription_metadata_from_fields(f: &BTreeMap<String, String>) -> Option<SubscriptionMetadata> {
    let g = |k: &str| f.get(k).cloned();
    let m = SubscriptionMetadata { name: g("name"), update: g("update"), refresh: g("refresh"), color: g("color"), icon: g("icon"), used: g("used"), available: g("available"), ..Default::default() }.normalized();
    (!m.is_empty()).then_some(m)
}

/// olcRTC subscription (docs/sub.md): `#key: value` subscription fields, `##key: value` per-link fields.
fn parse_olcrtc_text(text: &str, sub_url: Option<&str>, interval: Option<i32>) -> Option<LocationBundleV4> {
    if !text.contains(OLCRTC_PREFIX) {
        return None;
    }
    let mut sub_fields: BTreeMap<String, String> = BTreeMap::new();
    let mut links: Vec<(panel::OlcRtcLink, BTreeMap<String, String>)> = Vec::new();
    for line in text.lines().map(normalized_import_text).filter(|l| !l.is_empty()) {
        if line.starts_with(OLCRTC_PREFIX) {
            if let Some(p) = panel::parse_olcrtc(&line) {
                links.push((p, BTreeMap::new()));
            }
        } else if let Some(rest) = line.strip_prefix("##") {
            if let (Some((k, v)), Some(last)) = (parse_subscription_field(rest), links.last_mut()) {
                last.1.insert(k, v);
            }
        } else if let Some(rest) = line.strip_prefix('#') {
            if let Some((k, v)) = parse_subscription_field(rest) {
                sub_fields.insert(k, v);
            }
        }
    }
    if links.is_empty() {
        return None;
    }
    let sub_meta = with_subscription_interval(subscription_metadata_from_fields(&sub_fields), interval.or_else(|| panel::refresh_interval_hours(sub_fields.get("refresh").map(String::as_str))));
    let mut used = HashSet::new();
    let entries = links
        .into_iter()
        .enumerate()
        .map(|(idx, (p, f))| {
            let g = |k: &str| f.get(k).cloned();
            let meta = LocationMetadata {
                name: g("name"),
                color: g("color"),
                icon: g("icon"),
                used: g("used"),
                available: g("available"),
                ip: g("ip"),
                comment: g("comment"),
                mimo: p.mimo.clone(),
                subscription: sub_meta.clone(),
                ..Default::default()
            }
            .normalized();
            let meta = (!meta.is_empty()).then_some(meta);
            let name = first_not_blank(&[meta.as_ref().and_then(|m| m.name.as_deref()), p.mimo.as_deref(), Some(&p.location.name)]);
            let loc = LocationConfig { name, ..p.location }.normalized();
            let id = unique_storage_id(&format!("imported_{}", location_slug_or(&loc, idx)), &mut used);
            entry(&id, &loc, sub_url, meta)
        })
        .collect();
    bundle_of(entries)
}

fn scheme_lines<'a>(text: &'a str, scheme: &'a str) -> impl Iterator<Item = &'a str> {
    text.trim().lines().map(str::trim).filter(move |l| starts_ci(l, scheme))
}

fn parse_freeturn_text(text: &str, sub_url: Option<&str>) -> Option<LocationBundleV4> {
    let mut used = HashSet::new();
    let entries = scheme_lines(text, panel::FREETURN_SCHEME).filter_map(panel::parse_freeturn).map(|l| freeturn_entry(&l, sub_url, &mut used)).collect();
    bundle_of(entries)
}

fn freeturn_entry(link: &panel::FreeturnLink, sub_url: Option<&str>, used: &mut HashSet<String>) -> LocationEntry {
    let name = if link.comment.trim().is_empty() { format!("VK-TURN {}", link.server_ip) } else { link.comment.clone() };
    let location = if link.mode == "tcp" {
        // The exit dials through the local freeturn tcp listener; SNI stays the real host.
        let base = share_link::parse(&link.exit_proxy_link).unwrap_or_default();
        let sni = if base.sni.trim().is_empty() { base.server.clone() } else { base.sni.clone() };
        LocationConfig {
            name: name.clone(),
            engine: EngineType::VkTurn,
            proxy: Some(ProxyProfile { tag: name.clone(), sni, server: "127.0.0.1".into(), server_port: DEFAULT_FREETURN_PORT, ..base }),
            vkturn: Some(VkTurnConfig {
                uri: link.uri.clone(),
                vk_link: link.vk_link.clone(),
                listen_port: DEFAULT_FREETURN_PORT,
                outbound: OUTBOUND_PROXY.into(),
                outbound_proxy_link: link.exit_proxy_link.clone(),
                ..Default::default()
            }),
            ..Default::default()
        }
    } else {
        LocationConfig {
            name: name.clone(),
            engine: EngineType::VkTurn,
            proxy: Some(ProxyProfile {
                tag: name.clone(),
                kind: "wireguard".into(),
                server: link.server_ip.clone(),
                server_port: link.server_port,
                raw_outbound: Some(link.wg_outbound_json.clone()),
                ..Default::default()
            }),
            vkturn: Some(VkTurnConfig { uri: link.uri.clone(), vk_link: link.vk_link.clone(), listen_port: link.listen_port, ..Default::default() }),
            ..Default::default()
        }
    }
    .normalized();
    let id = unique_storage_id(&format!("imported_vkturn_{}", endpoint_base(&format!("{}_{}", link.server_ip, link.server_port))), used);
    entry(&id, &location, sub_url, None)
}

fn parse_qwdtt_text(text: &str, sub_url: Option<&str>) -> Option<LocationBundleV4> {
    let mut used = HashSet::new();
    let entries = scheme_lines(text, panel::QWDTT_SCHEME)
        .filter_map(panel::parse_qwdtt)
        .map(|l| {
            let name = if l.name.trim().is_empty() { format!("qWDTT {}", l.peer) } else { l.name.clone() };
            let loc = LocationConfig {
                name,
                engine: EngineType::VkTurn,
                vkturn: Some(VkTurnConfig {
                    core: CORE_WDTT.into(),
                    wdtt_peer: l.peer.clone(),
                    wdtt_password: l.password.clone(),
                    wdtt_workers: l.workers,
                    vk_link: l.hashes.clone(),
                    listen_port: if (1..=65535).contains(&l.listen_port) { l.listen_port } else { DEFAULT_FREETURN_PORT },
                    ..Default::default()
                }),
                ..Default::default()
            }
            .normalized();
            let id = unique_storage_id(&format!("imported_qwdtt_{}", endpoint_base(&l.peer)), &mut used);
            entry(&id, &loc, sub_url, None)
        })
        .collect();
    bundle_of(entries)
}

fn parse_csqtt_text(text: &str, sub_url: Option<&str>) -> Option<LocationBundleV4> {
    let mut used = HashSet::new();
    let entries = scheme_lines(text, panel::CSQTT_SCHEME)
        .filter_map(panel::parse_csqtt)
        .map(|l| {
            let loc = LocationConfig {
                name: if l.name.trim().is_empty() { format!("csqtt {}", l.host) } else { l.name.clone() },
                engine: EngineType::VkTurn,
                vkturn: Some(VkTurnConfig {
                    core: CORE_CSQTT.into(),
                    csqtt_peer: l.host.clone(),
                    csqtt_port: l.port,
                    csqtt_password: l.password.clone(),
                    vk_link: l.hashes.clone(),
                    ..Default::default()
                }),
                ..Default::default()
            }
            .normalized();
            let id = unique_storage_id(&format!("imported_csqtt_{}", endpoint_base(&l.host)), &mut used);
            entry(&id, &loc, sub_url, None)
        })
        .collect();
    bundle_of(entries)
}

fn parse_openflux_text(text: &str, sub_url: Option<&str>) -> Option<LocationBundleV4> {
    let mut used = HashSet::new();
    let entries = scheme_lines(text, panel::OPENFLUX_SCHEME)
        .filter_map(panel::parse_openflux)
        .map(|(name, config)| {
            let loc = LocationConfig {
                name: if name.trim().is_empty() { format!("OpenFlux {}", config.summary()) } else { name },
                engine: EngineType::OpenFlux,
                open_flux: Some(config.clone()),
                ..Default::default()
            }
            .normalized();
            let base: Vec<char> = endpoint_base(&config.doc_url).chars().collect();
            let tail: String = base[base.len().saturating_sub(40)..].iter().collect();
            let id = unique_storage_id(&format!("imported_openflux_{tail}"), &mut used);
            entry(&id, &loc, sub_url, None)
        })
        .collect();
    bundle_of(entries)
}

fn parse_masterdns_text(text: &str, sub_url: Option<&str>) -> Option<LocationBundleV4> {
    let mut used = HashSet::new();
    let entries = scheme_lines(text, panel::MASTERDNS_SCHEME)
        .filter_map(panel::parse_masterdns)
        .map(|(name, config)| {
            let domain = config.domain_list().into_iter().next().unwrap_or_default();
            let loc = LocationConfig {
                name: if name.trim().is_empty() { format!("MasterDNS {domain}") } else { name },
                engine: EngineType::MasterDns,
                master_dns: Some(config),
                ..Default::default()
            }
            .normalized();
            let id = unique_storage_id(&format!("imported_masterdns_{}", endpoint_base(&domain)), &mut used);
            entry(&id, &loc, sub_url, None)
        })
        .collect();
    bundle_of(entries)
}

fn parse_snolc_text(text: &str, sub_url: Option<&str>) -> Option<LocationBundleV4> {
    let mut used = HashSet::new();
    let entries = scheme_lines(text, "snolc://")
        .filter_map(SnolcConfig::parse_uri)
        .map(|(config, name)| {
            let loc = LocationConfig {
                name: if name.trim().is_empty() { config.summary() } else { name },
                engine: EngineType::Snolc,
                snolc: Some(config.clone()),
                ..Default::default()
            }
            .normalized();
            let id = unique_storage_id(&format!("imported_snolc_{}", endpoint_base(&format!("{}_{}", config.host, config.port))), &mut used);
            entry(&id, &loc, sub_url, None)
        })
        .collect();
    bundle_of(entries)
}

fn parse_amneziawg_text(text: &str, sub_url: Option<&str>) -> Option<LocationBundleV4> {
    let t = text.trim();
    if !share_link::looks_like_amneziawg(t) {
        return None;
    }
    let p = share_link::parse_amneziawg(t)?;
    let name = if p.tag.trim().is_empty() { "AmneziaWG".to_string() } else { p.tag.clone() };
    let id = unique_storage_id(&format!("imported_awg_{}", endpoint_base(&format!("{}_{}", p.server, p.server_port))), &mut HashSet::new());
    let loc = LocationConfig { name, engine: EngineType::Standard, proxy: Some(p), ..Default::default() }.normalized();
    bundle_of(vec![entry(&id, &loc, sub_url, None)])
}

fn parse_proxy_text(text: &str, sub_url: Option<&str>, metadata: Option<&SubscriptionMetadata>) -> Option<LocationBundleV4> {
    let profiles = share_link::parse_subscription(text);
    if profiles.is_empty() {
        return None;
    }
    let meta = metadata.map(|m| LocationMetadata { subscription: Some(m.clone()), ..Default::default() });
    let mut used = HashSet::new();
    let entries = profiles
        .into_iter()
        .map(|p| {
            let id = unique_storage_id(&format!("imported_{}", endpoint_base(&format!("{}_{}", p.server, p.server_port))), &mut used);
            let loc = LocationConfig { name: p.display_name(), engine: EngineType::Standard, proxy: Some(p), ..Default::default() }.normalized();
            entry(&id, &loc, sub_url, meta.clone())
        })
        .collect();
    bundle_of(entries)
}

// ---------------- raw sing-box ----------------

fn json_objects(v: &Value) -> Vec<Obj> {
    v.as_array().map(|a| a.iter().filter_map(|x| x.as_object().cloned()).collect()).unwrap_or_default()
}

fn parse_raw_singbox(text: &str, sub_url: Option<&str>) -> Option<LocationBundleV4> {
    let t = text.trim();
    if !t.starts_with('{') && !t.starts_with('[') {
        return None;
    }
    let element: Value = serde_json::from_str(t).ok()?;
    let outbounds: Vec<Obj> = match &element {
        Value::Object(o) if o.contains_key("outbounds") => {
            if !o["outbounds"].is_array() {
                return None;
            }
            json_objects(&o["outbounds"])
        }
        Value::Object(o) => vec![o.clone()],
        Value::Array(_) => json_objects(&element),
        _ => return None,
    };
    let servers: Vec<&Obj> = outbounds.iter().filter(|o| o.contains_key("type") && o.contains_key("server")).collect();
    if servers.is_empty() {
        return None;
    }
    let root = element.as_object();
    let route = root.and_then(|r| normalize_singbox_route(r, &outbounds));
    let fake = root.and_then(fake_dns_spec_from_singbox);
    let mut used = HashSet::new();
    let entries = servers
        .into_iter()
        .filter_map(|ob| {
            let server = s(ob, "server")?.to_string();
            let port = i(ob, "server_port")?;
            let kind = s(ob, "type")?.to_string();
            let tag = s(ob, "tag").filter(|t| not_blank(t)).map(str::to_string).unwrap_or_else(|| format!("{server}:{port}"));
            let profile = ProxyProfile { tag: tag.clone(), kind, server: server.clone(), server_port: port, raw_outbound: Some(Value::Object(ob.clone()).to_string()), raw_singbox_route: route.clone(), ..Default::default() };
            let loc = LocationConfig { name: tag, engine: EngineType::Standard, proxy: Some(profile), fake_dns: fake.clone(), ..Default::default() }.normalized();
            let id = unique_storage_id(&format!("imported_{}", endpoint_base(&format!("{server}_{port}"))), &mut used);
            Some(entry(&id, &loc, sub_url, None))
        })
        .collect();
    bundle_of(entries)
}

/// A sing-box config's own `route` re-pointed at our tags (`proxy`/`direct`, block → reject);
/// rules bound to its inbounds/dns outbound and its sniff/hijack plumbing are dropped.
pub fn normalize_singbox_route(root: &Obj, outbounds: &[Obj]) -> Option<String> {
    let route = root.get("route")?.as_object()?;
    let rules = json_objects(route.get("rules")?);
    if rules.is_empty() {
        return None;
    }
    let kind_by_tag: BTreeMap<&str, &str> = outbounds
        .iter()
        .filter_map(|ob| {
            let tag = s(ob, "tag").filter(|t| not_blank(t))?;
            let kind = match s(ob, "type").map(str::to_lowercase).as_deref() {
                Some("direct") => "direct",
                Some("block") | Some("reject") => "reject",
                Some("dns") => "dns",
                _ => "proxy",
            };
            Some((tag, kind))
        })
        .collect();
    let mapped: Vec<Value> = rules
        .into_iter()
        .filter_map(|rule| {
            if rule.contains_key("inbound") {
                return None;
            }
            match s(&rule, "action").map(str::to_lowercase).as_deref() {
                Some("sniff") | Some("hijack-dns") => return None,
                None | Some("route") => {}
                _ => return Some(Value::Object(rule)),
            }
            let target = s(&rule, "outbound")?.to_string();
            let mut m: Obj = rule.iter().filter(|(k, _)| *k != "outbound").map(|(k, v)| (k.clone(), v.clone())).collect();
            match kind_by_tag.get(target.as_str()).copied().unwrap_or("proxy") {
                "dns" => None,
                "reject" => {
                    m.remove("action");
                    m.insert("action".into(), json!("reject"));
                    Some(Value::Object(m))
                }
                "direct" => {
                    m.insert("outbound".into(), json!("direct"));
                    Some(Value::Object(m))
                }
                _ => {
                    m.insert("outbound".into(), json!("proxy"));
                    Some(Value::Object(m))
                }
            }
        })
        .collect();
    if mapped.is_empty() {
        return None;
    }
    // A download_detour naming the original config's outbound is a hard error: re-point it.
    let rule_sets: Vec<Value> = route
        .get("rule_set")
        .map(json_objects)
        .unwrap_or_default()
        .into_iter()
        .map(|set| {
            let detour = s(&set, "download_detour").map(str::to_string);
            if detour.is_none() && s(&set, "type") != Some("remote") {
                return Value::Object(set);
            }
            let mut m: Obj = set.into_iter().filter(|(k, _)| k != "download_detour").collect();
            let d = if detour.as_deref().and_then(|t| kind_by_tag.get(t)) == Some(&"direct") { "direct" } else { "proxy" };
            m.insert("download_detour".into(), json!(d));
            Value::Object(m)
        })
        .collect();
    let final_tag = match s(route, "final") {
        Some(t) if kind_by_tag.get(t).copied().unwrap_or("proxy") == "direct" => "direct",
        _ => "proxy",
    };
    let mut out = Obj::new();
    out.insert("final".into(), json!(final_tag));
    out.insert("rules".into(), Value::Array(mapped));
    if !rule_sets.is_empty() {
        out.insert("rule_set".into(), Value::Array(rule_sets));
    }
    Some(Value::Object(out).to_string())
}

/// fakeip of a sing-box config: the legacy `dns.fakeip` block or a 1.12+ `type: fakeip` server.
pub fn fake_dns_spec_from_singbox(root: &Obj) -> Option<FakeDnsSpec> {
    let dns = root.get("dns")?.as_object()?;
    let legacy = dns.get("fakeip").and_then(Value::as_object);
    let server = dns.get("servers").and_then(Value::as_array).and_then(|a| a.iter().filter_map(Value::as_object).find(|o| s(o, "type").is_some_and(|t| t.eq_ignore_ascii_case("fakeip"))));
    let block = legacy.or(server)?;
    if block.get("enabled").and_then(Value::as_bool) == Some(false) {
        return None;
    }
    let d = FakeDnsSpec::default();
    Some(FakeDnsSpec {
        inet4_range: s(block, "inet4_range").filter(|v| not_blank(v)).map(str::to_string).unwrap_or(d.inet4_range.clone()),
        inet6_range: s(block, "inet6_range").filter(|v| not_blank(v)).map(str::to_string).unwrap_or(d.inet6_range.clone()),
        ..d
    })
}

// ---------------- raw Xray ----------------

fn parse_raw_xray(text: &str, sub_url: Option<&str>) -> Option<LocationBundleV4> {
    let t = text.trim();
    if !t.starts_with('{') && !t.starts_with('[') {
        return None;
    }
    let element: Value = serde_json::from_str(t).ok()?;
    let configs: Vec<Obj> = match &element {
        Value::Object(o) => vec![o.clone()],
        Value::Array(_) => json_objects(&element),
        _ => return None,
    };
    let mut used = HashSet::new();
    let entries = configs.iter().filter_map(|c| parse_single_raw_xray_entry(c, &mut used, sub_url)).collect();
    bundle_of(entries)
}

fn first_endpoint(proxy: &Obj) -> Option<(String, i32)> {
    let settings = proxy.get("settings")?.as_object()?;
    let list = settings.get("vnext").and_then(Value::as_array).or_else(|| settings.get("servers").and_then(Value::as_array))?;
    let first = list.first()?.as_object()?;
    Some((s(first, "address")?.to_string(), i(first, "port")?))
}

fn proxy_outbound(root: &Obj) -> Option<Obj> {
    json_objects(root.get("outbounds")?).into_iter().find(|o| s(o, "protocol").is_some_and(|p| PROXY_PROTOCOLS.contains(&p.to_lowercase().as_str())))
}

fn parse_single_raw_xray_entry(root: &Obj, used: &mut HashSet<String>, sub_url: Option<&str>) -> Option<LocationEntry> {
    let proxy = proxy_outbound(root)?;
    let protocol = s(&proxy, "protocol")?.to_lowercase();
    let (server, port) = first_endpoint(&proxy)?;
    let fallback = format!("{server}:{port}");
    let name = first_not_blank(&[s(root, "remarks"), s(&proxy, "tag"), Some(&fallback)]);
    let description = root.get("meta").and_then(Value::as_object).and_then(|m| s(m, "serverDescription")).unwrap_or("").to_string();
    // A config with its own routing/DNS runs verbatim on Xray; otherwise translate when possible.
    let typed = if brings_own_routing(root) { None } else { typed_profile_from_xray_outbound(&proxy, &protocol, &server, port, &name) };
    let loc = match typed {
        Some(p) => LocationConfig { name, description, engine: EngineType::Standard, proxy: Some(p), core: ProxyCore::Auto, fake_dns: fake_dns_spec_from_xray(root), ..Default::default() },
        None => LocationConfig {
            name: name.clone(),
            description,
            engine: EngineType::Standard,
            proxy: Some(ProxyProfile { tag: name, kind: protocol, server: server.clone(), server_port: port, raw_xray_config: Some(Value::Object(root.clone()).to_string()), ..Default::default() }),
            core: ProxyCore::Xray,
            ..Default::default()
        },
    }
    .normalized();
    let id = unique_storage_id(&format!("imported_{}", endpoint_base(&format!("{server}_{port}"))), used);
    Some(entry(&id, &loc, sub_url, None))
}

/// `routing.rules` or a scoped (object) `dns.servers` entry: neither survives the typed translation.
pub fn brings_own_routing(root: &Obj) -> bool {
    let routing = root.get("routing").and_then(|r| r.get("rules")).and_then(Value::as_array).is_some_and(|a| !a.is_empty());
    let scoped = root.get("dns").and_then(|d| d.get("servers")).and_then(Value::as_array).is_some_and(|a| a.iter().any(Value::is_object));
    routing || scoped
}

/// An Xray proxy outbound as a typed profile sing-box can dial; `None` for xhttp/unknown.
fn typed_profile_from_xray_outbound(ob: &Obj, protocol: &str, server: &str, port: i32, name: &str) -> Option<ProxyProfile> {
    let settings = ob.get("settings").and_then(Value::as_object);
    let stream = ob.get("streamSettings").and_then(Value::as_object);
    let network = match stream.and_then(|st| s(st, "network")).map(str::to_lowercase).as_deref().unwrap_or("tcp") {
        "tcp" | "raw" => NETWORK_TCP,
        "ws" | "websocket" => NETWORK_WS,
        "grpc" | "gun" => NETWORK_GRPC,
        "h2" | "http" => NETWORK_HTTP,
        "httpupgrade" => NETWORK_HTTPUPGRADE,
        _ => return None,
    };
    let security = match stream.and_then(|st| s(st, "security")).map(str::to_lowercase).as_deref() {
        Some("reality") => SECURITY_REALITY,
        Some("tls") | Some("xtls") => SECURITY_TLS,
        _ => SECURITY_NONE,
    };
    let first_obj = |v: Option<&Value>| v.and_then(Value::as_array).and_then(|a| a.first()).and_then(Value::as_object).cloned();
    let vnext_user = first_obj(settings.and_then(|st| st.get("vnext"))).and_then(|v| first_obj(v.get("users")));
    let server_obj = first_obj(settings.and_then(|st| st.get("servers")));
    let tls = stream.and_then(|st| st.get("realitySettings").and_then(Value::as_object).or_else(|| st.get("tlsSettings").and_then(Value::as_object)));
    let ts = |k: &str| tls.and_then(|t| s(t, k)).unwrap_or("").to_string();
    let alpn: Vec<String> = tls.and_then(|t| t.get("alpn")).and_then(Value::as_array).map(|a| a.iter().filter_map(|v| v.as_str().map(str::to_string)).collect()).unwrap_or_default();
    let allow_insecure = tls.and_then(|t| t.get("allowInsecure")).is_some_and(|v| v.as_bool() == Some(true) || v.as_str() == Some("true"));
    let ws_like = stream.and_then(|st| st.get("wsSettings").and_then(Value::as_object).or_else(|| st.get("httpupgradeSettings").and_then(Value::as_object)));
    let grpc = stream.and_then(|st| st.get("grpcSettings")).and_then(Value::as_object);
    let path = if network == NETWORK_GRPC { grpc.and_then(|g| s(g, "serviceName")) } else { ws_like.and_then(|w| s(w, "path")) }.unwrap_or("").to_string();
    let host = ws_like.and_then(|w| s(w, "host").or_else(|| w.get("headers").and_then(Value::as_object).and_then(|h| s(h, "Host")))).unwrap_or("").to_string();
    let base = ProxyProfile {
        tag: name.into(),
        server: server.into(),
        server_port: port,
        network: network.into(),
        security: security.into(),
        sni: ts("serverName"),
        alpn,
        fingerprint: ts("fingerprint"),
        allow_insecure,
        reality_public_key: ts("publicKey"),
        reality_short_id: ts("shortId"),
        path,
        host,
        ..Default::default()
    };
    match protocol {
        "vless" | "vmess" => {
            let u = vnext_user?;
            let id = s(&u, "id").filter(|v| not_blank(v))?.to_string();
            Some(ProxyProfile {
                kind: protocol.into(),
                uuid: id,
                flow: s(&u, "flow").unwrap_or("").into(),
                alter_id: i(&u, "alterId").unwrap_or(0),
                cipher: s(&u, "security").unwrap_or("auto").into(),
                ..base
            })
        }
        "trojan" => Some(ProxyProfile { kind: TYPE_TROJAN.into(), password: s(server_obj.as_ref()?, "password")?.into(), ..base }),
        "shadowsocks" => {
            let so = server_obj?;
            Some(ProxyProfile {
                kind: TYPE_SHADOWSOCKS.into(),
                password: s(&so, "password")?.into(),
                method: s(&so, "method").unwrap_or("").into(),
                security: SECURITY_NONE.into(),
                sni: String::new(),
                alpn: vec![],
                fingerprint: String::new(),
                allow_insecure: false,
                reality_public_key: String::new(),
                reality_short_id: String::new(),
                path: String::new(),
                host: String::new(),
                ..base
            })
        }
        _ => None,
    }
}

/// FakeDNS of a full Xray config: the pool and the `dns.hosts` → 0.0.0.0 blackholes as regexps.
pub fn fake_dns_spec_from_xray(root: &Obj) -> Option<FakeDnsSpec> {
    let fake = root.get("fakedns")?;
    let pool = fake.as_array().and_then(|a| a.first()).and_then(Value::as_object).or_else(|| fake.as_object());
    let ip_pool = pool.and_then(|p| s(p, "ipPool"));
    let inet4 = ip_pool.filter(|p| p.contains('.')).unwrap_or("198.18.0.0/15").to_string();
    let inet6 = ip_pool.filter(|p| p.contains(':')).unwrap_or("fc00::/18").to_string();
    let hosts = root.get("dns").and_then(|d| d.get("hosts")).and_then(Value::as_object);
    let block_regex = hosts
        .map(|h| {
            h.iter()
                .filter(|(_, v)| match v {
                    Value::String(s) => s == "0.0.0.0",
                    Value::Array(a) => a.iter().any(|x| x.as_str() == Some("0.0.0.0")),
                    _ => false,
                })
                .filter_map(|(k, _)| {
                    if let Some(r) = k.strip_prefix("regexp:") {
                        Some(r.to_string())
                    } else if let Some(d) = k.strip_prefix("domain:") {
                        Some(format!(r"(^|\.){}$", d.replace('.', r"\.")))
                    } else if k.starts_with("geosite:") || k.starts_with("geoip:") {
                        None
                    } else {
                        Some(format!(r"(^|\.){}$", k.replace('.', r"\.")))
                    }
                })
                .collect()
        })
        .unwrap_or_default();
    Some(FakeDnsSpec { inet4_range: inet4, inet6_range: inet6, block_regex, ..Default::default() })
}

// ---------------- rich (Happ-UA) body enrichment ----------------

fn configs_of(body: &str) -> Vec<Obj> {
    match serde_json::from_str::<Value>(body.trim()) {
        Ok(Value::Object(o)) => vec![o],
        Ok(v @ Value::Array(_)) => json_objects(&v),
        _ => vec![],
    }
}

/// `"server:port"` → `meta.serverDescription` of a rich Xray body.
pub fn server_descriptions_from_body(body: &str) -> BTreeMap<String, String> {
    let mut out = BTreeMap::new();
    for root in configs_of(body) {
        let Some(desc) = root.get("meta").and_then(Value::as_object).and_then(|m| s(m, "serverDescription")).filter(|d| not_blank(d)) else { continue };
        let Some((server, port)) = proxy_outbound(&root).and_then(|p| first_endpoint(&p)) else { continue };
        out.insert(format!("{server}:{port}"), desc.to_string());
    }
    out
}

/// (remarks, "server:port", verbatim JSON) for every xhttp/splithttp config and every config with
/// its own routing/DNS in a rich body.
pub fn verbatim_xhttp_configs(body: &str) -> Vec<(String, String, String)> {
    let mut out = Vec::new();
    for root in configs_of(body) {
        let Some(proxy) = proxy_outbound(&root) else { continue };
        let net = proxy.get("streamSettings").and_then(|st| st.get("network")).and_then(Value::as_str).map(str::to_lowercase);
        if !matches!(net.as_deref(), Some("xhttp") | Some("splithttp")) && !brings_own_routing(&root) {
            continue;
        }
        let Some((server, port)) = first_endpoint(&proxy) else { continue };
        out.push((s(&root, "remarks").unwrap_or("").trim().to_string(), format!("{server}:{port}"), Value::Object(root).to_string()));
    }
    out
}

/// The FakeDNS spec of a rich body (the first config that has `fakedns`).
pub fn fake_dns_spec_from_body(body: &str) -> Option<FakeDnsSpec> {
    match serde_json::from_str::<Value>(body.trim()).ok()? {
        Value::Object(o) => fake_dns_spec_from_xray(&o),
        v @ Value::Array(_) => json_objects(&v).into_iter().find(|o| o.contains_key("fakedns")).and_then(|o| fake_dns_spec_from_xray(&o)),
        _ => None,
    }
}

/// Attaches the rich body's descriptions, verbatim xhttp/routing configs and FakeDNS to a bundle
/// parsed from the user's chosen-UA body.
pub fn enrich_from_rich_body(bundle: LocationBundleV4, rich: &str) -> LocationBundleV4 {
    let fake = fake_dns_spec_from_body(rich);
    let desc = server_descriptions_from_body(rich);
    let verbatim = verbatim_xhttp_configs(rich);
    if fake.is_none() && desc.is_empty() && verbatim.is_empty() {
        return bundle;
    }
    let locations = bundle
        .locations
        .into_iter()
        .map(|e| {
            let mut changed = false;
            let mut e2 = e.clone();
            if e2.description.trim().is_empty() {
                if let Some(d) = e2.proxy.as_ref().and_then(|p| desc.get(&format!("{}:{}", p.server, p.server_port))) {
                    e2.description = d.clone();
                    changed = true;
                }
            }
            if !verbatim.is_empty() && e2.proxy.as_ref().is_some_and(|p| p.raw_xray_config.as_deref().map_or(true, |r| r.trim().is_empty())) {
                let p = e2.proxy.clone().unwrap();
                let sp = format!("{}:{}", p.server, p.server_port);
                let by_name = verbatim.iter().find(|v| !v.0.trim().is_empty() && v.0 == e2.name.trim());
                let by_endpoint = || {
                    let m: Vec<_> = verbatim.iter().filter(|v| v.1 == sp).collect();
                    (m.len() == 1).then(|| m[0])
                };
                if let Some(m) = by_name.or_else(by_endpoint) {
                    e2.proxy = Some(ProxyProfile { raw_xray_config: Some(m.2.clone()), ..p });
                    e2.core = Some(ProxyCore::Xray);
                    changed = true;
                }
            }
            if let Some(f) = &fake {
                let p = e2.proxy.as_ref();
                if e2.fake_dns.is_none() && p.map_or(true, |p| p.raw_xray_config.as_deref().map_or(true, |r| r.trim().is_empty())) && p.map_or(true, |p| p.network != NETWORK_XHTTP) {
                    e2.fake_dns = Some(f.clone());
                    changed = true;
                }
            }
            if changed {
                e2.normalized()
            } else {
                e
            }
        })
        .collect();
    LocationBundleV4 { locations, ..bundle }
}

/// The panel's "not supported for this client" stub (a normal 200 body).
pub fn is_unsupported_client_stub(content: &str, profile_title: Option<&str>, announce: Option<&str>) -> bool {
    let head: String = content.chars().take(4096).collect();
    let decoded = decode_base64_chunk(head.trim());
    let hit = [Some(head.as_str()), decoded.as_deref(), profile_title, announce].into_iter().flatten().any(|p| {
        let l = p.to_lowercase();
        l.contains("не поддерживается для этого клиента") || l.contains("not supported for this client")
    });
    hit
}

// ---------------- exported bundles / legacy JSON ----------------

fn parse_bundle(root: &Obj, sub_url: Option<&str>, interval: Option<i32>) -> Option<LocationBundleV4> {
    let items = root.get("locations")?.as_array()?;
    let locations: Vec<LocationEntry> = items
        .iter()
        .filter_map(Value::as_object)
        .filter_map(|item| {
            if let Some(e) = decode_location_entry(item, sub_url) {
                return Some(LocationEntry { metadata: loc_meta_with_interval(e.metadata.clone(), interval), ..e }.normalized());
            }
            let id = s(item, "storage_id").or_else(|| s(item, "storageId")).map(str::to_string).or_else(|| s(item, "id").map(|i| format!("imported_{}", storage_slug(i))));
            parse_single_location(item, id.as_deref(), sub_url).map(|e| LocationEntry { metadata: loc_meta_with_interval(e.metadata.clone(), interval), ..e }.normalized())
        })
        .collect();
    let version = i(root, "version").unwrap_or(3);
    if version < 3 && locations.is_empty() {
        return None;
    }
    Some(LocationBundleV4 { active_location_id: s(root, "active_location_id").or_else(|| s(root, "activeLocationId")).map(str::to_string), locations, ..Default::default() })
}

fn decode_location_entry(root: &Obj, sub_url: Option<&str>) -> Option<LocationEntry> {
    // `storage_id` is a required field of the stored entry (Kotlin has no default for it).
    root.get("storage_id")?.as_str()?;
    let mut e: LocationEntry = serde_json::from_value(Value::Object(root.clone())).ok()?;
    if e.subscription_url.as_deref().map_or(true, |u| u.trim().is_empty()) {
        if let Some(u) = sub_url.filter(|u| not_blank(u)) {
            e.subscription_url = Some(u.to_string());
        }
    }
    let e = e.normalized();
    e.location().is_storable().then_some(e)
}

/// A legacy single-location JSON (olcRTC fields under many historical names).
pub fn parse_single_location(root: &Obj, fallback_id: Option<&str>, sub_url: Option<&str>) -> Option<LocationEntry> {
    if let Some(e) = decode_location_entry(root, sub_url) {
        return Some(e);
    }
    let src = root.get("location").and_then(Value::as_object).or_else(|| root.get("hysteria").and_then(Value::as_object)).unwrap_or(root);
    let turn = root.get("turn").and_then(Value::as_object);
    let provider = first_not_blank(&[
        s(src, "auth_provider"), s(src, "authProvider"), s(src, "bypass_provider"), s(src, "bypassProvider"), s(src, "provider"),
        turn.and_then(|t| s(t, "type")), s(root, "auth_provider"), s(root, "authProvider"), s(root, "bypass_provider"), s(root, "bypassProvider"), s(root, "provider"),
    ]);
    let args = first_not_blank(&[s(src, "transport_args"), s(src, "transportArgs"), s(src, "args"), s(root, "transport_args"), s(root, "transportArgs"), s(root, "args")]);
    let arg_int = |name: &str| -> Option<i32> {
        let parts: Vec<&str> = args.split_whitespace().collect();
        let idx = parts.iter().position(|p| *p == name);
        parts.get(idx.map_or(0, |i| i + 1)).and_then(|v| v.parse().ok())
    };
    let vp8_fps = [i(src, "vp8_fps"), i(src, "vp8Fps"), i(root, "vp8_fps"), i(root, "vp8Fps"), arg_int("-vp8-fps")].into_iter().flatten().next().unwrap_or(DEFAULT_VP8_FPS);
    let vp8_batch = [i(src, "vp8_batch"), i(src, "vp8Batch"), i(root, "vp8_batch"), i(root, "vp8Batch"), arg_int("-vp8-batch")].into_iter().flatten().next().unwrap_or(DEFAULT_VP8_BATCH);
    let location = LocationConfig {
        name: first_not_blank(&[s(src, "name"), s(root, "name")]),
        id: first_not_blank(&[s(src, "id"), s(src, "room_id"), s(src, "server"), s(root, "id")]),
        key: first_not_blank(&[s(src, "key"), s(src, "encryption_key"), s(src, "password"), s(root, "key")]),
        bypass_provider: provider,
        transport: first_not_blank(&[s(src, "transport"), s(root, "transport"), (!args.trim().is_empty()).then_some(TRANSPORT_VP8CHANNEL)]),
        vp8_fps,
        vp8_batch,
        ..Default::default()
    }
    .normalized();
    if !location.is_complete() {
        return None;
    }
    let generated = format!("imported_{}", location_slug(&location));
    let id = first_not_blank(&[fallback_id, s(root, "storage_id"), s(root, "storageId"), s(src, "storage_id"), s(src, "storageId"), Some(&generated)]);
    Some(entry(&id, &location, sub_url, None))
}

/// A legacy stored location text (bundle or single object) → its entry.
pub fn parse_single_location_text(text: &str, fallback_id: Option<&str>, sub_url: Option<&str>) -> Option<LocationEntry> {
    let root = match serde_json::from_str::<Value>(text) {
        Ok(Value::Object(o)) => o,
        _ => return None,
    };
    if let Some(b) = parse_bundle(&root, sub_url, None) {
        return b.normalized().locations.into_iter().next();
    }
    parse_single_location(&root, fallback_id, sub_url)
}

// ---------------- refresh bookkeeping ----------------

/// What identifies a subscription server across refreshes (the endpoint, not the name).
pub fn subscription_signature(location: &LocationConfig) -> String {
    let n = location.normalized();
    let proxy = n.proxy.as_ref().map(|p| format!("{}@{}:{}", p.kind, p.server, p.server_port)).unwrap_or_default();
    let vk = n
        .vkturn
        .as_ref()
        .map(|v| {
            let uri = v.uri.split('$').next().unwrap_or("");
            format!("{}@{}", v.outbound, if uri.trim().is_empty() { v.outbound_proxy_link.as_str() } else { uri })
        })
        .unwrap_or_default();
    let mdns = n.master_dns.as_ref().map(|m| format!("{}@{}", m.domains, m.resolvers)).unwrap_or_default();
    let of = n
        .open_flux
        .as_ref()
        .map(|o| {
            if o.is_session() {
                format!("session@{}", o.carriers.iter().map(|c| format!("{}={}{}", c.kind, c.url, c.dial)).collect::<Vec<_>>().join(","))
            } else {
                format!("{}@{}", o.transport, if o.uses_max() { &o.max_uid } else { &o.doc_url })
            }
        })
        .unwrap_or_default();
    let snolc = n.snolc.as_ref().map(|c| format!("snolc@{}:{}", c.host, c.port)).unwrap_or_default();
    [n.bypass_provider.clone(), n.transport.clone(), n.id.clone(), n.key.clone(), proxy, vk, mdns, of, snolc].join("|")
}

/// The signature + name: several servers of one panel often share one endpoint.
pub fn named_subscription_signature(location: &LocationConfig) -> String {
    format!("{}|{}", subscription_signature(location), location.name.trim())
}

/// `cascade` as the second proxy of a Standard/Chain server with a main proxy (never its own node).
pub fn with_cascade(e: &LocationEntry, cascade: Option<&ProxyProfile>) -> LocationEntry {
    let Some(main) = e.proxy.as_ref() else { return e.clone() };
    let loc = e.location();
    if loc.engine != EngineType::Standard && loc.engine != EngineType::Chain {
        return e.clone();
    }
    LocationEntry { proxy2: cascade.filter(|c| !main.is_same_node_as(c)).cloned(), ..e.clone() }
}

/// The user's own VK call link beats the subscription's.
pub fn with_vk_link_override(e: LocationEntry, link: Option<&str>) -> LocationEntry {
    let Some(link) = link.filter(|l| not_blank(l)) else { return e };
    let Some(vk) = e.vkturn.clone() else { return e };
    let meta = LocationMetadata { vk_link_override: Some(link.into()), ..e.metadata.clone().unwrap_or_default() };
    LocationEntry { vkturn: Some(VkTurnConfig { vk_link: link.into(), ..vk }), metadata: Some(meta), ..e }
}

fn trimmed_url(e: &LocationEntry) -> Option<String> {
    e.subscription_url.as_deref().map(str::trim).filter(|u| !u.is_empty()).map(str::to_string)
}

/// Subscription URLs of `bundle` (first-seen order) with their entries.
pub fn subscription_groups(bundle: &LocationBundleV4) -> Vec<(String, Vec<LocationEntry>)> {
    let mut out: Vec<(String, Vec<LocationEntry>)> = Vec::new();
    for e in &bundle.locations {
        if let Some(u) = trimmed_url(e) {
            match out.iter_mut().find(|(k, _)| *k == u) {
                Some((_, v)) => v.push(e.clone()),
                None => out.push((u, vec![e.clone()])),
            }
        }
    }
    out
}

/// URLs whose interval has elapsed (auto-update on). `retry_failed` keys off the last SUCCESS only.
pub fn due_subscription_urls(bundle: &LocationBundleV4, now: i64, retry_failed: bool) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for e in &bundle.locations {
        let Some(url) = trimmed_url(e) else { continue };
        let meta = e.metadata.as_ref().and_then(|m| m.subscription.as_ref());
        if meta.is_some_and(|m| !m.auto_update_enabled) {
            continue;
        }
        let interval = meta.and_then(|m| m.update_interval_hours).unwrap_or(DEFAULT_UPDATE_INTERVAL_HOURS) as i64;
        let last_refresh = meta.and_then(|m| m.last_refresh_at_epoch_ms).unwrap_or(0);
        let touch = if retry_failed { last_refresh } else { last_refresh.max(meta.and_then(|m| m.last_attempt_at_epoch_ms).unwrap_or(0)) };
        if (touch <= 0 || now - touch >= interval * 3_600_000) && !out.contains(&url) {
            out.push(url);
        }
    }
    out
}

/// URLs none of whose entries has an expiry yet (backfill).
pub fn subscription_urls_missing_expiry(bundle: &LocationBundleV4) -> Vec<String> {
    subscription_groups(bundle)
        .into_iter()
        .filter(|(_, es)| es.iter().all(|e| e.metadata.as_ref().and_then(|m| m.subscription.as_ref()).and_then(|s| s.expires_at_epoch_ms).is_none()))
        .map(|(u, _)| u)
        .collect()
}

/// One refreshed subscription: the parsed import plus the interval the panel stated.
#[derive(Debug, Clone)]
pub struct RefreshedSubscription {
    pub parsed: ParsedImport,
    pub update_interval_hours: Option<i32>,
}

/// Applies one subscription's refresh outcome to the bundle. `None` (or an empty result) keeps
/// the old entries and only stamps the attempt. Returns the new bundle and whether it succeeded.
///
/// Each refreshed server takes over the id (selection, pings) of the same server from before:
/// same endpoint + name first, then the endpoint alone; the selection survives when its server does.
pub fn apply_subscription_refresh(bundle: &LocationBundleV4, url: &str, outcome: Option<&RefreshedSubscription>, now: i64) -> (LocationBundleV4, bool) {
    let previous: Vec<LocationEntry> = bundle.locations.iter().filter(|e| trimmed_url(e).as_deref() == Some(url)).cloned().collect();
    let mut kept: Vec<LocationEntry> = bundle.locations.iter().filter(|e| trimmed_url(e).as_deref() != Some(url)).cloned().collect();
    let mut used: HashSet<String> = kept.iter().map(|e| e.storage_id.clone()).collect();
    let refreshed = outcome.map(|o| o.parsed.bundle.locations.clone()).unwrap_or_default();
    if refreshed.is_empty() {
        for e in previous {
            if used.insert(e.storage_id.clone()) {
                let m = edit_subscription(e.metadata.clone(), |s| s.last_attempt_at_epoch_ms = Some(now));
                kept.push(LocationEntry { metadata: Some(m), ..e }.normalized());
            }
        }
        return (LocationBundleV4 { locations: kept, ..bundle.clone() }, false);
    }
    let outcome = outcome.unwrap();
    let sub_of = |e: &LocationEntry| e.metadata.as_ref().and_then(|m| m.subscription.clone());
    let prev_interval = previous.iter().find_map(|e| sub_of(e).and_then(|s| s.update_interval_hours));
    let prev_auto = !previous.iter().any(|e| sub_of(e).is_some_and(|s| !s.auto_update_enabled));
    let prev_custom = previous.iter().find_map(|e| sub_of(e).and_then(|s| s.custom_name));
    let prev_cascade_link = previous.iter().find_map(|e| sub_of(e).and_then(|s| s.cascade_link));
    let prev_cascade = prev_cascade_link.as_deref().and_then(share_link::parse).filter(|p| p.is_complete());
    let interval = outcome.update_interval_hours.or(prev_interval).unwrap_or(DEFAULT_UPDATE_INTERVAL_HOURS);

    let mut taken: HashSet<String> = HashSet::new();
    let claim = |pool: Vec<&LocationEntry>, taken: &mut HashSet<String>| -> Option<LocationEntry> {
        let e = pool.into_iter().find(|e| !taken.contains(&e.storage_id))?;
        taken.insert(e.storage_id.clone());
        Some(e.clone())
    };
    let prev_locs: Vec<(LocationConfig, &LocationEntry)> = previous.iter().map(|e| (e.location(), e)).collect();
    let exact: Vec<Option<LocationEntry>> = refreshed
        .iter()
        .map(|e| {
            let sig = named_subscription_signature(&e.location());
            claim(prev_locs.iter().filter(|(l, _)| named_subscription_signature(l) == sig).map(|(_, e)| *e).collect(), &mut taken)
        })
        .collect();
    let reused: Vec<Option<LocationEntry>> = refreshed
        .iter()
        .zip(exact)
        .map(|(e, x)| {
            x.or_else(|| {
                let sig = subscription_signature(&e.location());
                claim(prev_locs.iter().filter(|(l, _)| subscription_signature(l) == sig).map(|(_, e)| *e).collect(), &mut taken)
            })
        })
        .collect();
    // Reserve reused ids before generating new ones.
    used.extend(taken.iter().cloned());

    let active_before = bundle.active_location_id.clone();
    let mut active_after = active_before.clone();
    let active_here = active_before.as_ref().is_some_and(|a| previous.iter().any(|e| &e.storage_id == a));
    let mut active_reused = false;
    let reassigned: Vec<LocationEntry> = refreshed
        .iter()
        .enumerate()
        .map(|(idx, e)| {
            let r = reused[idx].as_ref();
            let id = match r {
                Some(r) => r.storage_id.clone(),
                None => unique_storage_id(&format!("imported_{}", location_slug_or(&e.location(), idx)), &mut used),
            };
            if active_before.is_some() && r.map(|r| &r.storage_id) == active_before.as_ref() {
                active_after = Some(id.clone());
                active_reused = true;
            }
            let meta = refresh_state(e.metadata.clone(), interval, Some(now), Some(now), prev_auto, prev_custom.clone(), prev_cascade_link.clone());
            let mut n = LocationEntry { storage_id: id, subscription_url: Some(url.to_string()), metadata: Some(meta), ..e.clone() };
            if let Some(c) = &prev_cascade {
                n = with_cascade(&n, Some(c));
            }
            with_vk_link_override(n, r.and_then(|r| r.metadata.as_ref()).and_then(|m| m.vk_link_override.as_deref())).normalized()
        })
        .collect();
    if active_here && !active_reused {
        active_after = reassigned.first().map(|e| e.storage_id.clone());
    }
    kept.extend(reassigned);
    (LocationBundleV4 { active_location_id: active_after, locations: kept, ..bundle.clone() }, true)
}

/// Stamps a fresh import's subscription entries with "refreshed now" (and the interval).
pub fn stamp_imported(bundle: LocationBundleV4, now: i64, imported_interval: Option<i32>) -> LocationBundleV4 {
    let b = bundle.normalized();
    let locations = b
        .locations
        .into_iter()
        .map(|e| {
            let sub = e.metadata.as_ref().and_then(|m| m.subscription.clone());
            if sub.is_none() && trimmed_url(&e).is_none() {
                return e;
            }
            let interval = sub.as_ref().and_then(|s| s.update_interval_hours).or(imported_interval).unwrap_or(DEFAULT_UPDATE_INTERVAL_HOURS);
            let meta = refresh_state(
                e.metadata.clone(),
                interval,
                Some(now),
                Some(now),
                sub.as_ref().map_or(true, |s| s.auto_update_enabled),
                sub.as_ref().and_then(|s| s.custom_name.clone()),
                sub.as_ref().and_then(|s| s.cascade_link.clone()),
            );
            LocationEntry { metadata: Some(meta), ..e }.normalized()
        })
        .collect();
    LocationBundleV4 { locations, ..b }
}
