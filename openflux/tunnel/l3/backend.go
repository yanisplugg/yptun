package l3

import "fmt"

var errBackendUnavailable = fmt.Errorf("l3: no raw packet backend on this platform")

// L3Backend is the platform-specific raw IPv4 I/O.
//
// Implementations must deliver only packets addressed to EgressIP().
// Recv invokes cb synchronously from a single goroutine.
type L3Backend interface {
	EgressIP() [4]byte
	Send(pkt []byte) error
	Recv(cb func([]byte))
	Close() error
}
