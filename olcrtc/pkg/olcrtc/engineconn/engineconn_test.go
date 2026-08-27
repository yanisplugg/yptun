package engineconn

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/auth"
	"github.com/openlibrecommunity/olcrtc/internal/engine"
)

type stubSession struct {
	connected  bool
	onEnded    func(string)
	watchBlock chan struct{}
}

func newStubSession() *stubSession { return &stubSession{watchBlock: make(chan struct{})} }

func (s *stubSession) Connect(context.Context) error    { s.connected = true; return nil }
func (*stubSession) Send([]byte) error                  { return nil }
func (*stubSession) Close() error                       { return nil }
func (*stubSession) SetReconnectCallback(func())        {}
func (*stubSession) SetShouldReconnect(func() bool)     {}
func (s *stubSession) SetEndedCallback(cb func(string)) { s.onEnded = cb }
func (s *stubSession) WatchConnection(context.Context)  { <-s.watchBlock }
func (s *stubSession) CanSend() bool                    { return s.connected }
func (*stubSession) GetBufferedAmount() uint64          { return 0 }
func (*stubSession) Reconnect(string)                   {}
func (s *stubSession) SubscriberCanSend() bool          { return s.connected }

type stubProvider struct {
	engineName string
	tokenSeen  chan string
}

func (p stubProvider) Engine() string          { return p.engineName }
func (stubProvider) DefaultServiceURL() string { return "https://example" }
func (p stubProvider) Issue(_ context.Context, cfg auth.Config) (auth.Credentials, error) {
	if p.tokenSeen != nil {
		p.tokenSeen <- cfg.Token
	}
	return auth.Credentials{URL: "wss://example", Token: "token"}, nil
}

func registerEngine(name string, session engine.Session) {
	engine.Register(name, func(context.Context, engine.Config) (engine.Session, error) { return session, nil })
}

func TestNewDirectValidation(t *testing.T) {
	if _, err := New(context.Background(), Config{Token: "token"}); !errors.Is(err, ErrURLRequired) {
		t.Fatalf("New(no URL) error = %v", err)
	}
	if _, err := New(context.Background(), Config{URL: "wss://example"}); !errors.Is(err, ErrTokenRequired) {
		t.Fatalf("New(no token) error = %v", err)
	}
}

func TestProviderTokenForwarded(t *testing.T) {
	stub := newStubSession()
	registerEngine("provider-token-engine", stub)
	seen := make(chan string, 1)
	auth.Register("provider-token", stubProvider{engineName: "provider-token-engine", tokenSeen: seen})
	if _, err := New(context.Background(), Config{
		Provider: "provider-token", RoomURL: "room", ProviderToken: "account-token",
	}); err != nil {
		t.Fatalf("New() error = %v", err)
	}
	if token := <-seen; token != "account-token" {
		t.Fatalf("provider token = %q", token)
	}
}

func TestDialChainsEndedCallbackAndUnblocksRead(t *testing.T) {
	stub := newStubSession()
	registerEngine("ended-engine", stub)
	session, err := New(context.Background(), Config{
		Engine: "ended-engine", URL: "wss://example", Token: "token",
	})
	if err != nil {
		t.Fatalf("New() error = %v", err)
	}
	ended := make(chan string, 1)
	session.SetEndedCallback(func(reason string) { ended <- reason })
	conn, err := session.Dial(context.Background())
	if err != nil {
		t.Fatalf("Dial() error = %v", err)
	}
	readErr := make(chan error, 1)
	go func() {
		_, readErrValue := conn.Read(make([]byte, 1))
		readErr <- readErrValue
	}()
	stub.onEnded("finished")
	close(stub.watchBlock)

	select {
	case reason := <-ended:
		if reason != "finished" {
			t.Fatalf("ended reason = %q", reason)
		}
	case <-time.After(time.Second):
		t.Fatal("user callback was not called")
	}
	select {
	case err := <-readErr:
		if !errors.Is(err, ErrSessionEnded) {
			t.Fatalf("Read() error = %v, want ErrSessionEnded", err)
		}
	case <-time.After(time.Second):
		t.Fatal("Read() did not unblock")
	}
}

func TestDialWriteAndClose(t *testing.T) {
	stub := newStubSession()
	registerEngine("stream-engine", stub)
	session, err := New(context.Background(), Config{
		Engine: "stream-engine", URL: "wss://example", Token: "token",
	})
	if err != nil {
		t.Fatalf("New() error = %v", err)
	}
	conn, err := session.Dial(context.Background())
	if err != nil {
		t.Fatalf("Dial() error = %v", err)
	}
	if _, ok := conn.(net.Conn); ok {
		t.Fatal("raw engine stream must not claim net.Conn deadline semantics")
	}
	if n, writeErr := conn.Write([]byte("hello")); writeErr != nil || n != 5 {
		t.Fatalf("Write() = (%d, %v)", n, writeErr)
	}
	if err := conn.Close(); err != nil {
		t.Fatalf("Close() error = %v", err)
	}
	close(stub.watchBlock)
}

var _ engine.Session = (*stubSession)(nil)
