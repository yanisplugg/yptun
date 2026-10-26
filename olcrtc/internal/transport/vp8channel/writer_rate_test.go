package vp8channel

import (
	"sync"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/transport"
	"github.com/openlibrecommunity/olcrtc/internal/transport/common"
)

// ai-generated: the whole file (olcrtc#26). What it holds: the writer must
// keep the whole video track under the rate a relay tolerates from one
// publisher, without ever holding a control frame back.

// ratedTransport is a transport whose writes are recorded and whose bulk path
// is limited to bytesPerSec.
func ratedTransport(bytesPerSec, burst int) (*streamTransport, func() [][]byte) {
	var mu sync.Mutex
	var written [][]byte
	tr := &streamTransport{
		data:          newKCPPlane(16, nil),
		control:       newKCPPlane(16, nil),
		batchSize:     1,
		frameInterval: time.Second / 30,
		limiter:       common.NewPublishLimiter(bytesPerSec, burst),
	}
	tr.sampleWriter = func(sample []byte) bool {
		mu.Lock()
		defer mu.Unlock()
		written = append(written, append([]byte(nil), sample...))
		return true
	}
	return tr, func() [][]byte {
		mu.Lock()
		defer mu.Unlock()
		out := make([][]byte, len(written))
		copy(out, written)
		return out
	}
}

func ratedFrame(size int) *packetBuffer {
	hdr := testEpochHdr(1)
	frame := make([]byte, epochHdrLen+size)
	copy(frame, hdr[:])
	return &packetBuffer{data: frame}
}

func TestWriterHoldsBulkDataOverTheCeiling(t *testing.T) {
	tr, written := ratedTransport(10_000, 1000)
	w := newWriterState(tr)
	w.keepaliveEvery, w.forceKeepaliveEvery = 3, 60

	for range 4 {
		tr.data.out <- ratedFrame(600 - epochHdrLen)
	}
	for range 4 {
		w.drainData()
	}

	if got := len(written()); got != 1 {
		t.Fatalf("wrote %d samples on an empty bucket, want 1", got)
	}
	if w.data.pending == nil {
		t.Fatal("the frame that did not fit was dropped instead of held")
	}

	time.Sleep(200 * time.Millisecond) // a full bucket again
	w.drainData()
	if got := len(written()); got != 2 {
		t.Fatalf("wrote %d samples after the bucket refilled, want 2", got)
	}
}

func TestWriterKeepsControlFramesFlowingWhileBulkWaits(t *testing.T) {
	tr, written := ratedTransport(10_000, 1000)
	w := newWriterState(tr)
	w.keepaliveEvery, w.forceKeepaliveEvery = 3, 60

	// A control frame larger than the whole bucket still goes out at once,
	// and leaves the bulk path waiting out its debt.
	tr.control.out <- ratedFrame(3000 - epochHdrLen)
	if !w.drainControl() {
		t.Fatal("a control frame was held back by the rate limiter")
	}
	if got := len(written()); got != 1 {
		t.Fatalf("wrote %d samples, want the control frame", got)
	}

	tr.data.out <- ratedFrame(600 - epochHdrLen)
	w.drainData()
	if got := len(written()); got != 1 {
		t.Fatalf("wrote %d samples, want the bulk frame to wait out the debt", got)
	}
	if w.data.pending == nil {
		t.Fatal("the bulk frame was dropped instead of held")
	}
}

func TestWriterHoldsDatagramsOverTheCeiling(t *testing.T) {
	tr, written := ratedTransport(10_000, 1000)
	tr.datagram = make(chan []byte, 4)
	w := newWriterState(tr)
	w.keepaliveEvery, w.forceKeepaliveEvery = 3, 60

	for range 3 {
		tr.datagram <- ratedFrame(600 - epochHdrLen).data
	}
	// One pass drains until the bucket is empty and then reports that it
	// must retry, holding the datagram that did not fit.
	if w.drainDatagram() {
		t.Fatal("drainDatagram did not report that it must retry")
	}
	if got := len(written()); got != 1 {
		t.Fatalf("wrote %d datagram samples on an empty bucket, want 1", got)
	}
	if w.pendingDatagram == nil {
		t.Fatal("the datagram that did not fit was dropped instead of held")
	}

	time.Sleep(200 * time.Millisecond) // a full bucket again
	w.drainDatagram()
	if got := len(written()); got != 2 {
		t.Fatalf("wrote %d datagram samples after the bucket refilled, want 2", got)
	}
}

// TestPeerPumpStaysUnderTheCeiling runs the server's per-peer pump with a
// queue it could drain in one tick and checks what reached the track.
func TestPeerPumpStaysUnderTheCeiling(t *testing.T) {
	const (
		rate  = 20_000
		burst = 2000
	)
	tr, written := ratedTransport(rate, burst)
	tr.frameInterval = 5 * time.Millisecond
	tr.closeCh = make(chan struct{})

	out := make(chan *packetBuffer, 128)
	for range 100 {
		out <- ratedFrame(1000 - epochHdrLen)
	}
	done := make(chan struct{})
	start := time.Now()
	go tr.peerWriterPump(dataLane{out: out, conn: func() *kcpConn { return nil }, name: "rated peer"}, done)
	time.Sleep(400 * time.Millisecond)
	close(tr.closeCh)
	close(done)
	elapsed := time.Since(start)

	var sent int
	for _, sample := range written() {
		sent += len(sample)
	}
	if sent == 0 {
		t.Fatal("the pump sent nothing")
	}
	if ceiling := burst + int(rate*elapsed.Seconds()); sent > ceiling {
		t.Fatalf("the pump sent %d bytes in %s, above the %d the ceiling allows", sent, elapsed, ceiling)
	}
}

// limitedVideoStream is a session whose service polices publishers, the
// engine.PublishRateLimited shape a transport looks for.
type limitedVideoStream struct {
	*fakeVideoStream
	limit int
}

func (s *limitedVideoStream) PublishRateLimit() int { return s.limit }

func TestNewStreamTransportTakesItsCeilingFromTheEngine(t *testing.T) {
	cfg := transport.Config{DeviceID: "client"}
	opts := Options{FPS: 30, BatchSize: 64}

	// A session that declares nothing publishes unpaced, with the window its
	// host profile allows.
	plain := newStreamTransport(&fakeVideoStream{}, nil, cfg, opts)
	if plain.limiter != nil {
		t.Fatal("a session that polices nothing paced the transport")
	}
	if !plain.readyToSend(make([]byte, 10*defaultMaxPayloadSize)) {
		t.Fatal("an unpaced transport held a sample back")
	}
	if plain.sendWindow != kcpSendWindow(false) {
		t.Fatalf("unpaced send window = %d, want %d", plain.sendWindow, kcpSendWindow(false))
	}

	// A session that declares a ceiling paces every writer of the track and
	// shortens the queue in front of them.
	limited := newStreamTransport(&limitedVideoStream{fakeVideoStream: &fakeVideoStream{}, limit: 1_000_000}, nil, cfg, opts)
	if limited.limiter == nil {
		t.Fatal("a session that polices publishers left the transport unpaced")
	}
	if !limited.readyToSend(make([]byte, defaultMaxPayloadSize)) {
		t.Fatal("a fresh bucket cannot carry one full sample")
	}
	limited.limiter.Charge(publishBurstBytes)
	if limited.readyToSend(make([]byte, defaultMaxPayloadSize)) {
		t.Fatal("a spent bucket still carries a full sample")
	}
	if limited.sendWindow != kcpSendWindow(true) {
		t.Fatalf("paced send window = %d, want %d", limited.sendWindow, kcpSendWindow(true))
	}
	if limited.data.sndWnd != limited.sendWindow || limited.control.sndWnd != limited.sendWindow {
		t.Fatalf("planes run with %d/%d, want %d", limited.data.sndWnd, limited.control.sndWnd, limited.sendWindow)
	}
}
