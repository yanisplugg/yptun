package datachannel

import (
	"context"
	"errors"
	"testing"

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
