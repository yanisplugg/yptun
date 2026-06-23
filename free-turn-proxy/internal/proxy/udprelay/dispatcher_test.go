package udprelay

import "testing"

func newTestSlot(id, buf int) *streamSlot {
	return &streamSlot{id: id, sendCh: make(chan *Packet, buf)}
}

func testPkt() *Packet { return &Packet{Data: make([]byte, 1), N: 1} }

// chunk-affinity: первые dispatchChunkSize пакетов идут в один стрим, следующие
// - в другой. Это и есть то, что не даёт single-flow WG ловить reorder.
func TestDispatchChunkAffinity(t *testing.T) {
	d := newDispatcher()
	s0 := newTestSlot(0, 100)
	s1 := newTestSlot(1, 100)
	d.register(s0)
	d.register(s1)

	for i := 0; i < 2*dispatchChunkSize; i++ {
		if !d.dispatch(testPkt()) {
			t.Fatalf("packet %d unexpectedly dropped", i)
		}
	}
	if got := len(s0.sendCh); got != dispatchChunkSize {
		t.Fatalf("stream0 got %d packets, want %d", got, dispatchChunkSize)
	}
	if got := len(s1.sendCh); got != dispatchChunkSize {
		t.Fatalf("stream1 got %d packets, want %d", got, dispatchChunkSize)
	}
}

// Если очередь текущего стрима полна, пакет уходит в свободный (новый чанк),
// а не дропается и не блокирует ingest.
func TestDispatchFalloverWhenFull(t *testing.T) {
	d := newDispatcher()
	s0 := newTestSlot(0, 1) // забивается одним пакетом
	s1 := newTestSlot(1, 100)
	d.register(s0)
	d.register(s1)

	if !d.dispatch(testPkt()) { // заполняет s0
		t.Fatal("packet 1 dropped")
	}
	if !d.dispatch(testPkt()) { // s0 полон → fallover на s1
		t.Fatal("packet 2 dropped")
	}
	if len(s0.sendCh) != 1 {
		t.Fatalf("stream0 got %d, want 1", len(s0.sendCh))
	}
	if len(s1.sendCh) != 1 {
		t.Fatalf("stream1 got %d (fallover failed), want 1", len(s1.sendCh))
	}
}

// Когда все стримы перегружены, пакет дропается (false), а WG ретранслирует.
func TestDispatchDropWhenAllFull(t *testing.T) {
	d := newDispatcher()
	s0 := newTestSlot(0, 1)
	d.register(s0)
	if !d.dispatch(testPkt()) {
		t.Fatal("packet 1 dropped")
	}
	if d.dispatch(testPkt()) {
		t.Fatal("packet 2 should be dropped: the only stream is full")
	}
}

func TestDispatchNoStreams(t *testing.T) {
	d := newDispatcher()
	if d.dispatch(testPkt()) {
		t.Fatal("dispatch should return false with no registered streams")
	}
}

func TestRegisterUnregister(t *testing.T) {
	d := newDispatcher()
	s0 := newTestSlot(0, 1)
	s1 := newTestSlot(1, 1)
	d.register(s0)
	d.register(s1)
	if d.liveStreams() != 2 {
		t.Fatalf("liveStreams=%d, want 2", d.liveStreams())
	}
	d.unregister(s0)
	if d.liveStreams() != 1 {
		t.Fatalf("liveStreams=%d after unregister, want 1", d.liveStreams())
	}
	// Оставшийся стрим всё ещё принимает пакеты.
	if !d.dispatch(testPkt()) {
		t.Fatal("dispatch to remaining stream failed")
	}
	if len(s1.sendCh) != 1 {
		t.Fatalf("stream1 got %d, want 1", len(s1.sendCh))
	}
}
