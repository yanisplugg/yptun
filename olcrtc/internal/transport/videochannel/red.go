package videochannel

import (
	"errors"
	"strings"

	"github.com/pion/interceptor"
	"github.com/pion/rtp"
	"github.com/pion/webrtc/v4"

	"github.com/openlibrecommunity/olcrtc/internal/transport/common"
)

// mimeTypeRED is the RFC 2198 wrapper WebRTC negotiates for video FEC
// (RFC 5109): every media packet's payload is one or more RED blocks, the
// last of them the primary encoding. A VK Calls SFU forwards camera video
// wrapped this way even when the publisher sent no redundancy, so a track
// that arrives as video/red carries the codec its primary blocks name.
const mimeTypeRED = "video/red"

const (
	redFollowBit      = 0x80 // F: another block header follows this one
	redPayloadMask    = 0x7f // the block's payload type
	redBlockHeader    = 4    // bytes of a redundant block's header
	redLengthHighByte = 2    // header byte holding the length's top two bits
	redLengthLowByte  = 3    // header byte holding the length's low eight bits
	redLengthHighMask = 0x03
	redFirstPackets   = 64 // packets inspected for a decodable primary block
)

// ErrREDHeader is returned for a RED payload whose block headers do not fit it.
var ErrREDHeader = errors.New("videochannel: malformed RED header")

// redSource reads a video/red track as the codec of its primary blocks:
// every RED packet is unwrapped to its primary block, and a packet whose
// primary block is another payload type (FEC) or does not parse is skipped.
type redSource struct {
	src     rtpSource
	redType uint8
	primary uint8
	pending *rtp.Packet // the packet remoteSource read to learn the codec
}

// ReadRTP implements rtpSource.
func (r *redSource) ReadRTP() (*rtp.Packet, interceptor.Attributes, error) {
	for {
		pkt, attrs, err := r.next()
		if err != nil {
			return nil, nil, err
		}
		if pkt.PayloadType != r.redType {
			return pkt, attrs, nil
		}
		payloadType, data, unwrapErr := common.REDPrimary(pkt.Payload)
		if unwrapErr != nil || payloadType != r.primary {
			continue
		}
		pkt.PayloadType = payloadType
		pkt.Payload = data
		return pkt, attrs, nil
	}
}

func (r *redSource) next() (*rtp.Packet, interceptor.Attributes, error) {
	if pkt := r.pending; pkt != nil {
		r.pending = nil
		return pkt, nil, nil
	}
	pkt, attrs, err := r.src.ReadRTP()
	if err != nil {
		return nil, nil, err //nolint:wrapcheck // the track's own end, as readDecoderInput reads it
	}
	return pkt, attrs, nil
}

// remoteTrack is the part of *webrtc.TrackRemote remoteSource reads.
type remoteTrack interface {
	rtpSource
	Codec() webrtc.RTPCodecParameters
	PayloadType() webrtc.PayloadType
}

// remoteSource picks what a remote track is read through and decoded as:
// the track itself for a codec the transport decodes, or, for a video/red
// track, the unwrapper for the codec its primary blocks name — found by
// payload type among the codecs negotiated for the receiver, from the first
// packets whose primary block is one the transport decodes.
func remoteSource(track remoteTrack, codecs []webrtc.RTPCodecParameters) (rtpSource, codecSpec, bool) {
	mime := track.Codec().MimeType
	if !strings.EqualFold(mime, mimeTypeRED) {
		codec, ok := codecSpecForMime(mime)
		return track, codec, ok
	}
	for range redFirstPackets {
		pkt, _, err := track.ReadRTP()
		if err != nil {
			return nil, codecSpec{}, false
		}
		payloadType, _, unwrapErr := common.REDPrimary(pkt.Payload)
		if unwrapErr != nil {
			continue
		}
		codec, ok := codecSpecForMime(mimeByPayloadType(codecs, payloadType))
		if !ok {
			continue // FEC, or a codec the transport does not decode
		}
		source := &redSource{src: track, redType: uint8(track.PayloadType()), primary: payloadType, pending: pkt}
		return source, codec, true
	}
	return nil, codecSpec{}, false
}

// mimeByPayloadType is the MIME type negotiated for a payload type, or "".
func mimeByPayloadType(codecs []webrtc.RTPCodecParameters, payloadType uint8) string {
	for _, codec := range codecs {
		if uint8(codec.PayloadType) == payloadType {
			return codec.MimeType
		}
	}
	return ""
}
