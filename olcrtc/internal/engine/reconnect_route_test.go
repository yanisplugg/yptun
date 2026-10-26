package engine

import (
	"context"
	"sync/atomic"
	"testing"
	"time"
)

func newRouteGatedReconnector(probe func() bool, reconnect func(context.Context) error) *Reconnector {
	r := NewReconnector(ReconnectorConfig{
		MaxAttempts: 3,
		Reconnect:   reconnect,
		RouteProbe:  probe,
	})
	r.noRouteProbeInitial = time.Millisecond
	r.noRouteProbeMax = 2 * time.Millisecond
	return r
}

// While the host protector refuses sockets there is no interface to dial
// from: an attempt would open a hundred sockets to learn nothing and count
// towards the limit that ends the session. The attempt waits for the probe.
func TestReconnectorWaitsForRouteBeforeAttempt(t *testing.T) {
	var probes, calls atomic.Int32
	r := newRouteGatedReconnector(
		func() bool { return probes.Add(1) >= 3 },
		func(context.Context) error { calls.Add(1); return nil },
	)
	if terminal := r.handleAttempt(t.Context(), nil); terminal {
		t.Fatal("a reconnect that succeeded ended the loop")
	}
	if probes.Load() != 3 || calls.Load() != 1 {
		t.Fatalf("probes=%d calls=%d, want 3/1", probes.Load(), calls.Load())
	}
	if r.count != 1 {
		t.Fatalf("count=%d, want 1: waiting for a route is not an attempt", r.count)
	}
}

// The wait ends with the context, before any attempt.
func TestReconnectorRouteWaitEndsWithContext(t *testing.T) {
	var calls atomic.Int32
	r := newRouteGatedReconnector(
		func() bool { return false },
		func(context.Context) error { calls.Add(1); return nil },
	)
	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		time.Sleep(20 * time.Millisecond)
		cancel()
	}()
	started := time.Now()
	if terminal := r.handleAttempt(ctx, nil); !terminal {
		t.Fatal("cancelled route wait continued the loop")
	}
	if elapsed := time.Since(started); elapsed > time.Second {
		t.Fatalf("cancellation took %s", elapsed)
	}
	if calls.Load() != 0 {
		t.Fatalf("calls=%d, want none while there is no route", calls.Load())
	}
}

// A route that never comes back must not hold the session open forever: past
// noRouteMaxWait a real attempt runs and counts, and the limit still ends it.
func TestReconnectorRouteWaitGivesUpAfterMaxWait(t *testing.T) {
	now := time.Date(2026, time.September, 16, 8, 0, 0, 0, time.UTC)
	var calls atomic.Int32
	r := newRouteGatedReconnector(
		nil,
		func(context.Context) error { calls.Add(1); return nil },
	)
	r.now = func() time.Time { return now }
	r.routeProbe = func() bool {
		now = now.Add(r.noRouteMaxWait / 2)
		return false
	}
	if terminal := r.handleAttempt(t.Context(), nil); terminal {
		t.Fatal("a reconnect that succeeded ended the loop")
	}
	if calls.Load() != 1 {
		t.Fatalf("calls=%d, want the attempt once the wait is over", calls.Load())
	}
}

// Without a probe nothing is gated: desktop has no protector.
func TestReconnectorWithoutRouteProbeAttemptsAtOnce(t *testing.T) {
	var calls atomic.Int32
	r := NewReconnector(ReconnectorConfig{
		MaxAttempts: 1,
		Reconnect:   func(context.Context) error { calls.Add(1); return nil },
	})
	r.routeProbe = nil
	if terminal := r.handleAttempt(t.Context(), nil); terminal {
		t.Fatal("a reconnect that succeeded ended the loop")
	}
	if calls.Load() != 1 {
		t.Fatalf("calls=%d, want 1", calls.Load())
	}
}
