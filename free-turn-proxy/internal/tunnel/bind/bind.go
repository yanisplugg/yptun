package bind

import (
	"net"
	"net/netip"
	"sync/atomic"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"

	"github.com/samosvalishe/free-turn-proxy/internal/tunnel"
)

// SinglePeerBind подключает WireGuard к каналу net.PacketConn вместо сокета.
type SinglePeerBind struct {
	pc     net.PacketConn
	closed atomic.Bool
}

type peerEndpoint struct{}

func (peerEndpoint) ClearSrc()           {}
func (peerEndpoint) SrcToString() string { return "" }
func (peerEndpoint) DstToString() string { return tunnel.EndpointSinglePeer }
func (peerEndpoint) DstToBytes() []byte  { return []byte{} }
func (peerEndpoint) DstIP() netip.Addr   { return netip.Addr{} }
func (peerEndpoint) SrcIP() netip.Addr   { return netip.Addr{} }

var theEndpoint conn.Endpoint = peerEndpoint{}

func NewSinglePeerBind(pc net.PacketConn) *SinglePeerBind {
	return &SinglePeerBind{pc: pc}
}

// Open инициализирует функции приёма пакетов и сбрасывает дедлайны.
func (b *SinglePeerBind) Open(uint16) ([]conn.ReceiveFunc, uint16, error) {
	b.closed.Store(false)
	if err := b.pc.SetReadDeadline(time.Time{}); err != nil {
		return nil, 0, err
	}
	return []conn.ReceiveFunc{b.receive}, 0, nil
}

func (b *SinglePeerBind) receive(packets [][]byte, sizes []int, eps []conn.Endpoint) (int, error) {
	n, _, err := b.pc.ReadFrom(packets[0])
	if err != nil {
		// При закрытом Bind возвращаем net.ErrClosed для корректного выхода из цикла чтения.
		if b.closed.Load() {
			return 0, net.ErrClosed
		}
		return 0, err
	}
	sizes[0] = n
	eps[0] = theEndpoint
	return 1, nil
}

func (b *SinglePeerBind) Send(bufs [][]byte, _ conn.Endpoint) error {
	for _, buf := range bufs {
		if _, err := b.pc.WriteTo(buf, nil); err != nil {
			return err
		}
	}
	return nil
}

func (*SinglePeerBind) ParseEndpoint(string) (conn.Endpoint, error) {
	return theEndpoint, nil
}

func (*SinglePeerBind) BatchSize() int { return 1 }

func (*SinglePeerBind) SetMark(uint32) error { return nil }

// Close прерывает текущие операции чтения без закрытия нижележащего PacketConn.
func (b *SinglePeerBind) Close() error {
	if !b.closed.CompareAndSwap(false, true) {
		return nil
	}
	return b.pc.SetReadDeadline(time.Unix(1, 0))
}
