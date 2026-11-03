use anyhow::{Context, Result, bail};
use primp::dns::{Addrs, Name, Resolve, Resolving};
use std::{
    collections::HashMap,
    net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr},
    sync::{
        Arc, OnceLock, RwLock,
        atomic::{AtomicBool, AtomicUsize, Ordering},
    },
    time::{Duration, Instant},
};
use tokio::{net::UdpSocket, time::timeout};

const YANDEX_DNS_SERVERS: [SocketAddr; 2] = [
    SocketAddr::new(IpAddr::V4(Ipv4Addr::new(77, 88, 8, 8)), 53),
    SocketAddr::new(IpAddr::V4(Ipv4Addr::new(77, 88, 8, 1)), 53),
];
const NSDI_DNS_SERVERS: [SocketAddr; 2] = [
    SocketAddr::new(IpAddr::V4(Ipv4Addr::new(195, 208, 4, 1)), 53),
    SocketAddr::new(IpAddr::V4(Ipv4Addr::new(195, 208, 5, 1)), 53),
];
const ROSTELECOM_DNS_SERVERS: [SocketAddr; 2] = [
    SocketAddr::new(IpAddr::V4(Ipv4Addr::new(95, 189, 32, 74)), 53),
    SocketAddr::new(IpAddr::V4(Ipv4Addr::new(212, 48, 193, 36)), 53),
];

struct DnsTier {
    name: &'static str,
    servers: [SocketAddr; 2],
}

const DNS_TIERS: [DnsTier; 3] = [
    DnsTier {
        name: "Yandex",
        servers: YANDEX_DNS_SERVERS,
    },
    DnsTier {
        name: "НСДИ",
        servers: NSDI_DNS_SERVERS,
    },
    DnsTier {
        name: "Ростелеком",
        servers: ROSTELECOM_DNS_SERVERS,
    },
];

const DNS_TIMEOUT: Duration = Duration::from_millis(900);
const DNS_CACHE_MAX_TTL: Duration = Duration::from_secs(900);
const DNS_CACHE_MAX_ENTRIES: usize = 1024;
const DNS_A_RECORD: u16 = 1;
const DNS_AAAA_RECORD: u16 = 28;
const NO_PREFERENCE: usize = usize::MAX;
const SYSTEM_PREFERENCE: usize = usize::MAX - 1;
static TUNNEL_ACTIVE: AtomicBool = AtomicBool::new(false);
static PREFERRED_TIER: AtomicUsize = AtomicUsize::new(NO_PREFERENCE);

enum DnsQueryError {
    TimedOut,
    Failed(anyhow::Error),
}

struct DnsAnswer {
    addresses: Vec<IpAddr>,
    ttl: Duration,
}

struct DnsCacheEntry {
    addresses: Vec<IpAddr>,
    expires_at: Instant,
}

pub struct DnsResolver {
    cache: RwLock<HashMap<String, DnsCacheEntry>>,
}

impl Default for DnsResolver {
    fn default() -> Self {
        Self::new()
    }
}

impl DnsResolver {
    pub fn new() -> Self {
        Self {
            cache: RwLock::new(HashMap::new()),
        }
    }

    pub async fn lookup(&self, host: &str) -> Result<Vec<IpAddr>> {
        if let Ok(address) = host.parse::<IpAddr>() {
            return Ok(vec![address]);
        }

        let normalized = host.trim().to_ascii_lowercase();
        let now = Instant::now();
        if let Some(addresses) = self.cache.read().ok().and_then(|guard| {
            guard
                .get(&normalized)
                .filter(|entry| now < entry.expires_at)
                .map(|entry| entry.addresses.clone())
        }) {
            return Ok(addresses);
        }

        let result = multi_tier_lookup(&normalized).await;

        match result {
            Ok(answer) => {
                let ttl = answer.ttl.min(DNS_CACHE_MAX_TTL);
                if let Ok(mut guard) = self.cache.write() {
                    if !guard.contains_key(&normalized) && guard.len() >= DNS_CACHE_MAX_ENTRIES {
                        let now = Instant::now();
                        guard.retain(|_, entry| now < entry.expires_at);
                        if guard.len() >= DNS_CACHE_MAX_ENTRIES
                            && let Some(oldest) = guard
                                .iter()
                                .min_by_key(|(_, entry)| entry.expires_at)
                                .map(|(key, _)| key.clone())
                        {
                            guard.remove(&oldest);
                        }
                    }
                    guard.insert(
                        normalized,
                        DnsCacheEntry {
                            addresses: answer.addresses.clone(),
                            expires_at: Instant::now() + ttl,
                        },
                    );
                }
                Ok(answer.addresses)
            }
            Err(error) => {
                if let Some(addresses) = self.cache.read().ok().and_then(|guard| {
                    guard
                        .get(&normalized)
                        .filter(|entry| !entry.addresses.is_empty())
                        .map(|entry| entry.addresses.clone())
                }) {
                    crate::log_error!("[DNS] Использован кэш для {host} (DNS недоступен)");
                    return Ok(addresses);
                }
                Err(error)
            }
        }
    }
}

async fn multi_tier_lookup(host: &str) -> Result<DnsAnswer> {
    let pref = PREFERRED_TIER.load(Ordering::Relaxed);
    if pref == SYSTEM_PREFERENCE {
        if let Ok(answer) = system_lookup(host).await
            && !answer.addresses.is_empty()
        {
            return Ok(answer);
        }
    } else if pref < DNS_TIERS.len()
        && let Ok(answer) = lookup_with_tier(host, &DNS_TIERS[pref]).await
        && !answer.addresses.is_empty()
    {
        return Ok(answer);
    }

    let mut last_error = None;
    for (idx, tier) in DNS_TIERS.iter().enumerate() {
        match lookup_with_tier(host, tier).await {
            Ok(answer) if !answer.addresses.is_empty() => {
                PREFERRED_TIER.store(idx, Ordering::Relaxed);
                return Ok(answer);
            }
            Ok(_) => {
                last_error = Some(anyhow::anyhow!("пустой DNS-ответ от {}", tier.name));
            }
            Err(DnsQueryError::TimedOut) => {
                last_error = Some(anyhow::anyhow!(
                    "DNS-серверы {} не ответили по таймауту",
                    tier.name
                ));
            }
            Err(DnsQueryError::Failed(err)) => {
                last_error = Some(err);
            }
        }
    }

    if let Ok(answer) = system_lookup(host).await
        && !answer.addresses.is_empty()
    {
        PREFERRED_TIER.store(SYSTEM_PREFERENCE, Ordering::Relaxed);
        return Ok(answer);
    }

    if !tunnel_active() {
        tokio::time::sleep(Duration::from_millis(200)).await;
        for (idx, tier) in DNS_TIERS.iter().enumerate() {
            if let Ok(answer) = lookup_with_tier(host, tier).await
                && !answer.addresses.is_empty()
            {
                PREFERRED_TIER.store(idx, Ordering::Relaxed);
                return Ok(answer);
            }
        }
        if let Ok(answer) = system_lookup(host).await
            && !answer.addresses.is_empty()
        {
            PREFERRED_TIER.store(SYSTEM_PREFERENCE, Ordering::Relaxed);
            return Ok(answer);
        }
    }

    crate::log_error!(
        "[DNS] Ни один из DNS-серверов и системный резолвер не дали ответа для {host}."
    );
    Err(last_error
        .unwrap_or_else(|| anyhow::anyhow!("DNS-серверы не вернули адресов"))
        .context(format!(
            "Ни один из DNS-серверов и системный резолвер не дали ответа для {host}."
        )))
}

async fn system_lookup(host: &str) -> Result<DnsAnswer> {
    let mut addresses = Vec::new();
    for address in tokio::net::lookup_host((host, 0))
        .await
        .with_context(|| format!("системный DNS lookup {host}"))?
    {
        let address = address.ip();
        if !addresses.contains(&address) {
            addresses.push(address);
        }
    }
    if addresses.is_empty() {
        bail!("пустой системный DNS-ответ для {host}");
    }
    Ok(DnsAnswer {
        addresses,
        ttl: DNS_CACHE_MAX_TTL,
    })
}

async fn lookup_with_tier(
    host: &str,
    tier: &DnsTier,
) -> std::result::Result<DnsAnswer, DnsQueryError> {
    let query_id = rand::random::<u16>();
    let a_query = build_query(host, query_id, DNS_A_RECORD).map_err(DnsQueryError::Failed)?;
    let a_answer = query_tier(tier, &a_query, query_id).await?;
    if !a_answer.addresses.is_empty() {
        return Ok(a_answer);
    }

    let aaaa_id = query_id.wrapping_add(1);
    let aaaa_query = build_query(host, aaaa_id, DNS_AAAA_RECORD).map_err(DnsQueryError::Failed)?;
    query_tier(tier, &aaaa_query, aaaa_id).await
}

async fn query_tier(
    tier: &DnsTier,
    query: &[u8],
    query_id: u16,
) -> std::result::Result<DnsAnswer, DnsQueryError> {
    let response = timeout(DNS_TIMEOUT, async {
        let primary = query_dns_server(tier.servers[0], query.to_vec(), query_id);
        let secondary = query_dns_server(tier.servers[1], query.to_vec(), query_id);
        tokio::pin!(primary);
        tokio::pin!(secondary);

        let (first_primary, first_result) = tokio::select! {
            result = &mut primary => (true, result),
            result = &mut secondary => (false, result),
        };
        match first_result {
            Ok(answer) if !answer.addresses.is_empty() => Ok(answer),
            Ok(_) => {
                let second = if first_primary {
                    secondary.await
                } else {
                    primary.await
                };
                second.and_then(|answer| {
                    if answer.addresses.is_empty() {
                        bail!("пустой DNS-ответ от {}", tier.name);
                    }
                    Ok(answer)
                })
            }
            Err(first_error) => {
                let second_result = if first_primary {
                    secondary.await
                } else {
                    primary.await
                };
                second_result.map_err(|second_error| {
                    anyhow::anyhow!(
                        "оба сервера {} DNS отклонили запрос: {first_error}; {second_error}",
                        tier.name
                    )
                })
            }
        }
    })
    .await;

    match response {
        Ok(Ok(answer)) => Ok(answer),
        Ok(Err(error)) => Err(DnsQueryError::Failed(error)),
        Err(_) => Err(DnsQueryError::TimedOut),
    }
}

async fn query_dns_server(server: SocketAddr, query: Vec<u8>, query_id: u16) -> Result<DnsAnswer> {
    let socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0))
        .await
        .context("создание UDP-сокета DNS")?;
    socket
        .send_to(&query, server)
        .await
        .with_context(|| format!("отправка DNS-запроса к {server}"))?;

    let mut response = [0_u8; 1500];
    loop {
        let (length, source) = socket
            .recv_from(&mut response)
            .await
            .with_context(|| format!("получение DNS-ответа от {server}"))?;
        if source != server || length < 2 || response[0..2] != query_id.to_be_bytes() {
            continue;
        }
        return parse_response(&response[..length]);
    }
}

fn build_query(host: &str, query_id: u16, record_type: u16) -> Result<Vec<u8>> {
    let host = host.trim_end_matches('.');
    if host.is_empty() || host.len() > 253 {
        bail!("некорректное DNS-имя {host:?}");
    }

    let mut query = Vec::with_capacity(host.len() + 18);
    query.extend_from_slice(&query_id.to_be_bytes());
    query.extend_from_slice(&0x0100_u16.to_be_bytes());
    query.extend_from_slice(&1_u16.to_be_bytes());
    query.extend_from_slice(&0_u16.to_be_bytes());
    query.extend_from_slice(&0_u16.to_be_bytes());
    query.extend_from_slice(&0_u16.to_be_bytes());
    for label in host.split('.') {
        if label.is_empty() || label.len() > 63 || !label.is_ascii() {
            bail!("некорректная DNS-метка {label:?}");
        }
        query.push(label.len() as u8);
        query.extend_from_slice(label.as_bytes());
    }
    query.push(0);
    query.extend_from_slice(&record_type.to_be_bytes());
    query.extend_from_slice(&1_u16.to_be_bytes());
    Ok(query)
}

fn parse_response(response: &[u8]) -> Result<DnsAnswer> {
    if response.len() < 12 {
        bail!("слишком короткий DNS-ответ");
    }
    let flags = read_u16(response, 2)?;
    if flags & 0x8000 == 0 {
        bail!("DNS-ответ без флага response");
    }
    if flags & 0x0200 != 0 {
        bail!("DNS-ответ требует TCP-повторения");
    }
    let status = flags & 0x000f;
    if status != 0 {
        bail!("DNS-ответ с кодом {status}");
    }

    let questions = read_u16(response, 4)? as usize;
    let answers = read_u16(response, 6)? as usize;
    let mut offset = 12;
    for _ in 0..questions {
        offset = skip_name(response, offset)?;
        offset = offset
            .checked_add(4)
            .filter(|value| *value <= response.len())
            .context("повреждён DNS-вопрос")?;
    }

    let mut addresses = Vec::new();
    let mut ttl: Option<u32> = None;
    for _ in 0..answers {
        offset = skip_name(response, offset)?;
        let record_type = read_u16(response, offset)?;
        let record_class = read_u16(response, offset + 2)?;
        let record_ttl = read_u32(response, offset + 4)?;
        let data_length = read_u16(response, offset + 8)? as usize;
        let data_offset = offset
            .checked_add(10)
            .filter(|value| *value <= response.len())
            .context("повреждён DNS-ответ")?;
        let next_offset = data_offset
            .checked_add(data_length)
            .filter(|value| *value <= response.len())
            .context("повреждён DNS resource record")?;
        if record_class == 1 && record_type == DNS_A_RECORD && data_length == 4 {
            let address = IpAddr::V4(Ipv4Addr::new(
                response[data_offset],
                response[data_offset + 1],
                response[data_offset + 2],
                response[data_offset + 3],
            ));
            if !addresses.contains(&address) {
                addresses.push(address);
            }
            ttl = Some(ttl.map_or(record_ttl, |current| current.min(record_ttl)));
        }
        if record_class == 1 && record_type == DNS_AAAA_RECORD && data_length == 16 {
            let mut octets = [0_u8; 16];
            octets.copy_from_slice(&response[data_offset..next_offset]);
            let address = IpAddr::V6(Ipv6Addr::from(octets));
            if !addresses.contains(&address) {
                addresses.push(address);
            }
            ttl = Some(ttl.map_or(record_ttl, |current| current.min(record_ttl)));
        }
        offset = next_offset;
    }
    Ok(DnsAnswer {
        addresses,
        ttl: Duration::from_secs(u64::from(ttl.unwrap_or(0))),
    })
}

fn skip_name(message: &[u8], mut offset: usize) -> Result<usize> {
    loop {
        let length = *message.get(offset).context("повреждён DNS name")?;
        if length & 0xc0 == 0xc0 {
            return offset
                .checked_add(2)
                .filter(|value| *value <= message.len())
                .context("повреждён DNS compression pointer");
        }
        if length & 0xc0 != 0 {
            bail!("некорректный DNS name");
        }
        offset = offset.checked_add(1).context("переполнение DNS name")?;
        if length == 0 {
            return Ok(offset);
        }
        offset = offset
            .checked_add(length as usize)
            .filter(|value| *value <= message.len())
            .context("обрезанный DNS name")?;
    }
}

fn read_u16(message: &[u8], offset: usize) -> Result<u16> {
    let bytes = message
        .get(offset..offset + 2)
        .context("обрезанный DNS-заголовок")?;
    Ok(u16::from_be_bytes([bytes[0], bytes[1]]))
}

fn read_u32(message: &[u8], offset: usize) -> Result<u32> {
    let bytes = message
        .get(offset..offset + 4)
        .context("обрезанный DNS resource record")?;
    Ok(u32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
}

pub fn mark_tunnel_active() {
    TUNNEL_ACTIVE.store(true, Ordering::Release);
}

fn tunnel_active() -> bool {
    TUNNEL_ACTIVE.load(Ordering::Acquire)
}

impl Resolve for DnsResolver {
    fn resolve(&self, name: Name) -> Resolving {
        let host = name.as_str().to_owned();
        let resolver = global();
        Box::pin(async move {
            let addresses = resolver
                .lookup(&host)
                .await
                .map_err(|error| -> Box<dyn std::error::Error + Send + Sync> { error.into() })?;
            Ok(Box::new(
                addresses
                    .into_iter()
                    .map(|address| SocketAddr::new(address, 0)),
            ) as Addrs)
        })
    }
}

pub fn global() -> Arc<DnsResolver> {
    static RESOLVER: OnceLock<Arc<DnsResolver>> = OnceLock::new();
    RESOLVER
        .get_or_init(|| Arc::new(DnsResolver::new()))
        .clone()
}

pub async fn resolve_socket(address: &str) -> Result<SocketAddr> {
    if let Ok(address) = address.parse::<SocketAddr>() {
        return Ok(address);
    }
    let parsed = url::Url::parse(&format!("udp://{address}"))
        .with_context(|| format!("разбор адреса {address:?}"))?;
    let host = parsed.host_str().context("адрес не содержит имя хоста")?;
    let port = parsed.port().context("адрес не содержит порт")?;
    let ip = global()
        .lookup(host)
        .await?
        .into_iter()
        .next()
        .context("пустой DNS-ответ")?;
    Ok(SocketAddr::new(ip, port))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn answer_packet(query_id: u16) -> Vec<u8> {
        let mut packet = Vec::new();
        packet.extend_from_slice(&query_id.to_be_bytes());
        packet.extend_from_slice(&[0x81, 0x80, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00]);
        packet.extend_from_slice(&[
            0x02, b'v', b'k', 0x02, b'r', b'u', 0x00, 0x00, 0x01, 0x00, 0x01,
        ]);
        packet.extend_from_slice(&[
            0xc0, 0x0c, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x3c, 0x00, 0x04, 77, 88, 8, 8,
        ]);
        packet
    }

    #[test]
    fn parses_compressed_ipv4_answer_with_its_ttl() {
        let answer = parse_response(&answer_packet(0x1234)).unwrap();
        assert_eq!(
            answer.addresses,
            vec![IpAddr::V4(Ipv4Addr::new(77, 88, 8, 8))]
        );
        assert_eq!(answer.ttl, Duration::from_secs(60));
    }

    #[test]
    fn query_rejects_invalid_dns_name() {
        assert!(build_query("", 1, DNS_A_RECORD).is_err());
        assert!(build_query("a..vk.ru", 1, DNS_A_RECORD).is_err());
    }

    #[tokio::test]
    async fn query_ignores_responses_with_a_foreign_transaction_id() {
        let server = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        let server_address = server.local_addr().unwrap();
        let query = build_query("vk.ru", 0x4321, DNS_A_RECORD).unwrap();
        let client = tokio::spawn(query_dns_server(server_address, query, 0x4321));
        let mut request = [0_u8; 512];
        let (_, client_address) = server.recv_from(&mut request).await.unwrap();
        server
            .send_to(&answer_packet(0x9999), client_address)
            .await
            .unwrap();
        server
            .send_to(&answer_packet(0x4321), client_address)
            .await
            .unwrap();
        let answer = client.await.unwrap().unwrap();
        assert_eq!(
            answer.addresses,
            vec![IpAddr::V4(Ipv4Addr::new(77, 88, 8, 8))]
        );
    }

    #[tokio::test]
    async fn literal_socket_does_not_require_dns() {
        assert_eq!(
            resolve_socket("127.0.0.1:46000").await.unwrap(),
            "127.0.0.1:46000".parse().unwrap()
        );
        assert_eq!(
            resolve_socket("[::1]:46000").await.unwrap(),
            "[::1]:46000".parse().unwrap()
        );
    }

    #[tokio::test]
    async fn dns_cache_hit_avoids_network_queries() {
        let resolver = DnsResolver::new();
        let ip = IpAddr::V4(Ipv4Addr::new(93, 184, 216, 34));
        resolver.cache.write().unwrap().insert(
            "cached.example.ru".to_string(),
            DnsCacheEntry {
                addresses: vec![ip],
                expires_at: Instant::now() + Duration::from_secs(300),
            },
        );
        let result = resolver.lookup("cached.example.ru").await.unwrap();
        assert_eq!(result, vec![ip]);
    }

    #[tokio::test]
    async fn dns_stale_cache_serves_requests_when_dns_fails() {
        let resolver = DnsResolver::new();
        let ip = IpAddr::V4(Ipv4Addr::new(1, 2, 3, 4));
        resolver.cache.write().unwrap().insert(
            "unreachable.example.invalid".to_string(),
            DnsCacheEntry {
                addresses: vec![ip],
                expires_at: Instant::now() - Duration::from_secs(10),
            },
        );
        let result = resolver
            .lookup("unreachable.example.invalid")
            .await
            .unwrap();
        assert_eq!(result, vec![ip]);
    }

    #[test]
    fn dns_tier_servers_count_is_six() {
        assert_eq!(DNS_TIERS.len(), 3);
        let total_servers: usize = DNS_TIERS.iter().map(|tier| tier.servers.len()).sum();
        assert_eq!(total_servers, 6);
    }
}
