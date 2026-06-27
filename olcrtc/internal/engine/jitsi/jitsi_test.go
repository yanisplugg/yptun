package jitsi

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
	"github.com/zarazaex69/j"
)

const (
	testHost      = "meet.example.com"
	testRoom      = "myroom"
	rawFieldKey   = "raw"
	classEndpoint = "EndpointMessage"
)

func TestNormaliseHost(t *testing.T) {
	tests := []struct {
		raw  string
		want string
	}{
		{testHost, testHost},
		{"https://" + testHost, testHost},
		{"https://" + testHost + "/", testHost},
		{"https://" + testHost + "/path", testHost},
		{"//" + testHost, testHost},
		{"  https://" + testHost + "  ", testHost},
		{"", ""},
	}
	for _, tc := range tests {
		t.Run(tc.raw, func(t *testing.T) {
			if got := normaliseHost(tc.raw); got != tc.want {
				t.Fatalf("normaliseHost(%q) = %q, want %q", tc.raw, got, tc.want)
			}
		})
	}
}

func TestDecodeRaw(t *testing.T) {
	const payload = "hello world"
	encoded := encodeForTest(t, []byte(payload))

	got := decodeRaw(makeBridgeMessage(classEndpoint, map[string]any{rawFieldKey: encoded}))
	if string(got) != payload {
		t.Fatalf("decodeRaw = %q, want %q", got, payload)
	}

	if got := decodeRaw(makeBridgeMessage("OtherClass", map[string]any{rawFieldKey: encoded})); got != nil {
		t.Fatalf("decodeRaw(other class) = %q, want nil", got)
	}
	if got := decodeRaw(makeBridgeMessage(classEndpoint, map[string]any{})); got != nil {
		t.Fatalf("decodeRaw(no raw) = %q, want nil", got)
	}
	if got := decodeRaw(makeBridgeMessage(classEndpoint, map[string]any{rawFieldKey: "not-base64!!!"})); got != nil {
		t.Fatalf("decodeRaw(bad base64) = %q, want nil", got)
	}
}

func TestNewRequiresHost(t *testing.T) {
	_, err := New(context.Background(), engine.Config{
		Extra: map[string]string{credentialKeyRoom: testRoom},
	})
	if !errors.Is(err, ErrHostRequired) {
		t.Fatalf("err = %v, want ErrHostRequired", err)
	}
}

func TestNewRequiresRoom(t *testing.T) {
	_, err := New(context.Background(), engine.Config{
		URL: testHost,
	})
	if !errors.Is(err, ErrRoomRequired) {
		t.Fatalf("err = %v, want ErrRoomRequired", err)
	}
}

func TestNewSucceeds(t *testing.T) {
	sess, err := New(context.Background(), engine.Config{
		URL:   "https://" + testHost,
		Extra: map[string]string{credentialKeyRoom: testRoom},
		Name:  "olcrtc-test",
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer func() { _ = sess.Close() }()
	caps := sess.Capabilities()
	if !caps.ByteStream || !caps.VideoTrack {
		t.Fatalf("Capabilities = %+v, want ByteStream && VideoTrack", caps)
	}
}

func TestByteStreamWebSocketNegotiatesPeerConnectionWithoutRTCPKeepalive(t *testing.T) {
	sess, err := New(context.Background(), engine.Config{
		URL:    testHost,
		Extra:  map[string]string{credentialKeyRoom: testRoom},
		OnData: func([]byte) {},
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer func() { _ = sess.Close() }()

	js, ok := sess.(*Session)
	if !ok {
		t.Fatal("sess is not *Session")
	}
	if !js.shouldNegotiatePC(true) {
		t.Fatal("shouldNegotiatePC(true) = false for websocket bytestream session")
	}
	if js.shouldRequestVideo() {
		t.Fatal("shouldRequestVideo() = true for bytestream-only session")
	}
}

func TestByteStreamSCTPFallbackNegotiatesPeerConnection(t *testing.T) {
	sess, err := New(context.Background(), engine.Config{
		URL:    testHost,
		Extra:  map[string]string{credentialKeyRoom: testRoom},
		OnData: func([]byte) {},
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer func() { _ = sess.Close() }()

	js, ok := sess.(*Session)
	if !ok {
		t.Fatal("sess is not *Session")
	}
	if !js.shouldNegotiatePC(true) {
		t.Fatal("shouldNegotiatePC(true) = false for SCTP bytestream fallback")
	}
	if js.shouldRequestVideo() {
		t.Fatal("shouldRequestVideo() = true for bytestream-only session")
	}
}

func TestVideoSessionNegotiatesPeerConnectionAndRequestsVideo(t *testing.T) {
	sess, err := New(context.Background(), engine.Config{
		URL:   testHost,
		Extra: map[string]string{credentialKeyRoom: testRoom},
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer func() { _ = sess.Close() }()

	js, ok := sess.(*Session)
	if !ok {
		t.Fatal("sess is not *Session")
	}
	if js.shouldNegotiatePC(false) {
		t.Fatal("shouldNegotiatePC(false) = true before bytestream/video is configured")
	}
	if err := js.AddVideoTrack(nil); err != nil {
		t.Fatalf("AddVideoTrack(nil): %v", err)
	}
	if !js.shouldNegotiatePC(false) {
		t.Fatal("shouldNegotiatePC(false) = false for video session")
	}
	if !js.shouldRequestVideo() {
		t.Fatal("shouldRequestVideo() = false for video session")
	}
}

func TestSendBeforeConnect(t *testing.T) {
	sess, err := New(context.Background(), engine.Config{
		URL:    testHost,
		Extra:  map[string]string{credentialKeyRoom: testRoom},
		OnData: func([]byte) {},
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer func() { _ = sess.Close() }()
	if err := sess.Send([]byte("data")); !errors.Is(err, ErrBridgeNotReady) {
		t.Fatalf("Send err = %v, want ErrBridgeNotReady", err)
	}
}

func TestSendAfterClose(t *testing.T) {
	sess, err := New(context.Background(), engine.Config{
		URL:   testHost,
		Extra: map[string]string{credentialKeyRoom: testRoom},
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	if err := sess.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
	if err := sess.Send([]byte("data")); !errors.Is(err, ErrSessionClosed) {
		t.Fatalf("Send err = %v, want ErrSessionClosed", err)
	}
}

func TestSanitiseNick(t *testing.T) {
	tests := []struct {
		raw  string
		want string
	}{
		{nameAlice, nameAlice},
		{"Alice Smith", "Alice-Smith"},
		{"Конрад Олег", "Konrad-Oleg"},
		{"olcrtc-bot42", "olcrtc-bot42"},
		{"  bob  ", nameBob},
		{"$$$ %%%", ""},
		{"verylongnicknamethatexceedslimit", "verylongnicknamet"[:16]},
	}
	for _, tc := range tests {
		t.Run(tc.raw, func(t *testing.T) {
			if got := sanitiseNick(tc.raw); got != tc.want {
				t.Fatalf("sanitiseNick(%q) = %q, want %q", tc.raw, got, tc.want)
			}
		})
	}
}

func TestDeliverBridgeMessageMagicAndPeerLatch(t *testing.T) {
	sess, err := New(context.Background(), engine.Config{
		URL:   testHost,
		Extra: map[string]string{credentialKeyRoom: testRoom},
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer func() { _ = sess.Close() }()

	js, ok := sess.(*Session)
	if !ok {
		t.Fatal("sess is not *Session")
	}
	var received [][]byte
	js.onData = func(b []byte) {
		received = append(received, append([]byte(nil), b...))
	}

	good := makeBridgeFrame(t, []byte("alpha"))
	bad := encodeForTest(t, []byte("alpha")) // no magic prefix

	// First valid frame from peerA latches the peer and is delivered.
	if !js.deliverBridgeMessage(makeBridgeMessageFrom("peerA", map[string]any{rawFieldKey: good}), true) {
		t.Fatal("deliverBridgeMessage returned false on valid frame")
	}
	// Frame without magic is dropped.
	js.deliverBridgeMessage(makeBridgeMessageFrom("peerA", map[string]any{rawFieldKey: bad}), true)
	// Frame from a different sender re-latches: any sender that passes
	// the OLR magic check is by definition another olcrtc instance, and
	// when a peer reconnects JVB assigns it a new endpoint id. We must
	// adopt the new id so the peer's post-reconnect bytes flow.
	beta := makeBridgeFrame(t, []byte("beta"))
	js.deliverBridgeMessage(makeBridgeMessageFrom("peerB", map[string]any{rawFieldKey: beta}), true)

	if len(received) != 2 {
		t.Fatalf("received frames = %d, want 2 (%q)", len(received), received)
	}
	if string(received[0]) != "alpha" || string(received[1]) != "beta" {
		t.Fatalf("received = %q, want [alpha beta]", received)
	}
	if p := js.peerEndpoint.Load(); p == nil || *p != "peerB" {
		t.Fatalf("peerEndpoint after re-latch = %v, want peerB", p)
	}
}

func TestDeliverBridgeMessageWithPeerDataDoesNotLatchSinglePeer(t *testing.T) {
	sess, err := New(context.Background(), engine.Config{
		URL:   testHost,
		Extra: map[string]string{credentialKeyRoom: testRoom},
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer func() { _ = sess.Close() }()

	js, ok := sess.(*Session)
	if !ok {
		t.Fatal("sess is not *Session")
	}
	got := make(map[string]string)
	js.onPeerData = func(peerID string, b []byte) {
		got[peerID] = string(b)
	}

	frameA := makeBridgeFrameForEpoch(t, 0x1111, 0, []byte("alpha"))
	frameB := makeBridgeFrameForEpoch(t, 0x2222, 0, []byte("beta"))
	js.deliverBridgeMessage(makeBridgeMessageFrom("peerA", map[string]any{rawFieldKey: frameA}), true)
	js.deliverBridgeMessage(makeBridgeMessageFrom("peerB", map[string]any{rawFieldKey: frameB}), true)

	if got["peerA"] != "alpha" || got["peerB"] != "beta" {
		t.Fatalf("peer data = %#v, want both peers delivered", got)
	}
}

func TestDeliverBridgeMessageDropsStalePeerEpoch(t *testing.T) {
	sess, err := New(context.Background(), engine.Config{
		URL:   testHost,
		Extra: map[string]string{credentialKeyRoom: testRoom},
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer func() { _ = sess.Close() }()

	js, ok := sess.(*Session)
	if !ok {
		t.Fatal("sess is not *Session")
	}
	js.localEpoch.Store(0x2222)
	delivered := false
	js.onData = func([]byte) { delivered = true }

	stale := makeBridgeFrameForEpoch(t, 0x1111, 0xaaaa, []byte("old-smux"))
	js.deliverBridgeMessage(makeBridgeMessageFrom("peerA", map[string]any{rawFieldKey: stale}), true)
	if delivered {
		t.Fatal("stale peer-epoch frame was delivered")
	}
}

func TestReconnectEpochAnnounceWithZeroPeerEpochIsAccepted(t *testing.T) {
	sess, err := New(context.Background(), engine.Config{
		URL:   testHost,
		Extra: map[string]string{credentialKeyRoom: testRoom},
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer func() { _ = sess.Close() }()

	js, ok := sess.(*Session)
	if !ok {
		t.Fatal("sess is not *Session")
	}
	js.localEpoch.Store(0x2222)

	announce := makeBridgeFrameForEpoch(t, 0x1111, 0, nil)
	js.deliverBridgeMessage(makeBridgeMessageFrom("peerA", map[string]any{rawFieldKey: announce}), true)
	if got := js.peerEpoch.Load(); got != 0x1111 {
		t.Fatalf("peerEpoch = 0x%08x, want announce epoch", got)
	}
}

// TestRequireTargetedPeerLatchesFirstBroadcastThenRejectsOthers codifies the
// field-verified handshake order under WaitForPeer. The client blocks in
// WaitForPeer until peerEpoch latches, and the latch can only come from the
// peer's first bridge frame. Because the client waits before sending its own
// SYN, the server has not learned the client's localEpoch yet, so that first
// frame necessarily arrives as a broadcast (receiverEpoch==0). The guard must
// therefore accept an unlatched broadcast (or WaitForPeer wedges and the link
// never comes up - "ping works, no connection"). Once latched, broadcasts from
// a different senderEpoch (a third-party olcrtc instance or a stale ghost in a
// polluted room) are dropped, while further frames from the latched peer keep
// flowing.
//
//nolint:cyclop // setup asserts latch, epoch, and delivery state
func TestRequireTargetedPeerLatchesFirstBroadcastThenRejectsOthers(t *testing.T) {
	var received [][]byte
	sess, err := New(context.Background(), engine.Config{
		URL:                 testHost,
		Extra:               map[string]string{credentialKeyRoom: testRoom},
		RequireTargetedPeer: true,
		OnData: func(b []byte) {
			received = append(received, append([]byte(nil), b...))
		},
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer func() { _ = sess.Close() }()

	js, ok := sess.(*Session)
	if !ok {
		t.Fatal("sess is not *Session")
	}
	js.localEpoch.Store(0x3333)

	// Server welcome arrives as a broadcast (receiverEpoch==0) because the
	// server has not learned our epoch yet. It must latch us and be delivered
	// so WaitForPeer can unblock.
	serverWelcome := makeBridgeFrameForEpoch(t, 0x1111, 0, []byte("SERVER_WELCOME"))
	js.deliverBridgeMessage(makeBridgeMessageFrom("server", map[string]any{rawFieldKey: serverWelcome}), true)
	if len(received) != 1 || string(received[0]) != "SERVER_WELCOME" {
		t.Fatalf("received = %q, want server welcome", received)
	}
	if got := js.peerEpoch.Load(); got != 0x1111 {
		t.Fatalf("peerEpoch after welcome = 0x%08x, want server epoch", got)
	}
	if got := js.peerEndpoint.Load(); got == nil || *got != "server" {
		t.Fatalf("peerEndpoint after welcome = %v, want server", got)
	}

	// A broadcast from a different senderEpoch after we are latched is a
	// third-party/ghost and must be dropped.
	otherClient := makeBridgeFrameForEpoch(t, 0x2222, 0, []byte("CLIENT_HELLO"))
	js.deliverBridgeMessage(makeBridgeMessageFrom("clientB", map[string]any{rawFieldKey: otherClient}), true)
	if len(received) != 1 {
		t.Fatalf("received after third-party broadcast = %q, want only server welcome", received)
	}
	if got := js.peerEpoch.Load(); got != 0x1111 {
		t.Fatalf("peerEpoch after third-party broadcast = 0x%08x, want latched server epoch", got)
	}

	// A further frame from the latched peer keeps flowing.
	more := makeBridgeFrameForEpoch(t, 0x1111, 0, []byte("MORE"))
	js.deliverBridgeMessage(makeBridgeMessageFrom("server", map[string]any{rawFieldKey: more}), true)
	if len(received) != 2 || string(received[1]) != "MORE" {
		t.Fatalf("received = %q, want server welcome + MORE", received)
	}
}

// TestDeliverBridgeMessagePeerEpochChangeAcceptsFrameNoReconnect codifies
// the post-fix behaviour: when a peer's epoch flips (because the peer
// reconnected), we update our latch and ACCEPT the new frame instead of
// dropping it AND NEVER trigger our own reconnect. The earlier
// "reconnect on peer epoch change" semantics created a tight ping-pong
// loop: peer reconnects → we drop their first frame and reconnect →
// we publish a fresh epoch → peer drops our frame and reconnects → ...
// Both sides ended up in a cycle with no data flowing, which is exactly
// what the paired chaos stress test caught.
func TestDeliverBridgeMessagePeerEpochChangeAcceptsFrameNoReconnect(t *testing.T) {
	sess, err := New(context.Background(), engine.Config{
		URL:   testHost,
		Extra: map[string]string{credentialKeyRoom: testRoom},
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer func() { _ = sess.Close() }()

	js, ok := sess.(*Session)
	if !ok {
		t.Fatal("sess is not *Session")
	}
	js.localEpoch.Store(0x3333)
	js.SetShouldReconnect(func() bool { return true })
	var received [][]byte
	js.onData = func(b []byte) {
		received = append(received, append([]byte(nil), b...))
	}

	first := makeBridgeFrameForEpoch(t, 0x1111, 0, []byte("first"))
	js.deliverBridgeMessage(makeBridgeMessageFrom("peerA", map[string]any{rawFieldKey: first}), true)

	// Peer reconnected, new epoch, and the very first post-reconnect
	// frame carries the new payload.
	changed := makeBridgeFrameForEpoch(t, 0x2222, 0x3333, []byte("after-peer-reconnect"))
	js.deliverBridgeMessage(makeBridgeMessageFrom("peerA", map[string]any{rawFieldKey: changed}), true)

	if len(received) != 2 ||
		string(received[0]) != "first" ||
		string(received[1]) != "after-peer-reconnect" {
		t.Fatalf("received = %q, want both payloads in order", received)
	}
	if got := js.peerEpoch.Load(); got != 0x2222 {
		t.Fatalf("peerEpoch.Load() = 0x%X, want 0x2222 (latch must update)", got)
	}
	select {
	case <-js.reconnectCh:
		t.Fatal("peer epoch change must NOT enqueue a self-reconnect (causes ping-pong loop)")
	case <-time.After(100 * time.Millisecond):
	}
}

func TestBridgeCloseRequestsReconnect(t *testing.T) {
	sess, err := New(context.Background(), engine.Config{
		URL:   testHost,
		Extra: map[string]string{credentialKeyRoom: testRoom},
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer func() { _ = sess.Close() }()

	js, ok := sess.(*Session)
	if !ok {
		t.Fatal("sess is not *Session")
	}
	var ended string
	js.SetEndedCallback(func(reason string) { ended = reason })
	js.SetShouldReconnect(func() bool { return true })

	if js.deliverBridgeMessage(j.BridgeMessage{}, false) {
		t.Fatal("deliverBridgeMessage returned true on closed bridge")
	}
	select {
	case <-js.reconnectCh:
	case <-time.After(time.Second):
		t.Fatal("bridge close did not request reconnect")
	}
	if ended != "" {
		t.Fatalf("ended = %q, want empty", ended)
	}
}

func TestBridgeCloseEndsWhenReconnectDisabled(t *testing.T) {
	sess, err := New(context.Background(), engine.Config{
		URL:   testHost,
		Extra: map[string]string{credentialKeyRoom: testRoom},
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer func() { _ = sess.Close() }()

	js, ok := sess.(*Session)
	if !ok {
		t.Fatal("sess is not *Session")
	}
	var ended string
	js.SetEndedCallback(func(reason string) { ended = reason })
	js.SetShouldReconnect(func() bool { return false })

	if js.deliverBridgeMessage(j.BridgeMessage{}, false) {
		t.Fatal("deliverBridgeMessage returned true on closed bridge")
	}
	if ended != "jitsi bridge closed" {
		t.Fatalf("ended = %q, want bridge close reason", ended)
	}
}

func TestEngineRegistration(t *testing.T) {
	if _, err := engine.New(context.Background(), "jitsi", engine.Config{
		URL:   testHost,
		Extra: map[string]string{credentialKeyRoom: testRoom},
	}); err != nil {
		t.Fatalf("engine.New(jitsi) = %v, want nil", err)
	}
}
