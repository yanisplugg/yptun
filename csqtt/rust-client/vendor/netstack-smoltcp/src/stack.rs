use std::{
    net::IpAddr,
    pin::Pin,
    task::{ready, Context, Poll},
};

use futures::{Sink, Stream};
use smoltcp::wire::IpProtocol;
use tokio::sync::mpsc::{channel, Receiver};
use tokio_util::sync::PollSender;
use tracing::{debug, trace};

use crate::{
    filter::{IpFilter, IpFilters},
    packet::{AnyIpPktFrame, IpPacket},
    runner::Runner,
    tcp::TcpListener,
    udp::UdpSocket,
};

pub struct StackBuilder {
    enable_udp: bool,
    enable_tcp: bool,
    enable_icmp: bool,
    stack_buffer_size: usize,
    udp_buffer_size: usize,
    tcp_buffer_size: usize,

    tcp_recv_buffer_size: u32,
    tcp_send_buffer_size: u32,
    mtu: usize,
    ip_filters: IpFilters<'static>,
}

impl Default for StackBuilder {
    fn default() -> Self {
        Self {
            enable_udp: false,
            enable_tcp: false,
            enable_icmp: false,
            stack_buffer_size: 1024,
            udp_buffer_size: 512,
            tcp_buffer_size: 512,

            tcp_recv_buffer_size: 0x3FFF * 20,
            tcp_send_buffer_size: 0x3FFF * 20,
            mtu: 1504,
            ip_filters: IpFilters::with_non_broadcast(),
        }
    }
}

#[allow(unused)]
impl StackBuilder {
    pub fn enable_udp(mut self, enable: bool) -> Self {
        self.enable_udp = enable;
        self
    }

    pub fn enable_tcp(mut self, enable: bool) -> Self {
        self.enable_tcp = enable;
        self
    }

    pub fn enable_icmp(mut self, enable: bool) -> Self {
        self.enable_icmp = enable;
        self
    }

    pub fn stack_buffer_size(mut self, size: usize) -> Self {
        self.stack_buffer_size = size;
        self
    }

    pub fn udp_buffer_size(mut self, size: usize) -> Self {
        self.udp_buffer_size = size;
        self
    }

    pub fn tcp_buffer_size(mut self, size: usize) -> Self {
        self.tcp_buffer_size = size;
        self
    }

    pub fn tcp_recv_buffer_size(mut self, size: u32) -> Self {
        self.tcp_recv_buffer_size = size;
        self
    }

    pub fn tcp_send_buffer_size(mut self, size: u32) -> Self {
        self.tcp_send_buffer_size = size;
        self
    }

    pub fn set_ip_filters(mut self, filters: IpFilters<'static>) -> Self {
        self.ip_filters = filters;
        self
    }

    pub fn add_ip_filter(mut self, filter: IpFilter<'static>) -> Self {
        self.ip_filters.add(filter);
        self
    }

    pub fn add_ip_filter_fn<F>(mut self, filter: F) -> Self
    where
        F: Fn(&IpAddr, &IpAddr) -> bool + Send + Sync + 'static,
    {
        self.ip_filters.add_fn(filter);
        self
    }

    pub fn mtu(mut self, mtu: usize) -> Self {
        self.mtu = mtu;
        self
    }

    #[allow(clippy::type_complexity)]
    pub fn build(
        self,
    ) -> std::io::Result<(
        Stack,
        Option<Runner>,
        Option<UdpSocket>,
        Option<TcpListener>,
    )> {
        let (stack_tx, stack_rx) = channel(self.stack_buffer_size);

        let (udp_tx, udp_rx) = if self.enable_udp {
            let (udp_tx, udp_rx) = channel(self.udp_buffer_size);
            (Some(PollSender::new(udp_tx)), Some(udp_rx))
        } else {
            (None, None)
        };

        let (tcp_tx, tcp_rx) = if self.enable_tcp {
            let (tcp_tx, tcp_rx) = channel(self.tcp_buffer_size);
            (Some(PollSender::new(tcp_tx)), Some(tcp_rx))
        } else {
            (None, None)
        };

        if self.enable_icmp && !self.enable_tcp {
            use std::io::{Error, ErrorKind::InvalidInput};
            return Err(Error::new(InvalidInput, "ICMP requires TCP"));
        }
        let icmp_tx = if self.enable_icmp {
            tcp_tx.clone()
        } else {
            None
        };

        let udp_socket = udp_rx.map(|udp_rx| UdpSocket::new(udp_rx, stack_tx.clone()));

        let (tcp_runner, tcp_listener) = if let Some(tcp_rx) = tcp_rx {
            let (tcp_runner, tcp_listener) = TcpListener::new(
                tcp_rx,
                stack_tx,
                self.mtu,
                self.tcp_recv_buffer_size,
                self.tcp_send_buffer_size,
            )?;
            (Some(tcp_runner), Some(tcp_listener))
        } else {
            (None, None)
        };

        let stack = Stack {
            ip_filters: self.ip_filters,
            stack_rx,
            sink_buf: None,
            udp_tx,
            tcp_tx,
            icmp_tx,
        };

        Ok((stack, tcp_runner, udp_socket, tcp_listener))
    }
}

pub struct Stack {
    ip_filters: IpFilters<'static>,
    sink_buf: Option<(AnyIpPktFrame, IpProtocol)>,
    udp_tx: Option<PollSender<AnyIpPktFrame>>,
    tcp_tx: Option<PollSender<AnyIpPktFrame>>,
    icmp_tx: Option<PollSender<AnyIpPktFrame>>,
    stack_rx: Receiver<AnyIpPktFrame>,
}

impl Stack {
    fn poll_send(&mut self, cx: &mut Context<'_>) -> Poll<Result<(), std::io::Error>> {
        let (item, proto) = match self.sink_buf.take() {
            Some(val) => val,
            None => return Poll::Ready(Ok(())),
        };

        let tx = match proto {
            IpProtocol::Tcp => self.tcp_tx.as_mut(),
            IpProtocol::Udp => self.udp_tx.as_mut(),
            IpProtocol::Icmp | IpProtocol::Icmpv6 => self.icmp_tx.as_mut(),
            _ => unreachable!(),
        };

        let Some(tx) = tx else {
            return Poll::Ready(Ok(()));
        };

        match tx.poll_reserve(cx) {
            Poll::Pending => {
                self.sink_buf = Some((item, proto));
                Poll::Pending
            }
            Poll::Ready(Err(_)) => Poll::Ready(Err(channel_closed_err("channel is closed"))),
            Poll::Ready(Ok(_)) => match tx.send_item(item) {
                Ok(()) => Poll::Ready(Ok(())),
                Err(_) => Poll::Ready(Err(channel_closed_err("channel is closed"))),
            },
        }
    }
}

impl Stream for Stack {
    type Item = std::io::Result<AnyIpPktFrame>;

    fn poll_next(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        match self.stack_rx.poll_recv(cx) {
            Poll::Ready(Some(pkt)) => Poll::Ready(Some(Ok(pkt))),
            Poll::Ready(None) => Poll::Ready(None),
            Poll::Pending => Poll::Pending,
        }
    }
}

impl Sink<AnyIpPktFrame> for Stack {
    type Error = std::io::Error;

    fn poll_ready(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {

        if self.sink_buf.is_some() {
            ready!(self.poll_send(cx))?;
        }
        Poll::Ready(Ok(()))
    }

    fn start_send(mut self: Pin<&mut Self>, item: AnyIpPktFrame) -> Result<(), Self::Error> {
        if item.is_empty() {
            return Ok(());
        }

        use std::io::{Error, ErrorKind::InvalidInput};
        let packet = IpPacket::new_checked(item.as_slice())
            .map_err(|err| Error::new(InvalidInput, format!("invalid IP packet: {err}")))?;

        let src_ip = packet.src_addr();
        let dst_ip = packet.dst_addr();

        let addr_allowed = self.ip_filters.is_allowed(&src_ip, &dst_ip);
        if !addr_allowed {
            trace!("IP packet {src_ip} -> {dst_ip} (allowed? {addr_allowed}) throwing away",);
            return Ok(());
        }

        let protocol = packet.protocol();
        if matches!(
            protocol,
            IpProtocol::Tcp | IpProtocol::Udp | IpProtocol::Icmp | IpProtocol::Icmpv6
        ) {
            self.sink_buf.replace((item, protocol));
        } else {
            debug!("tun IP packet ignored (protocol: {:?})", protocol);
        }

        Ok(())
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        self.poll_send(cx)
    }

    fn poll_close(
        mut self: Pin<&mut Self>,
        _cx: &mut Context<'_>,
    ) -> Poll<Result<(), Self::Error>> {
        self.stack_rx.close();
        Poll::Ready(Ok(()))
    }
}

fn channel_closed_err<E>(err: E) -> std::io::Error
where
    E: Into<Box<dyn std::error::Error + Send + Sync>>,
{
    std::io::Error::new(std::io::ErrorKind::BrokenPipe, err)
}

#[cfg(test)]
mod tcp_window_tests {
    use super::*;

    #[test]
    fn tcp_window_defaults_to_historical_and_threads_overrides() {
        let d = StackBuilder::default();
        assert_eq!(d.tcp_recv_buffer_size, 0x3FFF * 20);
        assert_eq!(d.tcp_send_buffer_size, 0x3FFF * 20);

        let b = StackBuilder::default()
            .tcp_recv_buffer_size(1 << 20)
            .tcp_send_buffer_size(2 << 20);
        assert_eq!(b.tcp_recv_buffer_size, 1 << 20);
        assert_eq!(b.tcp_send_buffer_size, 2 << 20);

        assert_eq!(b.tcp_buffer_size, 512);
    }
}
