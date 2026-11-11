//! Reads and rewrites single parameters of an AmneziaWG/WireGuard wg-quick INI (port of
//! `AwgIniEditor`). Everything else — keys, addresses, comments, key order — stays as it was.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Section {
    Interface,
    Peer,
}

impl Section {
    pub fn header(self) -> &'static str {
        match self {
            Section::Interface => "Interface",
            Section::Peer => "Peer",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    /** Digits only. */
    Number,
    /** A value or a range ("1000000-2000000"). */
    Range,
    /** Free text on one line. */
    Text,
    /** On/off: written as `on`, the line removed when off. */
    Switch,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Knob {
    pub key: &'static str,
    pub hint: &'static str,
    pub kind: Kind,
    pub section: Section,
}

const fn k(key: &'static str, hint: &'static str, kind: Kind, section: Section) -> Knob {
    Knob { key, hint, kind, section }
}
use Kind::*;
use Section::*;

/// What the editor offers, in the order it shows them.
pub const KNOBS: &[Knob] = &[
    k("Jc", "4", Number, Interface), k("Jmin", "40", Number, Interface), k("Jmax", "70", Number, Interface),
    k("S1", "0", Number, Interface), k("S2", "0", Number, Interface), k("S3", "0", Number, Interface), k("S4", "0", Number, Interface),
    k("H1", "1", Range, Interface), k("H2", "2", Range, Interface), k("H3", "3", Range, Interface), k("H4", "4", Range, Interface),
    k("I1", "<b 0x…>", Text, Interface), k("I2", "", Text, Interface), k("I3", "", Text, Interface),
    k("I4", "", Text, Interface), k("I5", "", Text, Interface),
    k("J1", "", Text, Interface), k("J2", "", Text, Interface), k("J3", "", Text, Interface), k("Itime", "120", Number, Interface),
    k("MTU", "1280", Number, Interface), k("DNS", "1.1.1.1", Text, Interface),
    k("PersistentKeepalive", "25", Number, Peer),
    k("RandomTrailers", "", Switch, Interface), k("DisableCookies", "", Switch, Interface),
];

/// The connection itself — keys, addresses, endpoint.
pub const CONNECTION: &[Knob] = &[
    k("PrivateKey", "", Text, Interface),
    k("Address", "10.0.0.2/32, fd00::2/128", Text, Interface),
    k("PublicKey", "", Text, Peer),
    k("PresharedKey", "", Text, Peer),
    k("Endpoint", "host:51820", Text, Peer),
    k("AllowedIPs", "0.0.0.0/0, ::/0", Text, Peer),
];

/// Keys that make a wg-quick INI AmneziaWG rather than plain WireGuard (lower case).
pub const OBFUSCATION_KEYS: &[&str] = &[
    "jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4",
    "i1", "i2", "i3", "i4", "i5", "j1", "j2", "j3", "itime", "randomtrailers", "disablecookies",
];

const AWG_SEED: &[(&str, &str)] =
    &[("Jc", "4"), ("Jmin", "40"), ("Jmax", "70"), ("S1", "0"), ("S2", "0"), ("H1", "1"), ("H2", "2"), ("H3", "3"), ("H4", "4")];

pub fn is_awg(ini: &str) -> bool {
    OBFUSCATION_KEYS.iter().any(|key| !get(ini, key, Interface).is_empty())
}

/// The obfuscation parameters `ini` sets, as (key, value).
pub fn obfuscation(ini: &str) -> Vec<(String, String)> {
    KNOBS
        .iter()
        .filter(|kn| OBFUSCATION_KEYS.contains(&kn.key.to_lowercase().as_str()))
        .filter_map(|kn| {
            let v = get(ini, kn.key, Interface);
            (!v.is_empty()).then(|| (kn.key.to_string(), v))
        })
        .collect()
}

pub fn to_wireguard(ini: &str) -> String {
    OBFUSCATION_KEYS.iter().fold(ini.to_string(), |acc, key| set(&acc, key, "", Interface))
}

pub fn to_amneziawg(ini: &str) -> String {
    if is_awg(ini) {
        return ini.to_string();
    }
    AWG_SEED.iter().fold(ini.to_string(), |acc, (key, value)| set(&acc, key, value, Interface))
}

/// An empty config to fill in: the section layout and the usual defaults, no keys yet.
pub fn skeleton(awg: bool) -> String {
    let base = "[Interface]\nPrivateKey = \nAddress = 10.0.0.2/32\nDNS = 1.1.1.1\nMTU = 1280\n\n\
                [Peer]\nPublicKey = \nEndpoint = \nAllowedIPs = 0.0.0.0/0, ::/0\nPersistentKeepalive = 25";
    if awg {
        to_amneziawg(base)
    } else {
        base.to_string()
    }
}

/// The required parameters `ini` still lacks; empty when usable.
pub fn missing(ini: &str) -> Vec<&'static str> {
    [("PrivateKey", Interface), ("Address", Interface), ("PublicKey", Peer), ("Endpoint", Peer)]
        .into_iter()
        .filter(|(key, s)| get(ini, key, *s).is_empty())
        .map(|(key, _)| key)
        .collect()
}

/// `host` and `port` of the peer Endpoint (port 0 when absent or unreadable).
pub fn endpoint(ini: &str) -> (String, i32) {
    let value = get(ini, "Endpoint", Peer);
    let unbracket = |s: &str| s.strip_prefix('[').and_then(|s| s.strip_suffix(']')).unwrap_or(s).to_string();
    match value.rfind(':') {
        Some(colon) if colon > 0 => (unbracket(&value[..colon]), value[colon + 1..].parse().unwrap_or(0)),
        _ => (unbracket(&value), 0),
    }
}

/// What `raw` may contain for a field of `kind`.
pub fn filter(raw: &str, kind: Kind) -> String {
    match kind {
        Number => raw.chars().filter(char::is_ascii_digit).collect(),
        Range => raw.chars().filter(|c| c.is_ascii_digit() || *c == '-').collect(),
        Text => raw.replace(['\r', '\n'], ""),
        Switch => raw.to_string(),
    }
}

/// The current value of `key` in `section`, or "".
pub fn get(ini: &str, key: &str, section: Section) -> String {
    let lines: Vec<&str> = ini.split('\n').collect();
    match find(&lines, key, section) {
        Some(i) => lines[i].split_once('=').map(|(_, v)| v.trim()).unwrap_or(lines[i].trim()).to_string(),
        None => String::new(),
    }
}

/// `ini` with `key` set to `value` in `section`; a blank value removes the line.
pub fn set(ini: &str, key: &str, value: &str, section: Section) -> String {
    let clean = value.trim().replace(['\r', '\n'], "");
    let normalized = ini.replace("\r\n", "\n");
    let mut lines: Vec<String> = normalized.split('\n').map(str::to_string).collect();
    let refs: Vec<&str> = lines.iter().map(String::as_str).collect();
    if let Some(i) = find(&refs, key, section) {
        if clean.is_empty() {
            lines.remove(i);
        } else {
            let left = lines[i].split('=').next().unwrap_or("").trim_end().to_string();
            lines[i] = format!("{left} = {clean}");
        }
        return lines.join("\n");
    }
    if clean.is_empty() {
        return ini.to_string();
    }
    let header = format!("[{}]", section.header());
    let Some(start) = lines.iter().position(|l| l.trim().eq_ignore_ascii_case(&header)) else {
        while lines.last().is_some_and(|l| l.trim().is_empty()) {
            lines.pop();
        }
        lines.extend([String::new(), header, format!("{key} = {clean}")]);
        return lines.join("\n");
    };
    let mut end = start + 1;
    while end < lines.len() && !lines[end].trim().starts_with('[') {
        end += 1;
    }
    let mut at = end;
    while at - 1 > start && lines[at - 1].trim().is_empty() {
        at -= 1;
    }
    lines.insert(at, format!("{key} = {clean}"));
    lines.join("\n")
}

fn find(lines: &[&str], key: &str, section: Section) -> Option<usize> {
    let mut current: Option<&str> = None;
    for (i, raw) in lines.iter().enumerate() {
        let line = raw.trim();
        if line.starts_with('[') && line.ends_with(']') && line.len() >= 2 {
            current = Some(line[1..line.len() - 1].trim());
            continue;
        }
        if !current.is_some_and(|c| c.eq_ignore_ascii_case(section.header())) {
            continue;
        }
        if line.is_empty() || line.starts_with('#') || line.starts_with(';') {
            continue;
        }
        if let Some(eq) = line.find('=') {
            if eq > 0 && line[..eq].trim().eq_ignore_ascii_case(key) {
                return Some(i);
            }
        }
    }
    None
}
