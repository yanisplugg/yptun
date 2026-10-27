//go:build olcrtc_testhooks

package testhooks

import (
	"context"
	"testing"
	"time"
)

// ai-generated: whole file, cover for the tagged build's bridge delay.

const tagged = true

func TestDelayIsHonoured(t *testing.T) {
	t.Setenv("OLCRTC_TEST_BRIDGE_DELAY", "300ms")
	start := time.Now()
	BeforeBridgeOpen()
	if d := time.Since(start); d < 300*time.Millisecond {
		t.Fatalf("BeforeBridgeOpen returned after %v, want >= 300ms", d)
	}
}

// The gate starts every local server with the variable set, to "0s" when a
// scenario wants no delay; that, and anything that is not a positive
// duration, must leave the bridge undelayed.
func TestNoDelayUnlessPositive(t *testing.T) {
	for _, v := range []string{"0s", "-2s", "2"} {
		t.Run(v, func(t *testing.T) {
			t.Setenv("OLCRTC_TEST_BRIDGE_DELAY", v)
			start := time.Now()
			BeforeBridgeOpen()
			if d := time.Since(start); d > atOnce {
				t.Fatalf("BeforeBridgeOpen slept %v on %q", d, v)
			}
		})
	}
}

// ai-generated: the provider drop's tests (olcrtc#19).

func TestProviderDropIsHonoured(t *testing.T) {
	t.Setenv("OLCRTC_TEST_PROVIDER_DROP_AFTER", "100ms")
	start := time.Now()
	dropped := 0
	DropProviderAfter(context.Background(), func() { dropped++ })
	if d := time.Since(start); d < 100*time.Millisecond {
		t.Fatalf("DropProviderAfter dropped after %v, want >= 100ms", d)
	}
	if dropped != 1 {
		t.Fatalf("drops = %d, want 1", dropped)
	}
}

func TestProviderDropStopsWithTheServer(t *testing.T) {
	t.Setenv("OLCRTC_TEST_PROVIDER_DROP_AFTER", "1h")
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	DropProviderAfter(ctx, func() { t.Error("dropped after the context ended") })
}

func TestNoDropUnlessPositive(t *testing.T) {
	for _, v := range []string{"0s", "-2s", "2"} {
		t.Run(v, func(t *testing.T) {
			t.Setenv("OLCRTC_TEST_PROVIDER_DROP_AFTER", v)
			start := time.Now()
			DropProviderAfter(context.Background(), func() { t.Errorf("dropped on %q", v) })
			if d := time.Since(start); d > atOnce {
				t.Fatalf("DropProviderAfter waited %v on %q", d, v)
			}
		})
	}
}

// The tagged build lifts the egress policy for exactly "1", the value the
// gate's local target sets. ai-generated (egress hardening).
func TestAllowPrivateTargetsOnlyForOne(t *testing.T) {
	for v, want := range map[string]bool{"1": true, "": false, "0": false, "true": false} {
		t.Setenv("OLCRTC_TEST_ALLOW_PRIVATE_TARGETS", v)
		if got := AllowPrivateTargets(); got != want {
			t.Errorf("AllowPrivateTargets() with %q = %v, want %v", v, got, want)
		}
	}
}
