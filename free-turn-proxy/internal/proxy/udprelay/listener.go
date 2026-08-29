package udprelay

import (
	"context"
	"net"
	"sync"
	"sync/atomic"
)

// Packet представляет буферизованную датаграмму для передачи воркерам.
type Packet struct {
	Data []byte
	N    int
}

// packetPool переиспользует буферы датаграмм.
var packetPool = sync.Pool{
	New: func() any { return &Packet{Data: make([]byte, 2048)} },
}

// runListener читает входящие датаграммы и раздаёт их стримам через диспетчер с
// chunk-affinity (см. dispatcher.dispatch). ЛОКАЛЬНЫЙ ПАТЧ: у upstream тут одна
// общая очередь inboundChan, которую разбирают все стримы по готовности - это
// размазывает подряд идущие WG-пакеты по TURN-путям с разным latency и роняет
// скорость одиночного потока. Не терять при ре-вендоре.
func runListener(ctx context.Context, listenConn net.PacketConn, activeLocalPeer *atomic.Value, d *dispatcher) {
	var lastAddr net.Addr
	var lastAddrStr string
	for {
		if ctx.Err() != nil {
			return
		}
		pktIface := packetPool.Get()
		pkt := pktIface.(*Packet) //nolint:errcheck // pool New always returns *Packet
		nRead, addr, err := listenConn.ReadFrom(pkt.Data)
		if err != nil {
			return
		}

		if addr != lastAddr {
			s := addr.String()
			if s != lastAddrStr {
				activeLocalPeer.Store(addr)
				lastAddrStr = s
			}
			lastAddr = addr
		}

		pkt.N = nRead

		if !d.dispatch(pkt) {
			packetPool.Put(pkt)
		}
	}
}
