package jitsi

// ai-generated: the whole file (issue #14: the conference interceptors send
// again what the bridge says it did not get).

import (
	"testing"
	"time"

	pioninterceptor "github.com/pion/interceptor"
	"github.com/pion/rtcp"
	"github.com/pion/rtp"
	"github.com/pion/webrtc/v4"
)

// TestConferenceInterceptorsAnswerNacks asks for a packet again, the way JVB
// asks when it loses one of ours. Unanswered, JVB forwards the gap and the
// frame that packet belonged to never reassembles at the peer: on a bridge
// that lost 5-10% of what we sent it, the handshake went with it (issue #14).
func TestConferenceInterceptorsAnswerNacks(t *testing.T) {
	media := &webrtc.MediaEngine{}
	if err := media.RegisterDefaultCodecs(); err != nil {
		t.Fatalf("codecs: %v", err)
	}
	registry, err := newConferenceInterceptors(media)
	if err != nil {
		t.Fatalf("interceptors: %v", err)
	}
	chain, err := registry.Build("test")
	if err != nil {
		t.Fatalf("build: %v", err)
	}
	t.Cleanup(func() { _ = chain.Close() })

	sent := make(chan uint16, 8)
	info := &pioninterceptor.StreamInfo{SSRC: 7, RTCPFeedback: []pioninterceptor.RTCPFeedback{{Type: "nack"}}}
	writer := chain.BindLocalStream(info, pioninterceptor.RTPWriterFunc(
		func(header *rtp.Header, payload []byte, _ pioninterceptor.Attributes) (int, error) {
			sent <- header.SequenceNumber
			return len(payload), nil
		}))
	if _, err = writer.Write(&rtp.Header{SSRC: 7, SequenceNumber: 11}, []byte("a frame"), nil); err != nil {
		t.Fatalf("write: %v", err)
	}
	<-sent

	request, err := rtcp.Marshal([]rtcp.Packet{&rtcp.TransportLayerNack{
		SenderSSRC: 1, MediaSSRC: 7, Nacks: []rtcp.NackPair{{PacketID: 11}},
	}})
	if err != nil {
		t.Fatalf("marshal nack: %v", err)
	}
	reader := chain.BindRTCPReader(pioninterceptor.RTCPReaderFunc(
		func(buf []byte, attrs pioninterceptor.Attributes) (int, pioninterceptor.Attributes, error) {
			return copy(buf, request), attrs, nil
		}))
	if _, _, err = reader.Read(make([]byte, 1500), pioninterceptor.Attributes{}); err != nil {
		t.Fatalf("read rtcp: %v", err)
	}
	select {
	case seq := <-sent:
		if seq != 11 {
			t.Fatalf("sent packet %d again, want 11", seq)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("the nack went unanswered: a packet the bridge lost is never sent again")
	}
}
