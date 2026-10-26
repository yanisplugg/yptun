// ai-generated: the whole file (review of olcrtc#39 - what a handover does to
// the room list, to the listener and to the number of clients running).
package mobile

import (
	"context"
	"reflect"
	"sync"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/pkg/olcrtc/client"
)

// One room at a time. A hop tears the room it is leaving down before it
// starts the next, or two clients would hold two tunnels, two SOCKS
// listeners on one port and two sessions the host cannot tell apart.
func TestAHandoverNeverRunsTwoRoomsAtOnce(t *testing.T) {
	shortFailoverDelay(t)
	var mu sync.Mutex
	live, mostLive := 0, 0
	retire := make(chan struct{})
	standby := make(chan struct{})
	runtime := configuredRuntime(t, func(ctx context.Context, cfg client.Config, onReady func(string)) error {
		mu.Lock()
		live++
		if live > mostLive {
			mostLive = live
		}
		mu.Unlock()
		defer func() {
			mu.Lock()
			live--
			mu.Unlock()
		}()
		onReady("127.0.0.1:1080")
		if cfg.RoomURL == testRoom {
			<-retire
			return nil
		}
		close(standby)
		<-ctx.Done()
		return ctx.Err()
	})
	if err := runtime.AddFailoverRoom(testStandby); err != nil {
		t.Fatalf("AddFailoverRoom() error = %v", err)
	}
	if err := runtime.Start(); err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	if err := runtime.WaitReady(2000); err != nil {
		t.Fatalf("WaitReady() error = %v", err)
	}
	close(retire)
	select {
	case <-standby:
	case <-time.After(2 * time.Second):
		t.Fatal("the standby room never ran")
	}
	mu.Lock()
	peak := mostLive
	mu.Unlock() // the runner takes this lock on its way out; Stop waits for that
	if peak != 1 {
		t.Fatalf("rooms running at once = %d, want 1", peak)
	}
	if err := runtime.Stop(1000); err != nil {
		t.Fatalf("Stop() error = %v", err)
	}
}

// The list a hop reads is the list as it is then, in both directions: a room
// withdrawn while a session is live is not hopped to, the way one added is.
func TestRoomsClearedDuringASessionAreGoneAtTheNextHop(t *testing.T) {
	shortFailoverDelay(t)
	var mu sync.Mutex
	var seen []string
	retire := make(chan struct{})
	runtime := configuredRuntime(t, func(_ context.Context, cfg client.Config, onReady func(string)) error {
		mu.Lock()
		seen = append(seen, cfg.RoomURL)
		mu.Unlock()
		onReady("127.0.0.1:1080")
		if cfg.RoomURL == testRoom {
			<-retire
		}
		return errTestRun
	})
	if err := runtime.AddFailoverRoom(testStandby); err != nil {
		t.Fatalf("AddFailoverRoom() error = %v", err)
	}
	if err := runtime.Start(); err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	if err := runtime.WaitReady(2000); err != nil {
		t.Fatalf("WaitReady() error = %v", err)
	}
	runtime.ClearFailoverRooms() // the server withdrew it before we got there
	close(retire)
	waitForState(t, runtime, "stopped")
	mu.Lock()
	defer mu.Unlock()
	if want := []string{testRoom}; !reflect.DeepEqual(seen, want) {
		t.Fatalf("rooms tried = %v, want %v - the withdrawn room was hopped to", seen, want)
	}
}

// A listener the host takes back hears nothing more. It is the same call that
// installs one, so a host tearing its UI down has a way to stop being called
// into from the runtime's connect path.
func TestSessionListenerCanBeTakenBack(t *testing.T) {
	second := make(chan struct{})
	runtime := configuredRuntime(t, func(ctx context.Context, cfg client.Config, onReady func(string)) error {
		cfg.OnSessionOpen("first")
		onReady("127.0.0.1:1080")
		<-second
		cfg.OnSessionOpen("after the listener was removed")
		<-ctx.Done()
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
	if got, want := listener.events(), []string{testRoom + " first"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("session events = %v, want %v", got, want)
	}
	runtime.SetSessionListener(nil)
	close(second)
	time.Sleep(50 * time.Millisecond)
	if got, want := listener.events(), []string{testRoom + " first"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("session events = %v, want only %v after the listener was removed", got, want)
	}
	if err := runtime.Stop(1000); err != nil {
		t.Fatalf("Stop() error = %v", err)
	}
}

// roomRun is one room the supervisor handed the runner, and whether that run
// was told to give up on the room if it turns out to be empty.
type roomRun struct {
	room     string
	giveUpOn bool
}

// A generation with one room keeps retrying in it. Giving up there would end
// the generation and leave the host holding a tunnel it did not ask to lose,
// which is what the runtime did before failover and what a host without a
// retry loop of its own still expects.
func TestASingleRoomGenerationDoesNotGiveUpOnIt(t *testing.T) {
	var mu sync.Mutex
	var runs []roomRun
	release := make(chan struct{})
	runtime := configuredRuntime(t, func(_ context.Context, cfg client.Config, onReady func(string)) error {
		mu.Lock()
		runs = append(runs, roomRun{cfg.RoomURL, cfg.EndOnEmptyRoom})
		mu.Unlock()
		onReady("127.0.0.1:1080")
		<-release
		return nil
	})
	if err := runtime.Start(); err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	if err := runtime.WaitReady(2000); err != nil {
		t.Fatalf("WaitReady() error = %v", err)
	}
	mu.Lock()
	got := append([]roomRun(nil), runs...)
	mu.Unlock()
	close(release)
	if want := []roomRun{{testRoom, false}}; !reflect.DeepEqual(got, want) {
		t.Fatalf("runs = %v, want %v - a lone room must be retried, not given up on", got, want)
	}
	if err := runtime.Stop(1000); err != nil {
		t.Fatalf("Stop() error = %v", err)
	}
}

// A second room is somewhere to go, so the run started after it arrives is
// told to give up on a room nobody is in. The count is read when a room's run
// starts: a room the host appends mid-session is in force from the next start,
// which is the hop the retired room causes anyway.
func TestASecondRoomLetsTheNextStartGiveUpOnAnEmptyRoom(t *testing.T) {
	shortFailoverDelay(t)
	var mu sync.Mutex
	var runs []roomRun
	firstStarted := make(chan struct{})
	retire := make(chan struct{})
	standby := make(chan struct{})
	runtime := configuredRuntime(t, func(ctx context.Context, cfg client.Config, onReady func(string)) error {
		mu.Lock()
		runs = append(runs, roomRun{cfg.RoomURL, cfg.EndOnEmptyRoom})
		first := len(runs) == 1
		mu.Unlock()
		onReady("127.0.0.1:1080")
		if first {
			close(firstStarted)
			<-retire // the server retires this room once the standby is known
			return errTestRun
		}
		close(standby)
		<-ctx.Done()
		return ctx.Err()
	})
	if err := runtime.Start(); err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	<-firstStarted
	// The subscription refresh reaches a generation that is already live.
	if err := runtime.AddFailoverRoom(testStandby); err != nil {
		t.Fatalf("AddFailoverRoom() error = %v", err)
	}
	close(retire)
	select {
	case <-standby:
	case <-time.After(2 * time.Second):
		t.Fatal("the room added mid-session was never started")
	}
	mu.Lock()
	got := append([]roomRun(nil), runs...)
	mu.Unlock()
	want := []roomRun{{testRoom, false}, {testStandby, true}}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("runs = %v, want %v", got, want)
	}
	if err := runtime.Stop(1000); err != nil {
		t.Fatalf("Stop() error = %v", err)
	}
}

// Both rooms known before Start: the very first run already has somewhere to
// go, so it is told to give up on an empty room from the outset.
func TestAGenerationStartedWithTwoRoomsGivesUpOnTheFirst(t *testing.T) {
	var mu sync.Mutex
	var runs []roomRun
	release := make(chan struct{})
	runtime := configuredRuntime(t, func(_ context.Context, cfg client.Config, onReady func(string)) error {
		mu.Lock()
		runs = append(runs, roomRun{cfg.RoomURL, cfg.EndOnEmptyRoom})
		mu.Unlock()
		onReady("127.0.0.1:1080")
		<-release
		return nil
	})
	if err := runtime.AddFailoverRoom(testStandby); err != nil {
		t.Fatalf("AddFailoverRoom() error = %v", err)
	}
	if err := runtime.Start(); err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	if err := runtime.WaitReady(2000); err != nil {
		t.Fatalf("WaitReady() error = %v", err)
	}
	mu.Lock()
	got := append([]roomRun(nil), runs...)
	mu.Unlock()
	close(release)
	if want := []roomRun{{testRoom, true}}; !reflect.DeepEqual(got, want) {
		t.Fatalf("runs = %v, want %v", got, want)
	}
	if err := runtime.Stop(1000); err != nil {
		t.Fatalf("Stop() error = %v", err)
	}
}
