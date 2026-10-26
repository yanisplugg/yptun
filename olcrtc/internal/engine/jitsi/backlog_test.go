package jitsi

import (
	"sync/atomic"
	"testing"
	"time"
)

// The queues above sendLoop are bounded, but they used to drain into
// pion's SCTP association (SendText never blocks) or the j library's
// 1024-message websocket queue. On a saturated relay that put the whole
// backlog into memory, minutes of it, ahead of every later frame: connect
// acks timed out, pongs went missing, and the session was torn down
// (olcbox#23). The sender now holds while what sits below it is over the
// high-water mark.

func holdingSession(t *testing.T, backlog *atomic.Int64) *Session {
	t.Helper()
	s := newQueuedSession(t)
	s.backlogGauge = func() int { return int(backlog.Load()) }
	return s
}

func TestSenderHoldsWhileTheBacklogBelowItIsHigh(t *testing.T) {
	var backlog atomic.Int64
	backlog.Store(bridgeBacklogHighWater + 1)
	s := holdingSession(t, &backlog)

	released := make(chan bool, 1)
	go func() { released <- s.waitBridgeRoom() }()
	select {
	case <-released:
		t.Fatal("the sender went ahead with the backlog over the high-water mark")
	case <-time.After(100 * time.Millisecond):
	}

	backlog.Store(bridgeBacklogHighWater) // at the mark is room enough
	select {
	case ok := <-released:
		if !ok {
			t.Fatal("waitBridgeRoom reported the session closed; it was not")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("the sender stayed held after the backlog drained")
	}
}

func TestAHeldSenderIsReleasedWhenTheSessionCloses(t *testing.T) {
	var backlog atomic.Int64
	backlog.Store(10 * bridgeBacklogHighWater)
	s := holdingSession(t, &backlog)

	released := make(chan bool, 1)
	go func() { released <- s.waitBridgeRoom() }()
	time.Sleep(50 * time.Millisecond)
	_ = s.Close()
	select {
	case ok := <-released:
		if ok {
			t.Fatal("waitBridgeRoom returned true on a closed session")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("closing the session did not release the held sender")
	}
}

// Before the bridge exists there is nothing to queue behind, and the real
// gauge must say so rather than wait for a peer connection that is not there.
func TestTheRealGaugeReadsZeroBeforeTheBridgeExists(t *testing.T) {
	s := newQueuedSession(t)
	if got := s.bridgeBacklog(); got != 0 {
		t.Fatalf("bridgeBacklog() = %d before any bridge, want 0", got)
	}
	done := make(chan bool, 1)
	go func() { done <- s.waitBridgeRoom() }()
	select {
	case ok := <-done:
		if !ok {
			t.Fatal("waitBridgeRoom returned false on an open session")
		}
	case <-time.After(time.Second):
		t.Fatal("waitBridgeRoom held with nothing below it")
	}
}
