//! The locations repository (port of `LocationsRepositoryImpl`): storage, import, subscription
//! fetch/refresh and every edit of the saved bundle. Synchronous: the platform calls it from a
//! background thread and supplies storage ([`Store`]) and HTTP ([`HttpFetcher`]).

use std::collections::BTreeMap;
use std::sync::{Mutex, RwLock};

use serde_json::Value;

use crate::importer::{self, ImportMode, PanelHeaders, ParsedImport, RefreshedSubscription};
use crate::location::*;
use crate::model::ProxyProfile;
use crate::settings::*;
use crate::share_link;

/// Never refreshed automatically: a raw public dump, filtered by the ping-and-pick flow instead.
pub const FREE_SERVERS_URL: &str = "https://raw.githubusercontent.com/zieng2/wl/main/vless_universal.txt";
/// Happ provider check; the provider id is appended.
pub const PROVIDER_CHECK_URL: &str = "https://check.happ-proxy.com/provider?id=";
pub const HEADER_APP_ID: &str = "x-app-id";
const MAIN_ACCEPT: &str = "application/json, text/plain, text/markdown, application/octet-stream, */*";
const KEY_PROVIDER_REPORT: &str = "provider_report_state";

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct HttpRequest {
    pub url: String,
    pub headers: Vec<(String, String)>,
    /// `socks5://[user:pass@]host:port` when the fetch must go through the running tunnel.
    pub proxy: Option<String>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct HttpResponse {
    pub status: u16,
    /// Repeated headers stay repeated (Set-Cookie).
    pub headers: Vec<(String, String)>,
    pub body: String,
}

impl HttpResponse {
    /// Case-insensitive header lookup.
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers.iter().find(|(k, _)| k.eq_ignore_ascii_case(name)).map(|(_, v)| v.as_str())
    }
    pub fn headers_named<'a>(&'a self, name: &'a str) -> impl Iterator<Item = &'a str> {
        self.headers.iter().filter(move |(k, _)| k.eq_ignore_ascii_case(name)).map(|(_, v)| v.as_str())
    }
    pub fn ok(&self) -> bool {
        (200..300).contains(&self.status)
    }
}

/// Blocking HTTP GET implemented by the platform (URLSession / OkHttp). `None` = transport error.
pub trait HttpFetcher: Send + Sync {
    fn get(&self, request: &HttpRequest) -> Option<HttpResponse>;
}

/// Persistent storage implemented by the platform.
pub trait Store: Send + Sync {
    fn load_bundle(&self) -> Option<LocationBundleV4>;
    fn save_bundle(&self, bundle: &LocationBundleV4);
    /// Changes whenever ANY process writes the bundle (the VPN extension has its own repository);
    /// `None` disables the in-memory cache.
    fn bundle_version_token(&self) -> Option<i64> {
        None
    }
    /// Pre-bundle storage: (storage id, location JSON).
    fn load_legacy_locations(&self) -> Vec<(String, String)> {
        vec![]
    }
    fn load_legacy_active_id(&self) -> Option<String> {
        None
    }
    /// Small key/value state (provider report days, cached logos).
    fn load_value(&self, _key: &str) -> Option<String> {
        None
    }
    fn save_value(&self, _key: &str, _value: &str) {}
    fn app_behavior(&self) -> AppBehaviorSettings {
        AppBehaviorSettings::default()
    }
}

/// Who the app is to subscription panels.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ClientInfo {
    /// `YPtun/<version>`.
    pub user_agent: String,
    pub app_version: String,
    pub hwid: String,
    pub app_id: String,
    pub device_os: String,
    pub os_version: String,
    pub device_model: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum RequestMode {
    Identity,
    Compatibility,
}

#[derive(Debug, Clone, Default)]
struct ImportSource {
    content: String,
    subscription_url: Option<String>,
    update_interval_hours: Option<i32>,
    compatibility: bool,
    headers: PanelHeaders,
    info_json: Option<String>,
    rich_json: Option<String>,
    /// Where the branding logo is looked up when no icon header came.
    page_url: Option<String>,
}

pub struct Repository {
    store: Box<dyn Store>,
    fetcher: Box<dyn HttpFetcher>,
    client: ClientInfo,
    now: Box<dyn Fn() -> i64 + Send + Sync>,
    /// Serializes mutations (a refresh holds it across its fetches).
    mutation: Mutex<()>,
    /// The decoded bundle keyed by the store's version token; readers never wait for a mutation.
    cache: RwLock<Option<(LocationBundleV4, Option<i64>)>>,
}

fn not_blank(s: &str) -> bool {
    !s.trim().is_empty()
}

impl Repository {
    pub fn new(store: Box<dyn Store>, fetcher: Box<dyn HttpFetcher>, client: ClientInfo) -> Self {
        let now = || std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0);
        Self::with_clock(store, fetcher, client, Box::new(now))
    }

    pub fn with_clock(store: Box<dyn Store>, fetcher: Box<dyn HttpFetcher>, client: ClientInfo, now: Box<dyn Fn() -> i64 + Send + Sync>) -> Self {
        Self { store, fetcher, client, now, mutation: Mutex::new(()), cache: RwLock::new(None) }
    }

    // ---------------- storage ----------------

    /// The current bundle (normalized; legacy storage migrated on first read).
    pub fn bundle(&self) -> LocationBundleV4 {
        if let Some(b) = self.cached() {
            return b;
        }
        let _guard = self.mutation.lock().unwrap();
        self.bundle_locked()
    }

    fn cached(&self) -> Option<LocationBundleV4> {
        let token = self.store.bundle_version_token()?;
        let cache = self.cache.read().unwrap();
        cache.as_ref().filter(|(_, t)| *t == Some(token)).map(|(b, _)| b.clone())
    }

    fn set_cache(&self, bundle: &LocationBundleV4, token: Option<i64>) {
        *self.cache.write().unwrap() = Some((bundle.clone(), token));
    }

    /// Loads the bundle; the caller holds `mutation`.
    fn bundle_locked(&self) -> LocationBundleV4 {
        if let Some(b) = self.cached() {
            return b;
        }
        let token = self.store.bundle_version_token();
        if let Some(stored) = self.store.load_bundle().map(|b| b.normalized()).filter(|b| !b.locations.is_empty()) {
            self.set_cache(&stored, token);
            return stored;
        }
        let legacy = self.migrate_legacy();
        if !legacy.locations.is_empty() {
            self.store.save_bundle(&legacy);
            self.set_cache(&legacy, self.store.bundle_version_token());
        } else {
            self.set_cache(&legacy, token);
        }
        legacy
    }

    /// Saves; the caller holds `mutation`.
    fn save_locked(&self, bundle: LocationBundleV4) {
        let n = bundle.normalized();
        self.store.save_bundle(&n);
        self.set_cache(&n, self.store.bundle_version_token());
    }

    /// Runs `f` on the bundle under the mutation lock and saves what it returns (`None` = no change).
    fn mutate<R>(&self, f: impl FnOnce(LocationBundleV4) -> (Option<LocationBundleV4>, R)) -> R {
        let _guard = self.mutation.lock().unwrap();
        let (next, r) = f(self.bundle_locked());
        if let Some(n) = next {
            self.save_locked(n);
        }
        r
    }

    pub fn save_bundle(&self, bundle: LocationBundleV4) {
        self.mutate(|_| (Some(bundle), ()))
    }

    fn migrate_legacy(&self) -> LocationBundleV4 {
        let legacy: Vec<LocationEntry> = self.store.load_legacy_locations().iter().filter_map(|(id, text)| importer::parse_single_location_text(text, Some(id), None)).collect();
        let active = self.store.load_legacy_active_id().filter(|id| legacy.iter().any(|e| &e.storage_id == id));
        LocationBundleV4 { active_location_id: active, locations: legacy, ..Default::default() }.normalized()
    }

    /// The backup JSON; locations of an encrypted subscription stay out of it.
    pub fn export_bundle(&self) -> String {
        let b = self.bundle();
        let locations = b.locations.iter().filter(|e| !e.metadata.as_ref().is_some_and(|m| m.locked)).cloned().collect();
        serde_json::to_string_pretty(&LocationBundleV4 { locations, ..b }).unwrap()
    }

    // ---------------- import ----------------

    /// Imports a pasted text, a link or a subscription URL. False when nothing usable was found.
    pub fn import_text(&self, text: &str, proxy: Option<&str>) -> bool {
        let Some((source, parsed)) = self.resolve_parsed_import(text, None, proxy) else { return false };
        let now = (self.now)();
        let imported = importer::stamp_imported(parsed.bundle, now, source.update_interval_hours);
        let mode = parsed.mode;
        self.mutate(|current| {
            // Re-importing the same subscription URL behaves like a refresh: fresh fields replace stale ones.
            let basis = match source.subscription_url.as_deref().map(str::trim).filter(|u| !u.is_empty()) {
                Some(u) => LocationBundleV4 { locations: current.locations.iter().filter(|e| e.subscription_url.as_deref().map(str::trim) != Some(u)).cloned().collect(), ..current.clone() },
                None => current,
            };
            (Some(importer::merge_imported_bundle(Some(&basis), &imported, mode == ImportMode::Restore)), ())
        });
        true
    }

    fn resolve_parsed_import(&self, text: &str, fallback_interval: Option<i32>, proxy: Option<&str>) -> Option<(ImportSource, ParsedImport)> {
        let input = importer::normalized_import_text(text);
        if input.is_empty() {
            return None;
        }
        let http = importer::is_http_url(&input);
        let mut source = self.resolve_source(&input, RequestMode::Identity, proxy, false).or_else(|| if http { self.resolve_source(&input, RequestMode::Compatibility, proxy, false) } else { None })?;
        let parse = |s: &ImportSource| -> Option<ParsedImport> {
            if is_stub(s) {
                return None;
            }
            self.parse_source(s, fallback_interval)
        };
        let mut parsed = parse(&source);
        if parsed.is_none() && http && !source.compatibility {
            if let Some(fb) = self.resolve_source(&input, RequestMode::Compatibility, proxy, false) {
                parsed = parse(&fb);
                source = fb;
            }
        }
        // Panels answer an unknown UA with a stub: last resort, present as a full Happ client.
        if parsed.is_none() && http && self.store.app_behavior().effective_subscription_ua() == SUB_UA_BOTH {
            if let Some(h) = self.resolve_source(&input, RequestMode::Identity, proxy, true) {
                if let Some(p) = parse(&h) {
                    source = h;
                    parsed = Some(p);
                }
            }
        }
        let mut parsed = parsed?;
        // The cosmetic logo lookup runs only for a subscription that actually parsed.
        if source.headers.icon_url.is_none() {
            if let Some(logo) = source.page_url.clone().and_then(|page| self.cached_branding_logo(&page, proxy)) {
                source.headers.icon_url = Some(logo);
                if let Some(p) = self.parse_source(&source, fallback_interval) {
                    parsed = p;
                }
            }
        }
        Some((source, parsed))
    }

    /// The page's logo, looked up once and remembered ("" = none).
    fn cached_branding_logo(&self, page_url: &str, proxy: Option<&str>) -> Option<String> {
        let key = format!("logo:{page_url}");
        let logo = match self.store.load_value(&key) {
            Some(cached) => cached,
            None => {
                let found = self.fetch_branding_logo(page_url, proxy);
                self.store.save_value(&key, &found);
                found
            }
        };
        (!logo.is_empty()).then_some(logo)
    }

    fn parse_source(&self, source: &ImportSource, fallback_interval: Option<i32>) -> Option<ParsedImport> {
        let initial = source.update_interval_hours.or(fallback_interval).or(source.subscription_url.as_ref().map(|_| DEFAULT_UPDATE_INTERVAL_HOURS));
        let primary = importer::subscription_metadata_from_body(source.info_json.as_deref().unwrap_or(&source.content));
        let secondary = importer::subscription_metadata_from_headers(&source.headers);
        let meta = importer::with_subscription_interval(importer::merge_subscription_metadata(primary, secondary), initial);
        let parsed = importer::parse_import(&importer::normalized_import_text(&source.content), source.subscription_url.as_deref(), initial, meta)?;
        Some(match source.rich_json.as_deref() {
            Some(rich) => ParsedImport { bundle: importer::enrich_from_rich_body(parsed.bundle, rich), ..parsed },
            None => parsed,
        })
    }

    fn subscription_user_agent(&self, s: &AppBehaviorSettings) -> String {
        match s.effective_subscription_ua() {
            SUB_UA_HAPP => HAPP_USER_AGENT.into(),
            SUB_UA_V2RAYTUN => format!("{V2RAYTUN_UA_PREFIX}{}", self.client.device_os.to_lowercase()),
            _ => self.client.user_agent.clone(),
        }
    }

    fn resolve_source(&self, text: &str, mode: RequestMode, proxy: Option<&str>, force_happ: bool) -> Option<ImportSource> {
        if text.trim().is_empty() {
            return None;
        }
        if !importer::is_http_url(text) {
            return Some(ImportSource { content: importer::normalized_import_text(text), ..Default::default() });
        }
        self.download(text, mode, proxy, force_happ)
    }

    fn get(&self, url: &str, headers: Vec<(String, String)>, proxy: Option<&str>) -> Option<HttpResponse> {
        self.fetcher.get(&HttpRequest { url: url.into(), headers, proxy: proxy.map(str::to_string) })
    }

    fn download(&self, url: &str, mode: RequestMode, proxy: Option<&str>, force_happ: bool) -> Option<ImportSource> {
        let privacy = self.store.app_behavior();
        let ua = if force_happ { HAPP_USER_AGENT.to_string() } else { self.subscription_user_agent(&privacy) };
        let identity = mode == RequestMode::Identity && privacy.send_device_info_to_subscriptions;
        let id_headers = |full: bool| -> Vec<(String, String)> {
            let mut h = Vec::new();
            if identity {
                h.push(("x-hwid".into(), self.client.hwid.clone()));
                if not_blank(&self.client.app_id) {
                    h.push((HEADER_APP_ID.into(), self.client.app_id.clone()));
                }
                if full {
                    h.push(("x-device-os".into(), self.client.device_os.clone()));
                    h.push(("x-ver-os".into(), self.client.os_version.clone()));
                    h.push(("x-device-model".into(), self.client.device_model.clone()));
                }
            }
            h
        };
        let mut headers = vec![("Accept".to_string(), MAIN_ACCEPT.to_string()), ("User-Agent".to_string(), ua.clone())];
        headers.extend(id_headers(true));
        let response = self.get(url, headers, proxy).filter(HttpResponse::ok)?;
        let content = importer::normalized_import_text(&response.body);
        if content.is_empty() {
            return None;
        }
        // Remnawave's rich JSON (user.expiresAt + traffic) lives at <url>/info.
        let mut info_headers = vec![("Accept".to_string(), "application/json".to_string()), ("User-Agent".to_string(), ua.clone())];
        info_headers.extend(id_headers(false));
        let info_json = self.get(&format!("{}/info", url.trim().trim_end_matches('/')), info_headers, proxy).filter(HttpResponse::ok).map(|r| r.body).filter(|b| not_blank(b));
        // FakeDNS, per-server descriptions and verbatim xhttp configs ship only in the Happ-UA body.
        let rich_json = if ua == HAPP_USER_AGENT {
            Some(response.body.clone())
        } else if privacy.effective_subscription_ua() != SUB_UA_BOTH {
            None
        } else {
            let mut h = vec![("Accept".to_string(), "application/json, text/plain, */*".to_string()), ("User-Agent".to_string(), HAPP_USER_AGENT.to_string())];
            h.extend(id_headers(false));
            self.get(url, h, proxy).filter(HttpResponse::ok).map(|r| r.body).filter(|b| {
                let t = b.trim_start();
                t.starts_with('[') || t.starts_with('{')
            })
        };
        let header = |n: &str| response.header(n).and_then(importer::decode_maybe_base64_header);
        let header_icon = header("profile-icon").or_else(|| header("profile-logo"));
        let page_url = header("profile-web-page-url").unwrap_or_else(|| url.to_string());
        Some(ImportSource {
            page_url: Some(page_url),
            content,
            subscription_url: Some(url.trim().to_string()),
            update_interval_hours: response.header("profile-update-interval").and_then(|v| v.trim().parse::<i32>().ok()).map(|h| h.clamp(MIN_UPDATE_INTERVAL_HOURS, MAX_UPDATE_INTERVAL_HOURS)),
            compatibility: mode == RequestMode::Compatibility,
            headers: PanelHeaders {
                profile_title: header("profile-title"),
                user_info: response.header("subscription-userinfo").map(|v| v.trim().to_string()),
                support_url: header("support-url"),
                web_page_url: header("profile-web-page-url"),
                icon_url: header_icon,
                announce: header("announce"),
                provider_id: response.header("providerid").map(str::trim).filter(|v| !v.is_empty()).map(str::to_string),
            },
            info_json,
            rich_json,
        })
    }

    /// Remnawave page logo: `brandingSettings.logoUrl` of `<origin>/assets/.app-config-v2.json`,
    /// served with the page's own session cookie to a browser UA. "" = none.
    pub fn fetch_branding_logo(&self, page_url: &str, proxy: Option<&str>) -> String {
        let page = page_url.trim();
        let Some(origin) = origin_of(page) else { return String::new() };
        let ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36".to_string();
        let Some(resp) = self.get(page, vec![("User-Agent".into(), ua.clone()), ("Accept".into(), "text/html".into())], proxy) else { return String::new() };
        let cookie = resp.headers_named("Set-Cookie").map(|c| c.split(';').next().unwrap_or("")).collect::<Vec<_>>().join("; ");
        let mut h = vec![("User-Agent".to_string(), ua), ("Accept".to_string(), "*/*".to_string())];
        if !cookie.is_empty() {
            h.push(("Cookie".into(), cookie));
        }
        let Some(cfg) = self.get(&format!("{origin}/assets/.app-config-v2.json"), h, proxy).filter(HttpResponse::ok) else { return String::new() };
        let logo = serde_json::from_str::<Value>(&cfg.body).ok().and_then(|v| v.get("brandingSettings")?.get("logoUrl")?.as_str().map(|s| s.trim().to_string()));
        // docs.rw = Remnawave's stock placeholder.
        logo.filter(|l| importer::is_http_url(l) && !l.contains("docs.rw")).unwrap_or_default()
    }

    // ---------------- refresh ----------------

    /// Refreshes every subscription (or only `only_urls`); returns how many succeeded.
    pub fn refresh_subscriptions(&self, only_urls: Option<&[String]>, proxy: Option<&str>) -> i32 {
        let _guard = self.mutation.lock().unwrap();
        let mut bundle = self.bundle_locked();
        let urls: Vec<String> = importer::subscription_groups(&bundle)
            .into_iter()
            .map(|(u, _)| u)
            .filter(|u| u != FREE_SERVERS_URL && only_urls.map_or(true, |o| o.iter().any(|x| x.trim() == u)))
            .collect();
        if urls.is_empty() {
            return 0;
        }
        let (mut ok, mut attempted) = (0, false);
        for url in &urls {
            let prev_interval = bundle.locations.iter().filter(|e| e.subscription_url.as_deref().map(str::trim) == Some(url.as_str())).find_map(|e| e.metadata.as_ref()?.subscription.as_ref()?.update_interval_hours);
            let outcome = self
                .resolve_parsed_import(url, prev_interval, proxy)
                .map(|(source, parsed)| RefreshedSubscription { parsed, update_interval_hours: source.update_interval_hours });
            let (next, success) = importer::apply_subscription_refresh(&bundle, url, outcome.as_ref(), (self.now)());
            bundle = next;
            if success {
                ok += 1;
            } else {
                attempted = true;
            }
        }
        if ok > 0 || attempted {
            self.save_locked(bundle);
        }
        ok
    }

    pub fn refresh_subscription(&self, url: &str, proxy: Option<&str>) -> i32 {
        let u = url.trim();
        if u.is_empty() {
            return 0;
        }
        self.refresh_subscriptions(Some(&[u.to_string()]), proxy)
    }

    /// Refreshes the subscriptions whose interval has elapsed.
    pub fn refresh_due_subscriptions(&self, retry_failed: bool, proxy: Option<&str>) -> i32 {
        let due = importer::due_subscription_urls(&self.bundle(), (self.now)(), retry_failed);
        if due.is_empty() {
            0
        } else {
            self.refresh_subscriptions(Some(&due), proxy)
        }
    }

    /// Refreshes the subscriptions that never reported an expiry.
    pub fn refresh_subscriptions_missing_expiry(&self, proxy: Option<&str>) -> i32 {
        let urls = importer::subscription_urls_missing_expiry(&self.bundle());
        if urls.is_empty() {
            0
        } else {
            self.refresh_subscriptions(Some(&urls), proxy)
        }
    }

    /// Reports each panel provider id at most once per day (Settings → Privacy can turn it off).
    pub fn report_provider_usage(&self) {
        let privacy = self.store.app_behavior();
        if !privacy.provider_report_enabled {
            return;
        }
        let mut ids: Vec<String> = Vec::new();
        for e in self.bundle().locations {
            if let Some(id) = e.metadata.and_then(|m| m.subscription).and_then(|s| s.provider_id).map(|s| s.trim().to_string()).filter(|s| !s.is_empty()) {
                if !ids.contains(&id) {
                    ids.push(id);
                }
            }
        }
        if ids.is_empty() {
            return;
        }
        let today = (self.now)() / 86_400_000;
        let mut state: BTreeMap<String, i64> = self
            .store
            .load_value(KEY_PROVIDER_REPORT)
            .and_then(|raw| serde_json::from_str::<BTreeMap<String, Value>>(&raw).ok())
            .map(|m| m.into_iter().filter_map(|(k, v)| v.as_i64().or_else(|| v.as_str()?.parse().ok()).map(|d| (k, d))).collect())
            .unwrap_or_default();
        let send = privacy.send_device_info_to_subscriptions;
        let mut changed = false;
        for id in &ids {
            if state.get(id) == Some(&today) {
                continue;
            }
            let mut h = vec![("User-Agent".to_string(), self.subscription_user_agent(&privacy))];
            if send {
                if not_blank(&self.client.hwid) {
                    h.push(("x-hwid".into(), self.client.hwid.clone()));
                }
                if not_blank(&self.client.app_id) {
                    h.push((HEADER_APP_ID.into(), self.client.app_id.clone()));
                }
                h.push(("x-device-os".into(), self.client.device_os.clone()));
                h.push(("x-ver-os".into(), self.client.os_version.clone()));
                h.push(("x-device-model".into(), self.client.device_model.clone()));
            }
            h.push(("x-app-version".into(), self.client.app_version.clone()));
            if self.get(&format!("{PROVIDER_CHECK_URL}{}", crate::uri::percent_encode(id)), h, None).is_some_and(|r| r.ok()) {
                state.insert(id.clone(), today);
                changed = true;
            }
        }
        let before = state.len();
        state.retain(|k, _| ids.contains(k));
        if changed || state.len() != before {
            self.store.save_value(KEY_PROVIDER_REPORT, &serde_json::to_string(&state).unwrap());
        }
    }

    // ---------------- subscription edits ----------------

    fn edit_group(&self, url: &str, f: impl Fn(&LocationEntry) -> LocationEntry) {
        let u = url.trim();
        if u.is_empty() {
            return;
        }
        self.mutate(|b| {
            let locations = b.locations.iter().map(|e| if e.subscription_url.as_deref().map(str::trim) == Some(u) { f(e).normalized() } else { e.clone() }).collect();
            (Some(LocationBundleV4 { locations, ..b }), ())
        })
    }

    pub fn set_subscription_update_interval(&self, url: &str, hours: i32) {
        let h = hours.clamp(MIN_UPDATE_INTERVAL_HOURS, MAX_UPDATE_INTERVAL_HOURS);
        self.edit_group(url, |e| {
            let sub = e.metadata.as_ref().and_then(|m| m.subscription.clone());
            let m = importer::refresh_state(
                e.metadata.clone(),
                h,
                sub.as_ref().and_then(|s| s.last_refresh_at_epoch_ms),
                sub.as_ref().and_then(|s| s.last_attempt_at_epoch_ms),
                sub.as_ref().map_or(true, |s| s.auto_update_enabled),
                sub.as_ref().and_then(|s| s.custom_name.clone()),
                sub.as_ref().and_then(|s| s.cascade_link.clone()),
            );
            LocationEntry { metadata: Some(m), ..e.clone() }
        })
    }

    pub fn set_subscription_auto_update(&self, url: &str, enabled: bool) {
        self.edit_group(url, |e| LocationEntry { metadata: Some(importer::edit_subscription(e.metadata.clone(), |s| s.auto_update_enabled = enabled)), ..e.clone() })
    }

    pub fn set_subscription_custom_name(&self, url: &str, name: &str) {
        self.edit_group(url, |e| LocationEntry { metadata: Some(importer::edit_subscription(e.metadata.clone(), |s| s.custom_name = Some(name.to_string()))), ..e.clone() })
    }

    /// A second proxy for every server of a subscription ("" clears). False for an unusable link.
    pub fn set_subscription_cascade(&self, url: &str, link: &str) -> bool {
        let u = url.trim().to_string();
        if u.is_empty() {
            return false;
        }
        let t = link.trim().to_string();
        let cascade: Option<ProxyProfile> = if t.is_empty() {
            None
        } else {
            match share_link::parse(&t).filter(|p| p.is_complete()) {
                Some(p) => Some(p),
                None => return false,
            }
        };
        self.mutate(|b| {
            let group: Vec<&LocationEntry> = b.locations.iter().filter(|e| e.subscription_url.as_deref().map(str::trim) == Some(u.as_str())).collect();
            if group.is_empty() {
                return (None, false);
            }
            // Clearing only undoes a cascade set here.
            if cascade.is_none() && group.iter().all(|e| e.metadata.as_ref().and_then(|m| m.subscription.as_ref()).and_then(|s| s.cascade_link.as_deref()).map_or(true, |l| l.trim().is_empty())) {
                return (None, true);
            }
            let locations = b
                .locations
                .iter()
                .map(|e| {
                    if e.subscription_url.as_deref().map(str::trim) != Some(u.as_str()) {
                        return e.clone();
                    }
                    let c = importer::with_cascade(e, cascade.as_ref());
                    let m = importer::edit_subscription(e.metadata.clone(), |s| s.cascade_link = (!t.is_empty()).then(|| t.clone()));
                    LocationEntry { metadata: Some(m), ..c }.normalized()
                })
                .collect();
            (Some(LocationBundleV4 { locations, ..b.clone() }), true)
        })
    }

    /// Points a subscription at a new URL (http(s), unused by another subscription).
    pub fn change_subscription_url(&self, old_url: &str, new_url: &str) -> bool {
        let (old, new) = (old_url.trim().to_string(), new_url.trim().to_string());
        if old.is_empty() || new == old || !importer::is_http_url(&new) || new.chars().any(char::is_whitespace) {
            return false;
        }
        self.mutate(|b| {
            let url_of = |e: &LocationEntry| e.subscription_url.as_deref().map(str::trim).map(str::to_string);
            if !b.locations.iter().any(|e| url_of(e).as_deref() == Some(old.as_str())) || b.locations.iter().any(|e| url_of(e).as_deref() == Some(new.as_str())) {
                return (None, false);
            }
            let locations = b.locations.iter().map(|e| if url_of(e).as_deref() == Some(old.as_str()) { LocationEntry { subscription_url: Some(new.clone()), ..e.clone() }.normalized() } else { e.clone() }).collect();
            (Some(LocationBundleV4 { locations, ..b.clone() }), true)
        })
    }

    // ---------------- location edits ----------------

    pub fn set_location_pinned(&self, storage_id: &str, pinned: bool) {
        self.edit_location(storage_id, |e| Some(LocationEntry { metadata: Some(LocationMetadata { pinned, ..e.metadata.clone().unwrap_or_default() }), ..e.clone() }))
    }

    /// The user's own VK call link for a VK-TURN location (kept across refreshes).
    pub fn set_location_vk_link(&self, storage_id: &str, link: &str) {
        let l = link.trim().to_string();
        self.edit_location(storage_id, |e| {
            let vk = e.vkturn.clone()?;
            Some(LocationEntry {
                vkturn: Some(crate::transports::VkTurnConfig { vk_link: l.clone(), ..vk }),
                metadata: Some(LocationMetadata { vk_link_override: Some(l.clone()), ..e.metadata.clone().unwrap_or_default() }),
                ..e.clone()
            })
        })
    }

    fn edit_location(&self, storage_id: &str, f: impl Fn(&LocationEntry) -> Option<LocationEntry>) {
        self.mutate(|b| {
            if !b.locations.iter().any(|e| e.storage_id == storage_id) {
                return (None, ());
            }
            let locations = b.locations.iter().map(|e| if e.storage_id == storage_id { f(e).map(|n| n.normalized()).unwrap_or_else(|| e.clone()) } else { e.clone() }).collect();
            (Some(LocationBundleV4 { locations, ..b }), ())
        })
    }

    /// Saves (in place, or appended) and selects a location.
    pub fn save_location(&self, storage_id: &str, location: &LocationConfig) {
        let id = if storage_id.trim().is_empty() { importer::storage_slug(&{ let n = location.display_name(); if n.trim().is_empty() { location.id.clone() } else { n } }) } else { storage_id.to_string() };
        self.mutate(|b| {
            let current = b.locations.iter().find(|e| e.storage_id == id);
            let e = LocationEntry::from(&id, location, current.and_then(|c| c.subscription_url.clone()), current.and_then(|c| c.metadata.clone()));
            let mut locations = b.locations.clone();
            match locations.iter_mut().find(|x| x.storage_id == id) {
                Some(slot) => *slot = e,
                None => locations.push(e),
            }
            (Some(LocationBundleV4 { active_location_id: Some(id.clone()), locations, ..b }), ())
        })
    }

    pub fn load_location(&self, storage_id: &str) -> Option<LocationConfig> {
        self.bundle().locations.into_iter().find(|e| e.storage_id == storage_id).map(|e| e.location())
    }

    pub fn delete_locations(&self, storage_ids: &[String]) {
        if storage_ids.is_empty() {
            return;
        }
        self.mutate(|b| {
            let active = b.active_location_id.clone().filter(|a| !storage_ids.contains(a));
            let locations = b.locations.iter().filter(|e| !storage_ids.contains(&e.storage_id)).cloned().collect();
            (Some(LocationBundleV4 { active_location_id: active, locations, ..b }), ())
        })
    }

    pub fn delete_location(&self, storage_id: &str) {
        self.delete_locations(&[storage_id.to_string()])
    }

    pub fn all_locations(&self) -> Vec<LocationEntry> {
        self.bundle().locations
    }

    pub fn active_location_id(&self) -> Option<String> {
        self.bundle().active_location_id
    }

    /// Selects a location (an unknown id clears the selection).
    pub fn set_active_location_id(&self, storage_id: Option<&str>) {
        self.mutate(|b| {
            let next = storage_id.filter(|id| b.locations.iter().any(|e| e.storage_id == *id)).map(str::to_string);
            (Some(LocationBundleV4 { active_location_id: next, ..b }), ())
        })
    }

    pub fn active_location(&self) -> Option<LocationEntry> {
        let b = self.bundle();
        b.locations.iter().find(|e| Some(&e.storage_id) == b.active_location_id.as_ref()).cloned()
    }
}

fn is_stub(s: &ImportSource) -> bool {
    importer::is_unsupported_client_stub(&s.content, s.headers.profile_title.as_deref(), s.headers.announce.as_deref())
}

fn origin_of(url: &str) -> Option<String> {
    let lower = url.to_lowercase();
    let scheme_len = if lower.starts_with("https://") {
        8
    } else if lower.starts_with("http://") {
        7
    } else {
        return None;
    };
    let rest = &url[scheme_len..];
    let end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    (end > 0).then(|| url[..scheme_len + end].to_string())
}
