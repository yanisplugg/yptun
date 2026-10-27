package vp8channel

import (
	"errors"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/transport"
)

// newDatagramTestTransport builds a transport the way New does, without a
// provider: the fake stream accepts writes and no track is attached, so tests
// observe samples through sampleWriter.
func newDatagramTestTransport(cfg transport.Config, opts Options) *streamTransport {
	if opts.FPS == 0 {
		opts.FPS = 30
	}
	if opts.BatchSize == 0 {
		opts.BatchSize = 1
	}
	cfg.DeviceID = "client"
	tr := newStreamTransport(&fakeVideoStream{canSend: true}, nil, cfg, opts)
	tr.localEpoch = 0x100
	return tr
}

func mkDatagramFrame(token, src, dst uint32, payload []byte) []byte {
	hdr := buildEpochHeaderTo(token, src, dst)
	frame := make([]byte, 0, epochHdrLen+len(datagramMagic)+len(payload))
	frame = append(frame, hdr[:]...)
	frame = append(frame, datagramMagic[:]...)
	frame = append(frame, payload...)
	return frame
}

func mkDatagramBatchFrame(token, src, dst uint32, payloads [][]byte) []byte {
	hdr := buildEpochHeaderTo(token, src, dst)
	frame := make([]byte, 0, defaultMaxPayloadSize)
	frame = append(frame, hdr[:]...)
	frame = append(frame, datagramBatchMagic[:]...)
	for _, payload := range payloads {
		packet := append(append([]byte(nil), datagramMagic[:]...), payload...)
		frame = appendBatchPacket(frame, packet)
	}
	return frame
}

func assertQueuedDatagram(t *testing.T, tr *streamTransport, wantDst uint32, wantPayload string) {
	t.Helper()
	frame := <-tr.datagram
	token, src, dst, ok := parseEpochHeader(frame)
	if !ok || token != tr.bindingToken || src != tr.localEpoch || dst != wantDst {
		t.Fatalf("datagram header token=0x%x src=0x%x dst=0x%x ok=%v want dst=0x%x",
			token, src, dst, ok, wantDst)
	}
	payload, ok := splitDatagramPayload(frame[epochHdrLen:])
	if !ok || string(payload) != wantPayload {
		t.Fatalf("datagram payload=%q ok=%v want %q", payload, ok, wantPayload)
	}
}

func assertDatagramPacketPayload(t *testing.T, packet []byte, want string) {
	t.Helper()
	payload, ok := splitDatagramPayload(packet)
	if !ok || string(payload) != want {
		t.Fatalf("datagram packet payload=%q ok=%v, want %q/true", payload, ok, want)
	}
}

func assertStringReceived(t *testing.T, ch <-chan string, want string) {
	t.Helper()
	select {
	case got := <-ch:
		if got != want {
			t.Fatalf("received %q, want %q", got, want)
		}
	case <-time.After(time.Second):
		t.Fatalf("did not receive %q", want)
	}
}

func TestDatagramEnqueueBroadcast(t *testing.T) {
	tr := newDatagramTestTransport(transport.Config{}, Options{})
	if !tr.Features().Datagram {
		t.Fatal("Features().Datagram = false, want true")
	}
	if !tr.DatagramCanSend() {
		t.Fatal("DatagramCanSend() = false, want true")
	}
	if err := tr.SendDatagram([]byte("broadcast")); err != nil {
		t.Fatalf("SendDatagram() error = %v", err)
	}
	assertQueuedDatagram(t, tr, 0, "broadcast")
}

func TestDatagramEnqueueLatchedPeer(t *testing.T) {
	tr := newDatagramTestTransport(transport.Config{}, Options{})
	tr.peerEpoch.Store(0x200)
	if err := tr.SendDatagram([]byte("latched")); err != nil {
		t.Fatalf("SendDatagram(latched) error = %v", err)
	}
	assertQueuedDatagram(t, tr, 0x200, "latched")
}

func TestDatagramEnqueueDirectPeer(t *testing.T) {
	tr := newDatagramTestTransport(transport.Config{}, Options{})
	if err := tr.SendDatagramTo("00000300", []byte("direct")); err != nil {
		t.Fatalf("SendDatagramTo() error = %v", err)
	}
	assertQueuedDatagram(t, tr, 0x300, "direct")
	if err := tr.SendDatagramTo("not-hex", []byte("direct")); err == nil {
		t.Fatal("SendDatagramTo(bad peer) error = nil")
	}
}

func TestDatagramBackpressureAndClosedState(t *testing.T) {
	tr := newDatagramTestTransport(transport.Config{}, Options{})
	for len(tr.datagram) < cap(tr.datagram)*canSendHighWatermark/100 {
		tr.datagram <- []byte("queued")
	}
	if tr.DatagramCanSend() {
		t.Fatal("DatagramCanSend() = true at high watermark")
	}
	for len(tr.datagram) < cap(tr.datagram) {
		tr.datagram <- []byte("queued")
	}
	if err := tr.SendDatagram([]byte("full")); !errors.Is(err, ErrDatagramQueueFull) {
		t.Fatalf("SendDatagram(full) error = %v, want %v", err, ErrDatagramQueueFull)
	}
	huge := make([]byte, defaultMaxPayloadSize)
	if err := tr.SendDatagram(huge); !errors.Is(err, transport.ErrTrafficPayloadTooLarge) {
		t.Fatalf("SendDatagram(huge) error = %v, want %v", err, transport.ErrTrafficPayloadTooLarge)
	}
	tr.closed.Store(true)
	if tr.DatagramCanSend() {
		t.Fatal("DatagramCanSend() = true after close")
	}
	if err := tr.SendDatagram([]byte("closed")); !errors.Is(err, ErrTransportClosed) {
		t.Fatalf("SendDatagram(closed) error = %v, want %v", err, ErrTransportClosed)
	}
}

func TestHandleIncomingDatagramSinglePeer(t *testing.T) {
	got := make(chan string, 1)
	tr := newDatagramTestTransport(transport.Config{
		OnDatagram: func(data []byte) { got <- string(data) },
	}, Options{})

	tr.handleIncomingFrame(mkDatagramFrame(tr.bindingToken, 0x300, tr.localEpoch, []byte("early")))
	select {
	case msg := <-got:
		t.Fatalf("unexpected pre-latch datagram: %q", msg)
	default:
	}
	if tr.peerConfirmed.Load() {
		t.Fatal("a datagram confirmed the peer")
	}

	tr.peerEpoch.Store(0x200)
	tr.peerConfirmed.Store(true)
	tr.handleIncomingFrame(mkDatagramFrame(tr.bindingToken, 0x200, tr.localEpoch, []byte("udp")))
	assertStringReceived(t, got, "udp")

	tr.handleIncomingFrame(mkDatagramFrame(tr.bindingToken, 0x300, tr.localEpoch, []byte("other-peer")))
	tr.handleIncomingFrame(mkDatagramFrame(tr.bindingToken, 0x200, 0x999, []byte("foreign-dst")))
	select {
	case msg := <-got:
		t.Fatalf("unexpected datagram: %q", msg)
	default:
	}
}

func TestHandleIncomingDatagramPeerRouting(t *testing.T) {
	got := make(chan string, 2)
	tr := newDatagramTestTransport(transport.Config{
		OnPeerData:     func(string, []byte) {},
		OnPeerDatagram: func(peerID string, data []byte) { got <- peerID + ":" + string(data) },
	}, Options{})

	tr.handleIncomingFrame(mkDatagramFrame(tr.bindingToken, 0x300, tr.localEpoch, []byte("peer-udp")))
	assertStringReceived(t, got, "00000300:peer-udp")

	tr.handleIncomingFrame(mkDatagramBatchFrame(tr.bindingToken, 0x300, tr.localEpoch, [][]byte{
		[]byte("one"),
		[]byte("two"),
	}))
	assertStringReceived(t, got, "00000300:one")
	assertStringReceived(t, got, "00000300:two")
}

func TestSplitDatagramBatchRejectsTruncatedFrames(t *testing.T) {
	if _, ok := splitDatagramBatchPayload(append(append([]byte(nil), datagramBatchMagic[:]...), 0)); ok {
		t.Fatal("a one-byte length prefix parsed")
	}
	if _, ok := splitDatagramBatchPayload(append(append([]byte(nil), datagramBatchMagic[:]...), 0, 5, 'a')); ok {
		t.Fatal("a short packet parsed")
	}
	if _, ok := splitDatagramPayload([]byte("OLU")); ok {
		t.Fatal("a short marker parsed")
	}
}

func TestWriterDrainsDatagramBeforeReliableData(t *testing.T) {
	tr := newDatagramTestTransport(transport.Config{}, Options{BatchSize: 1})
	dataHdr := tr.epochHeader()
	tr.data.out <- &packetBuffer{data: append(dataHdr[:], []byte("kcp")...)}
	tr.datagram <- mkDatagramFrame(tr.bindingToken, tr.localEpoch, 0x200, []byte("udp"))

	var writes [][]byte
	tr.sampleWriter = func(data []byte) bool {
		writes = append(writes, append([]byte(nil), data...))
		return true
	}
	w := newWriterState(tr)
	if !w.drainDatagram() {
		t.Fatal("drainDatagram() = false, want true")
	}
	w.drainData()
	if len(writes) != 2 {
		t.Fatalf("writes = %d, want 2", len(writes))
	}
	if payload, ok := splitDatagramPayload(writes[0][epochHdrLen:]); !ok || string(payload) != "udp" {
		t.Fatalf("first write datagram payload=%q ok=%v", payload, ok)
	}
	if string(writes[1][epochHdrLen:]) != "kcp" {
		t.Fatalf("second write = %q, want kcp", writes[1][epochHdrLen:])
	}
}

func TestWriterRetriesAFailedDatagramSample(t *testing.T) {
	tr := newDatagramTestTransport(transport.Config{}, Options{BatchSize: 1})
	tr.datagram <- mkDatagramFrame(tr.bindingToken, tr.localEpoch, 0x200, []byte("udp"))
	accept := false
	var writes int
	tr.sampleWriter = func([]byte) bool {
		writes++
		return accept
	}
	w := newWriterState(tr)
	if w.drainDatagram() {
		t.Fatal("drainDatagram() = true while the track refuses samples")
	}
	if w.pendingDatagram == nil {
		t.Fatal("the refused sample was not kept for retry")
	}
	accept = true
	if !w.drainDatagram() || w.pendingDatagram != nil {
		t.Fatalf("retry: drained=%v pending=%v", accept, w.pendingDatagram != nil)
	}
	if writes != 2 {
		t.Fatalf("writes = %d, want 2", writes)
	}
}

func TestWriterBatchesDatagramsWithSameRoute(t *testing.T) {
	tr := newDatagramTestTransport(transport.Config{}, Options{BatchSize: 8})
	first := mkDatagramFrame(tr.bindingToken, tr.localEpoch, 0x200, []byte("one"))
	tr.datagram <- mkDatagramFrame(tr.bindingToken, tr.localEpoch, 0x200, []byte("two"))

	var writes [][]byte
	tr.sampleWriter = func(data []byte) bool {
		writes = append(writes, append([]byte(nil), data...))
		return true
	}
	w := newWriterState(tr)
	sample := w.batchDatagramSampleFrom(tr.datagram, first)
	if !w.writeSample(sample) {
		t.Fatal("writeSample(datagram batch) = false, want true")
	}
	if len(writes) != 1 {
		t.Fatalf("writes = %d, want 1", len(writes))
	}
	packets, ok := splitDatagramBatchPayload(writes[0][epochHdrLen:])
	if !ok || len(packets) != 2 {
		t.Fatalf("datagram batch packets=%d ok=%v, want 2/true", len(packets), ok)
	}
	assertDatagramPacketPayload(t, packets[0], "one")
	assertDatagramPacketPayload(t, packets[1], "two")
}

func TestWriterKeepsDifferentDatagramRoutesSeparate(t *testing.T) {
	tr := newDatagramTestTransport(transport.Config{}, Options{BatchSize: 8})
	first := mkDatagramFrame(tr.bindingToken, 0x200, 0x300, []byte("one"))
	second := mkDatagramFrame(tr.bindingToken, 0x200, 0x400, []byte("two"))
	tr.datagram <- second

	w := newWriterState(tr)
	sample := w.batchDatagramSampleFrom(tr.datagram, first)
	packets, ok := splitDatagramBatchPayload(sample[epochHdrLen:])
	if !ok || len(packets) != 1 {
		t.Fatalf("datagram batch packets=%d ok=%v, want 1/true", len(packets), ok)
	}
	if w.pendingDatagram == nil {
		t.Fatal("pendingDatagram = nil, want second route queued for next tick")
	}
	if !sameEpochHeader(w.pendingDatagram, second) {
		t.Fatal("pendingDatagram route changed")
	}
	if w.batchDatagramSampleFrom(tr.datagram, first)[epochHdrLen] != datagramMagic[0] {
		t.Fatal("a lone datagram was not sent as it is")
	}
}

func TestCloseDropsQueuedDatagrams(t *testing.T) {
	tr := newDatagramTestTransport(transport.Config{}, Options{})
	tr.datagram <- []byte("stale")
	if err := tr.Close(); err != nil {
		t.Fatalf("Close() error = %v", err)
	}
	if len(tr.datagram) != 0 {
		t.Fatalf("queued datagrams after Close = %d, want 0", len(tr.datagram))
	}
}
