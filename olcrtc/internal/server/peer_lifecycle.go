package server

import (
	"sync"

	"github.com/openlibrecommunity/olcrtc/internal/transport"
)

// retirePeer tells the transport that the server's session on peerID has
// ended, so it can release what it keeps for that peer.
// ai-generated: single call after teardown, replacing the fence and cleanup pair.
func (s *Server) retirePeer(peerID string) {
	if lifecycle, ok := s.ln.(transport.PeerLifecycle); ok {
		lifecycle.RetirePeer(peerID)
	}
}

// maxParallelPeerCloses bounds the fan-out below. Each close is a frame and a
// short wait, not work, so the bound is about how many goroutines a teardown
// is allowed to start at once, not about throughput.
const maxParallelPeerCloses = 16

// closePeers runs close over every peer of a detached session at once.
//
// Closing one peer tells it the session is over and then waits ~200 ms for
// that frame to leave (tunnelcore.NotifyControlClose). Done one after another,
// a server with a dozen peers spent seconds inside its reconnect callback -
// and the reconnect loop drops a provider event raised while that callback
// runs, so a room that moved during the teardown was simply missed (#31).
// The peers are already detached from the server here, and their closes touch
// nothing shared but the transport's own peer registry, so they are
// independent.
//
// ai-generated: the whole function.
func (s *Server) closePeers(peers map[string]*peerSession, closeOne func(*peerSession)) {
	if len(peers) == 0 {
		return
	}
	slots := make(chan struct{}, maxParallelPeerCloses)
	var wg sync.WaitGroup
	for _, peer := range peers {
		wg.Add(1)
		go func(peer *peerSession) {
			defer wg.Done()
			slots <- struct{}{}
			defer func() { <-slots }()
			closeOne(peer)
		}(peer)
	}
	wg.Wait()
}
