// Regression tests for the reconnect and lifecycle defects fixed in the
// engine. Each test names the failure mode it pins down so a future regression
// points back at the original bug rather than at an opaque assertion.
package jitsi

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/zarazaex69/j"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
)

// TestStaleRecvLoopDoesNotClearFreshBridge pins the reconnect flap: a recvLoop
// belonging to the previous connection notices its bridge closed only after a
// successful reconnect already republished the bridge. It must not clear
// bridgeReady, otherwise every Send fails with ErrBridgeNotReady until the
// next reconnect.
func TestStaleRecvLoopDoesNotClearFreshBridge(t *testing.T) {
	js := newSilentSession(t)
	js.SetShouldReconnect(func() bool { return true })

	// Generation of the connection the stale loop belongs to.
	js.markBridgeReady()
	staleGen := js.bridgeGen.Load()

	// A reconnect completes and republishes the bridge.
	js.markBridgeReady()

	// Only now does the stale loop report its own bridge closed.
	if js.deliverBridgeMessageGen(staleGen, j.BridgeMessage{}, false) {
		t.Fatal("deliverBridgeMessageGen returned true on closed bridge")
	}

	if !js.bridgeReady.Load() {
		t.Fatal("stale recvLoop cleared bridgeReady of the newer connection")
	}
	if reconnectQueued(js) {
		t.Fatal("stale recvLoop queued a reconnect for a superseded bridge")
	}
	if err := js.Send([]byte("payload")); err != nil {
		t.Fatalf("Send after stale bridge close: %v, want nil", err)
	}
}

// TestLiveRecvLoopStillRequestsReconnect is the counterpart: the loop of the
// live bridge must still be able to report the close.
func TestLiveRecvLoopStillRequestsReconnect(t *testing.T) {
	js := newSilentSession(t)
	js.SetShouldReconnect(func() bool { return true })
	js.markBridgeReady()

	if js.deliverBridgeMessageGen(js.bridgeGen.Load(), j.BridgeMessage{}, false) {
		t.Fatal("deliverBridgeMessageGen returned true on closed bridge")
	}
	if !reconnectQueued(js) {
		t.Fatal("live bridge close did not request a reconnect")
	}
	if js.bridgeReady.Load() {
		t.Fatal("claimed reconnect must clear bridgeReady")
	}
}

// TestRequestReconnectKeepsBridgeReadyWhenNotClaimed verifies that a request
// which cannot be claimed (the single slot is already taken) leaves the flag
// alone: only the claim the supervisor will actually service closes the
// bridge.
func TestRequestReconnectKeepsBridgeReadyWhenNotClaimed(t *testing.T) {
	js := newSilentSession(t)
	js.SetShouldReconnect(func() bool { return true })

	// Occupy the single reconnect slot.
	if got := js.Request(false, false); got != engine.ReconnectQueued {
		t.Fatalf("reconnect request = %v, want queued", got)
	}
	js.markBridgeReady()

	js.requestReconnect("second request")
	if !js.bridgeReady.Load() {
		t.Fatal("unclaimed reconnect request cleared bridgeReady")
	}
}

// TestTrickleCancelIsGuardedByPCMu races negotiatePC's trickle bookkeeping
// against a concurrent teardownPC. Under -race this catches the
// unsynchronised read/write of trickleCancel between the waitForJingle and
// WatchConnection goroutines.
func TestTrickleCancelIsGuardedByPCMu(t *testing.T) {
	js := newSilentSession(t)

	var wg sync.WaitGroup
	wg.Go(func() {
		for range 200 {
			_, cancel := context.WithCancel(context.Background())
			js.setTrickleCancel(cancel)
		}
	})
	wg.Go(func() {
		for range 200 {
			js.teardownPC()
		}
	})
	wg.Wait()

	js.teardownPC()
	js.pcMu.Lock()
	defer js.pcMu.Unlock()
	if js.trickleCancel != nil {
		t.Fatal("teardownPC left trickleCancel installed")
	}
}

// TestSetTrickleCancelCancelsReplacedLoop confirms that installing a new
// trickle loop stops the one it replaces, so a drain loop bound to a dead PC
// never outlives its peer connection.
func TestSetTrickleCancelCancelsReplacedLoop(t *testing.T) {
	js := newSilentSession(t)

	ctx, cancel := context.WithCancel(context.Background())
	js.setTrickleCancel(cancel)
	if ctx.Err() != nil {
		t.Fatal("first trickle context cancelled on install")
	}

	_, next := context.WithCancel(context.Background())
	js.setTrickleCancel(next)
	select {
	case <-ctx.Done():
	case <-time.After(time.Second):
		t.Fatal("replaced trickle loop was not cancelled")
	}
	js.teardownPC()
}

// TestCallbackSettersAreRaceFree hammers the callback setters against the
// goroutines that read them. Before the fix these were plain fields written by
// the provider and read from the supervisor and keepalive goroutines.
func TestCallbackSettersAreRaceFree(t *testing.T) {
	js := newSilentSession(t)

	stop := make(chan struct{})
	var wg sync.WaitGroup
	wg.Go(func() {
		for {
			select {
			case <-stop:
				return
			default:
			}
			js.SetShouldReconnect(func() bool { return true })
			js.SetEndedCallback(func(string) {})
			js.SetReconnectCallback(func() {})
		}
	})
	wg.Go(func() {
		for {
			select {
			case <-stop:
				return
			default:
			}
			js.requestReconnect("race")
			drainReconnectChNonBlocking(js)
			js.signalEnded("race")
			js.notifyReconnect()
		}
	})

	time.Sleep(100 * time.Millisecond)
	close(stop)
	wg.Wait()
}

// TestNilCallbacksAreSafe guards the atomic-pointer storage: clearing a
// callback must disable it rather than store a pointer to a nil func that
// panics when called.
func TestNilCallbacksAreSafe(t *testing.T) {
	js := newSilentSession(t)

	js.SetShouldReconnect(nil)
	js.SetEndedCallback(nil)
	js.SetReconnectCallback(nil)

	if !js.reconnectAllowed() {
		t.Fatal("reconnectAllowed() = false without a policy, want true")
	}
	js.signalEnded("no callback")
	js.notifyReconnect()
}

// TestCloseStopsGoroutineLaunches pins the WaitGroup contract: once Close has
// decided the session is done, no goroutine may be added any more. A late
// wg.Add racing wg.Wait either panics with "WaitGroup misuse" or lets Close
// return while a fresh goroutine is starting.
func TestCloseStopsGoroutineLaunches(t *testing.T) {
	js := newSilentSession(t)

	started := make(chan struct{}, 1)
	if err := js.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}

	js.goLaunch(func() { started <- struct{}{} })
	select {
	case <-started:
		t.Fatal("goLaunch started a goroutine after Close")
	case <-time.After(100 * time.Millisecond):
	}
}

// TestConcurrentCloseAndLaunch races Close against the goroutine launchers the
// reconnect paths use. It must neither panic nor leak.
func TestConcurrentCloseAndLaunch(t *testing.T) {
	js := newSilentSession(t)

	var wg sync.WaitGroup
	for range 8 {
		wg.Go(func() {
			for range 50 {
				js.goLaunch(func() { time.Sleep(time.Millisecond) })
			}
		})
	}
	wg.Go(func() { _ = js.Close() })
	wg.Wait()
}

// TestWaitJSessionWakesOnInstall pins the send-loop signalling: waitJSession
// must return as soon as a session is installed instead of polling for it.
func TestWaitJSessionWakesOnInstall(t *testing.T) {
	js := newSilentSession(t)
	js.setJSession(nil)

	type result struct {
		sess    *j.Session
		elapsed time.Duration
	}
	done := make(chan result, 1)
	go func() {
		start := time.Now()
		sess := js.waitJSession()
		done <- result{sess: sess, elapsed: time.Since(start)}
	}()

	time.Sleep(20 * time.Millisecond)
	js.setJSession(&j.Session{})

	select {
	case res := <-done:
		if res.sess == nil {
			t.Fatal("waitJSession returned nil after a session was installed")
		}
		if res.elapsed >= jSessionWaitTimeout {
			t.Fatalf("waitJSession took %s, want a prompt wake-up", res.elapsed)
		}
	case <-time.After(jSessionWaitTimeout + time.Second):
		t.Fatal("waitJSession did not wake on install")
	}
	js.setJSession(nil)
}

// TestWaitJSessionIsBounded pins the queue-overflow fix: sendLoop is the only
// consumer of both send queues, so it may not block for a whole reconnect.
func TestWaitJSessionIsBounded(t *testing.T) {
	js := newSilentSession(t)
	js.setJSession(nil)

	start := time.Now()
	if sess := js.waitJSession(); sess != nil {
		t.Fatal("waitJSession returned a session while none was installed")
	}
	elapsed := time.Since(start)
	if elapsed < jSessionWaitTimeout {
		t.Fatalf("waitJSession returned after %s, want at least %s", elapsed, jSessionWaitTimeout)
	}
	if elapsed > jSessionWaitTimeout+2*time.Second {
		t.Fatalf("waitJSession took %s, want a bounded wait of %s", elapsed, jSessionWaitTimeout)
	}
}

// TestWaitJSessionReturnsOnClose keeps shutdown prompt: a closed session must
// not hold sendLoop for the whole bounded wait.
func TestWaitJSessionReturnsOnClose(t *testing.T) {
	js := newSilentSession(t)
	js.setJSession(nil)

	done := make(chan struct{})
	go func() {
		js.waitJSession()
		close(done)
	}()
	time.Sleep(10 * time.Millisecond)
	_ = js.Close()

	select {
	case <-done:
	case <-time.After(jSessionWaitTimeout):
		t.Fatal("waitJSession ignored session close")
	}
}

// TestSetJSessionRearmsSignal covers the reconnect cycle: after the session is
// torn down, the next waiter must block again instead of reading a stale
// "ready" signal and returning nil.
func TestSetJSessionRearmsSignal(t *testing.T) {
	js := newSilentSession(t)

	js.setJSession(&j.Session{})
	if js.waitJSession() == nil {
		t.Fatal("waitJSession returned nil while a session was installed")
	}

	if old := js.setJSession(nil); old == nil {
		t.Fatal("setJSession(nil) did not return the replaced session")
	}
	start := time.Now()
	if js.waitJSession() != nil {
		t.Fatal("waitJSession returned a session after teardown")
	}
	if elapsed := time.Since(start); elapsed < jSessionWaitTimeout {
		t.Fatalf("waitJSession returned after %s without blocking; signal was not rearmed", elapsed)
	}
}

// TestParseEpochFrameSharedValidation pins the header validation shared by the
// broadcast and the per-peer receive paths: both must reject the same frames.
func TestParseEpochFrameSharedValidation(t *testing.T) {
	js := newSilentSession(t)
	js.localEpoch.Store(0x4444)

	tests := []struct {
		name  string
		frame string
		want  bool
	}{
		{"short frame", encodeForTest(t, bridgeMagic[:]), false},
		{"zero sender epoch", makeBridgeFrameForEpoch(t, 0, 0x4444, []byte("x")), false},
		{"own echo", makeBridgeFrameForEpoch(t, 0x4444, 0, []byte("x")), false},
		{"addressed to old incarnation", makeBridgeFrameForEpoch(t, 0x1111, 0x9999, []byte("x")), false},
		{"broadcast", makeBridgeFrameForEpoch(t, 0x1111, 0, []byte("x")), true},
		{"targeted", makeBridgeFrameForEpoch(t, 0x1111, 0x4444, []byte("x")), true},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			payload := decodeRaw(makeBridgeMessageFrom("peerA",
				map[string]any{rawFieldKey: tc.frame}))
			if _, got := js.parseEpochFrame(payload); got != tc.want {
				t.Fatalf("parseEpochFrame ok = %v, want %v", got, tc.want)
			}
			if _, got := js.acceptPeerEpochFrame("peerA", payload); got != tc.want {
				t.Fatalf("acceptPeerEpochFrame ok = %v, want %v (shared validation)", got, tc.want)
			}
			if _, got := js.acceptEpochFrame(payload); got != tc.want {
				t.Fatalf("acceptEpochFrame ok = %v, want %v (shared validation)", got, tc.want)
			}
			js.ResetPeer()
		})
	}
}

// TestLatchPeerEndpointIgnoresBroadcast keeps the broadcast exemption after
// the rename: an empty sender is a JVB broadcast (our own echo) and must not
// re-bind the latch.
func TestLatchPeerEndpointIgnoresBroadcast(t *testing.T) {
	js := newSilentSession(t)

	js.latchPeerEndpoint("peerA")
	js.latchPeerEndpoint("")

	got := js.peerEndpoint.Load()
	if got == nil || *got != "peerA" {
		t.Fatalf("peerEndpoint = %v, want peerA", got)
	}

	js.latchPeerEndpoint("peerB")
	got = js.peerEndpoint.Load()
	if got == nil || *got != "peerB" {
		t.Fatalf("peerEndpoint after re-latch = %v, want peerB", got)
	}
}
