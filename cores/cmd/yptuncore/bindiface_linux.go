//go:build linux

package main

import (
	"net"
	"syscall"
)

// bindSocketToInterface pins one outbound socket to interface [index] with SO_BINDTODEVICE.
//
// This process runs as the desktop USER, not root, so LinuxTunController's `uidrange 0-0` bypass
// rule does not cover it: a `direct` dial would follow the TUN rule back into our own tunnel. A
// socket bound to a device skips routes whose device differs, so the TUN table no longer matches
// and the lookup falls through to the main table.
//
// Unprivileged SO_BINDTODEVICE needs Linux 5.7+. On older kernels (or any other failure) the socket
// is left unpinned rather than failing the dial — the same behaviour as before this existed.
func bindSocketToInterface(conn syscall.RawConn, index uint32) error {
	iface, err := net.InterfaceByIndex(int(index))
	if err != nil {
		return nil
	}
	_ = conn.Control(func(fd uintptr) {
		_ = syscall.BindToDevice(int(fd), iface.Name)
	})
	return nil
}
