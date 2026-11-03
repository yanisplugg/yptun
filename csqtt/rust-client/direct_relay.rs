use crate::packet::{PacketBuf, PacketPool};
use arc_swap::ArcSwap;
use socket2::SockRef;
use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::{Arc, OnceLock, RwLock};
use std::time::Duration;
use tokio::sync::{Semaphore, mpsc};
use tokio_util::sync::CancellationToken;

const MAX_CONCURRENT_DNS_QUERIES: usize = 256;
const MAX_UDP_SESSIONS: usize = 4096;

/// Bounded capacity of the direct-relay → TUN inbound hand-off. Keeps a slow
/// TUN writer from letting smoltcp/DNS egress grow without limit and starve the
/// shared packet pool.
pub const DIRECT_RELAY_INBOUND_CAPACITY: usize = 128;

fn dns_query_slots() -> &'static Arc<Semaphore> {
    static SLOTS: OnceLock<Arc<Semaphore>> = OnceLock::new();
    SLOTS.get_or_init(|| Arc::new(Semaphore::new(MAX_CONCURRENT_DNS_QUERIES)))
}

pub static PRERESOLVE_DOMAINS: &[&str] = &[
    "2gis.com",
    "2gis.ru",
    "47news.ru",
    "alfabank.ru",
    "avito.ru",
    "avito.st",
    "cikrf.ru",
    "digital.gov.ru",
    "dobro.ru",
    "duma.gov.ru",
    "dzen.ru",
    "evotor.ru",
    "gazeta.ru",
    "genproc.gov.ru",
    "gosuslugi.ru",
    "government.ru",
    "gu-st.ru",
    "hrlink.ru",
    "izbirkom.ru",
    "kinopoisk.ru",
    "kp.ru",
    "kremlin.ru",
    "lemanapro.ru",
    "lenta.ru",
    "lizaalert.org",
    "lmru.tech",
    "mail.ru",
    "max.ru",
    "mradx.net",
    "ofd.ru",
    "ok.ru",
    "okcdn.ru",
    "oneme.ru",
    "onf.ru",
    "ozon.ru",
    "ozone.ru",
    "pochta.ru",
    "rambler.ru",
    "rbc.ru",
    "roskachestvo.gov.ru",
    "rutube.ru",
    "rutubelist.ru",
    "rzd.ru",
    "sber.ru",
    "sberbank.ru",
    "sirius.online",
    "sochisirius.ru",
    "sportmail.ru",
    "t2.ru",
    "taximaxim.ru",
    "tbank.ru",
    "tinkoff.ru",
    "tutu.ru",
    "userapi.com",
    "vk-portal.net",
    "vk.com",
    "vk.ru",
    "vtb.ru",
    "wb.ru",
    "wildberries.ru",
    "speedtest.yandex.ru",
    "speedtest.yandex.net",
    "xn--80ajghhoc2aj1c8b.xn--p1ai",
    "ya.ru",
    "yandex.com",
    "yandex.net",
    "yandex.ru",
    "yastatic.net",
];

async fn resolve_direct_domain(domain: &str) -> Vec<IpAddr> {
    if let Ok(addrs) = tokio::net::lookup_host((domain, 443)).await {
        let list: Vec<IpAddr> = addrs.map(|sa| sa.ip()).collect();
        if !list.is_empty() {
            return list;
        }
    }
    crate::dns::global()
        .lookup(domain)
        .await
        .unwrap_or_default()
}

async fn run_full_preresolve_task(
    direct_ips: Arc<ArcSwap<std::collections::HashSet<IpAddr>>>,
    dns_domain_map: Arc<crate::sni::DnsDomainMap>,
    domain_routing: Arc<ArcSwap<crate::dispatcher::DomainRoutingConfig>>,
    generation: u64,
    current_generation: Arc<std::sync::atomic::AtomicU64>,
    include_template: bool,
    custom_domains: Vec<String>,
) {
    let mut domains_to_resolve = Vec::new();
    if include_template {
        for &d in PRERESOLVE_DOMAINS {
            domains_to_resolve.push(d.to_string());
        }
    }
    for d in custom_domains {
        let trimmed = d.trim().to_lowercase();
        if !trimmed.is_empty() && !domains_to_resolve.contains(&trimmed) {
            domains_to_resolve.push(trimmed);
        }
    }
    if domains_to_resolve.is_empty() {
        if current_generation.load(std::sync::atomic::Ordering::SeqCst) == generation
            && domain_routing.load().is_whitelist
        {
            direct_ips.store(Arc::new(std::collections::HashSet::new()));
        }
        return;
    }

    let sem = Arc::new(tokio::sync::Semaphore::new(12));
    let mut handles = Vec::with_capacity(domains_to_resolve.len());
    for domain in domains_to_resolve {
        let permit = match sem.clone().acquire_owned().await {
            Ok(p) => p,
            Err(_) => return,
        };
        let d = domain.clone();
        handles.push(tokio::spawn(async move {
            let _permit = permit;
            let ips = resolve_direct_domain(&d).await;
            (d, ips)
        }));
    }

    let mut collected = std::collections::HashSet::new();
    for handle in handles {
        if let Ok((domain_name, ips)) = handle.await {
            dns_domain_map.insert_multiple(&ips, &domain_name);
            for ip in ips {
                collected.insert(ip);
            }
        }
    }

    if current_generation.load(std::sync::atomic::Ordering::SeqCst) != generation {
        return;
    }
    let config = domain_routing.load();
    if !config.enabled {
        return;
    }

    let count = collected.len();
    if !config.is_whitelist {
        direct_ips.rcu(|current| {
            let mut updated = (**current).clone();
            for ip in &collected {
                updated.insert(*ip);
            }
            Arc::new(updated)
        });
        crate::log_error!(
            "[ДОМЕНЫ] Разрешено прямых IP (ЧС): {}",
            direct_ips.load().len()
        );
    } else {
        direct_ips.store(Arc::new(std::collections::HashSet::new()));
        crate::log_error!(
            "[ДОМЕНЫ] Предрезолвлен список БС для туннеля (доменов: {})",
            count
        );
    }
}

fn is_local_or_private_ip(ip: &IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => {
            let octets = v4.octets();
            v4.is_private()
                || v4.is_loopback()
                || v4.is_link_local()
                || (octets[0] == 100 && (octets[1] & 0xc0) == 64)
        }
        IpAddr::V6(v6) => {
            v6.is_loopback()
                || v6.is_unspecified()
                || v6.is_unique_local()
                || v6.is_unicast_link_local()
                || v6.is_multicast()
                || v6
                    .to_ipv4_mapped()
                    .is_some_and(|v4| is_local_or_private_ip(&IpAddr::V4(v4)))
        }
    }
}

pub struct DirectRelay {
    domain_routing: Arc<ArcSwap<crate::dispatcher::DomainRoutingConfig>>,
    direct_ips: Arc<ArcSwap<std::collections::HashSet<IpAddr>>>,
    dns_domain_map: Arc<crate::sni::DnsDomainMap>,
    pool: Arc<PacketPool>,
    inbound_tx: mpsc::Sender<PacketBuf>,
    direct_pkt_tx: tokio::sync::mpsc::Sender<Vec<u8>>,
    active_tcp: Arc<RwLock<HashMap<(SocketAddr, SocketAddr), std::time::Instant>>>,
    udp_sessions: Arc<RwLock<HashMap<SocketAddr, Arc<tokio::net::UdpSocket>>>>,
    preresolve_generation: Arc<std::sync::atomic::AtomicU64>,
    shutdown: CancellationToken,
}

impl Drop for DirectRelay {
    fn drop(&mut self) {
        self.shutdown.cancel();
    }
}

impl DirectRelay {
    pub fn new(
        domain_routing: Arc<ArcSwap<crate::dispatcher::DomainRoutingConfig>>,
        direct_ips: Arc<ArcSwap<std::collections::HashSet<IpAddr>>>,
        dns_domain_map: Arc<crate::sni::DnsDomainMap>,
        pool: Arc<PacketPool>,
        inbound_tx: mpsc::Sender<PacketBuf>,
    ) -> std::io::Result<Self> {
        let shutdown = CancellationToken::new();
        let preresolve_generation = Arc::new(std::sync::atomic::AtomicU64::new(0));
        let config = domain_routing.load();
        if config.enabled
            && let Ok(handle) = tokio::runtime::Handle::try_current()
        {
            let generation_id = preresolve_generation.load(std::sync::atomic::Ordering::SeqCst);
            let ips_clone = direct_ips.clone();
            let dns_map_clone = dns_domain_map.clone();
            let routing_clone = domain_routing.clone();
            let generation_clone = preresolve_generation.clone();
            let include_template = config.template_domains;
            let custom_domains = config.direct_domains.to_vec();
            handle.spawn(async move {
                run_full_preresolve_task(
                    ips_clone,
                    dns_map_clone,
                    routing_clone,
                    generation_id,
                    generation_clone,
                    include_template,
                    custom_domains,
                )
                .await;
            });
        }

        let (stack_res, runner, _udp_sock, tcp_listener) =
            netstack_smoltcp::StackBuilder::default()
                .enable_tcp(true)
                .enable_udp(false)
                .enable_icmp(false)
                .mtu(1500)
                .tcp_recv_buffer_size(65536)
                .tcp_send_buffer_size(65536)
                .stack_buffer_size(4096)
                .tcp_buffer_size(4096)
                .build()?;

        if let Some(runner) = runner {
            let shutdown = shutdown.clone();
            tokio::spawn(async move {
                tokio::select! {
                    result = runner => {
                        if let Err(e) = result {
                            crate::log_error!("[SMOLTCP] Runner error: {e}");
                        }
                    }
                    _ = shutdown.cancelled() => {}
                }
            });
        }

        let (mut stack_sink, mut stack_stream) = futures::StreamExt::split(stack_res);
        let (direct_pkt_tx, mut direct_pkt_rx) = tokio::sync::mpsc::channel::<Vec<u8>>(10000);

        let shutdown_ingress = shutdown.clone();
        tokio::spawn(async move {
            use futures::SinkExt;
            loop {
                tokio::select! {
                    _ = shutdown_ingress.cancelled() => break,
                    pkt = direct_pkt_rx.recv() => {
                        let Some(pkt) = pkt else {
                            break;
                        };
                        if let Err(e) = stack_sink.send(pkt).await {
                            crate::log_error!("[SMOLTCP] Ingress packet delivery error: {e}");
                            break;
                        }
                    }
                }
            }
        });

        let pool_egress = pool.clone();
        let inbound_tx_egress = inbound_tx.clone();
        let shutdown_egress = shutdown.clone();
        tokio::spawn(async move {
            use futures::StreamExt;
            loop {
                let next = tokio::select! {
                    _ = shutdown_egress.cancelled() => break,
                    next = stack_stream.next() => next,
                };
                let Some(res) = next else {
                    break;
                };
                let frame = match res {
                    Ok(v) => v,
                    Err(e) => {
                        crate::log_error!("[SMOLTCP] Egress stream error: {e}");
                        continue;
                    }
                };
                let Some(mut pkt) = pool_egress.try_acquire() else {
                    continue;
                };
                if let Ok(dest) = pkt.extend_tail(frame.len()) {
                    dest.copy_from_slice(&frame);
                    if inbound_tx_egress.send(pkt).await.is_err() {
                        break;
                    }
                }
            }
        });

        let active_tcp = Arc::new(RwLock::new(HashMap::new()));
        let active_tcp_c = active_tcp.clone();

        if let Some(mut listener) = tcp_listener {
            let shutdown_listener = shutdown.clone();
            tokio::spawn(async move {
                use futures::StreamExt;
                loop {
                    let accepted = tokio::select! {
                        _ = shutdown_listener.cancelled() => break,
                        accepted = listener.next() => accepted,
                    };
                    let Some((mut client_stream, local_addr, remote_addr)) = accepted else {
                        break;
                    };
                    let key = (local_addr, remote_addr);
                    if let Ok(mut guard) = active_tcp_c.write() {
                        guard.insert(key, std::time::Instant::now());
                    }
                    let active_tcp_conn = active_tcp_c.clone();

                    tokio::spawn(async move {
                        let connect_res = tokio::time::timeout(
                            Duration::from_secs(5),
                            tokio::net::TcpStream::connect(remote_addr),
                        )
                        .await;

                        if let Ok(Ok(mut server_stream)) = connect_res {
                            let _ = server_stream.set_nodelay(true);
                            let sock_ref = SockRef::from(&server_stream);
                            let keepalive = socket2::TcpKeepalive::new()
                                .with_time(Duration::from_secs(30))
                                .with_interval(Duration::from_secs(10));
                            let _ = sock_ref.set_tcp_keepalive(&keepalive);
                            let _ = sock_ref.set_recv_buffer_size(262144);
                            let _ = sock_ref.set_send_buffer_size(262144);

                            let _ = tokio::io::copy_bidirectional_with_sizes(
                                &mut client_stream,
                                &mut server_stream,
                                65536,
                                65536,
                            )
                            .await;

                            use tokio::io::AsyncWriteExt;
                            let _ = tokio::time::timeout(
                                Duration::from_millis(300),
                                client_stream.shutdown(),
                            )
                            .await;
                            let _ = tokio::time::timeout(
                                Duration::from_millis(300),
                                server_stream.shutdown(),
                            )
                            .await;
                            drop(server_stream);
                        }

                        drop(client_stream);
                        if let Ok(mut guard) = active_tcp_conn.write() {
                            guard.remove(&key);
                        }
                    });
                }
            });
        }

        let active_tcp_cleanup = active_tcp.clone();
        let shutdown_cleanup = shutdown.clone();
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(Duration::from_secs(30));
            loop {
                tokio::select! {
                    _ = shutdown_cleanup.cancelled() => break,
                    _ = interval.tick() => {}
                }
                let now = std::time::Instant::now();
                if let Ok(mut guard) = active_tcp_cleanup.write() {
                    guard.retain(|_, last_seen| {
                        now.duration_since(*last_seen) < Duration::from_secs(120)
                    });
                }
            }
        });

        Ok(Self {
            domain_routing,
            direct_ips,
            dns_domain_map,
            pool,
            inbound_tx,
            direct_pkt_tx,
            active_tcp,
            udp_sessions: Arc::new(RwLock::new(HashMap::new())),
            preresolve_generation,
            shutdown,
        })
    }

    pub fn sync_domain_routing(&self, config: &crate::dispatcher::DomainRoutingConfig) {
        let generation_id = self
            .preresolve_generation
            .fetch_add(1, std::sync::atomic::Ordering::SeqCst)
            + 1;
        if !config.enabled {
            if !config.ip_routing_enabled {
                self.direct_ips
                    .store(Arc::new(std::collections::HashSet::new()));
            }
            self.dns_domain_map.clear();
            if let Ok(mut guard) = self.active_tcp.write() {
                guard.clear();
            }
            if let Ok(mut guard) = self.udp_sessions.write() {
                guard.clear();
            }
            return;
        }
        if let Ok(handle) = tokio::runtime::Handle::try_current() {
            let ips_clone = self.direct_ips.clone();
            let dns_map_clone = self.dns_domain_map.clone();
            let routing_clone = self.domain_routing.clone();
            let generation_clone = self.preresolve_generation.clone();
            let include_template = config.template_domains;
            let custom_domains = config.direct_domains.to_vec();
            handle.spawn(async move {
                run_full_preresolve_task(
                    ips_clone,
                    dns_map_clone,
                    routing_clone,
                    generation_id,
                    generation_clone,
                    include_template,
                    custom_domains,
                )
                .await;
            });
        }
    }

    pub fn preresolve_domains(&self, domains: Vec<String>) {
        if let Ok(handle) = tokio::runtime::Handle::try_current() {
            let generation_id = self
                .preresolve_generation
                .fetch_add(1, std::sync::atomic::Ordering::SeqCst)
                + 1;
            let ips_clone = self.direct_ips.clone();
            let dns_map_clone = self.dns_domain_map.clone();
            let routing_clone = self.domain_routing.clone();
            let generation_clone = self.preresolve_generation.clone();
            let include_template = self.domain_routing.load().template_domains;
            handle.spawn(async move {
                run_full_preresolve_task(
                    ips_clone,
                    dns_map_clone,
                    routing_clone,
                    generation_id,
                    generation_clone,
                    include_template,
                    domains,
                )
                .await;
            });
        }
    }

    pub fn is_direct_ip(&self, ip: &IpAddr) -> bool {
        self.direct_ips.load().contains(ip)
    }

    fn forward_direct_packet(&self, slice: &[u8]) {
        let _ = self.direct_pkt_tx.try_send(slice.to_vec());
    }

    pub async fn try_handle(&self, packet: &PacketBuf) -> bool {
        let config = self.domain_routing.load();
        if !config.enabled && !config.ip_routing_enabled {
            return false;
        }
        let slice = packet.as_slice();

        if let Some((domain, is_resp, _)) = crate::sni::parse_ip_packet_dns(slice)
            && !is_resp
        {
            let is_local_dns = crate::sni::extract_dst_addr(slice)
                .map(|a| is_local_or_private_ip(&a.ip()))
                .unwrap_or(false)
                || crate::sni::extract_src_addr(slice)
                    .map(|a| is_local_or_private_ip(&a.ip()))
                    .unwrap_or(false);
            if !is_local_dns {
                if config.enabled {
                    let is_direct = crate::sni::is_direct_domain_mode(
                        &domain,
                        config.is_whitelist,
                        config.exclude_ru_zones,
                        config.template_domains,
                        &config.direct_domains,
                    );
                    if is_direct {
                        self.handle_dns_query(slice, &domain, true);
                        return true;
                    }
                } else if config.ip_routing_enabled {
                    let is_tunnel_dns = if let Some(dst_addr) = crate::sni::extract_dst_addr(slice)
                    {
                        let dst_ip = dst_addr.ip();
                        if config.ip_is_whitelist {
                            config.ip_rules.iter().any(|r| r.matches(&dst_ip))
                        } else {
                            !config.ip_rules.iter().any(|r| r.matches(&dst_ip))
                        }
                    } else {
                        false
                    };
                    if !is_tunnel_dns {
                        self.handle_dns_query(slice, &domain, true);
                        return true;
                    }
                }
            }
            return false;
        }

        let Some(src_addr) = crate::sni::extract_src_addr(slice) else {
            return false;
        };
        let Some(dst_addr) = crate::sni::extract_dst_addr(slice) else {
            return false;
        };
        let dst_ip = dst_addr.ip();
        if dst_ip.is_unspecified()
            || dst_ip.is_loopback()
            || dst_ip.is_multicast()
            || is_local_or_private_ip(&dst_ip)
        {
            return false;
        }
        if let std::net::IpAddr::V4(v4) = dst_ip {
            let octets = v4.octets();
            if octets[0] == 0
                || octets[0] == 127
                || octets[0] >= 224
                || (octets[0] == 10 && octets[1] == 66 && octets[2] == 67)
            {
                return false;
            }
        }

        if slice.len() < 20 {
            return false;
        }
        let version = slice[0] >> 4;
        let protocol = match version {
            4 => slice[9],
            6 => {
                if slice.len() < 40 {
                    return false;
                }
                slice[6]
            }
            _ => return false,
        };

        if version == 4 && slice.len() >= 8 {
            let fragment_offset = (u16::from_be_bytes([slice[6], slice[7]]) & 0x1fff) * 8;
            if fragment_offset != 0 {
                return false;
            }
        }

        let key = (src_addr, dst_addr);

        if protocol == 6 {
            if self.direct_pkt_tx.is_closed() {
                return false;
            }
            let tcp_slice = match version {
                4 => {
                    let ihl = (slice[0] & 0x0f) as usize * 4;
                    if slice.len() < ihl + 14 {
                        return false;
                    }
                    &slice[ihl..]
                }
                6 => {
                    if slice.len() < 54 {
                        return false;
                    }
                    &slice[40..]
                }
                _ => return false,
            };
            let tcp_flags = tcp_slice[13];
            let is_tcp_syn = (tcp_flags & 0x02 != 0) && (tcp_flags & 0x10 == 0);
            if !is_tcp_syn {
                let is_active = if let Ok(guard) = self.active_tcp.read() {
                    guard.contains_key(&key)
                } else {
                    false
                };
                if is_active {
                    if self.direct_pkt_tx.is_closed() {
                        return false;
                    }
                    if let Ok(mut guard) = self.active_tcp.write() {
                        guard.insert(key, std::time::Instant::now());
                    }
                    self.forward_direct_packet(slice);
                    return true;
                }
                return false;
            }
        }

        let domain = self.dns_domain_map.get(&dst_ip);
        let verdict = crate::sni::evaluate_route(
            &dst_ip,
            domain.as_deref(),
            config.enabled,
            config.is_whitelist,
            config.exclude_ru_zones,
            config.template_domains,
            &config.direct_domains,
            config.ip_routing_enabled,
            config.ip_is_whitelist,
            &config.ip_rules,
        );

        let is_candidate_direct = if self.is_direct_ip(&dst_ip) {
            if config.ip_routing_enabled && config.ip_is_whitelist && !config.ip_rules.is_empty() {
                !config.ip_rules.iter().any(|r| r.matches(&dst_ip))
            } else {
                true
            }
        } else {
            matches!(verdict, crate::sni::RouteVerdict::Direct)
        };

        if protocol == 17 {
            if !is_candidate_direct {
                return false;
            }
            return self
                .handle_direct_udp_packet(slice, src_addr, dst_addr)
                .await;
        }

        if protocol != 6 {
            return false;
        }

        if is_candidate_direct {
            if let Ok(mut guard) = self.active_tcp.write() {
                guard.insert(key, std::time::Instant::now());
            }
            self.forward_direct_packet(slice);
            return true;
        }

        false
    }

    async fn handle_direct_udp_packet(
        &self,
        slice: &[u8],
        src_addr: SocketAddr,
        dst_addr: SocketAddr,
    ) -> bool {
        let version = slice[0] >> 4;
        let ihl = match version {
            4 => (slice[0] & 0x0f) as usize * 4,
            6 => 40,
            _ => return false,
        };
        if slice.len() < ihl + 8 {
            return false;
        }
        let udp_slice = &slice[ihl..];
        let udp_len = u16::from_be_bytes([udp_slice[4], udp_slice[5]]) as usize;
        if udp_len < 8 || udp_slice.len() < udp_len {
            return false;
        }
        let payload = &udp_slice[8..udp_len];

        let existing_sock = {
            if let Ok(guard) = self.udp_sessions.read() {
                guard.get(&src_addr).cloned()
            } else {
                None
            }
        };

        let sock = if let Some(s) = existing_sock {
            s
        } else {
            let bind_addr = if dst_addr.is_ipv6() {
                "[::]:0"
            } else {
                "0.0.0.0:0"
            };
            let Ok(new_sock) = tokio::net::UdpSocket::bind(bind_addr).await else {
                return false;
            };
            let socket_ref = socket2::SockRef::from(&new_sock);
            let _ = socket_ref.set_recv_buffer_size(262144);
            let _ = socket_ref.set_send_buffer_size(262144);
            let new_sock = Arc::new(new_sock);

            let mut guard = match self.udp_sessions.write() {
                Ok(guard) => guard,
                Err(poisoned) => poisoned.into_inner(),
            };
            let registered = if let Some(existing) = guard.get(&src_addr).cloned() {
                Some(existing)
            } else if guard.len() >= MAX_UDP_SESSIONS {
                return false;
            } else {
                guard.insert(src_addr, new_sock.clone());
                None
            };

            match registered {
                Some(existing) => existing,
                None => {
                    let sock_clone = new_sock.clone();
                    let inbound_tx = self.inbound_tx.clone();
                    let pool = self.pool.clone();
                    let sessions = self.udp_sessions.clone();
                    let dns_map_clone = self.dns_domain_map.clone();
                    let client_addr = src_addr;
                    let shutdown = self.shutdown.clone();

                    tokio::spawn(async move {
                        let mut buf = vec![0u8; 65536];
                        loop {
                            let recv_res = tokio::select! {
                                _ = shutdown.cancelled() => break,
                                recv_res = tokio::time::timeout(
                                    Duration::from_secs(120),
                                    sock_clone.recv_from(&mut buf),
                                ) => recv_res,
                            };

                            match recv_res {
                                Ok(Ok((len, remote_addr))) => {
                                    if remote_addr.port() == 53
                                        && let Some((domain, _, ips)) =
                                            crate::sni::extract_dns_info(&buf[..len])
                                        && !ips.is_empty()
                                    {
                                        dns_map_clone.insert_multiple(&ips, &domain);
                                    }
                                    let udp_len = 8 + len;
                                    let ip_header_len = if remote_addr.is_ipv6() { 40 } else { 20 };
                                    let total_len = ip_header_len + udp_len;
                                    let Some(mut pkt) = pool.try_acquire() else {
                                        continue;
                                    };
                                    if let Ok(dest) = pkt.extend_tail(total_len)
                                        && craft_udp_packet_into(
                                            remote_addr,
                                            client_addr,
                                            &buf[..len],
                                            dest,
                                        )
                                    {
                                        let _ = inbound_tx.send(pkt).await;
                                    }
                                }
                                Ok(Err(ref e))
                                    if e.kind() == std::io::ErrorKind::Interrupted
                                        || e.kind() == std::io::ErrorKind::WouldBlock
                                        || e.kind() == std::io::ErrorKind::ConnectionRefused =>
                                {
                                    continue;
                                }
                                _ => break,
                            }
                        }
                        if let Ok(mut guard) = sessions.write() {
                            let is_current = guard
                                .get(&client_addr)
                                .is_some_and(|current| Arc::ptr_eq(current, &sock_clone));
                            if is_current {
                                guard.remove(&client_addr);
                            }
                        }
                    });

                    new_sock
                }
            }
        };

        sock.send_to(payload, dst_addr).await.is_ok()
    }

    fn handle_dns_query(&self, slice: &[u8], domain: &str, is_direct: bool) {
        let Some(src_addr) = crate::sni::extract_src_addr(slice) else {
            return;
        };
        let Some(dst_addr) = crate::sni::extract_dst_addr(slice) else {
            return;
        };
        let version = slice[0] >> 4;
        let ihl = match version {
            4 => (slice[0] & 0x0f) as usize * 4,
            6 => 40,
            _ => return,
        };
        if slice.len() < ihl + 8 {
            return;
        }
        let udp_len = u16::from_be_bytes([slice[ihl + 4], slice[ihl + 5]]) as usize;
        let total_end = (ihl + udp_len).min(slice.len());
        if total_end < ihl + 8 {
            return;
        }
        let query_payload = slice[ihl + 8..total_end].to_vec();

        let direct_ips = self.direct_ips.clone();
        let dns_domain_map = self.dns_domain_map.clone();
        let inbound_tx = self.inbound_tx.clone();
        let pool = self.pool.clone();
        let domain_name = domain.to_string();

        let Ok(permit) = dns_query_slots().clone().try_acquire_owned() else {
            return;
        };

        tokio::spawn(async move {
            let _permit = permit;
            let socket = match tokio::net::UdpSocket::bind("0.0.0.0:0").await {
                Ok(s) => s,
                Err(_) => return,
            };
            let upstreams = [
                SocketAddr::new(IpAddr::V4(Ipv4Addr::new(77, 88, 8, 8)), 53),
                SocketAddr::new(IpAddr::V4(Ipv4Addr::new(77, 88, 8, 1)), 53),
                SocketAddr::new(IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1)), 53),
                SocketAddr::new(IpAddr::V4(Ipv4Addr::new(8, 8, 8, 8)), 53),
            ];
            let mut resp_buf = [0u8; 4096];
            let mut resp_len = 0;

            for &upstream in &upstreams {
                if socket.send_to(&query_payload, upstream).await.is_ok()
                    && let Ok(Ok((n, _))) = tokio::time::timeout(
                        Duration::from_millis(600),
                        socket.recv_from(&mut resp_buf),
                    )
                    .await
                {
                    resp_len = n;
                    break;
                }
            }

            if resp_len == 0 {
                return;
            }

            let resp_slice = &resp_buf[..resp_len];
            if let Some((_, _, ips)) = crate::sni::extract_dns_info(resp_slice)
                && !ips.is_empty()
            {
                dns_domain_map.insert_multiple(&ips, &domain_name);
                if is_direct {
                    direct_ips.rcu(|current| {
                        let mut updated = (**current).clone();
                        updated.extend(ips.iter().copied());
                        Arc::new(updated)
                    });
                }
            }

            let udp_len = 8 + resp_len;
            let ip_header_len = if dst_addr.is_ipv6() { 40 } else { 20 };
            let total_len = ip_header_len + udp_len;
            let Some(mut pkt) = pool.try_acquire() else {
                return;
            };
            if let Ok(dest_slice) = pkt.extend_tail(total_len)
                && craft_udp_packet_into(dst_addr, src_addr, resp_slice, dest_slice)
            {
                let _ = inbound_tx.send(pkt).await;
            }
        });
    }
}

pub fn craft_tcp_packet_into(
    src: SocketAddr,
    dst: SocketAddr,
    seq: u32,
    ack: u32,
    flags: u8,
    payload: &[u8],
    buf: &mut [u8],
) -> bool {
    let has_syn = (flags & 0x02) != 0;
    let tcp_header_len = if has_syn { 24 } else { 20 };
    match (src, dst) {
        (SocketAddr::V4(src_v4), SocketAddr::V4(dst_v4)) => {
            let total_len = 20 + tcp_header_len + payload.len();
            if buf.len() < total_len {
                return false;
            }
            buf[0] = 0x45;
            buf[1] = 0x00;
            buf[2..4].copy_from_slice(&(total_len as u16).to_be_bytes());
            buf[4..6].copy_from_slice(&0x1234u16.to_be_bytes());
            buf[6..8].copy_from_slice(&0x4000u16.to_be_bytes());
            buf[8] = 64;
            buf[9] = 6;
            buf[10..12].copy_from_slice(&[0, 0]);
            buf[12..16].copy_from_slice(&src_v4.ip().octets());
            buf[16..20].copy_from_slice(&dst_v4.ip().octets());
            let ip_csum = ip_checksum(&buf[..20]);
            buf[10..12].copy_from_slice(&ip_csum.to_be_bytes());

            let tcp_start = 20;
            buf[tcp_start..tcp_start + 2].copy_from_slice(&src_v4.port().to_be_bytes());
            buf[tcp_start + 2..tcp_start + 4].copy_from_slice(&dst_v4.port().to_be_bytes());
            buf[tcp_start + 4..tcp_start + 8].copy_from_slice(&seq.to_be_bytes());
            buf[tcp_start + 8..tcp_start + 12].copy_from_slice(&ack.to_be_bytes());
            buf[tcp_start + 12] = if has_syn { 0x60 } else { 0x50 };
            buf[tcp_start + 13] = flags;
            buf[tcp_start + 14..tcp_start + 16].copy_from_slice(&65535u16.to_be_bytes());
            buf[tcp_start + 16..tcp_start + 18].copy_from_slice(&[0, 0]);
            buf[tcp_start + 18..tcp_start + 20].copy_from_slice(&[0, 0]);
            if has_syn {
                buf[tcp_start + 20..tcp_start + 24].copy_from_slice(&[0x02, 0x04, 0x04, 0x00]);
            }
            if !payload.is_empty() {
                buf[tcp_start + tcp_header_len..total_len].copy_from_slice(payload);
            }
            let tcp_csum = tcp_checksum_v4(*src_v4.ip(), *dst_v4.ip(), &buf[tcp_start..total_len]);
            buf[tcp_start + 16..tcp_start + 18].copy_from_slice(&tcp_csum.to_be_bytes());
            true
        }
        (SocketAddr::V6(src_v6), SocketAddr::V6(dst_v6)) => {
            let payload_len = tcp_header_len + payload.len();
            let total_len = 40 + payload_len;
            if buf.len() < total_len {
                return false;
            }
            buf[0] = 0x60;
            buf[1..4].copy_from_slice(&[0, 0, 0]);
            buf[4..6].copy_from_slice(&(payload_len as u16).to_be_bytes());
            buf[6] = 6;
            buf[7] = 64;
            buf[8..24].copy_from_slice(&src_v6.ip().octets());
            buf[24..40].copy_from_slice(&dst_v6.ip().octets());

            let tcp_start = 40;
            buf[tcp_start..tcp_start + 2].copy_from_slice(&src_v6.port().to_be_bytes());
            buf[tcp_start + 2..tcp_start + 4].copy_from_slice(&dst_v6.port().to_be_bytes());
            buf[tcp_start + 4..tcp_start + 8].copy_from_slice(&seq.to_be_bytes());
            buf[tcp_start + 8..tcp_start + 12].copy_from_slice(&ack.to_be_bytes());
            buf[tcp_start + 12] = if has_syn { 0x60 } else { 0x50 };
            buf[tcp_start + 13] = flags;
            buf[tcp_start + 14..tcp_start + 16].copy_from_slice(&65535u16.to_be_bytes());
            buf[tcp_start + 16..tcp_start + 18].copy_from_slice(&[0, 0]);
            buf[tcp_start + 18..tcp_start + 20].copy_from_slice(&[0, 0]);
            if has_syn {
                buf[tcp_start + 20..tcp_start + 24].copy_from_slice(&[0x02, 0x04, 0x04, 0x00]);
            }
            if !payload.is_empty() {
                buf[tcp_start + tcp_header_len..total_len].copy_from_slice(payload);
            }
            let tcp_csum = tcp_checksum_v6(*src_v6.ip(), *dst_v6.ip(), &buf[tcp_start..total_len]);
            buf[tcp_start + 16..tcp_start + 18].copy_from_slice(&tcp_csum.to_be_bytes());
            true
        }
        _ => false,
    }
}

pub fn craft_udp_packet_into(
    src: SocketAddr,
    dst: SocketAddr,
    payload: &[u8],
    buf: &mut [u8],
) -> bool {
    match (src, dst) {
        (SocketAddr::V4(src_v4), SocketAddr::V4(dst_v4)) => {
            let udp_len = 8 + payload.len();
            let total_len = 20 + udp_len;
            if buf.len() < total_len {
                return false;
            }
            buf[0] = 0x45;
            buf[1] = 0x00;
            buf[2..4].copy_from_slice(&(total_len as u16).to_be_bytes());
            buf[4..6].copy_from_slice(&0x2345u16.to_be_bytes());
            buf[6..8].copy_from_slice(&0x4000u16.to_be_bytes());
            buf[8] = 64;
            buf[9] = 17;
            buf[10..12].copy_from_slice(&[0, 0]);
            buf[12..16].copy_from_slice(&src_v4.ip().octets());
            buf[16..20].copy_from_slice(&dst_v4.ip().octets());
            let ip_csum = ip_checksum(&buf[..20]);
            buf[10..12].copy_from_slice(&ip_csum.to_be_bytes());

            let udp_start = 20;
            buf[udp_start..udp_start + 2].copy_from_slice(&src_v4.port().to_be_bytes());
            buf[udp_start + 2..udp_start + 4].copy_from_slice(&dst_v4.port().to_be_bytes());
            buf[udp_start + 4..udp_start + 6].copy_from_slice(&(udp_len as u16).to_be_bytes());
            buf[udp_start + 6..udp_start + 8].copy_from_slice(&[0, 0]);
            buf[udp_start + 8..total_len].copy_from_slice(payload);
            let udp_csum = udp_checksum_v4(*src_v4.ip(), *dst_v4.ip(), &buf[udp_start..total_len]);
            buf[udp_start + 6..udp_start + 8].copy_from_slice(&udp_csum.to_be_bytes());
            true
        }
        (SocketAddr::V6(src_v6), SocketAddr::V6(dst_v6)) => {
            let udp_len = 8 + payload.len();
            let total_len = 40 + udp_len;
            if buf.len() < total_len {
                return false;
            }
            buf[0] = 0x60;
            buf[1..4].copy_from_slice(&[0, 0, 0]);
            buf[4..6].copy_from_slice(&(udp_len as u16).to_be_bytes());
            buf[6] = 17;
            buf[7] = 64;
            buf[8..24].copy_from_slice(&src_v6.ip().octets());
            buf[24..40].copy_from_slice(&dst_v6.ip().octets());

            let udp_start = 40;
            buf[udp_start..udp_start + 2].copy_from_slice(&src_v6.port().to_be_bytes());
            buf[udp_start + 2..udp_start + 4].copy_from_slice(&dst_v6.port().to_be_bytes());
            buf[udp_start + 4..udp_start + 6].copy_from_slice(&(udp_len as u16).to_be_bytes());
            buf[udp_start + 6..udp_start + 8].copy_from_slice(&[0, 0]);
            buf[udp_start + 8..total_len].copy_from_slice(payload);
            let udp_csum = udp_checksum_v6(*src_v6.ip(), *dst_v6.ip(), &buf[udp_start..total_len]);
            buf[udp_start + 6..udp_start + 8].copy_from_slice(&udp_csum.to_be_bytes());
            true
        }
        _ => false,
    }
}

pub fn build_tcp_packet(
    src: SocketAddr,
    dst: SocketAddr,
    seq: u32,
    ack: u32,
    flags: u8,
    payload: &[u8],
) -> Option<Vec<u8>> {
    let has_syn = (flags & 0x02) != 0;
    let tcp_header_len = if has_syn { 24 } else { 20 };
    let ip_header_len = if src.is_ipv6() { 40 } else { 20 };
    let total_len = ip_header_len + tcp_header_len + payload.len();
    let mut buf = vec![0u8; total_len];
    if craft_tcp_packet_into(src, dst, seq, ack, flags, payload, &mut buf) {
        Some(buf)
    } else {
        None
    }
}

pub fn build_udp_packet(src: SocketAddr, dst: SocketAddr, payload: &[u8]) -> Option<Vec<u8>> {
    let udp_len = 8 + payload.len();
    let ip_header_len = if src.is_ipv6() { 40 } else { 20 };
    let total_len = ip_header_len + udp_len;
    let mut buf = vec![0u8; total_len];
    if craft_udp_packet_into(src, dst, payload, &mut buf) {
        Some(buf)
    } else {
        None
    }
}

pub fn ip_checksum(header: &[u8]) -> u16 {
    let mut sum = 0u32;
    for chunk in header.chunks_exact(2) {
        sum += u16::from_be_bytes([chunk[0], chunk[1]]) as u32;
    }
    while (sum >> 16) != 0 {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !(sum as u16)
}

pub fn tcp_checksum_v4(src_ip: Ipv4Addr, dst_ip: Ipv4Addr, tcp_segment: &[u8]) -> u16 {
    let mut sum = 0u32;
    let src_oct = src_ip.octets();
    sum += u16::from_be_bytes([src_oct[0], src_oct[1]]) as u32;
    sum += u16::from_be_bytes([src_oct[2], src_oct[3]]) as u32;
    let dst_oct = dst_ip.octets();
    sum += u16::from_be_bytes([dst_oct[0], dst_oct[1]]) as u32;
    sum += u16::from_be_bytes([dst_oct[2], dst_oct[3]]) as u32;
    sum += 6u32;
    sum += tcp_segment.len() as u32;

    let mut chunks = tcp_segment.chunks_exact(2);
    for chunk in &mut chunks {
        sum += u16::from_be_bytes([chunk[0], chunk[1]]) as u32;
    }
    if let Some(&rem) = chunks.remainder().first() {
        sum += (rem as u32) << 8;
    }
    while (sum >> 16) != 0 {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !(sum as u16)
}

pub fn tcp_checksum_v6(src_ip: Ipv6Addr, dst_ip: Ipv6Addr, tcp_segment: &[u8]) -> u16 {
    let mut sum = 0u32;
    let src_oct = src_ip.octets();
    for chunk in src_oct.chunks_exact(2) {
        sum += u16::from_be_bytes([chunk[0], chunk[1]]) as u32;
    }
    let dst_oct = dst_ip.octets();
    for chunk in dst_oct.chunks_exact(2) {
        sum += u16::from_be_bytes([chunk[0], chunk[1]]) as u32;
    }
    sum += (tcp_segment.len() as u32) >> 16;
    sum += (tcp_segment.len() as u32) & 0xffff;
    sum += 6u32;

    let mut chunks = tcp_segment.chunks_exact(2);
    for chunk in &mut chunks {
        sum += u16::from_be_bytes([chunk[0], chunk[1]]) as u32;
    }
    if let Some(&rem) = chunks.remainder().first() {
        sum += (rem as u32) << 8;
    }
    while (sum >> 16) != 0 {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !(sum as u16)
}

pub fn udp_checksum_v4(src_ip: Ipv4Addr, dst_ip: Ipv4Addr, udp_segment: &[u8]) -> u16 {
    let mut sum = 0u32;
    let src_oct = src_ip.octets();
    sum += u16::from_be_bytes([src_oct[0], src_oct[1]]) as u32;
    sum += u16::from_be_bytes([src_oct[2], src_oct[3]]) as u32;
    let dst_oct = dst_ip.octets();
    sum += u16::from_be_bytes([dst_oct[0], dst_oct[1]]) as u32;
    sum += u16::from_be_bytes([dst_oct[2], dst_oct[3]]) as u32;
    sum += 17u32;
    sum += udp_segment.len() as u32;

    let mut chunks = udp_segment.chunks_exact(2);
    for chunk in &mut chunks {
        sum += u16::from_be_bytes([chunk[0], chunk[1]]) as u32;
    }
    if let Some(&rem) = chunks.remainder().first() {
        sum += (rem as u32) << 8;
    }
    while (sum >> 16) != 0 {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    let res = !(sum as u16);
    if res == 0 { 0xffff } else { res }
}

pub fn udp_checksum_v6(src_ip: Ipv6Addr, dst_ip: Ipv6Addr, udp_segment: &[u8]) -> u16 {
    let mut sum = 0u32;
    let src_oct = src_ip.octets();
    for chunk in src_oct.chunks_exact(2) {
        sum += u16::from_be_bytes([chunk[0], chunk[1]]) as u32;
    }
    let dst_oct = dst_ip.octets();
    for chunk in dst_oct.chunks_exact(2) {
        sum += u16::from_be_bytes([chunk[0], chunk[1]]) as u32;
    }
    sum += (udp_segment.len() as u32) >> 16;
    sum += (udp_segment.len() as u32) & 0xffff;
    sum += 17u32;

    let mut chunks = udp_segment.chunks_exact(2);
    for chunk in &mut chunks {
        sum += u16::from_be_bytes([chunk[0], chunk[1]]) as u32;
    }
    if let Some(&rem) = chunks.remainder().first() {
        sum += (rem as u32) << 8;
    }
    while (sum >> 16) != 0 {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    let res = !(sum as u16);
    if res == 0 { 0xffff } else { res }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_ip_checksum_computation() {
        let mut hdr = [
            0x45, 0x00, 0x00, 0x3c, 0x1c, 0x46, 0x40, 0x00, 0x40, 0x06, 0x00, 0x00, 0xac, 0x10,
            0x0a, 0x63, 0xac, 0x10, 0x0a, 0x0c,
        ];
        let csum = ip_checksum(&hdr);
        assert_eq!(csum, 0xb1e6);
        hdr[10..12].copy_from_slice(&csum.to_be_bytes());
        let verified = ip_checksum(&hdr);
        assert_eq!(verified, 0);
    }

    #[test]
    fn test_tcp_packet_construction() {
        let src: SocketAddr = "10.0.0.2:54321".parse().unwrap();
        let dst: SocketAddr = "77.88.55.60:443".parse().unwrap();
        let pkt = build_tcp_packet(src, dst, 1000, 2000, 0x12, b"test").unwrap();
        assert_eq!(pkt[0], 0x45);
        assert_eq!(pkt[9], 6);
        let dst_extracted = crate::sni::extract_dst_addr(&pkt).unwrap();
        assert_eq!(dst_extracted, dst);
        let src_extracted = crate::sni::extract_src_addr(&pkt).unwrap();
        assert_eq!(src_extracted, src);
    }

    #[test]
    fn test_udp_packet_construction() {
        let src: SocketAddr = "77.88.8.8:53".parse().unwrap();
        let dst: SocketAddr = "10.0.0.2:12345".parse().unwrap();
        let pkt = build_udp_packet(src, dst, b"dns_data").unwrap();
        assert_eq!(pkt[0], 0x45);
        assert_eq!(pkt[9], 17);
        let dst_extracted = crate::sni::extract_dst_addr(&pkt).unwrap();
        assert_eq!(dst_extracted, dst);
        let src_extracted = crate::sni::extract_src_addr(&pkt).unwrap();
        assert_eq!(src_extracted, src);
    }

    #[test]
    fn test_preresolve_domains_contains_critical() {
        assert!(PRERESOLVE_DOMAINS.contains(&"yandex.ru"));
        assert!(PRERESOLVE_DOMAINS.contains(&"ya.ru"));
        assert!(PRERESOLVE_DOMAINS.contains(&"speedtest.yandex.ru"));
        assert!(PRERESOLVE_DOMAINS.contains(&"vk.com"));
        assert!(PRERESOLVE_DOMAINS.contains(&"avito.ru"));
        assert!(PRERESOLVE_DOMAINS.contains(&"2gis.ru"));
        assert_eq!(PRERESOLVE_DOMAINS.len(), 68);
    }

    #[tokio::test]
    async fn test_smoltcp_direct_tcp_syn() {
        let domain_routing = Arc::new(ArcSwap::from_pointee(
            crate::dispatcher::DomainRoutingConfig {
                enabled: true,
                is_whitelist: false,
                exclude_ru_zones: true,
                template_domains: false,
                direct_domains: vec!["example.com".to_string()].into(),
                ip_routing_enabled: false,
                ip_is_whitelist: false,
                ip_rules: vec![].into(),
            },
        ));
        let direct_ips = Arc::new(ArcSwap::from_pointee(std::collections::HashSet::new()));
        let dns_domain_map = Arc::new(crate::sni::DnsDomainMap::default());
        let pool = PacketPool::new(64);
        let (inbound_tx, mut inbound_rx) = mpsc::channel(64);

        let relay = DirectRelay::new(
            domain_routing,
            direct_ips,
            dns_domain_map,
            pool.clone(),
            inbound_tx,
        )
        .expect("smoltcp test stack must initialize");

        let src: SocketAddr = "10.66.67.2:54321".parse().unwrap();
        let dst: SocketAddr = "93.184.216.34:80".parse().unwrap();
        relay
            .direct_ips
            .store(Arc::new(std::iter::once(dst.ip()).collect()));

        let syn_raw = build_tcp_packet(src, dst, 1000, 0, 0x02, &[]).unwrap();
        let mut pkt = pool.try_acquire().unwrap();
        pkt.extend_tail(syn_raw.len())
            .unwrap()
            .copy_from_slice(&syn_raw);

        let handled = relay.try_handle(&pkt).await;
        assert!(handled);

        let resp = tokio::time::timeout(Duration::from_millis(1000), inbound_rx.recv()).await;
        assert!(resp.is_ok(), "Expected SYN-ACK response from smoltcp stack");
        let resp_pkt = resp.unwrap().unwrap();
        let resp_slice = resp_pkt.as_slice();
        assert_eq!(resp_slice[9], 6);
        let flags = resp_slice[33];
        assert_eq!(flags & 0x12, 0x12, "Expected SYN-ACK flags (0x12)");
    }

    #[tokio::test]
    async fn test_direct_relay_active_tcp_cleanup_purge() {
        let domain_routing = Arc::new(ArcSwap::from_pointee(
            crate::dispatcher::DomainRoutingConfig::default(),
        ));
        let direct_ips = Arc::new(ArcSwap::from_pointee(std::collections::HashSet::new()));
        let dns_domain_map = Arc::new(crate::sni::DnsDomainMap::default());
        let pool = PacketPool::new(16);
        let (inbound_tx, _inbound_rx) = mpsc::channel(64);
        let relay = DirectRelay::new(domain_routing, direct_ips, dns_domain_map, pool, inbound_tx)
            .expect("smoltcp test stack must initialize");
        let k1: (SocketAddr, SocketAddr) = (
            "10.0.0.1:1000".parse().unwrap(),
            "1.1.1.1:80".parse().unwrap(),
        );
        let k2: (SocketAddr, SocketAddr) = (
            "10.0.0.2:2000".parse().unwrap(),
            "2.2.2.2:80".parse().unwrap(),
        );
        let now = std::time::Instant::now();
        {
            let mut guard = relay.active_tcp.write().unwrap();
            guard.insert(k1, now - Duration::from_secs(130));
            guard.insert(k2, now - Duration::from_secs(10));
        }
        {
            let mut guard = relay.active_tcp.write().unwrap();
            guard.retain(|_, last_seen| now.duration_since(*last_seen) < Duration::from_secs(120));
        }
        let guard = relay.active_tcp.read().unwrap();
        assert!(!guard.contains_key(&k1));
        assert!(guard.contains_key(&k2));
    }

    #[tokio::test]
    async fn test_direct_relay_malformed_packets_rejected_safely() {
        let domain_routing = Arc::new(ArcSwap::from_pointee(
            crate::dispatcher::DomainRoutingConfig {
                enabled: true,
                is_whitelist: false,
                exclude_ru_zones: true,
                template_domains: false,
                direct_domains: vec!["example.com".to_string()].into(),
                ip_routing_enabled: false,
                ip_is_whitelist: false,
                ip_rules: vec![].into(),
            },
        ));
        let direct_ips = Arc::new(ArcSwap::from_pointee(std::collections::HashSet::new()));
        let dns_domain_map = Arc::new(crate::sni::DnsDomainMap::default());
        let pool = PacketPool::new(16);
        let (inbound_tx, _inbound_rx) = mpsc::channel(64);
        let relay = DirectRelay::new(
            domain_routing,
            direct_ips,
            dns_domain_map,
            pool.clone(),
            inbound_tx,
        )
        .expect("smoltcp test stack must initialize");

        let empty_pkt = pool.try_acquire().unwrap();
        assert!(!relay.try_handle(&empty_pkt).await);

        for len in 1..20 {
            let mut short_pkt = pool.try_acquire().unwrap();
            let mut buf = vec![0u8; len];
            buf[0] = 0x45;
            short_pkt.extend_tail(len).unwrap().copy_from_slice(&buf);
            assert!(!relay.try_handle(&short_pkt).await);
        }

        let mut bad_ver = pool.try_acquire().unwrap();
        let mut buf = vec![0u8; 40];
        buf[0] = 0x25;
        bad_ver.extend_tail(40).unwrap().copy_from_slice(&buf);
        assert!(!relay.try_handle(&bad_ver).await);
    }

    #[tokio::test]
    async fn test_direct_relay_bypasses_icmp_cleanly() {
        let domain_routing = Arc::new(ArcSwap::from_pointee(
            crate::dispatcher::DomainRoutingConfig {
                enabled: true,
                is_whitelist: false,
                exclude_ru_zones: true,
                template_domains: false,
                direct_domains: vec!["example.com".to_string()].into(),
                ip_routing_enabled: false,
                ip_is_whitelist: false,
                ip_rules: vec![].into(),
            },
        ));
        let direct_ips = Arc::new(ArcSwap::from_pointee(std::collections::HashSet::new()));
        let dns_domain_map = Arc::new(crate::sni::DnsDomainMap::default());
        let pool = PacketPool::new(16);
        let (inbound_tx, _inbound_rx) = mpsc::channel(64);
        let relay = DirectRelay::new(
            domain_routing,
            direct_ips,
            dns_domain_map,
            pool.clone(),
            inbound_tx,
        )
        .expect("smoltcp test stack must initialize");

        let target_ip: IpAddr = "93.184.216.34".parse().unwrap();
        relay
            .direct_ips
            .store(Arc::new(std::iter::once(target_ip).collect()));

        let mut icmp_pkt = pool.try_acquire().unwrap();
        let mut buf = vec![0u8; 64];
        buf[0] = 0x45;
        buf[2..4].copy_from_slice(&64u16.to_be_bytes());
        buf[8] = 64;
        buf[9] = 1;
        buf[12..16].copy_from_slice(&[10, 66, 67, 2]);
        buf[16..20].copy_from_slice(&[93, 184, 216, 34]);
        buf[20] = 8;
        buf[21] = 0;
        icmp_pkt.extend_tail(64).unwrap().copy_from_slice(&buf);

        let handled = relay.try_handle(&icmp_pkt).await;
        assert!(!handled);
    }

    #[tokio::test]
    async fn test_run_full_preresolve_task_preserves_dynamic_ips() {
        let domain_routing = Arc::new(ArcSwap::from_pointee(
            crate::dispatcher::DomainRoutingConfig {
                enabled: true,
                is_whitelist: false,
                exclude_ru_zones: true,
                template_domains: false,
                direct_domains: vec![].into(),
                ip_routing_enabled: false,
                ip_is_whitelist: false,
                ip_rules: vec![].into(),
            },
        ));
        let dynamic_ip: IpAddr = "123.45.67.89".parse().unwrap();
        let direct_ips = Arc::new(ArcSwap::from_pointee(
            std::iter::once(dynamic_ip).collect::<std::collections::HashSet<IpAddr>>(),
        ));
        let dns_domain_map = Arc::new(crate::sni::DnsDomainMap::default());
        let current_gen = Arc::new(std::sync::atomic::AtomicU64::new(1));

        run_full_preresolve_task(
            direct_ips.clone(),
            dns_domain_map,
            domain_routing,
            1,
            current_gen,
            false,
            vec![],
        )
        .await;

        let loaded = direct_ips.load();
        assert!(loaded.contains(&dynamic_ip));
    }
}
