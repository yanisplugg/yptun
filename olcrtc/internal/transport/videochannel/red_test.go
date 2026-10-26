package videochannel

import (
	"github.com/openlibrecommunity/olcrtc/internal/transport/common"

	"bytes"
	"errors"
	"io"
	"testing"

	"github.com/pion/interceptor"
	"github.com/pion/rtp"
	"github.com/pion/webrtc/v4"
)

const (
	testVP8Type    = 100
	testREDType    = 104
	testULPFECType = 106
	testOtherType  = 96
)

// redPayload builds a RED payload: a four-byte header per redundant block,
// the one-byte primary header, then the blocks' data in the same order.
func redPayload(primaryType uint8, primary []byte, redundant ...[]byte) []byte {
	var out []byte
	for _, block := range redundant {
		out = append(out, redFollowBit|primaryType, 0,
			byte(len(block)>>8)&redLengthHighMask, byte(len(block))) //nolint:gosec // test lengths fit ten bits
	}
	out = append(out, primaryType)
	for _, block := range redundant {
		out = append(out, block...)
	}
	return append(out, primary...)
}

func redRTP(payloadType uint8, seq uint16, payload []byte) *rtp.Packet {
	return &rtp.Packet{Header: rtp.Header{PayloadType: payloadType, SequenceNumber: seq}, Payload: payload}
}

func TestREDPrimarySingleBlock(t *testing.T) {
	payloadType, data, err := common.REDPrimary(redPayload(testVP8Type, []byte{1, 2, 3}))
	if err != nil || payloadType != testVP8Type || !bytes.Equal(data, []byte{1, 2, 3}) {
		t.Fatalf("got pt=%d data=%v err=%v", payloadType, data, err)
	}
}

func TestREDPrimarySkipsRedundantBlocks(t *testing.T) {
	long := bytes.Repeat([]byte{9}, 300) // a length that needs the header's top bits
	payload := redPayload(testVP8Type, []byte{7, 7}, []byte{1, 2, 3}, long)
	payloadType, data, err := common.REDPrimary(payload)
	if err != nil || payloadType != testVP8Type || !bytes.Equal(data, []byte{7, 7}) {
		t.Fatalf("got pt=%d data=%v err=%v", payloadType, data, err)
	}
}

func TestREDPrimaryMalformed(t *testing.T) {
	for name, payload := range map[string][]byte{
		"empty":            {},
		"follow bit only":  {redFollowBit | testVP8Type, 0},
		"length past end":  {redFollowBit | testVP8Type, 0, 0, 5, testVP8Type, 1, 2},
		"no primary block": {redFollowBit | testVP8Type, 0, 0, 0},
	} {
		if _, _, err := common.REDPrimary(payload); !errors.Is(err, common.ErrREDHeader) {
			t.Errorf("%s: err=%v, want ErrREDHeader", name, err)
		}
	}
}

// redFakeTrack hands out queued packets, then io.EOF.
type redFakeTrack struct {
	mime    string
	packets []*rtp.Packet
}

func (f *redFakeTrack) ReadRTP() (*rtp.Packet, interceptor.Attributes, error) {
	if len(f.packets) == 0 {
		return nil, nil, io.EOF
	}
	pkt := f.packets[0]
	f.packets = f.packets[1:]
	return pkt, nil, nil
}

func (f *redFakeTrack) Codec() webrtc.RTPCodecParameters {
	return webrtc.RTPCodecParameters{RTPCodecCapability: webrtc.RTPCodecCapability{MimeType: f.mime, ClockRate: 90000}}
}

func (f *redFakeTrack) PayloadType() webrtc.PayloadType { return testREDType }

func testCodecs() []webrtc.RTPCodecParameters {
	return []webrtc.RTPCodecParameters{
		{RTPCodecCapability: webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP8}, PayloadType: testVP8Type},
		{RTPCodecCapability: webrtc.RTPCodecCapability{MimeType: mimeTypeRED}, PayloadType: testREDType},
		{RTPCodecCapability: webrtc.RTPCodecCapability{MimeType: "video/ulpfec"}, PayloadType: testULPFECType},
	}
}

func TestREDSourceUnwrapsSkipsFECAndPassesOtherTypes(t *testing.T) {
	track := &redFakeTrack{mime: mimeTypeRED, packets: []*rtp.Packet{
		redRTP(testREDType, 1, redPayload(testVP8Type, []byte{0xA})),
		redRTP(testREDType, 2, redPayload(testULPFECType, []byte{0xF})), // FEC: skipped
		redRTP(testOtherType, 3, []byte{0xB}),                           // not RED: as is
		redRTP(testREDType, 4, []byte{redFollowBit | testVP8Type}),      // malformed: skipped
		redRTP(testREDType, 5, redPayload(testVP8Type, []byte{0xC}, []byte{1, 2})),
	}}
	source := &redSource{src: track, redType: testREDType, primary: testVP8Type}
	want := []struct {
		seq         uint16
		payloadType uint8
		payload     []byte
	}{
		{1, testVP8Type, []byte{0xA}},
		{3, testOtherType, []byte{0xB}},
		{5, testVP8Type, []byte{0xC}},
	}
	for _, w := range want {
		pkt, _, err := source.ReadRTP()
		if err != nil {
			t.Fatalf("seq %d: %v", w.seq, err)
		}
		if pkt.SequenceNumber != w.seq || pkt.PayloadType != w.payloadType || !bytes.Equal(pkt.Payload, w.payload) {
			t.Fatalf("got seq=%d pt=%d payload=%v, want %+v", pkt.SequenceNumber, pkt.PayloadType, pkt.Payload, w)
		}
	}
	if _, _, err := source.ReadRTP(); !errors.Is(err, io.EOF) {
		t.Fatalf("after the queue: err=%v, want io.EOF", err)
	}
}

func TestRemoteSourceREDLearnsTheCodecFromThePrimaryBlock(t *testing.T) {
	track := &redFakeTrack{mime: mimeTypeRED, packets: []*rtp.Packet{
		redRTP(testREDType, 1, redPayload(testULPFECType, []byte{0xF})), // FEC first: not the codec
		redRTP(testREDType, 2, redPayload(testVP8Type, []byte{0xA})),
		redRTP(testREDType, 3, redPayload(testVP8Type, []byte{0xB})),
	}}
	source, codec, ok := remoteSource(track, testCodecs())
	if !ok || codec.capability.MimeType != webrtc.MimeTypeVP8 {
		t.Fatalf("ok=%v codec=%q", ok, codec.capability.MimeType)
	}
	first, _, err := source.ReadRTP()
	if err != nil || first.SequenceNumber != 2 || first.PayloadType != testVP8Type || !bytes.Equal(first.Payload, []byte{0xA}) {
		t.Fatalf("the packet that named the codec must be delivered first: %+v %v", first, err)
	}
	second, _, err := source.ReadRTP()
	if err != nil || second.SequenceNumber != 3 || !bytes.Equal(second.Payload, []byte{0xB}) {
		t.Fatalf("second: %+v %v", second, err)
	}
}

func TestRemoteSourceREDWithoutADecodablePrimaryBlock(t *testing.T) {
	track := &redFakeTrack{mime: mimeTypeRED, packets: []*rtp.Packet{
		redRTP(testREDType, 1, redPayload(testULPFECType, []byte{0xF})),
	}}
	if _, _, ok := remoteSource(track, testCodecs()); ok {
		t.Fatal("a RED track with no decodable primary block must be refused")
	}
}

func TestRemoteSourcePlainCodecs(t *testing.T) {
	track := &redFakeTrack{mime: webrtc.MimeTypeVP8}
	source, codec, ok := remoteSource(track, nil)
	if !ok || source != rtpSource(track) || codec.capability.MimeType != webrtc.MimeTypeVP8 {
		t.Fatalf("VP8 track: ok=%v codec=%q", ok, codec.capability.MimeType)
	}
	if _, _, ok := remoteSource(&redFakeTrack{mime: "video/av1"}, nil); ok {
		t.Fatal("an undecodable codec must be refused")
	}
}
