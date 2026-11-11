//! Subscription body → link text (port of the app's `SubscriptionDecoder`).
//!
//! Accepts a JSON object with a `links` / `ssConfLinks` array (Remnawave), a base64 blob of
//! newline-separated links (standard, url-safe or MIME), or a plain newline-separated list.

use base64::engine::general_purpose::{STANDARD, URL_SAFE};
use base64::Engine;

/// The body as plain link text: base64 decoded, a JSON `links` array flattened one per line, a plain
/// body returned untouched (its `#`/`##` metadata lines — `#desc:`, `#order:`, olcRTC headers —
/// matter to the parsers).
pub fn to_link_text(body: &str) -> String {
    let trimmed = body.trim();
    if let Some(links) = extract_json_links(trimmed) {
        return links.join("\n");
    }
    maybe_base64_decode(trimmed).unwrap_or_else(|| body.to_string())
}

/// The body as a flat list of non-empty, trimmed lines (links).
pub fn to_links(body: &str) -> Vec<String> {
    let trimmed = body.trim();
    let raw: Vec<String> = match extract_json_links(trimmed) {
        Some(links) => links,
        None => {
            let text = maybe_base64_decode(trimmed).unwrap_or_else(|| trimmed.to_string());
            text.split(['\n', '\r']).map(String::from).collect()
        }
    };
    raw.into_iter().map(|l| l.trim().to_string()).filter(|l| !l.is_empty()).collect()
}

fn extract_json_links(body: &str) -> Option<Vec<String>> {
    if !body.starts_with('{') {
        return None;
    }
    let root: serde_json::Value = serde_json::from_str(body).ok()?;
    let obj = root.as_object()?;
    let mut out = Vec::new();
    for key in ["links", "ssConfLinks"] {
        if let Some(arr) = obj.get(key).and_then(|v| v.as_array()) {
            for el in arr {
                match el {
                    serde_json::Value::String(s) => out.push(s.clone()),
                    serde_json::Value::Number(n) => out.push(n.to_string()),
                    serde_json::Value::Bool(b) => out.push(b.to_string()),
                    _ => {}
                }
            }
        }
    }
    (!out.is_empty()).then_some(out)
}

/// Decodes a base64 subscription blob, only when the result contains links (`://`).
pub fn maybe_base64_decode(value: &str) -> Option<String> {
    if value.contains("://") {
        return None;
    }
    let compact: String = value.chars().filter(|c| !matches!(c, '\n' | '\r' | ' ')).collect();
    if compact.len() < 8 {
        return None;
    }
    [Codec::Standard, Codec::UrlSafe, Codec::Mime]
        .into_iter()
        .filter_map(|c| c.decode(&compact))
        .find(|t| t.contains("://"))
}

/// Decodes a (possibly unpadded, possibly url-safe) base64 chunk, or None.
pub fn decode_base64_chunk(value: &str) -> Option<String> {
    let compact: String = value.trim().chars().filter(|c| !matches!(c, '\n' | '\r' | ' ')).collect();
    if compact.is_empty() {
        return None;
    }
    [Codec::UrlSafe, Codec::Standard, Codec::Mime].into_iter().find_map(|c| c.decode(&compact))
}

/// Raw bytes of a base64 chunk (url-safe or standard, padding optional).
pub fn decode_base64_bytes(value: &str) -> Option<Vec<u8>> {
    let compact: String = value.trim().chars().filter(|c| !c.is_whitespace()).collect();
    if compact.is_empty() {
        return None;
    }
    let padded = pad(&compact);
    URL_SAFE.decode(&padded).ok().or_else(|| STANDARD.decode(&padded).ok())
}

#[derive(Clone, Copy)]
enum Codec {
    Standard,
    UrlSafe,
    /// MIME: characters outside the alphabet are ignored.
    Mime,
}

impl Codec {
    fn decode(self, compact: &str) -> Option<String> {
        let bytes = match self {
            Codec::Standard => STANDARD.decode(pad(compact)).ok()?,
            Codec::UrlSafe => URL_SAFE.decode(pad(compact)).ok()?,
            Codec::Mime => {
                let filtered: String = compact
                    .chars()
                    .filter(|c| c.is_ascii_alphanumeric() || *c == '+' || *c == '/')
                    .collect();
                if filtered.is_empty() {
                    return None;
                }
                STANDARD.decode(pad(&filtered)).ok()?
            }
        };
        Some(String::from_utf8_lossy(&bytes).into_owned())
    }
}

fn pad(value: &str) -> String {
    let rem = value.len() % 4;
    if rem == 0 {
        value.to_string()
    } else {
        format!("{value}{}", "=".repeat(4 - rem))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plain_body_is_kept_with_metadata_lines() {
        let body = "vless://a@b:1#x\n#desc:x=y\n#order:x";
        assert_eq!(to_link_text(body), body);
    }

    #[test]
    fn base64_body_is_decoded() {
        let raw = "vless://a@b.com:443#A\nvless://c@d.com:443#B";
        let enc = STANDARD.encode(raw);
        assert_eq!(to_link_text(&enc), raw);
        assert_eq!(to_links(&enc).len(), 2);
    }

    #[test]
    fn json_links_are_flattened() {
        let body = r#"{"links":["vless://a@b:1","trojan://p@c:2"],"x":1}"#;
        assert_eq!(to_links(body), vec!["vless://a@b:1", "trojan://p@c:2"]);
    }

    #[test]
    fn unpadded_url_safe_chunk() {
        let enc = URL_SAFE.encode("aes-128-gcm:pw?>").trim_end_matches('=').to_string();
        assert_eq!(decode_base64_chunk(&enc).as_deref(), Some("aes-128-gcm:pw?>"));
    }
}
