//go:build windows

package wdtt

import (
	"context"
	"net"
	"syscall"
)

// listenUDP binds a UDP socket with SO_REUSEADDR so a quick restart can reclaim the port — the
// Windows twin of listen_unix.go (the socket is a Handle here, not an int fd).
func listenUDP(addr string) (net.PacketConn, error) {
	lc := net.ListenConfig{
		Control: func(network, address string, c syscall.RawConn) error {
			var setErr error
			if err := c.Control(func(fd uintptr) {
				setErr = syscall.SetsockoptInt(syscall.Handle(fd), syscall.SOL_SOCKET, syscall.SO_REUSEADDR, 1)
			}); err != nil {
				return err
			}
			return setErr
		},
	}
	return lc.ListenPacket(context.Background(), "udp", addr)
}
