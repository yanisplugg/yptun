package bind

import (
	"net"
	"net/netip"
	"sync/atomic"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/conn"

	"github.com/samosvalishe/free-turn-proxy/internal/tunnel"
)

// Подключает WireGuard-устройство к каналу pc вместо сокета.
type SinglePeerBind struct {
	pc     net.PacketConn
	closed atomic.Bool
}

// peerEndpoint - единственный endpoint такого Bind. Адреса пустые: устройство
// использует их только для логов и cookie-подсчёта, а маршрутизировать нечего.
type peerEndpoint struct{}

func (peerEndpoint) ClearSrc()           {}
func (peerEndpoint) SrcToString() string { return "" }
func (peerEndpoint) DstToString() string { return tunnel.EndpointSinglePeer }
func (peerEndpoint) DstToBytes() []byte  { return []byte{} }
func (peerEndpoint) DstIP() netip.Addr   { return netip.Addr{} }
func (peerEndpoint) SrcIP() netip.Addr   { return netip.Addr{} }

var theEndpoint conn.Endpoint = peerEndpoint{}

// NewSinglePeerBind оборачивает канал до релея. Bind не владеет pc: закрывает
// его тот, кто открыл (сессия закрывает свою половину пары по отмене ctx).
func NewSinglePeerBind(pc net.PacketConn) *SinglePeerBind {
	return &SinglePeerBind{pc: pc}
}

func (b *SinglePeerBind) Open(uint16) ([]conn.ReceiveFunc, uint16, error) {
	return []conn.ReceiveFunc{b.receive}, 0, nil
}

func (b *SinglePeerBind) receive(packets [][]byte, sizes []int, eps []conn.Endpoint) (int, error) {
	n, _, err := b.pc.ReadFrom(packets[0])
	if err != nil {
		// Устройство прекращает цикл только на net.ErrClosed; любую другую
		// ошибку оно логирует и читает снова, поэтому пробуждение по дедлайну
		// после Close надо перевести именно в неё. Close сначала ставит
		// closed=true, затем SetReadDeadline - race-окна нет.
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

// ParseEndpoint игнорирует строку: собеседник один и известен заранее.
func (*SinglePeerBind) ParseEndpoint(string) (conn.Endpoint, error) {
	return theEndpoint, nil
}

// BatchSize=1: пара в памяти отдаёт по одной датаграмме, батчить нечего.
func (*SinglePeerBind) BatchSize() int { return 1 }

// SetMark - no-op: SO_MARK ставят на сокете, а его здесь нет.
func (*SinglePeerBind) SetMark(uint32) error { return nil }

// Close прекращает работу Bind. Сам канал не закрывается - им владеет тот, кто
// его создал; после Close все ReceiveFunc отдают net.ErrClosed.
func (b *SinglePeerBind) Close() error {
	if !b.closed.CompareAndSwap(false, true) {
		return nil
	}
	// Дедлайн в прошлом будит читателя, висящего в ReadFrom.
	return b.pc.SetReadDeadline(time.Unix(1, 0))
}
