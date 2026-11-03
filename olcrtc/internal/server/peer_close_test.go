package server

import (
	"sync/atomic"
	"testing"
	"time"
)

// A teardown closes its peers at once. Serially, each peer's close waits about
// 200 ms for its close notice to leave, and a dozen peers held the reconnect
// callback - and with it the reconnect loop - for seconds (#31).
func TestPeersOfATornDownSessionAreClosedTogether(t *testing.T) {
	const peers = 12
	const each = 120 * time.Millisecond

	sessions := make(map[string]*peerSession, peers)
	for i := range peers {
		sessions[string(rune('a'+i))] = &peerSession{}
	}

	var closed atomic.Int64
	s := &Server{}
	start := time.Now()
	s.closePeers(sessions, func(*peerSession) {
		time.Sleep(each)
		closed.Add(1)
	})
	elapsed := time.Since(start)

	if got := closed.Load(); got != peers {
		t.Fatalf("closed %d peers, want %d", got, peers)
	}
	// Serial would be peers*each; anything near one close means they overlapped.
	if elapsed > each*3 {
		t.Fatalf("closing %d peers took %s, want about %s", peers, elapsed, each)
	}
}

func TestClosingNoPeersDoesNothing(t *testing.T) {
	s := &Server{}
	s.closePeers(nil, func(*peerSession) { t.Fatal("closed a peer that was not there") })
	s.closePeers(map[string]*peerSession{}, func(*peerSession) { t.Fatal("closed a peer that was not there") })
}
