package salutejazz

import (
	"context"
	"encoding/json"
	"errors"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/livekit/protocol/livekit"
	"github.com/pion/webrtc/v4"
	"google.golang.org/protobuf/proto"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
)

// TestJoinConfigOfferAnswerPing walks the whole join handshake against the
// connector fake: the frames that drive it arrive in the order the capture
// recorded, the join this engine actually built has the captured shape, the
// SFU's subscriber offer is answered, and a ping is answered with a pong.
func TestJoinConfigOfferAnswerPing(t *testing.T) {
	url, fake := newFakeConnector(t)
	sess, err := New(context.Background(), engine.Config{
		URL: url, Token: "passw0rd", Name: "test",
		Extra: map[string]string{"roomID": "abc123"},
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = sess.Close() })
	// Sized past the handshake: a hook that blocks would stall the reader.
	got := make(chan string, 8)
	sess.(*Session).onJoinPayload = func(ev string) { got <- ev }
	if err := sess.Connect(context.Background()); err != nil {
		t.Fatalf("connect: %v (fake: %s)", err, fake.lastError())
	}
	for _, want := range []string{"join-response", "rtc:config", "rtc:offer"} {
		select {
		case ev := <-got:
			if ev != want {
				t.Fatalf("got %q want %q", ev, want)
			}
		case <-time.After(5 * time.Second):
			t.Fatalf("timeout waiting for %q", want)
		}
	}
	// ping answered inside 2 s
	if err := sess.(*Session).pingOnce(); err != nil {
		t.Fatal(err)
	}
	if !fake.sawAnswer() {
		t.Fatal("fake SFU never got the SDP answer")
	}

	// What the connector received, not what a literal in this file says.
	joins := fake.joins()
	if len(joins) != 1 {
		t.Fatalf("joins = %d, want 1", len(joins))
	}
	if joins[0].group != "" {
		t.Fatalf("the join carried a group id %q; the capture has none", joins[0].group)
	}
	if payload := redactPassword(joins[0].payload); payload != wantJoinPayload("test") {
		t.Fatalf("join payload\n got %s\nwant %s", payload, wantJoinPayload("test"))
	}
	if failure := fake.lastError(); failure != "" {
		t.Fatalf("fake SFU error: %s", failure)
	}
}

// TestPublisherPeerConnectionNegotiates pins the finding that shaped this
// engine: the channels the SFU creates on the subscriber PC carry nothing
// out, so the client offers a publisher PC of its own and sends on that.
// Both peer connections must reach connected and both publisher channels
// must open.
func TestPublisherPeerConnectionNegotiates(t *testing.T) {
	url, fake := newFakeConnector(t)
	sess := connectSession(t, url, "publisher")

	if !fake.sawPublisherOffer() {
		t.Fatal("fake SFU never got the client's publisher offer")
	}
	if !fake.sawAnswer() {
		t.Fatal("fake SFU never got the SDP answer")
	}
	waitFor(t, 5*time.Second, "both peer connections connected", func() bool {
		return sess.subscriberConnected() && sess.publisherConnected()
	})
	waitFor(t, 5*time.Second, "both publisher channels open", func() bool {
		return channelOpen(sess, labelReliable) && channelOpen(sess, labelLossy)
	})
	if !sess.SubscriberCanSend() || !sess.CanSend() {
		t.Fatalf("session not ready: subscriber=%v publisher=%v", sess.SubscriberCanSend(), sess.CanSend())
	}
	if failure := fake.lastError(); failure != "" {
		t.Fatalf("fake SFU error: %s", failure)
	}
}

// TestRTCConfigReachesPionNormalised covers the TURN path: what rtc:config
// advertises is what the peer connections are built with, after the shared
// normaliser has been over it.
func TestRTCConfigReachesPionNormalised(t *testing.T) {
	url, _ := newFakeConnector(t)
	sess := connectSession(t, url, "ice")

	servers := sess.iceServers()
	if len(servers) != 1 || len(servers[0].URLs) != 1 || servers[0].URLs[0] != fakeSTUNURL {
		t.Fatalf("ice servers = %+v, want one %s entry", servers, fakeSTUNURL)
	}
}

// TestParticipantsUpdateFillsTheIdentityMap covers rtc:participants:update:
// the roster names everyone else in the room under the identity a data
// packet is addressed to, and never lists this session as its own peer.
func TestParticipantsUpdateFillsTheIdentityMap(t *testing.T) {
	url, fake := newFakeConnector(t)
	first := connectSession(t, url, "first")
	second := connectSession(t, url, "second")

	if first.localIdentity() == "" || first.localIdentity() == second.localIdentity() {
		t.Fatalf("identities %q and %q", first.localIdentity(), second.localIdentity())
	}
	waitFor(t, 5*time.Second, "the first session to see the second", func() bool {
		peers := first.remoteIdentities()
		return len(peers) == 1 && peers[0] == second.localIdentity()
	})
	waitFor(t, 5*time.Second, "the second session to see the first", func() bool {
		peers := second.remoteIdentities()
		return len(peers) == 1 && peers[0] == first.localIdentity()
	})
	if failure := fake.lastError(); failure != "" {
		t.Fatalf("fake SFU error: %s", failure)
	}
}

// TestRejoinCarriesNoStaleGroup pins what the capture says about a rejoin:
// the join frame names no group, because the group belongs to the join that
// is being replaced. Identity and group live on the connection attempt, so a
// second attempt starts without either.
func TestRejoinCarriesNoStaleGroup(t *testing.T) {
	url, fake := newFakeConnector(t)
	sess := connectSession(t, url, "rejoin")

	firstGroup := sess.current().groupID()
	firstIdentity := sess.localIdentity()
	if firstGroup == "" || firstIdentity == "" {
		t.Fatalf("after the join: group %q identity %q", firstGroup, firstIdentity)
	}
	if err := sess.reconnect(context.Background()); err != nil {
		t.Fatalf("reconnect: %v (fake: %s)", err, fake.lastError())
	}

	joins := fake.joins()
	if len(joins) != 2 {
		t.Fatalf("joins = %d, want 2", len(joins))
	}
	for i, join := range joins {
		if join.group != "" {
			t.Fatalf("join %d carried a group id %q", i, join.group)
		}
	}
	if group := sess.current().groupID(); group == "" {
		t.Fatal("the rejoin never learned its own group")
	}
	if identity := sess.localIdentity(); identity == firstIdentity {
		t.Fatalf("the rejoin kept the old identity %q", identity)
	}
	// The fake fails a join that carries a group and a later frame that
	// carries the wrong one.
	if failure := fake.lastError(); failure != "" {
		t.Fatalf("fake SFU error: %s", failure)
	}
}

// TestTeardownRunsOnce pins the lifecycle invariant the review found broken:
// Close and a Connect that is giving up both own the same generation, and a
// teardown that only closes its signal channel when it looks open closes it
// twice the moment the two overlap.
func TestTeardownRunsOnce(t *testing.T) {
	url, _ := newFakeConnector(t)
	sess := connectSession(t, url, "teardown")
	gen := sess.current()

	var wg sync.WaitGroup
	start := make(chan struct{})
	for range 16 {
		wg.Go(func() {
			<-start
			sess.teardown(gen)
		})
	}
	close(start)
	wg.Wait()
}

// TestCloseDuringConnectTearsDownOnce is the same race through the public
// surface: Close lands while Connect is still waiting for a join-response
// the connector is holding, so both end the same generation.
func TestCloseDuringConnectTearsDownOnce(t *testing.T) {
	url, fake := newFakeConnector(t)
	release := fake.holdJoins()
	t.Cleanup(release)

	sess, err := New(context.Background(), engine.Config{
		URL: url, Token: "passw0rd", Name: "race",
		Extra: map[string]string{"roomID": "abc123"},
	})
	if err != nil {
		t.Fatal(err)
	}
	sj := sess.(*Session)
	sj.joinTimeout = 300 * time.Millisecond

	connected := make(chan error, 1)
	go func() { connected <- sess.Connect(context.Background()) }()
	waitFor(t, 5*time.Second, "the join to reach the connector", func() bool {
		return len(fake.joins()) == 1
	})

	closed := make(chan error, 1)
	go func() { closed <- sess.Close() }()

	select {
	case err := <-connected:
		if err == nil {
			t.Fatal("connect succeeded while the join was held")
		}
	case <-time.After(10 * time.Second):
		t.Fatal("connect never returned")
	}
	select {
	case err := <-closed:
		if err != nil {
			t.Fatalf("close: %v", err)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("close never returned")
	}
	// Closing again, and connecting again, must both be safe.
	if err := sess.Close(); err != nil {
		t.Fatalf("second close: %v", err)
	}
	if err := sess.Connect(context.Background()); err == nil {
		t.Fatal("connect succeeded on a closed session")
	}
}

// TestClientFramesMatchTheCapturedShape pins the bytes this engine puts on
// the connector. What the service sees has to be what its own web client
// sends, field for field and in its order, and the capture of that client is
// where every field name and every field's place below comes from.
//
// The values are not. A room code, its password, a group id, a host address
// and an ICE fragment are what one real call was, and this repository is
// public: the values here are made up, with RFC 5737 for the address.
func TestClientFramesMatchTheCapturedShape(t *testing.T) {
	sess, err := New(context.Background(), engine.Config{
		URL: "wss://example.invalid/connector", Token: "passw0rd", Name: "Spike Tester",
		Extra: map[string]string{"roomID": "abc123"},
	})
	if err != nil {
		t.Fatal(err)
	}
	sj := sess.(*Session)
	gen := newGeneration(nil)

	// The join carries no group id: the connector names the group in its
	// answer to this very frame.
	got := frameJSON(t, sj, gen, eventJoin, joinRequest{
		Password:        "passw0rd",
		ParticipantName: "Spike Tester",
		SupportedFeatures: supportedFeatures{
			AttachedRooms: true, SessionGroups: true, Transcription: true, Interpretation: true,
		},
	})
	want := `{"roomId":"abc123","payload":` + wantJoinPayload("Spike Tester") +
		`,"event":"join","requestId":"REQ"}`
	if got != want {
		t.Fatalf("join frame\n got %s\nwant %s", got, want)
	}

	// Everything after the join echoes the group id.
	storeString(&gen.group, "00000000-0000-4000-8000-000000000000")

	got = frameJSON(t, sj, gen, eventMediaIn, mediaIn{
		Method:      methodAnswer,
		Description: &sdpOut{SDP: "v=0\r\n", Type: sdpTypeAnswer},
	})
	want = `{"roomId":"abc123","payload":{"method":"rtc:answer","description":{"sdp":"v=0\r\n",` +
		`"type":"answer"}},"event":"media-in","groupId":"00000000-0000-4000-8000-000000000000",` +
		`"requestId":"REQ"}`
	if got != want {
		t.Fatalf("answer frame\n got %s\nwant %s", got, want)
	}

	mid, index, ufrag := "0", uint16(0), "abcd"
	got = frameJSON(t, sj, gen, eventMediaIn, mediaIn{
		Method: methodICE,
		Candidates: []iceCandidate{{
			Candidate:        "candidate:1 1 udp 2122260223 192.0.2.10 50000 typ host",
			SDPMid:           &mid,
			SDPMLineIndex:    &index,
			UsernameFragment: &ufrag,
			Target:           targetSubscriber,
		}},
	})
	want = `{"roomId":"abc123","payload":{"method":"rtc:ice","rtcIceCandidates":[{"candidate":` +
		`"candidate:1 1 udp 2122260223 192.0.2.10 50000 typ host","sdpMid":"0",` +
		`"sdpMLineIndex":0,"usernameFragment":"abcd","target":"SUBSCRIBER"}]},"event":"media-in",` +
		`"groupId":"00000000-0000-4000-8000-000000000000","requestId":"REQ"}`
	if got != want {
		t.Fatalf("ice frame\n got %s\nwant %s", got, want)
	}

	got = frameJSON(t, sj, gen, eventMediaIn, mediaIn{
		Method:  methodPing,
		PingReq: &pingRequest{Timestamp: 1790035953804, RTT: 0},
	})
	want = `{"roomId":"abc123","payload":{"method":"rtc:ping","ping_req":{"timestamp":1790035953804,` +
		`"rtt":0}},"event":"media-in","groupId":"00000000-0000-4000-8000-000000000000","requestId":"REQ"}`
	if got != want {
		t.Fatalf("ping frame\n got %s\nwant %s", got, want)
	}
}

// connectSession joins the fake and hands back the connected session.
func connectSession(t *testing.T, url, name string) *Session {
	t.Helper()
	sess, err := New(context.Background(), engine.Config{
		URL: url, Token: "passw0rd", Name: name,
		Extra: map[string]string{"roomID": "abc123"},
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = sess.Close() })
	if err := sess.Connect(context.Background()); err != nil {
		t.Fatalf("connect %s: %v", name, err)
	}
	return sess.(*Session)
}

// wantJoinPayload is the join payload the web client sends, field for field
// and in its order, with the password redacted.
func wantJoinPayload(name string) string {
	return `{"password":"REDACTED","participantName":"` + name + `",` +
		`"supportedFeatures":{"attachedRooms":true,"sessionGroups":true,"transcription":true,` +
		`"interpretation":true},"isSilent":false}`
}

// passwordField matches the one field a recorded frame must never be
// compared - or printed - with its value.
var passwordField = regexp.MustCompile(`"password":"[^"]*"`)

func redactPassword(frame string) string {
	return passwordField.ReplaceAllString(frame, `"password":"REDACTED"`)
}

func channelOpen(s *Session, label string) bool {
	dc := s.publisherChannel(label == labelReliable)
	return dc != nil && dc.ReadyState().String() == "open"
}

// frameJSON marshals one outgoing frame with its random request id replaced,
// so a test can compare the rest byte for byte. The password is redacted:
// the shape is what is pinned, never the credential.
func frameJSON(t *testing.T, s *Session, gen *generation, event string, payload any) string {
	t.Helper()
	frame, err := s.envelope(gen, event, payload)
	if err != nil {
		t.Fatal(err)
	}
	frame.RequestID = "REQ"
	raw, err := json.Marshal(frame)
	if err != nil {
		t.Fatal(err)
	}
	return redactPassword(string(raw))
}

func waitFor(t *testing.T, timeout time.Duration, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("timeout after %s waiting for %s", timeout, what)
}

// TestCloseDuringTheDialLeavesNoSocket pins the window the closeOnce fix
// opened: a Close that lands while the connector handshake is still running
// used to find the generation with no socket on it, spend its one teardown,
// and then have a live socket published behind it - a join went out for a
// closed session and nothing was left to close the connection.
func TestCloseDuringTheDialLeavesNoSocket(t *testing.T) {
	url, fake := newFakeConnector(t)
	release := fake.holdDials()
	t.Cleanup(release)

	sess, err := New(context.Background(), engine.Config{
		URL: url, Token: "passw0rd", Name: "dial",
		Extra: map[string]string{"roomID": "abc123"},
	})
	if err != nil {
		t.Fatal(err)
	}
	sj := sess.(*Session)
	sj.joinTimeout = 300 * time.Millisecond

	connected := make(chan error, 1)
	go func() { connected <- sess.Connect(context.Background()) }()
	waitFor(t, 5*time.Second, "the client to reach the connector", func() bool {
		return fake.dialsSeen() == 1
	})
	gen := sj.current()
	if gen == nil {
		t.Fatal("connect published no generation to close")
	}

	if err := sess.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}
	release()

	select {
	case err := <-connected:
		if err == nil {
			t.Fatal("connect succeeded after the session was closed")
		}
	case <-time.After(10 * time.Second):
		t.Fatal("connect never returned")
	}

	// The generation was torn down while the dial was in flight, so nothing
	// may be published on it afterwards.
	gen.wsMu.Lock()
	published := gen.ws != nil
	gen.wsMu.Unlock()
	if published {
		t.Fatal("a socket was published on a generation that had already been torn down")
	}
	// A frame already on the wire would arrive after Connect returned.
	deadline := time.Now().Add(500 * time.Millisecond)
	for time.Now().Before(deadline) {
		if joins := fake.joins(); len(joins) != 0 {
			t.Fatalf("%d join frames reached the connector after Close", len(joins))
		}
		time.Sleep(20 * time.Millisecond)
	}
	waitFor(t, 5*time.Second, "the connector socket to close", func() bool {
		return fake.liveSockets() == 0
	})
}

// TestPeerConnectionIsNotPublishedAfterTeardown is the same rule for the
// peer connections: one built just as its generation ends has no owner, and
// an unowned pion peer connection keeps an ICE agent and a TURN allocation
// alive. A torn-down generation refuses it and closes it.
func TestPeerConnectionIsNotPublishedAfterTeardown(t *testing.T) {
	sess := newIdleSession(t)
	api, err := newWebRTCAPI(nil, "")
	if err != nil {
		t.Fatal(err)
	}
	gen := newGeneration(api)
	sess.teardown(gen)

	pc, err := api.NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		t.Fatal(err)
	}
	if gen.publishPC(targetSubscriber, pc) {
		t.Fatal("a torn-down generation took a peer connection")
	}
	if gen.subPC.Load() != nil {
		t.Fatal("a torn-down generation kept a peer connection")
	}
	if state := pc.ConnectionState(); state != webrtc.PeerConnectionStateClosed {
		t.Fatalf("peer connection state = %s, want closed", state)
	}
}

// TestTeardownLeavesNoPeerConnectionBehind runs the publish and the teardown
// at the same instant: whichever wins, the generation ends up owning
// nothing and the peer connection ends up closed.
func TestTeardownLeavesNoPeerConnectionBehind(t *testing.T) {
	sess := newIdleSession(t)
	api, err := newWebRTCAPI(nil, "")
	if err != nil {
		t.Fatal(err)
	}
	for range 8 {
		gen := newGeneration(api)
		pc, pcErr := api.NewPeerConnection(webrtc.Configuration{})
		if pcErr != nil {
			t.Fatal(pcErr)
		}
		var wg sync.WaitGroup
		start := make(chan struct{})
		wg.Go(func() {
			<-start
			gen.publishPC(targetPublisher, pc)
		})
		wg.Go(func() {
			<-start
			sess.teardown(gen)
		})
		close(start)
		wg.Wait()

		if gen.pubPC.Load() != nil {
			t.Fatal("a torn-down generation still owns a peer connection")
		}
		if state := pc.ConnectionState(); state != webrtc.PeerConnectionStateClosed {
			t.Fatalf("peer connection state = %s, want closed", state)
		}
	}
}

// newIdleSession is a session that has never connected: enough to drive the
// lifecycle helpers without a connector.
func newIdleSession(t *testing.T) *Session {
	t.Helper()
	sess, err := New(context.Background(), engine.Config{
		URL: "wss://example.invalid/connector", Token: "passw0rd", Name: "idle",
		Extra: map[string]string{"roomID": "abc123"},
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = sess.Close() })
	return sess.(*Session)
}

// TestDataLanesRoundTripThroughTheFake drives the data plane end to end
// through the fake's relay: a reliable broadcast, a reliable packet
// addressed to one identity, and a datagram on the lossy lane, every one of
// them written on the publisher peer connection and read on the subscriber.
func TestDataLanesRoundTripThroughTheFake(t *testing.T) {
	url, fake := newFakeConnector(t)
	sender := connectSession(t, url, "a")
	receiver, in := connectPeer(t, url, "b")

	// A lane that has not opened refuses what it is given, and a
	// participant the roster has not named yet cannot be addressed.
	for _, s := range []*Session{sender, receiver} {
		waitFor(t, 5*time.Second, "the roster to name the peer and both lanes to open", func() bool {
			return len(s.remoteIdentities()) == 1 && s.CanSend() && s.DatagramCanSend()
		})
	}

	if err := sender.Send([]byte("hello-stream")); err != nil {
		t.Fatal(err)
	}
	if got := receive(t, in, "the broadcast payload"); string(got.payload) != "hello-stream" {
		t.Fatalf("stream %q", got.payload)
	}
	if err := sender.SendTo(receiver.localIdentity(), []byte("hello-targeted")); err != nil {
		t.Fatal(err)
	}
	if got := receive(t, in, "the addressed payload"); string(got.payload) != "hello-targeted" {
		t.Fatalf("targeted %q", got.payload)
	}
	if err := sender.SendDatagram([]byte("hello-dgram")); err != nil {
		t.Fatal(err)
	}
	got := receive(t, in, "the datagram")
	if string(got.payload) != "hello-dgram" || got.lane != "datagram" {
		t.Fatalf("datagram %q on the %s lane", got.payload, got.lane)
	}
	if failure := fake.lastError(); failure != "" {
		t.Fatalf("fake SFU error: %s", failure)
	}
}

// TestTargetedDeliveryAndTopicDispatch pins the two things a round trip
// between two participants cannot show: a packet addressed to one identity
// reaches that one and nobody else, and the lane a payload arrives on is
// decided by its topic, not by the channel it came in on.
func TestTargetedDeliveryAndTopicDispatch(t *testing.T) {
	url, fake := newFakeConnector(t)
	sender := connectSession(t, url, "sender")
	wanted, wantedIn := connectPeer(t, url, "wanted")
	other, otherIn := connectPeer(t, url, "other")

	// Everyone has to be in the room, on both lanes, before the first
	// addressed packet: a participant the SFU has not listed yet cannot be
	// addressed, and a lane that has not opened refuses what it is given.
	for _, s := range []*Session{sender, wanted, other} {
		waitFor(t, 5*time.Second, "the roster to name both peers and both lanes to open", func() bool {
			return len(s.remoteIdentities()) == 2 && s.CanSend() && s.DatagramCanSend()
		})
	}

	if err := sender.SendTo(wanted.localIdentity(), []byte("for-one")); err != nil {
		t.Fatal(err)
	}
	got := receive(t, wantedIn, "the addressed payload")
	if string(got.payload) != "for-one" || got.lane != "stream" {
		t.Fatalf("addressed packet = %q on %s, want %q on stream", got.payload, got.lane, "for-one")
	}
	if got.sender != sender.localIdentity() {
		t.Fatalf("sender = %q, want %q", got.sender, sender.localIdentity())
	}

	// The datagram is a broadcast, so it reaches the other participant too:
	// waiting for it there is what proves the addressed packet before it
	// did not, rather than merely being late.
	if err := sender.SendDatagram([]byte("for-all")); err != nil {
		t.Fatal(err)
	}
	if dgram := receive(t, otherIn, "the broadcast datagram"); string(dgram.payload) != "for-all" {
		t.Fatalf("other received %q before the datagram", dgram.payload)
	} else if dgram.lane != "datagram" {
		t.Fatalf("datagram arrived on the %s lane", dgram.lane)
	}
	if dgram := receive(t, wantedIn, "the broadcast datagram"); dgram.lane != "datagram" {
		t.Fatalf("datagram arrived on the %s lane", dgram.lane)
	}
	if failure := fake.lastError(); failure != "" {
		t.Fatalf("fake SFU error: %s", failure)
	}
}

// TestPeerBindingBelongsToTheConnectionAttempt covers the peer interfaces:
// the local id is the identity the join-response named, a binding is
// refused without one, and neither ResetPeer nor a rejoin leaves a stale
// binding behind.
func TestPeerBindingBelongsToTheConnectionAttempt(t *testing.T) {
	url, fake := newFakeConnector(t)
	sess := connectSession(t, url, "binding")
	peer := connectSession(t, url, "peer")

	if id := sess.LocalPeerID(); id == "" || id != sess.localIdentity() {
		t.Fatalf("local peer id %q, identity %q", id, sess.localIdentity())
	}
	if err := sess.ConfirmPeer(""); !errors.Is(err, engine.ErrInvalidPeerID) {
		t.Fatalf("ConfirmPeer(\"\") = %v, want %v", err, engine.ErrInvalidPeerID)
	}
	if err := sess.ConfirmPeer(peer.localIdentity()); err != nil {
		t.Fatal(err)
	}
	if bound := loadString(&sess.current().confirmed); bound != peer.localIdentity() {
		t.Fatalf("bound to %q, want %q", bound, peer.localIdentity())
	}
	sess.ResetPeer()
	if bound := loadString(&sess.current().confirmed); bound != "" {
		t.Fatalf("ResetPeer left %q bound", bound)
	}

	// The roster alone releases WaitForPeer: the other session is in the room.
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := sess.WaitForPeer(ctx); err != nil {
		t.Fatalf("wait for peer: %v (fake: %s)", err, fake.lastError())
	}

	// A rejoin is a fresh connection attempt, and the binding does not
	// survive it: the SFU issues new participant ids.
	if err := sess.ConfirmPeer(peer.localIdentity()); err != nil {
		t.Fatal(err)
	}
	if err := sess.reconnect(context.Background()); err != nil {
		t.Fatalf("reconnect: %v (fake: %s)", err, fake.lastError())
	}
	if bound := loadString(&sess.current().confirmed); bound != "" {
		t.Fatalf("the rejoin kept the binding %q", bound)
	}
	if failure := fake.lastError(); failure != "" {
		t.Fatalf("fake SFU error: %s", failure)
	}
}

// TestTheDataPlaneRefusesWhatItCannotCarry pins the discipline the reviews
// asked for: nothing is written to a generation that is gone, and a lane
// that has not negotiated says so instead of pretending.
func TestTheDataPlaneRefusesWhatItCannotCarry(t *testing.T) {
	idle := newIdleSession(t)
	for what, err := range map[string]error{
		"Send":           idle.Send([]byte("x")),
		"SendTo":         idle.SendTo("someone", []byte("x")),
		"SendDatagram":   idle.SendDatagram([]byte("x")),
		"SendDatagramTo": idle.SendDatagramTo("someone", []byte("x")),
	} {
		if !errors.Is(err, ErrNoDataChannel) {
			t.Fatalf("%s on a session that never connected = %v, want %v", what, err, ErrNoDataChannel)
		}
	}
	if idle.CanSend() || idle.DatagramCanSend() || idle.GetBufferedAmount() != 0 {
		t.Fatal("a session that never connected reports a live lane")
	}

	url, _ := newFakeConnector(t)
	sess := connectSession(t, url, "refuses")
	waitFor(t, 5*time.Second, "both lanes open", func() bool {
		return sess.CanSend() && sess.DatagramCanSend()
	})
	if err := sess.Close(); err != nil {
		t.Fatal(err)
	}
	for what, err := range map[string]error{
		"Send":         sess.Send([]byte("x")),
		"SendDatagram": sess.SendDatagram([]byte("x")),
	} {
		if !errors.Is(err, ErrSessionClosed) {
			t.Fatalf("%s after Close = %v, want %v", what, err, ErrSessionClosed)
		}
	}
	if sess.CanSend() || sess.DatagramCanSend() {
		t.Fatal("a closed session reports a live lane")
	}
}

// TestSenderIdentityReadsEveryStampLiveKitUses covers the fallback chain:
// 1.5.3 stamps the user packet and drops to the sid when it has no identity
// for a participant, later versions stamp the packet around it. Only the
// identity stamps name a participant the room can address.
func TestSenderIdentityReadsEveryStampLiveKitUses(t *testing.T) {
	for _, tc := range []struct {
		name     string
		packet   *livekit.DataPacket
		want     string
		identity bool
	}{
		{"user identity", &livekit.DataPacket{Value: &livekit.DataPacket_User{
			User: &livekit.UserPacket{ParticipantIdentity: "who", ParticipantSid: "PA_who"}}}, "who", true},
		{"user sid", &livekit.DataPacket{Value: &livekit.DataPacket_User{
			User: &livekit.UserPacket{ParticipantSid: "PA_who"}}}, "PA_who", false},
		{"packet identity", &livekit.DataPacket{ParticipantIdentity: "who",
			Value: &livekit.DataPacket_User{User: &livekit.UserPacket{}}}, "who", true},
		{"anonymous", &livekit.DataPacket{Value: &livekit.DataPacket_User{
			User: &livekit.UserPacket{}}}, "", false},
	} {
		got, byIdentity := senderIdentity(tc.packet)
		if got != tc.want {
			t.Fatalf("%s: sender = %q, want %q", tc.name, got, tc.want)
		}
		if byIdentity != tc.identity {
			t.Fatalf("%s: named by identity = %v, want %v", tc.name, byIdentity, tc.identity)
		}
	}
}

// inbound is one payload as a session's callbacks delivered it.
type inbound struct {
	lane    string
	sender  string
	payload []byte
}

// connectPeer joins the fake with both per-peer callbacks wired to one
// queue, so a test can tell the stream lane from the datagram lane.
func connectPeer(t *testing.T, url, name string) (*Session, chan inbound) {
	t.Helper()
	in := make(chan inbound, 16)
	sess, err := New(context.Background(), engine.Config{
		URL: url, Token: "passw0rd", Name: name,
		Extra:          map[string]string{"roomID": "abc123"},
		OnPeerData:     func(p string, d []byte) { in <- inbound{"stream", p, d} },
		OnPeerDatagram: func(p string, d []byte) { in <- inbound{"datagram", p, d} },
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = sess.Close() })
	if err := sess.Connect(context.Background()); err != nil {
		t.Fatalf("connect %s: %v", name, err)
	}
	return sess.(*Session), in
}

func receive(t *testing.T, in chan inbound, what string) inbound {
	t.Helper()
	select {
	case got := <-in:
		return got
	case <-time.After(10 * time.Second):
		t.Fatalf("timeout waiting for %s", what)
		return inbound{}
	}
}

// TestLanesAgreeWithTheGenerationTheyBelongTo pins the first of the Task 4
// review's findings: teardown closes the generation before it closes the
// peer connections, and in that window Send already refuses while the
// channel is still open. The predicates the transport layer asks before it
// writes must refuse there too, or it is told to send on a lane that is
// gone.
func TestLanesAgreeWithTheGenerationTheyBelongTo(t *testing.T) {
	url, _ := newFakeConnector(t)
	sess := connectSession(t, url, "lanes")
	waitFor(t, 5*time.Second, "both lanes open", func() bool {
		return sess.CanSend() && sess.DatagramCanSend()
	})

	// The first step of teardown, on its own: the generation is gone, its
	// channels are not closed yet.
	engine.CloseSignal(sess.current().done)

	if err := sess.Send([]byte("x")); !errors.Is(err, ErrNoDataChannel) {
		t.Fatalf("Send on a torn-down attempt = %v, want %v", err, ErrNoDataChannel)
	}
	if sess.CanSend() || sess.DatagramCanSend() {
		t.Fatalf("a torn-down attempt reports open lanes: reliable=%v lossy=%v",
			sess.CanSend(), sess.DatagramCanSend())
	}
	if buffered := sess.GetBufferedAmount(); buffered != 0 {
		t.Fatalf("a torn-down attempt reports %d buffered bytes", buffered)
	}
}

// TestOurOwnPacketIsNotDeliveredBack pins the echo filter: a packet stamped
// with this session's own identity is ours, however it came back, and the
// tunnel above must never read its own bytes.
func TestOurOwnPacketIsNotDeliveredBack(t *testing.T) {
	sess, in := newCallbackSession(t)
	gen := newGeneration(nil)
	storeString(&gen.identity, "self")

	sess.handleDataPacket(gen, userPacket(t, "self", "", []byte("mine")))
	sess.handleDataPacket(gen, userPacket(t, "other", "", []byte("theirs")))

	got := receive(t, in, "the remote payload")
	if string(got.payload) != "theirs" || got.sender != "other" {
		t.Fatalf("delivered %q from %q, want %q from %q", got.payload, got.sender, "theirs", "other")
	}
	select {
	case extra := <-in:
		t.Fatalf("a second payload %q from %q was delivered", extra.payload, extra.sender)
	default:
	}
	if peers := gen.remoteIdentities(); len(peers) != 1 || peers[0] != "other" {
		t.Fatalf("roster = %v, want just the remote identity", peers)
	}
}

// TestASidIsNotARosterEntry pins the second finding: LiveKit falls back to
// the participant sid when it has no identity for a sender, and a sid names
// nobody a packet can be addressed to. It is reported as the sender, so the
// payload still reaches the callbacks, and it never enters the roster.
func TestASidIsNotARosterEntry(t *testing.T) {
	sess, in := newCallbackSession(t)
	gen := newGeneration(nil)
	storeString(&gen.identity, "self")

	sess.handleDataPacket(gen, sidPacket(t, "PA_unnamed", []byte("payload")))

	got := receive(t, in, "the payload from an unnamed participant")
	if got.sender != "PA_unnamed" {
		t.Fatalf("sender = %q, want the sid fallback", got.sender)
	}
	if peers := gen.remoteIdentities(); len(peers) != 0 {
		t.Fatalf("roster = %v, want no entry for a sid", peers)
	}
}

// newCallbackSession is a session that never connects, with both per-peer
// callbacks wired to one queue: enough to drive the receive path directly.
func newCallbackSession(t *testing.T) (*Session, chan inbound) {
	t.Helper()
	in := make(chan inbound, 8)
	sess, err := New(context.Background(), engine.Config{
		URL: "wss://example.invalid/connector", Token: "passw0rd", Name: "callbacks",
		Extra:          map[string]string{"roomID": "abc123"},
		OnPeerData:     func(p string, d []byte) { in <- inbound{"stream", p, d} },
		OnPeerDatagram: func(p string, d []byte) { in <- inbound{"datagram", p, d} },
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = sess.Close() })
	return sess.(*Session), in
}

// userPacket is one relayed packet as LiveKit 1.5.3 stamps it: the sender on
// the user packet, under the identity the room addresses it by.
func userPacket(t *testing.T, sender, topic string, payload []byte) []byte {
	t.Helper()
	user := &livekit.UserPacket{
		Payload:             payload,
		ParticipantIdentity: sender,
		ParticipantSid:      "PA_" + sender,
	}
	if topic != "" {
		user.Topic = &topic
	}
	return marshalPacket(t, &livekit.DataPacket{Value: &livekit.DataPacket_User{User: user}})
}

// sidPacket is a packet from a participant the SFU has no identity for: only
// the sid is stamped.
func sidPacket(t *testing.T, sid string, payload []byte) []byte {
	t.Helper()
	return marshalPacket(t, &livekit.DataPacket{Value: &livekit.DataPacket_User{
		User: &livekit.UserPacket{Payload: payload, ParticipantSid: sid},
	}})
}

func marshalPacket(t *testing.T, packet *livekit.DataPacket) []byte {
	t.Helper()
	frame, err := proto.Marshal(packet)
	if err != nil {
		t.Fatal(err)
	}
	return frame
}

// TestCloseEndsAnAttemptItNeverSaw pins the window the Task 3 re-review
// found: Connect reads the terminated flag and publishes its connection
// attempt in two steps, and a Close that lands between them sees no attempt
// to end while Connect sees no close to obey. The attempt went on to dial,
// join, and hold a participant in the room until the join timed out.
func TestCloseEndsAnAttemptItNeverSaw(t *testing.T) {
	url, fake := newFakeConnector(t)
	sess, err := New(context.Background(), engine.Config{
		URL: url, Token: "passw0rd", Name: "window",
		Extra: map[string]string{"roomID": "abc123"},
	})
	if err != nil {
		t.Fatal(err)
	}
	sj := sess.(*Session)
	sj.joinTimeout = 2 * time.Second
	// Close lands inside the window, every run.
	sj.beforePublish = func() { _ = sess.Close() }

	start := time.Now()
	if err := sess.Connect(context.Background()); !errors.Is(err, ErrSessionClosed) {
		t.Fatalf("connect = %v, want %v", err, ErrSessionClosed)
	}
	if took := time.Since(start); took > time.Second {
		t.Fatalf("connect took %s: it waited out the join instead of seeing the close", took)
	}
	if dials := fake.dialsSeen(); dials != 0 {
		t.Fatalf("%d clients reached the connector behind a closed session", dials)
	}
	if joins := fake.joins(); len(joins) != 0 {
		t.Fatalf("%d joins reached the connector behind a closed session", len(joins))
	}
	if gen := sj.current(); gen != nil {
		t.Fatal("a closed session still points at a connection attempt")
	}
}

// TestAGivenUpConnectLeavesNoAttemptBehind pins the second finding: every
// accessor on this session reads the live attempt through s.cur, so a
// Connect that tore its own attempt down has to take it off the session
// too. What was left behind answered for a connection that no longer
// existed - an identity, a roster, a confirmed peer binding.
func TestAGivenUpConnectLeavesNoAttemptBehind(t *testing.T) {
	url, fake := newFakeConnector(t)
	release := fake.holdJoins()
	t.Cleanup(release)

	sess, err := New(context.Background(), engine.Config{
		URL: url, Token: "passw0rd", Name: "givenup",
		Extra: map[string]string{"roomID": "abc123"},
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = sess.Close() })
	sj := sess.(*Session)
	sj.joinTimeout = 300 * time.Millisecond

	if err := sess.Connect(context.Background()); !errors.Is(err, ErrJoinTimeout) {
		t.Fatalf("connect = %v, want %v", err, ErrJoinTimeout)
	}
	if gen := sj.current(); gen != nil {
		t.Fatalf("a failed connect left an attempt behind (torn down = %v)", gen.isDone())
	}
	if id := sj.LocalPeerID(); id != "" {
		t.Fatalf("a session with no live attempt reports the peer id %q", id)
	}
}

// TestReconnectRejoinsWithRefreshedCredentials drives a drop end to end: the
// link dies, the session asks its caller for fresh credentials, joins the
// room again from scratch and reports the reconnect on the callback that was
// registered once, before any of it.
func TestReconnectRejoinsWithRefreshedCredentials(t *testing.T) {
	url, fake := newFakeConnector(t)
	var refreshes atomic.Int64
	s, err := New(context.Background(), engine.Config{URL: url, Token: "passw0rd",
		Name: "r", Extra: map[string]string{"roomID": "abc123"},
		Refresh: func(context.Context) (engine.Credentials, error) {
			refreshes.Add(1)
			return engine.Credentials{URL: url, Token: "passw0rd",
				Extra: map[string]string{"roomID": "abc123"}}, nil
		}})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = s.Close() })
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go s.WatchConnection(ctx)

	reconnected := make(chan struct{}, 1)
	s.SetReconnectCallback(func() { reconnected <- struct{}{} })
	if err := s.Connect(context.Background()); err != nil {
		t.Fatal(err)
	}
	first := s.(*Session).localIdentity()

	fake.dropAll() // close every fake-side socket
	select {
	case <-reconnected:
	case <-time.After(10 * time.Second):
		t.Fatalf("no reconnect after drop (fake: %s)", fake.lastError())
	}
	if refreshes.Load() == 0 {
		t.Fatal("Refresh was not called")
	}
	// The rejoin is a join of its own: a fresh handshake under a new
	// identity, carrying no group id from the attempt it replaces.
	joins := fake.joins()
	if len(joins) != 2 {
		t.Fatalf("joins = %d, want 2", len(joins))
	}
	for i, join := range joins {
		if join.group != "" {
			t.Fatalf("join %d carried a group id %q", i, join.group)
		}
	}
	if id := s.(*Session).localIdentity(); id == "" || id == first {
		t.Fatalf("identity after the rejoin = %q, before = %q", id, first)
	}
	if failure := fake.lastError(); failure != "" {
		t.Fatalf("fake SFU error: %s", failure)
	}
}

// TestEndedReasonOnServerErrorEvent covers the verdict a session cannot
// reconnect its way out of: the connector says the room is gone, and a
// rejoin would only ask the same question again. The agent's respawn loop
// is what rejoins, into the room the ring sync has moved to.
func TestEndedReasonOnServerErrorEvent(t *testing.T) {
	url, fake := newFakeConnector(t)
	s, err := New(context.Background(), engine.Config{URL: url, Token: "p",
		Name: "e", Extra: map[string]string{"roomID": "abc123"}})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = s.Close() })
	ended := make(chan string, 1)
	s.SetEndedCallback(func(r string) { ended <- r })
	if err := s.Connect(context.Background()); err != nil {
		t.Fatalf("connect: %v (fake: %s)", err, fake.lastError())
	}
	fake.sendError("ROOM_NOT_FOUND", "room is gone")
	select {
	case r := <-ended:
		if !strings.Contains(r, "ROOM_NOT_FOUND") {
			t.Fatalf("reason %q", r)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("no ended callback")
	}
	// A session that has ended asks for nothing more.
	if request := s.(*Session).queueReconnect(); request != engine.ReconnectRejected {
		t.Fatalf("a reconnect was queued after the room was gone: %v", request)
	}
}

// TestAJoinRefusalEndsTheSessionAndStopsRetrying covers the other half of
// the verdict: the refusal arrives as the answer to our own join, under that
// join's requestId. Whatever the code says, our access to the room is gone,
// the Connect waiting on it is released at once rather than sitting out the
// join timeout, and nothing is retried.
func TestAJoinRefusalEndsTheSessionAndStopsRetrying(t *testing.T) {
	url, fake := newFakeConnector(t)
	fake.refuseJoins("FORBIDDEN", "not allowed in this room")

	sess, err := New(context.Background(), engine.Config{
		URL: url, Token: "passw0rd", Name: "refused",
		Extra: map[string]string{"roomID": "abc123"},
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = sess.Close() })
	sj := sess.(*Session)
	sj.joinTimeout = 20 * time.Second
	ended := make(chan string, 1)
	sess.SetEndedCallback(func(r string) { ended <- r })

	start := time.Now()
	if err := sess.Connect(context.Background()); err == nil {
		t.Fatal("connect succeeded against a connector that refused the join")
	}
	if took := time.Since(start); took > 5*time.Second {
		t.Fatalf("connect took %s: it waited out the join instead of reading the refusal", took)
	}
	select {
	case r := <-ended:
		if !strings.Contains(r, "FORBIDDEN") {
			t.Fatalf("reason %q", r)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("no ended callback")
	}
	if request := sj.queueReconnect(); request != engine.ReconnectRejected {
		t.Fatalf("a reconnect was queued after the join was refused: %v", request)
	}
	// Nor by the front door: a session that has ended does not join again,
	// whoever asks it to.
	if err := sess.Connect(context.Background()); !errors.Is(err, ErrSessionClosed) {
		t.Fatalf("connect after the refusal = %v, want %v", err, ErrSessionClosed)
	}
	if joins := fake.joins(); len(joins) != 1 {
		t.Fatalf("joins = %d, want the one that was refused", len(joins))
	}
}

// TestAnErrorForAnotherRequestLeavesTheSessionAlone pins what the capture
// shows: an error frame answers one request, under that request's id, and
// the official client draws one for a feature it is not entitled to half a
// second after joining - and stays in the room for the rest of the call. An
// error this session did not ask for, and that names no room, ends nothing.
func TestAnErrorForAnotherRequestLeavesTheSessionAlone(t *testing.T) {
	url, fake := newFakeConnector(t)
	sess := connectSession(t, url, "noisy")
	ended := make(chan string, 1)
	sess.SetEndedCallback(func(r string) { ended <- r })

	fake.sendError("FORBIDDEN", "have no permission to view transcription")

	select {
	case reason := <-ended:
		t.Fatalf("a per-request error ended the session: %s", reason)
	case <-time.After(500 * time.Millisecond):
	}
	// The socket is still there - the connector answers a ping on it - and
	// so is the lane.
	if err := sess.pingOnce(); err != nil {
		t.Fatalf("ping after the error frame: %v", err)
	}
	if !sess.CanSend() {
		t.Fatal("the lane is gone after a per-request error")
	}
	if request := sess.queueReconnect(); request != engine.ReconnectQueued {
		t.Fatalf("a live session refused a reconnect request: %v", request)
	}
}

// TestReconnectKeepsDeliveringToTheSameCallbacks pins the other half of a
// rejoin: it is a new connection attempt under a new identity, and the byte
// stream has to come back on the callbacks the caller registered once,
// before any of it.
func TestReconnectKeepsDeliveringToTheSameCallbacks(t *testing.T) {
	url, fake := newFakeConnector(t)
	receiver, in := connectPeer(t, url, "receiver")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go receiver.WatchConnection(ctx)

	reconnected := make(chan struct{}, 1)
	receiver.SetReconnectCallback(func() { reconnected <- struct{}{} })
	first := receiver.localIdentity()

	receiver.Reconnect("a liveness probe said so")
	select {
	case <-reconnected:
	case <-time.After(20 * time.Second):
		t.Fatalf("no reconnect (fake: %s)", fake.lastError())
	}
	if id := receiver.localIdentity(); id == first {
		t.Fatalf("the rejoin kept the identity %q", id)
	}

	sender := connectSession(t, url, "sender")
	for _, s := range []*Session{sender, receiver} {
		waitFor(t, 10*time.Second, "the roster to name the peer and both lanes to open", func() bool {
			return len(s.remoteIdentities()) == 1 && s.CanSend() && s.DatagramCanSend()
		})
	}
	if err := sender.SendTo(receiver.localIdentity(), []byte("after-the-rejoin")); err != nil {
		t.Fatal(err)
	}
	got := receive(t, in, "the payload after the rejoin")
	if string(got.payload) != "after-the-rejoin" || got.sender != sender.localIdentity() {
		t.Fatalf("delivered %q from %q after the rejoin", got.payload, got.sender)
	}
	if failure := fake.lastError(); failure != "" {
		t.Fatalf("fake SFU error: %s", failure)
	}
}

// TestARefreshThatFailsIsRetriedNotFatal covers the credentials call on the
// way back: it goes over the same network the session has just lost, so a
// refresh that fails is the ordinary case, not a verdict. The attempt fails
// with it, the supervisor backs off and asks again, and nothing about the
// session has ended.
func TestARefreshThatFailsIsRetriedNotFatal(t *testing.T) {
	url, fake := newFakeConnector(t)
	refused := errors.New("no credentials right now")
	var refreshes atomic.Int64
	sess, err := New(context.Background(), engine.Config{
		URL: url, Token: "passw0rd", Name: "refresh",
		Extra: map[string]string{"roomID": "abc123"},
		Refresh: func(context.Context) (engine.Credentials, error) {
			if refreshes.Add(1) == 1 {
				return engine.Credentials{}, refused
			}
			return engine.Credentials{URL: url, Token: "passw0rd",
				Extra: map[string]string{"roomID": "abc123"}}, nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = sess.Close() })
	ended := make(chan string, 1)
	sess.SetEndedCallback(func(r string) { ended <- r })
	reconnected := make(chan struct{}, 1)
	sess.SetReconnectCallback(func() { reconnected <- struct{}{} })

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go sess.WatchConnection(ctx)
	if err := sess.Connect(context.Background()); err != nil {
		t.Fatalf("connect: %v (fake: %s)", err, fake.lastError())
	}

	fake.dropAll()
	select {
	case <-reconnected:
	case <-time.After(30 * time.Second):
		t.Fatalf("no reconnect after a refresh that failed (fake: %s)", fake.lastError())
	}
	if got := refreshes.Load(); got < 2 {
		t.Fatalf("refreshes = %d, want the failed one and the one that worked", got)
	}
	select {
	case reason := <-ended:
		t.Fatalf("a refresh that failed ended the session: %s", reason)
	default:
	}
}
