package jitsi

// ai-generated: the whole file (the remote video latch skips the source JVB
// sends its bandwidth probes on).

import (
	"context"
	"fmt"
	"slices"
	"testing"
	"time"

	"github.com/pion/rtp"
	"github.com/pion/webrtc/v4"
)

// TestRemoteVideoLatchSkipsTheBridgesOwnSource plays JVB: before it forwards
// the peer's video it probes the endpoint's bandwidth with padding on a video
// source of its own, announced in session-initiate under the owner "jvb".
// When a probe beats the peer's first packet, the first video track the
// endpoint sees is the bridge's. Latching that one drains the peer's stream
// for the whole session, and the tunnel never hears the peer's handshake.
func TestRemoteVideoLatchSkipsTheBridgesOwnSource(t *testing.T) {
	s := newSilentSession(t)
	handed := make(chan uint32, 4)
	s.SetVideoTrackHandler(func(track *webrtc.TrackRemote, _ *webrtc.RTPReceiver) {
		handed <- uint32(track.SSRC())
		go drainTrack(track)
	})

	bridge := newPlainPeerConnection(t)
	probe, probeSSRC := addRTPTrack(t, bridge, "jvb-v0")
	peer, peerSSRC := addRTPTrack(t, bridge, "peer-v0")
	endpoint := newPlainPeerConnection(t)
	arrived := make(chan uint32, 4)
	endpoint.OnTrack(func(track *webrtc.TrackRemote, recv *webrtc.RTPReceiver) {
		s.handleRemoteTrack(track, recv)
		arrived <- uint32(track.SSRC())
	})
	connectPair(t, bridge, endpoint)
	s.noteSources(initiateWithBridgeSources(probeSSRC, probeSSRC+1), true)

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	padding := rtp.Packet{Header: rtp.Header{Version: 2, Padding: true, PaddingSize: 200}}
	if err := sendUntilArrived(ctx, probe, padding, arrived, probeSSRC); err != nil {
		t.Fatalf("the bridge's probe: %v", err)
	}
	frame := rtp.Packet{Header: rtp.Header{Version: 2, Marker: true}, Payload: []byte{0x10, 0x00, 0x9d, 0x01, 0x2a}}
	if err := sendUntilArrived(ctx, peer, frame, arrived, peerSSRC); err != nil {
		t.Fatalf("the peer's video: %v", err)
	}
	select {
	case got := <-handed:
		if got != peerSSRC {
			t.Fatalf("the carrier got the bridge's probe source %d, want the peer's %d", got, peerSSRC)
		}
	case <-ctx.Done():
		t.Fatal("the carrier got no video track")
	}
}

func TestSourceOwnersReadsBothSourceFormats(t *testing.T) {
	jsonForm := initiateWithBridgeSources(1001, 1002)
	xmlForm := `<iq type='set' xmlns='jabber:client'><jingle action='session-initiate' xmlns='urn:xmpp:jingle:1'>` +
		`<content name='video'><description media='video' xmlns='urn:xmpp:jingle:apps:rtp:1'>` +
		`<source ssrc='2001' name='jvb-v0' xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'>` +
		`<ssrc-info xmlns='http://jitsi.org/jitmeet' owner='jvb'/></source>` +
		`<source ssrc='2002' xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'>` +
		`<ssrc-info xmlns='http://jitsi.org/jitmeet' owner='room@conference.example.org/abcd1234'/></source>` +
		`</description></content></jingle></iq>`
	withPeer := `<iq type='set'><jingle action='session-initiate'><json-message xmlns='http://jitsi.org/jitmeet'>` +
		`{&quot;sources&quot;:{&quot;abcd1234&quot;:[[{&quot;s&quot;:3001},{&quot;s&quot;:3002}],` +
		`[[&quot;f&quot;,3001,3002]],[{&quot;s&quot;:3003}]],&quot;jvb&quot;:[[{&quot;s&quot;:3004}],[],[]]}}` +
		`</json-message></jingle></iq>`
	for _, tc := range []struct {
		name, stanza string
		bridge       []uint32
		owners       map[uint32]string
	}{
		{"json", jsonForm, []uint32{1001, 1002}, nil},
		{"xml", xmlForm, []uint32{2001}, map[uint32]string{2002: "abcd1234"}},
		{"json with a participant", withPeer, []uint32{3004},
			map[uint32]string{3001: "abcd1234", 3002: "abcd1234", 3003: "abcd1234"}},
		{"not a stanza", "<iq", nil, nil},
	} {
		owners := sourceOwners(tc.stanza)
		var bridge []uint32
		for ssrc, owner := range owners {
			if owner == bridgeOwner {
				bridge = append(bridge, ssrc)
			}
		}
		if got := slices.Sorted(slices.Values(bridge)); !slices.Equal(got, tc.bridge) {
			t.Errorf("%s: the bridge's sources = %v, want %v", tc.name, got, tc.bridge)
		}
		for ssrc, want := range tc.owners {
			if owners[ssrc] != want {
				t.Errorf("%s: source %d belongs to %q, want %q", tc.name, ssrc, owners[ssrc], want)
			}
		}
	}
}

// initiateWithBridgeSources is a session-initiate as Jicofo sends it to an
// endpoint that reads JSON sources: the bridge's own video and audio source.
func initiateWithBridgeSources(video, audio uint32) string {
	return fmt.Sprintf(`<iq to='endpoint@example.org/r' from='room@conference.example.org/focus' type='set' `+
		`xmlns='jabber:client'><jingle action='session-initiate' sid='s1' xmlns='urn:xmpp:jingle:1'>`+
		`<content name='video' creator='initiator'><description media='video' `+
		`xmlns='urn:xmpp:jingle:apps:rtp:1'/></content><json-message xmlns='http://jitsi.org/jitmeet'>`+
		`{&quot;sources&quot;:{&quot;jvb&quot;:[[{&quot;s&quot;:%d,&quot;n&quot;:&quot;jvb-v0&quot;,`+
		`&quot;m&quot;:&quot;mixedmslabel mixedlabelvideo0&quot;}],[],[{&quot;s&quot;:%d,`+
		`&quot;n&quot;:&quot;jvb-a0&quot;}]]}}</json-message></jingle></iq>`, video, audio)
}

// newPlainPeerConnection is a PeerConnection with the default codecs and no
// interceptors.
func newPlainPeerConnection(t *testing.T) *webrtc.PeerConnection {
	t.Helper()
	codecs := &webrtc.MediaEngine{}
	if err := codecs.RegisterDefaultCodecs(); err != nil {
		t.Fatalf("codecs: %v", err)
	}
	pc, err := webrtc.NewAPI(webrtc.WithMediaEngine(codecs)).NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		t.Fatalf("pc: %v", err)
	}
	t.Cleanup(func() { _ = pc.Close() })
	return pc
}

// addRTPTrack adds a VP8 track to pc and returns it with the SSRC its
// sender writes it under.
func addRTPTrack(t *testing.T, pc *webrtc.PeerConnection, id string) (*webrtc.TrackLocalStaticRTP, uint32) {
	t.Helper()
	track, err := webrtc.NewTrackLocalStaticRTP(webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP8}, id, id)
	if err != nil {
		t.Fatalf("track %s: %v", id, err)
	}
	sender, err := pc.AddTrack(track)
	if err != nil {
		t.Fatalf("add track %s: %v", id, err)
	}
	return track, uint32(sender.GetParameters().Encodings[0].SSRC)
}

// sendUntilArrived writes pkt on track every 10 ms until the endpoint reports
// the track for ssrc.
func sendUntilArrived(
	ctx context.Context,
	track *webrtc.TrackLocalStaticRTP,
	pkt rtp.Packet,
	arrived <-chan uint32,
	ssrc uint32,
) error {
	ticker := time.NewTicker(10 * time.Millisecond)
	defer ticker.Stop()
	for seq := uint16(1); ; seq++ {
		pkt.SequenceNumber, pkt.Timestamp = seq, uint32(seq)*3000
		if err := track.WriteRTP(&pkt); err != nil {
			return fmt.Errorf("write: %w", err)
		}
		select {
		case got := <-arrived:
			if got == ssrc {
				return nil
			}
		case <-ctx.Done():
			return fmt.Errorf("track %d never arrived: %w", ssrc, ctx.Err())
		case <-ticker.C:
		}
	}
}
