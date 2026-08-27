package common_test

import (
	"bytes"
	"hash/crc32"
	"testing"

	"github.com/openlibrecommunity/olcrtc/internal/transport/common"
)

// TestReassemblerRejectsHostileHeaders locks in that the self-describing wire
// fields are bounded before anything is allocated. Every case here is a frame
// an unauthenticated room participant can emit, and each one used to be taken
// at face value as an allocation size.
func TestReassemblerRejectsHostileHeaders(t *testing.T) {
	payload := []byte("x")

	cases := []struct {
		name     string
		fragment common.Fragment
	}{
		{
			name: "total length beyond the cap",
			fragment: common.Fragment{
				Seq: 1, CRC: 1, TotalLen: 0xFFFFFFFF,
				FragIdx: 0, FragTotal: 1, Payload: payload,
			},
		},
		{
			name: "fragment count beyond the cap",
			fragment: common.Fragment{
				Seq: 2, CRC: 1, TotalLen: common.MaxMessageLen,
				FragIdx: 0, FragTotal: 65535, Payload: payload,
			},
		},
		{
			name: "fragment count exceeds the announced length",
			fragment: common.Fragment{
				Seq: 3, CRC: 1, TotalLen: 4,
				FragIdx: 0, FragTotal: 64, Payload: payload,
			},
		},
		{
			name: "index outside the fragment count",
			fragment: common.Fragment{
				Seq: 4, CRC: 1, TotalLen: 8,
				FragIdx: 7, FragTotal: 2, Payload: payload,
			},
		},
		{
			name: "zero fragment count",
			fragment: common.Fragment{
				Seq: 5, CRC: 1, TotalLen: 8,
				FragIdx: 0, FragTotal: 0, Payload: payload,
			},
		},
		{
			name: "payload longer than the announced message",
			fragment: common.Fragment{
				Seq: 6, CRC: 1, TotalLen: 1,
				FragIdx: 0, FragTotal: 1, Payload: []byte("much longer"),
			},
		},
	}

	r := common.NewReassembler(8)
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			result, data := r.Push(tc.fragment.WithPayloadCRC())
			if result != common.ResultIgnore || data != nil {
				t.Fatalf("Push() = (%v, %v), want Ignore", result, data)
			}
		})
	}
}

// TestReassemblerRejectsCorruptedFragment is the ack-honesty guarantee: a
// fragment whose payload does not match its own checksum is not stored and
// not acknowledged, so the sender retransmits it and the message still
// completes. Acking it on arrival was what made the sender report success
// while the receiver silently dropped the message.
func TestReassemblerRejectsCorruptedFragment(t *testing.T) {
	r := common.NewReassembler(8)
	payload := []byte("hello world")
	crc := crc32.ChecksumIEEE(payload)
	frags := common.FragmentPayload(payload, 5)

	push := func(idx int, body []byte) (common.Result, []byte) {
		return r.Push(common.Fragment{
			Seq:       1,
			CRC:       crc,
			TotalLen:  uint32(len(payload)), //nolint:gosec // bounded test fixture
			FragIdx:   uint16(idx),          //nolint:gosec // bounded test fixture
			FragTotal: uint16(len(frags)),   //nolint:gosec // bounded test fixture
			FragCRC:   crc32.ChecksumIEEE(frags[idx]),
			Payload:   body,
		})
	}

	for i := range frags[:len(frags)-1] {
		if result, _ := push(i, frags[i]); result != common.ResultPartial {
			t.Fatalf("Push(%d) = %v, want Partial", i, result)
		}
	}

	last := len(frags) - 1
	corrupted := append([]byte(nil), frags[last]...)
	corrupted[0] ^= 0xff
	if result, _ := push(last, corrupted); result != common.ResultIgnore {
		t.Fatalf("Push(corrupted) = %v, want Ignore", result)
	}

	result, data := push(last, frags[last])
	if result != common.ResultDelivered || !bytes.Equal(data, payload) {
		t.Fatalf("Push(retransmit) = (%v, %q), want the assembled message", result, data)
	}
}

// TestReassemblerDedupWindowSurvivesRotation covers the delivered-window
// rotation: a retransmit arriving right after the window filled up must still
// resolve to a duplicate instead of being reassembled and delivered twice.
func TestReassemblerDedupWindowSurvivesRotation(t *testing.T) {
	const window = 4
	r := common.NewReassembler(window)

	deliver := func(seq uint32, body []byte) (common.Result, []byte) {
		return r.Push(common.Fragment{
			Seq:       seq,
			CRC:       crc32.ChecksumIEEE(body),
			TotalLen:  uint32(len(body)), //nolint:gosec // bounded test fixture
			FragIdx:   0,
			FragTotal: 1,
			Payload:   body,
		}.WithPayloadCRC())
	}

	first := []byte("first")
	if result, _ := deliver(1, first); result != common.ResultDelivered {
		t.Fatalf("Push(1) = %v, want Delivered", result)
	}

	// Fill the window past its cap so it rotates. Rotation keeps the
	// displaced generation addressable, so the first message is still known.
	for seq := uint32(2); seq <= window*2; seq++ {
		if result, _ := deliver(seq, []byte("payload")); result != common.ResultDelivered {
			t.Fatalf("Push(%d) = %v, want Delivered", seq, result)
		}
	}

	if result, data := deliver(1, first); result != common.ResultDuplicate || data != nil {
		t.Fatalf("Push(retransmit of 1) = (%v, %q), want Duplicate", result, data)
	}
}
