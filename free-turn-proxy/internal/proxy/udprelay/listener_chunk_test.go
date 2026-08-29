package udprelay

import (
	"context"
	"net"
	"sync/atomic"
	"testing"
	"time"
)

// Регрессия ре-вендора: runListener обязан раздавать пакеты ЧЕРЕЗ диспетчер
// (chunk-affinity), а не через общую очередь. dispatcher_test проверяет сам
// диспетчер и остаётся зелёным, даже если listener.go его больше не зовёт -
// поэтому проводку проверяем отдельно.
func TestRunListenerDispatchesChunks(t *testing.T) {
	t.Parallel()

	conn, err := net.ListenPacket("udp", "127.0.0.1:0") //nolint:noctx // тестовый сокет
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer func() { _ = conn.Close() }()

	d := newDispatcher()
	a := &streamSlot{id: 1, sendCh: make(chan *Packet, streamSendBuf)}
	b := &streamSlot{id: 2, sendCh: make(chan *Packet, streamSendBuf)}
	d.register(a)
	d.register(b)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	var peer atomic.Value
	go runListener(ctx, conn, &peer, d)

	sender, err := net.Dial("udp", conn.LocalAddr().String()) //nolint:noctx // тестовый сокет
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer func() { _ = sender.Close() }()

	const total = 2 * dispatchChunkSize
	for i := 0; i < total; i++ {
		if _, err := sender.Write([]byte{byte(i)}); err != nil {
			t.Fatalf("write %d: %v", i, err)
		}
	}

	deadline := time.Now().Add(3 * time.Second)
	for len(a.sendCh)+len(b.sendCh) < total && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if got := len(a.sendCh) + len(b.sendCh); got != total {
		t.Fatalf("получено %d пакетов из %d", got, total)
	}

	// Смотрим НА КАКОМ стриме осел каждый пакет: поровну половины даёт и
	// попакетный round-robin, поэтому счёта мало - важна именно аффинность чанка.
	first := drain(a)
	second := drain(b)
	if len(first) != dispatchChunkSize || len(second) != dispatchChunkSize {
		t.Fatalf("a=%v b=%v", first, second)
	}
	for i, v := range first {
		if int(v) != i {
			t.Fatalf("chunk-affinity сломана: первый стрим получил %v, ожидались пакеты 0..%d подряд",
				first, dispatchChunkSize-1)
		}
	}
	for i, v := range second {
		if int(v) != dispatchChunkSize+i {
			t.Fatalf("chunk-affinity сломана: второй стрим получил %v, ожидались пакеты %d..%d подряд",
				second, dispatchChunkSize, total-1)
		}
	}
}

// drain выгребает первый байт каждого пакета из очереди стрима.
func drain(s *streamSlot) []byte {
	var out []byte
	for {
		select {
		case pkt := <-s.sendCh:
			out = append(out, pkt.Data[0])
		default:
			return out
		}
	}
}
