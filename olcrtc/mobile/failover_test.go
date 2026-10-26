// ai-generated: the whole file (tests for the failover room list).
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

const testStandby = "https://meet.example.org/standby"

func shortFailoverDelay(t *testing.T) {
	t.Helper()
	prev := failoverRetryDelay
	failoverRetryDelay = 10 * time.Millisecond
	t.Cleanup(func() { failoverRetryDelay = prev })
}

// A retired primary must not strand the client: the standby delivered
// alongside it is tried next. This is the whole point of the failover list.
func TestFailoverAdvancesToNextRoomWhenTheFirstEnds(t *testing.T) {
	shortFailoverDelay(t)
	var seenMu sync.Mutex
	var seen []string
	runtime := configuredRuntime(t, func(ctx context.Context, cfg client.Config, onReady func(string)) error {
		seenMu.Lock()
		seen = append(seen, cfg.RoomURL)
		seenMu.Unlock()
		if cfg.RoomURL == testRoom {
			return errTestRun // the primary is gone: what a retired room looks like
		}
		onReady("127.0.0.1:1080")
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
		t.Fatalf("WaitReady() error = %v, want the standby to come up", err)
	}
	seenMu.Lock()
	got := append([]string(nil), seen...)
	seenMu.Unlock()
	if !reflect.DeepEqual(got, []string{testRoom, testStandby}) {
		t.Fatalf("rooms tried = %v, want [%s %s]", got, testRoom, testStandby)
	}
	if err := runtime.Stop(100); err != nil {
		t.Fatalf("Stop() error = %v", err)
	}
}

// A room delivered while a session is live - as a subscription refresh does -
// is used at the next hop, without a restart. Without this the list a client
// starts with is the only list it ever has.
func TestRoomsAddedDuringASessionAreUsedAtTheNextHop(t *testing.T) {
	shortFailoverDelay(t)
	retirePrimary := make(chan struct{})
	standbyRunning := make(chan struct{})
	runtime := configuredRuntime(t, func(ctx context.Context, cfg client.Config, onReady func(string)) error {
		onReady("127.0.0.1:1080")
		if cfg.RoomURL == testRoom {
			<-retirePrimary // the server retires it while we sit in it
			return nil
		}
		close(standbyRunning)
		<-ctx.Done()
		return ctx.Err()
	})
	if err := runtime.Start(); err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	if err := runtime.WaitReady(100); err != nil {
		t.Fatalf("WaitReady() error = %v", err)
	}
	if err := runtime.AddFailoverRoom(testStandby); err != nil {
		t.Fatalf("AddFailoverRoom() error = %v", err)
	}
	close(retirePrimary)
	select {
	case <-standbyRunning:
	case <-time.After(2 * time.Second):
		t.Fatal("the room added mid-session was never tried")
	}
	if runtime.State() != "running" {
		t.Fatalf("State() = %q after the hop, want running", runtime.State())
	}
	if err := runtime.Stop(100); err != nil {
		t.Fatalf("Stop() error = %v", err)
	}
}

// Every room on offer is tried once, then the generation ends with the last
// room's error and the host's own retry loop takes over.
func TestGenerationEndsAfterOnePassOverDeadRooms(t *testing.T) {
	shortFailoverDelay(t)
	var tried int
	var triedMu sync.Mutex
	runtime := configuredRuntime(t, func(context.Context, client.Config, func(string)) error {
		triedMu.Lock()
		tried++
		triedMu.Unlock()
		return errTestRun
	})
	if err := runtime.AddFailoverRoom(testStandby); err != nil {
		t.Fatalf("AddFailoverRoom() error = %v", err)
	}
	if err := runtime.Start(); err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	if err := runtime.WaitReady(2000); !errors.Is(err, errTestRun) {
		t.Fatalf("WaitReady() error = %v, want %v", err, errTestRun)
	}
	waitForState(t, runtime, "stopped")
	triedMu.Lock()
	defer triedMu.Unlock()
	if tried != 2 {
		t.Fatalf("rooms tried = %d, want 2 (primary and standby, once each)", tried)
	}
}

func TestFailoverRoomListIsOrderedAndDeduplicated(t *testing.T) {
	runtime := configuredRuntime(t, blockingReadyRunner)
	if err := runtime.AddFailoverRoom("  "); !errors.Is(err, ErrInvalidConfig) {
		t.Fatalf("blank AddFailoverRoom() error = %v, want %v", err, ErrInvalidConfig)
	}
	for _, room := range []string{"b", testRoom, "a", "b"} {
		if err := runtime.AddFailoverRoom(room); err != nil {
			t.Fatalf("AddFailoverRoom(%q) error = %v", room, err)
		}
	}
	if got, want := runtime.defaults.rooms(), []string{testRoom, "b", "a"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("rooms() = %v, want %v", got, want)
	}
	runtime.ClearFailoverRooms()
	if got := runtime.defaults.rooms(); !reflect.DeepEqual(got, []string{testRoom}) {
		t.Fatalf("rooms() after clear = %v, want [%s]", got, testRoom)
	}
}
