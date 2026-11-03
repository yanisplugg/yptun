package server

import (
	"context"
	"errors"
	"fmt"
	"io"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/xtaci/smux"

	"github.com/openlibrecommunity/olcrtc/internal/control"
	"github.com/openlibrecommunity/olcrtc/internal/framing"
	"github.com/openlibrecommunity/olcrtc/internal/handshake"
	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/muxconn"
	"github.com/openlibrecommunity/olcrtc/internal/runtime"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
	"github.com/openlibrecommunity/olcrtc/internal/tunnelcore"
)

const (
	// maxPeerSessions bounds how many per-peer stacks the server holds at
	// once. Peer IDs come from the transport before anything is decrypted,
	// so any participant in the room can mint them: each new ID otherwise
	// costs a muxconn, an smux session and two goroutines that nothing
	// reclaims until a handshake that never comes.
	maxPeerSessions = 128

	// peerHandshakeTimeout bounds how long a peer session may sit waiting
	// for its handshake before it is released. A peer that reappears simply
	// gets a fresh session built on its next frame.
	peerHandshakeTimeout = 4 * handshake.DefaultTimeout

	// peerLimitWarnInterval rate-limits the peer-cap warning so a flood of
	// bogus peer IDs cannot turn the log into the outage.
	peerLimitWarnInterval = time.Minute
)

type peerStat struct {
	deviceID string
	openedAt time.Time
}

// peerSession holds one client's independently synchronized peer-routing state.
type peerSession struct {
	peerID string
	// group is the peer's pin group: data and control conns share it, so
	// the key the peer's first record opens under serves both planes.
	group         *muxconn.PinGroup
	sessionReady  chan struct{}
	readyOnce     sync.Once
	handshakeOnce sync.Once
	closeOnce     sync.Once
	mu            sync.Mutex
	conn          *muxconn.Conn
	session       *smux.Session
	controlConn   *muxconn.Conn
	controlSess   *smux.Session
	controlStrm   *smux.Stream
	controlStop   context.CancelFunc
	sessionID     string
	deviceID      string
	closed        bool
}

func newPeerSession(peerID string, needsControl bool, group *muxconn.PinGroup) *peerSession {
	peer := &peerSession{peerID: peerID, group: group}
	if needsControl {
		peer.sessionReady = make(chan struct{})
	}
	return peer
}

func (ps *peerSession) signalReady() {
	if ps.sessionReady != nil {
		ps.readyOnce.Do(func() { close(ps.sessionReady) })
	}
}

func (ps *peerSession) startHandshake(start func()) {
	ps.handshakeOnce.Do(start)
}

func (ps *peerSession) sid() string {
	ps.mu.Lock()
	defer ps.mu.Unlock()
	return ps.sessionID
}

func (ps *peerSession) setHandshake(result handshakeResult) bool {
	ps.mu.Lock()
	defer ps.mu.Unlock()
	if ps.closed {
		return false
	}
	ps.sessionID = result.sessionID
	ps.deviceID = result.deviceID
	return true
}

func (ps *peerSession) attachData(conn *muxconn.Conn, session *smux.Session) bool {
	ps.mu.Lock()
	defer ps.mu.Unlock()
	if ps.closed || ps.conn != nil || ps.session != nil {
		return false
	}
	ps.conn = conn
	ps.session = session
	return true
}

func (ps *peerSession) dataConn() *muxconn.Conn {
	ps.mu.Lock()
	defer ps.mu.Unlock()
	return ps.conn
}

func (ps *peerSession) dataSession() *smux.Session {
	ps.mu.Lock()
	defer ps.mu.Unlock()
	return ps.session
}

func (ps *peerSession) controlPlane() (*muxconn.Conn, *smux.Session) {
	ps.mu.Lock()
	defer ps.mu.Unlock()
	return ps.controlConn, ps.controlSess
}

func (ps *peerSession) attachControl(conn *muxconn.Conn, session *smux.Session) bool {
	ps.mu.Lock()
	defer ps.mu.Unlock()
	if ps.closed || ps.controlConn != nil || ps.controlSess != nil {
		return false
	}
	ps.controlConn = conn
	ps.controlSess = session
	return true
}

func (ps *peerSession) setControl(stream *smux.Stream, stop context.CancelFunc) bool {
	ps.mu.Lock()
	defer ps.mu.Unlock()
	if ps.closed {
		return false
	}
	ps.controlStrm = stream
	ps.controlStop = stop
	return true
}

type teardown struct {
	conn        *muxconn.Conn
	session     *smux.Session
	controlConn *muxconn.Conn
	controlSess *smux.Session
	controlStrm *smux.Stream
	controlStop context.CancelFunc
	sessionID   string
}

func (ps *peerSession) closeSnapshot() teardown {
	ps.mu.Lock()
	defer ps.mu.Unlock()
	ps.closed = true
	return teardown{
		conn: ps.conn, session: ps.session, controlConn: ps.controlConn,
		controlSess: ps.controlSess, controlStrm: ps.controlStrm,
		controlStop: ps.controlStop, sessionID: ps.sessionID,
	}
}

// closeConns closes the conns a peer's sessions run over.
//
// ai-generated: the whole method (olcrtc#49).
func (t teardown) closeConns() {
	if t.conn != nil {
		_ = t.conn.Close()
	}
	if t.controlConn != nil {
		_ = t.controlConn.Close()
	}
}

func (s *Server) installPeerControlPlane(plane transport.PeerControlPlane) {
	plane.SetControlOnPeerData(s.onPeerControlData)
}

func (s *Server) onPeerControlData(peerID string, data []byte) {
	peer := s.getOrCreatePeerControlSession(peerID)
	if peer == nil {
		return
	}
	if conn, _ := peer.controlPlane(); conn != nil {
		conn.Push(data)
	}
}

func (s *Server) getOrCreatePeerControlSession(peerID string) *peerSession {
	if peerID == "" {
		return nil
	}
	_, supportsControl := s.ln.(transport.PeerControlPlane)
	if !supportsControl {
		return nil
	}
	s.sessMu.Lock()
	peer := s.peerSessions[peerID]
	if peer != nil {
		if conn, _ := peer.controlPlane(); conn != nil {
			s.sessMu.Unlock()
			return peer
		}
	} else {
		if !s.mayAdmitPeerLocked(nil) {
			s.sessMu.Unlock()
			return nil
		}
		peer = newPeerSession(peerID, true, muxconn.NewPinGroup(s.ring))
	}
	conn := muxconn.NewPeerControlUnboundGrouped(s.ln, peer.group, peerID)
	if conn == nil {
		s.sessMu.Unlock()
		return nil
	}
	session, err := tunnelcore.NewSession(
		conn, tunnelcore.ServerRole, runtime.ControlSmuxConfig(runtime.MaxPayload(s.ln)),
	)
	if err != nil {
		logger.Warnf("control smux init failed for peer %s: %v", peerID, err)
		_ = conn.Close()
		s.sessMu.Unlock()
		return nil
	}
	if !peer.attachControl(conn, session) {
		_ = session.Close()
		_ = conn.Close()
		s.sessMu.Unlock()
		return peer
	}
	s.peerSessions[peerID] = peer
	s.sessMu.Unlock()
	logger.Infof("server: peer control session created peerID=%s", peerID)
	peer.startHandshake(func() {
		s.goTracked(func() { s.acceptPeerHandshake(s.streamContext(), peer) })
		s.goTracked(func() { s.expirePeerHandshake(peer) })
	})
	return peer
}

// expirePeerHandshake releases a peer whose handshake never lands. A
// control-only peer never reaches servePeer, so nothing else bounds it: its
// accept goroutine simply blocks in AcceptStream, and enough of them fill the
// admission cap and lock legitimate peers out.
func (s *Server) expirePeerHandshake(peer *peerSession) {
	timer := time.NewTimer(peerHandshakeTimeout)
	defer timer.Stop()
	select {
	case <-peer.sessionReady:
	case <-s.done:
	case <-timer.C:
		if peer.sid() != "" {
			return
		}
		logger.Infof("server: peer %s did not handshake within %s - releasing control session",
			peer.peerID, peerHandshakeTimeout)
		s.removePeer(peer, "handshake timeout")
	}
}

// mayAdmitPeerLocked reports whether a new per-peer stack may be built.
// Callers hold sessMu, the same lock closeSession takes to swap the peer map
// out, so a peer admitted here is always one teardown will see - and a peer
// arriving after teardown is refused instead of leaking a goroutine that
// outlives wg.Wait.
func (s *Server) mayAdmitPeerLocked(existing *peerSession) bool {
	if s.stopping() {
		return false
	}
	if existing != nil {
		return true
	}
	if len(s.peerSessions) >= maxPeerSessions {
		s.warnPeerLimit()
		return false
	}
	return true
}

func (s *Server) warnPeerLimit() {
	now := time.Now().UnixNano()
	last := s.peerLimitWarn.Load()
	if now-last < int64(peerLimitWarnInterval) {
		return
	}
	if !s.peerLimitWarn.CompareAndSwap(last, now) {
		return
	}
	logger.Warnf("server: peer session limit %d reached - refusing new peers", maxPeerSessions)
}

// goTracked runs fn on a goroutine shutdown waits for. Registration happens
// under sessMu, which shutdown also takes before wg.Wait, so wg.Add can never
// race a Wait that has already started.
func (s *Server) goTracked(fn func()) {
	s.sessMu.Lock()
	if s.stopping() {
		s.sessMu.Unlock()
		return
	}
	s.wg.Add(1)
	s.sessMu.Unlock()
	go func() {
		defer s.wg.Done()
		fn()
	}()
}

func (s *Server) onPeerData(peerID string, data []byte) {
	peer := s.getPeerSession(peerID)
	if peer == nil {
		// Not in peer-routing mode: fall back to the single data conn.
		// ai-generated: a routed peer's frame never belongs to the singleton path.
		if s.peerLn == nil {
			s.onData(data)
		}
		return
	}
	// ai-generated: the restart below (olcrtc#49). A peer whose handshake
	// runs on its data session - a transport without a control plane of its
	// own - is read for a session started over: a client that gives a
	// handshake up and tries again under the same relay identity. One with a
	// control plane starts over there.
	conn := peer.dataConn()
	if peer.sessionReady != nil || conn == nil {
		tunnelcore.PushData(conn, data)
		return
	}
	if record := conn.PushSession(data); record != nil {
		s.restartPeer(peer, record)
	}
}

// restartPeer gives a peer that has started its smux session over a session
// of its own, beginning with record, the new session's first. The session the
// peer left is ended without a word to it: the peer is gone from it, and what
// it wrote now would land in the new one, whose streams have the numbers the
// old one's had - a retried hello runs on the stream the old session's
// control stream ran on. The new session keeps the pin group: the peer holds
// the key it held.
//
// ai-generated: the whole function (olcrtc#49).
func (s *Server) restartPeer(peer *peerSession, record []byte) {
	logger.Infof("server: peer %s started its session over - replacing the one it left", peer.peerID)
	s.endPeer(peer, "restarted", false)
	next := s.peerSessionIn(peer.peerID, peer.group)
	if next == nil {
		return
	}
	if conn := next.dataConn(); conn != nil {
		conn.PushOpened(record)
	}
}

func (s *Server) getPeerSession(peerID string) *peerSession {
	return s.peerSessionIn(peerID, nil)
}

// peerSessionIn is getPeerSession with the pin group a peer session built
// from nothing starts in: carried, the group of the session a restarted peer
// left, or a fresh one when that is nil.
//
// A session built from nothing is built after the peer's last one is retired
// (retireEndedLocked).
//
// ai-generated: carried and the retirement first (olcrtc#49); the rest is
// getPeerSession as it was.
func (s *Server) peerSessionIn(peerID string, carried *muxconn.PinGroup) *peerSession {
	if peerID == "" || s.peerLn == nil {
		return nil
	}
	s.sessMu.Lock()
	peer := s.peerSessions[peerID]
	if peer == nil {
		s.retireEndedLocked(peerID)
		peer = s.peerSessions[peerID]
	}
	if peer != nil && peer.dataConn() != nil {
		s.sessMu.Unlock()
		return peer
	}
	if !s.mayAdmitPeerLocked(peer) {
		s.sessMu.Unlock()
		return nil
	}
	group := carried
	if peer != nil {
		group = peer.group
	}
	if group == nil {
		group = muxconn.NewPinGroup(s.ring)
	}
	conn := muxconn.NewPeerGrouped(s.peerLn, group, peerID)
	session, err := tunnelcore.NewSession(conn, tunnelcore.ServerRole, runtime.SmuxConfigFor(s.ln))
	if err != nil {
		s.sessMu.Unlock()
		logger.Warnf("smux server init failed for peer %s: %v", peerID, err)
		_ = conn.Close()
		return nil
	}
	if peer == nil {
		_, needsControl := s.ln.(transport.PeerControlPlane)
		peer = newPeerSession(peerID, needsControl, group)
		s.peerSessions[peerID] = peer
	}
	if !peer.attachData(conn, session) {
		_ = session.Close()
		_ = conn.Close()
		s.sessMu.Unlock()
		return peer
	}
	s.sessMu.Unlock()
	s.goTracked(func() { s.servePeer(peer) })
	return peer
}

func (s *Server) acceptPeerHandshake(ctx context.Context, peer *peerSession) {
	const maxStaleRetries = 3
	_, session := peer.controlPlane()
	if session == nil {
		return
	}
	for retry := 0; retry <= maxStaleRetries; retry++ {
		stream, err := session.AcceptStream()
		if err != nil {
			if ctx.Err() == nil {
				logger.Infof("server: AcceptStream(peer control=%s) error: %v", peer.peerID, err)
				s.removePeer(peer, "handshake failed")
			}
			return
		}
		_ = stream.SetDeadline(time.Now().Add(handshake.DefaultTimeout))
		hello, sessionID, err := handshake.Server(stream, s.authHook, s.localPeerID())
		_ = stream.SetDeadline(time.Time{})
		if err != nil {
			_ = stream.Close()
			if errors.Is(err, framing.ErrFrameTooLarge) && retry < maxStaleRetries {
				logger.Debugf("handshake peer=%s: stale stream retry %d: %v", peer.peerID, retry+1, err)
				continue
			}
			logger.Warnf("handshake peer=%s failed: %v", peer.peerID, err)
			s.removePeer(peer, "handshake failed")
			return
		}
		if !peer.setHandshake(handshakeResult{sessionID: sessionID, deviceID: hello.DeviceID}) {
			_ = stream.Close()
			return
		}
		s.meter.bind(sessionID, peer.group.KeyID())
		peer.signalReady()
		s.health.RecordSession(sessionID)
		s.onOpen(sessionID, hello.DeviceID, hello.Claims)
		s.trackPeerOpen(sessionID, hello.DeviceID)
		logger.Infof("peer session %s opened (peer=%s device=%s)", sessionID, peer.peerID, hello.DeviceID)
		s.startPeerControlLoop(ctx, peer, stream)
		return
	}
}

func (s *Server) startPeerControlLoop(ctx context.Context, peer *peerSession, stream *smux.Stream) {
	controlCtx, stop := context.WithCancel(ctx)
	if !peer.setControl(stream, stop) {
		stop()
		_ = stream.Close()
		return
	}
	runner := tunnelcore.ControlRunner{
		Transport: s.ln, Config: s.liveness, Health: s.health,
		LogFields: func() string { return "role=server peer=" + peer.peerID },
		// ai-generated: a peer that ended the stream is told nothing, and its
		// session is reported left, not dead of liveness (olcrtc#49).
		OnDeath: func(err error) {
			if peerLeft(err) {
				s.endPeer(peer, "left", false)
				return
			}
			s.endPeer(peer, "liveness", true)
		},
		Progress: func() uint64 { return peer.dataConn().PayloadBytes() },
	}
	s.goTracked(func() {
		defer func() { _ = stream.Close() }()
		runner.Run(controlCtx, closedByCaller{stream})
	})
}

// closedByCaller is a control stream whose Close is left to the loop that
// runs it. control.Run closes its stream the moment the read ends, which on a
// peer that ended the stream sends that peer a FIN before OnDeath has decided
// it is to hear nothing; closed after OnDeath, the peer's conns are shut and
// the FIN goes nowhere.
//
// ai-generated: the whole type (olcrtc#49).
type closedByCaller struct{ io.ReadWriter }

// Close leaves the stream open for the caller to close.
func (closedByCaller) Close() error { return nil }

func (s *Server) servePeer(peer *peerSession) {
	if peer.sid() == "" && !s.establishPeerSession(peer) {
		return
	}
	session := peer.dataSession()
	if session == nil {
		return
	}
	ctx := s.streamContext()
	sessionID := peer.sid()
	for {
		if s.stopping() {
			return
		}
		stream, err := session.AcceptStream()
		if err != nil {
			if !s.stopping() {
				logger.Infof("server: AcceptStream(peer=%s) error - closing peer session: %v", peer.peerID, err)
				s.removePeer(peer, "closed")
			}
			return
		}
		s.goTracked(func() { s.handleStream(ctx, stream, sessionID) })
	}
}

func (s *Server) streamContext() context.Context {
	if s.baseCtx != nil {
		return s.baseCtx
	}
	return context.Background()
}

func (s *Server) establishPeerSession(peer *peerSession) bool {
	if peer.sessionReady != nil {
		return s.waitPeerHandshake(peer)
	}
	session := peer.dataSession()
	if session == nil {
		return false
	}
	ctx := s.streamContext()
	// ai-generated: answerHello, not acceptHandshake, which reinstalls peer
	// routing for a failure (olcrtc#49).
	stream, result, ok := s.answerHello(ctx, session)
	if !ok {
		s.removePeer(peer, "handshake failed")
		return false
	}
	if !peer.setHandshake(result) {
		// ai-generated: the close of a session the peer's teardown cannot
		// see (olcrtc#49). The peer was ended while its hello was being
		// answered - it started its session over - and the session opened
		// for that hello is on no peer to be closed with it.
		_ = stream.Close()
		s.onClose(result.sessionID, "closed")
		s.trackPeerClose(result.sessionID, "closed")
		return false
	}
	// This is the handshake path of transports that route peers without a
	// per-peer control plane (datachannel over jitsi or livekit): the meter
	// learns the session's key here, as acceptPeerHandshake does for the
	// control-plane path.
	s.meter.bind(result.sessionID, peer.group.KeyID())
	s.startPeerControlLoop(ctx, peer, stream)
	return true
}

// waitPeerHandshake blocks until the peer's control-plane handshake lands.
// The wait is bounded: an unauthenticated peer that never handshakes would
// otherwise pin its whole session stack until the server stops, and a peer
// that comes back simply gets a fresh session built on its next frame.
func (s *Server) waitPeerHandshake(peer *peerSession) bool {
	if peer.sessionReady == nil {
		return false
	}
	timer := time.NewTimer(peerHandshakeTimeout)
	defer timer.Stop()
	select {
	case <-peer.sessionReady:
		return peer.sid() != ""
	case <-timer.C:
		logger.Infof("server: peer %s did not handshake within %s - releasing session",
			peer.peerID, peerHandshakeTimeout)
		s.removePeer(peer, "handshake timeout")
		return false
	case <-s.done:
		s.removePeer(peer, "closed")
		return false
	}
}

// removePeer ends peer if it is still the session for its peer ID, and then
// tells the transport, after the CLOSE notification has gone out.
// ai-generated: only the owning session retires the epoch, after teardown.
func (s *Server) removePeer(peer *peerSession, reason string) {
	s.endPeer(peer, reason, true)
}

// endPeer is removePeer, and with notify false it tells the peer nothing (see
// endPeerSession).
//
// The transport is told after the teardown, unless a new session for the
// peer ID has been built during it: that session told the transport before
// its first send (retireEndedLocked), and the teardown then tells it nothing.
// A close notice can take the whole notice budget on a stalled leg, and a
// peer that has given the session up is sending its retried hello meanwhile;
// told only now, the transport would refuse the new session's welcome, held
// on the same full relay window, as a frame of the session that ended.
//
// ai-generated: notify and the retirement a new session takes over
// (olcrtc#49); the rest is removePeer as it was.
func (s *Server) endPeer(peer *peerSession, reason string, notify bool) {
	if peer == nil {
		return
	}
	s.sessMu.Lock()
	if s.peerSessions[peer.peerID] != peer {
		s.sessMu.Unlock()
		return
	}
	delete(s.peerSessions, peer.peerID)
	if s.retiring == nil {
		s.retiring = make(map[string]*peerSession)
	}
	s.retiring[peer.peerID] = peer
	s.sessMu.Unlock()
	s.endPeerSession(peer, reason, notify)
	s.sessMu.Lock()
	own := s.retiring[peer.peerID] == peer
	if own {
		delete(s.retiring, peer.peerID)
	}
	s.sessMu.Unlock()
	if own {
		s.retirePeer(peer.peerID)
	}
}

// retireEndedLocked tells the transport that the session ended for peerID
// is over, when that session's teardown has not yet done so, before a new
// session for peerID is built: the new session's sends then begin after the
// retirement, and nothing the retirement refuses is theirs. What it refuses
// is the old session's, its close notice too, and rightly: the peer is
// sending into a new session by now, and anything the old one sends would
// land there.
//
// Called with sessMu held and no session in the map for peerID. The lock is
// let go around the transport's call and taken again, so the caller reads
// the map afresh after it.
//
// ai-generated: the whole method (olcrtc#49).
func (s *Server) retireEndedLocked(peerID string) {
	for s.retiring[peerID] != nil && s.peerSessions[peerID] == nil {
		delete(s.retiring, peerID)
		s.sessMu.Unlock()
		s.retirePeer(peerID)
		s.sessMu.Lock()
	}
}

// peerLeft reports whether a control stream ended because the peer ended it:
// it closed the stream, or said it was leaving. The session is over at the
// peer's end then, and nothing written into it would reach the peer - only
// the session it runs next under the same relay identity, whose streams have
// the numbers this one's had.
//
// ai-generated: the whole function (olcrtc#49).
func peerLeft(err error) bool {
	return errors.Is(err, io.EOF) || errors.Is(err, control.ErrClosedByPeer)
}

func (s *Server) closePeerSession(peer *peerSession, reason string) {
	s.endPeerSession(peer, reason, true)
}

// endPeerSession tears peer down, telling it first, with notify, that the
// session is over. Without notify the peer has left the session and is told
// nothing: its conns close before anything else, so not even the stream
// closes below put a frame on the wire.
//
// ai-generated: notify, and the sessions closed before the control stream
// (olcrtc#49); the rest is closePeerSession as it was.
func (s *Server) endPeerSession(peer *peerSession, reason string, notify bool) {
	peer.closeOnce.Do(func() {
		teardown := peer.closeSnapshot()
		peer.signalReady()
		if notify {
			notifyPeerClose(teardown.controlStrm)
		} else {
			teardown.closeConns()
		}
		if teardown.controlStop != nil {
			teardown.controlStop()
		}
		// The sessions close first: a closed session closes its streams, so
		// the stream's own close below queues no FIN behind a send loop the
		// link has stalled, which smux would wait 30 s on and then send into
		// whatever the peer runs next under the same identity. What is still
		// queued is dropped with the session.
		if teardown.session != nil {
			_ = teardown.session.Close()
		}
		if teardown.controlSess != nil {
			_ = teardown.controlSess.Close()
		}
		if teardown.controlStrm != nil {
			_ = teardown.controlStrm.Close()
		}
		if teardown.controlConn != nil {
			_ = teardown.controlConn.Close()
		}
		if teardown.conn != nil {
			_ = teardown.conn.Close()
		}
		if teardown.sessionID != "" {
			s.onClose(teardown.sessionID, reason)
			s.trackPeerClose(teardown.sessionID, reason)
		}
	})
}

// peerCloseNoticeBudget bounds the close notice a peer is sent, as control's
// closeNoticeBudget bounds its own: long enough for the frame on a link that
// still works, short enough that one the relay has stalled cannot hold the
// teardown - onClose, retirePeer - behind it.
//
// ai-generated (olcrtc#49).
const peerCloseNoticeBudget = 1500 * time.Millisecond

// notifyPeerClose tells the peer on stream that its session is over, waiting
// at most peerCloseNoticeBudget. A notice still queued when the budget runs
// out is released, and dropped, by the session's close.
//
// ai-generated: the whole function (olcrtc#49).
func notifyPeerClose(stream *smux.Stream) {
	if stream == nil {
		return
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		tunnelcore.NotifyControlClose(stream)
	}()
	timer := time.NewTimer(peerCloseNoticeBudget)
	defer timer.Stop()
	select {
	case <-done:
	case <-timer.C:
	}
}

func (s *Server) trackPeerOpen(sessionID, deviceID string) {
	s.peersMu.Lock()
	s.peerStats[sessionID] = peerStat{deviceID: deviceID, openedAt: time.Now()}
	line := s.peersLineLocked()
	s.peersMu.Unlock()
	logger.Infof("peer connected: device=%s session=%s", deviceID, sessionID)
	logger.Infof("%s", line)
}

func (s *Server) trackPeerClose(sessionID, reason string) {
	s.peersMu.Lock()
	stat, ok := s.peerStats[sessionID]
	if !ok {
		s.peersMu.Unlock()
		return
	}
	delete(s.peerStats, sessionID)
	line := s.peersLineLocked()
	s.peersMu.Unlock()
	logger.Infof("peer disconnected: device=%s session=%s reason=%s duration=%s",
		stat.deviceID, sessionID, reason, time.Since(stat.openedAt).Round(time.Second))
	logger.Infof("%s", line)
}

func (s *Server) peersLineLocked() string {
	devices := make([]string, 0, len(s.peerStats))
	for _, stat := range s.peerStats {
		devices = append(devices, stat.deviceID)
	}
	sort.Strings(devices)
	return fmt.Sprintf("Current peers count: %d, Devices: [%s]", len(s.peerStats), strings.Join(devices, ", "))
}

func (s *Server) logPeersLine() {
	s.peersMu.Lock()
	line := s.peersLineLocked()
	s.peersMu.Unlock()
	logger.Infof("%s", line)
}

func (s *Server) stopping() bool {
	select {
	case <-s.done:
		return true
	default:
		return false
	}
}
