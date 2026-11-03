//go:build !olcrtc_testhooks

package testhooks

import (
	"context"
	"testing"
	"time"
)

// ai-generated: whole file, cover for the release build's empty hook.

const tagged = false

// A build without the tag must ignore the variable entirely: a release
// binary started with it set still opens its bridge at once.
func TestEnvIgnoredWithoutTheTag(t *testing.T) {
	t.Setenv("OLCRTC_TEST_BRIDGE_DELAY", "2s")
	start := time.Now()
	BeforeBridgeOpen()
	if d := time.Since(start); d > atOnce {
		t.Fatalf("an untagged build slept %v on OLCRTC_TEST_BRIDGE_DELAY", d)
	}
}

// A build without the tag drops no provider, whatever the variable says.
// ai-generated (olcrtc#19).
func TestProviderDropIgnoredWithoutTheTag(t *testing.T) {
	t.Setenv("OLCRTC_TEST_PROVIDER_DROP_AFTER", "1ms")
	DropProviderAfter(context.Background(), func() { t.Error("an untagged build dropped its provider") })
}

// A release build keeps its egress policy whatever the variable says.
// ai-generated (egress hardening).
func TestPrivateTargetsIgnoredWithoutTheTag(t *testing.T) {
	t.Setenv("OLCRTC_TEST_ALLOW_PRIVATE_TARGETS", "1")
	if AllowPrivateTargets() {
		t.Fatal("an untagged build lifted the egress policy")
	}
}
