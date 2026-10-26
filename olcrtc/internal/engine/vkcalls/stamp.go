package vkcalls

import (
	"strings"

	"github.com/pion/interceptor"
	"github.com/pion/rtp"
	"github.com/pion/sdp/v3"

	"github.com/openlibrecommunity/olcrtc/internal/logger"
)

// sdesStamper writes the SDES mid and rid header extensions on every
// outgoing video packet. Pion negotiates both extensions but writes neither,
// and the SFU binds a published stream to its section and simulcast layer
// by them, as Chrome's packets carry them.
// The declared simulcast layer: the transport's small frame.
const (
	layerWidth  = 320
	layerHeight = 180
	layerFPS    = 15
	layerKbps   = 180
)

type sdesStamper struct {
	interceptor.NoOp
	mid, rid string
}

type sdesStamperFactory struct{ mid, rid string }

// NewInterceptor implements interceptor.Factory.
func (f sdesStamperFactory) NewInterceptor(string) (interceptor.Interceptor, error) {
	return &sdesStamper{mid: f.mid, rid: f.rid}, nil
}

// BindLocalStream stamps the stream's packets when it is video and the
// extensions were negotiated for it.
func (s *sdesStamper) BindLocalStream(info *interceptor.StreamInfo, w interceptor.RTPWriter) interceptor.RTPWriter {
	if !strings.HasPrefix(strings.ToLower(info.MimeType), "video/") {
		return w
	}
	var midID, ridID, astID uint8
	for _, ext := range info.RTPHeaderExtensions {
		switch ext.URI {
		case sdp.SDESMidURI:
			midID = uint8(ext.ID) //nolint:gosec // extension ids are 1..255
		case sdp.SDESRTPStreamIDURI:
			ridID = uint8(ext.ID) //nolint:gosec // extension ids are 1..255
		case sdp.ABSSendTimeURI:
			astID = uint8(ext.ID) //nolint:gosec // extension ids are 1..255
		}
	}
	if midID == 0 && ridID == 0 && astID == 0 {
		return w
	}
	logger.Debugf("vkcalls: stamping ssrc=%d mid=%s rid=%s", info.SSRC, s.mid, s.rid)
	return interceptor.RTPWriterFunc(func(header *rtp.Header, payload []byte, attrs interceptor.Attributes) (int, error) {
		if midID != 0 && s.mid != "" {
			_ = header.SetExtension(midID, []byte(s.mid))
		}
		if ridID != 0 && s.rid != "" {
			_ = header.SetExtension(ridID, []byte(s.rid))
		}
		return w.Write(header, payload, attrs)
	})
}

// publishMid is the mid of the offer's video section the SFU receives on.
func publishMid(offer string) string {
	sections, err := splitSections(strings.ReplaceAll(offer, "\r\n", "\n"))
	if err != nil {
		return ""
	}
	for _, sec := range sections {
		if sec.kind() == kindVideo && sec.direction() == dirRecvOnly {
			return sec.mid()
		}
	}
	return ""
}

// EXPERIMENT (temporary): encodeChangeSimulcast is the change-simulcast
// producer command: 07 00 seq mediaSource(1=camera) count, then per layer
// rid(str) width height fps bitrate.
func encodeChangeSimulcast(sequence int, rid string, width, height, fps, bitrate int) []byte {
	out := appendMPInt(nil, 7)
	out = appendMPInt(out, 0)
	out = appendMPInt(out, sequence)
	out = appendMPInt(out, 1)
	out = appendMPInt(out, 1)
	out = append(out, 0xA0|byte(len(rid))) //nolint:gosec // wire-format bit assembly, truncation is the point
	out = append(out, rid...)
	out = appendMPInt(out, width)
	out = appendMPInt(out, height)
	out = appendMPInt(out, fps)
	// The web client's serializeChangeSimulcast writes eT.enc(bitrate/1e3):
	// the layer bitrate travels in kbit/s, not bit/s.
	return appendMPInt(out, bitrate/1000)
}

// layerProfile is the simulcast layer the engine declares, in the change
// command and the answer's rid constraints: the small layer the transport
// publishes. The SFU forwards it whatever the transport's frame rate - the
// CI gate measured the full matrix with this declaration.
func layerProfile() (int, int, int, int) {
	return layerWidth, layerHeight, layerFPS, layerKbps
}

// encodePerfStatReport is the SDK's report-perf-stat producer command (type
// 1): [1, 0, sequence, framesDecoded, framesReceived]. The SDK sends it every
// statisticsInterval (5 s) from getStats; the SFU feeds its consumer-leg
// liveness with it — without the reports the forward is stalled within a
// minute (spike tun-rr-01).
func encodePerfStatReport(sequence int, framesDecoded, framesReceived uint32) []byte {
	out := appendMPInt(nil, 1)
	out = appendMPInt(out, 0)
	out = appendMPInt(out, sequence)
	out = appendMPInt(out, int(framesDecoded))
	return appendMPInt(out, int(framesReceived))
}
