// ai-generated: the whole file (tests for the session listener).
package mobile

import (
	"context"
	"errors"
	"reflect"
	"sync"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/pkg/olcrtc/client"
)

// The host cannot read the runtime's log, so the moment a session is
// established - the client's "session opened" line - reaches it through a
// listener instead, naming the room. It fires for every session, so a
// failover to another room is visible as exactly that.
func TestSessionListenerNamesTheRoomOfEachSession(t *testing.T) {
	shortFailoverDelay(t)
	runtime := configuredRuntime(t, func(ctx context.Context, cfg client.Config, onReady func(string)) error {
		cfg.OnSessionOpen("session-in-" + cfg.RoomURL)
		if cfg.RoomURL == testRoom {
			return errTestRun
		}
		onReady("127.0.0.1:1080")
		<-ctx.Done()
		return ctx.Err()
	})
	listener := &recordingListener{}
	runtime.SetSessionListener(listener)
	if err := runtime.AddFailoverRoom(testStandby); err != nil {
		t.Fatalf("AddFailoverRoom() error = %v", err)
	}
	if err := runtime.Start(); err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	if err := runtime.WaitReady(2000); err != nil {
		t.Fatalf("WaitReady() error = %v", err)
	}
	want := []string{
		testRoom + " session-in-" + testRoom,
		testStandby + " session-in-" + testStandby,
	}
	if got := listener.events(); !reflect.DeepEqual(got, want) {
		t.Fatalf("session events = %v, want %v", got, want)
	}
	if err := runtime.Stop(100); err != nil {
		t.Fatalf("Stop() error = %v", err)
	}
}

// A runtime with no listener still lets the client report sessions; the events
// go nowhere. A listener installed later hears the next one, without a restart.
func TestSessionListenerIsOptionalAndReplaceable(t *testing.T) {
	sessions := make(chan struct{}, 1)
	runtime := configuredRuntime(t, func(ctx context.Context, cfg client.Config, onReady func(string)) error {
		cfg.OnSessionOpen("first")
		onReady("127.0.0.1:1080")
		<-sessions
		cfg.OnSessionOpen("second")
		<-ctx.Done()
		return ctx.Err()
	})
	if err := runtime.Start(); err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	if err := runtime.WaitReady(2000); err != nil {
		t.Fatalf("WaitReady() error = %v", err)
	}
	listener := &recordingListener{}
	runtime.SetSessionListener(listener)
	sessions <- struct{}{}
	deadline := time.Now().Add(2 * time.Second)
	for len(listener.events()) == 0 && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if got, want := listener.events(), []string{testRoom + " second"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("session events = %v, want %v", got, want)
	}
	if err := runtime.Stop(100); err != nil {
		t.Fatalf("Stop() error = %v", err)
	}
}

// A session that opens while its generation is being stopped is not one the
// host should act on: the event is dropped rather than delivered late.
func TestSessionListenerIgnoresAStoppingGeneration(t *testing.T) {
	stopping := make(chan struct{})
	runtime := configuredRuntime(t, func(ctx context.Context, cfg client.Config, onReady func(string)) error {
		onReady("127.0.0.1:1080")
		<-ctx.Done()
		<-stopping
		cfg.OnSessionOpen("late")
		return ctx.Err()
	})
	listener := &recordingListener{}
	runtime.SetSessionListener(listener)
	if err := runtime.Start(); err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	if err := runtime.WaitReady(2000); err != nil {
		t.Fatalf("WaitReady() error = %v", err)
	}
	if err := runtime.Stop(1); !errors.Is(err, ErrStopTimeout) {
		t.Fatalf("Stop() error = %v, want %v", err, ErrStopTimeout)
	}
	close(stopping)
	waitForState(t, runtime, "stopped")
	if got := listener.events(); len(got) != 0 {
		t.Fatalf("session events = %v, want none from a stopping generation", got)
	}
}

type recordingListener struct {
	mu     sync.Mutex
	opened []string
}

func (l *recordingListener) OnSessionOpened(room, sessionID string) {
	l.mu.Lock()
	l.opened = append(l.opened, room+" "+sessionID)
	l.mu.Unlock()
}

func (l *recordingListener) events() []string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return append([]string(nil), l.opened...)
}
