//go:build !windows

package main

import "syscall"

// Non-Windows desktops don't need per-socket interface pinning: LinuxTunController installs
// `ip rule add uidrange 0-0 lookup main`, so everything this process dials already bypasses the
// tunnel. Kept as a no-op so the exported setter has one signature everywhere.
func bindSocketToInterface(conn syscall.RawConn, index uint32) error {
	return nil
}
