package engine

import (
	"testing"

	"github.com/pion/webrtc/v4"
)

func TestCloseSignal(t *testing.T) {
	CloseSignal(nil)
	ch := make(chan struct{})
	CloseSignal(ch)
	CloseSignal(ch)
	select {
	case <-ch:
	default:
		t.Fatal("signal was not closed")
	}
}

func TestApplyRefreshedCredentials(t *testing.T) {
	url, token, room := "old-url", "old-token", "old-room"
	ApplyRefreshedCredentials(Credentials{
		URL:   "new-url",
		Token: "new-token",
		Extra: map[string]string{"room": "new-room"},
	}, &url, &token, map[string]*string{"room": &room})
	if url != "new-url" || token != "new-token" || room != "new-room" {
		t.Fatalf("credentials = %q/%q/%q", url, token, room)
	}
}

func TestPionSettingsBuildsAPI(t *testing.T) {
	apply, err := NewPionSettings(PionSettingsOptions{IPv4Only: true})
	if err != nil {
		t.Fatalf("NewPionSettings: %v", err)
	}
	settings := webrtc.SettingEngine{}
	apply(&settings)
	api := webrtc.NewAPI(webrtc.WithSettingEngine(settings))
	pc, err := api.NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		t.Fatalf("NewPeerConnection: %v", err)
	}
	if err := pc.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
}

func TestPionSettingsNoop(t *testing.T) {
	apply, err := NewPionSettings(PionSettingsOptions{})
	if err != nil {
		t.Fatalf("NewPionSettings: %v", err)
	}
	if apply != nil {
		t.Fatal("empty options returned a non-nil settings hook")
	}
}

func TestVideoTrackState(t *testing.T) {
	var state VideoTrackState
	state.StoreVideoTrack(nil)
	state.SetVideoTrackHandler(func(*webrtc.TrackRemote, *webrtc.RTPReceiver) {})
	if !state.HasVideoTracks() || !state.WantsVideo() || state.VideoTrackHandler() == nil {
		t.Fatal("video state did not retain track and handler")
	}
	count := 0
	state.RangeVideoTracks(func(webrtc.TrackLocal, bool) {
		count++
	})
	if count != 1 {
		t.Fatalf("track count = %d, want 1", count)
	}
}
