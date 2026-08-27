package goolom

import (
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
)

func TestSessionReconnectAndEndedHelpers(t *testing.T) {
	s := &Session{
		closeCh:        make(chan struct{}),
		keepAliveCh:    make(chan struct{}),
		sessionCloseCh: make(chan struct{}),
		telemetryCh:    make(chan struct{}, 1),
	}

	keepAliveCh, sessionCloseCh := s.resetSession()
	if keepAliveCh == nil || sessionCloseCh == nil || keepAliveCh != s.keepAliveCh || sessionCloseCh != s.sessionCloseCh {
		t.Fatal("resetSession() did not replace session channels")
	}

	s.subscriberReady.Store(true)
	s.publisherReady.Store(true)
	s.resetMediaState()
	if s.subscriberReady.Load() || s.publisherReady.Load() || s.subscriberConnCh() == nil {
		t.Fatal("resetMediaState() did not reset readiness")
	}

	s.queueReconnect()
	if !s.Drain() {
		t.Fatal("queueReconnect() did not enqueue")
	}

	s.SetShouldReconnect(func() bool { return false })
	s.queueReconnect()
	if s.Drain() {
		t.Fatal("queueReconnect() enqueued despite policy=false")
	}

	s.SetShouldReconnect(nil)
	if got := s.Request(false, false); got != engine.ReconnectQueued {
		t.Fatalf("first reconnect request = %v, want queued", got)
	}
	if got := s.Request(false, false); got != engine.ReconnectCoalesced {
		t.Fatalf("second reconnect request = %v, want coalesced", got)
	}
	if !s.Drain() || s.Drain() {
		t.Fatal("Drain() did not consume exactly one queued request")
	}

	s.telemetryActive.Store(true)
	s.stopTelemetry()
	select {
	case <-s.telemetryCh:
	default:
		t.Fatal("stopTelemetry() did not signal active telemetry")
	}

	ended := ""
	s.SetEndedCallback(func(reason string) { ended = reason })
	s.signalEnded("done")
	if !s.closed.Load() || ended != "done" {
		t.Fatalf("signalEnded() closed=%v reason=%q", s.closed.Load(), ended)
	}
}

func TestWaitForAckTimeoutAndClose(t *testing.T) {
	s := &Session{
		closeCh:    make(chan struct{}),
		ackWaiters: make(map[string]chan struct{}),
	}
	ch := s.registerAckWaiter("timeout")
	if s.waitForAck("timeout", ch, time.Millisecond) {
		t.Fatal("waitForAck(timeout) = true")
	}

	ch = s.registerAckWaiter("closed")
	close(s.closeCh)
	if s.waitForAck("closed", ch, time.Second) {
		t.Fatal("waitForAck(closeCh) = true")
	}
}
