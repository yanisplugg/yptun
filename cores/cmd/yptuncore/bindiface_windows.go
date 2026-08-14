//go:build windows

package main

import (
	"encoding/binary"
	"syscall"
)

// IP_UNICAST_IF (ws2ipdef.h). Forces every packet sent on the socket out of one interface,
// regardless of what the routing table says — the Windows equivalent of Android's
// VpnService.protect() for a core that has no interface-binding of its own.
const ipUnicastIf = 31

// bindSocketToInterface pins one outbound IPv4 socket to interface [index]. The caller decides
// WHICH sockets get pinned (see shouldPinSocket); this only applies the option.
func bindSocketToInterface(conn syscall.RawConn, index uint32) error {
	// MSDN: for IPv4 the interface index is passed in NETWORK byte order.
	var buf [4]byte
	binary.BigEndian.PutUint32(buf[:], index)
	value := int(binary.LittleEndian.Uint32(buf[:]))

	var sockErr error
	if err := conn.Control(func(fd uintptr) {
		sockErr = syscall.SetsockoptInt(syscall.Handle(fd), syscall.IPPROTO_IP, ipUnicastIf, value)
	}); err != nil {
		return err
	}
	return sockErr
}
