package vp8channel

import "github.com/openlibrecommunity/olcrtc/internal/logger"

// RetirePeer implements transport.PeerLifecycle: the server's session on
// peerID has ended. The epoch's KCP sessions and writer pump go now if the
// peer has been silent for retiredPeerIdle, and otherwise through the sweep
// once it has. A peer still heard from keeps them and is never fenced off: the
// client retries its handshake on the same epoch, and a fresh server session
// over this KCP state is what answers it. See docs/peer-release.ru.md.
//
// ai-generated: replaced the retirement fence and its two-phase cleanup.
func (p *streamTransport) RetirePeer(peerID string) {
	epoch, err := parsePeerID(peerID)
	if err != nil {
		return
	}
	sess := p.peers.lookup(epoch)
	if sess == nil {
		return
	}
	sess.retired.Store(true)
	if p.peers.releaseIdle(sess, retiredPeerIdle) {
		logger.Infof("vp8channel: peer session ended, releasing epoch=0x%08x", epoch)
	}
}

// ai-generated: unblock queued peer output before KCP flushes on close.
// The singleton/client close path is deliberately unchanged.
func (r *kcpRuntime) abortPeer() {
	_ = r.conn.Close()
	r.close()
}
