//! Small URI helpers shared by the share-link parsers (port of the app's `UriCodec`).

use std::collections::BTreeMap;

/// Percent- and plus-decodes a URI component into UTF-8 (invalid sequences become U+FFFD).
/// A `%` that is not followed by two hex digits stays as is.
pub fn percent_decode(value: &str) -> String {
    if !value.contains('%') && !value.contains('+') {
        return value.to_string();
    }
    let bytes = value.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        let c = bytes[i];
        if c == b'%' && i + 2 < bytes.len() {
            match (hex_val(bytes[i + 1]), hex_val(bytes[i + 2])) {
                (Some(h), Some(l)) => {
                    out.push(h << 4 | l);
                    i += 3;
                }
                _ => {
                    out.push(c);
                    i += 1;
                }
            }
        } else if c == b'+' {
            out.push(b' ');
            i += 1;
        } else {
            out.push(c);
            i += 1;
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn hex_val(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}

/// Minimal RFC 3986 percent-encoding (unreserved characters stay, everything else is `%XX`).
pub fn percent_encode(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for b in value.bytes() {
        if b.is_ascii_alphanumeric() || b"-_.~".contains(&b) {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

/// Splits `host:port` or `[ipv6]:port` (a trailing `/path` is dropped: many panels emit
/// `host:443/?type=…`).
pub fn split_host_port(raw: &str) -> Option<(String, i32)> {
    let value = raw.split('/').next().unwrap_or("");
    if let Some(rest) = value.strip_prefix('[') {
        let close = rest.find(']')?;
        let host = &rest[..close];
        let port = rest[close + 1..].strip_prefix(':')?.parse::<i32>().ok()?;
        return Some((host.to_string(), port));
    }
    let colon = value.rfind(':')?;
    if colon == 0 {
        return None;
    }
    let port = value[colon + 1..].parse::<i32>().ok()?;
    Some((value[..colon].to_string(), port))
}

/// Parses an `a=b&c=d` query into a map (values percent-decoded; later keys win, like Kotlin's
/// `toMap`). `&amp;` from HTML-escaped public lists is accepted.
pub fn parse_query(query: &str) -> BTreeMap<String, String> {
    let mut map = BTreeMap::new();
    if query.trim().is_empty() {
        return map;
    }
    for pair in query.replace("&amp;", "&").split('&') {
        if pair.trim().is_empty() {
            continue;
        }
        match pair.find('=') {
            Some(eq) => map.insert(percent_decode(&pair[..eq]), percent_decode(&pair[eq + 1..])),
            None => map.insert(percent_decode(pair), String::new()),
        };
    }
    map
}

/// `substringBefore(delim)`: everything before the first `delim`, or the whole string.
pub(crate) fn before<'a>(s: &'a str, delim: char) -> &'a str {
    s.split_once(delim).map_or(s, |(a, _)| a)
}

/// `substringAfter(delim, "")`: everything after the first `delim`, or "".
pub(crate) fn after<'a>(s: &'a str, delim: char) -> &'a str {
    s.split_once(delim).map_or("", |(_, b)| b)
}

/// Comma-separated list, trimmed, empties dropped.
pub(crate) fn split_list(s: &str) -> Vec<String> {
    s.split(',').map(str::trim).filter(|v| !v.is_empty()).map(String::from).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_percent_plus_and_keeps_broken_escapes() {
        assert_eq!(percent_decode("My%20Server"), "My Server");
        assert_eq!(percent_decode("a+b"), "a b");
        assert_eq!(percent_decode("%F0%9F%87%AB%F0%9F%87%AEfinland"), "🇫🇮finland");
        assert_eq!(percent_decode("100%"), "100%");
        assert_eq!(percent_decode("%zz"), "%zz");
    }

    #[test]
    fn splits_host_port() {
        assert_eq!(split_host_port("a.com:443"), Some(("a.com".into(), 443)));
        assert_eq!(split_host_port("[2001:db8::1]:443"), Some(("2001:db8::1".into(), 443)));
        assert_eq!(split_host_port("a.com:443/?type=tcp"), Some(("a.com".into(), 443)));
        assert_eq!(split_host_port("a.com"), None);
    }

    #[test]
    fn parses_query() {
        let q = parse_query("a=1&amp;b=%2Fx&c");
        assert_eq!(q["a"], "1");
        assert_eq!(q["b"], "/x");
        assert_eq!(q["c"], "");
    }
}
