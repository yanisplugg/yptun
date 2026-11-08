//! Stateful session: settings are parsed once (set_*) and reused for every config build.

use std::sync::{Arc, RwLock, RwLockReadGuard, RwLockWriteGuard};

use yptun_core::location::LocationConfig;
use yptun_core::model::{ProxyCore, ProxyProfile};
use yptun_core::routing::{RoutingProfile, RoutingProfilesState, RoutingRules, TrafficSettings};
use yptun_core::singbox::config::{self as singbox_config, SingBoxOptions};
use yptun_core::xray::config::{self as xray_config, RawOptions, XrayOptions};

use crate::{from_json, to_json, CoreKind, FfiError};

/// Per-start inbound/runtime options. Everything else comes from the session settings;
/// fields not listed keep the core defaults.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct InboundOptions {
    pub listen_host: String,
    pub listen_port: i32,
    pub socks_username: String,
    pub socks_password: String,
    pub log_level: String,
    /// The location's routing profile id (None = the global one).
    pub routing_profile_id: Option<String>,
    pub tun_mode: bool,
    pub hijack_dns: bool,
    pub auto_detect_interface: bool,
    pub mixed_inbound: bool,
    pub block_quic: bool,
    pub log_file_path: Option<String>,
    pub cache_file_path: Option<String>,
}

#[derive(Default)]
struct State {
    traffic: TrafficSettings,
    routing: RoutingRules,
    profiles: RoutingProfilesState,
    global_core: ProxyCore,
}

#[derive(uniffi::Object)]
pub struct CoreSession {
    state: RwLock<State>,
}

impl CoreSession {
    fn read(&self) -> RwLockReadGuard<'_, State> {
        self.state.read().unwrap_or_else(|e| e.into_inner())
    }

    fn write(&self) -> RwLockWriteGuard<'_, State> {
        self.state.write().unwrap_or_else(|e| e.into_inner())
    }

    fn routing_profile(st: &State, id: &Option<String>) -> Option<RoutingProfile> {
        st.profiles.resolve(id.as_deref()).cloned()
    }
}

#[uniffi::export]
impl CoreSession {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self { state: RwLock::new(State::default()) })
    }

    pub fn set_traffic(&self, traffic_json: String) -> Result<(), FfiError> {
        let t: TrafficSettings = from_json("TrafficSettings", &traffic_json)?;
        self.write().traffic = t;
        Ok(())
    }

    pub fn set_routing(&self, routing_json: String) -> Result<(), FfiError> {
        let r: RoutingRules = from_json("RoutingRules", &routing_json)?;
        self.write().routing = r;
        Ok(())
    }

    pub fn set_routing_profiles(&self, profiles_json: String) -> Result<(), FfiError> {
        let p: RoutingProfilesState = from_json("RoutingProfilesState", &profiles_json)?;
        self.write().profiles = p;
        Ok(())
    }

    pub fn set_global_core(&self, core: CoreKind) {
        self.write().global_core = core.into();
    }

    pub fn global_core(&self) -> CoreKind {
        self.read().global_core.into()
    }

    pub fn any_needs_geo_files(&self) -> bool {
        self.read().profiles.any_needs_geo_files()
    }

    /// RoutingProfile JSON in effect for a location (None = no profile).
    pub fn resolve_routing_profile(&self, location_profile_id: Option<String>) -> Result<Option<String>, FfiError> {
        Self::routing_profile(&self.read(), &location_profile_id).map(|p| to_json(&p)).transpose()
    }

    pub fn resolved_core(&self, location_json: String) -> Result<CoreKind, FfiError> {
        let l: LocationConfig = from_json("LocationConfig", &location_json)?;
        Ok(l.resolved_core(self.read().global_core).into())
    }

    pub fn build_singbox_config(&self, profile_json: String, options: InboundOptions) -> Result<String, FfiError> {
        let profile: ProxyProfile = from_json("ProxyProfile", &profile_json)?;
        let st = self.read();
        let routing_profile = Self::routing_profile(&st, &options.routing_profile_id);
        let o = SingBoxOptions {
            listen_port: options.listen_port,
            listen_host: options.listen_host,
            socks_username: options.socks_username,
            socks_password: options.socks_password,
            log_level: options.log_level,
            auto_detect_interface: options.auto_detect_interface,
            routing: st.routing.clone(),
            traffic: st.traffic.clone(),
            routing_profile,
            singbox_geosite_base: st.profiles.singbox_geosite_base.clone(),
            singbox_geoip_base: st.profiles.singbox_geoip_base.clone(),
            block_quic: options.block_quic,
            tun_mode: options.tun_mode,
            hijack_dns: options.hijack_dns,
            mixed_inbound: options.mixed_inbound,
            log_file_path: options.log_file_path,
            cache_file_path: options.cache_file_path,
            ..SingBoxOptions::default()
        };
        singbox_config::build(&profile, &o).map_err(|e| FfiError::Invalid(e.to_string()))
    }

    pub fn build_xray_config(&self, profile_json: String, options: InboundOptions) -> Result<String, FfiError> {
        let profile: ProxyProfile = from_json("ProxyProfile", &profile_json)?;
        let st = self.read();
        let routing_profile = Self::routing_profile(&st, &options.routing_profile_id);
        let o = XrayOptions {
            listen_port: options.listen_port,
            listen_host: options.listen_host,
            socks_username: options.socks_username,
            socks_password: options.socks_password,
            log_level: options.log_level,
            traffic: st.traffic.clone(),
            routing_profile,
            block_quic: options.block_quic,
            bypass_lan: st.routing.bypass_lan,
            ..XrayOptions::default()
        };
        Ok(xray_config::build(&profile, &o))
    }

    /// A user's own Xray JSON made runnable (only the SOCKS inbound is rewritten).
    pub fn prepare_raw_xray(&self, raw_config_json: String, options: InboundOptions) -> String {
        let st = self.read();
        let mut o = RawOptions::new(options.listen_port);
        o.routing_profile = Self::routing_profile(&st, &options.routing_profile_id);
        o.listen_host = options.listen_host;
        o.socks_username = options.socks_username;
        o.socks_password = options.socks_password;
        o.fake_dns_enabled = st.traffic.fake_dns_enabled;
        xray_config::prepare_raw(&raw_config_json, &o)
    }
}
