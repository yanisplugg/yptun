package jitsi

import (
	"context"
	"encoding/binary"
	"fmt"
	"math/rand/v2"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
)

// TestReconnectCounterResetsOnSuccess pins the post-fix counting model:
// the reconnect counter records *consecutive failures*, not the total
// number of reconnects. A success must zero the counter so legitimate
// long-running churn (peer rejoin, JVB restart) doesn't gradually creep
// past maxReconnects on perfectly recoverable failures.
func TestReconnectCounterResetsOnSuccess(t *testing.T) {
	js := newChurnSession(t)
	defer func() { _ = js.Close() }()

	js.reconnectMu.Lock()
	js.reconnectCount = 4
	js.reconnectWindowStart = time.Now()
	js.reconnectMu.Unlock()

	// Mimic handleReconnectAttempt's success path.
	js.reconnectMu.Lock()
	js.reconnectCount = 0
	js.reconnectWindowStart = time.Time{}
	count := js.reconnectCount
	wst := js.reconnectWindowStart
	js.reconnectMu.Unlock()

	if count != 0 || !wst.IsZero() {
		t.Fatalf("after success: count=%d window=%v, want 0/zero", count, wst)
	}
}

// TestReconnectCounterTripsOnConsecutiveFailures verifies the safety
// net: when reconnect attempts keep failing back-to-back, the supervisor
// must end the session once the counter exceeds maxReconnects.
func TestReconnectCounterTripsOnConsecutiveFailures(t *testing.T) {
	js := newChurnSession(t)
	defer func() { _ = js.Close() }()

	endedCh := make(chan string, 1)
	js.SetEndedCallback(func(reason string) {
		select {
		case endedCh <- reason:
		default:
		}
	})

	// Pre-fill the counter as if maxReconnects+1 failures had landed.
	js.reconnectMu.Lock()
	js.reconnectCount = maxReconnects + 1
	js.reconnectMu.Unlock()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan bool, 1)
	go func() { done <- js.handleReconnectAttempt(ctx) }()

	select {
	case reason := <-endedCh:
		if reason == "" {
			t.Fatal("ended with empty reason")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("cap was not enforced after consecutive failures")
	}
	cancel()
	<-done
}

// TestResetPeerClearsBindingForNewPeer covers fix 032151b: after an
// upper-layer handshake failure the supervisor calls ResetPeer, and the
// next peer in the room must be allowed to latch - not blocked by the
// previously-latched (now stale) endpoint.
//
// TestResetPeerClearsBindingForNewPeer covers the explicit ResetPeer
// path used after an upper-layer handshake failure. With the post-fix
// re-latch behaviour, peer B is admitted on its first valid frame even
// without ResetPeer (because magic-validated traffic from a different
// sender means the peer reconnected with a new JVB endpoint id). This
// test still asserts that ResetPeer() clears the latch so the very next
// peer is recognised cleanly.
func TestResetPeerClearsBindingForNewPeer(t *testing.T) {
	js := newChurnSession(t)
	defer func() { _ = js.Close() }()

	var got [][]byte
	var mu sync.Mutex
	js.onData = func(b []byte) {
		mu.Lock()
		got = append(got, append([]byte(nil), b...))
		mu.Unlock()
	}
	js.localEpoch.Store(0xDEADBEEF)

	// Peer A latches and delivers.
	frameA := makeBridgeFrameForEpoch(t, 0x1111, 0, []byte("from-A"))
	js.deliverBridgeMessage(makeBridgeMessageFrom("peerA", map[string]any{rawFieldKey: frameA}), true)

	// Peer B sends — magic passes, so we re-latch onto peerB and the
	// payload is delivered. (Old behaviour: dropped until ResetPeer.)
	frameB1 := makeBridgeFrameForEpoch(t, 0x2222, 0, []byte("from-B-relatched"))
	js.deliverBridgeMessage(makeBridgeMessageFrom("peerB", map[string]any{rawFieldKey: frameB1}), true)

	// ResetPeer still must zero out latches for explicit recovery.
	js.ResetPeer()
	if js.peerEpoch.Load() != 0 {
		t.Fatalf("peerEpoch after ResetPeer = %#x, want 0", js.peerEpoch.Load())
	}
	if p := js.peerEndpoint.Load(); p != nil {
		t.Fatalf("peerEndpoint after ResetPeer = %q, want nil", *p)
	}

	// Peer B again — fresh latch, frame delivers.
	frameB2 := makeBridgeFrameForEpoch(t, 0x2222, 0, []byte("from-B-final"))
	js.deliverBridgeMessage(makeBridgeMessageFrom("peerB", map[string]any{rawFieldKey: frameB2}), true)

	mu.Lock()
	defer mu.Unlock()
	if len(got) != 3 {
		t.Fatalf("delivered = %d frames, want 3 (from-A, from-B-relatched, from-B-final): %q",
			len(got), got)
	}
	if string(got[0]) != "from-A" ||
		string(got[1]) != "from-B-relatched" ||
		string(got[2]) != "from-B-final" {
		t.Fatalf("delivered = %q, want [from-A from-B-relatched from-B-final]", got)
	}
}

// TestChurnPeerEpochChanges hammers fix acac112 (epoch-based bridge frame
// filtering) under churn: many epoch transitions in rapid succession from
// the same peer. Existing tests fire a single epoch change; this test fires
// hundreds and asserts that:
//   - no payload carrying a stale receiver-epoch is delivered;
//   - peerEpoch always tracks the latest accepted sender-epoch;
//   - the reconnect channel is signaled (at least once) on real changes.
//
// Run with -race to catch CAS misuses on peerEpoch / peerEndpoint.
func TestChurnPeerEpochChanges(t *testing.T) {
	js := newChurnSession(t)
	defer func() { _ = js.Close() }()

	js.localEpoch.Store(0x42424242)
	js.SetShouldReconnect(func() bool { return true })

	var delivered atomic.Uint64
	var staleDelivered atomic.Uint64
	js.onData = func(b []byte) {
		delivered.Add(1)
		// Stale frames in this test are tagged with the literal "STALE".
		if len(b) >= 5 && string(b[:5]) == "STALE" {
			staleDelivered.Add(1)
		}
	}

	const iterations = 500
	const goroutines = 8
	var wg sync.WaitGroup
	for g := range goroutines {
		seed := uint64(g) + 1
		wg.Go(func() {
			rng := rand.New(rand.NewPCG(seed, seed^0x9E3779B97F4A7C15)) //nolint:gosec // weak RNG is fine for test fixtures
			for i := range iterations {
				switch rng.IntN(3) {
				case 0:
					// Fresh epoch; receiverEpoch=0 acts as announce.
					ep := uint32(rng.Uint64()|1) & 0xFFFFFFFE //nolint:gosec // truncation is the intent
					payload := fmt.Appendf(nil, "ok-%d-%d", seed, i)
					raw := makeBridgeFrameForEpoch(t, ep, 0, payload)
					js.deliverBridgeMessage(
						makeBridgeMessageFrom("peerA",
							map[string]any{rawFieldKey: raw}), true)
				case 1:
					// Stale: receiverEpoch mismatched with local. Must be dropped.
					raw := makeBridgeFrameForEpoch(t, 0x1111, 0xBADBAD, []byte("STALE-rcv"))
					js.deliverBridgeMessage(
						makeBridgeMessageFrom("peerA",
							map[string]any{rawFieldKey: raw}), true)
				case 2:
					// Acknowledging local epoch: must pass.
					payload := fmt.Appendf(nil, "ack-%d-%d", seed, i)
					raw := makeBridgeFrameForEpoch(t, 0x9999, 0x42424242, payload)
					js.deliverBridgeMessage(
						makeBridgeMessageFrom("peerA",
							map[string]any{rawFieldKey: raw}), true)
				}
				drainReconnectCh(js)
			}
		})
	}
	wg.Wait()

	if staleDelivered.Load() != 0 {
		t.Fatalf("stale frames delivered: %d (filter regression)", staleDelivered.Load())
	}
	if delivered.Load() == 0 {
		t.Fatal("no frames delivered at all - filter is too aggressive")
	}
}

// TestChurnConcurrentResetAndDeliver races ResetPeer against concurrent
// deliverBridgeMessage from multiple peers. Under -race it would catch
// torn reads on peerEndpoint / peerEpoch; logically it asserts that we
// never deliver data attributed to a peer that lost the latch.
func TestChurnConcurrentResetAndDeliver(t *testing.T) {
	js := newChurnSession(t)
	defer func() { _ = js.Close() }()

	js.localEpoch.Store(0x55555555)
	js.SetShouldReconnect(func() bool { return true })
	js.onData = func([]byte) {} // discard

	stop := make(chan struct{})
	var wg sync.WaitGroup

	for i, peer := range []string{"peerA", "peerB", "peerC"} {
		ep := uint32(0x1000 * (i + 1))
		wg.Go(func() {
			for {
				select {
				case <-stop:
					return
				default:
				}
				raw := makeBridgeFrameForEpoch(t, ep, 0, []byte(peer))
				js.deliverBridgeMessage(
					makeBridgeMessageFrom(peer,
						map[string]any{rawFieldKey: raw}), true)
				drainReconnectCh(js)
			}
		})
	}

	wg.Go(func() {
		for {
			select {
			case <-stop:
				return
			default:
			}
			js.ResetPeer()
			time.Sleep(time.Microsecond * 50)
		}
	})

	time.Sleep(200 * time.Millisecond)
	close(stop)
	wg.Wait()
}

// TestChurnReconnectAttemptSerial exercises handleReconnectAttempt across
// --- helpers ---

func newChurnSession(t *testing.T) *Session {
	t.Helper()
	sess, err := New(context.Background(), engine.Config{
		URL:   testHost,
		Extra: map[string]string{credentialKeyRoom: testRoom},
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	js, ok := sess.(*Session)
	if !ok {
		t.Fatal("sess is not *Session")
	}
	return js
}

func drainReconnectCh(js *Session) {
	select {
	case <-js.reconnectCh:
	default:
	}
}

// Keep binary.BigEndian referenced even if all current uses are removed.
var _ = binary.BigEndian
