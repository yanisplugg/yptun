package vp8channel

import (
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"strings"

	"github.com/pion/rtp"
	"github.com/pion/rtp/codecs"
	"github.com/pion/webrtc/v4"
)

// A carrier is the video codec the transport's frames ride as. VP8 is the
// transport's own: every frame it builds starts with the VP8 keepalive
// keyframe (wire.go) and goes to the track as it is. A relay that forwards no
// VP8 (MTS Link's SFU answers H264 and VP9 only) is given the same frames in
// the codec it does take: the engine names it (engine.VideoCodecSelector), the
// frames are re-headed where they reach the track and un-headed where they
// leave it, and nothing in between - KCP, lanes, batching, the epoch header -
// knows the difference.

// ErrCarrierCodec is an engine asking for a codec the transport cannot dress
// its frames in.
var ErrCarrierCodec = errors.New("vp8channel: no carrier for the engine's video codec")

// frameAssembler turns the RTP packets of one track into whole frames in the
// transport's own layout: the epoch header at tokenOff, the KCP bytes behind
// it. A frame is returned once, valid until the next call.
type frameAssembler interface {
	processRTPPacket(pkt *rtp.Packet) []byte
}

type carrier struct {
	// capability is what the published track announces.
	capability webrtc.RTPCodecCapability
	// trackOptions are passed to the track; the VP9 carrier brings its own
	// packetizer.
	trackOptions []func(*webrtc.TrackLocalStaticRTP)
	// dress re-heads a frame for the track, into dst when it has the room;
	// nil when a frame goes out as it is. keyframe asks for a frame the relay
	// will count as a keyframe, whatever that takes on this carrier.
	dress func(dst, frame []byte, keyframe bool) []byte
	// newAssembler makes the reader of one remote track.
	newAssembler func() frameAssembler
	// reads lists the MIME types of the remote tracks that are read; the
	// rest are drained.
	reads []string
	// keyframes says the relay asks for keyframes (RTCP PLI) and forwards
	// nothing of a track until one arrives, so a request is answered at once.
	keyframes bool
}

// carrierFor is the carrier for the codec an engine names; none named is VP8.
func carrierFor(mime string) (*carrier, error) {
	switch {
	case mime == "", strings.EqualFold(mime, webrtc.MimeTypeVP8):
		return &carrier{
			capability:   webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP8, ClockRate: 90000},
			newAssembler: func() frameAssembler { return &vp8FrameState{} },
			reads:        []string{webrtc.MimeTypeVP8, mimeTypeRED},
		}, nil
	case strings.EqualFold(mime, webrtc.MimeTypeVP9):
		return &carrier{
			capability: webrtc.RTPCodecCapability{
				MimeType: webrtc.MimeTypeVP9, ClockRate: 90000, SDPFmtpLine: "profile-id=0",
			},
			trackOptions: []func(*webrtc.TrackLocalStaticRTP){
				webrtc.WithPayloader(func(webrtc.RTPCodecCapability) (rtp.Payloader, error) {
					return newVP9Payloader(), nil
				}),
			},
			dress:        vp9Dress,
			newAssembler: func() frameAssembler { return &vp9FrameState{} },
			reads:        []string{webrtc.MimeTypeVP9},
			keyframes:    true,
		}, nil
	default:
		return nil, fmt.Errorf("%w: %q", ErrCarrierCodec, mime)
	}
}

// accepts says whether a remote track of this MIME type carries frames. A nil
// carrier is the transport's own VP8.
func (c *carrier) accepts(mime string) bool {
	if c == nil {
		return strings.EqualFold(mime, webrtc.MimeTypeVP8) || strings.EqualFold(mime, mimeTypeRED)
	}
	for _, m := range c.reads {
		if strings.EqualFold(mime, m) {
			return true
		}
	}
	return false
}

// assembler is the reader of one remote track.
func (c *carrier) assembler() frameAssembler {
	if c == nil {
		return &vp8FrameState{}
	}
	return c.newAssembler()
}

// answersKeyframeRequests says whether a relay's keyframe request is answered.
func (c *carrier) answersKeyframeRequests() bool { return c != nil && c.keyframes }

// vp9Head is a real libvpx keyframe of a black 320x180 picture, what a browser
// encodes for one (WebCodecs, vp09.00.10.08). MTS Link's SFU forwards a track
// only from a keyframe it recognises, and what it recognises is a frame that
// opens with one and spans more than a packet; whatever follows the keyframe
// inside the frame is forwarded untouched (measured 2026-10-11: a frame cut
// short of a whole keyframe is not taken, one with our bytes behind it is, at
// 19 Mbit/s, byte for byte). The picture's size is of no account - 16x16 was
// taken as readily - so it is a small one of a shape cameras have.
var vp9Head = []byte{ //nolint:gochecknoglobals // wire constant, like vp8Keepalive
	0x82, 0x49, 0x83, 0x42, 0x60, 0x13, 0xf0, 0x0b, 0x36, 0x66, 0x38, 0x24, 0x1c, 0x19, 0x92, 0x00,
	0x00, 0x50, 0x43, 0xe8, 0xbf, 0x40, 0x00, 0x67, 0x3f, 0xfe, 0x2f, 0x20, 0x00, 0x04, 0xee, 0x66,
	0xa7, 0x89, 0xd3, 0xfb, 0x17, 0xd3, 0x70, 0x4c, 0xc4, 0xe7, 0xd9, 0x1b, 0x5b, 0xe5, 0xac, 0x4c,
	0x31, 0x1d, 0x90, 0x67, 0xac, 0xb3, 0x74, 0x35, 0x3e, 0x92, 0xab, 0xc1, 0xec, 0x2d, 0xe8, 0x00,
	0x85, 0x59, 0x2c, 0xf0,
}

const (
	// vp9HeadWidth and vp9HeadHeight are the picture vp9Head encodes, which
	// the packetizer announces in every keyframe's scalability structure.
	vp9HeadWidth  = 320
	vp9HeadHeight = 180
	// vp9TrailerLen is the two bytes that end a VP9 sample: how many bytes of
	// it, counted from the start, are the carrier's own - the keyframe and
	// any padding behind it. The reader finds the epoch header by them, so
	// the keyframe a sender uses is its own business and can change without
	// the readers changing with it.
	vp9TrailerLen = 2
	// vp9KeyframeSpan is how long a sample that has to count as a keyframe is
	// made: past what one RTP packet holds at pion's 1200-byte MTU.
	vp9KeyframeSpan = 1400
	// vp9DescriptorLen is the payload descriptor of every packet (flags and a
	// 15-bit picture id), vp9StructureLen the scalability structure the first
	// packet of a keyframe adds (one layer and its size).
	vp9DescriptorLen = 3
	vp9StructureLen  = 5
)

// vp9Padding is what a keyframe is padded with.
var vp9Padding [vp9KeyframeSpan]byte //nolint:gochecknoglobals // read-only zeros

// vp9Dress re-heads a frame of the transport's own layout for a VP9 track:
//
//	vp9Head | padding | token, src, dst, crc | KCP bytes | len(vp9Head + padding)
//
// The VP8 keepalive the frame came with is dropped; what follows it is kept
// as it is. A keyframe is padded to vp9KeyframeSpan, ahead of the epoch header
// so the padding never reaches KCP.
func vp9Dress(dst, frame []byte, keyframe bool) []byte {
	body := frame[min(len(vp8Keepalive), len(frame)):]
	pad := 0
	if keyframe {
		pad = max(vp9KeyframeSpan-len(vp9Head)-len(body)-vp9TrailerLen, 0)
	}
	out := dst[:0]
	out = append(out, vp9Head...)
	out = append(out, vp9Padding[:pad]...)
	out = append(out, body...)
	return binary.BigEndian.AppendUint16(out, uint16(len(vp9Head)+pad)) //nolint:gosec // at most vp9KeyframeSpan
}

// vp9Undress is the transport's own view of a VP9 sample: the epoch header at
// tokenOff, as in a VP8 frame, and the KCP bytes behind it. The bytes ahead of
// the header are the tail of the sender's keyframe and are never read. A
// sample that is not one of ours - the relay's own placeholder picture, a
// browser's camera - gives nil or a view whose header does not check.
func vp9Undress(sample []byte) []byte {
	n := len(sample)
	if n < vp9TrailerLen {
		return nil
	}
	head := int(binary.BigEndian.Uint16(sample[n-vp9TrailerLen:]))
	if head < tokenOff || head+epochHdrLen-tokenOff+vp9TrailerLen > n {
		return nil
	}
	return sample[head-tokenOff : n-vp9TrailerLen]
}

// vp9Payloader packetizes a sample the way libwebrtc does a single-layer VP9
// keyframe (read off the SFU's forward of a browser, 2026-10-11): flexible
// mode, a 15-bit picture id, and on the first packet the scalability
// structure - one spatial layer and its size. Every sample this transport
// writes opens with vp9Head, so every frame is announced as a keyframe: the
// relay starts forwarding again at the next frame after a lost packet,
// without waiting for an answer to its request.
type vp9Payloader struct {
	pictureID uint16
}

func newVP9Payloader() *vp9Payloader {
	var b [2]byte
	_, _ = rand.Read(b[:])
	return &vp9Payloader{pictureID: binary.BigEndian.Uint16(b[:]) & 0x7fff}
}

const (
	vp9FlagPictureID = 0x80   // I: a picture id follows
	vp9FlagFlexible  = 0x10   // F: flexible mode
	vp9FlagBegin     = 0x08   // B: first packet of the frame
	vp9FlagEnd       = 0x04   // E: last packet of the frame
	vp9FlagStructure = 0x02   // V: the scalability structure follows
	vp9FlagNotRef    = 0x01   // Z: not a reference for upper spatial layers
	vp9LongPictureID = 0x8000 // M, on the picture id's two bytes: the id is 15 bits
	vp9OneLayerSized = 0x10   // N_S=0, Y=1, G=0: one layer, its width and height
)

// Payload implements rtp.Payloader. Each packet is the sample's next bytes
// behind a fresh descriptor; the sample is copied, never kept.
func (p *vp9Payloader) Payload(mtu uint16, sample []byte) [][]byte {
	if len(sample) == 0 || int(mtu) <= vp9DescriptorLen+vp9StructureLen {
		return nil
	}
	id := p.pictureID
	p.pictureID = (p.pictureID + 1) & 0x7fff

	packets := make([][]byte, 0, len(sample)/int(mtu)+1)
	for rest, first := sample, true; len(rest) > 0; first = false {
		hdrLen := vp9DescriptorLen
		if first {
			hdrLen += vp9StructureLen
		}
		n := min(len(rest), int(mtu)-hdrLen)
		pkt := make([]byte, hdrLen, hdrLen+n)
		pkt[0] = vp9FlagPictureID | vp9FlagFlexible | vp9FlagNotRef
		binary.BigEndian.PutUint16(pkt[1:], vp9LongPictureID|id)
		if first {
			pkt[0] |= vp9FlagBegin | vp9FlagStructure
			pkt[3] = vp9OneLayerSized
			binary.BigEndian.PutUint16(pkt[4:], vp9HeadWidth)
			binary.BigEndian.PutUint16(pkt[6:], vp9HeadHeight)
		}
		if n == len(rest) {
			pkt[0] |= vp9FlagEnd
		}
		packets = append(packets, append(pkt, rest[:n]...))
		rest = rest[n:]
	}
	return packets
}

// vp9FrameState assembles the packets of one VP9 track into frames, with the
// same strictness as vp8FrameState: a sequence gap inside a frame drops the
// frame, and a frame that never ends is dropped at maxAssembledFrameSize.
type vp9FrameState struct {
	pkt         codecs.VP9Packet
	frameBuf    []byte
	lastSeq     uint16
	haveLastSeq bool
	frameValid  bool
}

func (s *vp9FrameState) drop() {
	s.frameValid = false
	s.frameBuf = s.frameBuf[:0]
}

// processRTPPacket returns a whole frame in the transport's layout when pkt
// completes one, nil otherwise.
func (s *vp9FrameState) processRTPPacket(pkt *rtp.Packet) []byte {
	if s.haveLastSeq && pkt.SequenceNumber != s.lastSeq+1 {
		s.drop()
	}
	s.lastSeq, s.haveLastSeq = pkt.SequenceNumber, true

	body, err := s.pkt.Unmarshal(pkt.Payload)
	if err != nil {
		s.drop()
		return nil
	}
	if s.pkt.B {
		s.frameBuf = s.frameBuf[:0]
		s.frameValid = true
	}
	if !s.frameValid {
		return nil
	}
	if len(s.frameBuf)+len(body) > maxAssembledFrameSize {
		s.drop()
		return nil
	}
	s.frameBuf = append(s.frameBuf, body...)
	if !s.pkt.E && !pkt.Marker {
		return nil
	}

	frame := s.frameBuf
	s.frameBuf = s.frameBuf[:0]
	s.frameValid = false
	return vp9Undress(frame)
}
