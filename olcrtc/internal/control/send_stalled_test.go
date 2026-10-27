package control

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"time"
)

// A link whose pongs are perfect but which cannot carry payload is dead, and
// liveness is the only thing positioned to say so.
//
// This is the phone bug: data and control ride separate KCP sessions, so the
// control plane answered every ping on time while the data plane had not
// accepted a byte in half a minute. smux treats the write timeout as a
// deadline rather than a fault, so the session stayed open; the tunnel
// reported healthy and carried nothing, indefinitely, because a reconnect only
// ever follows the control stream dying.
func TestSendStalledEndsAnOtherwiseHealthySession(t *testing.T) {
	var stalled atomic.Bool
	var reported atomic.Int32

	a, b := controlPair(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// A real peer on the other end, so the pongs really are perfect and the
	// only thing that can end this session is the stall.
	go func() { _ = Run(ctx, b, Config{Interval: 10 * time.Millisecond, Timeout: time.Second, Failures: 100}) }()

	done := make(chan error, 1)
	go func() {
		done <- Run(ctx, a, Config{
			Interval:      10 * time.Millisecond,
			Timeout:       time.Second,
			Failures:      100,
			SendStalled:   stalled.Load,
			OnSendStalled: func() { reported.Add(1) },
		})
	}()

	// Healthy for a while: the session must stay up.
	select {
	case err := <-done:
		t.Fatalf("Run returned while the link was healthy: %v", err)
	case <-time.After(100 * time.Millisecond):
	}

	stalled.Store(true)
	select {
	case err := <-done:
		if !errors.Is(err, ErrUnhealthy) {
			t.Fatalf("Run error = %v, want %v", err, ErrUnhealthy)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("a data plane that stopped sending never ended the session")
	}
	if reported.Load() != 1 {
		t.Fatalf("OnSendStalled called %d times, want 1", reported.Load())
	}
}

// Nil keeps the old behaviour exactly: no check, no teardown.
func TestSendStalledNilIsInert(t *testing.T) {
	a, b := controlPair(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	cfg := Config{Interval: 10 * time.Millisecond, Timeout: time.Second, Failures: 100}
	go func() { _ = Run(ctx, b, cfg) }()

	done := make(chan error, 1)
	go func() { done <- Run(ctx, a, cfg) }()

	select {
	case err := <-done:
		t.Fatalf("Run returned with no stall check configured: %v", err)
	case <-time.After(150 * time.Millisecond):
	}
}
