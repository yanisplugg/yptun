// ai-generated: the whole file (review of olcrtc#39 - the walk over a list
// whose names are not unique identities, and the bound on what the status
// tracker keeps).
package supervisor

import (
	"context"
	"fmt"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/app/session"
)

// A list of unnamed profiles is still walked. The mobile runtime names a
// profile after its room, and the "none" provider has no room, so every
// profile there is unnamed: going by name alone, the walk never left the head
// and the rooms behind it were unreachable.
func TestRunWalksProfilesWithNoNames(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	var ran []string
	err := Run(ctx, Config{
		Profiles: []Profile{
			{Config: session.Config{Provider: "none", URL: "one"}},
			{Config: session.Config{Provider: "none", URL: "two"}},
		},
		RetryDelay: time.Millisecond,
		MaxCycles:  1,
	}, func(_ context.Context, cfg session.Config) error {
		ran = append(ran, cfg.URL)
		if len(ran) > 2 { // a walk that does not advance ends here, not in the timeout
			cancel()
		}
		return errRunnerBoom
	})
	if err == nil && ctx.Err() == nil {
		t.Fatal("Run() error = nil, want the pass to end")
	}
	if want := []string{"one", "two"}; !equalStrings(ran, want) {
		t.Fatalf("profiles run = %v, want %v", ran, want)
	}
}

// Two profiles a config happens to give the same name are two profiles. The
// walk finds where it is before it looks the name up, so it advances past the
// entry it ran rather than back to the first entry that shares its name.
func TestRunWalksProfilesWhoseNamesRepeat(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	var ran []string
	err := Run(ctx, Config{
		Profiles: []Profile{
			{Name: testProfileOne, Config: session.Config{URL: "first"}},
			{Name: testProfileOne, Config: session.Config{URL: "second"}},
		},
		RetryDelay: time.Millisecond,
		MaxCycles:  1,
	}, func(_ context.Context, cfg session.Config) error {
		ran = append(ran, cfg.URL)
		if len(ran) > 2 { // a walk that does not advance ends here, not in the timeout
			cancel()
		}
		return errRunnerBoom
	})
	if err == nil && ctx.Err() == nil {
		t.Fatal("Run() error = nil, want the pass to end")
	}
	if want := []string{"first", "second"}; !equalStrings(ran, want) {
		t.Fatalf("profiles run = %v, want %v", ran, want)
	}
}

// A rolling list hands out a new name every hop. What the tracker keeps of
// them is bounded, or a client that follows a server's rooms for a week grows
// a counter per room and copies them all into every status it emits.
func TestStatusKeepsABoundedNumberOfProfiles(t *testing.T) {
	var last Status
	tracker := newStatusTracker(0, func(status Status) { last = status })
	const rooms = maxTrackedProfiles * 2
	for i := range rooms {
		name := fmt.Sprintf("room-%d", i)
		tracker.start(name, 1, 0)
		tracker.end(name, 1, nil)
	}
	if got := len(last.Profiles); got != maxTrackedProfiles {
		t.Fatalf("tracked profiles = %d, want %d", got, maxTrackedProfiles)
	}
	if got, want := last.Profiles[0].Name, fmt.Sprintf("room-%d", rooms-maxTrackedProfiles); got != want {
		t.Fatalf("oldest tracked profile = %q, want %q - the oldest go first", got, want)
	}
	if got, want := last.Profiles[len(last.Profiles)-1].Name, fmt.Sprintf("room-%d", rooms-1); got != want {
		t.Fatalf("newest tracked profile = %q, want %q", got, want)
	}
}

// A pass where nothing held is spaced out from the next one. Every profile
// ending as fast as it can start is a client that is offline or a server that
// has left every room, and at RetryDelay apiece that is a relay join every few
// seconds for as long as olcrtc runs.
func TestPassesThatProduceNothingAreSpacedOut(t *testing.T) {
	const base = DefaultRetryDelay
	pace := &profilePace{}
	if got := pace.wait(base, false); got != base {
		t.Fatalf("wait mid-pass = %v, want %v", got, base)
	}
	if got := pace.wait(base, true); got != 2*base {
		t.Fatalf("wait after one dead pass = %v, want %v", got, 2*base)
	}
	if got := pace.wait(base, true); got != 4*base {
		t.Fatalf("wait after two dead passes = %v, want %v", got, 4*base)
	}
	// It stays there while the passes keep coming to nothing, and no further.
	for range 20 {
		pace.wait(base, true)
	}
	if got := pace.wait(base, true); got != maxRetryDelay {
		t.Fatalf("wait after many dead passes = %v, want it capped at %v", got, maxRetryDelay)
	}
	// A profile that held is one that worked: the next pass follows at once,
	// so a room retired after hours is not paid for by the room after it.
	pace.held()
	if got := pace.wait(base, true); got != base {
		t.Fatalf("wait after a profile that held = %v, want %v", got, base)
	}
}
