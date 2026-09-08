package netutil

import (
	"context"
	"net"
	"syscall"
)

// LOCAL ADDITION (olcvpn-client). On Android every socket a VPN app opens is routed back INTO its own
// TUN unless VpnService.protect() is called on the raw fd. The tunnel's DNS queries must egress over
// the real network — looping them into the tunnel that carries them is an instant deadlock — so the
// host installs a protector here and the resolver dialer applies it to each new UDP socket.
//
// Nil (the default, and always on desktop) means "no protection needed": the dialer behaves exactly
// like the upstream net.DialUDP it replaces.
var protectFD func(fd int) bool

// SetProtectFD installs the platform socket protector. Pass nil to remove it.
func SetProtectFD(protect func(fd int) bool) { protectFD = protect }

// DialUDPProtected dials udp to addr, protecting the socket first when a protector is installed.
// The protector must run BEFORE connect(), which is exactly what Dialer.Control gives us.
func DialUDPProtected(addr *net.UDPAddr) (*net.UDPConn, error) {
	protect := protectFD
	if protect == nil {
		return net.DialUDP("udp", nil, addr)
	}

	dialer := net.Dialer{
		Control: func(network, address string, c syscall.RawConn) error {
			return c.Control(func(fd uintptr) { protect(int(fd)) })
		},
	}
	conn, err := dialer.DialContext(context.Background(), "udp", addr.String())
	if err != nil {
		return nil, err
	}
	udpConn, ok := conn.(*net.UDPConn)
	if !ok {
		conn.Close()
		return nil, net.UnknownNetworkError("udp: unexpected connection type")
	}
	return udpConn, nil
}
