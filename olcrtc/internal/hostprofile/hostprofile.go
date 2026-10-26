// Package hostprofile records the one fact every buffer in olcrtc sizes
// itself by: whether the host kills the process for growing.
//
// It is a leaf on purpose. The flag used to live in internal/runtime, which
// imports the transports, which import the engines - so the engines, where
// pion's own buffers are configured, could not read it. Nothing here imports
// anything, and everything may import this.
package hostprofile

import "sync/atomic"

// constrained is set once, before any session starts, by a host that has a
// hard memory ceiling.
var constrained atomic.Bool //nolint:gochecknoglobals // process-wide memory profile

// UseConstrainedBuffers shrinks every receive window and every pion buffer
// this process holds, for hosts that are killed rather than swapped when they
// grow: the iOS packet tunnel extension, and the Android VPN service beside
// it. It is one-way in a running process, which is what a host that never
// changes shape mid-flight wants.
func UseConstrainedBuffers() { constrained.Store(true) }

// BuffersAreConstrained reports the profile chosen for this process.
func BuffersAreConstrained() bool { return constrained.Load() }

// ResetForTest restores the server profile. Tests only.
func ResetForTest() { constrained.Store(false) }
