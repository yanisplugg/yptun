// Package vkcalls implements an engine.Session over the VK Calls SERVER
// topology: a signaling WebSocket against calls.okcdn.ru plus one bundled
// PeerConnection whose answer is shaped into the reference client's byte
// order — the SFU gates transport creation on that form.
//
// The negotiation contract was established by the vk spike
// (docs/spikes/vkcalls): the answer must carry the current offer's m-line
// payload lists, codec blocks and section directions in the reference
// attribute order, with our own ICE/DTLS fields and publish SSRCs, while
// media forwarding is driven by the update-display-layout control command
// on the producerCommand data channel, addressed by compact stream ids from
// the producerNotification registry.
package vkcalls

import (
	"errors"
	"fmt"
	"math/rand"
	"regexp"
	"strconv"
	"strings"
)

// maxSDPSize bounds accepted SDP inputs; live offers are ~70 KB.
const (
	maxSDPSize  = 1 << 20
	trickleLn   = "a=ice-options:trickle"
	activeLn    = "a=setup:active"
	kindAudio   = "audio"
	kindVideo   = "video"
	dirSendOnly = "sendonly"
	dirRecvOnly = "recvonly"
	zeroAddrLn  = "c=IN IP4 0.0.0.0"
)

// Shaping errors. They name the missing piece, never the values.
var (
	ErrShapeNoCrypto          = errors.New("vkcalls: native answer carries no ICE/DTLS fields")
	ErrShapeNoPublish         = errors.New("vkcalls: native answer declares no video publish section")
	ErrShapeSectionWithoutMID = errors.New("vkcalls: offer section without a=mid")
	ErrShapeDirection         = errors.New("vkcalls: offer section with unsupported direction")
	ErrShapeBundle            = errors.New("vkcalls: offer without a BUNDLE group")
	ErrShapeSDPTooLarge       = errors.New("vkcalls: SDP exceeds size bound")
)

var (
	iceUfragLine   = regexp.MustCompile(`(?m)^a=ice-ufrag:(\S+)$`)
	icePwdLine     = regexp.MustCompile(`(?m)^a=ice-pwd:(\S+)$`)
	fingerprintLin = regexp.MustCompile(`(?m)^(a=fingerprint:.*)$`)
	bundleLine     = regexp.MustCompile(`(?m)^a=group:BUNDLE (.*)$`)
	ssrcCNameLine  = regexp.MustCompile(`(?m)^a=ssrc:(\d+) cname:(\S+)$`)
	msidLine       = regexp.MustCompile(`(?m)^(a=msid:\S+ \S+)$`)
	midLine        = regexp.MustCompile(`^a=mid:(\S+)$`)
)

// section is one m= section split into its lines.
type section []string

func (s section) mid() string {
	for _, line := range s {
		if m := midLine.FindStringSubmatch(line); m != nil {
			return m[1]
		}
	}
	return ""
}

func (s section) direction() string {
	for _, line := range s {
		switch line {
		case "a=sendonly", "a=recvonly", "a=sendrecv", "a=inactive":
			return strings.TrimPrefix(line, "a=")
		}
	}
	return ""
}

func (s section) kind() string {
	head := strings.Fields(s[0])
	if len(head) == 0 {
		return ""
	}
	return strings.TrimPrefix(head[0], "m=")
}

// splitSections splits SDP text into its m= sections, dropping empty lines
// and everything before the first one. Blank input yields no sections.
func splitSections(sdp string) ([]section, error) {
	if len(sdp) > maxSDPSize {
		return nil, ErrShapeSDPTooLarge
	}
	var sections []section
	for _, line := range strings.Split(strings.ReplaceAll(sdp, "\r\n", "\n"), "\n") {
		if line == "" {
			continue
		}
		if strings.HasPrefix(line, "m=") {
			sections = append(sections, section{line})
			continue
		}
		if len(sections) > 0 {
			sections[len(sections)-1] = append(sections[len(sections)-1], line)
		}
	}
	return sections, nil
}

// ShapeState carries the mutable per-session shaping state across re-answers:
// the generated o= session id, the bumping o= version and the stable
// synthetic SSRCs of audio publish slots (we send nothing there, but the
// reference client declares them and the SFU expects stable attributions).
type ShapeState struct {
	SessionID int64
	Version   int
	AudioSSRC map[string]uint32
	// NativeVideoSSRC: EXPERIMENT (temporary) the native publish section's
	// FID group and cname lines.
	NativeVideoSSRC []string
	// NativeAudioSSRC: EXPERIMENT (temporary) the native answer's real
	// audio sender SSRC per mid.
	NativeAudioSSRC map[string]uint32
}

// NewShapeState builds shaping state with a fresh session id.
func NewShapeState() *ShapeState {
	return &ShapeState{
		SessionID: 1000000000000000000 + rand.Int63n(800000000000000000), //nolint:gosec // SDP session id, not a secret
		AudioSSRC: map[string]uint32{},
	}
}

func (st *ShapeState) audioSSRC(mid string) uint32 {
	if ssrc, ok := st.AudioSSRC[mid]; ok {
		return ssrc
	}
	ssrc := uint32(1000000000 + rand.Int63n(1000000000)) //nolint:gosec // synthetic RTP SSRC
	st.AudioSSRC[mid] = ssrc
	return ssrc
}

// ShapeAnswer reconstructs the answer for offer in the reference client's
// byte order. Every content-bearing line — m= payload lists, extmap and
// codec blocks, section directions — comes from the offer; the crypto fields
// and the video publish declaration come from native, the Pion-generated
// answer. The session header is fully generated: the transport gate accepts
// it (spike hybrid-16) and no captured template is needed.
func ShapeAnswer(offer, native string, st *ShapeState) (string, error) { //nolint:gocognit,gocyclo,cyclop
	offer = strings.ReplaceAll(offer, "\r\n", "\n")
	native = strings.ReplaceAll(native, "\r\n", "\n")
	ufrag := iceUfragLine.FindStringSubmatch(native)
	pwd := icePwdLine.FindStringSubmatch(native)
	fingerprint := fingerprintLin.FindStringSubmatch(native)
	if ufrag == nil || pwd == nil || fingerprint == nil {
		return "", ErrShapeNoCrypto
	}
	publishSSRC, cname, msid, msidStream, err := videoPublishOf(native)
	if err != nil {
		return "", err
	}
	st.NativeVideoSSRC = nil
	st.NativeAudioSSRC = map[string]uint32{}
	if nativeSections, splitErr := splitSections(native); splitErr == nil { //nolint:nestif
		for _, sec := range nativeSections {
			if sec.kind() == kindAudio && sec.direction() == "sendonly" {
				for _, line := range sec {
					if m := ssrcCNameLine.FindStringSubmatch(line); m != nil {
						if v, perr := strconv.ParseUint(m[1], 10, 32); perr == nil {
							st.NativeAudioSSRC[sec.mid()] = uint32(v)
						}
						break
					}
				}
			}
			if sec.kind() != kindVideo || sec.direction() != dirSendOnly {
				continue
			}
			for _, line := range sec {
				isCName := strings.HasPrefix(line, "a=ssrc:") && strings.Contains(line, " cname:")
				if strings.HasPrefix(line, "a=ssrc-group:") || isCName {
					st.NativeVideoSSRC = append(st.NativeVideoSSRC, line)
				}
			}
		}
	}
	bundle := bundleLine.FindStringSubmatch(offer)
	if bundle == nil {
		return "", ErrShapeBundle
	}

	offerSections, err := splitSections(offer)
	if err != nil {
		return "", err
	}
	st.Version += 2

	out := []string{
		"v=0",
		fmt.Sprintf("o=- %d %d IN IP4 127.0.0.1", st.SessionID, st.Version),
		"s=-",
		"t=0 0",
		"a=group:BUNDLE " + bundle[1],
		"a=msid-semantic: WMS " + strings.TrimPrefix(strings.Fields(msid)[0], "a=msid:"),
	}
	for _, sec := range offerSections {
		crypto := cryptoBlock(ufrag[1], pwd[1], fingerprint[1])
		lines, err := shapeSection(sec, crypto, st, msid, msidStream, publishSSRC, cname)
		if err != nil {
			return "", err
		}
		out = append(out, lines...)
	}
	return strings.Join(out, "\r\n"), nil
}

// cryptoBlock is the per-section ICE/DTLS block: every section carries the
// session's credentials with trickle and an active DTLS role, as the
// reference client writes them.
func cryptoBlock(ufrag, pwd, fingerprint string) []string {
	return []string{
		"a=ice-ufrag:" + ufrag,
		"a=ice-pwd:" + pwd,
		trickleLn,
		fingerprint,
		activeLn,
	}
}

// shapeSection reconstructs one offer section in the reference byte order.
func shapeSection(sec section, crypto []string, st *ShapeState, msid, msidStream string,
	_ uint32, cname string,
) ([]string, error) {
	mid := sec.mid()
	if mid == "" {
		return nil, ErrShapeSectionWithoutMID
	}
	kind := sec.kind()
	out := []string{sec[0], zeroAddrLn}
	if kind == "application" {
		out = append(out, crypto...)
		out = append(out, "a=mid:"+mid, "a=sctp-port:5000")
		return out, nil
	}
	out = append(out, "a=rtcp:9 IN IP4 0.0.0.0")
	out = append(out, crypto...)
	out = append(out, "a=mid:"+mid)
	for _, line := range sec {
		if strings.HasPrefix(line, "a=extmap:") {
			out = append(out, line)
		}
	}
	switch sec.direction() {
	case dirSendOnly:
		// The SFU sends here: answer recv-only with the offered vocabulary.
		out = append(out, "a=recvonly", "a=rtcp-mux")
		out = append(out, codecLines(sec)...)
		return out, nil
	case dirRecvOnly:
		// Our publish slot. Audio slots keep a stable synthetic SSRC; the
		// video slot declares the Pion track's real SSRC plainly — the
		// engine sends no rid headers, so the reference rid/simulcast form
		// would never match a stream.
		out = append(out, "a=sendonly")
		if kind == kindAudio {
			out = append(out, msidStream+" "+mid+"-audio")
		} else {
			out = append(out, msid)
		}
		out = append(out, "a=rtcp-mux")
		out = append(out, codecLines(sec)...)
		if kind == kindAudio {
			ssrc := st.audioSSRC(mid)
			out = append(out, "a=ssrc:"+strconv.FormatUint(uint64(ssrc), 10)+" cname:"+cname)
			return out, nil
		}
		// The video slot takes the browser's simulcast declaration: three
		// rid constraints with the engine publishing the low layer only, and
		// no a=ssrc — the SFU binds the stream by the rid header instead.
		// This is the form the SDK's finalizeAnswer writes and the SFU
		// forwards (spike rid-onelayer).
		w, h, fps, kbps := layerProfile()
		out = append(out,
			fmt.Sprintf("a=rid:l send max-width=%d;max-height=%d;max-fps=%d;max-br=%d", w, h, fps, kbps*1000),
			"a=rid:m send max-width=640;max-height=360;max-fps=20;max-br=500000",
			"a=rid:h send max-width=1280;max-height=720;max-fps=20;max-br=1200000",
			"a=simulcast:send l;m;h")
		return out, nil
	default:
		return nil, fmt.Errorf("%w: mid %s", ErrShapeDirection, mid)
	}
}

// codecLines returns the offer's rtpmap/fmtp/rtcp-fb block in offer order.
func codecLines(sec section) []string {
	var out []string
	for _, line := range sec {
		switch {
		case strings.HasPrefix(line, "a=rtpmap:"), strings.HasPrefix(line, "a=fmtp:"), strings.HasPrefix(line, "a=rtcp-fb:"):
			out = append(out, line)
		}
	}
	return out
}

// videoPublishOf extracts the sendonly video section's SSRC, CNAME, msid
// line and msid stream prefix from the native answer.
func videoPublishOf(native string) (uint32, string, string, string, error) {
	sections, err := splitSections(native)
	if err != nil {
		return 0, "", "", "", err
	}
	for _, sec := range sections {
		if sec.kind() != kindVideo || sec.direction() != dirSendOnly {
			continue
		}
		msid := "a=msid:proofkit video"
		if found := msidLine.FindStringSubmatch(strings.Join(sec, "\n")); found != nil {
			msid = found[1]
		}
		for _, line := range sec {
			m := ssrcCNameLine.FindStringSubmatch(line)
			if m == nil {
				continue
			}
			ssrc, err := strconv.ParseUint(m[1], 10, 32)
			if err != nil {
				return 0, "", "", "", ErrShapeNoPublish
			}
			return uint32(ssrc), m[2], msid, strings.Fields(msid)[0], nil
		}
	}
	return 0, "", "", "", ErrShapeNoPublish
}

// vp8Only restricts a publish section to the offer's VP8 payload type and
// its rtx, rewriting the m= line.
