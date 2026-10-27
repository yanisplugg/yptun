//go:build !olcrtc_testhooks

// Package testhooks holds knobs a test build may turn that a release build
// does not even contain. This is the release side: without the
// olcrtc_testhooks tag every hook is empty and the compiler drops the call.
package testhooks

import "context"

// ai-generated: the whole file.

// Enabled is false in every build that does not pass the tag.
const Enabled = false

// BeforeBridgeOpen does nothing in a build without the hooks.
func BeforeBridgeOpen() {}

// DropProviderAfter does nothing in a build without the hooks. ai-generated
// (olcrtc#19).
func DropProviderAfter(context.Context, func()) {}

// AllowPrivateTargets is false in a build without the hooks, whatever the
// environment says: a release server always keeps its egress policy.
// ai-generated (egress hardening).
func AllowPrivateTargets() bool { return false }
