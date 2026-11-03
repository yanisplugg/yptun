package vp8channel

import (
	"bytes"
	"encoding/binary"
	"errors"
	"testing"

	"github.com/pion/rtp"
	"github.com/pion/rtp/codecs"
	"github.com/pion/rtp/codecs/vp9"
	"github.com/pion/webrtc/v4"
)

// pionVideoMTU is what pion hands a payloader for one packet: its outbound
// MTU of 1200 less the RTP header.
const pionVideoMTU = 1188

// frameOf builds a frame the way the rest of the transport does: the VP8
// keepalive, the epoch header, the payload.
func frameOf(token, src, dst uint32, payload []byte) []byte {
	hdr := buildEpochHeaderTo(token, src, dst)
	return append(hdr[:], payload...)
}

func TestTheVP9HeadIsARealKeyframe(t *testing.T) {
	var h vp9.Header
	if err := h.Unmarshal(vp9Head); err != nil {
		t.Fatalf("the head does not parse as a VP9 frame: %v", err)
	}
	if h.NonKeyFrame {
		t.Fatal("the head is not a keyframe")
	}
	if h.Width() != vp9HeadWidth || h.Height() != vp9HeadHeight {
		t.Fatalf("the head is %dx%d, the descriptor announces %dx%d", h.Width(), h.Height(), vp9HeadWidth, vp9HeadHeight)
	}
}

func TestCarrierForNamesTheCodecAnEngineAsksFor(t *testing.T) {
	for _, mime := range []string{"", webrtc.MimeTypeVP8, "video/vp8"} {
		c, err := carrierFor(mime)
		if err != nil || c.capability.MimeType != webrtc.MimeTypeVP8 || c.dress != nil {
			t.Fatalf("carrierFor(%q) = %+v, %v; want the transport's own VP8, undressed", mime, c, err)
		}
	}
	c, err := carrierFor("video/vp9")
	if err != nil {
		t.Fatalf("carrierFor(vp9): %v", err)
	}
	if c.capability.MimeType != webrtc.MimeTypeVP9 || c.dress == nil || !c.keyframes {
		t.Fatalf("carrierFor(vp9) = %+v; want VP9, dressed, keyframes on request", c)
	}
	if !c.accepts("video/VP9") || c.accepts(webrtc.MimeTypeVP8) || c.accepts(webrtc.MimeTypeH264) {
		t.Fatal("a VP9 carrier reads VP9 tracks and nothing else")
	}
	if _, err := carrierFor(webrtc.MimeTypeH264); !errors.Is(err, ErrCarrierCodec) {
		t.Fatalf("carrierFor(h264) error = %v, want %v", err, ErrCarrierCodec)
	}
}

func TestTheVP8CarrierReadsVP8AndRED(t *testing.T) {
	c, err := carrierFor("")
	if err != nil {
		t.Fatal(err)
	}
	if !c.accepts(webrtc.MimeTypeVP8) || !c.accepts("video/red") || c.accepts(webrtc.MimeTypeVP9) {
		t.Fatal("the VP8 carrier reads VP8 and its RED wrapper, not VP9")
	}
}

func TestVP9DressPutsTheFrameBehindTheHeadAndUndressFindsItAgain(t *testing.T) {
	payload := bytes.Repeat([]byte{0xab}, 3000)
	frame := frameOf(0x11223344, 0x00000005, 0x00000007, payload)

	sample := vp9Dress(nil, frame, false)
	if !bytes.HasPrefix(sample, vp9Head) {
		t.Fatal("a dressed sample starts with the VP9 keyframe")
	}
	if bytes.Contains(sample, vp8Keepalive) {
		t.Fatal("the VP8 keepalive rides a VP9 sample")
	}
	if got, want := len(sample), len(vp9Head)+len(frame)-len(vp8Keepalive)+vp9TrailerLen; got != want {
		t.Fatalf("dressed %d bytes, want %d: a frame with a payload is not padded", got, want)
	}

	view := vp9Undress(sample)
	if view == nil {
		t.Fatal("undress refused a dressed sample")
	}
	token, src, dst, ok := parseEpochHeader(view)
	if !ok || token != 0x11223344 || src != 5 || dst != 7 {
		t.Fatalf("header after the round trip = %08x %d %d ok=%v", token, src, dst, ok)
	}
	if !bytes.Equal(view[epochHdrLen:], payload) {
		t.Fatal("payload changed in the round trip")
	}
}

// A keyframe the relay is to recognise has to span two packets (measured:
// MTS Link's SFU never counts a single-packet keyframe), so the frame that
// answers a keyframe request is padded past one.
func TestVP9DressPadsAKeyframePastOnePacket(t *testing.T) {
	bare := frameOf(1, 2, 0, nil)
	sample := vp9Dress(nil, bare, true)
	if len(sample) < vp9KeyframeSpan {
		t.Fatalf("bare keepalive dressed to %d bytes, want at least %d", len(sample), vp9KeyframeSpan)
	}
	if packets := newVP9Payloader().Payload(pionVideoMTU, sample); len(packets) < 2 {
		t.Fatalf("a bare keepalive went out in %d packet(s), want two or more", len(packets))
	}
	view := vp9Undress(sample)
	if len(view) != epochHdrLen {
		t.Fatalf("a padded keepalive undresses to %d bytes, want the bare %d: padding must not reach KCP", len(view), epochHdrLen)
	}
	if _, src, _, ok := parseEpochHeader(view); !ok || src != 2 {
		t.Fatal("the padded keepalive lost its header")
	}

	// The idle beacon is the same bare frame ten times a second: it stays small.
	if beacon := vp9Dress(nil, bare, false); len(beacon) != len(vp9Head)+epochHdrLen-len(vp8Keepalive)+vp9TrailerLen {
		t.Fatalf("an idle keepalive dressed to %d bytes: only a keyframe is padded", len(beacon))
	}
	// A keyframe that is long enough on its own gets no padding.
	long := frameOf(1, 2, 0, bytes.Repeat([]byte{1}, 2*vp9KeyframeSpan))
	if got, want := len(vp9Dress(nil, long, true)), len(vp9Dress(nil, long, false)); got != want {
		t.Fatalf("a long keyframe dressed to %d bytes, want the unpadded %d", got, want)
	}
}

func TestVP9DressReusesTheBufferItIsHanded(t *testing.T) {
	frame := frameOf(1, 2, 3, []byte("payload"))
	buf := make([]byte, 0, 4096)
	sample := vp9Dress(buf, frame, false)
	if &sample[0] != &buf[:1][0] {
		t.Fatal("dress allocated although the buffer had room")
	}
}

func TestVP9UndressRefusesWhatIsNotADressedSample(t *testing.T) {
	good := vp9Dress(nil, frameOf(1, 2, 3, []byte("payload")), false)
	for name, sample := range map[string][]byte{
		"empty":                    nil,
		"shorter than a header":    good[:10],
		"the relay's own keyframe": append(append([]byte(nil), vp9Head...), make([]byte, 141)...),
		"head length past the end": binary.BigEndian.AppendUint16(append([]byte(nil), good[:len(good)-2]...), 60000),
		"head length under the header offset": binary.BigEndian.AppendUint16(
			append([]byte(nil), good[:len(good)-2]...), tokenOff-1),
	} {
		view := vp9Undress(sample)
		if view == nil {
			continue
		}
		if _, _, _, ok := parseEpochHeader(view); ok {
			t.Errorf("%s: undressed to a frame with a valid header", name)
		}
	}
}

// What libwebrtc sent for a single-layer VP9 stream, read off the SFU's
// forward of a browser (2026-10-11): 9b 90 9a 10 02 80 01 68 on the first
// packet of a 640x360 keyframe with picture id 0x109a, 91 90 9a on the next,
// 95 90 9a on its last.
func TestVP9PayloaderPacketizesLikeABrowser(t *testing.T) {
	p := newVP9Payloader()
	p.pictureID = 0x109a
	sample := vp9Dress(nil, frameOf(1, 2, 3, bytes.Repeat([]byte{0x5a}, 3000)), false)

	packets := p.Payload(pionVideoMTU, sample)
	if len(packets) != 3 {
		t.Fatalf("%d packets for a %d-byte sample, want 3", len(packets), len(sample))
	}
	first := binary.BigEndian.AppendUint16(
		binary.BigEndian.AppendUint16([]byte{0x9b, 0x90, 0x9a, 0x10}, vp9HeadWidth), vp9HeadHeight)
	if !bytes.HasPrefix(packets[0], first) {
		t.Fatalf("first packet starts % x, want % x", packets[0][:len(first)], first)
	}
	if !bytes.HasPrefix(packets[1], []byte{0x91, 0x90, 0x9a}) {
		t.Fatalf("middle packet starts % x, want 91 90 9a", packets[1][:3])
	}
	if !bytes.HasPrefix(packets[2], []byte{0x95, 0x90, 0x9a}) {
		t.Fatalf("last packet starts % x, want 95 90 9a", packets[2][:3])
	}

	var whole []byte
	for i, pkt := range packets {
		if len(pkt) > pionVideoMTU {
			t.Fatalf("packet %d is %d bytes, over the %d it was given", i, len(pkt), pionVideoMTU)
		}
		var d codecs.VP9Packet
		body, err := d.Unmarshal(pkt)
		if err != nil {
			t.Fatalf("packet %d does not parse as VP9: %v", i, err)
		}
		if d.P || !d.F || d.PictureID != 0x109a || d.B != (i == 0) || d.E != (i == len(packets)-1) {
			t.Fatalf("packet %d descriptor = %+v", i, d)
		}
		whole = append(whole, body...)
	}
	if !bytes.Equal(whole, sample) {
		t.Fatal("the packets do not add up to the sample")
	}
}

func TestVP9PayloaderCountsPicturesAndWraps(t *testing.T) {
	p := newVP9Payloader()
	p.pictureID = 0x7fff
	sample := vp9Dress(nil, frameOf(1, 2, 3, []byte("x")), false)
	first := p.Payload(pionVideoMTU, sample)
	second := p.Payload(pionVideoMTU, sample)
	if !bytes.HasPrefix(first[0][1:], []byte{0xff, 0xff}) || !bytes.HasPrefix(second[0][1:], []byte{0x80, 0x00}) {
		t.Fatalf("picture ids % x then % x, want 0xffff then 0x8000", first[0][1:3], second[0][1:3])
	}
	if got := p.Payload(pionVideoMTU, nil); len(got) != 0 {
		t.Fatal("an empty sample makes no packet")
	}
	if got := p.Payload(4, sample); len(got) != 0 {
		t.Fatal("an MTU the descriptor does not fit makes no packet")
	}
}

// packetsOf is a sample on the wire: the payloader's packets with rising
// sequence numbers and the marker on the last.
func packetsOf(p *vp9Payloader, seq uint16, sample []byte) []*rtp.Packet {
	payloads := p.Payload(pionVideoMTU, sample)
	out := make([]*rtp.Packet, len(payloads))
	for i, payload := range payloads {
		if i > 0 {
			seq++
		}
		out[i] = &rtp.Packet{
			Header:  rtp.Header{SequenceNumber: seq, Marker: i == len(payloads)-1},
			Payload: payload,
		}
	}
	return out
}

func TestVP9FrameStateAssemblesWhatThePayloaderSent(t *testing.T) {
	p := newVP9Payloader()
	payload := bytes.Repeat([]byte{0xc3}, 5000)
	frame := frameOf(9, 8, 7, payload)
	state := &vp9FrameState{}

	var got []byte
	packets := packetsOf(p, 100, vp9Dress(nil, frame, false))
	for i, pkt := range packets {
		got = state.processRTPPacket(pkt)
		if (got != nil) != (i == len(packets)-1) {
			t.Fatalf("packet %d of %d: frame delivered = %v", i, len(packets), got != nil)
		}
	}
	token, src, dst, ok := parseEpochHeader(got)
	if !ok || token != 9 || src != 8 || dst != 7 || !bytes.Equal(got[epochHdrLen:], payload) {
		t.Fatal("the assembled frame is not the one sent")
	}

	// The next frame assembles on the same state.
	small := frameOf(9, 8, 7, []byte("next"))
	for _, pkt := range packetsOf(p, 100+uint16(len(packets)), vp9Dress(nil, small, false)) { //nolint:gosec // test sizes
		got = state.processRTPPacket(pkt)
	}
	if got == nil || string(got[epochHdrLen:]) != "next" {
		t.Fatalf("second frame = %q", got)
	}
}

func TestVP9FrameStateDropsAFrameWithAHole(t *testing.T) {
	p := newVP9Payloader()
	state := &vp9FrameState{}
	packets := packetsOf(p, 10, vp9Dress(nil, frameOf(1, 2, 3, bytes.Repeat([]byte{1}, 4000)), false))
	if len(packets) < 3 {
		t.Fatalf("want a frame of three packets or more, got %d", len(packets))
	}
	for i, pkt := range packets {
		if i == 1 {
			continue // lost
		}
		if got := state.processRTPPacket(pkt); got != nil {
			t.Fatal("a frame with a lost packet was delivered")
		}
	}
	// and the frame after it is whole again
	var got []byte
	for _, pkt := range packetsOf(p, 10+uint16(len(packets)), vp9Dress(nil, frameOf(1, 2, 3, []byte("after")), false)) { //nolint:gosec // test sizes
		got = state.processRTPPacket(pkt)
	}
	if got == nil || string(got[epochHdrLen:]) != "after" {
		t.Fatal("the frame after a lost one did not assemble")
	}
}

// An idle slot carries a keyframe of the relay's own every few seconds (209
// bytes, measured): a VP9 frame that is not one of ours. The bytes here are a
// stand-in of that size, not the relay's.
func TestVP9FrameStateIgnoresAFrameThatIsNotOurs(t *testing.T) {
	state := &vp9FrameState{}
	placeholder := append([]byte{0x9f, 0x80, 0x01, 0x10, 0x02, 0x80, 0x01, 0x68}, bytes.Repeat([]byte{0x82}, 201)...)
	got := state.processRTPPacket(&rtp.Packet{
		Header:  rtp.Header{SequenceNumber: 1, Marker: true},
		Payload: placeholder,
	})
	if got != nil {
		if _, _, _, ok := parseEpochHeader(got); ok {
			t.Fatal("a foreign VP9 frame passed as a frame of ours")
		}
	}
}

func TestVP9FrameStateBoundsAFrameThatNeverEnds(t *testing.T) {
	state := &vp9FrameState{}
	chunk := append([]byte{0x91, 0x80, 0x01}, bytes.Repeat([]byte{7}, 1100)...)
	start := append([]byte{0x99, 0x80, 0x01}, bytes.Repeat([]byte{7}, 1100)...)
	_ = state.processRTPPacket(&rtp.Packet{Header: rtp.Header{SequenceNumber: 0}, Payload: start})
	for seq := uint16(1); seq < 400; seq++ {
		if got := state.processRTPPacket(&rtp.Packet{Header: rtp.Header{SequenceNumber: seq}, Payload: chunk}); got != nil {
			t.Fatal("a frame with no end was delivered")
		}
		if len(state.frameBuf) > maxAssembledFrameSize {
			t.Fatalf("the buffer grew to %d bytes, over the %d cap", len(state.frameBuf), maxAssembledFrameSize)
		}
	}
}

// A sample reaches the track dressed for the carrier, and the rate limiter
// and the caller's buffer see none of it.
func TestWriteSampleDressesForTheCarrier(t *testing.T) {
	var written [][]byte
	c, err := carrierFor("video/vp9")
	if err != nil {
		t.Fatal(err)
	}
	tr := &streamTransport{carrier: c}
	tr.sampleWriter = func(data []byte) bool {
		written = append(written, append([]byte(nil), data...))
		return true
	}
	frame := frameOf(4, 5, 6, []byte("hello"))
	before := append([]byte(nil), frame...)
	if !tr.writeSampleLocked(frame) {
		t.Fatal("write refused")
	}
	if !bytes.Equal(frame, before) {
		t.Fatal("the caller's frame was changed")
	}
	if len(written) != 1 || !bytes.HasPrefix(written[0], vp9Head) {
		t.Fatal("the track did not get a VP9 sample")
	}
	if view := vp9Undress(written[0]); view == nil || string(view[epochHdrLen:]) != "hello" {
		t.Fatal("the sample does not undress to the frame")
	}

	// The idle beacon is a bare header through the same door: not padded.
	written = nil
	bare := tr.epochHeader()
	_ = tr.writeSampleLocked(bare[:])
	if len(written) != 1 || len(written[0]) >= vp9KeyframeSpan {
		t.Fatalf("an idle keepalive went out as %d bytes", len(written[0]))
	}

	vp8, err := carrierFor("")
	if err != nil {
		t.Fatal(err)
	}
	tr.carrier, written = vp8, nil
	_ = tr.writeSampleLocked(frame)
	if len(written) != 1 || !bytes.Equal(written[0], frame) {
		t.Fatal("the VP8 carrier must write a frame as it is")
	}
}

// A relay that asked for a keyframe forwards nothing of the track until one
// comes: the writer answers on its next tick, not at the next two-second beat.
func TestAKeyframeRequestIsAnsweredOnTheNextTick(t *testing.T) {
	var written [][]byte
	c, err := carrierFor("video/vp9")
	if err != nil {
		t.Fatal(err)
	}
	tr := &streamTransport{carrier: c, bindingToken: 7, localEpoch: 9}
	tr.sampleWriter = func(data []byte) bool {
		written = append(written, append([]byte(nil), data...))
		return true
	}
	w := &writerState{p: tr, forceKeepaliveEvery: 1000}

	w.forceKeepalive()
	if len(written) != 0 {
		t.Fatal("a keepalive went out with no request and no beat due")
	}
	tr.requestKeyframe()
	w.forceKeepalive()
	if len(written) != 1 {
		t.Fatalf("%d samples after a keyframe request, want 1", len(written))
	}
	if len(written[0]) < vp9KeyframeSpan {
		t.Fatalf("the answer is %d bytes, under the %d a keyframe has to span", len(written[0]), vp9KeyframeSpan)
	}
	w.forceKeepalive()
	if len(written) != 1 {
		t.Fatal("one request was answered twice")
	}
}

// A relay that takes none of our keyframes asks without end; the answers are
// spaced, so that state costs a keyframe a gap and not one a tick.
func TestKeyframeRequestsAreAnsweredAGapApart(t *testing.T) {
	writes := 0
	c, err := carrierFor("video/vp9")
	if err != nil {
		t.Fatal(err)
	}
	tr := &streamTransport{carrier: c}
	tr.sampleWriter = func([]byte) bool { writes++; return true }
	w := &writerState{p: tr, forceKeepaliveEvery: 1000, keyframeGap: 5}

	for range 20 {
		tr.requestKeyframe()
		w.forceKeepalive()
	}
	if writes != 4 {
		t.Fatalf("%d keyframes for 20 ticks of requests with a gap of 5 ticks, want 4", writes)
	}
}

// The VP8 relays never ask this way and their frames need no answer: a request
// on the transport's own carrier changes nothing.
func TestAKeyframeRequestOnTheVP8CarrierIsIgnored(t *testing.T) {
	writes := 0
	tr := &streamTransport{}
	tr.sampleWriter = func([]byte) bool { writes++; return true }
	w := &writerState{p: tr, forceKeepaliveEvery: 1000}
	tr.requestKeyframe()
	w.forceKeepalive()
	if writes != 0 {
		t.Fatal("a keyframe request changed what the VP8 carrier writes")
	}
}
