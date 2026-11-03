use std::{
    collections::HashMap,
    net::SocketAddr,
    pin::Pin,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    task::{Context, Poll, Waker},
};

use futures::Stream;
use smoltcp::{
    iface::{Config as InterfaceConfig, Interface, SocketHandle, SocketSet},
    phy::Device,
    socket::tcp::{Socket as TcpSocket, SocketBuffer as TcpSocketBuffer, State as TcpState},
    storage::RingBuffer,
    time::{Duration, Instant},
    wire::{HardwareAddress, IpAddress, IpCidr, IpProtocol, Ipv4Address, Ipv6Address, TcpPacket},
};
use spin::Mutex as SpinMutex;
use tokio::{
    io::{AsyncRead, AsyncWrite, ReadBuf},
    sync::{
        mpsc::{channel, Receiver, Sender},
        Notify,
    },
};
use tracing::{error, trace};

use crate::{
    device::VirtualDevice,
    packet::{AnyIpPktFrame, IpPacket},
    Runner,
};

#[derive(Debug, Clone, Copy, Eq, PartialEq)]
enum TcpSocketState {
    Normal,
    Close,
    Closing,
    Closed,
}

struct TcpSocketControl {
    send_buffer: RingBuffer<'static, u8>,
    send_waker: Option<Waker>,
    recv_buffer: RingBuffer<'static, u8>,
    recv_waker: Option<Waker>,
    recv_state: TcpSocketState,
    send_state: TcpSocketState,
}

struct TcpSocketCreation {
    control: SharedControl,
    socket: TcpSocket<'static>,
}

type SharedNotify = Arc<Notify>;
type SharedControl = Arc<SpinMutex<TcpSocketControl>>;

struct TcpListenerRunner;

impl TcpListenerRunner {
    #[allow(clippy::too_many_arguments)]
    fn create(
        device: VirtualDevice,
        iface: Interface,
        iface_ingress_tx: Sender<Vec<u8>>,
        iface_ingress_tx_avail: Arc<AtomicBool>,
        tcp_rx: Receiver<AnyIpPktFrame>,
        stream_tx: Sender<TcpStream>,
        sockets: HashMap<SocketHandle, SharedControl>,
        tcp_recv_buffer_size: u32,
        tcp_send_buffer_size: u32,
    ) -> Runner {
        Runner::new(async move {
            let notify = Arc::new(Notify::new());
            let (socket_tx, socket_rx) = channel::<TcpSocketCreation>(256);
            let n1 = notify.clone();
            let n2 = notify.clone();
            let mut h1 = tokio::spawn(Self::handle_packet(
                n1,
                iface_ingress_tx,
                iface_ingress_tx_avail.clone(),
                tcp_rx,
                stream_tx,
                socket_tx,
                tcp_recv_buffer_size,
                tcp_send_buffer_size,
            ));
            let mut h2 = tokio::spawn(Self::handle_socket(
                n2,
                device,
                iface,
                iface_ingress_tx_avail,
                sockets,
                socket_rx,
            ));
            tokio::select! {
                r1 = &mut h1 => {
                    h2.abort();
                    if let Ok(Err(e)) = r1 { return Err(e); }
                }
                r2 = &mut h2 => {
                    h1.abort();
                    if let Ok(Err(e)) = r2 { return Err(e); }
                }
            }
            trace!("VirtDevice::poll thread exited");
            Ok(())
        })
    }

    #[allow(clippy::too_many_arguments)]
    async fn handle_packet(
        notify: SharedNotify,
        iface_ingress_tx: Sender<Vec<u8>>,
        iface_ingress_tx_avail: Arc<AtomicBool>,
        mut tcp_rx: Receiver<AnyIpPktFrame>,
        stream_tx: Sender<TcpStream>,
        socket_tx: Sender<TcpSocketCreation>,
        tcp_recv_buffer_size: u32,
        tcp_send_buffer_size: u32,
    ) -> std::io::Result<()> {
        while let Some(frame) = tcp_rx.recv().await {
            let packet = match IpPacket::new_checked(frame.as_slice()) {
                Ok(p) => p,
                Err(err) => {
                    error!("invalid TCP IP packet: {:?}", err);
                    continue;
                }
            };

            if matches!(packet.protocol(), IpProtocol::Icmp | IpProtocol::Icmpv6) {
                if let Ok(permit) = iface_ingress_tx.reserve().await {
                    permit.send(frame);
                    iface_ingress_tx_avail.store(true, Ordering::Release);
                    notify.notify_one();
                }
                continue;
            }

            let src_ip = packet.src_addr();
            let dst_ip = packet.dst_addr();
            let payload = packet.payload();

            let packet = match TcpPacket::new_checked(payload) {
                Ok(p) => p,
                Err(err) => {
                    error!("invalid TCP err: {err}, src_ip: {src_ip}, dst_ip: {dst_ip}, payload: {payload:?}");
                    continue;
                }
            };
            let src_port = packet.src_port();
            let dst_port = packet.dst_port();

            let src_addr = SocketAddr::new(src_ip, src_port);
            let dst_addr = SocketAddr::new(dst_ip, dst_port);

            if packet.syn() && !packet.ack() {
                let mut socket = TcpSocket::new(
                    TcpSocketBuffer::new(vec![0u8; tcp_recv_buffer_size as usize]),
                    TcpSocketBuffer::new(vec![0u8; tcp_send_buffer_size as usize]),
                );
                socket.set_keep_alive(Some(Duration::from_secs(28)));
                socket.set_timeout(Some(Duration::from_secs(120)));

                if let Err(err) = socket.listen(dst_addr) {
                    error!("listen error: {:?}", err);
                    continue;
                }

                trace!("created TCP connection for {} <-> {}", src_addr, dst_addr);

                let control = Arc::new(SpinMutex::new(TcpSocketControl {
                    send_buffer: RingBuffer::new(vec![0u8; tcp_send_buffer_size as usize]),
                    send_waker: None,
                    recv_buffer: RingBuffer::new(vec![0u8; tcp_recv_buffer_size as usize]),
                    recv_waker: None,
                    recv_state: TcpSocketState::Normal,
                    send_state: TcpSocketState::Normal,
                }));

                let _ = stream_tx.send(TcpStream {
                    src_addr,
                    dst_addr,
                    notify: notify.clone(),
                    control: control.clone(),
                }).await;

                let _ = socket_tx.send(TcpSocketCreation { control, socket }).await;
            }

            if let Ok(permit) = iface_ingress_tx.reserve().await {
                permit.send(frame);
                iface_ingress_tx_avail.store(true, Ordering::Release);
                notify.notify_one();
            }
        }
        Ok(())
    }

    async fn handle_socket(
        notify: SharedNotify,
        mut device: VirtualDevice,
        mut iface: Interface,
        iface_ingress_tx_avail: Arc<AtomicBool>,
        mut sockets: HashMap<SocketHandle, SharedControl>,
        mut socket_rx: Receiver<TcpSocketCreation>,
    ) -> std::io::Result<()> {
        let mut socket_set = SocketSet::new(vec![]);
        loop {
            while let Ok(TcpSocketCreation { control, socket }) = socket_rx.try_recv() {
                let handle = socket_set.add(socket);
                sockets.insert(handle, control);
            }

            let before_poll = Instant::now();
            let updated_sockets = iface.poll(before_poll, &mut device, &mut socket_set);
            if matches!(
                updated_sockets,
                smoltcp::iface::PollResult::SocketStateChanged
            ) {
                trace!("VirtDevice::poll costed {}", Instant::now() - before_poll);
            }

            let mut sockets_to_remove = Vec::new();

            for (socket_handle, control) in sockets.iter() {
                let socket_handle = *socket_handle;
                let socket = socket_set.get_mut::<TcpSocket>(socket_handle);
                let mut control = control.lock();

                if socket.state() == TcpState::Closed
                    || (matches!(control.send_state, TcpSocketState::Closed)
                        && matches!(control.recv_state, TcpSocketState::Closed))
                {
                    if socket.state() != TcpState::Closed {
                        socket.abort();
                    }
                    sockets_to_remove.push(socket_handle);

                    control.send_state = TcpSocketState::Closed;
                    control.recv_state = TcpSocketState::Closed;

                    if let Some(waker) = control.send_waker.take() {
                        waker.wake();
                    }
                    if let Some(waker) = control.recv_waker.take() {
                        waker.wake();
                    }

                    trace!("closed/aborted TCP connection");
                    continue;
                }

                if matches!(control.send_state, TcpSocketState::Close)
                    && control.send_buffer.is_empty()
                {
                    trace!("closing TCP Write Half, {:?}", socket.state());
                    socket.close();
                    control.send_state = TcpSocketState::Closing;
                }

                let mut wake_receiver = false;
                while socket.can_recv() && !control.recv_buffer.is_full() {
                    let result = socket.recv(|buffer| {
                        let n = control.recv_buffer.enqueue_slice(buffer);
                        (n, ())
                    });

                    match result {
                        Ok(..) => wake_receiver = true,
                        Err(err) => {
                            error!("socket recv error: {:?}, {:?}", err, socket.state());
                            socket.abort();
                            if matches!(control.recv_state, TcpSocketState::Normal) {
                                control.recv_state = TcpSocketState::Closed;
                            }
                            wake_receiver = true;
                            break;
                        }
                    }
                }

                let states = [
                    TcpState::Listen,
                    TcpState::SynReceived,
                    TcpState::Established,
                    TcpState::FinWait1,
                    TcpState::FinWait2,
                ];
                if matches!(control.recv_state, TcpSocketState::Normal)
                    && !socket.may_recv()
                    && !states.contains(&socket.state())
                {
                    trace!("closed TCP Read Half, {:?}", socket.state());
                    control.recv_state = TcpSocketState::Closed;
                    wake_receiver = true;
                }

                if wake_receiver && control.recv_waker.is_some() {
                    if let Some(waker) = control.recv_waker.take() {
                        waker.wake();
                    }
                }

                let mut wake_sender = false;
                while socket.can_send() && !control.send_buffer.is_empty() {
                    let result = socket.send(|buffer| {
                        let n = control.send_buffer.dequeue_slice(buffer);
                        (n, ())
                    });

                    match result {
                        Ok(..) => wake_sender = true,
                        Err(err) => {
                            error!("socket send error: {:?}, {:?}", err, socket.state());
                            socket.abort();
                            if matches!(control.send_state, TcpSocketState::Normal) {
                                control.send_state = TcpSocketState::Closed;
                            }
                            wake_sender = true;
                            break;
                        }
                    }
                }

                if wake_sender && control.send_waker.is_some() {
                    if let Some(waker) = control.send_waker.take() {
                        waker.wake();
                    }
                }
            }

            for socket_handle in sockets_to_remove {
                sockets.remove(&socket_handle);
                socket_set.remove(socket_handle);
            }

            if iface_ingress_tx_avail.swap(false, Ordering::AcqRel) {
                tokio::task::yield_now().await;
            } else {
                let next_duration = iface
                    .poll_delay(before_poll, &socket_set)
                    .unwrap_or(Duration::from_millis(5));
                if next_duration != Duration::ZERO {
                    let _ = tokio::time::timeout(
                        tokio::time::Duration::from(next_duration.min(Duration::from_millis(20))),
                        notify.notified(),
                    )
                    .await;
                } else {
                    tokio::task::yield_now().await;
                }
            }
        }
    }
}

pub struct TcpListener {
    stream_rx: Receiver<TcpStream>,
}

impl TcpListener {
    pub(super) fn new(
        tcp_rx: Receiver<AnyIpPktFrame>,
        stack_tx: Sender<AnyIpPktFrame>,
        mtu: usize,
        tcp_recv_buffer_size: u32,
        tcp_send_buffer_size: u32,
    ) -> std::io::Result<(Runner, Self)> {
        let (mut device, iface_ingress_tx, iface_ingress_tx_avail) =
            VirtualDevice::new(stack_tx, mtu);
        let iface = Self::create_interface(&mut device)?;

        let (stream_tx, stream_rx) = channel(256);

        let runner = TcpListenerRunner::create(
            device,
            iface,
            iface_ingress_tx,
            iface_ingress_tx_avail,
            tcp_rx,
            stream_tx,
            HashMap::new(),
            tcp_recv_buffer_size,
            tcp_send_buffer_size,
        );

        Ok((runner, Self { stream_rx }))
    }

    fn create_interface<D>(device: &mut D) -> std::io::Result<Interface>
    where
        D: Device + ?Sized,
    {
        let mut iface_config = InterfaceConfig::new(HardwareAddress::Ip);
        iface_config.random_seed = rand::random();
        let mut iface = Interface::new(iface_config, device, Instant::now());
        iface.update_ip_addrs(|ip_addrs| {
            ip_addrs
                .push(IpCidr::new(IpAddress::v4(0, 0, 0, 1), 0))
                .expect("iface IPv4");
            ip_addrs
                .push(IpCidr::new(IpAddress::v6(0, 0, 0, 0, 0, 0, 0, 1), 0))
                .expect("iface IPv6");
        });
        iface
            .routes_mut()
            .add_default_ipv4_route(Ipv4Address::new(0, 0, 0, 1))
            .map_err(|e| std::io::Error::new(std::io::ErrorKind::AddrNotAvailable, e))?;
        iface
            .routes_mut()
            .add_default_ipv6_route(Ipv6Address::new(0, 0, 0, 0, 0, 0, 0, 1))
            .map_err(|e| std::io::Error::new(std::io::ErrorKind::AddrNotAvailable, e))?;
        iface.set_any_ip(true);
        Ok(iface)
    }
}

impl Stream for TcpListener {
    type Item = (TcpStream, SocketAddr, SocketAddr);

    fn poll_next(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<Option<Self::Item>> {
        self.stream_rx.poll_recv(cx).map(|stream| {
            stream.map(|stream| {
                let local_addr = *stream.local_addr();
                let remote_addr: SocketAddr = *stream.remote_addr();
                (stream, local_addr, remote_addr)
            })
        })
    }
}

pub struct TcpStream {
    src_addr: SocketAddr,
    dst_addr: SocketAddr,
    notify: SharedNotify,
    control: SharedControl,
}

impl Drop for TcpStream {
    fn drop(&mut self) {
        let mut control = self.control.lock();
        control.recv_state = TcpSocketState::Closed;
        control.send_state = TcpSocketState::Closed;
        self.notify.notify_one();
    }
}

impl TcpStream {
    pub fn local_addr(&self) -> &SocketAddr {
        &self.src_addr
    }

    pub fn remote_addr(&self) -> &SocketAddr {
        &self.dst_addr
    }
}

impl AsyncRead for TcpStream {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<std::io::Result<()>> {
        let mut control = self.control.lock();

        if control.recv_buffer.is_empty() {
            if matches!(control.recv_state, TcpSocketState::Closed) {
                return Ok(()).into();
            }

            if let Some(old_waker) = control.recv_waker.replace(cx.waker().clone()) {
                if !old_waker.will_wake(cx.waker()) {
                    old_waker.wake();
                }
            }

            return Poll::Pending;
        }

        let recv_buf = buf.initialize_unfilled();
        let n = control.recv_buffer.dequeue_slice(recv_buf);
        buf.advance(n);

        if n > 0 {
            self.notify.notify_one();
        }

        Ok(()).into()
    }
}

impl AsyncWrite for TcpStream {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<std::io::Result<usize>> {
        let mut control = self.control.lock();

        if !matches!(control.send_state, TcpSocketState::Normal) {
            return Err(std::io::ErrorKind::BrokenPipe.into()).into();
        }

        if control.send_buffer.is_full() {
            if let Some(old_waker) = control.send_waker.replace(cx.waker().clone()) {
                if !old_waker.will_wake(cx.waker()) {
                    old_waker.wake();
                }
            }

            return Poll::Pending;
        }

        let n = control.send_buffer.enqueue_slice(buf);

        if n > 0 {
            self.notify.notify_one();
        }

        Ok(n).into()
    }

    fn poll_flush(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        Ok(()).into()
    }

    fn poll_shutdown(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        let mut control = self.control.lock();

        if matches!(control.send_state, TcpSocketState::Normal) {
            control.send_state = TcpSocketState::Close;
            self.notify.notify_one();
        }

        Poll::Ready(Ok(()))
    }
}
