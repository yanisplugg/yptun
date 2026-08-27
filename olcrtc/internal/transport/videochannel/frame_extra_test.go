package videochannel

import (
	"testing"

	"github.com/pion/webrtc/v4"
)

func TestCodecSpecForMime(t *testing.T) {
	for _, mime := range []string{webrtc.MimeTypeH264, webrtc.MimeTypeVP8, webrtc.MimeTypeVP9} {
		spec, ok := codecSpecForMime(mime)
		if !ok {
			t.Fatalf("codecSpecForMime(%q) ok = false", mime)
		}
		if spec.capability.MimeType != mime || spec.depacketizer == nil || spec.capability.ClockRate != 90000 {
			t.Fatalf("codec spec = %+v", spec)
		}
	}
	if _, ok := codecSpecForMime("video/unknown"); ok {
		t.Fatal("codecSpecForMime() accepted unknown mime")
	}
	if got := vp8CodecSpec(); got.capability.MimeType != webrtc.MimeTypeVP8 {
		t.Fatalf("vp8CodecSpec() = %+v, want vp8", got)
	}
}
