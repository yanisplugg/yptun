package vkcalls

import (
	"fmt"
	"regexp"
	"strconv"
	"strings"

	"github.com/pion/interceptor"

	"github.com/pion/webrtc/v4"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/protect"
)

// The VK SFU only enables media for guests whose answer covers the offered
// codec set; Pion's default MediaEngine answers a subset. The offer's codecs
// are therefore echoed into the MediaEngine so the shaped answer negotiates
// the full offered vocabulary (spike: codecs-mode negotiation).

var (
	rtpmapLine  = regexp.MustCompile(`^a=rtpmap:(\d+) ([A-Za-z0-9-]+)/(\d+)(?:/(\d+))?`)
	fmtpLine    = regexp.MustCompile(`^a=fmtp:(\d+) (.*)$`)
	feedbackLin = regexp.MustCompile(`^a=rtcp-fb:(\d+) (\S+)(?: (\S+))?`)
	ssrcLine    = regexp.MustCompile(`(?m)^a=ssrc:(\d+)\s`)
)

// offerCodec parses one offered codec block: the rtpmap name and rate, then
// a bare-number channel count (audio) and fmtp:/fb: extras in any order.
func offerCodec(kind string, fields []string, payload uint8) (webrtc.RTPCodecParameters, bool) {
	spec := webrtc.RTPCodecParameters{
		RTPCodecCapability: webrtc.RTPCodecCapability{MimeType: kind + "/" + fields[0]},
		PayloadType:        webrtc.PayloadType(payload),
	}
	rate, err := strconv.ParseUint(fields[1], 10, 32)
	if err != nil {
		return spec, false
	}
	spec.ClockRate = uint32(rate)
	for _, extra := range fields[2:] {
		switch {
		case strings.HasPrefix(extra, "fmtp:"):
			spec.SDPFmtpLine = strings.TrimPrefix(extra, "fmtp:")
		case strings.HasPrefix(extra, "fb:"):
			parts := strings.Fields(strings.TrimPrefix(extra, "fb:"))
			fb := webrtc.RTCPFeedback{Type: parts[0]}
			if len(parts) > 1 {
				fb.Parameter = parts[1]
			}
			spec.RTCPFeedback = append(spec.RTCPFeedback, fb)
		default:
			channels, err := strconv.ParseUint(extra, 10, 16)
			if err != nil {
				return spec, false
			}
			spec.Channels = uint16(channels)
		}
	}
	return spec, true
}

// offerMediaEngine builds a MediaEngine mirroring the offer's codecs and the
// header extensions the SFU tags its forwarded packets with.
func offerMediaEngine(offer string) (*webrtc.MediaEngine, error) {
	sections, err := splitSections(offer)
	if err != nil {
		return nil, err
	}
	media := &webrtc.MediaEngine{}
	for _, sec := range sections {
		if err := registerSectionCodecs(media, sec); err != nil {
			return nil, err
		}
	}
	for _, uri := range []string{
		"urn:ietf:params:rtp-hdrext:ssrc-audio-level",
		"urn:ietf:params:rtp-hdrext:sdes:mid",
		"urn:ietf:params:rtp-hdrext:sdes:rtp-stream-id",
		"urn:ietf:params:rtp-hdrext:toffset",
		"http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time",
		"http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01",
		"http://www.webrtc.org/experiments/rtp-hdrext/color-space",
		"urn:3gpp:video-orientation",
		"http://www.webrtc.org/experiments/rtp-hdrext/playout-delay",
	} {
		_ = media.RegisterHeaderExtension(webrtc.RTPHeaderExtensionCapability{URI: uri}, webrtc.RTPCodecTypeVideo)
		_ = media.RegisterHeaderExtension(webrtc.RTPHeaderExtensionCapability{URI: uri}, webrtc.RTPCodecTypeAudio)
	}
	return media, nil
}

// registerSectionCodecs registers one m= section's offered codecs in payload
// order, keeping each payload's rtpmap, fmtp and rtcp-fb lines together.
func registerSectionCodecs(media *webrtc.MediaEngine, sec section) error {
	head := strings.Fields(sec[0])
	if len(head) < 4 {
		return nil
	}
	kind := strings.TrimPrefix(head[0], "m=")
	if kind != kindAudio && kind != kindVideo {
		return nil
	}
	codecType := webrtc.RTPCodecTypeVideo
	if kind == kindAudio {
		codecType = webrtc.RTPCodecTypeAudio
	}
	blocks := sectionCodecBlocks(sec)
	for _, payloadStr := range head[3:] {
		payload, err := strconv.ParseUint(payloadStr, 10, 8)
		if err != nil {
			continue
		}
		fields, ok := blocks[uint8(payload)]
		if !ok {
			continue
		}
		spec, ok := offerCodec(kind, fields, uint8(payload))
		if !ok {
			continue
		}
		if err := media.RegisterCodec(spec, codecType); err != nil {
			return fmt.Errorf("vkcalls: register %s pt=%d: %w", spec.MimeType, payload, err)
		}
	}
	return nil
}

// sectionCodecBlocks maps payload types to their codec fields: the rtpmap
// name/rate/channels followed by this payload's fmtp and rtcp-fb lines.
func sectionCodecBlocks(sec section) map[uint8][]string {
	blocks := map[uint8]*[]string{}
	pick := func(line string) (uint8, string, bool) {
		if m := fmtpLine.FindStringSubmatch(line); m != nil {
			pt, err := strconv.ParseUint(m[1], 10, 8)
			return uint8(pt), "fmtp:" + m[2], err == nil
		}
		if m := feedbackLin.FindStringSubmatch(line); m != nil {
			pt, err := strconv.ParseUint(m[1], 10, 8)
			if err != nil {
				return 0, "", false
			}
			extra := "fb:" + m[2]
			if m[3] != "" {
				extra += " " + m[3]
			}
			return uint8(pt), extra, true
		}
		return 0, "", false
	}
	for _, line := range sec {
		if m := rtpmapLine.FindStringSubmatch(line); m != nil {
			pt, parseErr := strconv.ParseUint(m[1], 10, 8)
			if parseErr != nil {
				continue
			}
			fields := []string{m[2], m[3]}
			if m[4] != "" {
				fields = append(fields, m[4])
			}
			blocks[uint8(pt)] = &fields
			continue
		}
		if pt, extra, ok := pick(line); ok {
			if fields, found := blocks[pt]; found {
				*fields = append(*fields, extra)
			}
		}
	}
	out := map[uint8][]string{}
	for pt, fields := range blocks {
		out[pt] = *fields
	}
	return out
}

// pionView is the offer as Pion is given it; the SFU never sees it, the
// shaped answer is built from the real offer.
//
// The SFU does not keep one payload numbering across the bundle: the
// publish slot names pt=102 red and pt=104 ulpfec while each receive slot
// names pt=102 VP9 and pt=104 red (#72). Pion keeps one codec per payload
// type for the whole connection, refuses a clash, labels an incoming stream
// by its payload type alone and sends a codec at the first payload type it
// saw. The view therefore puts the publish slot first among the video
// sections, shows Pion only the codecs we publish there (publish, "vp8")
// and their rtx, gives every payload type one meaning (claimPayloads) and
// trims the payload types that lost theirs. Every mid stays. memo, which
// may be nil, carries the meanings across one connection's renegotiations.
//
// ai-generated: this function (#72).
func pionView(offer string, publish []string, memo *viewMemo) (string, error) {
	text := strings.ReplaceAll(offer, "\r\n", "\n")
	sections, err := splitSections(text)
	if err != nil {
		return "", err
	}
	var header []string
	for _, line := range strings.Split(text, "\n") {
		if strings.HasPrefix(line, "m=") {
			break
		}
		if line != "" {
			header = append(header, line)
		}
	}
	sections = publishSlotFirst(sections)
	if len(publish) > 0 {
		for i, sec := range sections {
			if isPublishSlot(sec) {
				sections[i] = publishedOnly(sec, publish)
			}
		}
	}
	claims := claimPayloads(sections, publish, memo)
	out := header
	for _, sec := range sections {
		out = append(out, trimClashes(sec, claims)...)
	}
	return strings.Join(out, "\r\n") + "\r\n", nil
}

// viewMemo is what one connection's views have settled so far: Pion keeps
// its first meaning of a payload type for good.
//
// ai-generated: this type (#72).
type viewMemo struct {
	claims map[string]map[uint8]string
}

// isPublishSlot says whether sec is the video section the SFU receives on.
//
// ai-generated: this function (#72).
func isPublishSlot(sec section) bool {
	return sec.kind() == kindVideo && sec.direction() == dirRecvOnly
}

// isPublished says whether an rtpmap encoding is one of publish's codecs.
//
// ai-generated: this function (#72).
func isPublished(name string, publish []string) bool {
	for _, codec := range publish {
		if strings.HasPrefix(name, strings.ToLower(codec)+"/") {
			return true
		}
	}
	return false
}

// publishedOnly trims the publish slot to the codecs we publish and the rtx
// payloads repairing them; the rest would only claim payload types.
//
// ai-generated: this function (#72).
func publishedOnly(sec section, publish []string) section {
	names := rtpmapNames(sec)
	keep := map[uint8]bool{}
	for pt, name := range names {
		if isPublished(name, publish) {
			keep[pt] = true
		}
	}
	for pt, apt := range rtxApts(sec) {
		if keep[apt] {
			keep[pt] = true
		}
	}
	drop := map[uint8]bool{}
	for pt := range names {
		if !keep[pt] {
			drop[pt] = true
		}
	}
	return dropPayloads(sec, drop)
}

// rtxApts maps a section's rtx payload types to the payload they repair.
//
// ai-generated: this function (#72).
func rtxApts(sec section) map[uint8]uint8 {
	names := rtpmapNames(sec)
	out := map[uint8]uint8{}
	for _, line := range sec {
		m := fmtpLine.FindStringSubmatch(line)
		if m == nil {
			continue
		}
		pt, err := strconv.ParseUint(m[1], 10, 8)
		if err != nil || !strings.HasPrefix(names[uint8(pt)], "rtx/") {
			continue
		}
		for _, param := range strings.Split(m[2], ";") {
			if value, ok := strings.CutPrefix(strings.TrimSpace(param), "apt="); ok {
				if apt, aptErr := strconv.ParseUint(value, 10, 8); aptErr == nil {
					out[uint8(pt)] = uint8(apt)
				}
			}
		}
	}
	return out
}

// claimPayloads settles which codec each audio and video payload type names
// for Pion: what memo settled before, then the codecs we publish as the
// publish slot numbers them, then the other sections in offer order (the SFU
// forwards to us in their numbering), then the publish slot's other codecs
// where their payload type is still free. The result goes back to memo.
//
// ai-generated: this function (#72).
func claimPayloads(sections []section, publish []string, memo *viewMemo) map[string]map[uint8]string {
	claims := map[string]map[uint8]string{kindAudio: {}, kindVideo: {}}
	if memo != nil {
		for kind, known := range memo.claims {
			for pt, name := range known {
				claims[kind][pt] = name
			}
		}
		defer func() { memo.claims = claims }()
	}
	claim := func(sec section, wanted func(string) bool) {
		known, ok := claims[sec.kind()]
		if !ok {
			return
		}
		for pt, name := range rtpmapNames(sec) {
			if _, taken := known[pt]; !taken && wanted(name) {
				known[pt] = name
			}
		}
	}
	published := func(name string) bool { return isPublished(name, publish) }
	everything := func(string) bool { return true }
	for _, sec := range sections {
		if isPublishSlot(sec) {
			claim(sec, published)
		}
	}
	for _, sec := range sections {
		if !isPublishSlot(sec) {
			claim(sec, everything)
		}
	}
	for _, sec := range sections {
		if isPublishSlot(sec) {
			claim(sec, everything)
		}
	}
	return claims
}

// publishSlotFirst moves the video publish slot ahead of every other video
// section, keeping all else in order.
//
// ai-generated: this function (#72).
func publishSlotFirst(sections []section) []section {
	first, slot := -1, -1
	for i, sec := range sections {
		if sec.kind() != kindVideo {
			continue
		}
		if first < 0 {
			first = i
		}
		if sec.direction() == dirRecvOnly {
			slot = i
			break
		}
	}
	if slot <= first {
		return sections
	}
	out := make([]section, 0, len(sections))
	out = append(out, sections[:first]...)
	out = append(out, sections[slot])
	out = append(out, sections[first:slot]...)
	return append(out, sections[slot+1:]...)
}

// trimClashes drops from an audio or video section the payload types whose
// claim another codec won.
//
// ai-generated: this function (#72).
func trimClashes(sec section, claims map[string]map[uint8]string) section {
	known, ok := claims[sec.kind()]
	if !ok {
		return sec
	}
	drop := map[uint8]bool{}
	for pt, name := range rtpmapNames(sec) {
		if winner, claimed := known[pt]; claimed && winner != name {
			logger.Debugf("vkcalls: mid %s: pt=%d %s yields to %s, hidden from pion", sec.mid(), pt, name, winner)
			drop[pt] = true
		}
	}
	return dropPayloads(sec, drop)
}

// dropPayloads removes payload types from a section's m= line and their
// rtpmap, fmtp and rtcp-fb lines. A section that would be left with none is
// returned whole: there is nothing to negotiate in it either way.
//
// ai-generated: this function (#72).
func dropPayloads(sec section, drop map[uint8]bool) section {
	head := strings.Fields(sec[0])
	if len(drop) == 0 || len(head) < 4 {
		return sec
	}
	kept := head[:3:3]
	for _, payloadStr := range head[3:] {
		if pt, err := strconv.ParseUint(payloadStr, 10, 8); err != nil || !drop[uint8(pt)] {
			kept = append(kept, payloadStr)
		}
	}
	if len(kept) == 3 {
		return sec
	}
	out := section{strings.Join(kept, " ")}
	for _, line := range sec[1:] {
		if pt, isCodec := codecLinePayload(line); isCodec && drop[pt] {
			continue
		}
		out = append(out, line)
	}
	return out
}

// rtpmapNames maps a section's payload types to their lowercased rtpmap
// encoding (name/rate[/channels]), the identity Pion compares codecs by.
//
// ai-generated: this function (#72).
func rtpmapNames(sec section) map[uint8]string {
	names := map[uint8]string{}
	for _, line := range sec {
		if m := rtpmapLine.FindStringSubmatch(line); m != nil {
			if pt, err := strconv.ParseUint(m[1], 10, 8); err == nil {
				names[uint8(pt)] = strings.ToLower(strings.TrimPrefix(m[0], "a=rtpmap:"+m[1]+" "))
			}
		}
	}
	return names
}

// codecLinePayload returns the payload type an rtpmap, fmtp or rtcp-fb line
// belongs to.
//
// ai-generated: this function (#72).
func codecLinePayload(line string) (uint8, bool) {
	for _, prefix := range []string{"a=rtpmap:", "a=fmtp:", "a=rtcp-fb:"} {
		if rest, ok := strings.CutPrefix(line, prefix); ok {
			ptStr, _, _ := strings.Cut(rest, " ")
			pt, err := strconv.ParseUint(ptStr, 10, 8)
			return uint8(pt), err == nil
		}
	}
	return 0, false
}

// newWebRTCAPI builds the Pion API for the bundled peer connection: the
// offer's codecs, DTLS client role (the SDK answers active) and the host's
// protected networking.
func newWebRTCAPI(offer string, cfg engine.Config, resolver protect.Lookup) (*webrtc.API, error) {
	media, err := offerMediaEngine(offer)
	if err != nil {
		return nil, err
	}
	settings := webrtc.SettingEngine{}
	if roleErr := settings.SetAnsweringDTLSRole(webrtc.DTLSRoleClient); roleErr != nil {
		return nil, fmt.Errorf("vkcalls: dtls role: %w", roleErr)
	}
	apply, err := engine.NewPionSettings(engine.PionSettingsOptions{
		Resolver:         resolver,
		DTLSProfile:      cfg.DTLSProfile,
		LoggerFactory:    logger.NewPionLoggerFactory(),
		IPv4Only:         true,
		ProxyDialer:      true,
		DisableMulticast: true,
	})
	if err != nil {
		return nil, err //nolint:wrapcheck // shared builder already adds protected-net context
	}
	if apply != nil {
		apply(&settings)
	}
	// The engine answers as a browser-class endpoint: default interceptors
	// (RTCP receiver/sender reports, NACK, TWCC) keep the SFU's consumer-leg
	// liveness fed — without them the SFU stalls the forward after a minute —
	// and every outbound packet carries the mid and rid header extensions the
	// SFU maps our single simulcast layer by.
	registry := &interceptor.Registry{}
	if err := webrtc.RegisterDefaultInterceptorsWithOptions(
		media, registry, engine.DefaultInterceptorOptions()...,
	); err != nil {
		return nil, fmt.Errorf("vkcalls: interceptors: %w", err)
	}
	if err := webrtc.ConfigureTWCCHeaderExtensionSender(media, registry); err != nil {
		return nil, fmt.Errorf("vkcalls: twcc: %w", err)
	}
	registry.Add(sdesStamperFactory{mid: publishMid(offer), rid: "l"})
	opts := []func(*webrtc.API){
		webrtc.WithSettingEngine(settings),
		webrtc.WithMediaEngine(media),
		webrtc.WithInterceptorRegistry(registry),
	}
	return webrtc.NewAPI(opts...), nil
}

// remoteSSRCs lists the distinct SSRC attributions of an offer — the streams
// the accept-producer command reports knowing about.
func remoteSSRCs(offer string) []string {
	seen := map[string]bool{}
	var out []string
	for _, match := range ssrcLine.FindAllStringSubmatch(offer, -1) {
		if !seen[match[1]] {
			seen[match[1]] = true
			out = append(out, match[1])
		}
	}
	return out
}
