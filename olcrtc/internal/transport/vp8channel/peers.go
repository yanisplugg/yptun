package vp8channel

import (
	"fmt"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/logger"
)

const (
	// peerIdleTTL is how long a per-peer session survives without traffic.
	// A client rotates its epoch on every reconnect, so without eviction each
	// reconnect would strand a KCP session, its reader goroutine, its writer
	// pump and a full outbound queue for the lifetime of the process.
	//
	// It has to comfortably exceed the control-plane liveness window: a live
	// but momentarily quiet peer must not be collected out from under an
	// in-flight handshake.
	peerIdleTTL = 3 * time.Minute

	// peerSweepInterval is how often idle peers are collected. It also bounds
	// how long an ended peer's epoch outlives retiredPeerIdle once silent,
	// and a peer that left mid-transfer keeps retransmitting into the shared
	// track until then.
	//
	// ai-generated: 30 s to 10 s, and the second half of this comment.
	peerSweepInterval = 10 * time.Second

	// maxPeers caps how many remote epochs one transport tracks at once. A
	// hostile or badly broken room could otherwise mint epochs faster than
	// the TTL reclaims them. Beyond the cap the oldest peer is evicted.
	maxPeers = 64

	// retiredPeerIdle is how long a peer whose server session has ended may
	// stay silent before its KCP state is released, instead of peerIdleTTL.
	// A client still on the epoch sends a keepalive at least every
	// forceKeepalivePeriod, so five missed beats is a client that has moved
	// on; one that is still heard may be retrying its handshake on the epoch.
	//
	// ai-generated: the constant and its comment.
	retiredPeerIdle = 5 * forceKeepalivePeriod
)

// peerSession is everything one remote epoch owns on the server side: its
// bulk KCP session, the queue its downlink frames are pumped from, and the
// isolated control-plane KCP used for that peer's handshake and liveness.
type peerSession struct {
	epoch uint32
	data  *kcpRuntime
	out   chan *packetBuffer
	// done stops the writer pump. The queue itself is deliberately never
	// closed: kcp-go keeps a postProcess goroutine draining its transmit
	// queue after UDPSession.Close returns, so it can still be parked in
	// kcpConn.WriteTo's `case c.out <- packet`. Closing out there turns that
	// select case into a ready send on a closed channel, which panics as
	// soon as the scheduler picks it - a coin flip on every teardown.
	done      chan struct{}
	closeOnce sync.Once

	controlMu sync.Mutex
	control   *kcpRuntime
	closed    bool // ai-generated: guarded by controlMu; fences late control creation.

	// lastSeen is a UnixNano timestamp refreshed by every inbound frame.
	lastSeen int64

	// retired is set when the server's session on this epoch has ended and
	// cleared by the next control-plane send, which only a server session
	// talking to the peer makes: its welcome, then its liveness pings. A
	// data-plane send proves nothing: a late data frame rebuilds a server
	// session that waits for a handshake that never comes, and its smux
	// keepalive writes all the same.
	// ai-generated: the field and its comment.
	retired atomic.Bool
}

// controlRuntime returns the peer's control KCP if one has been created.
// newPeerSession binds a freshly started KCP runtime to its outbound queue.
func newPeerSession(epoch uint32, data *kcpRuntime, out chan *packetBuffer) *peerSession {
	return &peerSession{epoch: epoch, data: data, out: out, done: make(chan struct{})}
}

func (s *peerSession) controlRuntime() *kcpRuntime {
	s.controlMu.Lock()
	defer s.controlMu.Unlock()

	return s.control
}

func (s *peerSession) touch(now time.Time) {
	// Written under peerTable's lock, read under the same lock by sweep.
	s.lastSeen = now.UnixNano()
}

// claim marks the epoch as owned by a server session again. Only the control
// send path calls it; see retired.
// ai-generated: the whole method.
func (s *peerSession) claim() {
	if s.retired.Load() {
		s.retired.Store(false)
	}
}

// readdress stamps local, the transport's new epoch, into every frame the
// session sends from now on. ai-generated (olcrtc#19).
func (s *peerSession) readdress(token, local uint32) {
	s.data.setHeader(buildEpochHeaderTo(token, local, s.epoch))

	s.controlMu.Lock()
	defer s.controlMu.Unlock()

	if s.control != nil {
		s.control.setHeader(buildEpochHeaderTo(token, local|controlEpochFlag, s.epoch|controlEpochFlag))
	}
}

// close releases both KCP sessions and stops the writer pump. Safe to call
// more than once.
// ai-generated: fence control creation before detaching and closing both runtimes.
func (s *peerSession) close() {
	s.closeOnce.Do(func() {
		s.controlMu.Lock()
		s.closed = true
		control := s.control
		s.control = nil
		s.controlMu.Unlock()

		s.data.abortPeer()
		if control != nil {
			control.abortPeer()
		}

		close(s.done)
	})
}

// peerTable tracks per-peer sessions with idle eviction. All mutation happens
// under mu; sessions handed out to callers stay valid because kcpRuntime and
// the queue tolerate use after close (writes fail, reads return).
//
// The zero value is ready to use: the map is created on first insert, so a
// transport that never sees a peer never allocates one.
type peerTable struct {
	mu       sync.RWMutex
	sessions map[uint32]*peerSession
	closed   bool

	// createMu serializes the get-or-create path in peerSessionFor. Frames
	// for the same brand-new epoch can arrive on different goroutines (each
	// RTP/track reader drives its own handleIncomingFrame loop), so two
	// callers can both miss in get() before either has installed a session
	// via add(). Without serializing the check-then-create sequence, both
	// build an independent KCP runtime for the same epoch; add() just
	// overwrites the map entry with the second one, but peerSessionFor still
	// hands each caller its own (possibly orphaned) session object. That
	// silently splits one peer's reliable byte stream across two unrelated
	// KCP state machines, which reassemble garbage that fails AEAD
	// authentication one layer up in muxconn - see olcrtc#142.
	createMu sync.Mutex
}

// get returns the session for epoch, refreshing its idle timer. Only the
// inbound path uses it, so the timer measures how long the peer has been
// silent; the send paths use lookup.
func (t *peerTable) get(epoch uint32) *peerSession {
	t.mu.Lock()
	defer t.mu.Unlock()

	sess := t.sessions[epoch]
	if sess != nil {
		sess.touch(time.Now())
	}

	return sess
}

// lookup returns the session for epoch without refreshing its idle timer:
// the server writing to an epoch says nothing about the peer still being
// there.
// ai-generated: the whole method.
func (t *peerTable) lookup(epoch uint32) *peerSession {
	t.mu.RLock()
	defer t.mu.RUnlock()

	return t.sessions[epoch]
}

// add installs a freshly created session, evicting the oldest peer when the
// table is full. It returns false when the table is already closed or a
// session for this epoch is already installed, in which case the caller must
// release the session it built instead of using it. The latter should never
// happen in practice - peerSessionFor serializes creation via createMu - but
// refusing a silent overwrite here is a cheap backstop: without it, a second
// session for the same epoch would clobber the map entry while some other
// caller keeps using the orphaned first one, splitting that peer's KCP
// stream across two state machines. See olcrtc#142.
func (t *peerTable) add(sess *peerSession) bool {
	t.mu.Lock()
	defer t.mu.Unlock()

	if t.closed {
		return false
	}

	if _, exists := t.sessions[sess.epoch]; exists {
		return false
	}

	if len(t.sessions) >= maxPeers {
		t.evictOldestLocked()
	}

	if t.sessions == nil {
		t.sessions = make(map[uint32]*peerSession)
	}

	sess.touch(time.Now())
	t.sessions[sess.epoch] = sess

	return true
}

// evictOldestLocked drops the least recently used peer. Callers hold mu.
func (t *peerTable) evictOldestLocked() {
	var (
		oldest *peerSession
		found  bool
	)

	for _, sess := range t.sessions {
		if !found || sess.lastSeen < oldest.lastSeen {
			oldest, found = sess, true
		}
	}

	if !found {
		return
	}

	logger.Infof("vp8channel: peer table full, evicting epoch=0x%08x", oldest.epoch)
	delete(t.sessions, oldest.epoch)
	oldest.close()
}

// sweep evicts every session idle for longer than ttl, and every retired one
// idle for longer than retiredTTL.
// ai-generated: added retiredTTL and the retired cutoff.
func (t *peerTable) sweep(ttl, retiredTTL time.Duration) {
	now := time.Now()
	cutoff := now.Add(-ttl).UnixNano()
	retiredCutoff := now.Add(-retiredTTL).UnixNano()

	t.mu.Lock()

	var stale []*peerSession

	for epoch, sess := range t.sessions {
		if sess.lastSeen < cutoff || (sess.retired.Load() && sess.lastSeen < retiredCutoff) {
			stale = append(stale, sess)
			delete(t.sessions, epoch)
		}
	}

	t.mu.Unlock()

	for _, sess := range stale {
		logger.Infof("vp8channel: peer session idle, releasing epoch=0x%08x", sess.epoch)
		sess.close()
	}
}

// releaseIdle detaches sess and closes it if it is still the table's session
// for its epoch and has been silent for longer than idle. It reports whether
// it did.
// ai-generated: the whole method.
func (t *peerTable) releaseIdle(sess *peerSession, idle time.Duration) bool {
	cutoff := time.Now().Add(-idle).UnixNano()

	t.mu.Lock()
	if t.sessions[sess.epoch] != sess || sess.lastSeen >= cutoff {
		t.mu.Unlock()
		return false
	}
	delete(t.sessions, sess.epoch)
	t.mu.Unlock()

	sess.close()

	return true
}

// closeAll releases every session and marks the table closed so no further
// peers are admitted.
func (t *peerTable) closeAll() {
	t.mu.Lock()
	sessions := t.sessions
	t.sessions = nil
	t.closed = true
	t.mu.Unlock()

	for _, sess := range sessions {
		sess.close()
	}
}

// snapshot returns the sessions tracked now. ai-generated (olcrtc#19).
func (t *peerTable) snapshot() []*peerSession {
	t.mu.RLock()
	defer t.mu.RUnlock()

	sessions := make([]*peerSession, 0, len(t.sessions))
	for _, sess := range t.sessions {
		sessions = append(sessions, sess)
	}

	return sessions
}

// len reports how many peers are currently tracked.
func (t *peerTable) len() int {
	t.mu.RLock()
	defer t.mu.RUnlock()

	return len(t.sessions)
}

func formatPeerID(epoch uint32) string {
	return fmt.Sprintf("%08x", epoch)
}

func parsePeerID(peerID string) (uint32, error) {
	v, err := strconv.ParseUint(peerID, 16, 32)
	if err != nil {
		return 0, fmt.Errorf("parse peer id %q: %w", peerID, err)
	}

	return uint32(v), nil
}

// peerSessionFor returns the session for epoch, creating it on demand. It
// returns nil when the KCP session cannot be started or the transport is
// shutting down.
//
// The whole check-then-create sequence is serialized by peers.createMu so
// concurrent first-contact frames for the same new epoch (arriving on
// different reader goroutines) always converge on exactly one session
// instead of racing to install two independent KCP runtimes for it. See
// olcrtc#142.
func (p *streamTransport) peerSessionFor(epoch uint32) *peerSession {
	if sess := p.peers.get(epoch); sess != nil {
		return sess
	}

	p.peers.createMu.Lock()
	defer p.peers.createMu.Unlock()

	if sess := p.peers.get(epoch); sess != nil {
		return sess
	}

	peerID := formatPeerID(epoch)
	out := make(chan *packetBuffer, outboundQueueSize)
	acks := make(chan *packetBuffer, ackQueueSize) // ai-generated: issue #12

	// Address downlink frames to the specific client epoch so other clients
	// do not ingest them (issue #95 multi-client cross-talk).
	hdr := buildEpochHeaderTo(p.bindingToken, p.localEpochValue(), epoch)

	data, err := startKCPAcks(out, acks, func(payload []byte) {
		if p.onPeerData != nil {
			p.onPeerData(peerID, payload)
		}
	}, hdr, p.sendWindow)
	if err != nil {
		logger.Warnf("vp8channel: startKCP for peer 0x%08x failed: %v", epoch, err)

		return nil
	}

	sess := newPeerSession(epoch, data, out)

	if !p.peers.add(sess) {
		sess.close()

		return nil
	}

	logger.Infof("vp8channel: peer session created epoch=0x%08x peers=%d", epoch, p.peers.len())

	// Pump outbound frames from this peer's queue into the writer.
	// ai-generated: through a data lane (issue #12).
	go p.peerWriterPump(dataLane{
		out:    out,
		acks:   acks,
		conn:   func() *kcpConn { return data.conn },
		window: func(segments int) { data.sess.SetWindowSize(segments, 0) },
		name:   "peer " + peerID,
	}, sess.done)

	return sess
}

// peerControlFor returns the isolated control-plane KCP for a peer, creating
// it on demand. Outbound frames go via the shared controlOutbound queue so
// writerLoop drains them with higher priority than bulk data.
func (p *streamTransport) peerControlFor(epoch uint32) *kcpRuntime {
	sess := p.peerSessionFor(epoch)
	if sess == nil {
		return nil
	}

	return p.controlOf(sess)
}

// controlOf returns sess's control KCP, creating it on first use.
// ai-generated: split out of peerControlFor so the send path can reach it
// without creating a peer.
func (p *streamTransport) controlOf(sess *peerSession) *kcpRuntime {
	epoch := sess.epoch

	sess.controlMu.Lock()
	defer sess.controlMu.Unlock()

	// ai-generated: an in-flight lookup can outlive removal from the peer table.
	if sess.closed {
		return nil
	}

	if sess.control != nil {
		return sess.control
	}

	peerID := formatPeerID(epoch)

	// src = our control epoch; dst = the peer's control epoch so its loopback
	// filter accepts the frame and other clients drop it.
	hdr := buildEpochHeaderTo(
		p.bindingToken,
		p.localEpochValue()|controlEpochFlag,
		epoch|controlEpochFlag,
	)

	control, err := startKCP(p.control.out, func(data []byte) {
		p.deliverPeerControlData(peerID, data)
	}, hdr, p.sendWindow)
	if err != nil {
		logger.Warnf("vp8channel: startKCP for peer control 0x%08x failed: %v", epoch, err)

		return nil
	}

	sess.control = control
	logger.Infof("vp8channel: per-peer control KCP created peerID=%s", peerID)

	return control
}

// readdressPeers carries the epoch restartPlanes has just rotated into every
// per-peer session. A session stamps the epoch of its creation into all it
// sends, so a server whose provider rebuilt went on answering its clients
// under the old epoch: a client bound to that epoch kept seeing its peer
// alive, never read the rebuild as a restart, and sat out the whole control
// liveness window instead (olcrtc#19). The KCP conversations go on, so the
// close notice the server sends a peer after its rebuild still reaches it.
//
// Holding createMu orders this against peerSessionFor: a session is either
// in the table by now or reads the new epoch when it is built.
//
// ai-generated: the whole function (olcrtc#19).
func (p *streamTransport) readdressPeers() {
	p.peers.createMu.Lock()
	defer p.peers.createMu.Unlock()

	local := p.localEpochValue()
	for _, sess := range p.peers.snapshot() {
		sess.readdress(p.bindingToken, local)
	}
}

// sweepPeers evicts idle peer sessions until the transport closes.
func (p *streamTransport) sweepPeers() {
	ticker := time.NewTicker(peerSweepInterval)
	defer ticker.Stop()

	for {
		select {
		case <-p.closeCh:
			return
		case <-ticker.C:
			p.peers.sweep(peerIdleTTL, retiredPeerIdle)
		}
	}
}
