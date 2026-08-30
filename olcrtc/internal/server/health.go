package server

import (
	"context"

	"github.com/xtaci/smux"

	"github.com/openlibrecommunity/olcrtc/internal/control"
	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/tunnelcore"
)

func (s *Server) startControlLoop(ctx context.Context, session *smux.Session, stream *smux.Stream) {
	controlCtx, stop := context.WithCancel(ctx)
	s.sessMu.Lock()
	s.controlStrm = stream
	s.controlStop = stop
	s.sessMu.Unlock()
	runner := tunnelcore.ControlRunner{
		Transport: s.ln, Config: s.liveness, Health: s.health,
		LogFields: func() string { return "role=server session=" + s.currentSessionID() },
		OnDeath: func(error) {
			s.health.RecordReconnect()
			logger.Infof("server reconnect reason=liveness - reinstalling smux session")
			tunnelcore.ResetPeer(s.ln)
			s.reinstallSession(ctx, session)
			if s.ln != nil {
				s.ln.Reconnect("liveness")
			}
		},
	}
	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		defer func() { _ = stream.Close() }()
		runner.Run(controlCtx, stream)
	}()
}

// Status returns the latest server-side control health snapshot.
func (s *Server) Status() control.Status {
	return s.health.Status()
}
