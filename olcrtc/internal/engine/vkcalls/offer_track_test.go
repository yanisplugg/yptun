package vkcalls

import (
	"os"
	"strings"
	"testing"

	"github.com/pion/webrtc/v4"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
)

// TestOfferTrackNegotiates is the regression test for two negotiation-order
// bugs found against the live SFU: the remote description must be set before
// tracks attach (AddTrack then reuses the offer's publish transceiver), and
// every offered codec must register — an fb: extra parsed as a channel count
// once dropped VP8, leaving the answer with only red/ulpfec and the track
// with "codec is not supported by remote".
//
// The full-capture offer lives outside git (private spike artifacts); the
// test skips without it and the fixture in answer_test.go covers the shape.
func TestOfferTrackNegotiates(t *testing.T) {
	const capture = "/tmp/proofkit-vk-live/current-offer.sdp"
	raw, err := os.ReadFile(capture)
	if err != nil {
		t.Skip("no captured offer")
	}
	offer := strings.ReplaceAll(string(raw), "\r\n", "\n")
	// ai-generated: this block; the engine hands Pion its view of the offer (#72).
	view, err := pionView(offer, vp8Publish(), nil)
	if err != nil {
		t.Fatal(err)
	}
	api, err := newWebRTCAPI(view, engine.Config{}, nil)
	if err != nil {
		t.Fatal(err)
	}
	pc, err := api.NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()
	if srdErr := pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeOffer, SDP: view}); srdErr != nil {
		t.Fatal(srdErr)
	}
	track, err := webrtc.NewTrackLocalStaticSample(
		webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP8, ClockRate: 90000}, "video", "proofkit")
	if err != nil {
		t.Fatal(err)
	}
	if _, errAdd := pc.AddTrack(track); errAdd != nil {
		t.Fatal(errAdd)
	}
	answer, err := pc.CreateAnswer(nil)
	if err != nil {
		t.Fatal(err)
	}
	sections := strings.Split(answer.SDP, "m=video")
	for _, sec := range sections[1:] { // [0] is the session header and audio sections
		if strings.Contains(sec, "a=sendonly") {
			if !strings.Contains(strings.SplitN(sec, "\r\n", 2)[0], " 100 ") {
				t.Fatalf("publish section does not negotiate VP8: %s", sec[:min(120, len(sec))])
			}
			return
		}
	}
	t.Fatal("no sendonly video section in the answer")
}
