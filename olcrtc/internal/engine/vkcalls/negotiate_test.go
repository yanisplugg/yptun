// ai-generated: the whole file (tests for #72, payload types that name
// different codecs in different sections of one offer).
package vkcalls

import (
	"strings"
	"testing"

	"github.com/pion/webrtc/v4"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
)

// vp8Publish is what the vp8channel transport publishes.
func vp8Publish() []string { return []string{"vp8"} }

// publishSlotLine is the fixture's video publish slot m= line.
const publishSlotLine = "m=video 9 UDP/TLS/RTP/SAVPF 102 103 100 101\r\n"

// pionOffer is fixtureOffer completed for Pion's own parser and given an
// H264 in the publish slot, as the live offers carry. The sanitized fixture
// lists pt=101 (VP8's rtx) on two m= lines without its rtpmap, which the
// shaper never minds but SetRemoteDescription refuses.
func pionOffer() string {
	offer := strings.Replace(fixtureOffer, publishSlotLine,
		strings.TrimSuffix(publishSlotLine, "\r\n")+" 104\r\n", 1)
	offer = strings.Replace(offer,
		"a=rtcp-fb:100 nack pli\r\n",
		"a=rtcp-fb:100 nack pli\r\n"+
			"a=rtpmap:101 rtx/90000\r\na=fmtp:101 apt=100\r\n"+
			"a=rtpmap:104 H264/90000\r\n"+
			"a=fmtp:104 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f\r\n", 1)
	return strings.Replace(offer,
		"a=rtcp-fb:100 nack\r\na=ssrc:",
		"a=rtcp-fb:100 nack\r\na=rtpmap:101 rtx/90000\r\na=fmtp:101 apt=100\r\na=ssrc:", 1)
}

// peerSection is the forward section the SFU adds for a participant already
// in the room. Its payload numbering is the producer's own (a browser's
// here), so pt=102 is H264 while the publish slot of the same offer says VP9.
func peerSection(mid string) string {
	return "m=video 9 UDP/TLS/RTP/SAVPF 96 97 102 103\r\n" +
		"c=IN IP4 0.0.0.0\r\n" +
		"a=ice-ufrag:QcK1avCrgDjn21p\r\n" +
		"a=mid:" + mid + "\r\n" +
		"a=extmap:4 http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01\r\n" +
		"a=sendonly\r\n" +
		"a=rtcp-mux\r\n" +
		"a=rtpmap:96 VP8/90000\r\n" +
		"a=rtcp-fb:96 nack\r\n" +
		"a=rtpmap:97 rtx/90000\r\n" +
		"a=fmtp:97 apt=96\r\n" +
		"a=rtpmap:102 H264/90000\r\n" +
		"a=rtcp-fb:102 nack\r\n" +
		"a=fmtp:102 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f\r\n" +
		"a=rtpmap:103 rtx/90000\r\n" +
		"a=fmtp:103 apt=102\r\n" +
		"a=ssrc:2463708140 cname:guest\r\n" +
		"a=ssrc:2463708140 msid:guest video-guest\r\n"
}

// offerWithPeer returns the Pion-ready fixture offer with a forward section
// for one more participant, placed after the publish slot or ahead of it.
func offerWithPeer(t *testing.T, ahead bool) string {
	t.Helper()
	offer := strings.Replace(pionOffer(), "a=group:BUNDLE 0 1 2 3 5", "a=group:BUNDLE 0 1 2 3 5 6", 1)
	if !ahead {
		return offer + peerSection("6")
	}
	slot := strings.TrimSuffix(publishSlotLine, "\r\n") + " 104\r\n"
	if !strings.Contains(offer, slot) {
		t.Fatal("fixture lost its publish slot")
	}
	return strings.Replace(offer, slot, peerSection("6")+slot, 1)
}

// TestPayloadClashNegotiates is the regression test for #72: a room that
// already holds a participant offers that participant's forward section with
// its own payload numbering, and one payload type naming two codecs across
// sections aborted the connect with "register video/VP9 pt=102: codec
// already registered for same payload type". The engine's whole order must
// go through and publish VP8 at the payload type the shaped answer declares.
func TestPayloadClashNegotiates(t *testing.T) {
	for _, tc := range []struct {
		name  string
		ahead bool
	}{
		{"peer after publish slot", false},
		{"peer ahead of publish slot", true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			offer := offerWithPeer(t, tc.ahead)
			_, native, sender := negotiateFixture(t, offer, nil)
			assertPublishVP8(t, native, sender)

			shaped, err := ShapeAnswer(offer, native, NewShapeState())
			if err != nil {
				t.Fatalf("ShapeAnswer: %v", err)
			}
			// The SFU still gets the offer's full vocabulary, clashes included.
			if !strings.Contains(shaped, "a=mid:6") || !strings.Contains(shaped, "a=rtpmap:102 H264/90000") {
				t.Fatal("shaped answer no longer echoes the peer's forward section")
			}
		})
	}
}

// TestPayloadClashRenegotiates covers the other road into #72: the room was
// fresh at connect and the participant arrives in a renegotiation offer.
func TestPayloadClashRenegotiates(t *testing.T) {
	memo := &viewMemo{}
	pc, _, sender := negotiateFixture(t, pionOffer(), memo)
	reoffer := offerWithPeer(t, false)
	reoffer = strings.Replace(reoffer, "o=- 1790552099500 1790552100511", "o=- 1790552099500 1790552100512", 1)
	view, err := pionView(reoffer, vp8Publish(), memo)
	if err != nil {
		t.Fatal(err)
	}
	if srdErr := pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeOffer, SDP: view}); srdErr != nil {
		t.Fatalf("renegotiation SetRemoteDescription: %v", srdErr)
	}
	answer, err := pc.CreateAnswer(nil)
	if err != nil {
		t.Fatalf("renegotiation CreateAnswer: %v", err)
	}
	if err := pc.SetLocalDescription(answer); err != nil {
		t.Fatalf("renegotiation SetLocalDescription: %v", err)
	}
	assertPublishVP8(t, answer.SDP, sender)
	if _, err := ShapeAnswer(reoffer, answer.SDP, NewShapeState()); err != nil {
		t.Fatalf("ShapeAnswer: %v", err)
	}
}

// TestPionViewLeavesCleanOfferAlone: with no clash, the publish slot already
// first and no publish codecs to trim it to, Pion sees the offer as it came.
func TestPionViewLeavesCleanOfferAlone(t *testing.T) {
	offer := pionOffer()
	view, err := pionView(offer, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if view != offer {
		t.Fatalf("clean offer rewritten:\n%s", view)
	}
}

// TestPionViewKeepsEveryMid: moving and trimming never loses a section.
func TestPionViewKeepsEveryMid(t *testing.T) {
	offer := offerWithPeer(t, true)
	view, err := pionView(offer, vp8Publish(), nil)
	if err != nil {
		t.Fatal(err)
	}
	mids := func(sdp string) map[string]bool {
		sections, err := splitSections(strings.ReplaceAll(sdp, "\r\n", "\n"))
		if err != nil {
			t.Fatal(err)
		}
		out := map[string]bool{}
		for _, sec := range sections {
			out[sec.mid()] = true
		}
		return out
	}
	want, got := mids(offer), mids(view)
	if len(want) != len(got) {
		t.Fatalf("mids %v, want %v", got, want)
	}
	for mid := range want {
		if !got[mid] {
			t.Fatalf("mid %s lost", mid)
		}
	}
	// pt=102 is VP9 in the publish slot, H264 in the receive section: we do
	// not publish VP9, so the receive section's meaning wins.
	if !strings.Contains(view, "a=rtpmap:102 H264") || strings.Contains(view, "a=rtpmap:102 VP9") {
		t.Fatal("pt=102 did not settle on the receive section's H264")
	}
	assertOneMeaning(t, view)
}

// negotiateFixture runs the engine's order against offer: Pion's view of
// it (settling into memo, which may be nil), remote description, VP8 track
// on the publish slot, native answer.
func negotiateFixture(t *testing.T, offer string, memo *viewMemo) (*webrtc.PeerConnection, string, *webrtc.RTPSender) {
	t.Helper()
	view, err := pionView(offer, vp8Publish(), memo)
	if err != nil {
		t.Fatalf("pionView: %v", err)
	}
	api, err := newWebRTCAPI(view, engine.Config{}, nil)
	if err != nil {
		t.Fatalf("newWebRTCAPI: %v", err)
	}
	pc, err := api.NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = pc.Close() })
	if srdErr := pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeOffer, SDP: view}); srdErr != nil {
		t.Fatalf("SetRemoteDescription: %v", srdErr)
	}
	track, err := webrtc.NewTrackLocalStaticSample(
		webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP8, ClockRate: 90000}, "video", "proofkit")
	if err != nil {
		t.Fatal(err)
	}
	sender, err := pc.AddTrack(track)
	if err != nil {
		t.Fatalf("AddTrack: %v", err)
	}
	answer, err := pc.CreateAnswer(nil)
	if err != nil {
		t.Fatalf("CreateAnswer: %v", err)
	}
	if err := pc.SetLocalDescription(answer); err != nil {
		t.Fatalf("SetLocalDescription: %v", err)
	}
	return pc, answer.SDP, sender
}

// assertPublishVP8 checks that the publish slot answers and sends VP8 at
// pt=100, the slot's own payload type the shaped answer declares.
func assertPublishVP8(t *testing.T, native string, sender *webrtc.RTPSender) {
	t.Helper()
	publish := ""
	for _, sec := range strings.Split(native, "m=video")[1:] {
		if strings.Contains(sec, "a=sendonly") {
			publish = strings.SplitN(sec, "\r\n", 2)[0]
		}
	}
	if !strings.Contains(publish+" ", " 100 ") {
		t.Fatalf("publish section does not negotiate VP8 pt=100: %q", publish)
	}
	for _, c := range sender.GetParameters().Codecs {
		if strings.EqualFold(c.MimeType, webrtc.MimeTypeVP8) {
			if c.PayloadType != 100 {
				t.Fatalf("publish sender sends VP8 as pt=%d, want 100", c.PayloadType)
			}
			return
		}
	}
	t.Fatal("publish sender has no VP8")
}

// liveShapeOffer has the payload numbering of a live offer (#72) with
// sanitized values: the publish slot names pt=102 red and pt=104 ulpfec, the
// receive slots name pt=102 VP9, pt=104 red and pt=106 ulpfec. VP8 is pt=100
// in both.
func liveShapeOffer() string {
	session := "v=0\r\n" +
		"o=- 1790552099500 1790552100511 IN IP4 127.0.0.1\r\n" +
		"s=-\r\nt=0 0\r\n" +
		"a=group:BUNDLE 0 1 3 5 6\r\n" +
		"a=ice-lite\r\n" +
		"a=msid-semantic: WMS *\r\n"
	audio := "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n" +
		"c=IN IP4 0.0.0.0\r\n" +
		"a=candidate:1 1 udp 1 192.0.2.1 43210 typ host\r\n" +
		"a=ice-ufrag:QcK1avCrgDjn21p\r\n" +
		"a=ice-pwd:GZU7Kw3Sq1YSJxrABCtRTGhms\r\n" +
		"a=fingerprint:sha-256 32:16:0D:10:FC:A1:F1:04:43:F5:22:01:9B:2E:53:38\r\n" +
		"a=setup:actpass\r\n" +
		"a=mid:0\r\n" +
		"a=sendonly\r\n" +
		"a=rtcp-mux\r\n" +
		"a=rtpmap:111 opus/48000/2\r\n" +
		"a=ssrc:2463708138 cname:audio-mix\r\n"
	app := "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n" +
		"c=IN IP4 0.0.0.0\r\n" +
		"a=mid:1\r\n" +
		"a=sendrecv\r\n"
	ext := "a=extmap:4 http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01\r\n" +
		"a=extmap:3 http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time\r\n"
	vp8 := "a=rtpmap:100 VP8/90000\r\na=rtcp-fb:100 nack\r\na=rtcp-fb:100 nack pli\r\n" +
		"a=rtpmap:101 rtx/90000\r\na=fmtp:101 apt=100\r\n"
	h264 := "a=rtpmap:98 H264/90000\r\n" +
		"a=fmtp:98 packetization-mode=1;level-asymmetry-allowed=1;profile-level-id=42e034\r\n" +
		"a=rtpmap:99 rtx/90000\r\na=fmtp:99 apt=98\r\n"
	publish := "m=video 9 UDP/TLS/RTP/SAVPF 100 101 98 99 102 103 104\r\n" +
		"c=IN IP4 0.0.0.0\r\n" +
		"a=mid:3\r\n" + ext +
		"a=extmap:14 urn:ietf:params:rtp-hdrext:sdes:mid\r\n" +
		"a=extmap:10 urn:ietf:params:rtp-hdrext:sdes:rtp-stream-id\r\n" +
		"a=recvonly\r\n" +
		"a=rtcp-mux\r\n" + vp8 + h264 +
		"a=rtpmap:102 red/90000\r\n" +
		"a=rtpmap:103 rtx/90000\r\na=fmtp:103 apt=102\r\n" +
		"a=rtpmap:104 ulpfec/90000\r\n" +
		"a=rid:l recv\r\n" +
		"a=simulcast:recv l\r\n"
	receive := func(mid, ssrc string) string {
		return "m=video 9 UDP/TLS/RTP/SAVPF 102 103 100 101 98 99 104 105 106\r\n" +
			"c=IN IP4 0.0.0.0\r\n" +
			"a=mid:" + mid + "\r\n" + ext +
			"a=sendonly\r\n" +
			"a=rtcp-mux\r\n" +
			"a=rtpmap:102 VP9/90000\r\n" +
			"a=rtpmap:103 rtx/90000\r\na=fmtp:103 apt=102\r\n" + vp8 + h264 +
			"a=rtpmap:104 red/90000\r\n" +
			"a=rtpmap:105 rtx/90000\r\na=fmtp:105 apt=104\r\n" +
			"a=rtpmap:106 ulpfec/90000\r\n" +
			"a=ssrc:" + ssrc + " cname:pat-" + mid + "\r\n" +
			"a=ssrc:" + ssrc + " msid:pat-" + mid + " video-pat-" + mid + "\r\n"
	}
	return session + audio + app + publish + receive("5", "2463708139") + receive("6", "2463708140")
}

// TestLiveShapeKeepsReceiveNumbering is the second half of #72: with every
// publish-slot payload type kept, Pion took pt=104 for ulpfec and labelled
// the camera video the SFU forwards as red at 104 "video/ulpfec", which the
// transport rejects - both ends connected and nothing flowed. The receive
// slots' numbering must win for everything we do not publish, while VP8
// still goes out at the publish slot's pt=100.
func TestLiveShapeKeepsReceiveNumbering(t *testing.T) {
	offer := liveShapeOffer()
	view, err := pionView(offer, vp8Publish(), nil)
	if err != nil {
		t.Fatal(err)
	}
	assertOneMeaning(t, view)
	sections, err := splitSections(strings.ReplaceAll(view, "\r\n", "\n"))
	if err != nil {
		t.Fatal(err)
	}
	for _, sec := range sections {
		names := rtpmapNames(sec)
		switch sec.mid() {
		case "3":
			if names[100] != "vp8/90000" || names[104] != "" || names[102] != "" {
				t.Fatalf("publish slot in the view: %v", names)
			}
		case "5", "6":
			if names[104] != "red/90000" || names[102] != "vp9/90000" || names[100] != "vp8/90000" {
				t.Fatalf("receive slot %s in the view: %v", sec.mid(), names)
			}
		}
	}
	_, native, sender := negotiateFixture(t, offer, nil)
	assertPublishVP8(t, native, sender)
	if _, err := ShapeAnswer(offer, native, NewShapeState()); err != nil {
		t.Fatalf("ShapeAnswer: %v", err)
	}
}

// assertOneMeaning checks that no payload type names two codecs of one kind
// anywhere in a view: Pion would refuse or mislabel it.
func assertOneMeaning(t *testing.T, view string) {
	t.Helper()
	sections, err := splitSections(strings.ReplaceAll(view, "\r\n", "\n"))
	if err != nil {
		t.Fatal(err)
	}
	seen := map[string]map[uint8]string{}
	for _, sec := range sections {
		if seen[sec.kind()] == nil {
			seen[sec.kind()] = map[uint8]string{}
		}
		for pt, name := range rtpmapNames(sec) {
			if prior, ok := seen[sec.kind()][pt]; ok && prior != name {
				t.Fatalf("pt=%d is %s in one section and %s in mid %s", pt, prior, name, sec.mid())
			}
			seen[sec.kind()][pt] = name
		}
	}
}

// TestPublishedOnlyKeepsRepair: the publish slot shows Pion the published
// codec and its own rtx, nothing else.
func TestPublishedOnlyKeepsRepair(t *testing.T) {
	sections, err := splitSections(strings.ReplaceAll(liveShapeOffer(), "\r\n", "\n"))
	if err != nil {
		t.Fatal(err)
	}
	for _, sec := range sections {
		if !isPublishSlot(sec) {
			continue
		}
		got := publishedOnly(sec, vp8Publish())
		if got[0] != "m=video 9 UDP/TLS/RTP/SAVPF 100 101" {
			t.Fatalf("publish slot m= line %q", got[0])
		}
		names := rtpmapNames(got)
		if len(names) != 2 || names[100] != "vp8/90000" || names[101] != "rtx/90000" {
			t.Fatalf("publish slot codecs %v", names)
		}
		return
	}
	t.Fatal("no publish slot")
}
