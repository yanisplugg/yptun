package jitsi

import (
	"testing"
	"time"
)

// A client addresses its uploads to the endpoint it has latched - the
// server's - instead of the room. A frame addressed to "" is handed by the
// bridge to every endpoint in the room, so in a room with several clients
// each upload also fills the other clients' receive queues on the bridge,
// which drop it (#25).
func TestAClientSendsToTheEndpointItLatched(t *testing.T) {
	js := newSilentSession(t)

	if got := js.broadcastTarget(); got != "" {
		t.Fatalf("target before the latch = %q, want the room", got)
	}

	js.latchPeerEndpoint("peerA")
	if got := js.broadcastTarget(); got != "peerA" {
		t.Fatalf("target = %q, want peerA", got)
	}
}

// A latch nothing has confirmed for a while is not a destination: the peer
// may have come back on another endpoint, and a frame to the room still
// reaches it while the session sorts itself out.
func TestAStaleLatchFallsBackToTheRoom(t *testing.T) {
	js := newSilentSession(t)
	js.latchPeerEndpoint("peerA")

	js.peerEndpointSeen.Store(time.Now().Add(-peerLatchFresh - time.Second).UnixNano())
	if got := js.broadcastTarget(); got != "" {
		t.Fatalf("target with a stale latch = %q, want the room", got)
	}

	// The next frame from the peer makes it a destination again.
	js.latchPeerEndpoint("peerA")
	if got := js.broadcastTarget(); got != "peerA" {
		t.Fatalf("target after a fresh frame = %q, want peerA", got)
	}
}

// The server never latches an endpoint - it takes the peer path instead - so
// its broadcasts still go to the room.
func TestTheServerStillBroadcastsToTheRoom(t *testing.T) {
	js := newSilentSession(t)
	js.onPeerData = func(string, []byte) {}

	if got := js.broadcastTarget(); got != "" {
		t.Fatalf("server target = %q, want the room", got)
	}
}
