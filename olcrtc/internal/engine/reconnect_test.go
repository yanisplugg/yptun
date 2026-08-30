package engine

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestReconnectorRequestWindowReset(t *testing.T) {
	now := time.Date(2026, time.August, 11, 12, 0, 0, 0, time.UTC)
	var calls atomic.Int32
	r := NewReconnector(ReconnectorConfig{
		MaxAttempts: 2,
		Reconnect: func(context.Context) error {
			calls.Add(1)
			return nil
		},
	})
	r.now = func() time.Time { return now }
	r.count = 2
	r.lastRequest = now.Add(-reconnectFailureWindow - time.Second)

	if terminal := r.handleAttempt(t.Context(), nil); terminal {
		t.Fatal("expired request window ended reconnect loop")
	}
	if calls.Load() != 1 || r.count != 1 {
		t.Fatalf("calls=%d count=%d, want 1/1", calls.Load(), r.count)
	}
}

func TestReconnectorFailureWindowReset(t *testing.T) {
	now := time.Date(2026, time.August, 11, 12, 0, 0, 0, time.UTC)
	r := NewReconnector(ReconnectorConfig{
		MaxAttempts:   2,
		CountFailures: true,
		Reconnect:     func(context.Context) error { return nil },
	})
	r.now = func() time.Time { return now }
	r.count = 3
	r.windowStart = now.Add(-reconnectFailureWindow - time.Second)

	if terminal := r.handleAttempt(t.Context(), nil); terminal {
		t.Fatal("expired failure window ended reconnect loop")
	}
	if r.count != 0 || !r.windowStart.IsZero() {
		t.Fatalf("count=%d window=%v, want reset", r.count, r.windowStart)
	}
}

func TestReconnectorMaxAttempts(t *testing.T) {
	now := time.Date(2026, time.August, 11, 12, 0, 0, 0, time.UTC)
	ended := ""
	r := NewReconnector(ReconnectorConfig{
		MaxAttempts: 2,
		Reconnect: func(context.Context) error {
			t.Fatal("reconnect called after limit")
			return nil
		},
		OnLimit:     func(reason string) { ended = reason },
		LimitReason: "limit",
	})
	r.now = func() time.Time { return now }
	r.count = 2
	r.lastRequest = now

	if terminal := r.handleAttempt(t.Context(), nil); !terminal {
		t.Fatal("reconnect loop continued after limit")
	}
	if ended != "limit" {
		t.Fatalf("ended reason = %q, want limit", ended)
	}
}

func TestReconnectorCoalescesAndGuardsRequests(t *testing.T) {
	r := NewReconnector(ReconnectorConfig{})
	if got := r.Request(true, false); got != ReconnectRejected {
		t.Fatalf("closed request = %v, want rejected", got)
	}
	if got := r.Request(false, true); got != ReconnectRejected {
		t.Fatalf("active request = %v, want rejected", got)
	}
	r.SetShouldReconnect(func() bool { return false })
	if got := r.Request(false, false); got != ReconnectRejected {
		t.Fatalf("policy request = %v, want rejected", got)
	}

	r.SetShouldReconnect(nil)
	if got := r.Request(false, false); got != ReconnectQueued {
		t.Fatalf("first request = %v, want queued", got)
	}
	if got := r.Request(false, false); got != ReconnectCoalesced {
		t.Fatalf("second request = %v, want coalesced", got)
	}
	if !r.Drain() || r.Drain() {
		t.Fatal("queue did not contain exactly one request")
	}
}

func TestReconnectorCancellationStopsBackoff(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	r := NewReconnector(ReconnectorConfig{
		MaxAttempts: 1,
		Reconnect: func(context.Context) error {
			cancel()
			return errors.New("failed")
		},
	})
	start := time.Now()
	if terminal := r.handleAttempt(ctx, nil); !terminal {
		t.Fatal("cancelled reconnect loop continued")
	}
	if elapsed := time.Since(start); elapsed > time.Second {
		t.Fatalf("cancellation took %s", elapsed)
	}
}

func TestReconnectorCallbacksAreRaceSafe(t *testing.T) {
	r := NewReconnector(ReconnectorConfig{})
	var calls atomic.Int32
	var wg sync.WaitGroup
	for range 4 {
		wg.Go(func() {
			for range 500 {
				r.SetReconnectCallback(func() { calls.Add(1) })
				r.SetShouldReconnect(func() bool { return true })
				r.SetEndedCallback(func(string) { calls.Add(1) })
			}
		})
		wg.Go(func() {
			for range 500 {
				r.Request(false, false)
				r.Drain()
				r.NotifyReconnect()
				r.SignalEnded("done")
			}
		})
	}
	wg.Wait()
	if calls.Load() == 0 {
		t.Fatal("callbacks were never invoked")
	}
}
