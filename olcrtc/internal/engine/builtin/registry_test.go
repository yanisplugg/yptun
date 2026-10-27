package builtin_test

import (
	"slices"
	"testing"

	"github.com/openlibrecommunity/olcrtc/internal/auth"
	"github.com/openlibrecommunity/olcrtc/internal/engine"
	"github.com/openlibrecommunity/olcrtc/internal/engine/builtin"
)

// Every auth provider names the engine that consumes its credentials, and
// that engine has to exist in the same binary, whatever build tags it was
// made with. The lean mobile build once left the livekit engine out on the
// belief that no phone-side provider used it; wbstream does, and the app
// failed at session start with "engine new: engine not found" (olcbox#22).
func TestEveryAuthProviderHasItsEngine(t *testing.T) {
	builtin.RegisterDefaults()
	engines := engine.Available()
	for _, name := range auth.Available() {
		provider, err := auth.Get(name)
		if err != nil {
			t.Fatalf("auth.Get(%q) error = %v", name, err)
		}
		if want := provider.Engine(); !slices.Contains(engines, want) {
			t.Errorf("auth provider %q needs engine %q, which this build does not register (engines: %v)",
				name, want, engines)
		}
	}
}

// salutejazz's auth provider and engine must be wired into RegisterDefaults
// in every build, the lean one (-tags olcrtc_lean) included. Nothing about an
// engine is gated by that tag - it takes out the videochannel transport and
// nothing else - so an engine missing from one tag set is an accident, and
// olcbox#22 is what such an accident costs: the app failed at session start
// with "engine new: engine not found".
func TestSaluteJazzRegistered(t *testing.T) {
	builtin.RegisterDefaults()

	if got := builtin.Available(); !slices.Contains(got, "salutejazz") {
		t.Errorf("builtin.Available() = %v, want it to contain %q", got, "salutejazz")
	}
	if got := engine.Available(); !slices.Contains(got, "salutejazz") {
		t.Errorf("engine.Available() = %v, want it to contain %q", got, "salutejazz")
	}
}

// mtslink rides the same guarantee: auth provider and engine wired in every
// build, the lean one included.
func TestMtsLinkRegistered(t *testing.T) {
	builtin.RegisterDefaults()

	if got := builtin.Available(); !slices.Contains(got, "mtslink") {
		t.Errorf("builtin.Available() = %v, want it to contain %q", got, "mtslink")
	}
	if got := engine.Available(); !slices.Contains(got, "mtslink") {
		t.Errorf("engine.Available() = %v, want it to contain %q", got, "mtslink")
	}
}
