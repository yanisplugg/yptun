//go:build olcrtc_testhooks

// Package testhooks holds knobs a test build may turn that a release build
// does not even contain. With the olcrtc_testhooks tag the functions here read
// the environment; without it they are empty and the compiler drops them.
package testhooks

import (
	"context"
	"os"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/logger"
)

// ai-generated: the whole file.

// Enabled reports whether this binary was built with the hooks.
const Enabled = true

// bridgeDelayEnv names the variable BeforeBridgeOpen reads.
const bridgeDelayEnv = "OLCRTC_TEST_BRIDGE_DELAY"

// providerDropEnv names the variable DropProviderAfter reads. ai-generated
// (olcrtc#19).
const providerDropEnv = "OLCRTC_TEST_PROVIDER_DROP_AFTER"

// privateTargetsEnv names the variable AllowPrivateTargets reads.
// ai-generated (egress hardening).
const privateTargetsEnv = "OLCRTC_TEST_ALLOW_PRIVATE_TARGETS"

// BeforeBridgeOpen sleeps for OLCRTC_TEST_BRIDGE_DELAY (a Go duration) before
// a Jitsi session opens its bridge. The gate's S6 uses it to make a server
// come up after the client, the ordering that lost the hello in olcbox#22.
// An unset, zero or negative value delays nothing. So does one that is not a
// duration, but that one is logged: a mistyped delay must not pass unnoticed
// for a run that tested a late bridge.
func BeforeBridgeOpen() {
	v := os.Getenv(bridgeDelayEnv)
	if v == "" {
		return
	}
	d, err := time.ParseDuration(v)
	if err != nil {
		logger.Warnf("testhooks: %s=%q ignored: %v", bridgeDelayEnv, v, err)
		return
	}
	if d <= 0 {
		return
	}
	logger.Infof("testhooks: bridge opens %s late", d)
	time.Sleep(d)
}

// DropProviderAfter calls drop once, OLCRTC_TEST_PROVIDER_DROP_AFTER (a Go
// duration) after it is called, unless ctx ends first. A server calls it once
// its link is up, with a drop that makes its provider rebuild, as a relay that
// cuts the server's connection does: the WB Stream rebuild the client took
// three minutes to get over in olcrtc#19. An unset, zero or negative value
// drops nothing; so does one that is not a duration, which is logged.
//
// ai-generated: the whole function (olcrtc#19).
func DropProviderAfter(ctx context.Context, drop func()) {
	v := os.Getenv(providerDropEnv)
	if v == "" {
		return
	}
	d, err := time.ParseDuration(v)
	if err != nil {
		logger.Warnf("testhooks: %s=%q ignored: %v", providerDropEnv, v, err)
		return
	}
	if d <= 0 {
		return
	}
	logger.Infof("testhooks: the provider drops in %s", d)
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return
	case <-timer.C:
	}
	logger.Infof("testhooks: dropping the provider")
	drop()
}

// AllowPrivateTargets reports whether OLCRTC_TEST_ALLOW_PRIVATE_TARGETS is
// "1". The gate's local target sets it: its server must reach the gate's
// origin on loopback, a target the egress policy refuses. Anything else,
// unset included, keeps the policy.
//
// ai-generated: the whole function (egress hardening).
func AllowPrivateTargets() bool {
	if os.Getenv(privateTargetsEnv) != "1" {
		return false
	}
	logger.Infof("testhooks: the egress policy is off, private targets allowed")
	return true
}
