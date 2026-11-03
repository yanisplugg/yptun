package seichannel

// ai-generated: the whole file (reading SEI payloads packet by packet, and
// that what this side writes is still what an older peer can read).

import (
	"bytes"
	"testing"

	"github.com/pion/rtp"
	"github.com/pion/rtp/codecs"
	"github.com/pion/webrtc/v4/pkg/media/samplebuilder"
)

// packetize turns one access unit into the RTP packets pion would send.
func packetize(t *testing.T, accessUnit []byte, mtu, firstSeq uint16) []*rtp.Packet {
	t.Helper()
	payloader := &codecs.H264Payloader{}
	payloads := payloader.Payload(mtu, accessUnit)
	packets := make([]*rtp.Packet, 0, len(payloads))
	for i, payload := range payloads {
		packets = append(packets, &rtp.Packet{
			Header: rtp.Header{
				SequenceNumber: firstSeq + uint16(i),
				Timestamp:      3000,
				Marker:         i == len(payloads)-1,
			},
			Payload: payload,
		})
	}
	return packets
}

func readAll(t *testing.T, packets []*rtp.Packet) [][]byte {
	t.Helper()
	var (
		reader packetReader
		out    [][]byte
	)
	for _, packet := range packets {
		out = reader.payloads(packet, out)
	}
	return out
}

// TestPacketReaderReadsEveryPayloadOfAnAccessUnit covers the shapes pion
// packetises into: the parameter sets as an aggregation packet, one SEI NAL
// per packet, and the slice that ends the frame.
func TestPacketReaderReadsEveryPayloadOfAnAccessUnit(t *testing.T) {
	want := [][]byte{bytes.Repeat([]byte{1}, 900), []byte("second"), bytes.Repeat([]byte{3}, 40)}
	packets := packetize(t, buildVideoAccessUnit(want...), 1200, 100)

	got := readAll(t, packets)
	if len(got) != len(want) {
		t.Fatalf("read %d payloads, want %d", len(got), len(want))
	}
	for i := range want {
		if !bytes.Equal(got[i], want[i]) {
			t.Fatalf("payload %d differs", i)
		}
	}
}

// TestPacketReaderReassemblesAFragmentedNAL covers a payload too big for one
// packet, which a configured fragment size larger than the MTU produces.
func TestPacketReaderReassemblesAFragmentedNAL(t *testing.T) {
	want := bytes.Repeat([]byte{7}, 3000)
	packets := packetize(t, buildVideoAccessUnit(want), 1200, 500)
	if len(packets) < 4 {
		t.Fatalf("a 3000 byte payload packetised into %d packets", len(packets))
	}

	got := readAll(t, packets)
	if len(got) != 1 || !bytes.Equal(got[0], want) {
		t.Fatalf("read %d payloads from a fragmented NAL", len(got))
	}
}

// TestPacketReaderDropsAFragmentedNALWithAHole keeps half a NAL from being
// parsed: the fragment it carried is retransmitted anyway.
func TestPacketReaderDropsAFragmentedNALWithAHole(t *testing.T) {
	packets := packetize(t, buildVideoAccessUnit(bytes.Repeat([]byte{7}, 3000)), 1200, 500)
	kept := append([]*rtp.Packet{}, packets[:2]...)
	kept = append(kept, packets[3:]...)

	if got := readAll(t, kept); len(got) != 0 {
		t.Fatalf("read %d payloads from a NAL with a hole", len(got))
	}
}

// TestPacketReaderDeliversWithoutWaitingForTheNextFrame is the latency the
// sample builder used to add: a payload is complete when its packet is, not
// when the next frame's first packet arrives.
func TestPacketReaderDeliversWithoutWaitingForTheNextFrame(t *testing.T) {
	payload := []byte("now, not next frame")
	packets := packetize(t, buildVideoAccessUnit(payload), 1200, 900)

	var (
		reader packetReader
		got    [][]byte
	)
	for _, packet := range packets {
		got = reader.payloads(packet, got)
		if len(got) > 0 {
			break
		}
	}
	if len(got) != 1 || !bytes.Equal(got[0], payload) {
		t.Fatal("the payload waited for another packet after its own")
	}
}

// TestAccessUnitStaysReadableByASampleBuilder keeps the writer's batching
// compatible with a peer on the old inbound path, which reassembles whole
// access units and parses them with extractVideoPayloads.
func TestAccessUnitStaysReadableByASampleBuilder(t *testing.T) {
	want := [][]byte{[]byte("one"), []byte("two"), bytes.Repeat([]byte{9}, 900), []byte("four")}
	packets := packetize(t, buildVideoAccessUnit(want...), 1200, 1)
	// A sample builder only completes a frame once the next one starts, so
	// the tick after it is what an old receiver needs to read this one.
	next := packetize(t, buildVideoAccessUnit([]byte("next tick")), 1200, uint16(1+len(packets))) //nolint:gosec // small test index
	for _, packet := range next {
		packet.Timestamp = 6000
	}

	builder := samplebuilder.New(128, &codecs.H264Packet{}, 90000)
	for _, packet := range append(packets, next...) {
		builder.Push(packet)
	}

	var got [][]byte
	for sample := builder.Pop(); sample != nil; sample = builder.Pop() {
		got = append(got, extractVideoPayloads(sample.Data)...)
		if len(got) >= len(want) {
			break
		}
	}
	if len(got) != len(want) {
		t.Fatalf("an old receiver read %d payloads, want %d", len(got), len(want))
	}
	for i := range want {
		if !bytes.Equal(got[i], want[i]) {
			t.Fatalf("an old receiver read payload %d differently", i)
		}
	}
}

// TestAccessUnitGroupsSmallPayloadsIntoOnePacket is why acknowledgements are
// batched: a tick's worth of them rides one packet instead of three each.
func TestAccessUnitGroupsSmallPayloadsIntoOnePacket(t *testing.T) {
	payloads := make([][]byte, 0, 20)
	for range 20 {
		payloads = append(payloads, bytes.Repeat([]byte{2}, 21))
	}
	packets := packetize(t, buildVideoAccessUnit(payloads...), 1200, 1)
	// The parameter sets, one SEI NAL with every acknowledgement in it,
	// and the slice.
	if len(packets) != 3 {
		t.Fatalf("20 small payloads took %d packets, want 3", len(packets))
	}
	if got := readAll(t, packets); len(got) != 20 {
		t.Fatalf("read %d payloads back, want 20", len(got))
	}
}
