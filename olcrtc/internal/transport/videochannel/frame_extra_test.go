package videochannel

import (
	"errors"
	"testing"

	"github.com/pion/webrtc/v4"
)

func TestDecodeTransportFrameErrorsAndAck(t *testing.T) {
	tests := []struct {
		data []byte
		want error
	}{
		{data: []byte{1, 2, 3}, want: ErrFrameTooShort},
		{data: []byte{0, 0, 0, 0, protocolVersion, frameTypeAck}, want: ErrUnexpectedMagic},
		{data: []byte{0x4f, 0x56, 0x56, 0x32, 9, frameTypeAck}, want: ErrUnexpectedVersion},
		{data: []byte{0x4f, 0x56, 0x56, 0x32, protocolVersion, frameTypeAck}, want: ErrAckTooShort},
		{data: []byte{0x4f, 0x56, 0x56, 0x32, protocolVersion, frameTypeData}, want: ErrDataTooShort},
		{data: []byte{0x4f, 0x56, 0x56, 0x32, protocolVersion, 99}, want: ErrUnexpectedFrameType},
	}
	for _, tt := range tests {
		if _, err := decodeTransportFrame(tt.data); !errors.Is(err, tt.want) {
			t.Fatalf("decodeTransportFrame(%v) error = %v, want %v", tt.data, err, tt.want)
		}
	}

	ack, err := decodeTransportFrame(encodeAckFrame(7, 0x1234, 5))
	if err != nil {
		t.Fatalf("decode ack error = %v", err)
	}
	if ack.typ != frameTypeAck || ack.seq != 7 || ack.crc != 0x1234 || ack.fragIdx != 5 {
		t.Fatalf("ack = %+v", ack)
	}
}

func TestCodecSpecForMime(t *testing.T) {
	for _, mime := range []string{webrtc.MimeTypeH264, webrtc.MimeTypeVP8, webrtc.MimeTypeVP9} {
		spec, ok := codecSpecForMime(mime)
		if !ok {
			t.Fatalf("codecSpecForMime(%q) ok = false", mime)
		}
		if spec.mimeType != mime || spec.depacketizer == nil || spec.capability.ClockRate != 90000 {
			t.Fatalf("codec spec = %+v", spec)
		}
	}
	if _, ok := codecSpecForMime("video/unknown"); ok {
		t.Fatal("codecSpecForMime() accepted unknown mime")
	}
	if got := codecSpecForCarrier("any-carrier"); got.mimeType != webrtc.MimeTypeVP8 {
		t.Fatalf("codecSpecForCarrier() = %+v, want vp8", got)
	}
}
