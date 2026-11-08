//! olcRTC client command line + YAML config (port of the desktop OlcRtcCommand).
//!
//! The binary is started as "olcrtc <config.yaml>"; the YAML carries the room, the key, the
//! transport (with its vp8 / sei / video parameters from the location) and the local SOCKS listener.

use crate::location::{
    normalize_provider, LocationConfig, PROVIDER_WB_STREAM, TRANSPORT_SEICHANNEL, TRANSPORT_VIDEOCHANNEL, TRANSPORT_VP8CHANNEL,
};
use crate::pac::{LOCAL_SOCKS_HOST, LOCAL_SOCKS_PORT};

/// Preference-ordered resolver list for olcRTC signalling, mirroring Android's
/// FALLBACK_OLCRTC_DNS_SERVERS. olcrtc probes these in order with a real query and sticks to the
/// first that answers; Yandex goes first because Cloudflare/Google UDP/53 are routinely blocked on RU
/// networks. A single pinned resolver leaves the provider hostname unresolvable with no fallback.
pub const DNS_SERVERS: &str = "77.88.8.8:53,8.8.8.8:53,1.1.1.1:53";

pub struct OlcRtcCommand {
    pub binary: String,
    pub location: LocationConfig,
    pub socks_host: String,
    pub socks_port: u16,
    pub socks_user: String,
    pub socks_pass: String,
    pub data_dir: Option<String>,
}

impl OlcRtcCommand {
    /// Defaults as in Kotlin: SOCKS on PacServer's LOCAL_SOCKS_HOST:LOCAL_SOCKS_PORT, no auth, no data dir.
    pub fn new(binary: &str, location: LocationConfig) -> Self {
        OlcRtcCommand {
            binary: binary.to_string(),
            location,
            socks_host: LOCAL_SOCKS_HOST.to_string(),
            socks_port: LOCAL_SOCKS_PORT,
            socks_user: String::new(),
            socks_pass: String::new(),
            data_dir: None,
        }
    }

    pub fn args(&self, config_path: &str) -> Vec<String> {
        vec![self.binary.clone(), config_path.to_string()]
    }

    pub fn yaml(&self) -> String {
        let c = self.location.normalized();
        let provider = desktop_provider_arg(&c.bypass_provider);
        let mut y = String::new();
        let mut ln = |s: String| {
            y.push_str(&s);
            y.push('\n');
        };
        ln("mode: cnc".into());
        ln("link: direct".into());
        ln("auth:".into());
        ln(format!("  provider: {}", yaml_value(provider)));
        ln("room:".into());
        ln(format!("  id: {}", yaml_value(&c.id)));
        ln("crypto:".into());
        ln(format!("  key: {}", yaml_value(&c.key)));
        ln("net:".into());
        ln(format!("  transport: {}", yaml_value(&c.transport)));
        ln(format!("  dns: {}", yaml_value(DNS_SERVERS)));
        ln("socks:".into());
        ln(format!("  host: {}", yaml_value(&self.socks_host)));
        ln(format!("  port: {}", self.socks_port));
        if !self.socks_user.trim().is_empty() {
            ln(format!("  user: {}", yaml_value(&self.socks_user)));
            ln(format!("  pass: {}", yaml_value(&self.socks_pass)));
        }
        match c.transport.as_str() {
            TRANSPORT_VP8CHANNEL => {
                ln("vp8:".into());
                ln(format!("  fps: {}", c.vp8_fps));
                ln(format!("  batch_size: {}", c.vp8_batch));
            }
            // The location's own parameters (from its olcrtc:// link / subscription, docs/uri.md).
            TRANSPORT_SEICHANNEL => {
                let s = c.sei_options();
                ln("sei:".into());
                ln(format!("  fps: {}", s.fps));
                ln(format!("  batch_size: {}", s.batch));
                ln(format!("  fragment_size: {}", s.fragment_size));
                ln(format!("  ack_timeout_ms: {}", s.ack_timeout_ms));
            }
            TRANSPORT_VIDEOCHANNEL => {
                let v = c.video_options();
                ln("video:".into());
                ln(format!("  codec: {}", v.codec));
                ln(format!("  width: {}", v.width));
                ln(format!("  height: {}", v.height));
                ln(format!("  fps: {}", v.fps));
                ln(format!("  qr_size: {}", v.qr_size));
                ln(format!("  qr_recovery: {}", v.qr_recovery));
                ln(format!("  tile_module: {}", v.tile_module));
                ln(format!("  tile_rs: {}", v.tile_rs));
            }
            _ => {}
        }
        ln(format!("data: {}", yaml_value(self.data_dir.as_deref().unwrap_or("data"))));
        y
    }
}

pub fn desktop_provider_arg(provider: &str) -> &'static str {
    let normalized = normalize_provider(provider);
    if normalized == PROVIDER_WB_STREAM {
        "wbstream"
    } else {
        normalized
    }
}

/// Single-quoted YAML scalar ('' escapes a quote).
pub fn yaml_value(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('\'');
    out.push_str(&s.replace('\'', "''"));
    out.push('\'');
    out
}
