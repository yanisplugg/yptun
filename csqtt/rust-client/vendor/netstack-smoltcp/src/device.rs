use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc,
};

use smoltcp::{
    phy::{Device, DeviceCapabilities, Medium, RxToken, TxToken},
    time::Instant,
};
use tokio::sync::mpsc::{channel, Permit, Receiver, Sender};

use crate::packet::AnyIpPktFrame;

pub(super) struct VirtualDevice {
    in_buf_avail: Arc<AtomicBool>,
    in_buf: Receiver<Vec<u8>>,
    out_buf: Sender<AnyIpPktFrame>,
    mtu: usize,
}

impl VirtualDevice {
    pub(super) fn new(
        iface_egress_tx: Sender<AnyIpPktFrame>,
        mtu: usize,
    ) -> (Self, Sender<Vec<u8>>, Arc<AtomicBool>) {
        let iface_ingress_tx_avail = Arc::new(AtomicBool::new(false));
        let (iface_ingress_tx, iface_ingress_rx) = channel(2048);
        (
            Self {
                in_buf_avail: iface_ingress_tx_avail.clone(),
                in_buf: iface_ingress_rx,
                out_buf: iface_egress_tx,
                mtu,
            },
            iface_ingress_tx,
            iface_ingress_tx_avail,
        )
    }
}

impl Device for VirtualDevice {
    type RxToken<'a> = VirtualRxToken;
    type TxToken<'a> = VirtualTxToken<'a>;

    fn receive(&mut self, _timestamp: Instant) -> Option<(Self::RxToken<'_>, Self::TxToken<'_>)> {
        let Ok(permit) = self.out_buf.try_reserve() else {
            return None;
        };

        match self.in_buf.try_recv() {
            Ok(buffer) => Some((Self::RxToken { buffer }, Self::TxToken { permit })),
            Err(_) => {
                self.in_buf_avail.store(false, Ordering::Release);
                None
            }
        }
    }

    fn transmit(&mut self, _timestamp: Instant) -> Option<Self::TxToken<'_>> {
        match self.out_buf.try_reserve() {
            Ok(permit) => Some(Self::TxToken { permit }),
            Err(_) => None,
        }
    }

    fn capabilities(&self) -> DeviceCapabilities {
        let mut capabilities = DeviceCapabilities::default();
        capabilities.medium = Medium::Ip;
        capabilities.max_transmission_unit = self.mtu;
        capabilities
    }
}

pub(super) struct VirtualRxToken {
    buffer: Vec<u8>,
}

impl RxToken for VirtualRxToken {
    fn consume<R, F>(self, f: F) -> R
    where
        F: FnOnce(&[u8]) -> R,
    {
        f(&self.buffer[..])
    }
}

pub(super) struct VirtualTxToken<'a> {
    permit: Permit<'a, Vec<u8>>,
}

impl<'a> TxToken for VirtualTxToken<'a> {
    fn consume<R, F>(self, len: usize, f: F) -> R
    where
        F: FnOnce(&mut [u8]) -> R,
    {
        let mut buffer = vec![0u8; len];
        let result = f(&mut buffer);
        self.permit.send(buffer);
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    use tokio::sync::mpsc::channel;

    #[test]
    fn receive_keeps_in_buf_packet_when_out_buf_is_full() {
        let (out_tx, mut out_rx) = channel::<AnyIpPktFrame>(1);
        let (mut device, ingress_tx, in_buf_avail)
            = VirtualDevice::new(out_tx.clone(), 1500);

        out_tx.try_send(vec![0u8; 4]).unwrap();

        ingress_tx.try_send(b"payload".to_vec()).unwrap();
        in_buf_avail.store(true, Ordering::Release);

        assert!(device.receive(Instant::from_millis(0)).is_none());

        assert!(
            in_buf_avail.load(Ordering::Acquire),
            "in_buf_avail must stay true: the in_buf packet is still pending, only out_buf capacity was the blocker"
        );

        out_rx.try_recv().unwrap();

        let (rx_token, _tx_token) = device
            .receive(Instant::from_millis(1))
            .expect("in_buf packet must not have been dropped while out_buf was full");
        assert_eq!(rx_token.consume(|buf| buf.to_vec()), b"payload");
    }
}
