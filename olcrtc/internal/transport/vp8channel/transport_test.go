package vp8channel

import (
	"bytes"
	"encoding/binary"
	"sync"
	"testing"
	"time"
)

func pumpPackets(stop <-chan struct{}, from <-chan *packetBuffer, to *kcpRuntime) {
	for {
		select {
		case <-stop:
			return
		case pkt := <-from:
			// Strip the on-wire epoch header that kcpConn prepends;
			// the real receive path does this before calling deliver().
			if len(pkt.data) > epochHdrLen {
				to.deliver(pkt.data[epochHdrLen:])
			}
			pkt.release()
		}
	}
}

func checkMessages(t *testing.T, got, want [][]byte) {
	t.Helper()
	if len(got) != len(want) {
		t.Fatalf("got %d messages, want %d", len(got), len(want))
	}
	for i, m := range want {
		if !bytes.Equal(got[i], m) {
			t.Errorf("msg %d mismatch: got %d bytes, want %d", i, len(got[i]), len(m))
		}
	}
}

func buildReceiver(n int) (func([]byte), <-chan struct{}, func() [][]byte) {
	var mu sync.Mutex
	var recv [][]byte
	done := make(chan struct{})
	cb := func(msg []byte) {
		mu.Lock()
		recv = append(recv, append([]byte(nil), msg...))
		count := len(recv)
		mu.Unlock()
		if count == n {
			close(done)
		}
	}
	get := func() [][]byte {
		mu.Lock()
		defer mu.Unlock()
		return recv
	}
	return cb, done, get
}

// TestKCPLoopback runs two KCP runtimes back-to-back through an in-memory
// pipe simulating a perfect provider. Verifies that messages survive the
// KCP layer with their boundaries intact.
func TestKCPLoopback(t *testing.T) {
	msgs := [][]byte{
		[]byte("hello"),
		bytes.Repeat([]byte("x"), 1000),
		bytes.Repeat([]byte("y"), 20000),
	}

	a2b := make(chan *packetBuffer, 256)
	b2a := make(chan *packetBuffer, 256)

	cb, doneB, getRecv := buildReceiver(len(msgs))

	rtA, err := startKCP(a2b, nil, testEpochHdr(1))
	if err != nil {
		t.Fatalf("startKCP A: %v", err)
	}
	defer rtA.close()

	rtB, err := startKCP(b2a, cb, testEpochHdr(2))
	if err != nil {
		t.Fatalf("startKCP B: %v", err)
	}
	defer rtB.close()

	stop := make(chan struct{})
	defer close(stop)

	go pumpPackets(stop, a2b, rtB)
	go pumpPackets(stop, b2a, rtA)

	for _, m := range msgs {
		if err := rtA.send(m); err != nil {
			t.Fatalf("send: %v", err)
		}
	}

	select {
	case <-doneB:
	case <-time.After(5 * time.Second):
		t.Fatal("timeout waiting for messages")
	}

	checkMessages(t, getRecv(), msgs)
}

func TestVP8KeepaliveDoesNotLookLikeKCP(t *testing.T) {
	if len(vp8Keepalive) != tokenOff {
		t.Errorf("vp8Keepalive length %d != tokenOff %d", len(vp8Keepalive), tokenOff)
	}
}

func TestBatchSampleCarriesMultipleKCPPackets(t *testing.T) {
	hdr := testEpochHdr(1)
	packet := func(payload string) *packetBuffer {
		frame := make([]byte, epochHdrLen+len(payload))
		copy(frame, hdr[:])
		copy(frame[epochHdrLen:], payload)
		return &packetBuffer{data: frame}
	}

	tr := &streamTransport{
		data:      newKCPPlane(4, nil),
		batchSize: 3,
	}
	tr.data.out <- packet("two")
	tr.data.out <- packet("three")
	tr.data.out <- packet("four")

	sample, pending := tr.batchSampleFrom(tr.data.out, packet("one"), nil)
	if pending != nil {
		t.Fatal("batchSampleFrom() returned an unexpected pending packet")
	}
	if !bytes.Equal(sample[:epochHdrLen], hdr[:]) {
		t.Fatalf("sample epoch header = %x, want %x", sample[:epochHdrLen], hdr[:])
	}

	var got []string
	splitKCPPayload(sample[epochHdrLen:], func(payload []byte) {
		got = append(got, string(payload))
	})
	want := []string{"one", "two", "three"}
	if len(got) != len(want) {
		t.Fatalf("split payload count = %d, want %d (%v)", len(got), len(want), got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("payload[%d] = %q, want %q", i, got[i], want[i])
		}
	}
	if left := len(tr.data.out); left != 1 {
		t.Fatalf("outbound left = %d, want 1", left)
	}
}

func TestBatchSampleReusesWriterBuffer(t *testing.T) {
	hdr := testEpochHdr(1)
	frame := make([]byte, epochHdrLen+900)
	copy(frame, hdr[:])
	src := make(chan *packetBuffer, 1)
	tr := &streamTransport{batchSize: 2}

	src <- &packetBuffer{data: frame}
	first, pending := tr.batchSampleFrom(src, &packetBuffer{data: frame}, nil)
	if pending != nil {
		t.Fatal("first batch returned an unexpected pending packet")
	}
	src <- &packetBuffer{data: frame}
	second, pending := tr.batchSampleFrom(src, &packetBuffer{data: frame}, first[:0])
	if pending != nil {
		t.Fatal("second batch returned an unexpected pending packet")
	}
	if &first[0] != &second[0] {
		t.Fatal("batchSampleFrom() did not reuse writer-owned storage")
	}
	count := 0
	splitKCPPayload(second[epochHdrLen:], func(payload []byte) {
		count++
		if len(payload) != 900 {
			t.Fatalf("batched payload length = %d", len(payload))
		}
	})
	if count != 2 {
		t.Fatalf("batched payload count = %d, want 2", count)
	}
}

func TestBatchSamplePreservesOverflowPacket(t *testing.T) {
	hdr := testEpochHdr(1)
	packet := func(size int, fill byte, pool *sync.Pool) *packetBuffer {
		frame := make([]byte, epochHdrLen+size)
		copy(frame, hdr[:])
		for i := epochHdrLen; i < len(frame); i++ {
			frame[i] = fill
		}
		return &packetBuffer{data: frame, pool: pool}
	}

	firstSize := defaultMaxPayloadSize - epochHdrLen - len(kcpBatchMagic) - 2 - 4
	var pool sync.Pool
	overflow := packet(8, 'b', &pool)
	src := make(chan *packetBuffer, 1)
	src <- overflow
	tr := &streamTransport{batchSize: 2}

	first, pending := tr.batchSampleFrom(src, packet(firstSize, 'a', nil), nil)
	if pending != overflow {
		t.Fatalf("pending packet = %p, want overflow packet %p", pending, overflow)
	}
	if len(first) > defaultMaxPayloadSize {
		t.Fatalf("first batch size = %d, max %d", len(first), defaultMaxPayloadSize)
	}
	if overflow.pool == nil || len(overflow.data) == 0 {
		t.Fatal("overflow packet was released before the next batch")
	}

	second, next := tr.batchSampleFrom(src, pending, nil)
	if next != nil {
		t.Fatal("second batch returned an unexpected pending packet")
	}
	var payloads [][]byte
	splitKCPPayload(second[epochHdrLen:], func(payload []byte) {
		payloads = append(payloads, append([]byte(nil), payload...))
	})
	if len(payloads) != 1 || len(payloads[0]) != 8 || payloads[0][0] != 'b' {
		t.Fatalf("second batch payloads = %q, want one overflow packet", payloads)
	}
	if overflow.pool != nil || len(overflow.data) != 0 {
		t.Fatal("overflow packet was not released after the next batch")
	}
	// release is idempotent, so close/retry races cannot put one object into
	// sync.Pool twice.
	overflow.release()
	if overflow.pool != nil {
		t.Fatal("second release restored packet ownership")
	}
}

func TestSplitKCPPayloadAcceptsLegacySinglePacket(t *testing.T) {
	var got [][]byte
	splitKCPPayload([]byte("single"), func(payload []byte) {
		got = append(got, append([]byte(nil), payload...))
	})
	if len(got) != 1 || string(got[0]) != "single" {
		t.Fatalf("split legacy payload = %q", got)
	}
}

func testEpochHdr(epoch uint32) [epochHdrLen]byte {
	var hdr [epochHdrLen]byte
	copy(hdr[:], vp8Keepalive)
	binary.BigEndian.PutUint32(hdr[tokenOff:srcOff], bindingToken("test"))
	binary.BigEndian.PutUint32(hdr[srcOff:dstOff], epoch)
	binary.BigEndian.PutUint32(hdr[dstOff:crcOff], 0)
	binary.BigEndian.PutUint32(hdr[crcOff:epochHdrLen], epochCRC(bindingToken("test"), epoch, 0))
	return hdr
}

func TestHandleIncomingFrameIgnoresLoopedBackLocalEpoch(t *testing.T) {
	stream := &fakeVideoStream{}
	tr := &streamTransport{
		stream:       stream,
		bindingToken: bindingToken("test"),
		localEpoch:   12345,
		onData:       func([]byte) {},
	}

	frame := make([]byte, epochHdrLen+4)
	copy(frame, vp8Keepalive)
	binary.BigEndian.PutUint32(frame[tokenOff:srcOff], tr.bindingToken)
	binary.BigEndian.PutUint32(frame[srcOff:dstOff], tr.localEpoch)
	binary.BigEndian.PutUint32(frame[dstOff:crcOff], 0)
	binary.BigEndian.PutUint32(frame[crcOff:epochHdrLen], epochCRC(tr.bindingToken, tr.localEpoch, 0))
	copy(frame[epochHdrLen:], []byte{1, 2, 3, 4})

	tr.handleIncomingFrame(frame)

	if tr.peerConfirmed.Load() {
		t.Fatal("self-echo frame must not mark peer as seen")
	}
	if got := tr.peerEpoch.Load(); got != 0 {
		t.Fatalf("peer epoch changed on self-echo: got %d want 0", got)
	}
	if got := stream.reconnects.Load(); got != 0 {
		t.Fatalf("provider rebuilt on self-echo: got %d want 0", got)
	}
}

func TestHandleIncomingFrameIgnoresForeignBindingToken(t *testing.T) {
	stream := &fakeVideoStream{}
	tr := &streamTransport{
		stream:       stream,
		bindingToken: bindingToken("srv-client"),
		localEpoch:   12345,
		onData:       func([]byte) {},
	}

	frame := make([]byte, epochHdrLen+4)
	copy(frame, vp8Keepalive)
	otherToken := bindingToken("other-client")
	binary.BigEndian.PutUint32(frame[tokenOff:srcOff], otherToken)
	binary.BigEndian.PutUint32(frame[srcOff:dstOff], 999)
	binary.BigEndian.PutUint32(frame[dstOff:crcOff], 0)
	binary.BigEndian.PutUint32(frame[crcOff:epochHdrLen], epochCRC(otherToken, 999, 0))
	copy(frame[epochHdrLen:], []byte{1, 2, 3, 4})

	tr.handleIncomingFrame(frame)

	if tr.peerConfirmed.Load() {
		t.Fatal("foreign frame must not mark peer as seen")
	}
	if got := tr.peerEpoch.Load(); got != 0 {
		t.Fatalf("peer epoch changed on foreign frame: got %d want 0", got)
	}
	if got := stream.reconnects.Load(); got != 0 {
		t.Fatalf("provider rebuilt on foreign frame: got %d want 0", got)
	}
}
