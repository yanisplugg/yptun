use crate::packet::{
    PACKET_CAPACITY, PacketBuf, PacketBuffer, PacketPool, TUN_MTU, TUN_TX_SLOTS, UDP_TX_SLOTS,
    packet_pool_size, socket_addr_to_storage, storage_to_socket_addr,
};
use anyhow::{Context, Result};
use socket2::{Domain, Protocol, Socket, Type};
use std::{
    collections::VecDeque,
    io,
    net::{IpAddr, Ipv4Addr, SocketAddr},
    os::fd::{AsRawFd, RawFd},
    time::Duration,
};
use tokio::io::{Interest, unix::AsyncFd};
use tokio::net::UdpSocket;

pub const TICK_INTERVAL_MS: u64 = 100;
pub const MAX_DATAGRAMS: usize = 32;
pub const MAX_RX_PER_PASS: usize = 32;
pub const TUN_RX_DRAIN_BATCH: usize = 32;
const UDP_CONTROL_BYTES: usize = 128;
const URGENCY_FLUSH_SYSCALLS: usize = 1;
pub const UDP_RECV_BUFFER_BYTES: usize = 512 * 1024;
pub const UDP_SEND_BUFFER_BYTES: usize = 64 * 1024;
pub const RESERVED_CONTROL_SLOTS: usize = 32;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum UdpDatagramMode {
    #[default]
    Batch,
}

impl UdpDatagramMode {
    pub const fn label(self) -> &'static str {
        "recvmmsg/sendmmsg"
    }
}

fn configured_udp_datagram_mode() -> UdpDatagramMode {
    UdpDatagramMode::Batch
}

#[derive(Clone, Copy, Debug, Default)]
pub struct IoCounters {
    pub udp_datagram_mode: UdpDatagramMode,
    pub udp_rx_packets: u64,
    pub udp_rx_bytes: u64,
    pub udp_rx_errors: u64,
    pub udp_rx_eagain: u64,
    pub udp_rx_enobufs: u64,
    pub udp_recv_syscalls: u64,
    pub udp_tx_packets: u64,
    pub udp_tx_bytes: u64,
    pub udp_tx_errors: u64,
    pub udp_tx_drops: u64,
    pub udp_tx_eagain: u64,
    pub udp_tx_enobufs: u64,
    pub udp_send_syscalls: u64,
    pub tun_rx_packets: u64,
    pub tun_rx_bytes: u64,
    pub tun_rx_errors: u64,
    pub tun_tx_packets: u64,
    pub tun_tx_bytes: u64,
    pub tun_tx_errors: u64,
    pub tun_tx_drops: u64,
    pub readiness_wakeups: u64,
    pub free_udp_tx_slots: u64,
    pub free_tun_tx_slots: u64,
    pub packet_pool_capacity: u64,
    pub packet_pool_allocated: u64,
    pub packet_pool_retained: u64,
}

#[repr(align(8))]
struct ControlBuffer([u8; UDP_CONTROL_BYTES]);

impl ControlBuffer {
    const fn new() -> Self {
        Self([0; UDP_CONTROL_BYTES])
    }

    fn as_mut_ptr(&mut self) -> *mut u8 {
        self.0.as_mut_ptr()
    }

    fn len(&self) -> usize {
        self.0.len()
    }
}

pub struct UdpTxSlot {
    buffer: Option<PacketBuf>,
    peer: libc::sockaddr_storage,
    peer_len: libc::socklen_t,
    control: ControlBuffer,
    control_len: usize,
    owner_key: u8,
}

impl UdpTxSlot {
    fn new() -> Self {
        Self {
            buffer: None,
            peer: unsafe { std::mem::zeroed() },
            peer_len: 0,
            control: ControlBuffer::new(),
            control_len: 0,
            owner_key: 0,
        }
    }

    fn prepare_current(&mut self, peer: SocketAddr, source_ip: Option<IpAddr>) {
        self.peer_len = socket_addr_to_storage(peer, &mut self.peer);
        self.control_len = match (peer, source_ip) {
            (SocketAddr::V4(_), Some(IpAddr::V4(ip))) => {
                write_ipv4_pktinfo_control(ip, &mut self.control)
            }
            _ => 0,
        };
    }
}

struct TunTxSlot {
    buffer: Option<PacketBuf>,
}

impl TunTxSlot {
    fn new() -> Self {
        Self { buffer: None }
    }
}

pub struct TunDevice(tun::Device);

impl AsRawFd for TunDevice {
    fn as_raw_fd(&self) -> RawFd {
        self.0.as_raw_fd()
    }
}

pub struct TokioIo {
    pub(crate) udp: UdpSocket,
    pub(crate) tun: AsyncFd<TunDevice>,
    udp_tx: Vec<UdpTxSlot>,
    packet_pool: PacketPool,
    free_udp_tx: VecDeque<usize>,
    pending_udp_tx: VecDeque<usize>,
    tun_rx: PacketBuffer,
    tun_tx: Vec<TunTxSlot>,
    free_tun_tx: VecDeque<usize>,
    pending_tun_tx: VecDeque<usize>,
    rx_batch: [PacketBuffer; MAX_DATAGRAMS],
    rx_peers: [libc::sockaddr_storage; MAX_DATAGRAMS],
    rx_controls: [ControlBuffer; MAX_DATAGRAMS],
    rx_iovecs: [libc::iovec; MAX_DATAGRAMS],
    rx_msgs: [libc::mmsghdr; MAX_DATAGRAMS],
    tx_iovecs: [libc::iovec; MAX_DATAGRAMS],
    tx_msgs: [libc::mmsghdr; MAX_DATAGRAMS],
    udp_datagram_mode: UdpDatagramMode,
    counters: IoCounters,
    tun_fatal: bool,
    _kernel_networking: KernelNetworking,
    client_in_flight: [u16; 256],
}

pub struct PacketSink<'a> {
    tun_fd: RawFd,
    packet_pool: &'a mut PacketPool,
    udp_tx: &'a mut [UdpTxSlot],
    tun_tx: &'a mut [TunTxSlot],
    free_udp_tx: &'a mut VecDeque<usize>,
    pending_udp_tx: &'a mut VecDeque<usize>,
    free_tun_tx: &'a mut VecDeque<usize>,
    pending_tun_tx: &'a mut VecDeque<usize>,
    counters: &'a mut IoCounters,
    tun_fatal: &'a mut bool,
    urgent_udp_flush: &'a mut bool,
    client_in_flight: &'a mut [u16; 256],
}

pub trait PacketOutput {
    fn client_data_headroom(
        &self,
        client_key: usize,
        active_clients: usize,
        active_streams: usize,
    ) -> usize;

    fn send_udp_prioritized<F>(
        &mut self,
        peer: SocketAddr,
        source_ip: Option<IpAddr>,
        duplicate: bool,
        priority: bool,
        build: F,
    ) -> bool
    where
        F: FnOnce(&mut PacketBuf) -> bool;

    #[allow(clippy::too_many_arguments)]
    fn send_udp_client_with_headroom<F>(
        &mut self,
        client_key: usize,
        peer: SocketAddr,
        source_ip: Option<IpAddr>,
        duplicate: bool,
        priority: bool,
        headroom: usize,
        build: F,
    ) -> bool
    where
        F: FnOnce(&mut PacketBuf) -> bool;

    fn request_udp_flush(&mut self);

    fn write_tun(&mut self, payload: &[u8]) -> bool;
}

impl PacketSink<'_> {
    #[inline(always)]
    pub fn client_data_headroom(
        &self,
        client_key: usize,
        active_clients: usize,
        active_streams: usize,
    ) -> usize {
        if self.free_udp_tx.len() <= RESERVED_CONTROL_SLOTS {
            return 0;
        }
        let available = UDP_TX_SLOTS.saturating_sub(RESERVED_CONTROL_SLOTS);
        let active_n = active_clients.max(1);
        let fair_share = (available / active_n).max(8);
        // Each allocation is rate-limited to ~2 Mbit (client token bucket:
        // 247_000 B/s). To hold that rate the path needs ~2 Mbit * RTT worth of
        // packets in flight; at ~40 ms that is ~10 packets per stream. So the
        // per-client window scales linearly with the stream count (10 st -> ~20
        // Mbit, 50 -> ~100, 100 -> ~200), and fair_share is the real ceiling.
        let stream_limit = (active_streams.max(1) * 10).clamp(64, available);
        let client_quota = fair_share.min(stream_limit);
        let in_flight = self.client_in_flight[client_key.min(254)] as usize;
        client_quota.saturating_sub(in_flight)
    }

    #[cfg(test)]
    #[inline(always)]
    pub fn has_client_data_slot(
        &self,
        client_key: usize,
        active_clients: usize,
        active_streams: usize,
    ) -> bool {
        self.client_data_headroom(client_key, active_clients, active_streams) > 0
    }

    #[inline]
    #[allow(clippy::too_many_arguments)]
    pub fn send_udp_client_with_headroom<F>(
        &mut self,
        client_key: usize,
        peer: SocketAddr,
        source_ip: Option<IpAddr>,
        _duplicate: bool,
        priority: bool,
        headroom: usize,
        build: F,
    ) -> bool
    where
        F: FnOnce(&mut PacketBuf) -> bool,
    {
        if !priority && headroom == 0 {
            self.counters.udp_tx_drops = self.counters.udp_tx_drops.saturating_add(1);
            return false;
        }
        if self.free_udp_tx.is_empty() {
            self.counters.udp_tx_drops = self.counters.udp_tx_drops.saturating_add(1);
            return false;
        }
        let Some(slot_id) = self.free_udp_tx.pop_front() else {
            self.counters.udp_tx_drops = self.counters.udp_tx_drops.saturating_add(1);
            return false;
        };
        let Some(mut buffer) = self.packet_pool.try_acquire() else {
            self.free_udp_tx.push_front(slot_id);
            self.counters.udp_tx_drops = self.counters.udp_tx_drops.saturating_add(1);
            return false;
        };
        if !build(&mut buffer) {
            self.packet_pool.recycle(buffer);
            self.free_udp_tx.push_front(slot_id);
            self.counters.udp_tx_drops = self.counters.udp_tx_drops.saturating_add(1);
            return false;
        }
        let owner = if priority {
            255
        } else {
            client_key.min(254) as u8
        };
        let slot = &mut self.udp_tx[slot_id];
        slot.buffer = Some(buffer);
        slot.owner_key = owner;
        slot.prepare_current(peer, source_ip);
        if !priority {
            self.client_in_flight[owner as usize] =
                self.client_in_flight[owner as usize].saturating_add(1);
        }
        if priority {
            let insert_pos = self
                .pending_udp_tx
                .iter()
                .position(|&id| self.udp_tx[id].owner_key != 255)
                .unwrap_or(self.pending_udp_tx.len());
            self.pending_udp_tx.insert(insert_pos, slot_id);
            *self.urgent_udp_flush = true;
        } else {
            self.pending_udp_tx.push_back(slot_id);
        }
        true
    }

    #[inline]
    pub fn send_udp_prioritized<F>(
        &mut self,
        peer: SocketAddr,
        source_ip: Option<IpAddr>,
        duplicate: bool,
        priority: bool,
        build: F,
    ) -> bool
    where
        F: FnOnce(&mut PacketBuf) -> bool,
    {
        let headroom = self.client_data_headroom(0, 1, 1);
        self.send_udp_client_with_headroom(0, peer, source_ip, duplicate, priority, headroom, build)
    }

    #[inline(always)]
    pub fn request_udp_flush(&mut self) {
        *self.urgent_udp_flush = true;
    }

    #[inline]
    pub fn write_tun(&mut self, payload: &[u8]) -> bool {
        if self.pending_tun_tx.is_empty() {
            match write_tun_packet(self.tun_fd, payload) {
                Ok(()) => {
                    self.counters.tun_tx_packets = self.counters.tun_tx_packets.saturating_add(1);
                    self.counters.tun_tx_bytes = self
                        .counters
                        .tun_tx_bytes
                        .saturating_add(payload.len() as u64);
                    return true;
                }
                Err(error)
                    if error.kind() == io::ErrorKind::WouldBlock
                        || error.raw_os_error() == Some(libc::ENOBUFS) => {}
                Err(error) if is_fatal_tun_write_error(&error) => {
                    eprintln!(
                        "[TUN] packet write failed (fatal): {error}; len={} head={:02x?}",
                        payload.len(),
                        &payload[..payload.len().min(8)]
                    );
                    self.counters.tun_tx_errors = self.counters.tun_tx_errors.saturating_add(1);
                    self.counters.tun_tx_drops = self.counters.tun_tx_drops.saturating_add(1);
                    *self.tun_fatal = true;
                    return false;
                }
                Err(_) => {
                    self.counters.tun_tx_errors = self.counters.tun_tx_errors.saturating_add(1);
                    self.counters.tun_tx_drops = self.counters.tun_tx_drops.saturating_add(1);
                    return false;
                }
            }
        }
        let Some(slot_id) = self.free_tun_tx.pop_front() else {
            self.counters.tun_tx_drops = self.counters.tun_tx_drops.saturating_add(1);
            return false;
        };
        let Some(mut buffer) = self.packet_pool.try_acquire() else {
            self.free_tun_tx.push_front(slot_id);
            self.counters.tun_tx_drops = self.counters.tun_tx_drops.saturating_add(1);
            return false;
        };
        if !buffer.copy_from(payload) {
            self.packet_pool.recycle(buffer);
            self.free_tun_tx.push_front(slot_id);
            self.counters.tun_tx_drops = self.counters.tun_tx_drops.saturating_add(1);
            return false;
        }
        self.tun_tx[slot_id].buffer = Some(buffer);
        self.pending_tun_tx.push_back(slot_id);
        true
    }
}

impl PacketOutput for PacketSink<'_> {
    #[inline(always)]
    fn client_data_headroom(
        &self,
        client_key: usize,
        active_clients: usize,
        active_streams: usize,
    ) -> usize {
        PacketSink::client_data_headroom(self, client_key, active_clients, active_streams)
    }

    #[inline]
    fn send_udp_prioritized<F>(
        &mut self,
        peer: SocketAddr,
        source_ip: Option<IpAddr>,
        duplicate: bool,
        priority: bool,
        build: F,
    ) -> bool
    where
        F: FnOnce(&mut PacketBuf) -> bool,
    {
        PacketSink::send_udp_prioritized(self, peer, source_ip, duplicate, priority, build)
    }

    #[inline]
    fn send_udp_client_with_headroom<F>(
        &mut self,
        client_key: usize,
        peer: SocketAddr,
        source_ip: Option<IpAddr>,
        duplicate: bool,
        priority: bool,
        headroom: usize,
        build: F,
    ) -> bool
    where
        F: FnOnce(&mut PacketBuf) -> bool,
    {
        PacketSink::send_udp_client_with_headroom(
            self, client_key, peer, source_ip, duplicate, priority, headroom, build,
        )
    }

    #[inline(always)]
    fn request_udp_flush(&mut self) {
        PacketSink::request_udp_flush(self)
    }

    #[inline]
    fn write_tun(&mut self, payload: &[u8]) -> bool {
        PacketSink::write_tun(self, payload)
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum RxOutcome {
    Batch(usize),
    Drained,
}

impl TokioIo {
    pub async fn new(listen: SocketAddr, tun_iface: &str, tun_addr: &str) -> Result<Self> {
        let udp_datagram_mode = configured_udp_datagram_mode();
        eprintln!("[DATAPLANE] UDP I/O mode: {}", udp_datagram_mode.label());
        let domain = if listen.is_ipv4() {
            Domain::IPV4
        } else {
            Domain::IPV6
        };
        let raw_socket =
            Socket::new(domain, Type::DGRAM, Some(Protocol::UDP)).context("create UDP socket")?;
        raw_socket.set_reuse_address(true)?;
        raw_socket.set_recv_buffer_size(UDP_RECV_BUFFER_BYTES)?;
        raw_socket.set_send_buffer_size(UDP_SEND_BUFFER_BYTES)?;
        let ipv4_pktinfo_enabled = listen.is_ipv4()
            && set_socket_int(
                raw_socket.as_raw_fd(),
                libc::IPPROTO_IP,
                libc::IP_PKTINFO,
                1,
            )
            .is_ok();
        if listen.is_ipv4() && !ipv4_pktinfo_enabled {
            eprintln!("[DATAPLANE] IP_PKTINFO unavailable; UDP source address symmetry disabled");
        }
        raw_socket
            .bind(&listen.into())
            .with_context(|| format!("bind {listen}"))?;
        raw_socket
            .set_nonblocking(true)
            .context("set UDP nonblocking mode")?;
        let std_socket = std::net::UdpSocket::from(raw_socket);
        let udp = UdpSocket::from_std(std_socket).context("register UDP socket in tokio")?;

        let address = tun_addr
            .parse::<Ipv4Addr>()
            .with_context(|| format!("invalid TUN address {tun_addr}"))?;
        let tun_name = tun_iface.to_owned();
        let (device, kernel_networking) = tokio::task::spawn_blocking(move || -> Result<_> {
            let mut config = tun::Configuration::default();
            config
                .tun_name(tun_name.as_str())
                .address(address)
                .netmask((255, 255, 255, 0))
                .mtu(TUN_MTU)
                .up();
            let device = create_tun_device(&config, &tun_name)?;
            device.set_nonblock().context("set TUN nonblocking mode")?;
            set_tun_txqueuelen(&tun_name);
            let kernel_networking = KernelNetworking::install(&tun_name);
            Ok((device, kernel_networking))
        })
        .await
        .context("join TUN setup task")??;
        let tun = AsyncFd::new(TunDevice(device)).context("register TUN in tokio")?;

        Ok(Self {
            udp,
            tun,
            udp_tx: (0..UDP_TX_SLOTS).map(|_| UdpTxSlot::new()).collect(),
            packet_pool: PacketPool::new(packet_pool_size()),
            free_udp_tx: (0..UDP_TX_SLOTS).collect(),
            pending_udp_tx: VecDeque::with_capacity(UDP_TX_SLOTS),
            tun_rx: PacketBuffer::new(),
            tun_tx: (0..TUN_TX_SLOTS).map(|_| TunTxSlot::new()).collect(),
            free_tun_tx: (0..TUN_TX_SLOTS).collect(),
            pending_tun_tx: VecDeque::with_capacity(TUN_TX_SLOTS),
            rx_batch: std::array::from_fn(|_| PacketBuffer::new()),
            rx_peers: [unsafe { std::mem::zeroed() }; MAX_DATAGRAMS],
            rx_controls: std::array::from_fn(|_| ControlBuffer::new()),
            rx_iovecs: [libc::iovec {
                iov_base: std::ptr::null_mut(),
                iov_len: 0,
            }; MAX_DATAGRAMS],
            rx_msgs: std::array::from_fn(|_| unsafe { std::mem::zeroed() }),
            tx_iovecs: [libc::iovec {
                iov_base: std::ptr::null_mut(),
                iov_len: 0,
            }; MAX_DATAGRAMS],
            tx_msgs: std::array::from_fn(|_| unsafe { std::mem::zeroed() }),
            udp_datagram_mode,
            counters: IoCounters {
                udp_datagram_mode,
                ..IoCounters::default()
            },
            tun_fatal: false,
            _kernel_networking: kernel_networking,
            client_in_flight: [0; 256],
        })
    }

    pub fn with_sink<R>(&mut self, process: impl FnOnce(&mut PacketSink<'_>) -> R) -> R {
        let mut urgent_udp_flush = false;
        let output = {
            let tun_fd = self.tun.as_raw_fd();
            let Self {
                udp_tx,
                packet_pool,
                tun_tx,
                free_udp_tx,
                pending_udp_tx,
                free_tun_tx,
                pending_tun_tx,
                counters,
                tun_fatal,
                client_in_flight,
                ..
            } = self;
            let mut sink = PacketSink {
                tun_fd,
                packet_pool,
                udp_tx,
                tun_tx,
                free_udp_tx,
                pending_udp_tx,
                free_tun_tx,
                pending_tun_tx,
                counters,
                tun_fatal,
                urgent_udp_flush: &mut urgent_udp_flush,
                client_in_flight,
            };
            process(&mut sink)
        };
        if urgent_udp_flush {
            self.flush_udp_tx(URGENCY_FLUSH_SYSCALLS);
        }
        output
    }

    pub fn dispatch_udp_rx<L>(&mut self, budget: usize, logic: &mut L) -> RxOutcome
    where
        L: FnMut(SocketAddr, Option<IpAddr>, &mut [u8], &mut PacketSink<'_>),
    {
        let packet_limit = budget.min(MAX_DATAGRAMS);
        if packet_limit == 0 {
            return RxOutcome::Drained;
        }
        let udp_fd = self.udp.as_raw_fd();
        let tun_fd = self.tun.as_raw_fd();
        for index in 0..packet_limit {
            self.rx_iovecs[index] = libc::iovec {
                iov_base: self.rx_batch[index].as_mut_ptr().cast(),
                iov_len: PACKET_CAPACITY,
            };
            self.rx_msgs[index] = unsafe { std::mem::zeroed() };
            let msg = &mut self.rx_msgs[index].msg_hdr;
            msg.msg_name = (&mut self.rx_peers[index] as *mut libc::sockaddr_storage).cast();
            msg.msg_namelen = std::mem::size_of::<libc::sockaddr_storage>() as libc::socklen_t;
            msg.msg_iov = &mut self.rx_iovecs[index];
            msg.msg_iovlen = 1;
            msg.msg_control = self.rx_controls[index].as_mut_ptr().cast();
            msg.msg_controllen = UDP_CONTROL_BYTES as _;
        }
        let received = self.udp.try_io(Interest::READABLE, || {
            loop {
                let result = unsafe {
                    libc::recvmmsg(
                        udp_fd,
                        self.rx_msgs.as_mut_ptr(),
                        packet_limit as _,
                        libc::MSG_DONTWAIT as _,
                        std::ptr::null_mut(),
                    )
                };
                if result < 0 {
                    let error = io::Error::last_os_error();
                    if error.kind() == io::ErrorKind::Interrupted {
                        continue;
                    }
                    return Err(error);
                }
                return Ok(result as usize);
            }
        });
        let received = match received {
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                self.counters.udp_rx_eagain = self.counters.udp_rx_eagain.saturating_add(1);
                return RxOutcome::Drained;
            }
            Err(error) => {
                self.counters.udp_rx_errors = self.counters.udp_rx_errors.saturating_add(1);
                if error.raw_os_error() == Some(libc::ENOBUFS) {
                    self.counters.udp_rx_enobufs = self.counters.udp_rx_enobufs.saturating_add(1);
                }
                return RxOutcome::Drained;
            }
            Ok(0) => return RxOutcome::Drained,
            Ok(count) => count.min(packet_limit),
        };
        self.counters.udp_recv_syscalls = self.counters.udp_recv_syscalls.saturating_add(1);
        let mut urgent_udp_flush = false;
        for index in 0..received {
            let msg = &self.rx_msgs[index].msg_hdr;
            let len = self.rx_msgs[index].msg_len as usize;
            if msg.msg_flags & libc::MSG_TRUNC != 0 || len > PACKET_CAPACITY {
                self.counters.udp_rx_errors = self.counters.udp_rx_errors.saturating_add(1);
                continue;
            }
            if !self.rx_batch[index].set_len(len) {
                self.counters.udp_rx_errors = self.counters.udp_rx_errors.saturating_add(1);
                continue;
            }
            let Some(peer) = storage_to_socket_addr(&self.rx_peers[index], msg.msg_namelen) else {
                self.counters.udp_rx_errors = self.counters.udp_rx_errors.saturating_add(1);
                continue;
            };
            let control_len = (msg.msg_controllen as usize).min(self.rx_controls[index].len());
            let local_ip = parse_ipv4_pktinfo_destination(unsafe {
                std::slice::from_raw_parts(
                    self.rx_controls[index].0.as_ptr().cast::<u8>(),
                    control_len,
                )
            })
            .map(IpAddr::V4);
            self.counters.udp_rx_packets = self.counters.udp_rx_packets.saturating_add(1);
            self.counters.udp_rx_bytes = self.counters.udp_rx_bytes.saturating_add(len as u64);
            {
                let Self {
                    udp_tx,
                    packet_pool,
                    tun_tx,
                    free_udp_tx,
                    pending_udp_tx,
                    free_tun_tx,
                    pending_tun_tx,
                    counters,
                    tun_fatal,
                    client_in_flight,
                    ..
                } = self;
                let packet = self.rx_batch[index].as_mut_slice();
                let mut sink = PacketSink {
                    tun_fd,
                    packet_pool,
                    udp_tx,
                    tun_tx,
                    free_udp_tx,
                    pending_udp_tx,
                    free_tun_tx,
                    pending_tun_tx,
                    counters,
                    tun_fatal,
                    urgent_udp_flush: &mut urgent_udp_flush,
                    client_in_flight,
                };
                logic(peer, local_ip, packet, &mut sink);
            }
        }
        if urgent_udp_flush {
            self.flush_udp_tx(URGENCY_FLUSH_SYSCALLS);
        }
        RxOutcome::Batch(received)
    }

    pub fn read_tun_rx<L>(&mut self, max_packets: usize, logic: &mut L) -> io::Result<RxOutcome>
    where
        L: FnMut(&mut [u8], &mut PacketSink<'_>),
    {
        let tun_fd = self.tun.as_raw_fd();
        let mut processed = 0usize;
        let mut urgent_udp_flush = false;
        let mut drained = false;
        while processed < max_packets {
            let buffer = &mut self.tun_rx;
            let read = self.tun.try_io(Interest::READABLE, |_| {
                loop {
                    let result = unsafe {
                        libc::read(tun_fd, buffer.as_mut_ptr().cast(), buffer.capacity())
                    };
                    if result < 0 {
                        let error = io::Error::last_os_error();
                        if error.kind() == io::ErrorKind::Interrupted {
                            continue;
                        }
                        return Err(error);
                    }
                    return Ok(result as usize);
                }
            });
            let len = match read {
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                    drained = true;
                    break;
                }
                Err(error) => {
                    self.counters.tun_rx_errors = self.counters.tun_rx_errors.saturating_add(1);
                    return Err(error);
                }
                Ok(0) => {
                    self.counters.tun_rx_errors = self.counters.tun_rx_errors.saturating_add(1);
                    return Err(io::Error::new(
                        io::ErrorKind::UnexpectedEof,
                        "TUN packet reader returned EOF",
                    ));
                }
                Ok(len) => len,
            };
            if !self.tun_rx.set_len(len) {
                self.counters.tun_rx_errors = self.counters.tun_rx_errors.saturating_add(1);
                continue;
            }
            self.counters.tun_rx_packets = self.counters.tun_rx_packets.saturating_add(1);
            self.counters.tun_rx_bytes = self.counters.tun_rx_bytes.saturating_add(len as u64);
            {
                let Self {
                    udp_tx,
                    packet_pool,
                    tun_tx,
                    free_udp_tx,
                    pending_udp_tx,
                    free_tun_tx,
                    pending_tun_tx,
                    counters,
                    tun_fatal,
                    client_in_flight,
                    ..
                } = self;
                let packet = self.tun_rx.as_mut_slice();
                let mut sink = PacketSink {
                    tun_fd,
                    packet_pool,
                    udp_tx,
                    tun_tx,
                    free_udp_tx,
                    pending_udp_tx,
                    free_tun_tx,
                    pending_tun_tx,
                    counters,
                    tun_fatal,
                    urgent_udp_flush: &mut urgent_udp_flush,
                    client_in_flight,
                };
                logic(packet, &mut sink);
            }
            processed += 1;
        }
        if urgent_udp_flush {
            self.flush_udp_tx(URGENCY_FLUSH_SYSCALLS);
        }
        if drained {
            Ok(RxOutcome::Drained)
        } else {
            Ok(RxOutcome::Batch(processed))
        }
    }

    pub fn flush_udp_tx(&mut self, max_syscalls: usize) {
        let udp_fd = self.udp.as_raw_fd();
        let mut syscalls = 0usize;
        while self.pending_udp_tx_len() != 0 && syscalls < max_syscalls {
            let Some(slot_id) = self.front_pending_udp_tx() else {
                return;
            };
            if self.udp_tx[slot_id].buffer.is_none() {
                if let Some(slot_id) = self.pop_pending_udp_tx() {
                    let owner = self.udp_tx[slot_id].owner_key as usize;
                    if owner < 255 {
                        self.client_in_flight[owner] =
                            self.client_in_flight[owner].saturating_sub(1);
                    }
                    self.counters.udp_tx_errors = self.counters.udp_tx_errors.saturating_add(1);
                    self.counters.udp_tx_drops = self.counters.udp_tx_drops.saturating_add(1);
                    self.free_udp_tx.push_back(slot_id);
                }
                continue;
            }
            let mut packet_count = 0usize;
            for queued_slot in self.pending_udp_tx.iter().copied().take(MAX_DATAGRAMS) {
                let Some(buffer) = self.udp_tx[queued_slot].buffer.as_mut() else {
                    break;
                };
                self.tx_iovecs[packet_count] = libc::iovec {
                    iov_base: buffer.as_mut_ptr().cast(),
                    iov_len: buffer.len(),
                };
                let slot = &mut self.udp_tx[queued_slot];
                self.tx_msgs[packet_count] = unsafe { std::mem::zeroed() };
                let msg = &mut self.tx_msgs[packet_count].msg_hdr;
                msg.msg_name = (&mut slot.peer as *mut libc::sockaddr_storage).cast();
                msg.msg_namelen = slot.peer_len;
                msg.msg_iov = &mut self.tx_iovecs[packet_count];
                msg.msg_iovlen = 1;
                msg.msg_control = if slot.control_len > 0 {
                    slot.control.as_mut_ptr().cast()
                } else {
                    std::ptr::null_mut()
                };
                msg.msg_controllen = slot.control_len as _;
                packet_count += 1;
            }
            if packet_count == 0 {
                return;
            }
            syscalls += 1;
            let mut tx_enobufs = false;
            let sent = self.udp.try_io(Interest::WRITABLE, || {
                loop {
                    let result = unsafe {
                        libc::sendmmsg(
                            udp_fd,
                            self.tx_msgs.as_mut_ptr(),
                            packet_count as _,
                            libc::MSG_DONTWAIT as _,
                        )
                    };
                    if result < 0 {
                        let error = io::Error::last_os_error();
                        if error.kind() == io::ErrorKind::Interrupted {
                            continue;
                        }
                        if error.raw_os_error() == Some(libc::ENOBUFS) {
                            tx_enobufs = true;
                            return Err(io::Error::from(io::ErrorKind::WouldBlock));
                        }
                        let mut has_control = false;
                        for i in 0..packet_count {
                            if !self.tx_msgs[i].msg_hdr.msg_control.is_null() {
                                self.tx_msgs[i].msg_hdr.msg_control = std::ptr::null_mut();
                                self.tx_msgs[i].msg_hdr.msg_controllen = 0;
                                has_control = true;
                            }
                        }
                        if has_control {
                            let retry = unsafe {
                                libc::sendmmsg(
                                    udp_fd,
                                    self.tx_msgs.as_mut_ptr(),
                                    packet_count as _,
                                    libc::MSG_DONTWAIT as _,
                                )
                            };
                            if retry >= 0 {
                                return Ok(retry as usize);
                            }
                        }
                        return Err(error);
                    }
                    return Ok(result as usize);
                }
            });
            match sent {
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                    if tx_enobufs {
                        self.counters.udp_tx_enobufs =
                            self.counters.udp_tx_enobufs.saturating_add(1);
                    } else {
                        self.counters.udp_tx_eagain = self.counters.udp_tx_eagain.saturating_add(1);
                    }
                    return;
                }
                Err(_) => {
                    self.counters.udp_tx_errors = self.counters.udp_tx_errors.saturating_add(1);
                    self.counters.udp_tx_drops = self
                        .counters
                        .udp_tx_drops
                        .saturating_add(packet_count as u64);
                    for _ in 0..packet_count {
                        if let Some(slot_id) = self.pop_pending_udp_tx() {
                            let owner = self.udp_tx[slot_id].owner_key as usize;
                            if owner < 255 {
                                self.client_in_flight[owner] =
                                    self.client_in_flight[owner].saturating_sub(1);
                            }
                            if let Some(buffer) = self.udp_tx[slot_id].buffer.take() {
                                self.packet_pool.recycle(buffer);
                            }
                            self.free_udp_tx.push_back(slot_id);
                        }
                    }
                }
                Ok(0) => {
                    self.counters.udp_tx_eagain = self.counters.udp_tx_eagain.saturating_add(1);
                    return;
                }
                Ok(sent) => {
                    let sent = sent.min(packet_count);
                    self.counters.udp_send_syscalls =
                        self.counters.udp_send_syscalls.saturating_add(1);
                    for _ in 0..sent {
                        let Some(slot_id) = self.pop_pending_udp_tx() else {
                            return;
                        };
                        let owner = self.udp_tx[slot_id].owner_key as usize;
                        if owner < 255 {
                            self.client_in_flight[owner] =
                                self.client_in_flight[owner].saturating_sub(1);
                        }
                        let length = self.udp_tx[slot_id]
                            .buffer
                            .as_ref()
                            .map_or(0, PacketBuf::len);
                        self.counters.udp_tx_packets =
                            self.counters.udp_tx_packets.saturating_add(1);
                        self.counters.udp_tx_bytes =
                            self.counters.udp_tx_bytes.saturating_add(length as u64);
                        if let Some(buffer) = self.udp_tx[slot_id].buffer.take() {
                            self.packet_pool.recycle(buffer);
                        }
                        self.free_udp_tx.push_back(slot_id);
                    }
                    if sent < packet_count {
                        return;
                    }
                }
            }
        }
    }

    pub fn flush_tun_tx(&mut self) {
        let tun_fd = self.tun.as_raw_fd();
        while let Some(slot_id) = self.front_pending_tun_tx() {
            if self.tun_tx[slot_id].buffer.is_none() {
                self.pop_pending_tun_tx();
                self.counters.tun_tx_errors = self.counters.tun_tx_errors.saturating_add(1);
                self.counters.tun_tx_drops = self.counters.tun_tx_drops.saturating_add(1);
                self.free_tun_tx.push_back(slot_id);
                continue;
            }
            let written = {
                let payload = self.tun_tx[slot_id]
                    .buffer
                    .as_ref()
                    .map(PacketBuf::as_slice)
                    .unwrap_or_default();
                self.tun
                    .try_io(Interest::WRITABLE, |_| write_tun_packet(tun_fd, payload))
            };
            match written {
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => return,
                Err(error) if is_fatal_tun_write_error(&error) => {
                    eprintln!("[TUN] queued packet write failed (fatal): {error}");
                    self.counters.tun_tx_errors = self.counters.tun_tx_errors.saturating_add(1);
                    self.counters.tun_tx_drops = self.counters.tun_tx_drops.saturating_add(1);
                    self.tun_fatal = true;
                    if let Some(slot_id) = self.pop_pending_tun_tx() {
                        if let Some(buffer) = self.tun_tx[slot_id].buffer.take() {
                            self.packet_pool.recycle(buffer);
                        }
                        self.free_tun_tx.push_back(slot_id);
                    }
                    return;
                }
                Err(_) => {
                    self.counters.tun_tx_errors = self.counters.tun_tx_errors.saturating_add(1);
                    self.counters.tun_tx_drops = self.counters.tun_tx_drops.saturating_add(1);
                    if let Some(slot_id) = self.pop_pending_tun_tx() {
                        if let Some(buffer) = self.tun_tx[slot_id].buffer.take() {
                            self.packet_pool.recycle(buffer);
                        }
                        self.free_tun_tx.push_back(slot_id);
                    }
                    continue;
                }
                Ok(()) => {
                    let payload_len = self.tun_tx[slot_id]
                        .buffer
                        .as_ref()
                        .map(PacketBuf::len)
                        .unwrap_or(0);
                    self.counters.tun_tx_packets = self.counters.tun_tx_packets.saturating_add(1);
                    self.counters.tun_tx_bytes = self
                        .counters
                        .tun_tx_bytes
                        .saturating_add(payload_len as u64);
                    self.pop_pending_tun_tx();
                    if let Some(buffer) = self.tun_tx[slot_id].buffer.take() {
                        self.packet_pool.recycle(buffer);
                    }
                    self.free_tun_tx.push_back(slot_id);
                }
            }
        }
    }

    #[inline(always)]
    pub fn pending_udp_tx_len(&self) -> usize {
        self.pending_udp_tx.len()
    }

    #[inline(always)]
    pub fn pending_tun_tx_len(&self) -> usize {
        self.pending_tun_tx.len()
    }

    #[inline(always)]
    fn front_pending_udp_tx(&self) -> Option<usize> {
        self.pending_udp_tx.front().copied()
    }

    #[inline(always)]
    fn front_pending_tun_tx(&self) -> Option<usize> {
        self.pending_tun_tx.front().copied()
    }

    #[inline(always)]
    fn pop_pending_udp_tx(&mut self) -> Option<usize> {
        self.pending_udp_tx.pop_front()
    }

    #[inline(always)]
    fn pop_pending_tun_tx(&mut self) -> Option<usize> {
        self.pending_tun_tx.pop_front()
    }

    pub fn take_tun_fatal(&mut self) -> bool {
        std::mem::take(&mut self.tun_fatal)
    }

    #[inline]
    pub fn note_readiness_wakeup(&mut self) {
        self.counters.readiness_wakeups = self.counters.readiness_wakeups.saturating_add(1);
    }

    pub fn counters_snapshot(&self) -> IoCounters {
        let mut snapshot = self.counters;
        snapshot.udp_datagram_mode = self.udp_datagram_mode;
        snapshot.free_udp_tx_slots = self.free_udp_tx.len() as u64;
        snapshot.free_tun_tx_slots = self.free_tun_tx.len() as u64;
        let pool = self.packet_pool.snapshot();
        snapshot.packet_pool_capacity = pool.capacity as u64;
        snapshot.packet_pool_allocated = pool.allocated as u64;
        snapshot.packet_pool_retained = pool.retained as u64;
        snapshot
    }
}

const MANAGED_RULE_COMMENT: &str = "CSQTT_MANAGED";
const FIREWALL_REMOVE_PASSES: usize = 4;
const KERNEL_PARAMETERS: &[(&str, &str)] = &[
    ("/proc/sys/net/ipv4/ip_forward", "1"),
    ("/proc/sys/net/ipv6/conf/all/forwarding", "1"),
    ("/proc/sys/net/ipv4/conf/all/rp_filter", "2"),
    ("/proc/sys/net/ipv4/conf/default/rp_filter", "2"),
    ("/proc/sys/net/core/default_qdisc", "fq"),
    ("/proc/sys/net/ipv4/tcp_congestion_control", "bbr"),
    ("/proc/sys/net/ipv4/tcp_sack", "1"),
    ("/proc/sys/net/ipv4/tcp_dsack", "1"),
    ("/proc/sys/net/ipv4/tcp_reordering", "15"),
    ("/proc/sys/net/ipv4/tcp_window_scaling", "1"),
    ("/proc/sys/net/ipv4/tcp_fastopen", "3"),
    ("/proc/sys/net/ipv4/tcp_slow_start_after_idle", "0"),
];

fn set_kernel_parameter(path: &str, value: &str) {
    if let Err(error) = std::fs::write(path, value) {
        eprintln!("[NET] {path} = {value} failed: {error}");
    }
}

fn set_tun_txqueuelen(tun_iface: &str) {
    match std::process::Command::new("ip")
        .args(["link", "set", "dev", tun_iface, "txqueuelen", "48"])
        .output()
    {
        Ok(output) if output.status.success() => {}
        Ok(output) => eprintln!(
            "[TUN] set txqueuelen on {tun_iface} failed: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        ),
        Err(error) => eprintln!("[TUN] set txqueuelen on {tun_iface} failed: {error}"),
    }
}

struct FirewallRule {
    program: &'static str,
    table: Option<&'static str>,
    chain: &'static str,
    spec: Vec<String>,
}

impl FirewallRule {
    fn new(
        program: &'static str,
        table: Option<&'static str>,
        chain: &'static str,
        matches: &[&str],
        target: &[&str],
    ) -> Self {
        let spec = matches
            .iter()
            .copied()
            .chain(["-m", "comment", "--comment", MANAGED_RULE_COMMENT])
            .chain(target.iter().copied())
            .map(str::to_owned)
            .collect();
        Self {
            program,
            table,
            chain,
            spec,
        }
    }

    fn invoke(&self, action: &str, position: &[&str]) -> io::Result<std::process::Output> {
        let mut command = std::process::Command::new(self.program);
        command.args(["-w", "2"]);
        if let Some(table) = self.table {
            command.args(["-t", table]);
        }
        command
            .arg(action)
            .arg(self.chain)
            .args(position)
            .args(&self.spec)
            .output()
    }

    fn is_present(&self) -> bool {
        self.invoke("-C", &[])
            .is_ok_and(|output| output.status.success())
    }

    fn install(&self) -> Result<()> {
        if self.is_present() {
            return Ok(());
        }
        let output = self
            .invoke("-I", &["1"])
            .with_context(|| format!("run {}", self.program))?;
        if !output.status.success() {
            anyhow::bail!(
                "{} -I {} failed: {}",
                self.program,
                self.chain,
                String::from_utf8_lossy(&output.stderr).trim()
            );
        }
        Ok(())
    }

    fn remove(&self) {
        for _ in 0..FIREWALL_REMOVE_PASSES {
            if !self.is_present() {
                return;
            }
            if self.invoke("-D", &[]).is_err() {
                return;
            }
        }
    }
}

fn purge_legacy_mss_clamp() {
    let subnet = crate::tun_device::TUN_SUBNET;
    let mss_source = [
        "-s",
        subnet,
        "-p",
        "tcp",
        "-m",
        "tcp",
        "--tcp-flags",
        "SYN,RST",
        "SYN",
    ];
    let mss_destination = [
        "-d",
        subnet,
        "-p",
        "tcp",
        "-m",
        "tcp",
        "--tcp-flags",
        "SYN,RST",
        "SYN",
    ];
    for mss in [
        "1120", "1160", "1200", "1240", "1280", "1360", "1400", "1460",
    ] {
        let mss_target = ["-j", "TCPMSS", "--set-mss", mss];
        FirewallRule::new(
            "iptables",
            Some("mangle"),
            "FORWARD",
            &mss_source,
            &mss_target,
        )
        .remove();
        FirewallRule::new(
            "iptables",
            Some("mangle"),
            "FORWARD",
            &mss_destination,
            &mss_target,
        )
        .remove();
    }
}

fn managed_firewall_rules(tun_iface: &str) -> Vec<FirewallRule> {
    let subnet = crate::tun_device::TUN_SUBNET;
    vec![
        FirewallRule::new(
            "iptables",
            None,
            "FORWARD",
            &["-i", tun_iface],
            &["-j", "ACCEPT"],
        ),
        FirewallRule::new(
            "iptables",
            None,
            "FORWARD",
            &["-o", tun_iface],
            &["-j", "ACCEPT"],
        ),
        FirewallRule::new(
            "iptables",
            Some("nat"),
            "POSTROUTING",
            &["-s", subnet, "!", "-o", tun_iface],
            &["-j", "MASQUERADE"],
        ),
        FirewallRule::new(
            "ip6tables",
            None,
            "FORWARD",
            &["-i", tun_iface],
            &["-j", "ACCEPT"],
        ),
        FirewallRule::new(
            "ip6tables",
            None,
            "FORWARD",
            &["-o", tun_iface],
            &["-j", "ACCEPT"],
        ),
    ]
}

struct KernelNetworking {
    rules: Vec<FirewallRule>,
}

impl KernelNetworking {
    fn install(tun_iface: &str) -> Self {
        let _ = std::process::Command::new("modprobe")
            .arg("tcp_bbr")
            .output();
        for (path, value) in KERNEL_PARAMETERS {
            set_kernel_parameter(path, value);
        }
        set_kernel_parameter(
            &format!("/proc/sys/net/ipv4/conf/{tun_iface}/rp_filter"),
            "2",
        );
        purge_legacy_mss_clamp();
        let rules = managed_firewall_rules(tun_iface);
        for rule in &rules {
            if let Err(error) = rule.install() {
                eprintln!("[NET] {error:#}");
            }
        }
        Self { rules }
    }
}

impl Drop for KernelNetworking {
    fn drop(&mut self) {
        for rule in &self.rules {
            rule.remove();
        }
    }
}

fn create_tun_device(config: &tun::Configuration, tun_iface: &str) -> Result<tun::Device> {
    let mut last_error = None;
    for attempt in 0..8 {
        match tun::create(config) {
            Ok(device) => return Ok(device),
            Err(error) if is_tun_create_retryable(&error) => {
                eprintln!(
                    "[TUN] create {tun_iface} attempt {} failed ({error}), retrying",
                    attempt + 1
                );
                last_error = Some(error);
                std::thread::sleep(Duration::from_millis(40 + attempt * 40));
            }
            Err(error) => {
                return Err(error).with_context(|| format!("create TUN device {tun_iface}"));
            }
        }
    }
    Err(last_error.unwrap_or_else(|| tun::Error::String("TUN create retry exhausted".to_owned())))
        .with_context(|| format!("create TUN device {tun_iface}"))
}

fn is_tun_create_retryable(error: &tun::Error) -> bool {
    match error {
        tun::Error::Io(error) => matches!(
            error.raw_os_error(),
            Some(
                libc::EEXIST
                    | libc::EBUSY
                    | libc::EADDRINUSE
                    | libc::ENOENT
                    | libc::ENODEV
                    | libc::EAGAIN
                    | libc::EINTR
            )
        ),
        _ => false,
    }
}

fn set_socket_int(
    fd: RawFd,
    level: libc::c_int,
    name: libc::c_int,
    value: libc::c_int,
) -> io::Result<()> {
    let result = unsafe {
        libc::setsockopt(
            fd,
            level,
            name,
            (&value as *const libc::c_int).cast(),
            std::mem::size_of::<libc::c_int>() as libc::socklen_t,
        )
    };
    if result == 0 {
        Ok(())
    } else {
        Err(io::Error::last_os_error())
    }
}

fn cmsg_align(length: usize) -> usize {
    let align = std::mem::size_of::<usize>();
    (length + align - 1) & !(align - 1)
}

fn parse_ipv4_pktinfo_destination(mut control: &[u8]) -> Option<Ipv4Addr> {
    while control.len() >= std::mem::size_of::<libc::cmsghdr>() {
        let header = unsafe { &*(control.as_ptr().cast::<libc::cmsghdr>()) };
        let length = header.cmsg_len as usize;
        if length < std::mem::size_of::<libc::cmsghdr>() || length > control.len() {
            break;
        }
        if header.cmsg_level == libc::IPPROTO_IP && header.cmsg_type == libc::IP_PKTINFO {
            let data_offset = cmsg_align(std::mem::size_of::<libc::cmsghdr>());
            if length >= data_offset + std::mem::size_of::<libc::in_pktinfo>() {
                let pktinfo = unsafe {
                    std::ptr::read_unaligned(
                        control[data_offset..].as_ptr().cast::<libc::in_pktinfo>(),
                    )
                };
                let spec = Ipv4Addr::from(pktinfo.ipi_spec_dst.s_addr.to_ne_bytes());
                if !spec.is_unspecified() {
                    return Some(spec);
                }
                let destination = Ipv4Addr::from(pktinfo.ipi_addr.s_addr.to_ne_bytes());
                if !destination.is_unspecified() {
                    return Some(destination);
                }
            }
        }
        let advance = cmsg_align(length);
        if advance == 0 {
            break;
        }
        control = &control[advance.min(control.len())..];
    }
    None
}

fn write_ipv4_pktinfo_control(source: Ipv4Addr, control: &mut ControlBuffer) -> usize {
    if source.is_unspecified() {
        return 0;
    }
    let data_len = std::mem::size_of::<libc::in_pktinfo>();
    let space = unsafe { libc::CMSG_SPACE(data_len as libc::c_uint) as usize };
    let length = unsafe { libc::CMSG_LEN(data_len as libc::c_uint) as usize };
    if space > control.len() {
        return 0;
    }
    unsafe {
        std::ptr::write_bytes(control.as_mut_ptr(), 0, control.len());
        let header = control.as_mut_ptr().cast::<libc::cmsghdr>();
        (*header).cmsg_len = length as _;
        (*header).cmsg_level = libc::IPPROTO_IP;
        (*header).cmsg_type = libc::IP_PKTINFO;
        let pktinfo = libc::in_pktinfo {
            ipi_ifindex: 0,
            ipi_spec_dst: libc::in_addr {
                s_addr: u32::from_ne_bytes(source.octets()),
            },
            ipi_addr: libc::in_addr { s_addr: 0 },
        };
        std::ptr::copy_nonoverlapping(
            (&pktinfo as *const libc::in_pktinfo).cast::<u8>(),
            libc::CMSG_DATA(header),
            data_len,
        );
    }
    space
}

fn is_fatal_tun_write_error(error: &io::Error) -> bool {
    matches!(
        error.raw_os_error(),
        Some(code) if code == libc::ENODEV || code == libc::ENXIO || code == libc::EBADF
    )
}

fn write_tun_packet(fd: RawFd, payload: &[u8]) -> io::Result<()> {
    loop {
        let result = unsafe { libc::write(fd, payload.as_ptr().cast(), payload.len()) };
        if result == payload.len() as isize {
            return Ok(());
        }
        if result >= 0 {
            return Err(io::Error::new(
                io::ErrorKind::WriteZero,
                "short TUN packet write",
            ));
        }
        let error = io::Error::last_os_error();
        if error.kind() == io::ErrorKind::Interrupted {
            continue;
        }
        if error.kind() == io::ErrorKind::WouldBlock || error.raw_os_error() == Some(libc::ENOBUFS)
        {
            return Err(io::Error::new(
                io::ErrorKind::WouldBlock,
                "TUN write would block",
            ));
        }
        return Err(error);
    }
}

const _: () = {
    assert!(PACKET_CAPACITY >= TUN_MTU as usize);
    assert!(UDP_TX_SLOTS <= 4096);
    assert!(TUN_TX_SLOTS <= 4096);
};

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn datagram_mode_labels_identify_active_syscalls() {
        assert_eq!(UdpDatagramMode::Batch.label(), "recvmmsg/sendmmsg");
    }

    #[test]
    fn fatal_tun_write_errors_are_classified_narrowly() {
        for code in [libc::ENODEV, libc::ENXIO, libc::EBADF] {
            assert!(
                is_fatal_tun_write_error(&io::Error::from_raw_os_error(code)),
                "expected fatal for errno {code}"
            );
        }
        for code in [
            libc::EAGAIN,
            libc::ENOBUFS,
            libc::EMSGSIZE,
            libc::EINVAL,
            libc::EIO,
        ] {
            assert!(
                !is_fatal_tun_write_error(&io::Error::from_raw_os_error(code)),
                "expected non-fatal for errno {code}"
            );
        }
    }

    #[test]
    fn fair_share_allocation_and_control_slot_reservation() {
        let mut pool = PacketPool::new(10);
        let mut udp_tx: Vec<UdpTxSlot> = (0..UDP_TX_SLOTS).map(|_| UdpTxSlot::new()).collect();
        let mut tun_tx: Vec<TunTxSlot> = (0..TUN_TX_SLOTS).map(|_| TunTxSlot::new()).collect();
        let mut free_udp_tx: VecDeque<usize> = (0..UDP_TX_SLOTS).collect();
        let mut pending_udp_tx = VecDeque::new();
        let mut free_tun_tx = VecDeque::new();
        let mut pending_tun_tx = VecDeque::new();
        let mut counters = IoCounters::default();
        let mut tun_fatal = false;
        let mut urgent_udp_flush = false;
        let mut client_in_flight = [0u16; 256];

        let sink = PacketSink {
            tun_fd: -1,
            packet_pool: &mut pool,
            udp_tx: &mut udp_tx,
            tun_tx: &mut tun_tx,
            free_udp_tx: &mut free_udp_tx,
            pending_udp_tx: &mut pending_udp_tx,
            free_tun_tx: &mut free_tun_tx,
            pending_tun_tx: &mut pending_tun_tx,
            counters: &mut counters,
            tun_fatal: &mut tun_fatal,
            urgent_udp_flush: &mut urgent_udp_flush,
            client_in_flight: &mut client_in_flight,
        };

        assert!(sink.has_client_data_slot(1, 1, 36));
        assert!(sink.has_client_data_slot(2, 2, 36));

        sink.client_in_flight[1] = 864;
        assert!(!sink.has_client_data_slot(1, 1, 36));

        // A low stream count must still allow a full TUN receive batch in
        // flight; the old floor of 16 dropped the tail of every burst.
        sink.client_in_flight[3] = TUN_RX_DRAIN_BATCH as u16;
        assert!(sink.has_client_data_slot(3, 1, 1));

        sink.free_udp_tx.truncate(RESERVED_CONTROL_SLOTS);
        assert!(!sink.has_client_data_slot(2, 1, 36));
        assert_eq!(sink.free_udp_tx.len(), RESERVED_CONTROL_SLOTS);
    }

    #[test]
    fn packet_queues_and_socket_buffers_have_bounded_latency_budgets() {
        const TUN_MTU: u128 = 1_280;
        const BITS_PER_SECOND: u128 = 2_000_000;
        let queue_us = UDP_TX_SLOTS as u128 * TUN_MTU * 8 * 1_000_000 / BITS_PER_SECOND;
        let kernel_us = UDP_SEND_BUFFER_BYTES as u128 * 8 * 1_000_000 / BITS_PER_SECOND;
        assert!(queue_us <= 5_300_000);
        assert!(kernel_us <= 8_500_000);
    }

    #[tokio::test]
    async fn recvmsg_preserves_udp_payload_and_peer_address() {
        let receiver = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let sender = std::net::UdpSocket::bind("127.0.0.1:0").unwrap();
        let peer = sender.local_addr().unwrap();
        sender
            .send_to(b"csqtt-udp-receive", receiver.local_addr().unwrap())
            .unwrap();
        receiver.readable().await.unwrap();

        let mut packet = PacketBuffer::new();
        let mut address: libc::sockaddr_storage = unsafe { std::mem::zeroed() };
        let mut control = ControlBuffer::new();
        let mut iovec = libc::iovec {
            iov_base: packet.as_mut_ptr().cast(),
            iov_len: PACKET_CAPACITY,
        };
        let mut header: libc::msghdr = unsafe { std::mem::zeroed() };
        header.msg_name = (&mut address as *mut libc::sockaddr_storage).cast();
        header.msg_namelen = std::mem::size_of::<libc::sockaddr_storage>() as libc::socklen_t;
        header.msg_iov = &mut iovec;
        header.msg_iovlen = 1;
        header.msg_control = control.as_mut_ptr().cast();
        header.msg_controllen = control.len() as _;

        let received = receiver
            .try_io(Interest::READABLE, || {
                let value = unsafe {
                    libc::recvmsg(
                        receiver.as_raw_fd(),
                        &mut header,
                        libc::MSG_DONTWAIT as libc::c_int,
                    )
                };
                if value < 0 {
                    Err(io::Error::last_os_error())
                } else {
                    Ok(value as usize)
                }
            })
            .unwrap();
        assert!(packet.set_len(received));
        assert_eq!(packet.as_slice(), b"csqtt-udp-receive");
        assert_eq!(
            storage_to_socket_addr(&address, header.msg_namelen),
            Some(peer)
        );
    }

    #[tokio::test]
    #[ignore = "requires CAP_NET_ADMIN and a Linux TUN device"]
    async fn tun_sink_accepts_a_valid_ipv4_packet() {
        let interface = "csqttdiag0";
        let _ = std::process::Command::new("ip")
            .args(["link", "del", interface])
            .output();
        let mut io = TokioIo::new("127.0.0.1:0".parse().unwrap(), interface, "10.66.68.1")
            .await
            .unwrap();
        let mut packet = [0u8; 44];
        packet[0] = 0x45;
        packet[2..4].copy_from_slice(&44u16.to_be_bytes());
        packet[8] = 64;
        packet[9] = 17;
        packet[12..16].copy_from_slice(&[10, 66, 68, 2]);
        packet[16..20].copy_from_slice(&[10, 66, 68, 1]);
        packet[20..22].copy_from_slice(&47_214u16.to_be_bytes());
        packet[22..24].copy_from_slice(&47_231u16.to_be_bytes());
        packet[24..26].copy_from_slice(&24u16.to_be_bytes());
        let mut checksum = 0u32;
        for word in packet[..20].chunks_exact(2) {
            checksum = checksum.saturating_add(u16::from_be_bytes([word[0], word[1]]) as u32);
        }
        while checksum >> 16 != 0 {
            checksum = (checksum & 0xffff) + (checksum >> 16);
        }
        packet[10..12].copy_from_slice(&(!(checksum as u16)).to_be_bytes());
        assert!(io.with_sink(|sink| sink.write_tun(&packet)));
        drop(io);
        let _ = std::process::Command::new("ip")
            .args(["link", "del", interface])
            .output();
    }
}
