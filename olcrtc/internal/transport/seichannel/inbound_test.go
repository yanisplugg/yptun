package seichannel

import (
	"bytes"
	"hash/crc32"
	"testing"

	"github.com/openlibrecommunity/olcrtc/internal/transport/common"
)

// newInboundTransport builds a transport wired only for the inbound path: a
// reassembler, a sender for the ack queue and nothing else.
func newInboundTransport(onData func([]byte)) *streamTransport {
	closeCh := make(chan struct{})
	queue := common.NewOutboundQueue(closeCh, ErrTransportClosed)

	return &streamTransport{
		onData:      onData,
		queue:       queue,
		sender:      common.NewSender(common.SenderConfig{Role: common.RoleServer}, queue),
		reassembler: common.NewReassembler(256),
		closeCh:     closeCh,
	}
}

func TestInboundAssemblyAndAck(t *testing.T) {
	var got []byte
	tr := newInboundTransport(func(data []byte) { got = append([]byte(nil), data...) })

	payload := []byte("hello world")
	crc := crc32.ChecksumIEEE(payload)
	tr.handleInboundFrame(common.Frame{
		Type:      common.FrameTypeData,
		Seq:       1,
		CRC:       crc,
		TotalLen:  uint32(len(payload)), //nolint:gosec // G115: bounded conversion verified by surrounding logic
		FragIdx:   1,
		FragTotal: 2,
		FragCRC:   crc32.ChecksumIEEE([]byte(" world")),
		Payload:   []byte(" world"),
	})
	if len(got) != 0 {
		t.Fatalf("onData called before message complete: %q", got)
	}
	// Every decoded fragment is acked, including the partial one: that is
	// what lets the sender retransmit only the fragments still missing.
	assertAck(t, tr, 1, crc, 1)

	tr.handleInboundFrame(common.Frame{
		Type:      common.FrameTypeData,
		Seq:       1,
		CRC:       crc,
		TotalLen:  uint32(len(payload)), //nolint:gosec // G115: bounded conversion verified by surrounding logic
		FragIdx:   0,
		FragTotal: 2,
		FragCRC:   crc32.ChecksumIEEE([]byte("hello")),
		Payload:   []byte("hello"),
	})
	if !bytes.Equal(got, payload) {
		t.Fatalf("assembled payload = %q, want %q", got, payload)
	}
	assertAck(t, tr, 1, crc, 0)

	// A duplicate is re-acked but never delivered twice.
	got = nil
	tr.handleInboundFrame(common.Frame{
		Type:      common.FrameTypeData,
		Seq:       1,
		CRC:       crc,
		TotalLen:  uint32(len(payload)), //nolint:gosec // G115: bounded conversion verified by surrounding logic
		FragIdx:   0,
		FragTotal: 2,
		FragCRC:   crc32.ChecksumIEEE([]byte("hello")),
		Payload:   []byte("hello"),
	})
	if got != nil {
		t.Fatalf("duplicate delivered payload again: %q", got)
	}
	assertAck(t, tr, 1, crc, 0)
}

func assertAck(t *testing.T, tr *streamTransport, seq, crc uint32, fragIdx uint16) {
	t.Helper()

	raw, ok := tr.queue.Next()
	if !ok || raw == nil {
		t.Fatal("handleInboundFrame() did not enqueue ack")
	}
	frame, err := common.DecodeFrame(raw)
	if err != nil {
		t.Fatalf("DecodeFrame(ack) error = %v", err)
	}
	if frame.Type != common.FrameTypeAck || frame.Seq != seq ||
		frame.CRC != crc || frame.FragIdx != fragIdx {
		t.Fatalf("ack frame = %+v, want seq=%d crc=%d frag=%d", frame, seq, crc, fragIdx)
	}
}

func TestInboundRejectsBadCRC(t *testing.T) {
	called := false
	tr := newInboundTransport(func([]byte) { called = true })

	tr.handleInboundFrame(common.Frame{
		Seq:       2,
		CRC:       123,
		TotalLen:  3,
		FragIdx:   0,
		FragTotal: 1,
		FragCRC:   crc32.ChecksumIEEE([]byte("abc")),
		Payload:   []byte("abc"),
	})
	if called {
		t.Fatal("handleInboundFrame() delivered payload with bad crc")
	}
	if raw, ok := tr.queue.Next(); !ok || raw != nil {
		t.Fatalf("bad-crc fragment was acked: %v", raw)
	}
}
