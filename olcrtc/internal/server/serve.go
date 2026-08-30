package server

import (
	"context"
	"encoding/json"
	"errors"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/xtaci/smux"

	"github.com/openlibrecommunity/olcrtc/internal/framing"
	"github.com/openlibrecommunity/olcrtc/internal/handshake"
	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
	"github.com/openlibrecommunity/olcrtc/internal/tunnelcore"
)

type stateGate struct {
	mu sync.Mutex
	ch chan struct{}
}

func (g *stateGate) wait() <-chan struct{} {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.ch == nil {
		g.ch = make(chan struct{})
	}
	return g.ch
}

func (g *stateGate) broadcast() {
	g.mu.Lock()
	ch := g.ch
	g.ch = nil
	g.mu.Unlock()
	if ch != nil {
		close(ch)
	}
}

func waitState(ctx context.Context, changed <-chan struct{}) bool {
	select {
	case <-ctx.Done():
		return false
	case <-changed:
		return true
	}
}

func (s *Server) serve(ctx context.Context) {
	if s.peerLn != nil {
		<-ctx.Done()
		return
	}
	s.serveSingle(ctx)
}

func (s *Server) serveSingle(ctx context.Context) {
	for {
		if contextDone(ctx) {
			return
		}
		changed := s.state.wait()
		s.sessMu.RLock()
		session := s.session
		hasControlConn := s.controlConn != nil
		ready := s.sessionID != ""
		s.sessMu.RUnlock()
		if session == nil || (!ready && hasControlConn) {
			if !waitState(ctx, changed) {
				return
			}
			continue
		}
		if !ready && !s.acceptSingletonHandshake(ctx, session) {
			continue
		}
		stream, err := session.AcceptStream()
		if err != nil {
			if s.handleAcceptError(ctx, session, err) {
				return
			}
			continue
		}
		s.goTracked(func() { s.handleStream(ctx, stream, s.currentSessionID()) })
	}
}

func (s *Server) handleAcceptError(ctx context.Context, session *smux.Session, err error) bool {
	if contextDone(ctx) {
		return true
	}
	hadSession := s.handshakeReady()
	logger.Infof("server: AcceptStream(data) error - reinstalling session: %v", err)
	s.reinstallSession(ctx, session)
	if hadSession && s.ln != nil {
		s.ln.Reconnect("liveness")
	}
	return false
}

func (s *Server) currentSessionID() string {
	s.sessMu.RLock()
	defer s.sessMu.RUnlock()
	return s.sessionID
}

func contextDone(ctx context.Context) bool {
	select {
	case <-ctx.Done():
		return true
	default:
		return false
	}
}

func (s *Server) handshakeReady() bool {
	s.sessMu.RLock()
	defer s.sessMu.RUnlock()
	return s.sessionID != ""
}

type handshakeResult struct {
	sessionID string
	deviceID  string
}

func (s *Server) acceptHandshake(
	ctx context.Context,
	session *smux.Session,
) (*smux.Stream, handshakeResult, bool) {
	const maxStaleRetries = 3
	for retry := 0; retry <= maxStaleRetries; retry++ {
		stream, err := session.AcceptStream()
		if err != nil {
			if ctx.Err() != nil {
				return nil, handshakeResult{}, false
			}
			logger.Infof("server: AcceptStream(control) error - reinstalling session: %v", err)
			tunnelcore.ResetPeer(s.ln)
			s.reinstallSession(ctx, session)
			return nil, handshakeResult{}, false
		}
		_ = stream.SetDeadline(time.Now().Add(handshake.DefaultTimeout))
		hello, sessionID, err := handshake.Server(stream, s.authHook, s.localPeerID())
		_ = stream.SetDeadline(time.Time{})
		if err != nil {
			_ = stream.Close()
			if errors.Is(err, framing.ErrFrameTooLarge) && retry < maxStaleRetries {
				logger.Debugf("handshake: discarding stale stream (attempt %d): %v", retry+1, err)
				continue
			}
			logger.Warnf("handshake failed: %v", err)
			tunnelcore.ResetPeer(s.ln)
			s.reinstallSession(ctx, session)
			return nil, handshakeResult{}, false
		}
		s.health.RecordSession(sessionID)
		s.onOpen(sessionID, hello.DeviceID, hello.Claims)
		s.trackPeerOpen(sessionID, hello.DeviceID)
		logger.Infof("session %s opened (device=%s)", sessionID, hello.DeviceID)
		return stream, handshakeResult{sessionID: sessionID, deviceID: hello.DeviceID}, true
	}
	return nil, handshakeResult{}, false
}

func (s *Server) localPeerID() string {
	identity, ok := s.ln.(transport.PeerIdentity)
	if !ok {
		return ""
	}
	return identity.LocalPeerID()
}

func (s *Server) acceptSingletonHandshake(ctx context.Context, session *smux.Session) bool {
	stream, result, ok := s.acceptHandshake(ctx, session)
	if !ok {
		return false
	}
	s.sessMu.Lock()
	s.deviceID = result.deviceID
	s.sessionID = result.sessionID
	s.sessMu.Unlock()
	s.state.broadcast()
	s.startControlLoop(ctx, session, stream)
	return true
}

func (s *Server) handleStream(ctx context.Context, stream *smux.Stream, sessionID string) {
	defer func() { _ = stream.Close() }()
	if sessionID == "" {
		sessionID = s.currentSessionID()
	}
	if done := ctx.Done(); done != nil {
		finished := make(chan struct{})
		defer close(finished)
		go func() {
			select {
			case <-done:
				_ = stream.Close()
			case <-finished:
			}
		}()
	}
	const maxConnectRequest = 4096
	header := make([]byte, 0, 256)
	buffer := make([]byte, 256)
	_ = stream.SetReadDeadline(time.Now().Add(15 * time.Second))
	for {
		n, err := stream.Read(buffer)
		if n > 0 {
			header = append(header, buffer[:n]...)
			if request, ok := parseConnectRequest(header); ok {
				_ = stream.SetReadDeadline(time.Time{})
				s.dispatch(ctx, stream, request, sessionID)
				return
			}
		}
		if err != nil || len(header) > maxConnectRequest {
			return
		}
	}
}

func parseConnectRequest(buffer []byte) (ConnectRequest, bool) {
	var request ConnectRequest
	if err := json.Unmarshal(buffer, &request); err != nil {
		return request, false
	}
	return request, request.Cmd == connectCommand
}

func defaultAuthHook(_ string, _ map[string]any) (string, error) {
	return uuid.NewString(), nil
}
