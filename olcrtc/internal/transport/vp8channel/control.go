package vp8channel

// Control-plane implementation: transport.ControlPlane for the single-peer
// (client) side and transport.PeerControlPlane for the multi-peer (server)
// side. Control traffic runs on its own KCP session so handshake and liveness
// frames never queue behind bulk data.

// ControlSend implements transport.ControlPlane.
// It sends data through the isolated control-plane KCP session.
func (p *streamTransport) ControlSend(data []byte) error {
	return p.sendVia(p.control.get(), data)
}

// ControlSendTo sends data on the per-peer control KCP for peerID.
// Implements transport.PeerControlPlane.
func (p *streamTransport) ControlSendTo(peerID string, data []byte) error {
	return p.sendToPeer(peerID, data, p.peerControlFor)
}

// SetControlOnData implements transport.ControlPlane.
// The callback is stored and picked up by the running control KCP read loop
// on the next frame, so it can be set before or after Connect.
func (p *streamTransport) SetControlOnData(cb func([]byte)) {
	p.onControlData.Store(&cb)
}

// SetControlOnPeerData registers the callback for per-peer control frames.
// Implements transport.PeerControlPlane.
func (p *streamTransport) SetControlOnPeerData(cb func(peerID string, data []byte)) {
	p.onPeerControlData.Store(&cb)
}

// ControlCanSend reports whether the control-plane is ready to send.
// Unlike CanSend, it does not require the publisher PC to be ready - control
// frames (handshake welcome, ping/pong) must go through even before the
// publisher negotiation completes.
func (p *streamTransport) ControlCanSend() bool {
	return p.ready(p.control.get(), p.stream.SubscriberCanSend)
}

// ControlPeerCanSend reports whether the per-peer control KCP for peerID is
// ready. It never creates a session: an unknown peer is simply not ready.
// Implements transport.PeerControlPlane.
func (p *streamTransport) ControlPeerCanSend(peerID string) bool {
	epoch, err := parsePeerID(peerID)
	if err != nil {
		return false
	}

	sess := p.peers.get(epoch)
	if sess == nil {
		return false
	}

	return p.ready(sess.controlRuntime(), p.stream.SubscriberCanSend)
}

// deliverControlData dispatches a control message received on the singleton
// control KCP. It reads the callback on every frame so SetControlOnData can
// replace it without restarting the session.
func (p *streamTransport) deliverControlData(data []byte) {
	if cb := p.onControlData.Load(); cb != nil && *cb != nil {
		(*cb)(data)
	}
}

// deliverPeerControlData dispatches a control message received on a per-peer
// control KCP.
func (p *streamTransport) deliverPeerControlData(peerID string, data []byte) {
	if cb := p.onPeerControlData.Load(); cb != nil && *cb != nil {
		(*cb)(peerID, data)
	}
}

// handleControlFrame routes a control-plane VP8 frame. In multi-peer mode
// (server) each data epoch gets its own per-peer control KCP created on demand.
// In single-peer mode (client) the shared singleton control KCP is used.
// src carries the peer's control epoch (high bit set), dst is our epoch (or 0
// for broadcast). Loopback echoes of our own frames are discarded by the
// caller (handleIncomingFrame) via the src == localControlEpoch check.
func (p *streamTransport) handleControlFrame(src, dst uint32, kcpPayload []byte) {
	if len(kcpPayload) == 0 {
		return // control keepalive, nothing to deliver
	}

	// Multi-peer mode: route by data epoch (src &^ controlEpochFlag).
	if p.serverMode {
		deliverKCPPayload(p.peerControlFor(src&^controlEpochFlag), kcpPayload)
		return
	}

	// Single-peer mode (client): only accept control frames addressed
	// specifically to our control epoch. Other clients sharing the same SFU
	// room broadcast their handshake control frames with dst==0; the SFU
	// forwards those to us too. Without this filter those foreign bytes would
	// be fed into our singleton control KCP (which shares the static
	// kcpConvID) and corrupt our own handshake/liveness stream, so neither
	// client could complete its handshake (issue #95 multi-client). The
	// server always addresses a client directly (dst==clientControlEpoch),
	// so a non-targeted control frame is never legitimately ours.
	if dst != p.controlEpochValue() {
		return
	}

	deliverKCPPayload(p.control.get(), kcpPayload)
}
