package testhooks

import (
	"context"
	"testing"
	"time"
)

// ai-generated: whole file, cover for what the hooks do in every build.

// atOnce bounds how long a hook with nothing to do may take.
const atOnce = 50 * time.Millisecond

// Whatever the build, BeforeBridgeOpen must return at once when no delay is
// configured.
func TestBeforeBridgeOpenIsFreeWithoutDelay(t *testing.T) {
	t.Setenv("OLCRTC_TEST_BRIDGE_DELAY", "")
	start := time.Now()
	BeforeBridgeOpen()
	if d := time.Since(start); d > atOnce {
		t.Fatalf("BeforeBridgeOpen took %v with no delay configured", d)
	}
}

func TestEnabledMatchesTheBuild(t *testing.T) {
	if Enabled != tagged {
		t.Fatalf("Enabled = %v, want %v for this build", Enabled, tagged)
	}
}

// Whatever the build, DropProviderAfter must return at once, dropping
// nothing, when no drop is configured. ai-generated (olcrtc#19).
func TestDropProviderAfterIsFreeWithoutDelay(t *testing.T) {
	t.Setenv("OLCRTC_TEST_PROVIDER_DROP_AFTER", "")
	start := time.Now()
	DropProviderAfter(context.Background(), func() { t.Error("dropped with no drop configured") })
	if d := time.Since(start); d > atOnce {
		t.Fatalf("DropProviderAfter took %v with no drop configured", d)
	}
}
