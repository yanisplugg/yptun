//go:build !windows && !linux

package main

import "syscall"

// No per-socket pinning on the remaining desktops (macOS): kept as a no-op so the exported setter
// has one signature everywhere. Windows and Linux have their own bindiface_*.go.
func bindSocketToInterface(conn syscall.RawConn, index uint32) error {
	return nil
}
