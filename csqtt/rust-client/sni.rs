use std::collections::{HashMap, HashSet};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, SocketAddrV4, SocketAddrV6};
use std::sync::{LazyLock, RwLock};
use std::time::Instant;

pub fn extract_tls_sni(payload: &[u8]) -> Option<&str> {
    if payload.len() < 5 {
        return None;
    }
    if payload[0] != 0x16 {
        return None;
    }
    let version_major = payload[1];
    let version_minor = payload[2];
    if version_major != 3 || version_minor > 4 {
        return None;
    }
    let record_len = u16::from_be_bytes([payload[3], payload[4]]) as usize;
    let record_end = (5 + record_len).min(payload.len());
    let mut cursor = 5;
    if cursor >= record_end || payload[cursor] != 0x01 {
        return None;
    }
    cursor += 1;
    if cursor + 3 > record_end {
        return None;
    }
    let hs_len = ((payload[cursor] as usize) << 16)
        | ((payload[cursor + 1] as usize) << 8)
        | (payload[cursor + 2] as usize);
    cursor += 3;
    let hs_end = (cursor + hs_len).min(record_end);
    if cursor + 34 > hs_end {
        return None;
    }
    cursor += 2 + 32;
    if cursor >= hs_end {
        return None;
    }
    let session_id_len = payload[cursor] as usize;
    cursor += 1 + session_id_len;
    if cursor + 2 > hs_end {
        return None;
    }
    let cipher_suites_len = u16::from_be_bytes([payload[cursor], payload[cursor + 1]]) as usize;
    cursor += 2 + cipher_suites_len;
    if cursor >= hs_end {
        return None;
    }
    let comp_methods_len = payload[cursor] as usize;
    cursor += 1 + comp_methods_len;
    if cursor + 2 > hs_end {
        return None;
    }
    let extensions_len = u16::from_be_bytes([payload[cursor], payload[cursor + 1]]) as usize;
    cursor += 2;
    let extensions_end = (cursor + extensions_len).min(hs_end);
    while cursor + 4 <= extensions_end {
        let ext_type = u16::from_be_bytes([payload[cursor], payload[cursor + 1]]);
        let ext_len = u16::from_be_bytes([payload[cursor + 2], payload[cursor + 3]]) as usize;
        cursor += 4;
        let ext_end = cursor + ext_len;
        if ext_end > extensions_end {
            break;
        }
        if ext_type == 0x0000 {
            if cursor + 2 > ext_end {
                return None;
            }
            let list_len = u16::from_be_bytes([payload[cursor], payload[cursor + 1]]) as usize;
            let mut list_cursor = cursor + 2;
            let list_end = list_cursor + list_len;
            if list_end > ext_end {
                return None;
            }
            while list_cursor + 3 <= list_end {
                let name_type = payload[list_cursor];
                let name_len =
                    u16::from_be_bytes([payload[list_cursor + 1], payload[list_cursor + 2]])
                        as usize;
                list_cursor += 3;
                if list_cursor + name_len > list_end {
                    return None;
                }
                if name_type == 0x00 {
                    let name_bytes = &payload[list_cursor..list_cursor + name_len];
                    return std::str::from_utf8(name_bytes).ok();
                }
                list_cursor += name_len;
            }
        }
        cursor = ext_end;
    }
    None
}

pub fn extract_dns_info(payload: &[u8]) -> Option<(String, bool, Vec<IpAddr>)> {
    if payload.len() < 12 {
        return None;
    }
    let flags = u16::from_be_bytes([payload[2], payload[3]]);
    let is_response = (flags & 0x8000) != 0;
    let qdcount = u16::from_be_bytes([payload[4], payload[5]]) as usize;
    let ancount = u16::from_be_bytes([payload[6], payload[7]]) as usize;
    if qdcount == 0 || qdcount > 20 {
        return None;
    }
    let mut domain = String::new();
    let mut jumped = false;
    let mut max_jumps = 5;
    let mut label_cursor = 12;
    let mut after_qname_cursor = None;

    while label_cursor < payload.len() {
        let len = payload[label_cursor] as usize;
        if len == 0 {
            if !jumped {
                label_cursor += 1;
                after_qname_cursor = Some(label_cursor);
            }
            break;
        }
        if (len & 0xc0) == 0xc0 {
            if label_cursor + 2 > payload.len() {
                return None;
            }
            if !jumped {
                after_qname_cursor = Some(label_cursor + 2);
                jumped = true;
            }
            let pointer = ((len & 0x3f) << 8) | (payload[label_cursor + 1] as usize);
            if pointer >= payload.len() || max_jumps == 0 {
                return None;
            }
            max_jumps -= 1;
            label_cursor = pointer;
            continue;
        }
        label_cursor += 1;
        if label_cursor + len > payload.len() {
            return None;
        }
        if !domain.is_empty() {
            domain.push('.');
        }
        let label_bytes = &payload[label_cursor..label_cursor + len];
        if let Ok(label_str) = std::str::from_utf8(label_bytes) {
            domain.push_str(&label_str.to_ascii_lowercase());
        } else {
            return None;
        }
        label_cursor += len;
    }

    let mut cur = after_qname_cursor?;
    if cur + 4 > payload.len() {
        return None;
    }
    cur += 4;

    let mut resolved_ips = Vec::new();
    if is_response && ancount > 0 {
        for _ in 0..ancount {
            if cur >= payload.len() {
                break;
            }
            while cur < payload.len() {
                let len = payload[cur] as usize;
                if len == 0 {
                    cur += 1;
                    break;
                }
                if (len & 0xc0) == 0xc0 {
                    cur += 2;
                    break;
                }
                cur += 1 + len;
            }
            if cur + 10 > payload.len() {
                break;
            }
            let rtype = u16::from_be_bytes([payload[cur], payload[cur + 1]]);
            let rdlength = u16::from_be_bytes([payload[cur + 8], payload[cur + 9]]) as usize;
            cur += 10;
            if cur + rdlength > payload.len() {
                break;
            }
            if rtype == 1 && rdlength == 4 {
                let ip = Ipv4Addr::new(
                    payload[cur],
                    payload[cur + 1],
                    payload[cur + 2],
                    payload[cur + 3],
                );
                resolved_ips.push(IpAddr::V4(ip));
            } else if rtype == 28 && rdlength == 16 {
                let mut ip_bytes = [0u8; 16];
                ip_bytes.copy_from_slice(&payload[cur..cur + 16]);
                let ip = Ipv6Addr::from(ip_bytes);
                resolved_ips.push(IpAddr::V6(ip));
            }
            cur += rdlength;
        }
    }

    if domain.is_empty() {
        None
    } else {
        Some((domain, is_response, resolved_ips))
    }
}

pub fn is_ru_zone(host: &str) -> bool {
    const ASCII_SUFFIXES: &[&[u8]] = &[
        b".ru",
        b".su",
        b".xn--p1ai",
        b".xn--80adxhks",
        b".xn--d1acj3b",
        b".by",
        b".xn--90ais",
        b".kz",
        b".xn--54b7fta0cc",
    ];
    const UTF8_SUFFIXES: &[&str] = &[
        ".\u{440}\u{444}",
        ".\u{43c}\u{43e}\u{441}\u{43a}\u{432}\u{430}",
        ".\u{434}\u{435}\u{442}\u{438}",
        ".\u{431}\u{435}\u{43b}",
    ];
    if host.eq_ignore_ascii_case("ru")
        || host.eq_ignore_ascii_case("su")
        || host.eq_ignore_ascii_case("xn--p1ai")
        || host == "\u{440}\u{444}"
        || host == "\u{43c}\u{43e}\u{441}\u{43a}\u{432}\u{430}"
        || host == "\u{434}\u{435}\u{442}\u{438}"
        || host == "\u{431}\u{435}\u{43b}"
    {
        return true;
    }
    let host_bytes = host.as_bytes();
    for suffix in ASCII_SUFFIXES {
        if host_bytes.len() >= suffix.len()
            && host_bytes[host_bytes.len() - suffix.len()..].eq_ignore_ascii_case(suffix)
        {
            return true;
        }
    }
    for suffix in UTF8_SUFFIXES {
        if host.ends_with(suffix) {
            return true;
        }
    }
    false
}

pub static TEMPLATE_DOMAINS_RAW: &str = include_str!("../domains.txt");

pub static TEMPLATE_DOMAINS_SET: LazyLock<HashSet<&'static str>> = LazyLock::new(|| {
    let mut set = HashSet::with_capacity(512);
    for line in TEMPLATE_DOMAINS_RAW.lines() {
        let trimmed = line.trim();
        if !trimmed.is_empty() && !trimmed.starts_with('#') {
            set.insert(trimmed);
        }
    }
    set
});

pub fn is_template_domain(sni: &str) -> bool {
    let host = sni.trim_end_matches('.').to_ascii_lowercase();
    if TEMPLATE_DOMAINS_SET.contains(host.as_str()) {
        return true;
    }
    let mut cursor = 0;
    while let Some(pos) = host[cursor..].find('.') {
        let sub = &host[cursor + pos + 1..];
        if TEMPLATE_DOMAINS_SET.contains(sub) {
            return true;
        }
        cursor += pos + 1;
    }
    false
}

pub fn is_domain_in_list(sni: &str, direct_domains: &[String]) -> bool {
    let sni_trimmed = sni.trim_end_matches('.');
    let sni_bytes = sni_trimmed.as_bytes();
    for domain in direct_domains {
        let domain_trimmed = domain.trim().trim_end_matches('.');
        if domain_trimmed.is_empty() {
            continue;
        }
        let domain_bytes = domain_trimmed.as_bytes();
        if sni_bytes.eq_ignore_ascii_case(domain_bytes) {
            return true;
        }
        if sni_bytes.len() > domain_bytes.len()
            && sni_bytes[sni_bytes.len() - domain_bytes.len() - 1] == b'.'
            && sni_bytes[sni_bytes.len() - domain_bytes.len()..].eq_ignore_ascii_case(domain_bytes)
        {
            return true;
        }
    }
    false
}

pub fn is_direct_domain(
    sni: &str,
    exclude_ru_zones: bool,
    template_domains: bool,
    direct_domains: &[String],
) -> bool {
    let sni_trimmed = sni.trim_end_matches('.');
    if exclude_ru_zones && is_ru_zone(sni_trimmed) {
        return true;
    }
    if template_domains && is_template_domain(sni_trimmed) {
        return true;
    }
    is_domain_in_list(sni_trimmed, direct_domains)
}

pub fn is_direct_domain_mode(
    sni: &str,
    is_whitelist: bool,
    exclude_ru_zones: bool,
    template_domains: bool,
    direct_domains: &[String],
) -> bool {
    let sni_trimmed = sni.trim_end_matches('.');
    if is_whitelist {
        !is_domain_in_list(sni_trimmed, direct_domains)
    } else {
        is_direct_domain(
            sni_trimmed,
            exclude_ru_zones,
            template_domains,
            direct_domains,
        )
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum IpRule {
    Single(Ipv4Addr),
    Cidr(Ipv4Addr, u8),
    Range(u32, u32),
}

impl IpRule {
    pub fn matches(&self, ip: &IpAddr) -> bool {
        match (self, ip) {
            (IpRule::Single(rule_ip), IpAddr::V4(addr)) => rule_ip == addr,
            (IpRule::Cidr(rule_ip, prefix_len), IpAddr::V4(addr)) => {
                if *prefix_len == 0 {
                    return true;
                }
                if *prefix_len > 32 {
                    return false;
                }
                let mask = !((1u32 << (32 - prefix_len)) - 1);
                (u32::from(*addr) & mask) == (u32::from(*rule_ip) & mask)
            }
            (IpRule::Range(start, end), IpAddr::V4(addr)) => {
                let val = u32::from(*addr);
                val >= *start && val <= *end
            }
            _ => false,
        }
    }
}

pub fn parse_ip_rules(input: &str) -> Vec<IpRule> {
    let mut rules = Vec::new();
    for token in input.split(|c: char| c.is_whitespace() || c == ',' || c == ';') {
        let cleaned = token
            .trim()
            .trim_matches(|c: char| c == ',' || c == ';' || c == '"' || c == '\'');
        if cleaned.is_empty() {
            continue;
        }
        if let Some((ip_str, prefix_str)) = cleaned.split_once('/') {
            if let Ok(ip) = ip_str.trim().parse::<Ipv4Addr>()
                && let Ok(prefix) = prefix_str.trim().parse::<u8>()
                && prefix <= 32
            {
                let rule = IpRule::Cidr(ip, prefix);
                if !rules.contains(&rule) {
                    rules.push(rule);
                }
            }
        } else if let Some((start_str, end_str)) = cleaned.split_once('-') {
            if let Ok(ip1) = start_str.trim().parse::<Ipv4Addr>()
                && let Ok(ip2) = end_str.trim().parse::<Ipv4Addr>()
            {
                let start = u32::from(ip1);
                let end = u32::from(ip2);
                if start <= end {
                    let rule = IpRule::Range(start, end);
                    if !rules.contains(&rule) {
                        rules.push(rule);
                    }
                }
            }
        } else if let Ok(ip) = cleaned.parse::<Ipv4Addr>() {
            let rule = IpRule::Single(ip);
            if !rules.contains(&rule) {
                rules.push(rule);
            }
        }
    }
    rules
}

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum RouteVerdict {
    Direct,
    Tunnel,
}

#[expect(clippy::too_many_arguments)]
pub fn evaluate_route(
    dst_ip: &IpAddr,
    domain: Option<&str>,
    domain_routing_enabled: bool,
    domain_is_whitelist: bool,
    exclude_ru_zones: bool,
    template_domains: bool,
    direct_domains: &[String],
    ip_routing_enabled: bool,
    ip_is_whitelist: bool,
    ip_rules: &[IpRule],
) -> RouteVerdict {
    if !domain_routing_enabled && !ip_routing_enabled {
        return RouteVerdict::Tunnel;
    }

    if domain_routing_enabled
        && !domain_is_whitelist
        && let Some(d) = domain
        && is_direct_domain(d, exclude_ru_zones, template_domains, direct_domains)
    {
        return RouteVerdict::Direct;
    }

    if ip_routing_enabled && !ip_is_whitelist && ip_rules.iter().any(|r| r.matches(dst_ip)) {
        return RouteVerdict::Direct;
    }

    if domain_routing_enabled
        && domain_is_whitelist
        && let Some(d) = domain
        && is_domain_in_list(d, direct_domains)
    {
        return RouteVerdict::Tunnel;
    }

    if ip_routing_enabled && ip_is_whitelist && ip_rules.iter().any(|r| r.matches(dst_ip)) {
        return RouteVerdict::Tunnel;
    }

    let has_whitelist = (domain_routing_enabled && domain_is_whitelist)
        || (ip_routing_enabled && ip_is_whitelist && !ip_rules.is_empty());

    if has_whitelist {
        RouteVerdict::Direct
    } else {
        RouteVerdict::Tunnel
    }
}

pub struct DnsDomainMap {
    entries: RwLock<HashMap<IpAddr, (String, Instant)>>,
    max_entries: usize,
}

impl DnsDomainMap {
    pub fn new(max_entries: usize) -> Self {
        Self {
            entries: RwLock::new(HashMap::with_capacity(max_entries.min(4096))),
            max_entries,
        }
    }

    pub fn insert(&self, ip: IpAddr, domain: String) {
        if let Ok(mut map) = self.entries.write() {
            if map.len() >= self.max_entries {
                let now = Instant::now();
                map.retain(|_, (_, ts)| now.duration_since(*ts).as_secs() < 3600);
                if map.len() >= self.max_entries {
                    map.clear();
                }
            }
            map.insert(ip, (domain, Instant::now()));
        }
    }

    pub fn insert_multiple(&self, ips: &[IpAddr], domain: &str) {
        if ips.is_empty() {
            return;
        }
        if let Ok(mut map) = self.entries.write() {
            let now = Instant::now();
            if map.len() + ips.len() >= self.max_entries {
                map.retain(|_, (_, ts)| now.duration_since(*ts).as_secs() < 3600);
                if map.len() >= self.max_entries {
                    map.clear();
                }
            }
            let d = domain.to_string();
            for &ip in ips {
                map.insert(ip, (d.clone(), now));
            }
        }
    }

    pub fn get(&self, ip: &IpAddr) -> Option<String> {
        if let Ok(map) = self.entries.read() {
            map.get(ip).map(|(d, _)| d.clone())
        } else {
            None
        }
    }

    pub fn clear(&self) {
        if let Ok(mut map) = self.entries.write() {
            map.clear();
        }
    }
}

impl Default for DnsDomainMap {
    fn default() -> Self {
        Self::new(4096)
    }
}

pub fn parse_direct_domains(input: &str) -> Vec<String> {
    let mut result = Vec::new();
    for token in input.split(|c: char| c.is_whitespace() || c == ',' || c == ';') {
        let mut cleaned = token.trim();
        if cleaned.is_empty() {
            continue;
        }
        if let Some(pos) = cleaned.find("://") {
            cleaned = &cleaned[pos + 3..];
        }
        if let Some(pos) = cleaned.find('/') {
            cleaned = &cleaned[..pos];
        }
        if let Some(pos) = cleaned.find(':') {
            cleaned = &cleaned[..pos];
        }
        if cleaned.to_ascii_lowercase().starts_with("www.") {
            cleaned = &cleaned[4..];
        }
        let cleaned_domain = cleaned
            .trim_matches(|c: char| c == '.' || c == ',' || c == ';' || c.is_whitespace())
            .to_lowercase();
        if cleaned_domain.len() >= 3
            && cleaned_domain.contains('.')
            && !result.contains(&cleaned_domain)
        {
            result.push(cleaned_domain);
        }
    }
    result
}

pub fn parse_ip_packet_sni(packet: &[u8]) -> Option<(&str, u16)> {
    if packet.is_empty() {
        return None;
    }
    let version = packet[0] >> 4;
    let (ihl, protocol, total_len) = match version {
        4 => {
            if packet.len() < 20 {
                return None;
            }
            let ihl = (packet[0] & 0x0f) as usize * 4;
            if ihl < 20 || ihl > packet.len() {
                return None;
            }
            let protocol = packet[9];
            let total_len = u16::from_be_bytes([packet[2], packet[3]]) as usize;
            (ihl, protocol, total_len.min(packet.len()))
        }
        6 => {
            if packet.len() < 40 {
                return None;
            }
            let protocol = packet[6];
            let payload_len = u16::from_be_bytes([packet[4], packet[5]]) as usize;
            (40, protocol, (40 + payload_len).min(packet.len()))
        }
        _ => return None,
    };
    if protocol != 6 || total_len <= ihl {
        return None;
    }
    let tcp_slice = &packet[ihl..total_len];
    if tcp_slice.len() < 20 {
        return None;
    }
    let dest_port = u16::from_be_bytes([tcp_slice[2], tcp_slice[3]]);
    let data_offset = ((tcp_slice[12] >> 4) as usize) * 4;
    if tcp_slice.len() <= data_offset {
        return None;
    }
    let payload = &tcp_slice[data_offset..];
    extract_tls_sni(payload).map(|sni| (sni, dest_port))
}

pub fn parse_ip_packet_dns(packet: &[u8]) -> Option<(String, bool, Vec<IpAddr>)> {
    if packet.is_empty() {
        return None;
    }
    let version = packet[0] >> 4;
    let (ihl, protocol, total_len) = match version {
        4 => {
            if packet.len() < 20 {
                return None;
            }
            let ihl = (packet[0] & 0x0f) as usize * 4;
            if ihl < 20 || ihl > packet.len() {
                return None;
            }
            let protocol = packet[9];
            let total_len = u16::from_be_bytes([packet[2], packet[3]]) as usize;
            (ihl, protocol, total_len.min(packet.len()))
        }
        6 => {
            if packet.len() < 40 {
                return None;
            }
            let protocol = packet[6];
            let payload_len = u16::from_be_bytes([packet[4], packet[5]]) as usize;
            (40, protocol, (40 + payload_len).min(packet.len()))
        }
        _ => return None,
    };
    if protocol != 17 || total_len <= ihl {
        return None;
    }
    let udp_slice = &packet[ihl..total_len];
    if udp_slice.len() < 8 {
        return None;
    }
    let src_port = u16::from_be_bytes([udp_slice[0], udp_slice[1]]);
    let dst_port = u16::from_be_bytes([udp_slice[2], udp_slice[3]]);
    if src_port != 53 && dst_port != 53 {
        return None;
    }
    let udp_len = u16::from_be_bytes([udp_slice[4], udp_slice[5]]) as usize;
    if udp_slice.len() < udp_len || udp_len < 8 {
        return None;
    }
    let payload = &udp_slice[8..udp_len];
    extract_dns_info(payload)
}

pub fn extract_dst_addr(packet: &[u8]) -> Option<SocketAddr> {
    if packet.is_empty() {
        return None;
    }
    let version = packet[0] >> 4;
    match version {
        4 => {
            if packet.len() < 20 {
                return None;
            }
            let ihl = (packet[0] & 0x0f) as usize * 4;
            if ihl < 20 || ihl > packet.len() {
                return None;
            }
            let protocol = packet[9];
            let dst_ip = Ipv4Addr::new(packet[16], packet[17], packet[18], packet[19]);
            if (protocol == 6 || protocol == 17) && packet.len() >= ihl + 4 {
                let dst_port = u16::from_be_bytes([packet[ihl + 2], packet[ihl + 3]]);
                Some(SocketAddr::V4(SocketAddrV4::new(dst_ip, dst_port)))
            } else {
                Some(SocketAddr::new(IpAddr::V4(dst_ip), 0))
            }
        }
        6 => {
            if packet.len() < 40 {
                return None;
            }
            let protocol = packet[6];
            let mut ip_bytes = [0u8; 16];
            ip_bytes.copy_from_slice(&packet[24..40]);
            let dst_ip = Ipv6Addr::from(ip_bytes);
            if (protocol == 6 || protocol == 17) && packet.len() >= 44 {
                let dst_port = u16::from_be_bytes([packet[42], packet[43]]);
                Some(SocketAddr::V6(SocketAddrV6::new(dst_ip, dst_port, 0, 0)))
            } else {
                Some(SocketAddr::new(IpAddr::V6(dst_ip), 0))
            }
        }
        _ => None,
    }
}

pub fn extract_src_addr(packet: &[u8]) -> Option<SocketAddr> {
    if packet.is_empty() {
        return None;
    }
    let version = packet[0] >> 4;
    match version {
        4 => {
            if packet.len() < 20 {
                return None;
            }
            let ihl = (packet[0] & 0x0f) as usize * 4;
            if ihl < 20 || ihl > packet.len() {
                return None;
            }
            let protocol = packet[9];
            let src_ip = Ipv4Addr::new(packet[12], packet[13], packet[14], packet[15]);
            if (protocol == 6 || protocol == 17) && packet.len() >= ihl + 4 {
                let src_port = u16::from_be_bytes([packet[ihl], packet[ihl + 1]]);
                Some(SocketAddr::V4(SocketAddrV4::new(src_ip, src_port)))
            } else {
                Some(SocketAddr::new(IpAddr::V4(src_ip), 0))
            }
        }
        6 => {
            if packet.len() < 40 {
                return None;
            }
            let protocol = packet[6];
            let mut ip_bytes = [0u8; 16];
            ip_bytes.copy_from_slice(&packet[8..24]);
            let src_ip = Ipv6Addr::from(ip_bytes);
            if (protocol == 6 || protocol == 17) && packet.len() >= 44 {
                let src_port = u16::from_be_bytes([packet[40], packet[41]]);
                Some(SocketAddr::V6(SocketAddrV6::new(src_ip, src_port, 0, 0)))
            } else {
                Some(SocketAddr::new(IpAddr::V6(src_ip), 0))
            }
        }
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_ru_zone_detection() {
        assert!(is_ru_zone("yandex.ru"));
        assert!(is_ru_zone("mail.ru"));
        assert!(is_ru_zone("gosuslugi.ru"));
        assert!(is_ru_zone("sub.domain.ru"));
        assert!(is_ru_zone("test.su"));
        assert!(is_ru_zone("test.рф"));
        assert!(is_ru_zone("test.xn--p1ai"));
        assert!(is_ru_zone("site.xn--80adxhks"));
        assert!(is_ru_zone("portal.xn--d1acj3b"));
        assert!(is_ru_zone("gov.by"));
        assert!(is_ru_zone("site.бел"));
        assert!(is_ru_zone("kaspi.kz"));
        assert!(is_ru_zone("ru"));
        assert!(is_ru_zone("su"));

        assert!(!is_ru_zone("google.com"));
        assert!(!is_ru_zone("youtube.com"));
        assert!(!is_ru_zone("discord.gg"));
        assert!(!is_ru_zone("github.com"));
        assert!(!is_ru_zone("rust-lang.org"));
        assert!(!is_ru_zone("notru.com"));
    }

    #[test]
    fn test_direct_domain_matching() {
        let custom = vec!["discord.com".to_string(), "telegram.org".to_string()];
        assert!(is_direct_domain("yandex.ru", true, false, &custom));
        assert!(is_direct_domain("discord.com", false, false, &custom));
        assert!(is_direct_domain("cdn.discord.com", false, false, &custom));
        assert!(is_direct_domain("telegram.org", false, false, &custom));
        assert!(is_direct_domain("api.telegram.org", false, false, &custom));
        assert!(!is_direct_domain("google.com", true, false, &custom));
        assert!(!is_direct_domain("yandex.ru", false, false, &custom));
        assert!(is_direct_domain("yandex.net", false, true, &custom));
        assert!(!is_direct_domain("yandex.net", false, false, &custom));
    }

    #[test]
    fn test_template_domains_matching() {
        assert!(is_template_domain("yandex.net"));
        assert!(is_template_domain("speed.yandex.net"));
        assert!(is_template_domain("static.yastatic.net"));
        assert!(is_template_domain("vk.com"));
        assert!(is_template_domain("m.vk.com"));
        assert!(is_template_domain("api.vk.me"));
        assert!(is_template_domain("wildberries.ru"));
        assert!(!is_template_domain("google.com"));
        assert!(!is_template_domain("netflix.com"));
    }

    #[test]
    fn test_parse_direct_domains() {
        let newline_input = "yandex.ru\nmail.ru\nkinopoisk.ru";
        let parsed = parse_direct_domains(newline_input);
        assert_eq!(parsed, vec!["yandex.ru", "mail.ru", "kinopoisk.ru"]);

        let comma_space_input = "yandex.ru, mail.ru, kinopoisk.ru";
        let parsed = parse_direct_domains(comma_space_input);
        assert_eq!(parsed, vec!["yandex.ru", "mail.ru", "kinopoisk.ru"]);

        let comma_no_space_input = "yandex.ru,mail.ru,kinopoisk.ru";
        let parsed = parse_direct_domains(comma_no_space_input);
        assert_eq!(parsed, vec!["yandex.ru", "mail.ru", "kinopoisk.ru"]);

        let space_input = "yandex.ru mail.ru kinopoisk.ru";
        let parsed = parse_direct_domains(space_input);
        assert_eq!(parsed, vec!["yandex.ru", "mail.ru", "kinopoisk.ru"]);

        let mixed_input = "https://yandex.ru/internet, http://www.mail.ru:8080/test; kinopoisk.ru\ndzen.ru ivi.ru";
        let parsed = parse_direct_domains(mixed_input);
        assert_eq!(
            parsed,
            vec!["yandex.ru", "mail.ru", "kinopoisk.ru", "dzen.ru", "ivi.ru"]
        );

        let dirty_input = ",,   \n\n  http://,, .ru, test.com, test.com, ";
        let parsed = parse_direct_domains(dirty_input);
        assert_eq!(parsed, vec!["test.com"]);
    }

    #[test]
    fn test_tls_sni_extraction() {
        let hostname = b"kremlin.ru";
        let mut valid_fixture = Vec::new();
        valid_fixture.extend_from_slice(&[0x16, 0x03, 0x01]);
        let rec_len = (4 + 2 + 32 + 1 + 4 + 2 + 2 + 4 + 2 + 1 + 2 + hostname.len()) as u16;
        valid_fixture.extend_from_slice(&rec_len.to_be_bytes());
        valid_fixture.push(0x01);
        let hs_len = (2 + 32 + 1 + 4 + 2 + 2 + 4 + 2 + 1 + 2 + hostname.len()) as u32;
        valid_fixture.push(((hs_len >> 16) & 0xff) as u8);
        valid_fixture.push(((hs_len >> 8) & 0xff) as u8);
        valid_fixture.push((hs_len & 0xff) as u8);
        valid_fixture.extend_from_slice(&[0x03, 0x03]);
        valid_fixture.extend_from_slice(&[0xaa; 32]);
        valid_fixture.push(0x00);
        valid_fixture.extend_from_slice(&[0x00, 0x02, 0x13, 0x01]);
        valid_fixture.extend_from_slice(&[0x01, 0x00]);
        let ext_total_len = (4 + 2 + 1 + 2 + hostname.len()) as u16;
        valid_fixture.extend_from_slice(&ext_total_len.to_be_bytes());
        valid_fixture.extend_from_slice(&[0x00, 0x00]);
        let sni_data_len = (2 + 1 + 2 + hostname.len()) as u16;
        valid_fixture.extend_from_slice(&sni_data_len.to_be_bytes());
        let list_len = (1 + 2 + hostname.len()) as u16;
        valid_fixture.extend_from_slice(&list_len.to_be_bytes());
        valid_fixture.push(0x00);
        let name_len = hostname.len() as u16;
        valid_fixture.extend_from_slice(&name_len.to_be_bytes());
        valid_fixture.extend_from_slice(hostname);

        let extracted = extract_tls_sni(&valid_fixture);
        assert_eq!(extracted, Some("kremlin.ru"));
    }

    #[test]
    fn test_tls_sni_extraction_from_ip_packet() {
        let hostname = b"yandex.ru";
        let mut tls = Vec::new();
        tls.extend_from_slice(&[0x16, 0x03, 0x01]);
        let rec_len = (4 + 2 + 32 + 1 + 4 + 2 + 2 + 4 + 2 + 1 + 2 + hostname.len()) as u16;
        tls.extend_from_slice(&rec_len.to_be_bytes());
        tls.push(0x01);
        let hs_len = (2 + 32 + 1 + 4 + 2 + 2 + 4 + 2 + 1 + 2 + hostname.len()) as u32;
        tls.push(((hs_len >> 16) & 0xff) as u8);
        tls.push(((hs_len >> 8) & 0xff) as u8);
        tls.push((hs_len & 0xff) as u8);
        tls.extend_from_slice(&[0x03, 0x03]);
        tls.extend_from_slice(&[0xbb; 32]);
        tls.push(0x00);
        tls.extend_from_slice(&[0x00, 0x02, 0x13, 0x01]);
        tls.extend_from_slice(&[0x01, 0x00]);
        let ext_total_len = (4 + 2 + 1 + 2 + hostname.len()) as u16;
        tls.extend_from_slice(&ext_total_len.to_be_bytes());
        tls.extend_from_slice(&[0x00, 0x00]);
        let sni_data_len = (2 + 1 + 2 + hostname.len()) as u16;
        tls.extend_from_slice(&sni_data_len.to_be_bytes());
        let list_len = (1 + 2 + hostname.len()) as u16;
        tls.extend_from_slice(&list_len.to_be_bytes());
        tls.push(0x00);
        let name_len = hostname.len() as u16;
        tls.extend_from_slice(&name_len.to_be_bytes());
        tls.extend_from_slice(hostname);

        let mut ip_packet = Vec::new();
        let ip_len = (20 + 20 + tls.len()) as u16;
        ip_packet.push(0x45);
        ip_packet.push(0x00);
        ip_packet.extend_from_slice(&ip_len.to_be_bytes());
        ip_packet.extend_from_slice(&[0x00, 0x01, 0x00, 0x00, 0x40, 0x06, 0x00, 0x00]);
        ip_packet.extend_from_slice(&[10, 0, 0, 2]);
        ip_packet.extend_from_slice(&[77, 88, 55, 60]);

        ip_packet.extend_from_slice(&[0xcd, 0x12]);
        ip_packet.extend_from_slice(&[0x01, 0xbb]);
        ip_packet.extend_from_slice(&[0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00]);
        ip_packet.push(0x50);
        ip_packet.push(0x18);
        ip_packet.extend_from_slice(&[0x10, 0x00, 0x00, 0x00, 0x00, 0x00]);

        ip_packet.extend_from_slice(&tls);

        let res = parse_ip_packet_sni(&ip_packet);
        assert_eq!(res, Some(("yandex.ru", 443)));
        let dst = extract_dst_addr(&ip_packet).unwrap();
        assert_eq!(dst.to_string(), "77.88.55.60:443");
    }

    #[test]
    fn test_dns_query_parsing() {
        let mut dns_payload = Vec::new();
        dns_payload.extend_from_slice(&[0x12, 0x34]);
        dns_payload.extend_from_slice(&[0x01, 0x00]);
        dns_payload.extend_from_slice(&[0x00, 0x01]);
        dns_payload.extend_from_slice(&[0x00, 0x00]);
        dns_payload.extend_from_slice(&[0x00, 0x00]);
        dns_payload.extend_from_slice(&[0x00, 0x00]);

        dns_payload.push(6);
        dns_payload.extend_from_slice(b"yandex");
        dns_payload.push(2);
        dns_payload.extend_from_slice(b"ru");
        dns_payload.push(0);

        dns_payload.extend_from_slice(&[0x00, 0x01]);
        dns_payload.extend_from_slice(&[0x00, 0x01]);

        let (domain, is_resp, ips) = extract_dns_info(&dns_payload).unwrap();
        assert_eq!(domain, "yandex.ru");
        assert!(!is_resp);
        assert!(ips.is_empty());
    }

    #[test]
    fn test_dns_response_parsing_with_ips() {
        let mut dns_payload = Vec::new();
        dns_payload.extend_from_slice(&[0x12, 0x34]);
        dns_payload.extend_from_slice(&[0x81, 0x80]);
        dns_payload.extend_from_slice(&[0x00, 0x01]);
        dns_payload.extend_from_slice(&[0x00, 0x01]);
        dns_payload.extend_from_slice(&[0x00, 0x00]);
        dns_payload.extend_from_slice(&[0x00, 0x00]);

        dns_payload.push(6);
        dns_payload.extend_from_slice(b"yandex");
        dns_payload.push(2);
        dns_payload.extend_from_slice(b"ru");
        dns_payload.push(0);

        dns_payload.extend_from_slice(&[0x00, 0x01]);
        dns_payload.extend_from_slice(&[0x00, 0x01]);

        dns_payload.extend_from_slice(&[0xc0, 0x0c]);
        dns_payload.extend_from_slice(&[0x00, 0x01]);
        dns_payload.extend_from_slice(&[0x00, 0x01]);
        dns_payload.extend_from_slice(&[0x00, 0x00, 0x01, 0x2c]);
        dns_payload.extend_from_slice(&[0x00, 0x04]);
        dns_payload.extend_from_slice(&[77, 88, 55, 60]);

        let (domain, is_resp, ips) = extract_dns_info(&dns_payload).unwrap();
        assert_eq!(domain, "yandex.ru");
        assert!(is_resp);
        assert_eq!(ips, vec![IpAddr::V4(Ipv4Addr::new(77, 88, 55, 60))]);
    }

    #[test]
    fn test_ip_packet_dns_query_and_response() {
        let mut dns_query = Vec::new();
        dns_query.extend_from_slice(&[0xab, 0xcd]);
        dns_query.extend_from_slice(&[0x01, 0x00]);
        dns_query.extend_from_slice(&[0x00, 0x01]);
        dns_query.extend_from_slice(&[0x00, 0x00]);
        dns_query.extend_from_slice(&[0x00, 0x00]);
        dns_query.extend_from_slice(&[0x00, 0x00]);

        dns_query.push(4);
        dns_query.extend_from_slice(b"mail");
        dns_query.push(2);
        dns_query.extend_from_slice(b"ru");
        dns_query.push(0);
        dns_query.extend_from_slice(&[0x00, 0x01, 0x00, 0x01]);

        let udp_len = (8 + dns_query.len()) as u16;
        let mut udp = Vec::new();
        udp.extend_from_slice(&[0xca, 0xfe]);
        udp.extend_from_slice(&[0x00, 0x35]);
        udp.extend_from_slice(&udp_len.to_be_bytes());
        udp.extend_from_slice(&[0x00, 0x00]);
        udp.extend_from_slice(&dns_query);

        let ip_len = (20 + udp.len()) as u16;
        let mut ip_pkt = Vec::new();
        ip_pkt.push(0x45);
        ip_pkt.push(0x00);
        ip_pkt.extend_from_slice(&ip_len.to_be_bytes());
        ip_pkt.extend_from_slice(&[0x00, 0x02, 0x00, 0x00, 0x40, 0x11, 0x00, 0x00]);
        ip_pkt.extend_from_slice(&[10, 0, 0, 2]);
        ip_pkt.extend_from_slice(&[1, 1, 1, 1]);
        ip_pkt.extend_from_slice(&udp);

        let parsed = parse_ip_packet_dns(&ip_pkt);
        assert!(parsed.is_some());
        let (domain, is_resp, _) = parsed.unwrap();
        assert_eq!(domain, "mail.ru");
        assert!(!is_resp);
    }

    #[test]
    fn test_domain_whitelist_inversion_direct_routing() {
        let white_domains = vec!["special.com".to_string(), "vpn-only.org".to_string()];
        assert!(!is_direct_domain_mode(
            "special.com",
            true,
            false,
            false,
            &white_domains
        ));
        assert!(!is_direct_domain_mode(
            "sub.special.com",
            true,
            false,
            false,
            &white_domains
        ));
        assert!(!is_direct_domain_mode(
            "vpn-only.org",
            true,
            false,
            false,
            &white_domains
        ));
        assert!(is_direct_domain_mode(
            "google.com",
            true,
            false,
            false,
            &white_domains
        ));
        assert!(is_direct_domain_mode(
            "yandex.ru",
            true,
            false,
            false,
            &white_domains
        ));
    }

    #[test]
    fn test_domain_blacklist_direct_routing() {
        let black_domains = vec!["blocked.com".to_string(), "direct.org".to_string()];
        assert!(is_direct_domain_mode(
            "blocked.com",
            false,
            false,
            false,
            &black_domains
        ));
        assert!(is_direct_domain_mode(
            "sub.blocked.com",
            false,
            false,
            false,
            &black_domains
        ));
        assert!(!is_direct_domain_mode(
            "normal.com",
            false,
            false,
            false,
            &black_domains
        ));
    }

    #[test]
    fn test_ip_rules_parsing_cidr_single_and_range() {
        let rules_str = "1.2.3.4, 10.0.0.0/8; 192.168.1.1-192.168.1.50, invalid-ip, 999.999.999.999, 10.0.0.1/33";
        let rules = parse_ip_rules(rules_str);
        assert_eq!(rules.len(), 3);
        assert_eq!(rules[0], IpRule::Single(Ipv4Addr::new(1, 2, 3, 4)));
        assert_eq!(rules[1], IpRule::Cidr(Ipv4Addr::new(10, 0, 0, 0), 8));
        assert_eq!(
            rules[2],
            IpRule::Range(
                u32::from(Ipv4Addr::new(192, 168, 1, 1)),
                u32::from(Ipv4Addr::new(192, 168, 1, 50))
            )
        );
    }

    fn ip_verdict(ip: &IpAddr, enabled: bool, whitelist: bool, rules: &[IpRule]) -> RouteVerdict {
        evaluate_route(
            ip,
            None,
            false,
            false,
            false,
            false,
            &[],
            enabled,
            whitelist,
            rules,
        )
    }

    #[test]
    fn test_ip_direct_mode_blacklist_and_whitelist() {
        let rules = parse_ip_rules("1.1.1.1, 10.0.0.0/24, 172.16.0.10-172.16.0.20");
        let ip_1_1_1_1 = IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1));
        let ip_10_0_0_50 = IpAddr::V4(Ipv4Addr::new(10, 0, 0, 50));
        let ip_172_16_0_15 = IpAddr::V4(Ipv4Addr::new(172, 16, 0, 15));
        let ip_8_8_8_8 = IpAddr::V4(Ipv4Addr::new(8, 8, 8, 8));

        assert_eq!(
            ip_verdict(&ip_1_1_1_1, true, false, &rules),
            RouteVerdict::Direct
        );
        assert_eq!(
            ip_verdict(&ip_10_0_0_50, true, false, &rules),
            RouteVerdict::Direct
        );
        assert_eq!(
            ip_verdict(&ip_172_16_0_15, true, false, &rules),
            RouteVerdict::Direct
        );
        assert_eq!(
            ip_verdict(&ip_8_8_8_8, true, false, &rules),
            RouteVerdict::Tunnel
        );

        assert_eq!(
            ip_verdict(&ip_1_1_1_1, true, true, &rules),
            RouteVerdict::Tunnel
        );
        assert_eq!(
            ip_verdict(&ip_10_0_0_50, true, true, &rules),
            RouteVerdict::Tunnel
        );
        assert_eq!(
            ip_verdict(&ip_172_16_0_15, true, true, &rules),
            RouteVerdict::Tunnel
        );
        assert_eq!(
            ip_verdict(&ip_8_8_8_8, true, true, &rules),
            RouteVerdict::Direct
        );

        assert_eq!(
            ip_verdict(&ip_1_1_1_1, false, false, &rules),
            RouteVerdict::Tunnel
        );
    }

    #[test]
    fn test_ip_edge_cases_cidr_0_and_32_and_invalid_ranges() {
        let rules_cidr0 = parse_ip_rules("0.0.0.0/0");
        let ip1 = IpAddr::V4(Ipv4Addr::new(1, 2, 3, 4));
        let ip2 = IpAddr::V4(Ipv4Addr::new(255, 255, 255, 255));
        assert_eq!(
            ip_verdict(&ip1, true, false, &rules_cidr0),
            RouteVerdict::Direct
        );
        assert_eq!(
            ip_verdict(&ip2, true, false, &rules_cidr0),
            RouteVerdict::Direct
        );

        let rules_cidr32 = parse_ip_rules("192.168.1.100/32");
        let ip_match = IpAddr::V4(Ipv4Addr::new(192, 168, 1, 100));
        let ip_no_match = IpAddr::V4(Ipv4Addr::new(192, 168, 1, 101));
        assert_eq!(
            ip_verdict(&ip_match, true, false, &rules_cidr32),
            RouteVerdict::Direct
        );
        assert_eq!(
            ip_verdict(&ip_no_match, true, false, &rules_cidr32),
            RouteVerdict::Tunnel
        );

        let invalid_and_valid = parse_ip_rules("10.0.0.20-10.0.0.10 8.8.8.8");
        let ip_mid = IpAddr::V4(Ipv4Addr::new(10, 0, 0, 15));
        assert_eq!(
            ip_verdict(&ip_mid, true, false, &invalid_and_valid),
            RouteVerdict::Tunnel
        );
    }

    #[test]
    fn test_dns_domain_map() {
        let map = DnsDomainMap::new(10);
        let ip1 = IpAddr::V4(Ipv4Addr::new(1, 2, 3, 4));
        let ip2 = IpAddr::V4(Ipv4Addr::new(5, 6, 7, 8));

        map.insert(ip1, "example.com".to_string());
        assert_eq!(map.get(&ip1), Some("example.com".to_string()));
        assert_eq!(map.get(&ip2), None);

        map.insert_multiple(&[ip2], "vk.com");
        assert_eq!(map.get(&ip2), Some("vk.com".to_string()));
    }

    #[test]
    fn test_evaluate_route_universal_matrix() {
        let ip_rules = parse_ip_rules("1.1.1.1, 10.0.0.0/8, 172.16.0.0/16");
        let direct_domains = vec!["vk.com".to_string(), "yandex.ru".to_string()];

        let ip_1_1_1_1 = IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1));
        let ip_10_1_2_3 = IpAddr::V4(Ipv4Addr::new(10, 1, 2, 3));
        let ip_8_8_8_8 = IpAddr::V4(Ipv4Addr::new(8, 8, 8, 8));
        let ip_77_88_8_8 = IpAddr::V4(Ipv4Addr::new(77, 88, 8, 8));

        assert_eq!(
            evaluate_route(
                &ip_1_1_1_1,
                Some("vk.com"),
                false,
                false,
                false,
                false,
                &[],
                false,
                false,
                &[]
            ),
            RouteVerdict::Tunnel
        );

        assert_eq!(
            evaluate_route(
                &ip_8_8_8_8,
                Some("vk.com"),
                true,
                false,
                true,
                false,
                &direct_domains,
                true,
                true,
                &ip_rules
            ),
            RouteVerdict::Direct
        );
        assert_eq!(
            evaluate_route(
                &ip_77_88_8_8,
                Some("gosuslugi.ru"),
                true,
                false,
                true,
                false,
                &direct_domains,
                true,
                true,
                &ip_rules
            ),
            RouteVerdict::Direct
        );
        assert_eq!(
            evaluate_route(
                &ip_1_1_1_1,
                Some("google.com"),
                true,
                false,
                true,
                false,
                &direct_domains,
                true,
                true,
                &ip_rules
            ),
            RouteVerdict::Tunnel
        );
        assert_eq!(
            evaluate_route(
                &ip_10_1_2_3,
                None,
                true,
                false,
                true,
                false,
                &direct_domains,
                true,
                true,
                &ip_rules
            ),
            RouteVerdict::Tunnel
        );
        assert_eq!(
            evaluate_route(
                &ip_8_8_8_8,
                Some("cloudflare.com"),
                true,
                false,
                true,
                false,
                &direct_domains,
                true,
                true,
                &ip_rules
            ),
            RouteVerdict::Direct
        );
        assert_eq!(
            evaluate_route(
                &ip_8_8_8_8,
                None,
                true,
                false,
                true,
                false,
                &direct_domains,
                true,
                true,
                &ip_rules
            ),
            RouteVerdict::Direct
        );
        assert_eq!(
            evaluate_route(
                &ip_1_1_1_1,
                Some("vk.com"),
                true,
                false,
                true,
                false,
                &direct_domains,
                true,
                true,
                &ip_rules
            ),
            RouteVerdict::Direct
        );

        assert_eq!(
            evaluate_route(
                &ip_8_8_8_8,
                Some("vk.com"),
                true,
                false,
                false,
                false,
                &direct_domains,
                true,
                false,
                &ip_rules
            ),
            RouteVerdict::Direct
        );
        assert_eq!(
            evaluate_route(
                &ip_1_1_1_1,
                Some("google.com"),
                true,
                false,
                false,
                false,
                &direct_domains,
                true,
                false,
                &ip_rules
            ),
            RouteVerdict::Direct
        );
        assert_eq!(
            evaluate_route(
                &ip_8_8_8_8,
                Some("google.com"),
                true,
                false,
                false,
                false,
                &direct_domains,
                true,
                false,
                &ip_rules
            ),
            RouteVerdict::Tunnel
        );

        assert_eq!(
            evaluate_route(
                &ip_8_8_8_8,
                Some("vk.com"),
                true,
                true,
                false,
                false,
                &direct_domains,
                true,
                true,
                &ip_rules
            ),
            RouteVerdict::Tunnel
        );
        assert_eq!(
            evaluate_route(
                &ip_1_1_1_1,
                Some("unlisted.com"),
                true,
                true,
                false,
                false,
                &direct_domains,
                true,
                true,
                &ip_rules
            ),
            RouteVerdict::Tunnel
        );
        assert_eq!(
            evaluate_route(
                &ip_8_8_8_8,
                Some("unlisted.com"),
                true,
                true,
                false,
                false,
                &direct_domains,
                true,
                true,
                &ip_rules
            ),
            RouteVerdict::Direct
        );

        assert_eq!(
            evaluate_route(
                &ip_1_1_1_1,
                Some("vk.com"),
                true,
                true,
                false,
                false,
                &direct_domains,
                true,
                false,
                &ip_rules
            ),
            RouteVerdict::Direct
        );
        assert_eq!(
            evaluate_route(
                &ip_8_8_8_8,
                Some("vk.com"),
                true,
                true,
                false,
                false,
                &direct_domains,
                true,
                false,
                &ip_rules
            ),
            RouteVerdict::Tunnel
        );
        assert_eq!(
            evaluate_route(
                &ip_8_8_8_8,
                Some("unlisted.com"),
                true,
                true,
                false,
                false,
                &direct_domains,
                true,
                false,
                &ip_rules
            ),
            RouteVerdict::Direct
        );
    }

    #[test]
    fn test_evaluate_route_user_reported_scenario() {
        let ip_rules = parse_ip_rules("193.106.141.229");
        let direct_domains = vec!["notion.so".to_string()];

        let ping_eu_ip = IpAddr::V4(Ipv4Addr::new(193, 106, 141, 229));
        let yandex_ip = IpAddr::V4(Ipv4Addr::new(77, 88, 55, 66));
        let notion_ip = IpAddr::V4(Ipv4Addr::new(104, 18, 20, 50));

        assert_eq!(
            evaluate_route(
                &yandex_ip,
                Some("yandex.ru"),
                true,
                true,
                false,
                false,
                &direct_domains,
                true,
                true,
                &ip_rules
            ),
            RouteVerdict::Direct,
            "yandex.ru must go Direct when not in whitelists"
        );
        assert_eq!(
            evaluate_route(
                &yandex_ip,
                None,
                true,
                true,
                false,
                false,
                &direct_domains,
                true,
                true,
                &ip_rules
            ),
            RouteVerdict::Direct,
            "yandex IP must go Direct when not in IP whitelist"
        );

        assert_eq!(
            evaluate_route(
                &notion_ip,
                Some("notion.so"),
                true,
                true,
                false,
                false,
                &direct_domains,
                true,
                true,
                &ip_rules
            ),
            RouteVerdict::Tunnel,
            "notion.so must go Tunnel because it is in domain whitelist"
        );

        assert_eq!(
            evaluate_route(
                &ping_eu_ip,
                Some("ping.eu"),
                true,
                true,
                false,
                false,
                &direct_domains,
                true,
                true,
                &ip_rules
            ),
            RouteVerdict::Tunnel,
            "ping.eu must go Tunnel because its IP is in IP whitelist"
        );
        assert_eq!(
            evaluate_route(
                &ping_eu_ip,
                None,
                true,
                true,
                false,
                false,
                &direct_domains,
                true,
                true,
                &ip_rules
            ),
            RouteVerdict::Tunnel,
            "ping.eu IP with None domain must go Tunnel because it matches IP whitelist"
        );
    }
}
