// ai-generated: the whole file (tests for the dynamic profile list).
package supervisor

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/app/session"
)

var errReloadBoom = errors.New("reload failed")

func roomProfile(name string) Profile {
	return Profile{Name: name, Config: session.Config{RoomID: name}}
}

// With the list reloaded on every advance the supervisor follows a rolling
// window: once the room it just left drops out, it lands on the new head, so
// the rooms run are R1,R2,R3,... even though each was added only after the
// previous one had started.
func TestRunDynamicReloadFollowsRollingWindow(t *testing.T) {
	windows := [][]Profile{
		{roomProfile("R1")},
		{roomProfile("R1"), roomProfile("R2")},
		{roomProfile("R2"), roomProfile("R3")},
		{roomProfile("R3"), roomProfile("R4")},
		{roomProfile("R4"), roomProfile("R5")},
	}
	var mu sync.Mutex
	step := 0
	var got []string
	reload := func() ([]Profile, error) {
		mu.Lock()
		defer mu.Unlock()
		return windows[min(step, len(windows)-1)], nil
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	run := func(_ context.Context, cfg session.Config) error {
		mu.Lock()
		got = append(got, cfg.RoomID)
		step++
		done := len(got) >= len(windows)
		mu.Unlock()
		if done {
			cancel()
		}
		return nil // a clean end advances to the next room in the reloaded list
	}
	if err := Run(ctx, Config{Profiles: windows[0], Reload: reload, RetryDelay: time.Millisecond}, run); err != nil {
		t.Fatalf("Run() error = %v", err)
	}
	mu.Lock()
	defer mu.Unlock()
	if want := []string{"R1", "R2", "R3", "R4", "R5"}; !equalStrings(got, want) {
		t.Fatalf("rooms run = %v, want %v", got, want)
	}
}

// A room appended while the only profile was running is part of the current
// pass, so MaxCycles=1 tries it before giving up. This is how a host hands a
// running client its next room: the list grows underneath the live session.
func TestRunMaxCyclesCountsProfilesAddedDuringThePass(t *testing.T) {
	var mu sync.Mutex
	list := []Profile{roomProfile("R1")}
	var ran []string
	reload := func() ([]Profile, error) {
		mu.Lock()
		defer mu.Unlock()
		return append([]Profile(nil), list...), nil
	}
	err := Run(context.Background(), Config{
		Profiles: list, Reload: reload, RetryDelay: time.Millisecond, MaxCycles: 1,
	}, func(_ context.Context, cfg session.Config) error {
		mu.Lock()
		ran = append(ran, cfg.RoomID)
		if len(list) == 1 {
			list = append(list, roomProfile("R2"))
		}
		mu.Unlock()
		return errRunnerBoom
	})
	if !errors.Is(err, ErrMaxCyclesExceeded) || !errors.Is(err, errRunnerBoom) {
		t.Fatalf("Run() error = %v, want %v wrapping %v", err, ErrMaxCyclesExceeded, errRunnerBoom)
	}
	mu.Lock()
	defer mu.Unlock()
	if want := []string{"R1", "R2"}; !equalStrings(ran, want) {
		t.Fatalf("rooms run = %v, want %v", ran, want)
	}
}

// A reload that fails or comes back empty keeps the last good list; a bad
// write must not strand the supervisor with nowhere to go.
func TestRunKeepsTheLastListWhenReloadFails(t *testing.T) {
	var calls int
	var ran []string
	reload := func() ([]Profile, error) {
		calls++
		if calls%2 == 0 {
			return nil, errReloadBoom
		}
		return nil, nil
	}
	err := Run(context.Background(), Config{
		Profiles:   []Profile{roomProfile("R1"), roomProfile("R2")},
		Reload:     reload,
		RetryDelay: time.Millisecond,
		MaxCycles:  1,
	}, func(_ context.Context, cfg session.Config) error {
		ran = append(ran, cfg.RoomID)
		return errRunnerBoom
	})
	if !errors.Is(err, ErrMaxCyclesExceeded) {
		t.Fatalf("Run() error = %v, want %v", err, ErrMaxCyclesExceeded)
	}
	if want := []string{"R1", "R2"}; !equalStrings(ran, want) {
		t.Fatalf("rooms run = %v, want %v", ran, want)
	}
}

// A profile dropped from the list on reload keeps its place and its history in
// the status: profiles are tracked by name, in the order first seen, since a
// rolling list has no fixed index to key them on.
func TestRunStatusKeepsProfilesDroppedOnReload(t *testing.T) {
	var mu sync.Mutex
	list := []Profile{roomProfile("R1"), roomProfile("R2")}
	var ran []string
	var last Status
	err := Run(context.Background(), Config{
		Profiles: list, RetryDelay: time.Millisecond, MaxCycles: 1,
		Reload: func() ([]Profile, error) {
			mu.Lock()
			defer mu.Unlock()
			return append([]Profile(nil), list...), nil
		},
		OnStatus: func(status Status) { last = status },
	}, func(_ context.Context, cfg session.Config) error {
		mu.Lock()
		defer mu.Unlock()
		ran = append(ran, cfg.RoomID)
		if cfg.RoomID == "R1" {
			list = []Profile{roomProfile("R2")} // the room just left is gone
		}
		return errRunnerBoom
	})
	if !errors.Is(err, ErrMaxCyclesExceeded) {
		t.Fatalf("Run() error = %v, want %v", err, ErrMaxCyclesExceeded)
	}
	if want := []string{"R1", "R2"}; !equalStrings(ran, want) {
		t.Fatalf("rooms run = %v, want %v", ran, want)
	}
	if len(last.Profiles) != 2 || last.Profiles[0].Name != "R1" || last.Profiles[1].Name != "R2" {
		t.Fatalf("status profiles = %+v, want R1 then R2", last.Profiles)
	}
	if last.Profiles[0].Failures != 1 || last.Profiles[1].Failures != 1 {
		t.Fatalf("status failures = %+v, want one each", last.Profiles)
	}
}
