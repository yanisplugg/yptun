package mobile

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/pkg/olcrtc/client"
)

const (
	testRoom = "https://meet.example.org/room"
	testKey  = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
)

//nolint:gochecknoglobals,nolintlint // shared immutable sentinel keeps errors.Is assertions precise
var errTestRun = errors.New("test runner failed")

func configuredRuntime(t *testing.T, runner clientRunner) *Runtime {
	t.Helper()
	runtime := newRuntime(runner)
	if err := runtime.SetProvider("jitsi"); err != nil {
		t.Fatalf("SetProvider() error = %v", err)
	}
	if err := runtime.SetRoom(testRoom); err != nil {
		t.Fatalf("SetRoom() error = %v", err)
	}
	if err := runtime.SetKey(testKey); err != nil {
		t.Fatalf("SetKey() error = %v", err)
	}
	return runtime
}

func blockingReadyRunner(ctx context.Context, _ client.Config, onReady func(string)) error {
	onReady("127.0.0.1:1080")
	<-ctx.Done()
	return ctx.Err()
}

func TestRuntimeLifecycle(t *testing.T) {
	runtime := configuredRuntime(t, blockingReadyRunner)
	if runtime.State() != "idle" {
		t.Fatalf("State() = %q, want idle", runtime.State())
	}
	if err := runtime.Start(); err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	if err := runtime.Start(); !errors.Is(err, ErrAlreadyRunning) {
		t.Fatalf("second Start() error = %v, want %v", err, ErrAlreadyRunning)
	}
	if err := runtime.WaitReady(100); err != nil {
		t.Fatalf("WaitReady() error = %v", err)
	}
	if runtime.State() != "running" || !runtime.IsRunning() {
		t.Fatalf("state after ready = %q, running = %v", runtime.State(), runtime.IsRunning())
	}
	if err := runtime.Stop(100); err != nil {
		t.Fatalf("Stop() error = %v", err)
	}
	if runtime.State() != "stopped" || runtime.IsRunning() {
		t.Fatalf("state after stop = %q, running = %v", runtime.State(), runtime.IsRunning())
	}
	if err := runtime.Stop(1); err != nil {
		t.Fatalf("idempotent Stop() error = %v", err)
	}
}

func TestWaitReadyReturnsStartupError(t *testing.T) {
	runtime := configuredRuntime(t, func(context.Context, client.Config, func(string)) error {
		return errTestRun
	})
	if err := runtime.Start(); err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	if err := runtime.WaitReady(100); !errors.Is(err, errTestRun) {
		t.Fatalf("WaitReady() error = %v, want %v", err, errTestRun)
	}
}

func TestWaitReadyTimeout(t *testing.T) {
	runtime := configuredRuntime(t, func(ctx context.Context, _ client.Config, _ func(string)) error {
		<-ctx.Done()
		return ctx.Err()
	})
	if err := runtime.Start(); err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	if err := runtime.WaitReady(1); !errors.Is(err, ErrReadyTimeout) {
		t.Fatalf("WaitReady() error = %v, want %v", err, ErrReadyTimeout)
	}
	if err := runtime.Stop(100); err != nil {
		t.Fatalf("Stop() error = %v", err)
	}
}

func TestTimeoutConversionDoesNotOverflow(t *testing.T) {
	maxInt := int(^uint(0) >> 1)
	if got := timeoutFromMillis(maxInt, time.Second); got <= 0 {
		t.Fatalf("timeoutFromMillis(maxInt) = %v", got)
	}
}

func TestStopTimeoutKeepsStoppingState(t *testing.T) {
	release := make(chan struct{})
	runtime := configuredRuntime(t, func(context.Context, client.Config, func(string)) error {
		<-release
		return nil
	})
	if err := runtime.Start(); err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	if err := runtime.Stop(1); !errors.Is(err, ErrStopTimeout) {
		t.Fatalf("Stop() error = %v, want %v", err, ErrStopTimeout)
	}
	if runtime.State() != "stopping" {
		t.Fatalf("State() = %q, want stopping", runtime.State())
	}
	close(release)
	waitForState(t, runtime, "stopped")
}

func TestRapidRestartUsesNewGenerations(t *testing.T) {
	runtime := configuredRuntime(t, blockingReadyRunner)
	for generation := range 25 {
		if err := runtime.Start(); err != nil {
			t.Fatalf("Start() generation %d error = %v", generation, err)
		}
		if err := runtime.WaitReady(100); err != nil {
			t.Fatalf("WaitReady() generation %d error = %v", generation, err)
		}
		if err := runtime.Stop(100); err != nil {
			t.Fatalf("Stop() generation %d error = %v", generation, err)
		}
	}
	runtime.mu.Lock()
	got := runtime.nextGeneration
	runtime.mu.Unlock()
	if got != 25 {
		t.Fatalf("generation ID = %d, want 25", got)
	}
}

func TestStaleWaiterCannotObserveRestart(t *testing.T) {
	var calls int
	var callsMu sync.Mutex
	runtime := configuredRuntime(t, func(ctx context.Context, _ client.Config, onReady func(string)) error {
		callsMu.Lock()
		calls++
		call := calls
		callsMu.Unlock()
		if call > 1 {
			onReady("127.0.0.1:1080")
		}
		<-ctx.Done()
		return ctx.Err()
	})
	if err := runtime.Start(); err != nil {
		t.Fatalf("first Start() error = %v", err)
	}
	runtime.mu.Lock()
	first := runtime.current
	runtime.mu.Unlock()
	staleResult := make(chan error, 1)
	go func() { staleResult <- runtime.waitGenerationReady(first, time.Second) }()
	if err := runtime.Stop(100); err != nil {
		t.Fatalf("first Stop() error = %v", err)
	}
	if err := runtime.Start(); err != nil {
		t.Fatalf("second Start() error = %v", err)
	}
	if err := runtime.WaitReady(100); err != nil {
		t.Fatalf("second WaitReady() error = %v", err)
	}
	if err := <-staleResult; !errors.Is(err, ErrStoppedBeforeReady) {
		t.Fatalf("stale waiter error = %v, want %v", err, ErrStoppedBeforeReady)
	}
	if err := runtime.Stop(100); err != nil {
		t.Fatalf("second Stop() error = %v", err)
	}
}

func TestConcurrentStartStopWaitReady(t *testing.T) {
	runtime := configuredRuntime(t, blockingReadyRunner)
	if err := runtime.Start(); err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	var wg sync.WaitGroup
	for range 16 {
		wg.Add(3)
		go func() {
			defer wg.Done()
			err := runtime.Start()
			if err != nil && !errors.Is(err, ErrAlreadyRunning) {
				t.Errorf("concurrent Start() error = %v", err)
			}
		}()
		go func() {
			defer wg.Done()
			err := runtime.WaitReady(100)
			// A generation that stopped is reported as not running when it
			// had become ready, and as stopped-before-ready otherwise.
			if err != nil && !errors.Is(err, ErrStoppedBeforeReady) && !errors.Is(err, ErrNotRunning) {
				t.Errorf("concurrent WaitReady() error = %v", err)
			}
		}()
		go func() {
			defer wg.Done()
			if err := runtime.Stop(100); err != nil {
				t.Errorf("concurrent Stop() error = %v", err)
			}
		}()
	}
	wg.Wait()
	if err := runtime.Stop(100); err != nil {
		t.Fatalf("final Stop() error = %v", err)
	}
	waitForState(t, runtime, "stopped")
}

func waitForState(t *testing.T, runtime *Runtime, want string) {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		if runtime.State() == want {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("State() = %q, want %q", runtime.State(), want)
}

// TestWaitReadyAfterStop locks in that readiness is not reported for a
// runtime that has stopped. The ready channel of the finished generation
// stays closed and the runtime keeps that generation, so checking the latch
// alone told a caller the tunnel was up while State() said stopped.
func TestWaitReadyAfterStop(t *testing.T) {
	runtime := configuredRuntime(t, blockingReadyRunner)
	if err := runtime.Start(); err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	if err := runtime.WaitReady(1000); err != nil {
		t.Fatalf("WaitReady() error = %v", err)
	}
	if err := runtime.Stop(1000); err != nil {
		t.Fatalf("Stop() error = %v", err)
	}
	if err := runtime.WaitReady(100); !errors.Is(err, ErrNotRunning) {
		t.Fatalf("WaitReady() after Stop = %v, want %v", err, ErrNotRunning)
	}
	if state := runtime.State(); state != "stopped" {
		t.Fatalf("State() = %q, want stopped", state)
	}
}
