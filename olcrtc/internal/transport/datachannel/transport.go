// Package datachannel provides a transport backed by a provider's data channel.
package datachannel

import (
	"context"
	"fmt"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
	"github.com/openlibrecommunity/olcrtc/internal/transport/common"
)

const defaultMaxPayloadSize = 12 * 1024

// writeInterval is the least time between two small messages of one tunnel
// (see transport.Features.WriteInterval). The Jitsi bridge passes every
// message an endpoint sends through a queue of 50 and drops the oldest when
// forwarding falls behind, which it does for a moment whenever the receiving
// endpoint is busy; one tiny message per smux frame turned a burst of new
// streams into hundreds of messages and lost the acks of dozens of them
// (olcrtc#11). At one message per 5 ms the bridge can stall for a quarter of
// a second before it drops anything.
//
// ai-generated: this constant.
const writeInterval = 5 * time.Millisecond

// PeerResetter is satisfied so upper layers can clear the peer binding.
var _ transport.PeerResetter = (*streamTransport)(nil)

// PeerIdentity is satisfied when the underlying engine exposes routing epochs.
var _ transport.PeerIdentity = (*streamTransport)(nil)

// PeerLifecycle is satisfied so the server's peer retirements reach engines
// that keep send state per peer.
//
// ai-generated: this assertion (olcrtc#49).
var _ transport.PeerLifecycle = (*streamTransport)(nil)

type streamTransport struct {
	common.Lifecycle

	session engine.Session
	shaper  *transport.Shaper
}

// New creates a datachannel transport backed by a provider engine.
func New(ctx context.Context, cfg transport.Config) (transport.Transport, error) {
	sess, err := cfg.OpenEngine(ctx)
	if err != nil {
		return nil, err
	}

	tr := &streamTransport{Lifecycle: common.NewLifecycle(sess), session: sess}
	tr.shaper = transport.NewShaper(cfg.Traffic, tr.Features())

	return tr, nil
}

// Connect starts the transport connection.
func (p *streamTransport) Connect(ctx context.Context) error {
	if err := p.session.Connect(ctx); err != nil {
		return fmt.Errorf("session connect: %w", err)
	}
	return nil
}

// Send transmits data through the transport.
func (p *streamTransport) Send(data []byte) error {
	return p.shaper.Send(p.send, data)
}

func (p *streamTransport) send(data []byte) error {
	if err := p.session.Send(data); err != nil {
		return fmt.Errorf("session send: %w", err)
	}
	return nil
}

// SendTo transmits data to a specific remote endpoint when the engine supports it.
func (p *streamTransport) SendTo(peerID string, data []byte) error {
	return p.shaper.Send(func(payload []byte) error {
		return p.sendTo(peerID, payload)
	}, data)
}

func (p *streamTransport) sendTo(peerID string, data []byte) error {
	peer, ok := p.session.(engine.PeerSession)
	if !ok {
		return p.send(data)
	}
	if err := peer.SendTo(peerID, data); err != nil {
		return fmt.Errorf("session send to peer: %w", err)
	}
	return nil
}

// SendDatagram forwards an unordered, lossy payload to the engine's datagram
// lane when it has one.
func (p *streamTransport) SendDatagram(data []byte) error {
	dg, ok := p.session.(engine.DatagramSession)
	if !ok {
		return transport.ErrDatagramUnsupported
	}
	if err := dg.SendDatagram(data); err != nil {
		return fmt.Errorf("session send datagram: %w", err)
	}
	return nil
}

// SendDatagramTo forwards a lossy payload to a specific peer when the engine
// can address one, and to the room otherwise.
func (p *streamTransport) SendDatagramTo(peerID string, data []byte) error {
	peer, ok := p.session.(engine.PeerDatagramSession)
	if !ok {
		return p.SendDatagram(data)
	}
	if err := peer.SendDatagramTo(peerID, data); err != nil {
		return fmt.Errorf("session send datagram to peer: %w", err)
	}
	return nil
}

// DatagramCanSend reports whether the engine's datagram lane is writable.
func (p *streamTransport) DatagramCanSend() bool {
	dg, ok := p.session.(engine.DatagramSession)
	return ok && dg.DatagramCanSend()
}

// PeerSeen implements transport.PeerObserver when the engine can answer it.
func (p *streamTransport) PeerSeen() bool {
	observer, ok := p.session.(transport.PeerObserver)
	return ok && observer.PeerSeen()
}

// SupportsPeerRouting reports whether this transport can address individual peers.
func (p *streamTransport) SupportsPeerRouting() bool {
	_, ok := p.session.(engine.PeerSession)
	return ok
}

// LocalPeerID returns the underlying engine's local routing identity, if any.
func (p *streamTransport) LocalPeerID() string {
	identity, ok := p.session.(engine.PeerIdentity)
	if !ok {
		return ""
	}
	return identity.LocalPeerID()
}

// ConfirmPeer authenticates the underlying engine's remote routing identity.
func (p *streamTransport) ConfirmPeer(peerID string) error {
	identity, ok := p.session.(engine.PeerIdentity)
	if !ok {
		return fmt.Errorf("confirm engine peer: %w", transport.ErrPeerIdentityUnsupported)
	}
	if err := identity.ConfirmPeer(peerID); err != nil {
		return fmt.Errorf("confirm engine peer: %w", err)
	}
	return nil
}

// Close terminates the transport.
func (p *streamTransport) Close() error {
	if err := p.session.Close(); err != nil {
		return fmt.Errorf("session close: %w", err)
	}
	return nil
}

// ResetPeer clears peer binding on engines that expose it.
func (p *streamTransport) ResetPeer() {
	if resetter, ok := p.session.(engine.PeerResetter); ok {
		resetter.ResetPeer()
	}
}

// RetirePeer passes the server's word that its session on peerID has ended to
// an engine that keeps send state per peer. The transport itself keeps none.
//
// ai-generated: this method (olcrtc#49).
func (p *streamTransport) RetirePeer(peerID string) {
	if retirer, ok := p.session.(engine.PeerRetirer); ok {
		retirer.RetirePeer(peerID)
	}
}

// SetReconnectCallback registers reconnect handling.
func (p *streamTransport) SetReconnectCallback(cb func()) {
	p.session.SetReconnectCallback(cb)
}

// CanSend reports whether transport is ready for sending.
func (p *streamTransport) CanSend() bool {
	return p.session.CanSend()
}

// WaitForPeer blocks until the remote peer is confirmed ready, or ctx expires.
// Implements transport.PeerReadyTransport.
func (p *streamTransport) WaitForPeer(ctx context.Context) error {
	waiter, ok := p.session.(engine.PeerReadySession)
	if !ok {
		return nil
	}
	if err := waiter.WaitForPeer(ctx); err != nil {
		return fmt.Errorf("wait for peer: %w", err)
	}
	return nil
}

// Features describes the current datachannel transport semantics.
func (p *streamTransport) Features() transport.Features {
	_, datagram := p.session.(engine.DatagramSession)
	return p.shaper.Features(transport.Features{
		MaxPayloadSize: defaultMaxPayloadSize, Datagram: datagram, WriteInterval: writeInterval,
	})
}
