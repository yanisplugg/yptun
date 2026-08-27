package seichannel

import (
	"bytes"
	"testing"

	"github.com/pion/rtp"
	"github.com/pion/rtp/codecs"
	"github.com/pion/webrtc/v4/pkg/media/samplebuilder"
)

func TestSEIRoundTrip(t *testing.T) {
	payload := []byte("hello over seichannel")
	accessUnit := buildVideoAccessUnit(payload)

	got := extractVideoPayloads(accessUnit)
	if len(got) != 1 {
		t.Fatalf("expected 1 payload, got %d", len(got))
	}
	if !bytes.Equal(got[0], payload) {
		t.Fatalf("payload mismatch: got=%q want=%q", got[0], payload)
	}
}

func TestSEIRoundTripThroughRTPPacketizerAndSampleBuilder(t *testing.T) {
	payload := []byte("hello through rtp")
	accessUnit := buildVideoAccessUnit(payload)

	payloader := &codecs.H264Payloader{}
	packets := payloader.Payload(1200, accessUnit)
	if len(packets) == 0 {
		t.Fatal("H264 payloader returned no packets")
	}

	sb := samplebuilder.New(128, &codecs.H264Packet{}, 90000)
	for i, packetPayload := range packets {
		sb.Push(&rtp.Packet{
			Header: rtp.Header{
				SequenceNumber: uint16(i + 1),
				Timestamp:      1234,
				Marker:         i == len(packets)-1,
			},
			Payload: packetPayload,
		})
	}
	sb.Flush()

	sample := sb.Pop()
	if sample == nil {
		t.Fatal("samplebuilder returned nil sample")
	}

	got := extractVideoPayloads(sample.Data)
	if len(got) != 1 || !bytes.Equal(got[0], payload) {
		t.Fatalf("RTP SEI payloads = %q, want %q", got, payload)
	}
}
