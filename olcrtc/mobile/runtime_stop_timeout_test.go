package mobile

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/pkg/olcrtc/client"
)

// A generation that ignores cancellation must not cost the runtime its ability
// to start another one.
//
// This is the phone bug: switching rooms while the transport was wedged left
// "olcRTC runtime is already active" on every later attempt, with no way back
// short of force-stopping the app. A wedged transport reaches it by the
// ordinary route — its writes take 30 s each to fail, and Stop is given 5.
func TestStopTimeoutLeavesTheRuntimeStartable(t *testing.T) {
	stuck := make(chan struct{})
	t.Cleanup(func() { close(stuck) })
	started := make(chan struct{}, 2)

	runtime := configuredRuntime(t, func(_ context.Context, _ client.Config, onReady func(string)) error {
		started <- struct{}{}
		onReady("127.0.0.1:1080")
		<-stuck // deaf to the context on purpose: a transport that will not let go
		return nil
	})

	if err := runtime.Start(); err != nil {
		t.Fatalf("first Start() error = %v", err)
	}
	if err := runtime.WaitReady(2000); err != nil {
		t.Fatalf("WaitReady() error = %v", err)
	}
	if err := runtime.Stop(200); !errors.Is(err, ErrStopTimeout) {
		t.Fatalf("Stop() error = %v, want %v", err, ErrStopTimeout)
	}

	if state := runtime.State(); state != string(stateStopped) {
		t.Fatalf("State() after a missed deadline = %q, want stopped", state)
	}
	if runtime.IsRunning() {
		t.Fatal("an abandoned generation must not keep the runtime active")
	}
	if err := runtime.Start(); err != nil {
		t.Fatalf("Start() after a timed-out Stop() error = %v, want nil", err)
	}

	for i := range 2 {
		select {
		case <-started:
		case <-time.After(2 * time.Second):
			t.Fatalf("generation %d never ran", i+1)
		}
	}
}

// The abandoned generation finishing later must not disturb its successor.
func TestAbandonedGenerationDoesNotClobberItsSuccessor(t *testing.T) {
	// One channel per generation, so the abandoned one can finish while the
	// live one is still running. Sharing a channel releases both and the test
	// then proves nothing.
	release := make(chan struct{})
	stayUp := make(chan struct{})
	t.Cleanup(func() { close(stayUp) })

	var calls atomic.Int32
	runtime := configuredRuntime(t, func(_ context.Context, _ client.Config, onReady func(string)) error {
		first := calls.Add(1) == 1
		onReady("127.0.0.1:1080")
		// Deaf to the context, so the first Stop is guaranteed to miss.
		if first {
			<-release
		} else {
			<-stayUp
		}
		return nil
	})

	if err := runtime.Start(); err != nil {
		t.Fatalf("first Start() error = %v", err)
	}
	if err := runtime.WaitReady(2000); err != nil {
		t.Fatalf("WaitReady() error = %v", err)
	}
	if err := runtime.Stop(200); !errors.Is(err, ErrStopTimeout) {
		t.Fatalf("Stop() error = %v, want %v", err, ErrStopTimeout)
	}
	if err := runtime.Start(); err != nil {
		t.Fatalf("second Start() error = %v", err)
	}
	if err := runtime.WaitReady(2000); err != nil {
		t.Fatalf("second WaitReady() error = %v", err)
	}

	// Releasing both: the abandoned generation finishes first and must not be
	// the one whose result is written to the runtime.
	close(release)
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		if state := runtime.State(); state != string(stateRunning) {
			t.Fatalf("the live generation stopped reading as running: %q", state)
		}
		time.Sleep(20 * time.Millisecond)
	}
	if !runtime.IsRunning() {
		t.Fatal("the live generation should still be active")
	}
}
