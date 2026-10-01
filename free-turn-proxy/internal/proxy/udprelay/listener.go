package udprelay

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
)

const inboundQueueCap = 2000

var ErrLocalRead = errors.New("udprelay: local read failed") //nolint:gochecknoglobals // sentinel для errors.Is

// Packet представляет буферизованную датаграмму для передачи воркерам.
type Packet struct {
	Data []byte
	N    int
}

// packetPool переиспользует буферы датаграмм.
var packetPool = sync.Pool{
	New: func() any { return &Packet{Data: make([]byte, maxDatagramLen)} },
}

// runListener читает входящие датаграммы и раздаёт их стримам через диспетчер с
// chunk-affinity (см. dispatcher.dispatch). ЛОКАЛЬНЫЙ ПАТЧ: у upstream тут одна
// общая очередь inboundChan, которую разбирают все стримы по готовности - это
// размазывает подряд идущие WG-пакеты по TURN-путям с разным latency и роняет
// скорость одиночного потока. Не терять при ре-вендоре.
func runListener(ctx context.Context, listenConn net.PacketConn, activeLocalPeer *atomic.Value, d *dispatcher) error {
	var lastPort netip.AddrPort
	var lastAddrStr string
	for {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		pktIface := packetPool.Get()
		pkt := pktIface.(*Packet) //nolint:errcheck // pool New always returns *Packet
		nRead, addr, err := listenConn.ReadFrom(pkt.Data)
		if err != nil {
			packetPool.Put(pkt)
			return fmt.Errorf("%w: %w", ErrLocalRead, err)
		}

		if ua, ok := addr.(*net.UDPAddr); ok {
			if ap := ua.AddrPort(); ap != lastPort {
				activeLocalPeer.Store(addr)
				lastPort = ap
			}
		} else if s := addr.String(); s != lastAddrStr {
			activeLocalPeer.Store(addr)
			lastAddrStr = s
		}

		pkt.N = nRead

		if !d.dispatch(pkt) {
			packetPool.Put(pkt)
		}
	}
}
