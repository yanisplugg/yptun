package awg

import (
	"errors"

	"github.com/amnezia-vpn/amneziawg-go/conn"
)

// reservedBind wraps a conn.Bind and stamps Cloudflare WARP's 3 "reserved" header bytes onto every
// outgoing WireGuard message. WARP derives these from the registration client_id and DROPS any packet
// whose reserved field is zero — which is what amneziawg-go (and wireguard-go) emit by default, so a
// vanilla bind handshakes but carries no data. The bytes live at offset 1..3 (byte 0 is the message
// type); junk cover packets are shorter/ignored, so the len>=4 guard is enough. Receive is untouched:
// the device ignores reserved bytes on inbound. PeekLookAtSocketFd4/6 are delegated so the socket
// protector (protectBind) still finds the underlying fd through the wrapper.
type reservedBind struct {
	conn.Bind
	reserved [3]byte
}

func newReservedBind(inner conn.Bind, reserved [3]byte) conn.Bind {
	return &reservedBind{Bind: inner, reserved: reserved}
}

func (b *reservedBind) Send(bufs [][]byte, ep conn.Endpoint) error {
	for _, buf := range bufs {
		if len(buf) >= 4 {
			buf[1] = b.reserved[0]
			buf[2] = b.reserved[1]
			buf[3] = b.reserved[2]
		}
	}
	return b.Bind.Send(bufs, ep)
}

func (b *reservedBind) PeekLookAtSocketFd4() (int, error) {
	if p, ok := b.Bind.(interface{ PeekLookAtSocketFd4() (int, error) }); ok {
		return p.PeekLookAtSocketFd4()
	}
	return -1, errors.New("not supported")
}

func (b *reservedBind) PeekLookAtSocketFd6() (int, error) {
	if p, ok := b.Bind.(interface{ PeekLookAtSocketFd6() (int, error) }); ok {
		return p.PeekLookAtSocketFd6()
	}
	return -1, errors.New("not supported")
}

// bindFor builds the conn.Bind for cfg, wrapping it to stamp WARP reserved bytes when present.
func bindFor(cfg *wgConfig) conn.Bind {
	bind := conn.NewDefaultBind()
	if cfg.hasReserved {
		return newReservedBind(bind, cfg.reserved)
	}
	return bind
}
