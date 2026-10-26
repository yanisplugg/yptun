package protect

import (
	"context"
	"net"
)

// ProbeRoute reports whether the host protector accepts a socket right now.
//
// It is a statement about the device, not about any host. The iOS pin
// refuses every socket while no physical interface carries traffic out, and
// that refusal is the only thing the engine can see of a phone that has just
// lost its network: every dial fails at once, before a byte is sent. Asking
// with one socket that is never used lets a reconnect wait for the route
// instead of opening a hundred sockets to learn the same thing (olcbox#37).
// Android's protect only fails once the VpnService is gone, and without a
// protector there is nothing to ask.
func ProbeRoute() bool {
	if protector.Load() == nil {
		return true
	}
	lc := net.ListenConfig{Control: controlFunc}
	for _, network := range []string{"udp4", "udp6"} {
		conn, err := lc.ListenPacket(context.Background(), network, "")
		if err != nil {
			continue
		}
		_ = conn.Close()
		return true
	}
	return false
}
