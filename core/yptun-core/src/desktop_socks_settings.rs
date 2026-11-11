//! Port of `DesktopSocksProxySettings.kt` (jvmMain vpn): the local SOCKS inbound of the desktop
//! app -- address, port and optional credentials -- and its normalization rules.
//!
//! * Kotlin read the OS from the global `DesktopPaths.os`; here it is passed in (`normalized(os)`).
//! * Credentials are cut to 64 UTF-16 units like Kotlin `take(64)`, without splitting a character.

use serde::{Deserialize, Serialize};

use crate::desktop_native_assets::DesktopOs;
use crate::pac::{LOCAL_SOCKS_HOST, LOCAL_SOCKS_PORT};
use crate::util::is_blank;

pub const MIN_PORT: i32 = 1024;
pub const MAX_PORT: i32 = 65535;
pub const MAX_CREDENTIAL_LENGTH: usize = 64;
/// Port used when `secured` is off -- the default proxy port Windows/browsers expect.
pub const UNSECURED_PORT: i32 = 8080;

/// Serialized names are the Kotlin ones (`customPort`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct DesktopSocksProxySettings {
    pub host: String,
    pub port: i32,
    pub username: String,
    pub password: String,
    /// SOCKS authentication (user + password) on a port of your choosing. OFF by default: the
    /// local proxy then listens on the unsecured port with no credentials.
    pub secured: bool,
    /// Port the user picked for the credential-less listener; None = the standard unsecured port.
    #[serde(rename = "customPort")]
    pub custom_port: Option<i32>,
}

impl Default for DesktopSocksProxySettings {
    fn default() -> Self {
        Self {
            host: LOCAL_SOCKS_HOST.to_string(),
            port: i32::from(LOCAL_SOCKS_PORT),
            username: String::new(),
            password: String::new(),
            secured: false,
            custom_port: None,
        }
    }
}

impl DesktopSocksProxySettings {
    pub fn is_configured(&self) -> bool {
        !is_blank(&self.username) && !is_blank(&self.password)
    }

    pub fn normalized(&self, os: DesktopOs) -> Self {
        let host = if is_blank(&self.host) {
            LOCAL_SOCKS_HOST.to_string()
        } else {
            self.host.clone()
        };
        let custom_port = self.custom_port.filter(|p| is_valid_port(*p));
        // Unsecured is the default shape: no credentials, standard proxy port unless the user chose one.
        if !self.secured {
            return Self {
                host,
                port: custom_port.unwrap_or_else(|| unsecured_port(os)),
                username: String::new(),
                password: String::new(),
                secured: false,
                custom_port,
            };
        }
        Self {
            host,
            port: sanitize_port(Some(self.port)),
            username: take_utf16(&self.username, MAX_CREDENTIAL_LENGTH),
            password: take_utf16(&self.password, MAX_CREDENTIAL_LENGTH),
            secured: true,
            custom_port,
        }
    }
}

/// UNSECURED_PORT, except Linux: Steam's steamwebhelper holds 8080 there (Steam Deck).
pub fn unsecured_port(os: DesktopOs) -> i32 {
    if os == DesktopOs::Linux {
        i32::from(LOCAL_SOCKS_PORT)
    } else {
        UNSECURED_PORT
    }
}

pub fn is_valid_port(port: i32) -> bool {
    (MIN_PORT..=MAX_PORT).contains(&port)
}

pub fn sanitize_port(port: Option<i32>) -> i32 {
    port.filter(|p| is_valid_port(*p))
        .unwrap_or(i32::from(LOCAL_SOCKS_PORT))
}

fn take_utf16(s: &str, max: usize) -> String {
    let mut used = 0;
    let mut out = String::new();
    for c in s.chars() {
        used += c.len_utf16();
        if used > max {
            break;
        }
        out.push(c);
    }
    out
}
