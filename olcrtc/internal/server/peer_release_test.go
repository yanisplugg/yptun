package server

import (
	"context"
	"slices"
	"sync"
	"testing"

	"github.com/openlibrecommunity/olcrtc/internal/muxconn"
)

// ai-generated: reproduce a delayed teardown removing a replacement session.
func TestStalePeerTeardownPreservesReplacement(t *testing.T) {
	stale := &peerSession{peerID: "00000042"}
	live := &peerSession{peerID: stale.peerID}
	s := &Server{peerSessions: map[string]*peerSession{live.peerID: live}}
	s.removePeer(stale, "closed")
	if s.peerSessions[live.peerID] != live {
		t.Fatal("a stale session callback deleted its replacement")
	}
}

// retireLog records what the server tells the transport about ended peers.
// ai-generated: the whole type.
type retireLog struct {
	retiredMu sync.Mutex
	retired   []string
}

func (l *retireLog) RetirePeer(peerID string) {
	l.retiredMu.Lock()
	l.retired = append(l.retired, peerID)
	l.retiredMu.Unlock()
}

func (l *retireLog) calls() []string {
	l.retiredMu.Lock()
	defer l.retiredMu.Unlock()
	return slices.Clone(l.retired)
}

// ai-generated: a peer-routing transport that keeps per-peer state.
type lifecycleStub struct {
	peerRoutingStub
	retireLog
}

// ai-generated: the transport hears once, from the owning session, after teardown.
func TestPeerSessionEndRetiresEpochAfterTeardown(t *testing.T) {
	ln := &lifecycleStub{}
	peer := &peerSession{peerID: "00000042"}
	s := &Server{ln: ln, peerLn: ln, peerSessions: map[string]*peerSession{peer.peerID: peer}}
	peer.controlStop = func() {
		if len(ln.calls()) != 0 {
			t.Error("transport was told before the session's teardown")
		}
	}
	s.removePeer(peer, "closed")
	s.removePeer(peer, "liveness")
	if got := ln.calls(); !slices.Equal(got, []string{peer.peerID}) {
		t.Fatalf("RetirePeer calls = %v, want exactly [%s]", got, peer.peerID)
	}
	live := &peerSession{peerID: peer.peerID}
	s.peerSessions[live.peerID] = live
	s.removePeer(peer, "closed")
	if got := ln.calls(); len(got) != 1 {
		t.Fatalf("a stale session's callback retired its replacement: %v", got)
	}
}

// ai-generated: a per-peer control transport that keeps per-peer state.
type retiringControlStub struct {
	peerControlRoutingStub
	retireLog
}

// ai-generated: a provider reconnect ends every peer session while the transport
// lives on, so each of their epochs is retired.
func TestPeerRoutingReconnectRetiresEpochs(t *testing.T) {
	link := &retiringControlStub{}
	s := reconnectTestServer(t, &link.peerRoutingStub)
	s.ln, s.peerLn = link, link
	for _, id := range []string{"00000001", "00000002"} {
		s.peerSessions[id] = newPeerSession(id, true, muxconn.PrePinned(newServerTestKeys(t), ""))
	}
	s.handleReconnect(context.Background())
	got := link.calls()
	slices.Sort(got)
	if !slices.Equal(got, []string{"00000001", "00000002"}) {
		t.Fatalf("RetirePeer calls after reconnect = %v", got)
	}
	s.closeSession()
}
