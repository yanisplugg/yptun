//! Data model shared by every app. Field names on the wire match the Kotlin app's
//! kotlinx.serialization names, so locations it stored load unchanged.

use serde::{Deserialize, Serialize};

/// Engine that serves the local SOCKS5 the TUN bridge consumes.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, Default)]
pub enum EngineType {
    /// olcRTC (carrier/room/key).
    #[serde(rename = "stealth")]
    #[default]
    Stealth,
    /// One proxy outbound on sing-box or Xray.
    #[serde(rename = "standard")]
    Standard,
    /// A proxy dialled through olcRTC.
    #[serde(rename = "chain")]
    Chain,
    /// VK-TURN (free-turn-proxy / WDTT / csqtt).
    #[serde(rename = "vkturn")]
    VkTurn,
    /// MasterDnsVPN DNS tunnel.
    #[serde(rename = "masterdns")]
    MasterDns,
    /// OpenFlux document carriers.
    #[serde(rename = "openflux")]
    OpenFlux,
    /// snolc (Noise NK over TCP).
    #[serde(rename = "snolc")]
    Snolc,
}

impl EngineType {
    /// Lenient name → engine (old configs and panels use several spellings); unknown = Stealth.
    pub fn from_value(value: Option<&str>) -> Self {
        match value.map(|v| v.trim().to_lowercase()).as_deref() {
            Some("standard" | "singbox" | "sing-box" | "vless") => Self::Standard,
            Some("chain" | "stealth_chain" | "stealth+vless") => Self::Chain,
            Some("vkturn" | "vk-turn" | "freeturn") => Self::VkTurn,
            Some("masterdns" | "master-dns" | "masterdnsvpn" | "dnstt" | "dns-tt" | "dnstunnel") => {
                Self::MasterDns
            }
            Some("openflux" | "open-flux") => Self::OpenFlux,
            Some("snolc") => Self::Snolc,
            _ => Self::Stealth,
        }
    }
}

/// Backend that runs a [`ProxyProfile`] for the Standard/Chain engines.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, Default)]
pub enum ProxyCore {
    /// Xray when the profile requires it ([`ProxyProfile::requires_xray`]), sing-box otherwise.
    #[serde(rename = "auto")]
    #[default]
    Auto,
    #[serde(rename = "singbox")]
    SingBox,
    #[serde(rename = "xray")]
    Xray,
}

impl ProxyCore {
    pub fn from_value(value: Option<&str>) -> Self {
        match value.map(|v| v.trim().to_lowercase()).as_deref() {
            Some("singbox" | "sing-box") => Self::SingBox,
            Some("xray") => Self::Xray,
            _ => Self::Auto,
        }
    }
}

pub const TYPE_VLESS: &str = "vless";
pub const TYPE_VMESS: &str = "vmess";
pub const TYPE_TROJAN: &str = "trojan";
pub const TYPE_SHADOWSOCKS: &str = "shadowsocks";
pub const TYPE_AMNEZIAWG: &str = "amneziawg";
pub const TYPE_TRUSTTUNNEL: &str = "trusttunnel";
pub const TYPE_HYSTERIA2: &str = "hysteria2";
pub const TYPE_NAIVE: &str = "naive";

pub const NETWORK_TCP: &str = "tcp";
pub const NETWORK_WS: &str = "ws";
pub const NETWORK_GRPC: &str = "grpc";
pub const NETWORK_HTTP: &str = "http";
pub const NETWORK_HTTPUPGRADE: &str = "httpupgrade";
/// Xray-only transport (xhttp/splithttp); sing-box cannot speak it.
pub const NETWORK_XHTTP: &str = "xhttp";

pub const SECURITY_NONE: &str = "none";
pub const SECURITY_TLS: &str = "tls";
pub const SECURITY_REALITY: &str = "reality";

fn default_type() -> String {
    TYPE_VLESS.to_string()
}
fn default_cipher() -> String {
    "auto".to_string()
}
fn default_network() -> String {
    NETWORK_TCP.to_string()
}
fn default_security() -> String {
    SECURITY_NONE.to_string()
}

/// One proxy server parsed from a share link or a subscription. The shape follows sing-box's
/// outbounds; the raw fields carry protocols and configs that run verbatim.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct ProxyProfile {
    pub tag: String,
    #[serde(rename = "type", default = "default_type")]
    pub kind: String,
    pub server: String,
    #[serde(rename = "server_port")]
    pub server_port: i32,
    /// VLESS/VMess user id.
    pub uuid: String,
    /// Trojan/Shadowsocks/Naive/Hysteria2 password.
    pub password: String,
    /// Naive username.
    pub username: String,
    /// Shadowsocks method (cipher).
    pub method: String,
    /// VMess alterId (0 = AEAD).
    #[serde(rename = "alterId")]
    pub alter_id: i32,
    /// VMess cipher.
    #[serde(default = "default_cipher")]
    pub cipher: String,
    /// XTLS flow, e.g. `xtls-rprx-vision`.
    pub flow: String,
    /// Stream network: tcp, ws, grpc, http, httpupgrade, xhttp.
    #[serde(default = "default_network")]
    pub network: String,
    /// none, tls, reality.
    #[serde(default = "default_security")]
    pub security: String,
    pub sni: String,
    pub alpn: Vec<String>,
    /// uTLS fingerprint.
    pub fingerprint: String,
    #[serde(rename = "allowInsecure")]
    pub allow_insecure: bool,
    #[serde(rename = "realityPublicKey")]
    pub reality_public_key: String,
    #[serde(rename = "realityShortId")]
    pub reality_short_id: String,
    /// ws/httpupgrade/xhttp path or grpc serviceName.
    pub path: String,
    /// ws/http Host header.
    pub host: String,
    /// A raw sing-box outbound, used verbatim.
    #[serde(rename = "rawOutbound")]
    pub raw_outbound: Option<String>,
    /// A full Xray config, run verbatim on xray-core.
    #[serde(rename = "raw_xray_config")]
    pub raw_xray_config: Option<String>,
    /// The `route` block of a full sing-box JSON subscription, already remapped to the app's tags.
    #[serde(rename = "raw_singbox_route")]
    pub raw_singbox_route: Option<String>,
    /// AmneziaWG wg-quick INI.
    #[serde(rename = "awg_config")]
    pub awg_config: String,
    /// Trust Tunnel `tt://` deep link, kept verbatim.
    #[serde(rename = "tt_config")]
    pub tt_config: String,
    #[serde(rename = "hy2_obfs")]
    pub hy2_obfs: String,
    #[serde(rename = "hy2_obfs_password")]
    pub hy2_obfs_password: String,
    #[serde(rename = "hy2_up_mbps")]
    pub hy2_up_mbps: i32,
    #[serde(rename = "hy2_down_mbps")]
    pub hy2_down_mbps: i32,
    /// Port hopping spec, e.g. `443,5000-6000`.
    #[serde(rename = "hy2_ports")]
    pub hy2_ports: String,
    /// Naive over QUIC (`naive+quic://`).
    #[serde(rename = "naive_quic")]
    pub naive_quic: bool,
    /// VLESS Encryption (`encryption`, e.g. mlkem768x25519plus…); blank = none.
    #[serde(rename = "vless_encryption")]
    pub vless_encryption: String,
    /// xhttp `mode`.
    #[serde(rename = "xhttp_mode")]
    pub xhttp_mode: String,
    /// xhttp `extra` JSON, verbatim.
    #[serde(rename = "xhttp_extra")]
    pub xhttp_extra: String,
    /// streamSettings `finalmask` (`fm`), verbatim.
    #[serde(rename = "final_mask")]
    pub final_mask: String,
    /// TLS pinnedPeerCertSha256 (`pcs`), comma-separated lowercase hex.
    #[serde(rename = "pinned_cert_sha256")]
    pub pinned_cert_sha256: String,
    /// TLS verifyPeerCertByName (`vcn`).
    #[serde(rename = "verify_cert_name")]
    pub verify_cert_by_name: String,
    /// TLS echConfigList (`ech`).
    #[serde(rename = "ech_config")]
    pub ech_config_list: String,
    /// REALITY mldsa65Verify (`pqv`).
    #[serde(rename = "reality_pqv")]
    pub reality_mldsa65_verify: String,
    /// REALITY spiderX (`spx`).
    #[serde(rename = "reality_spx")]
    pub reality_spider_x: String,
}

impl Default for ProxyProfile {
    fn default() -> Self {
        Self {
            tag: String::new(),
            kind: default_type(),
            server: String::new(),
            server_port: 0,
            uuid: String::new(),
            password: String::new(),
            username: String::new(),
            method: String::new(),
            alter_id: 0,
            cipher: default_cipher(),
            flow: String::new(),
            network: default_network(),
            security: default_security(),
            sni: String::new(),
            alpn: Vec::new(),
            fingerprint: String::new(),
            allow_insecure: false,
            reality_public_key: String::new(),
            reality_short_id: String::new(),
            path: String::new(),
            host: String::new(),
            raw_outbound: None,
            raw_xray_config: None,
            raw_singbox_route: None,
            awg_config: String::new(),
            tt_config: String::new(),
            hy2_obfs: String::new(),
            hy2_obfs_password: String::new(),
            hy2_up_mbps: 0,
            hy2_down_mbps: 0,
            hy2_ports: String::new(),
            naive_quic: false,
            vless_encryption: String::new(),
            xhttp_mode: String::new(),
            xhttp_extra: String::new(),
            final_mask: String::new(),
            pinned_cert_sha256: String::new(),
            verify_cert_by_name: String::new(),
            ech_config_list: String::new(),
            reality_mldsa65_verify: String::new(),
            reality_spider_x: String::new(),
        }
    }
}

fn not_blank(s: &str) -> bool {
    !s.trim().is_empty()
}

fn not_blank_opt(s: &Option<String>) -> bool {
    s.as_deref().is_some_and(not_blank)
}

impl ProxyProfile {
    /// Only xray-core can serve this profile: sing-box speaks neither xhttp nor the Xray-only knobs.
    pub fn requires_xray(&self) -> bool {
        self.network == NETWORK_XHTTP
            || not_blank(&self.vless_encryption)
            || not_blank(&self.final_mask)
            || not_blank(&self.pinned_cert_sha256)
            || not_blank(&self.verify_cert_by_name)
            || not_blank(&self.ech_config_list)
    }

    pub fn is_complete(&self) -> bool {
        let port_ok = (1..=65535).contains(&self.server_port);
        match self.kind.as_str() {
            TYPE_HYSTERIA2 | TYPE_NAIVE => return not_blank(&self.server) && port_ok,
            TYPE_AMNEZIAWG => return not_blank(&self.awg_config),
            TYPE_TRUSTTUNNEL => return not_blank(&self.tt_config),
            _ => {}
        }
        if not_blank_opt(&self.raw_xray_config) || not_blank_opt(&self.raw_outbound) {
            return true;
        }
        if !not_blank(&self.server) || !port_ok {
            return false;
        }
        match self.kind.as_str() {
            TYPE_VLESS | TYPE_VMESS => not_blank(&self.uuid),
            TYPE_TROJAN => not_blank(&self.password),
            TYPE_SHADOWSOCKS => not_blank(&self.password) && not_blank(&self.method),
            _ => false,
        }
    }

    pub fn display_name(&self) -> String {
        if not_blank(&self.tag) {
            self.tag.clone()
        } else {
            format!("{}:{}", self.server, self.server_port)
        }
    }

    /// Duplicate-detection identity: no display tag, AmneziaWG INI without `#` comment lines and with
    /// normalised whitespace (the AmneziaWG name lives in a comment).
    pub fn dedup_normalized(&self) -> Self {
        let awg = self
            .awg_config
            .split('\n')
            .map(|l| l.replace('\r', "").trim().to_string())
            .filter(|l| !l.is_empty() && !l.starts_with('#'))
            .collect::<Vec<_>>()
            .join("\n");
        Self { tag: String::new(), awg_config: awg, ..self.clone() }
    }

    /// True when `other` is the same node (pasted twice, same raw config, or same server:port).
    pub fn is_same_node_as(&self, other: &Self) -> bool {
        if self.dedup_normalized() == other.dedup_normalized() {
            return true;
        }
        if not_blank_opt(&self.raw_xray_config) && self.raw_xray_config == other.raw_xray_config {
            return true;
        }
        if not_blank_opt(&self.raw_outbound) && self.raw_outbound == other.raw_outbound {
            return true;
        }
        not_blank(&self.server)
            && (1..=65535).contains(&self.server_port)
            && self.server.eq_ignore_ascii_case(&other.server)
            && self.server_port == other.server_port
    }
}
