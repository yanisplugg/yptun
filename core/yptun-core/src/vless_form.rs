//! The hand-made VLESS form (port of `VlessEditor`, Happ / v2rayN style): the option lists, what
//! every field writes into the main proxy, and which fields show for the chosen transport and
//! security. A raw sing-box/Xray JSON bypasses the typed fields, so only an empty or plain-VLESS
//! main proxy is editable here.

use crate::model::{
    ProxyProfile, NETWORK_GRPC, NETWORK_TCP, NETWORK_XHTTP, SECURITY_NONE, SECURITY_REALITY,
    SECURITY_TLS, TYPE_VLESS,
};
use crate::util::{filter_digits, is_blank, to_int_or_null};

pub const NETWORKS: [&str; 6] = ["tcp", "ws", "grpc", "httpupgrade", "xhttp", "http"];
pub const SECURITIES: [&str; 3] = [SECURITY_NONE, SECURITY_TLS, SECURITY_REALITY];
pub const FLOWS: [&str; 2] = ["", "xtls-rprx-vision"];
pub const FINGERPRINTS: [&str; 11] = [
    "",
    "chrome",
    "firefox",
    "safari",
    "ios",
    "android",
    "edge",
    "360",
    "qq",
    "random",
    "randomized",
];
pub const XHTTP_MODES: [&str; 5] = ["", "auto", "packet-up", "stream-up", "stream-one"];

/// The form's caption.
pub const CAPTION: &str = "VLESS — ручная настройка";

/// One field of the form, in the order the form shows them.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum VlessField {
    Server,
    Port,
    Uuid,
    Flow,
    Encryption,
    Network,
    /// ws/httpupgrade/xhttp/http path, or the grpc serviceName.
    Path,
    Host,
    XhttpMode,
    XhttpExtra,
    Security,
    Sni,
    Fingerprint,
    Alpn,
    PinnedCertSha256,
    EchConfig,
    RealityPublicKey,
    RealityShortId,
    RealitySpiderX,
    RealityMldsa65Verify,
}

impl VlessField {
    /// The choices of a dropdown field (`""` shows as "—"); `None` for a text field.
    pub fn options(self) -> Option<&'static [&'static str]> {
        match self {
            VlessField::Flow => Some(&FLOWS),
            VlessField::Network => Some(&NETWORKS),
            VlessField::XhttpMode => Some(&XHTTP_MODES),
            VlessField::Security => Some(&SECURITIES),
            VlessField::Fingerprint => Some(&FINGERPRINTS),
            _ => None,
        }
    }

    /// The field's label for `p` (the path is the grpc serviceName on grpc).
    pub fn label(self, p: &ProxyProfile) -> &'static str {
        match self {
            VlessField::Server => "Адрес сервера",
            VlessField::Port => "Порт",
            VlessField::Uuid => "UUID",
            VlessField::Flow => "Flow",
            VlessField::Encryption => "Encryption (vlessenc)",
            VlessField::Network => "Транспорт",
            VlessField::Path if p.network == NETWORK_GRPC => "serviceName",
            VlessField::Path => "Path",
            VlessField::Host => "Host",
            VlessField::XhttpMode => "xhttp mode",
            VlessField::XhttpExtra => "xhttp extra (JSON)",
            VlessField::Security => "Безопасность",
            VlessField::Sni => "SNI",
            VlessField::Fingerprint => "Fingerprint (uTLS)",
            VlessField::Alpn => "ALPN (через запятую)",
            VlessField::PinnedCertSha256 => "Pinned cert SHA-256",
            VlessField::EchConfig => "ECH config",
            VlessField::RealityPublicKey => "Public key (pbk)",
            VlessField::RealityShortId => "Short ID (sid)",
            VlessField::RealitySpiderX => "SpiderX (spx)",
            VlessField::RealityMldsa65Verify => "mldsa65 verify (pqv)",
        }
    }

    pub fn placeholder(self) -> &'static str {
        match self {
            VlessField::Encryption => "none",
            VlessField::XhttpExtra => "{\"xPaddingBytes\":\"100-1000\"}",
            VlessField::Alpn => "h2,http/1.1",
            _ => "",
        }
    }

    /// What the field shows for `p`.
    pub fn value(self, p: &ProxyProfile) -> String {
        match self {
            VlessField::Server => p.server.clone(),
            VlessField::Port if p.server_port > 0 => p.server_port.to_string(),
            VlessField::Port => String::new(),
            VlessField::Uuid => p.uuid.clone(),
            VlessField::Flow => p.flow.clone(),
            VlessField::Encryption => p.vless_encryption.clone(),
            VlessField::Network => p.network.clone(),
            VlessField::Path => p.path.clone(),
            VlessField::Host => p.host.clone(),
            VlessField::XhttpMode => p.xhttp_mode.clone(),
            VlessField::XhttpExtra => p.xhttp_extra.clone(),
            VlessField::Security => p.security.clone(),
            VlessField::Sni => p.sni.clone(),
            VlessField::Fingerprint => p.fingerprint.clone(),
            VlessField::Alpn => p.alpn.join(","),
            VlessField::PinnedCertSha256 => p.pinned_cert_sha256.clone(),
            VlessField::EchConfig => p.ech_config_list.clone(),
            VlessField::RealityPublicKey => p.reality_public_key.clone(),
            VlessField::RealityShortId => p.reality_short_id.clone(),
            VlessField::RealitySpiderX => p.reality_spider_x.clone(),
            VlessField::RealityMldsa65Verify => p.reality_mldsa65_verify.clone(),
        }
    }

    /// `p` with the field set to what was typed (or picked): text is trimmed, the port keeps its
    /// first five digits, `none` encryption is blank, ALPN is a comma list.
    pub fn apply(self, p: &ProxyProfile, typed: &str) -> ProxyProfile {
        let mut out = p.clone();
        let v = typed.trim().to_string();
        match self {
            VlessField::Server => out.server = v,
            VlessField::Port => {
                let digits: String = filter_digits(&v).chars().take(5).collect();
                out.server_port = to_int_or_null(&digits).unwrap_or(0);
            }
            VlessField::Uuid => out.uuid = v,
            VlessField::Flow => out.flow = typed.to_string(),
            VlessField::Encryption => {
                out.vless_encryption = if v == "none" { String::new() } else { v }
            }
            VlessField::Network => out.network = typed.to_string(),
            VlessField::Path => out.path = v,
            VlessField::Host => out.host = v,
            VlessField::XhttpMode => out.xhttp_mode = typed.to_string(),
            VlessField::XhttpExtra => out.xhttp_extra = v,
            VlessField::Security => out.security = typed.to_string(),
            VlessField::Sni => out.sni = v,
            VlessField::Fingerprint => out.fingerprint = typed.to_string(),
            VlessField::Alpn => {
                out.alpn = typed
                    .split(',')
                    .map(str::trim)
                    .filter(|s| !s.is_empty())
                    .map(str::to_string)
                    .collect();
            }
            VlessField::PinnedCertSha256 => out.pinned_cert_sha256 = v,
            VlessField::EchConfig => out.ech_config_list = v,
            VlessField::RealityPublicKey => out.reality_public_key = v,
            VlessField::RealityShortId => out.reality_short_id = v,
            VlessField::RealitySpiderX => out.reality_spider_x = v,
            VlessField::RealityMldsa65Verify => out.reality_mldsa65_verify = v,
        }
        out
    }
}

/// The fields the form shows for `p` (a fresh VLESS profile when there is none), in order.
pub fn visible_fields(p: Option<&ProxyProfile>) -> Vec<VlessField> {
    use VlessField::*;
    let fresh = ProxyProfile::default();
    let p = p.unwrap_or(&fresh);
    let tls = p.security == SECURITY_TLS;
    let reality = p.security == SECURITY_REALITY;
    let mut fields = vec![Server, Port, Uuid, Flow, Encryption, Network];
    if p.network != NETWORK_TCP {
        fields.push(Path);
        if p.network != NETWORK_GRPC {
            fields.push(Host);
        }
    }
    if p.network == NETWORK_XHTTP {
        fields.extend([XhttpMode, XhttpExtra]);
    }
    fields.push(Security);
    if tls || reality {
        fields.extend([Sni, Fingerprint]);
    }
    if tls {
        fields.extend([Alpn, PinnedCertSha256, EchConfig]);
    }
    if reality {
        fields.extend([
            RealityPublicKey,
            RealityShortId,
            RealitySpiderX,
            RealityMldsa65Verify,
        ]);
    }
    fields
}

/// The "allow insecure TLS" switch shows (TLS only).
pub fn shows_allow_insecure(p: Option<&ProxyProfile>) -> bool {
    p.map_or(SECURITY_NONE, |p| p.security.as_str()) == SECURITY_TLS
}

pub const ALLOW_INSECURE_TITLE: &str = "Разрешить небезопасный TLS";
pub const ALLOW_INSECURE_SUBTITLE: &str = "allowInsecure — не проверять сертификат";

/// No main proxy yet, or a plain VLESS one: a raw sing-box/Xray JSON bypasses the typed fields.
pub fn is_manually_editable_vless(proxy: Option<&ProxyProfile>) -> bool {
    proxy.is_none_or(|p| {
        p.kind == TYPE_VLESS
            && p.raw_xray_config.as_deref().is_none_or(is_blank)
            && p.raw_outbound.as_deref().is_none_or(is_blank)
    })
}
