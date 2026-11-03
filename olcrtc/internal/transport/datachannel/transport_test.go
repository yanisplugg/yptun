package datachannel

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
	enginebuiltin "github.com/openlibrecommunity/olcrtc/internal/engine/builtin"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
)

var (
	errDCBoom        = errors.New("boom")
	errDCConnectBoom = errors.New("connect boom")
	errDCSendBoom    = errors.New("send boom")
	errDCCloseBoom   = errors.New("close boom")
)

type stubSession struct {
	connectErr    error
	sendErr       error
	closeErr      error
	canSend       bool
	connectCalled bool
	sent          []byte
	watched       bool
	reconnectCB   func()
	shouldFn      func() bool
	endedCB       func(string)
}

func (s *stubSession) Connect(context.Context) error { s.connectCalled = true; return s.connectErr }
func (s *stubSession) Send(data []byte) error {
	s.sent = append([]byte(nil), data...)
	return s.sendErr
}
func (s *stubSession) Close() error                      { return s.closeErr }
func (s *stubSession) SetReconnectCallback(cb func())    { s.reconnectCB = cb }
func (s *stubSession) SetShouldReconnect(fn func() bool) { s.shouldFn = fn }
func (s *stubSession) SetEndedCallback(cb func(string))  { s.endedCB = cb }
func (s *stubSession) WatchConnection(context.Context)   { s.watched = true }
func (s *stubSession) CanSend() bool                     { return s.canSend }
func (s *stubSession) SubscriberCanSend() bool           { return s.canSend }
func (s *stubSession) GetBufferedAmount() uint64         { return 0 }
func (s *stubSession) Reconnect(string)                  {}

type datagramStubSession struct {
	*stubSession
	datagramErr error
	dgCanSend   bool
	dgSent      []byte
	dgPeer      string
}

func (s *datagramStubSession) SendDatagram(data []byte) error {
	s.dgSent = append([]byte(nil), data...)
	return s.datagramErr
}

func (s *datagramStubSession) SendDatagramTo(peerID string, data []byte) error {
	s.dgPeer = peerID
	return s.SendDatagram(data)
}

func (s *datagramStubSession) DatagramCanSend() bool { return s.dgCanSend }

func TestDatagramDelegation(t *testing.T) {
	sess := &datagramStubSession{stubSession: &stubSession{}, dgCanSend: true}
	tr := &streamTransport{session: sess}
	if !tr.Features().Datagram {
		t.Fatal("Features().Datagram = false, want true")
	}
	if !tr.DatagramCanSend() {
		t.Fatal("DatagramCanSend() = false, want true")
	}
	if err := tr.SendDatagram([]byte("udp")); err != nil {
		t.Fatalf("SendDatagram() error = %v", err)
	}
	if string(sess.dgSent) != "udp" {
		t.Fatalf("datagram sent = %q, want udp", sess.dgSent)
	}
	if err := tr.SendDatagramTo("peer-a", []byte("direct")); err != nil {
		t.Fatalf("SendDatagramTo() error = %v", err)
	}
	if sess.dgPeer != "peer-a" || string(sess.dgSent) != "direct" {
		t.Fatalf("peer datagram peer=%q sent=%q", sess.dgPeer, sess.dgSent)
	}
}

func TestDatagramUnsupported(t *testing.T) {
	tr := &streamTransport{session: &stubSession{}}
	if tr.Features().Datagram {
		t.Fatal("Features().Datagram = true, want false")
	}
	if tr.DatagramCanSend() {
		t.Fatal("DatagramCanSend() = true, want false")
	}
	if err := tr.SendDatagram([]byte("udp")); !errors.Is(err, transport.ErrDatagramUnsupported) {
		t.Fatalf("SendDatagram() error = %v, want %v", err, transport.ErrDatagramUnsupported)
	}
	if err := tr.SendDatagramTo("peer-a", []byte("udp")); !errors.Is(err, transport.ErrDatagramUnsupported) {
		t.Fatalf("SendDatagramTo() error = %v, want %v", err, transport.ErrDatagramUnsupported)
	}
}

type identitySession struct {
	*stubSession
	local     string
	confirmed string
}

func (s *identitySession) LocalPeerID() string { return s.local }
func (s *identitySession) ConfirmPeer(peerID string) error {
	s.confirmed = peerID
	return nil
}

func registerProvider(name string, sess engine.Session, err error) {
	enginebuiltin.Register(name, func(context.Context, enginebuiltin.Config) (engine.Session, error) {
		if err != nil {
			return nil, err
		}
		return sess, nil
	})
}

func TestNewAndFeatures(t *testing.T) {
	sess := &stubSession{canSend: true}
	registerProvider("datachannel-test-new-and-features", sess, nil)

	tr, err := New(context.Background(), transport.Config{Provider: "datachannel-test-new-and-features"})
	if err != nil {
		t.Fatalf("New() error = %v", err)
	}

	if err := tr.Connect(context.Background()); err != nil {
		t.Fatalf("Connect() error = %v", err)
	}
	if !sess.connectCalled {
		t.Fatal("Connect() was not forwarded")
	}
	if err := tr.Send([]byte("payload")); err != nil {
		t.Fatalf("Send() error = %v", err)
	}
	if string(sess.sent) != "payload" {
		t.Fatalf("Send() forwarded %q, want payload", sess.sent)
	}
	tr.SetReconnectCallback(func() {})
	tr.SetShouldReconnect(func() bool { return true })
	tr.SetEndedCallback(func(string) {})
	tr.WatchConnection(context.Background())
	if sess.reconnectCB == nil || sess.shouldFn == nil || sess.endedCB == nil || !sess.watched {
		t.Fatal("callbacks/watch were not forwarded")
	}
	if !tr.CanSend() {
		t.Fatal("CanSend() = false, want true")
	}

	features := tr.Features()
	if features.MaxPayloadSize != defaultMaxPayloadSize {
		t.Fatalf("Features() = %+v", features)
	}
	// ai-generated: the bridge relays a burst of tiny messages only if they
	// are gathered (olcrtc#11).
	if features.WriteInterval != writeInterval {
		t.Fatalf("Features().WriteInterval = %v, want %v", features.WriteInterval, writeInterval)
	}
	if err := tr.Close(); err != nil {
		t.Fatalf("Close() error = %v", err)
	}
}

func TestNewErrorPaths(t *testing.T) {
	registerProvider("datachannel-fail-create", nil, errDCBoom)
	_, err := New(context.Background(), transport.Config{Provider: "datachannel-fail-create"})
	if err == nil || err.Error() != "open engine session: boom" {
		t.Fatalf("New() error = %v", err)
	}
}

func TestPeerIdentityPropagatesToEngine(t *testing.T) {
	sess := &identitySession{stubSession: &stubSession{}, local: "1234abcd"}
	registerProvider("datachannel-test-peer-identity", sess, nil)

	tr, err := New(context.Background(), transport.Config{Provider: "datachannel-test-peer-identity"})
	if err != nil {
		t.Fatalf("New() error = %v", err)
	}
	identity, ok := tr.(transport.PeerIdentity)
	if !ok {
		t.Fatal("datachannel transport does not expose PeerIdentity")
	}
	if got := identity.LocalPeerID(); got != sess.local {
		t.Fatalf("LocalPeerID() = %q, want %q", got, sess.local)
	}
	if err := identity.ConfirmPeer("89abcdef"); err != nil {
		t.Fatalf("ConfirmPeer() error = %v", err)
	}
	if sess.confirmed != "89abcdef" {
		t.Fatalf("engine confirmed peer = %q, want 89abcdef", sess.confirmed)
	}
}

func TestStreamTransportWrapsErrors(t *testing.T) {
	tr := &streamTransport{session: &stubSession{
		connectErr: errDCConnectBoom,
		sendErr:    errDCSendBoom,
		closeErr:   errDCCloseBoom,
	}}

	if err := tr.Connect(context.Background()); err == nil || err.Error() != "session connect: connect boom" {
		t.Fatalf("Connect() error = %v", err)
	}
	if err := tr.Send([]byte("x")); err == nil || err.Error() != "session send: send boom" {
		t.Fatalf("Send() error = %v", err)
	}
	if err := tr.Close(); err == nil || err.Error() != "session close: close boom" {
		t.Fatalf("Close() error = %v", err)
	}
}

// retiringSession is an engine that keeps per-peer send state under the
// server's peer sessions.
type retiringSession struct {
	*stubSession
	retired []string
}

func (s *retiringSession) RetirePeer(peerID string) { s.retired = append(s.retired, peerID) }

// TestRetirePeerReachesTheEngine covers the server's word that its session
// on a peer has ended: the datachannel transport takes it and passes it on to
// an engine that keeps state per peer, and leaves an engine that keeps none
// alone.
//
// ai-generated: this test (olcrtc#49).
func TestRetirePeerReachesTheEngine(t *testing.T) {
	sess := &retiringSession{stubSession: &stubSession{}}
	var tr any = &streamTransport{session: sess}
	lifecycle, ok := tr.(transport.PeerLifecycle)
	if !ok {
		t.Fatal("the datachannel transport does not take the server's peer retirements")
	}
	lifecycle.RetirePeer("peer-1")
	if len(sess.retired) != 1 || sess.retired[0] != "peer-1" {
		t.Fatalf("the engine was told %v, want [peer-1]", sess.retired)
	}

	var plain any = &streamTransport{session: &stubSession{}}
	plain.(transport.PeerLifecycle).RetirePeer("peer-1")
}

// parkingSession is an engine whose send to one peer parks until the test
// releases it, the way a SaluteJazz send waits on that peer's relay window.
type parkingSession struct {
	*stubSession
	parked  string
	entered chan struct{}
	release chan struct{}
}

func (s *parkingSession) SendTo(peerID string, _ []byte) error {
	if peerID == s.parked {
		close(s.entered)
		<-s.release
	}
	return nil
}

// TestASendHeldForOnePeerDoesNotHoldAnother is a `traffic:` policy on a
// server whose engine holds a send per destination: one client's send
// waiting on that client's leg must not keep every other client's sends -
// their streams and their control pongs - waiting behind it.
//
// ai-generated: this test (olcrtc#49).
func TestASendHeldForOnePeerDoesNotHoldAnother(t *testing.T) {
	sess := &parkingSession{
		stubSession: &stubSession{},
		parked:      "slow",
		entered:     make(chan struct{}),
		release:     make(chan struct{}),
	}
	tr := &streamTransport{session: sess}
	tr.shaper = transport.NewShaper(transport.TrafficConfig{MaxPayloadSize: 4096}, tr.Features())

	held := make(chan error, 1)
	go func() { held <- tr.SendTo("slow", []byte("x")) }()
	select {
	case <-sess.entered:
	case <-time.After(5 * time.Second):
		t.Fatal("the send to the slow peer never reached the engine")
	}
	fast := make(chan error, 1)
	go func() { fast <- tr.SendTo("fast", []byte("y")) }()
	select {
	case err := <-fast:
		if err != nil {
			t.Fatalf("SendTo(fast) error = %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("a send to one peer waited on a send held for another")
	}
	close(sess.release)
	if err := <-held; err != nil {
		t.Fatalf("SendTo(slow) error = %v", err)
	}
}
