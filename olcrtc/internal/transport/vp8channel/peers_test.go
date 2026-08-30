package vp8channel

import (
	"testing"
	"time"
)

// newStubPeerSession builds a session whose KCP runtime is never started, so
// the table logic can be exercised without touching the network stack.
func newStubPeerSession(t *testing.T, epoch uint32) *peerSession {
	t.Helper()

	out := make(chan *packetBuffer, 1)

	rt, err := startKCP(out, nil, buildEpochHeader(0, epoch))
	if err != nil {
		t.Fatalf("startKCP() error = %v", err)
	}

	return newPeerSession(epoch, rt, out)
}

func TestPeerTableZeroValueIsUsable(t *testing.T) {
	var table peerTable

	if got := table.get(1); got != nil {
		t.Fatalf("get() on empty table = %v, want nil", got)
	}

	if got := table.len(); got != 0 {
		t.Fatalf("len() = %d, want 0", got)
	}

	sess := newStubPeerSession(t, 1)
	if !table.add(sess) {
		t.Fatal("add() = false, want true")
	}

	if got := table.get(1); got != sess {
		t.Fatalf("get(1) = %v, want the added session", got)
	}

	table.closeAll()
}

func TestPeerTableSweepEvictsIdleSessions(t *testing.T) {
	var table peerTable

	idle := newStubPeerSession(t, 1)
	fresh := newStubPeerSession(t, 2)

	table.add(idle)
	table.add(fresh)

	// Backdate the first session past the TTL.
	table.mu.Lock()
	idle.lastSeen = time.Now().Add(-time.Hour).UnixNano()
	table.mu.Unlock()

	table.sweep(time.Minute)

	if got := table.len(); got != 1 {
		t.Fatalf("len() after sweep = %d, want 1", got)
	}

	if got := table.get(1); got != nil {
		t.Fatal("idle session survived the sweep")
	}

	if got := table.get(2); got != fresh {
		t.Fatalf("get(2) = %v, want the fresh session", got)
	}

	// The queue itself stays open (kcp-go can still be writing into it);
	// the pump is stopped through done instead.
	select {
	case <-idle.done:
	default:
		t.Fatal("evicted session did not stop its writer pump")
	}

	table.closeAll()
}

func TestPeerTableTouchKeepsBusyPeerAlive(t *testing.T) {
	var table peerTable

	sess := newStubPeerSession(t, 7)
	table.add(sess)

	table.mu.Lock()
	sess.lastSeen = time.Now().Add(-time.Hour).UnixNano()
	table.mu.Unlock()

	// A single inbound frame refreshes the timer.
	table.get(7)
	table.sweep(time.Minute)

	if got := table.len(); got != 1 {
		t.Fatalf("len() after sweep = %d, want the touched peer to survive", got)
	}

	table.closeAll()
}

func TestPeerTableEvictsOldestWhenFull(t *testing.T) {
	var table peerTable

	base := time.Now().Add(-time.Hour)

	for i := range uint32(maxPeers) {
		sess := newStubPeerSession(t, i+1)
		table.add(sess)

		table.mu.Lock()
		sess.lastSeen = base.Add(time.Duration(i) * time.Second).UnixNano()
		table.mu.Unlock()
	}

	if got := table.len(); got != maxPeers {
		t.Fatalf("len() = %d, want %d", got, maxPeers)
	}

	table.add(newStubPeerSession(t, maxPeers+1))

	if got := table.len(); got != maxPeers {
		t.Fatalf("len() after overflow = %d, want %d", got, maxPeers)
	}

	if got := table.get(1); got != nil {
		t.Fatal("oldest peer survived the overflow eviction")
	}

	if got := table.get(maxPeers + 1); got == nil {
		t.Fatal("new peer was not admitted")
	}

	table.closeAll()
}

func TestPeerTableRejectsAddAfterClose(t *testing.T) {
	var table peerTable

	table.closeAll()

	if table.add(newStubPeerSession(t, 1)) {
		t.Fatal("add() = true after closeAll, want false")
	}

	if got := table.len(); got != 0 {
		t.Fatalf("len() = %d, want 0", got)
	}
}
