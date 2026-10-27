package client

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/xtaci/smux"

	cryptopkg "github.com/openlibrecommunity/olcrtc/internal/crypto"
	"github.com/openlibrecommunity/olcrtc/internal/handshake"
	"github.com/openlibrecommunity/olcrtc/internal/muxconn"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
)

// A relay may lose the client's first CLIENT_HELLO: the Jitsi videobridge
// drops a message addressed to an endpoint whose channel is not open yet,
// and the server's channel can open seconds after ours (olcbox#22). These
// tests drive openControlStreamTimeout against a fake server on the other
// end of an smux pair and check that a lost hello is sent again on a fresh
// stream, that an answered one is not, and that a rejection ends it at once.

func newSmuxPair(t *testing.T) (*smux.Session, *smux.Session) {
	t.Helper()
	a, b := net.Pipe()
	serverSess, err := smux.Server(a, testSmuxCfg())
	if err != nil {
		t.Fatalf("smux.Server() error = %v", err)
	}
	clientSess, err := smux.Client(b, testSmuxCfg())
	if err != nil {
		t.Fatalf("smux.Client() error = %v", err)
	}
	t.Cleanup(func() {
		_ = clientSess.Close()
		_ = serverSess.Close()
		_ = a.Close()
		_ = b.Close()
	})
	return serverSess, clientSess
}

func setHelloResend(t *testing.T, interval time.Duration) {
	t.Helper()
	prev := helloResendInterval
	helloResendInterval = interval
	t.Cleanup(func() { helloResendInterval = prev })
}

// acceptStream returns nil when the session is gone; the fake servers run on
// goroutines that may outlive the test, so they report through channels
// rather than t.
func acceptStream(sess *smux.Session) *smux.Stream {
	stream, err := sess.AcceptStream()
	if err != nil {
		return nil
	}
	return stream
}

func okAuth(string, map[string]any) (string, error) { return "sess-1", nil }

func TestOpenControlStreamResendsHelloWhenFirstIsUnanswered(t *testing.T) {
	serverSess, clientSess := newSmuxPair(t)
	setHelloResend(t, 50*time.Millisecond)

	served := make(chan error, 1)
	go func() {
		// The first hello reaches a server whose relay channel was not open:
		// it is read and never answered, as if it had never arrived.
		first := acceptStream(serverSess)
		if first == nil {
			served <- errors.New("no first stream")
			return
		}
		go func() { _, _ = io.Copy(io.Discard, first) }()
		second := acceptStream(serverSess)
		if second == nil {
			served <- errors.New("no second stream")
			return
		}
		_, _, err := handshake.Server(second, okAuth, "peer-1")
		served <- err
	}()

	start := time.Now()
	stream, sessionID, peerID, err := openControlStreamTimeout(
		context.Background(), clientSess, "dev", nil, 2*time.Second,
	)
	if err != nil {
		t.Fatalf("openControlStreamTimeout() error = %v", err)
	}
	defer func() { _ = stream.Close() }()
	if sessionID != "sess-1" || peerID != "peer-1" {
		t.Fatalf("openControlStreamTimeout() = (%q, %q), want (sess-1, peer-1)", sessionID, peerID)
	}
	if elapsed := time.Since(start); elapsed > time.Second {
		t.Fatalf("handshake took %v, the resend did not happen at the interval", elapsed)
	}
	if err := <-served; err != nil {
		t.Fatalf("server handshake error = %v", err)
	}
}

func TestOpenControlStreamKeepsFirstHelloWhileHedging(t *testing.T) {
	serverSess, clientSess := newSmuxPair(t)
	setHelloResend(t, 50*time.Millisecond)

	type accepted struct {
		first, second *smux.Stream
	}
	got := make(chan accepted, 1)
	go func() {
		// A slow server: it answers the first hello, but only after the
		// client has already sent a second one.
		first := acceptStream(serverSess)
		second := acceptStream(serverSess)
		time.Sleep(100 * time.Millisecond)
		if first != nil {
			_, _, _ = handshake.Server(first, okAuth, "peer-1")
		}
		got <- accepted{first, second}
	}()

	stream, _, _, err := openControlStreamTimeout(context.Background(), clientSess, "dev", nil, 2*time.Second)
	if err != nil {
		t.Fatalf("openControlStreamTimeout() error = %v", err)
	}
	defer func() { _ = stream.Close() }()
	streams := <-got
	if streams.first == nil || streams.second == nil {
		t.Fatal("server did not see both hello streams")
	}
	// The stream the client keeps is the one the welcome came back on.
	if _, err := stream.Write([]byte{0x2a}); err != nil {
		t.Fatalf("Write() on the kept stream error = %v", err)
	}
	buf := make([]byte, 1)
	_ = streams.first.SetReadDeadline(time.Now().Add(time.Second))
	if _, err := io.ReadFull(streams.first, buf); err != nil || buf[0] != 0x2a {
		t.Fatalf("kept stream is not the answered one: read = %v, %v", buf, err)
	}
	// The hedge stream, still unanswered, is closed once the first wins: what
	// the server reads there is the second hello and then the end of stream.
	_ = streams.second.SetReadDeadline(time.Now().Add(time.Second))
	if _, err := io.Copy(io.Discard, streams.second); err != nil && !errors.Is(err, io.EOF) {
		t.Fatalf("the losing hello stream did not close: %v", err)
	}
}

func TestOpenControlStreamRejectEndsItAtOnce(t *testing.T) {
	serverSess, clientSess := newSmuxPair(t)
	setHelloResend(t, time.Second)

	go func() {
		stream := acceptStream(serverSess)
		if stream == nil {
			return
		}
		_, _, _ = handshake.Server(stream, func(string, map[string]any) (string, error) {
			return "", errors.New("no seats")
		}, "peer-1")
	}()

	start := time.Now()
	stream, sessionID, peerID, err := openControlStreamTimeout(context.Background(), clientSess, "dev", nil, 5*time.Second)
	if stream != nil || sessionID != "" || peerID != "" {
		t.Fatalf("a rejected handshake returned (%v, %q, %q)", stream, sessionID, peerID)
	}
	if !errors.Is(err, handshake.ErrRejected) {
		t.Fatalf("openControlStreamTimeout() error = %v, want %v", err, handshake.ErrRejected)
	}
	if elapsed := time.Since(start); elapsed > 500*time.Millisecond {
		t.Fatalf("a rejection took %v to come back; it must not wait for the resend interval", elapsed)
	}
}

func TestOpenControlStreamTimesOutWhenNobodyAnswers(t *testing.T) {
	serverSess, clientSess := newSmuxPair(t)
	setHelloResend(t, 100*time.Millisecond)

	streams := make(chan *smux.Stream, 16)
	go func() {
		for {
			stream, err := serverSess.AcceptStream()
			if err != nil {
				return
			}
			streams <- stream
			go func() { _, _ = io.Copy(io.Discard, stream) }()
		}
	}()

	start := time.Now()
	stream, sessionID, peerID, err := openControlStreamTimeout(context.Background(), clientSess, "dev", nil, 350*time.Millisecond)
	if err == nil || stream != nil || sessionID != "" || peerID != "" {
		t.Fatalf("openControlStreamTimeout() = (%v, %q, %q, %v) with nobody answering", stream, sessionID, peerID, err)
	}
	var netErr net.Error
	if !errors.As(err, &netErr) || !netErr.Timeout() {
		t.Fatalf("openControlStreamTimeout() error = %v, want a timeout", err)
	}
	if elapsed := time.Since(start); elapsed < 300*time.Millisecond || elapsed > 1500*time.Millisecond {
		t.Fatalf("gave up after %v, want about the timeout", elapsed)
	}
	// Several hellos went out, and every stream is closed on the way out.
	if n := len(streams); n < 3 {
		t.Fatalf("server saw %d hello streams, want at least 3 within the timeout", n)
	}
	for len(streams) > 0 {
		stream := <-streams
		_ = stream.SetReadDeadline(time.Now().Add(time.Second))
		if _, err := stream.Read(make([]byte, 1)); err == nil {
			t.Fatal("a hello stream was left open after the timeout")
		}
	}
}

// ai-generated: lateConn, newLatePair, serveLatePeer and the three tests
// below (olcrtc#10: the reply window counted from before this side's own
// path was open).

// lateConn is the client's end of the path: a write waits until the path
// opens, the way muxconn holds smux's sender until the transport can send,
// which on Jitsi is when this side's bridge is open after ICE on the relay.
type lateConn struct {
	net.Conn
	open   <-chan struct{}
	closed chan struct{}
	once   sync.Once
}

func (c *lateConn) Write(p []byte) (int, error) {
	select {
	case <-c.open:
		return c.Conn.Write(p)
	case <-c.closed:
		return 0, net.ErrClosed
	}
}

func (c *lateConn) Close() error {
	c.once.Do(func() { close(c.closed) })
	return c.Conn.Close()
}

// newLatePair is newSmuxPair with the client's writes held until open closes.
func newLatePair(t *testing.T, open <-chan struct{}) (*smux.Session, *smux.Session) {
	t.Helper()
	a, b := net.Pipe()
	serverSess, err := smux.Server(a, testSmuxCfg())
	if err != nil {
		t.Fatalf("smux.Server() error = %v", err)
	}
	clientSess, err := smux.Client(&lateConn{Conn: b, open: open, closed: make(chan struct{})}, testSmuxCfg())
	if err != nil {
		t.Fatalf("smux.Client() error = %v", err)
	}
	t.Cleanup(func() {
		_ = clientSess.Close()
		_ = serverSess.Close()
		_ = a.Close()
		_ = b.Close()
	})
	return serverSess, clientSess
}

// serveLatePeer is a server whose own side of the path opens at up: a hello
// that reaches it earlier is lost on the way (the videobridge drops a message
// for an endpoint whose channel is not open yet), so it is read and never
// answered; the first one after up is.
func serveLatePeer(sess *smux.Session, up time.Time, served chan<- error) {
	for {
		stream := acceptStream(sess)
		if stream == nil {
			served <- errors.New("no hello after the server came up")
			return
		}
		if time.Now().Before(up) {
			go func() { _, _ = io.Copy(io.Discard, stream) }()
			continue
		}
		_, _, err := handshake.Server(stream, okAuth, "peer-1")
		served <- err
		return
	}
}

// S6's 8 s leg in miniature: this side's path opens at 1.5 s, the server's
// at 3.3 s, past a window counted from the start. The time this side spends
// on its own path is not the peer's to answer in, so the window runs from
// when the first hello could leave, and hellos keep going until it ends: two
// of them reach the server before it does.
func TestOpenControlStreamWindowStartsWhenItsPathOpens(t *testing.T) {
	const (
		timeout = 3 * time.Second
		ownPath = 1500 * time.Millisecond
		peerUp  = 3300 * time.Millisecond
	)
	setHelloResend(t, 500*time.Millisecond)
	open := make(chan struct{})
	serverSess, clientSess := newLatePair(t, open)
	start := time.Now()
	opener := time.AfterFunc(ownPath, func() { close(open) })
	t.Cleanup(func() { opener.Stop() })
	served := make(chan error, 1)
	go serveLatePeer(serverSess, start.Add(peerUp), served)

	stream, sessionID, peerID, err := openControlStreamTimeout(
		context.Background(), clientSess, "dev", nil, timeout,
	)
	if err != nil {
		t.Fatalf("openControlStreamTimeout() error = %v after %v; the peer came up %v after the path opened",
			err, time.Since(start).Round(time.Millisecond), peerUp-ownPath)
	}
	defer func() { _ = stream.Close() }()
	if sessionID != "sess-1" || peerID != "peer-1" {
		t.Fatalf("openControlStreamTimeout() = (%q, %q), want (sess-1, peer-1)", sessionID, peerID)
	}
	if err := <-served; err != nil {
		t.Fatalf("server handshake error = %v", err)
	}
}

// A path that never opens (Jitsi starts no conference in an empty room, so
// this side's bridge never comes up) fails at the timeout, as a timeout.
func TestOpenControlStreamGivesUpOnAPathThatNeverOpens(t *testing.T) {
	_, clientSess := newLatePair(t, make(chan struct{}))
	start := time.Now()
	done := make(chan error, 1)
	go func() {
		_, _, _, err := openControlStreamTimeout(context.Background(), clientSess, "dev", nil, 300*time.Millisecond)
		done <- err
	}()
	select {
	case err := <-done:
		var netErr net.Error
		if !errors.As(err, &netErr) || !netErr.Timeout() {
			t.Fatalf("openControlStreamTimeout() error = %v, want a timeout", err)
		}
		if elapsed := time.Since(start); elapsed < 250*time.Millisecond {
			t.Fatalf("gave up after %v, before the timeout", elapsed)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("still waiting 3s for a path that never opened, with a 300ms timeout")
	}
}

func TestOpenControlStreamStopsOnContextCancelBeforeItsPathOpens(t *testing.T) {
	_, clientSess := newLatePair(t, make(chan struct{}))
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() {
		_, _, _, err := openControlStreamTimeout(ctx, clientSess, "dev", nil, time.Hour)
		done <- err
	}()
	time.Sleep(20 * time.Millisecond)
	cancel()
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("openControlStreamTimeout() error = %v, want context.Canceled", err)
		}
	case <-time.After(time.Second):
		t.Fatal("the cancel did not end a wait for a path that never opened")
	}
}

// ai-generated: pathLink, newBatchingPair and the test below (olcrtc#11's
// write batching must still hold the first SYN until this side's path is
// open, or the window of olcrtc#10 counts from the join again).

// pathLink is one side of a transport that sends only once its path is open,
// and asks muxconn to batch small frames the way the datachannel transport
// does. What it sends is pushed into the other side's conn.
type pathLink struct {
	transport.Transport
	open <-chan struct{}
	peer atomic.Pointer[muxconn.Conn]
}

func (l *pathLink) CanSend() bool {
	select {
	case <-l.open:
		return true
	default:
		return false
	}
}

func (l *pathLink) Send(data []byte) error {
	if peer := l.peer.Load(); peer != nil {
		peer.Push(bytes.Clone(data))
	}
	return nil
}

func (*pathLink) Features() transport.Features {
	return transport.Features{MaxPayloadSize: 12 * 1024, WriteInterval: 5 * time.Millisecond}
}

// newBatchingPair is newLatePair through muxconn: the client's link can send
// once open closes, the server's at once, and both conns batch.
func newBatchingPair(t *testing.T, open <-chan struct{}) (*smux.Session, *smux.Session) {
	t.Helper()
	psk := []byte("01234567890123456789012345678901")
	clientKeys, err := cryptopkg.NewKeySet(psk, cryptopkg.Client)
	if err != nil {
		t.Fatalf("NewKeySet(client) error = %v", err)
	}
	serverKeys, err := cryptopkg.NewKeySet(psk, cryptopkg.Server)
	if err != nil {
		t.Fatalf("NewKeySet(server) error = %v", err)
	}
	always := make(chan struct{})
	close(always)
	clientLink, serverLink := &pathLink{open: open}, &pathLink{open: always}
	clientConn, serverConn := muxconn.New(clientLink, clientKeys), muxconn.New(serverLink, serverKeys)
	clientLink.peer.Store(serverConn)
	serverLink.peer.Store(clientConn)
	serverSess, err := smux.Server(serverConn, testSmuxCfg())
	if err != nil {
		t.Fatalf("smux.Server() error = %v", err)
	}
	clientSess, err := smux.Client(clientConn, testSmuxCfg())
	if err != nil {
		t.Fatalf("smux.Client() error = %v", err)
	}
	t.Cleanup(func() {
		_ = clientSess.Close()
		_ = serverSess.Close()
		_ = clientConn.Close()
		_ = serverConn.Close()
	})
	return serverSess, clientSess
}

// The S6 leg of TestOpenControlStreamWindowStartsWhenItsPathOpens with the
// conns the client really runs: batching may defer a frame by an interval,
// but the SYN's Write must still wait for the path, or OpenStream returns at
// once and the window runs out before the late server is up.
func TestOpenControlStreamWindowStartsWhenABatchingPathOpens(t *testing.T) {
	const (
		timeout = 3 * time.Second
		ownPath = 1500 * time.Millisecond
		peerUp  = 3300 * time.Millisecond
	)
	setHelloResend(t, 500*time.Millisecond)
	open := make(chan struct{})
	serverSess, clientSess := newBatchingPair(t, open)
	start := time.Now()
	opener := time.AfterFunc(ownPath, func() { close(open) })
	t.Cleanup(func() { opener.Stop() })
	served := make(chan error, 1)
	go serveLatePeer(serverSess, start.Add(peerUp), served)

	stream, sessionID, peerID, err := openControlStreamTimeout(
		context.Background(), clientSess, "dev", nil, timeout,
	)
	if err != nil {
		t.Fatalf("openControlStreamTimeout() error = %v after %v; the peer came up %v after the path opened",
			err, time.Since(start).Round(time.Millisecond), peerUp-ownPath)
	}
	defer func() { _ = stream.Close() }()
	if sessionID != "sess-1" || peerID != "peer-1" {
		t.Fatalf("openControlStreamTimeout() = (%q, %q), want (sess-1, peer-1)", sessionID, peerID)
	}
	if err := <-served; err != nil {
		t.Fatalf("server handshake error = %v", err)
	}
}
