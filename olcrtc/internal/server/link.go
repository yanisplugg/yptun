package server

import (
	"context"
	"fmt"

	"github.com/xtaci/smux"

	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/muxconn"
	"github.com/openlibrecommunity/olcrtc/internal/testhooks"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
	"github.com/openlibrecommunity/olcrtc/internal/tunnelcore"
)

func (s *Server) bringUpLink(ctx context.Context, cfg Config, cancel context.CancelFunc) error {
	s.baseCtx = ctx
	linkCfg := tunnelcore.BuildTransportConfig(tunnelcore.LinkConfig{
		Provider: cfg.Provider, RoomURL: cfg.RoomURL, Engine: cfg.Engine,
		URL: cfg.URL, Token: cfg.Token, ProviderToken: cfg.ProviderToken,
		ChannelID: cfg.ChannelID, DNSServer: s.dnsServer, DTLSProfile: cfg.DTLSProfile,
		Options: cfg.TransportOptions, Traffic: cfg.Traffic,
	}, s.linkRole())
	ln, err := transport.New(ctx, cfg.Transport, linkCfg)
	if err != nil {
		return fmt.Errorf("failed to create transport: %w", err)
	}
	s.ln = ln
	if peerLn, ok := ln.(transport.PeerTransport); ok && peerLn.SupportsPeerRouting() {
		s.peerLn = peerLn
	}
	ln.SetEndedCallback(func(reason string) {
		logger.Infof("Server link reported conference end: %s", reason)
		s.setLinkState(LinkDown)
		cancel()
	})
	ln.SetShouldReconnect(func() bool { return ctx.Err() == nil })
	ln.SetReconnectCallback(func() {
		if ctx.Err() == nil {
			s.handleReconnect(ctx)
		}
	})
	logger.Infof("Connecting transport=%s provider=%s ...", cfg.Transport, cfg.Provider)
	if s.peerLn == nil {
		s.installSession()
	} else {
		s.installControlSession(ctx)
	}
	if err := ln.Connect(ctx); err != nil {
		return fmt.Errorf("failed to connect link: %w", err)
	}
	// Connect returned nil: the carrier session is joined, which is what a
	// client needs to pair - over the session installed just above on the
	// single-session path, over a peer session built on demand on a
	// peer-routing transport, where nothing is installed at this point. A
	// join that fails returns instead and leaves /stats reporting
	// connecting, which is the whole point of the field: a room that is
	// gone must not read like a server one can reach.
	s.setLinkState(LinkUp)
	logger.Infof("Link connected")
	s.logPeersLine()
	s.goTracked(func() { ln.WatchConnection(ctx) })
	// ai-generated: a test build's provider drop (olcrtc#19). Enabled is a
	// constant, so a release build compiles nothing here.
	if testhooks.Enabled {
		s.goTracked(func() { testhooks.DropProviderAfter(ctx, func() { ln.Reconnect("testhook") }) })
	}
	return nil
}

func (s *Server) installSession() {
	group := muxconn.NewPinGroup(s.ring)
	pair, err := tunnelcore.NewSessionPairGrouped(s.ln, group, tunnelcore.ServerRole)
	if pair == nil {
		logger.Warnf("smux server init failed: %v", err)
		return
	}
	if err != nil {
		logger.Warnf("control smux server init failed: %v", err)
	}
	s.sessMu.Lock()
	s.installPairLocked(pair, group)
	s.sessMu.Unlock()
	s.state.broadcast()
	if pair.HasIsolatedControl() {
		control := pair.ControlSession
		s.goTracked(func() { s.acceptSingletonHandshake(s.baseCtx, control) })
	}
}

func (s *Server) installControlSession(ctx context.Context) {
	if peerControl, ok := s.ln.(transport.PeerControlPlane); ok {
		s.installPeerControlPlane(peerControl)
		return
	}
	group := muxconn.NewPinGroup(s.ring)
	conn, session, err := tunnelcore.NewControlSessionGrouped(s.ln, group, tunnelcore.ServerRole)
	if err != nil {
		logger.Warnf("control smux server init failed (peer-routing): %v", err)
		return
	}
	if conn == nil {
		// Correct, and it looks like a bug: a transport with no control
		// channel of its own has nothing to build a shared control session
		// on, and needs none. Its peers each get their own data session
		// through onPeerData, and peer.go runs the handshake there — see the
		// PeerControlPlane check in getOrCreatePeerControlSession.
		//
		// A "fix" that declined peer routing for such a transport was written
		// on the assumption that this silence meant nothing was serving, and
		// it broke jitsi+datachannel outright: without peer routing the
		// server broadcasts, and the client sets RequireTargetedPeer, so the
		// welcome was dropped and every handshake timed out. Two live
		// pairings each way said so. Do not repeat it.
		return
	}
	s.sessMu.Lock()
	s.group = group
	s.controlConn = conn
	s.controlSess = session
	s.sessMu.Unlock()
	s.goTracked(func() { s.acceptSingletonHandshake(ctx, session) })
}

// reconnectLink records the carrier session as down and asks the transport
// to rebuild it. Every server-side liveness verdict goes through here, so
// /stats reports down for the whole rebuild - the engine backs off between
// attempts, which is seconds to minutes - and not merely for the instant the
// server noticed.
//
// ai-generated: the whole function, and the two callers it replaced.
func (s *Server) reconnectLink(reason string) {
	if s.ln == nil {
		return
	}
	s.setLinkState(LinkDown)
	s.ln.Reconnect(reason)
}

func (s *Server) handleReconnect(ctx context.Context) {
	s.health.RecordReconnect()
	logger.Infof("server reconnect reason=provider - tearing down smux session")
	// The engine reports only a rebuild it completed, so the carrier
	// session is joined again by the time this runs; /stats says up once
	// the smux session over it has been reinstalled. A reinstall that
	// fails still leaves the session joined - what the serve loop retries
	// then is the session, not the room.
	//
	// The ctx check is the ordering, not a shortcut: a rebuild passes the
	// callback's own guard and then spends time tearing peers down, so an
	// end that arrives inside that window would otherwise have its
	// terminal down overwritten by this defer. Whatever ends the run
	// cancels ctx first.
	defer func() {
		if ctx.Err() == nil {
			s.setLinkState(LinkUp)
		}
	}()
	if s.peerLn != nil {
		s.reinstallPeerRouting(ctx)
		return
	}
	s.sessMu.RLock()
	current := s.session
	s.sessMu.RUnlock()
	s.reinstallSession(ctx, current)
}

func (s *Server) reinstallSession(ctx context.Context, dead *smux.Session) {
	if s.peerLn != nil {
		s.reinstallPeerRouting(ctx)
		return
	}
	s.reinstallMu.Lock()
	defer s.reinstallMu.Unlock()
	s.sessMu.RLock()
	if s.pair != nil {
		// ai-generated: changed s.pair.CloseConns() to s.pair.Close() and
		// added this comment explaining why.
		// Close (not CloseConns): the stale pair's smux Sessions must be
		// closed too, not just their muxconns - otherwise a recvLoop
		// currently parked on its flow-control bucket (waiting on
		// bucketNotify/die, not reading conn) never notices the conn died
		// and AcceptStream on it stays blocked until something else
		// eventually pokes it.
		_ = s.pair.Close()
	} else {
		if s.conn != nil {
			_ = s.conn.Close()
		}
		if s.controlConn != nil {
			_ = s.controlConn.Close()
		}
	}
	s.sessMu.RUnlock()
	// A fresh group per generation: the peer that comes back may hold
	// another key than the one that left.
	group := muxconn.NewPinGroup(s.ring)
	replacement, err := tunnelcore.NewSessionPairGrouped(s.ln, group, tunnelcore.ServerRole)
	if replacement == nil {
		logger.Warnf("smux server re-init failed: %v", err)
		return
	}
	if err != nil {
		logger.Warnf("control smux server re-init failed: %v", err)
	}
	if !s.swapSession(dead, replacement, group) {
		return
	}
	if replacement.HasIsolatedControl() {
		control := replacement.ControlSession
		s.goTracked(func() { s.acceptSingletonHandshake(ctx, control) })
	}
}

type peerRoutingTeardown struct {
	pair           *tunnelcore.SessionPair
	conn           *muxconn.Conn
	session        *smux.Session
	controlConn    *muxconn.Conn
	controlSession *smux.Session
	controlStream  *smux.Stream
	controlStop    context.CancelFunc
	peers          map[string]*peerSession
	sessionID      string
}

func (s *Server) reinstallPeerRouting(ctx context.Context) {
	s.reinstallMu.Lock()
	defer s.reinstallMu.Unlock()
	teardown := s.detachPeerRouting()
	s.closePeerRouting(teardown)
	s.installControlSession(ctx)
}

func (s *Server) detachPeerRouting() peerRoutingTeardown {
	s.sessMu.Lock()
	defer s.sessMu.Unlock()
	teardown := peerRoutingTeardown{
		pair: s.pair, conn: s.conn, session: s.session,
		controlConn: s.controlConn, controlSession: s.controlSess,
		controlStream: s.controlStrm, controlStop: s.controlStop,
		peers: s.peerSessions, sessionID: s.sessionID,
	}
	s.peerSessions = make(map[string]*peerSession)
	s.pair, s.conn, s.session, s.group = nil, nil, nil, nil
	s.controlConn, s.controlSess = nil, nil
	s.controlStrm, s.controlStop = nil, nil
	s.sessionID, s.deviceID = "", ""
	return teardown
}

func (s *Server) closePeerRouting(teardown peerRoutingTeardown) {
	tunnelcore.NotifyControlClose(teardown.controlStream)
	if teardown.controlStop != nil {
		teardown.controlStop()
	}
	if teardown.controlStream != nil {
		_ = teardown.controlStream.Close()
	}
	closeServerPair(teardown.pair, teardown.session, teardown.controlSession)
	if teardown.pair == nil {
		if teardown.conn != nil {
			_ = teardown.conn.Close()
		}
		if teardown.controlConn != nil {
			_ = teardown.controlConn.Close()
		}
	}
	if teardown.sessionID != "" {
		s.onClose(teardown.sessionID, "reconnect")
		s.trackPeerClose(teardown.sessionID, "reconnect")
	}
	s.closePeers(teardown.peers, func(peer *peerSession) {
		s.closePeerSession(peer, "reconnect")
		s.retirePeer(peer.peerID) // ai-generated: the transport outlives a provider reconnect
	})
	s.closeAllUDPFlows()
}

func (s *Server) staleReinstall(dead *smux.Session) bool {
	return dead != nil && dead != s.session && dead != s.controlSess
}

func (s *Server) swapSession(dead *smux.Session, replacement *tunnelcore.SessionPair, group *muxconn.PinGroup) bool {
	s.sessMu.Lock()
	if s.peerLn != nil {
		s.sessMu.Unlock()
		_ = replacement.Close()
		return false
	}
	if s.staleReinstall(dead) {
		s.sessMu.Unlock()
		_ = replacement.Close()
		return false
	}
	oldPair := s.pair
	oldSession := s.session
	oldControlSession := s.controlSess
	oldControl := s.controlStrm
	oldControlStop := s.controlStop
	oldSessionID := s.sessionID
	s.installPairLocked(replacement, group)
	s.controlStrm = nil
	s.controlStop = nil
	s.sessionID = ""
	s.deviceID = ""
	s.sessMu.Unlock()
	s.state.broadcast()
	if oldControlStop != nil {
		oldControlStop()
	}
	closeServerPair(oldPair, oldSession, oldControlSession)
	if oldControl != nil {
		_ = oldControl.Close()
	}
	if oldSessionID != "" {
		s.onClose(oldSessionID, "reconnect")
		s.trackPeerClose(oldSessionID, "reconnect")
	}
	s.closeAllUDPFlows()
	return true
}

func closeServerPair(pair *tunnelcore.SessionPair, session, controlSession *smux.Session) {
	if pair != nil {
		_ = pair.Close()
		return
	}
	if session != nil {
		_ = session.Close()
	}
	if controlSession != nil && controlSession != session {
		_ = controlSession.Close()
	}
}

func (s *Server) installPairLocked(pair *tunnelcore.SessionPair, group *muxconn.PinGroup) {
	s.pair = pair
	s.group = group
	s.conn = pair.DataConn
	s.session = pair.DataSession
	s.controlConn = pair.ControlConn
	if pair.HasIsolatedControl() {
		s.controlSess = pair.ControlSession
		return
	}
	s.controlSess = nil
}

func (s *Server) closeSession() {
	s.sessMu.Lock()
	pair := s.pair
	session := s.session
	controlSession := s.controlSess
	conn := s.conn
	controlConn := s.controlConn
	control := s.controlStrm
	controlStop := s.controlStop
	peers := s.peerSessions
	oldSessionID := s.sessionID
	s.peerSessions = make(map[string]*peerSession)
	s.pair, s.session, s.controlSess = nil, nil, nil
	s.conn, s.controlConn, s.group = nil, nil, nil
	s.controlStrm, s.controlStop = nil, nil
	s.sessionID, s.deviceID = "", ""
	s.sessMu.Unlock()
	s.state.broadcast()
	if controlStop != nil {
		controlStop()
	}
	tunnelcore.NotifyControlClose(control)
	if pair != nil {
		_ = pair.Close()
	} else {
		closeServerPair(nil, session, controlSession)
		if conn != nil {
			_ = conn.Close()
		}
		if controlConn != nil {
			_ = controlConn.Close()
		}
	}
	if oldSessionID != "" {
		s.onClose(oldSessionID, "closed")
		s.trackPeerClose(oldSessionID, "closed")
	}
	s.closePeers(peers, func(peer *peerSession) {
		s.closePeerSession(peer, "closed")
	})
	s.closeAllUDPFlows()
}

func (s *Server) onData(data []byte) {
	s.sessMu.RLock()
	conn := s.conn
	s.sessMu.RUnlock()
	tunnelcore.PushData(conn, data)
}

func (s *Server) shutdown() {
	if s.done != nil {
		s.doneOnce.Do(func() { close(s.done) })
	}
	s.closeSession()
	if s.ln != nil {
		_ = s.ln.Close()
	}
}

// linkRole is the server side's part of its link. It asks for no ICE mode: a
// server joins from a datacenter over UDP, and a room link's hint is for the
// client side alone (roomhint).
//
// ai-generated: this function (moved out of bringUpLink).
func (s *Server) linkRole() tunnelcore.LinkRoleConfig {
	return tunnelcore.LinkRoleConfig{
		OnData: s.onData, OnPeerData: s.onPeerData, Resolver: s.resolver,
		OnDatagram: s.onDatagram, OnPeerDatagram: s.onPeerDatagram,
		ProxyAddr: s.socksProxyAddr, ProxyPort: s.socksProxyPort,
	}
}
