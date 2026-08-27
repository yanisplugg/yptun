package client

import (
	"context"
	"errors"
	"io"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
	enginebuiltin "github.com/openlibrecommunity/olcrtc/internal/engine/builtin"
	"github.com/openlibrecommunity/olcrtc/internal/server"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
	"github.com/openlibrecommunity/olcrtc/internal/transport/datachannel"
)

const listenerTestKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

type listenerTestRoom struct {
	mu       sync.Mutex
	sessions map[*listenerTestSession]struct{}
}

type listenerTestSession struct {
	room   *listenerTestRoom
	onData func([]byte)
	mu     sync.Mutex
	ready  bool
	closed bool
}

func (r *listenerTestRoom) newSession(onData func([]byte)) *listenerTestSession {
	session := &listenerTestSession{room: r, onData: onData}
	r.mu.Lock()
	r.sessions[session] = struct{}{}
	r.mu.Unlock()
	return session
}

func (r *listenerTestRoom) waitConnected(t *testing.T, want int) {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		if r.connected() >= want {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("connected sessions = %d, want %d", r.connected(), want)
}

func (r *listenerTestRoom) connected() int {
	r.mu.Lock()
	sessions := make([]*listenerTestSession, 0, len(r.sessions))
	for session := range r.sessions {
		sessions = append(sessions, session)
	}
	r.mu.Unlock()
	count := 0
	for _, session := range sessions {
		if session.CanSend() {
			count++
		}
	}
	return count
}

func (s *listenerTestSession) Connect(context.Context) error {
	s.mu.Lock()
	s.ready = true
	s.mu.Unlock()
	return nil
}

func (s *listenerTestSession) Send(data []byte) error {
	if !s.CanSend() {
		return io.ErrClosedPipe
	}
	s.room.mu.Lock()
	peers := make([]*listenerTestSession, 0, len(s.room.sessions))
	for peer := range s.room.sessions {
		if peer != s {
			peers = append(peers, peer)
		}
	}
	s.room.mu.Unlock()
	for _, peer := range peers {
		peer.deliver(data)
	}
	return nil
}

func (s *listenerTestSession) deliver(data []byte) {
	s.mu.Lock()
	ready := s.ready && !s.closed
	onData := s.onData
	s.mu.Unlock()
	if ready && onData != nil {
		onData(append([]byte(nil), data...))
	}
}

func (s *listenerTestSession) Close() error {
	s.mu.Lock()
	s.closed = true
	s.ready = false
	s.mu.Unlock()
	return nil
}

func (*listenerTestSession) SetReconnectCallback(func())         {}
func (*listenerTestSession) SetShouldReconnect(func() bool)      {}
func (*listenerTestSession) SetEndedCallback(func(string))       {}
func (*listenerTestSession) WatchConnection(ctx context.Context) { <-ctx.Done() }
func (s *listenerTestSession) CanSend() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.ready && !s.closed
}
func (s *listenerTestSession) SubscriberCanSend() bool { return s.CanSend() }
func (*listenerTestSession) GetBufferedAmount() uint64 { return 0 }
func (*listenerTestSession) Reconnect(string)          {}

func TestRunWithAddressReportsDialableEphemeralListener(t *testing.T) {
	transport.Register("datachannel", datachannel.New)
	room := &listenerTestRoom{sessions: make(map[*listenerTestSession]struct{})}
	providerName := "listener-test-" + t.Name()
	enginebuiltin.Register(providerName, func(_ context.Context, cfg enginebuiltin.Config) (engine.Session, error) {
		return room.newSession(cfg.OnData), nil
	})

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	serverErr := make(chan error, 1)
	go func() {
		serverErr <- server.Run(ctx, server.Config{
			Transport: "datachannel", Provider: providerName, RoomURL: "room", KeyHex: listenerTestKey,
		})
	}()
	room.waitConnected(t, 1)

	address := make(chan string, 1)
	clientErr := make(chan error, 1)
	go func() {
		clientErr <- RunWithAddress(ctx, Config{
			Transport: "datachannel", Provider: providerName, RoomURL: "room", KeyHex: listenerTestKey,
			LocalAddr: "127.0.0.1:0", DeviceID: "listener-test-client",
		}, func(actualAddr string) { address <- actualAddr })
	}()

	actualAddr := waitListenerAddress(t, address, clientErr, serverErr)
	_, port, err := net.SplitHostPort(actualAddr)
	if err != nil || port == "0" {
		t.Fatalf("callback address = %q, split error = %v", actualAddr, err)
	}
	conn, err := net.DialTimeout("tcp4", actualAddr, time.Second)
	if err != nil {
		t.Fatalf("dial callback address %q: %v", actualAddr, err)
	}
	_ = conn.Close()

	cancel()
	waitListenerRun(t, "client", clientErr)
	waitListenerRun(t, "server", serverErr)
}

func waitListenerAddress(t *testing.T, address <-chan string, clientErr, serverErr <-chan error) string {
	t.Helper()
	select {
	case actualAddr := <-address:
		return actualAddr
	case err := <-clientErr:
		t.Fatalf("client stopped before callback: %v", err)
	case err := <-serverErr:
		t.Fatalf("server stopped before callback: %v", err)
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for listener callback")
	}
	return ""
}

func waitListenerRun(t *testing.T, name string, done <-chan error) {
	t.Helper()
	select {
	case err := <-done:
		if err != nil && !errors.Is(err, context.Canceled) {
			t.Fatalf("%s run error = %v", name, err)
		}
	case <-time.After(2 * time.Second):
		t.Fatalf("timed out waiting for %s shutdown", name)
	}
}

var _ engine.Session = (*listenerTestSession)(nil)

// TestSocks5RejectsEmptyDomain locks in that a zero-length domain is rejected
// at the listener. It used to be accepted and forwarded as an empty host,
// which the exit node then turned into a dial to its own loopback.
func TestSocks5RejectsEmptyDomain(t *testing.T) {
	local, remote := net.Pipe()
	defer func() { _ = local.Close() }()
	defer func() { _ = remote.Close() }()

	c := &Client{}
	result := make(chan error, 1)
	go func() {
		_, _, err := c.socks5Request(remote)
		result <- err
	}()

	// VER, CMD=CONNECT, RSV, ATYP=domain, len=0. net.Pipe is unbuffered, so
	// only the bytes the parser actually consumes may be written here.
	if _, err := local.Write([]byte{socksVersion, 1, 0, socksAddrDomain, 0}); err != nil {
		t.Fatalf("write request: %v", err)
	}

	select {
	case err := <-result:
		if !errors.Is(err, ErrEmptySOCKSDomain) {
			t.Fatalf("socks5Request() error = %v, want %v", err, ErrEmptySOCKSDomain)
		}
	case <-time.After(time.Second):
		t.Fatal("socks5Request() did not return")
	}
}
