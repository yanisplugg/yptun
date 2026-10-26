package jitsi

// ai-generated: the whole file (the remote video latch follows a peer that
// comes back under a new source, and still keeps a third participant out).

import (
	"fmt"
	"testing"
)

// initiateWithPeerSource is a session-initiate announcing one participant's
// video source, the JSON form Jicofo sends an endpoint that reads it.
func initiateWithPeerSource(endpoint string, ssrc uint32) string {
	return fmt.Sprintf(`<iq type='set' xmlns='jabber:client'><jingle action='session-initiate' `+
		`xmlns='urn:xmpp:jingle:1'><json-message xmlns='http://jitsi.org/jitmeet'>`+
		`{&quot;sources&quot;:{&quot;%s&quot;:[[{&quot;s&quot;:%d}],[],[]]}}`+
		`</json-message></jingle></iq>`, endpoint, ssrc)
}

// sourceAddForPeer is the source-add Jicofo sends when a participant joins
// or republishes.
func sourceAddForPeer(endpoint string, ssrc uint32) string {
	return fmt.Sprintf(`<iq type='set' xmlns='jabber:client'><jingle action='source-add' `+
		`xmlns='urn:xmpp:jingle:1'><json-message xmlns='http://jitsi.org/jitmeet'>`+
		`{&quot;sources&quot;:{&quot;%s&quot;:[[{&quot;s&quot;:%d}],[],[]]}}`+
		`</json-message></jingle></iq>`, endpoint, ssrc)
}

// TestVideoLatchFollowsAPeerThatComesBack is issue #9's other half: the peer
// tore its session down and rejoined, so its video comes under a new source.
// The latch has to follow it, or this side drains the peer for the rest of
// the session and the tunnel never hears it again.
func TestVideoLatchFollowsAPeerThatComesBack(t *testing.T) {
	session := newSilentSession(t)
	session.noteSources(initiateWithPeerSource("peer0001", 5001), true)
	if !session.latchPeerVideo(5001) {
		t.Fatal("the first source was refused the latch")
	}

	// The peer rejoins: same endpoint, new source.
	session.noteSources(sourceAddForPeer("peer0001", 5002), false)
	if !session.latchPeerVideo(5002) {
		t.Fatal("the peer's new source was drained")
	}
	if got := session.peerVideoSSRC.Load(); got != 5002 {
		t.Fatalf("the latch holds %d, want the peer's new source", got)
	}
}

// TestVideoLatchKeepsAThirdParticipantOut is what the latch is for: another
// endpoint's video must not reach the carrier while the peer is live.
func TestVideoLatchKeepsAThirdParticipantOut(t *testing.T) {
	session := newSilentSession(t)
	session.noteSources(initiateWithPeerSource("peer0001", 6001), true)
	session.noteSources(sourceAddForPeer("guest002", 6002), false)
	if !session.latchPeerVideo(6001) {
		t.Fatal("the peer's source was refused the latch")
	}

	if session.latchPeerVideo(6002) {
		t.Fatal("a third participant took the latch from a live peer")
	}
	if got := session.peerVideoSSRC.Load(); got != 6001 {
		t.Fatalf("the latch holds %d, want the peer's source", got)
	}
}

// TestVideoLatchKeepsASourceNoStanzaNamed leaves the latch alone when
// nothing says the source holding it is gone.
func TestVideoLatchKeepsASourceNoStanzaNamed(t *testing.T) {
	session := newSilentSession(t)
	if !session.latchPeerVideo(7001) {
		t.Fatal("the first source was refused the latch")
	}
	if session.latchPeerVideo(7002) {
		t.Fatal("a second source took a latch nothing said was free")
	}
}

// TestVideoLatchSkipsTheBridge keeps the bridge's own probes out whether or
// not they arrive first.
func TestVideoLatchSkipsTheBridge(t *testing.T) {
	session := newSilentSession(t)
	session.noteSources(initiateWithBridgeSources(8001, 8002), true)
	if !session.isBridgeSSRC(8001) {
		t.Fatal("the bridge's own video source was not recognised")
	}
	session.noteSources(sourceAddForPeer("peer0001", 8003), false)
	if !session.isBridgeSSRC(8001) {
		t.Fatal("a source-add forgot the bridge's own sources")
	}
	if !session.latchPeerVideo(8003) {
		t.Fatal("the peer's source was refused the latch")
	}
}
