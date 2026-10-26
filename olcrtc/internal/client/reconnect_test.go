package client

import (
	"context"
	"errors"
	"fmt"
	"io"
	"math"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/xtaci/smux"

	"github.com/openlibrecommunity/olcrtc/internal/control"
	cryptopkg "github.com/openlibrecommunity/olcrtc/internal/crypto"
	"github.com/openlibrecommunity/olcrtc/internal/handshake"
	"github.com/openlibrecommunity/olcrtc/internal/muxconn"
	"github.com/openlibrecommunity/olcrtc/internal/runtime"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
)

// ai-generated: the whole file (olcrtc#19).
//
// The client's reconnect paths against a fake provider and a fake server:
// the provider callback, the fallback that stands in when it is late, and
// what happens when the handshakes after either go unanswered. The rig's
// link is a transport with a control plane; every control conn the client
// opens gets a fresh smux session on the server's side, the way a server
// builds one per epoch, and the server answers hellos only while told to.

var errRigGateClosed = errors.New("rig: provider not sendable")

// errRigRefused is what a refusing rig server answers a hello with.
var errRigRefused = errors.New("rig: this client is not welcome")

// rigKey is the tunnel key both sides of the rig use.
var rigKey = []byte("01234567890123456789012345678901")

// rigLink is the client's transport. gate stands for the provider's CanSend.
type rigLink struct {
	server *rigServer
	gate   atomic.Bool
	// peerSeen is transport.PeerObserver: whether anything in the room has
	// sent a frame. newRig starts it true - somebody is in the room - so a
	// test has to empty the room to get one.
	//
	// ai-generated: the field and PeerSeen (the port of olcrtc#39).
	peerSeen atomic.Bool

	mu          sync.Mutex
	onReconnect func()
	onControl   func([]byte)
	// onRequest, when set, runs inside Reconnect: an engine that calls back
	// before the request returns.
	onRequest func(reason string)
	requests  chan string
	// peerReady is what WaitForPeer waits for, so a test can park a
	// handshake between its welcome and the session being installed. It is
	// closed in newRig; a test that wants that pause puts an open one here.
	// ai-generated (review of #20).
	peerReady chan struct{}
}

// WaitForPeer implements transport.PeerReadyTransport.
func (l *rigLink) WaitForPeer(context.Context) error {
	l.mu.Lock()
	ready := l.peerReady
	l.mu.Unlock()
	<-ready
	return nil
}

// parkPeer makes the next handshake wait in WaitForPeer and returns the
// func that lets it through.
func (l *rigLink) parkPeer() func() {
	ready := make(chan struct{})
	l.mu.Lock()
	l.peerReady = ready
	l.mu.Unlock()
	return func() { close(ready) }
}

func (l *rigLink) Connect(context.Context) error { return nil }
func (l *rigLink) Send([]byte) error             { return nil }
func (l *rigLink) Close() error                  { return nil }
func (l *rigLink) SetReconnectCallback(cb func()) {
	l.mu.Lock()
	l.onReconnect = cb
	l.mu.Unlock()
}
func (l *rigLink) SetShouldReconnect(func() bool)      {}
func (l *rigLink) SetEndedCallback(func(string))       {}
func (l *rigLink) WatchConnection(ctx context.Context) { <-ctx.Done() }
func (l *rigLink) CanSend() bool                       { return l.gate.Load() }
func (l *rigLink) Features() transport.Features        { return transport.Features{} }
func (l *rigLink) ResetPeer()                          {}

// PeerSeen implements transport.PeerObserver.
func (l *rigLink) PeerSeen() bool { return l.peerSeen.Load() }

func (l *rigLink) ControlCanSend() bool { return l.gate.Load() }

func (l *rigLink) Reconnect(reason string) {
	l.mu.Lock()
	onRequest := l.onRequest
	l.mu.Unlock()
	select {
	case l.requests <- reason:
	default:
	}
	if onRequest != nil {
		onRequest(reason)
	}
}

func (l *rigLink) ControlSend(data []byte) error {
	if !l.gate.Load() {
		return errRigGateClosed
	}
	l.server.push(data)
	return nil
}

// SetControlOnData is called for every control conn the client opens, so the
// server starts a session for it.
func (l *rigLink) SetControlOnData(cb func([]byte)) {
	l.mu.Lock()
	l.onControl = cb
	l.mu.Unlock()
	l.server.reset(l.deliver)
}

func (l *rigLink) deliver(data []byte) {
	l.mu.Lock()
	cb := l.onControl
	l.mu.Unlock()
	if cb != nil {
		cb(data)
	}
}

// callback is the provider calling back after it rebuilt its connection.
func (l *rigLink) callback() {
	l.mu.Lock()
	cb := l.onReconnect
	l.mu.Unlock()
	cb()
}

// rigServer counts the client control conns it has seen in sessions, one per
// handshake attempt, and answers the hellos of those from answerFrom on: a
// server that is gone answers nothing, and one that is back answers the
// sessions that reach it from then on.
type rigServer struct {
	keys       *cryptopkg.KeySet
	answerFrom atomic.Int32
	sessions   atomic.Int32
	welcomed   atomic.Int32
	// refuse answers every hello with a rejection, the way a server that
	// cannot authenticate this client does. ai-generated (review of #20).
	refuse  atomic.Bool
	refused atomic.Int32

	// writeMu is held while the server writes to a session and while one is
	// replaced, so a close notice on its way out is not cut short by the
	// client opening the conn that replaces it. The client can be through
	// its whole recovery before smux hands the write's result back, and the
	// test then read a closed pipe as a failure to send.
	writeMu sync.Mutex

	mu      sync.Mutex
	conn    *muxconn.Conn
	sess    *smux.Session
	streams []*smux.Stream
}

func (s *rigServer) reset(toClient func([]byte)) {
	conn := muxconn.NewControl(&rigServerLink{toClient: toClient}, s.keys)
	sess, err := smux.Server(conn, runtime.ControlSmuxConfig(0))
	if err != nil {
		panic(err)
	}
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	s.mu.Lock()
	oldConn, oldSess := s.conn, s.sess
	s.conn, s.sess = conn, sess
	s.mu.Unlock()
	if oldSess != nil {
		_ = oldSess.Close()
		_ = oldConn.Close()
	}
	go s.serve(sess, s.sessions.Add(1))
}

func (s *rigServer) stopAnswering() { s.answerFrom.Store(math.MaxInt32) }

func (s *rigServer) answerNew() { s.answerFrom.Store(s.sessions.Load() + 1) }

func (s *rigServer) push(data []byte) {
	s.mu.Lock()
	conn := s.conn
	s.mu.Unlock()
	if conn != nil {
		conn.Push(data)
	}
}

func (s *rigServer) serve(sess *smux.Session, index int32) {
	for {
		stream, err := sess.AcceptStream()
		if err != nil {
			return
		}
		if index < s.answerFrom.Load() {
			go func() { _, _ = io.Copy(io.Discard, stream) }()
			continue
		}
		go s.welcome(stream)
	}
}

func (s *rigServer) welcome(stream *smux.Stream) {
	_ = stream.SetDeadline(time.Now().Add(5 * time.Second))
	auth := func(string, map[string]any) (string, error) {
		if s.refuse.Load() {
			s.refused.Add(1)
			return "", errRigRefused
		}
		return fmt.Sprintf("rig-%d", s.welcomed.Add(1)), nil
	}
	if _, _, err := handshake.Server(stream, auth, ""); err != nil {
		_ = stream.Close()
		return
	}
	_ = stream.SetDeadline(time.Time{})
	s.mu.Lock()
	s.streams = append(s.streams, stream)
	s.mu.Unlock()
}

// closeSession tells the client its session is over, as a server does when
// its own provider has rebuilt. The client can be done with its handshake a
// moment before the server's side of it has returned, so a session not yet
// recorded is waited for.
func (s *rigServer) closeSession() error {
	deadline := time.Now().Add(2 * time.Second)
	for {
		s.writeMu.Lock()
		s.mu.Lock()
		if n := len(s.streams); n > 0 {
			stream := s.streams[n-1]
			s.mu.Unlock()
			err := control.SendClose(stream)
			s.writeMu.Unlock()
			return err
		}
		s.mu.Unlock()
		s.writeMu.Unlock()
		if time.Now().After(deadline) {
			return errors.New("rig: no session to close")
		}
		time.Sleep(5 * time.Millisecond)
	}
}

func (s *rigServer) close() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.sess != nil {
		_ = s.sess.Close()
		_ = s.conn.Close()
	}
}

// rigServerLink is the server's side of the control plane.
type rigServerLink struct {
	toClient func([]byte)
}

func (l *rigServerLink) Connect(context.Context) error   { return nil }
func (l *rigServerLink) Send([]byte) error               { return nil }
func (l *rigServerLink) Close() error                    { return nil }
func (l *rigServerLink) SetReconnectCallback(func())     {}
func (l *rigServerLink) SetShouldReconnect(func() bool)  {}
func (l *rigServerLink) SetEndedCallback(func(string))   {}
func (l *rigServerLink) WatchConnection(context.Context) {}
func (l *rigServerLink) CanSend() bool                   { return true }
func (l *rigServerLink) Features() transport.Features    { return transport.Features{} }
func (l *rigServerLink) Reconnect(string)                {}
func (l *rigServerLink) ControlSend(data []byte) error   { l.toClient(data); return nil }
func (l *rigServerLink) SetControlOnData(func([]byte))   {}
func (l *rigServerLink) ControlCanSend() bool            { return true }

type rig struct {
	t      *testing.T
	ctx    context.Context
	cancel context.CancelFunc
	link   *rigLink
	server *rigServer
	client *Client
}

// newRig brings a client up over the rig through bringUpLink, the way
// RunWithAddress does, with tune applied to the client first.
func newRig(t *testing.T, tune func(*Client)) *rig {
	t.Helper()
	serverKeys, err := cryptopkg.NewKeySet(rigKey, cryptopkg.Server)
	if err != nil {
		t.Fatalf("NewKeySet(server) error = %v", err)
	}
	clientKeys, err := cryptopkg.NewKeySet(rigKey, cryptopkg.Client)
	if err != nil {
		t.Fatalf("NewKeySet(client) error = %v", err)
	}
	server := &rigServer{keys: serverKeys}
	ready := make(chan struct{})
	close(ready)
	link := &rigLink{server: server, requests: make(chan string, 16), peerReady: ready}
	link.gate.Store(true)
	link.peerSeen.Store(true)
	name := "reconnect-rig-" + t.Name()
	transport.Register(name, func(context.Context, transport.Config) (transport.Transport, error) {
		return link, nil
	})
	c := &Client{
		keys: clientKeys, deviceID: "rig", health: runtime.NewHealthTracker(nil),
		sessionReady: make(chan struct{}), shutdownGrace: time.Second,
	}
	if tune != nil {
		tune(c)
	}
	ctx, cancel := context.WithCancel(context.Background())
	if err := c.bringUpLink(ctx, Config{Transport: name}, cancel); err != nil {
		cancel()
		t.Fatalf("bringUpLink() error = %v", err)
	}
	t.Cleanup(func() {
		cancel()
		c.shutdown()
		server.close()
	})
	return &rig{t: t, ctx: ctx, cancel: cancel, link: link, server: server, client: c}
}

func (r *rig) sessionID() string {
	r.client.sessMu.RLock()
	defer r.client.sessMu.RUnlock()
	return r.client.sessionID
}

// waitNewSession waits for an established session other than old.
func (r *rig) waitNewSession(old string, within time.Duration) {
	r.t.Helper()
	deadline := time.Now().Add(within)
	for time.Now().Before(deadline) {
		if id := r.sessionID(); id != "" && id != old && r.client.sessionEstablished() {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	r.t.Fatalf("no new session within %v", within)
}

// waitRequest waits for the client to ask the provider for a new connection
// with reason.
func (r *rig) waitRequest(reason string, within time.Duration) {
	r.t.Helper()
	timer := time.NewTimer(within)
	defer timer.Stop()
	for {
		select {
		case got := <-r.link.requests:
			if got == reason {
				return
			}
		case <-timer.C:
			r.t.Fatalf("the client did not ask the provider to reconnect (%s) within %v", reason, within)
		}
	}
}

// loseSession ends the session from the server's side and waits for the
// client to give it up and ask the provider for a new connection. The notice
// the server sends is the graceful close of a server whose own provider has
// rebuilt: a session that is over, not a room that is, so the client gives
// the session up and stays in the room.
//
// ai-generated: the reason (the port of olcrtc#39).
func (r *rig) loseSession() {
	r.t.Helper()
	if err := r.server.closeSession(); err != nil {
		r.t.Fatalf("closeSession() error = %v", err)
	}
	r.waitRequest(reconnectPeerClose, 2*time.Second)
}

// An engine may hold its send gate closed until its reconnect callback
// returns: LiveKit did until olcrtc#19. The callback must still return at
// once, handing the handshake to a goroutine that sends once the gate opens,
// rather than retrying on the provider's reconnect loop against a gate that
// cannot open while it does.
func TestProviderCallbackReturnsBeforeItsHandshake(t *testing.T) {
	r := newRig(t, func(c *Client) { c.handshakeTimeout = 300 * time.Millisecond })
	first := r.sessionID()

	r.link.gate.Store(false)
	returned := make(chan time.Duration, 1)
	go func() {
		start := time.Now()
		r.link.callback()
		returned <- time.Since(start)
		r.link.gate.Store(true)
	}()
	select {
	case took := <-returned:
		if took > 100*time.Millisecond {
			t.Fatalf("the provider callback took %v to return", took)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("the provider callback did not return while its handshake could not send")
	}
	r.waitNewSession(first, 2*time.Second)
}

// A provider callback that comes while the fallback is handshaking takes
// over at once: the fallback's handshake runs on the connection the rebuild
// has just replaced. It used to wait on reconnectMu until the fallback had
// spent its attempts.
func TestLateProviderCallbackTakesOverFromFallback(t *testing.T) {
	r := newRig(t, func(c *Client) {
		c.livenessFallback = 20 * time.Millisecond
		c.handshakeTimeout = 3 * time.Second
	})
	first := r.sessionID()
	r.server.stopAnswering()
	r.loseSession()

	// The fallback fires and opens its first hello; nobody answers it.
	deadline := time.Now().Add(2 * time.Second)
	for r.server.sessions.Load() < 2 {
		if time.Now().After(deadline) {
			t.Fatal("the fallback did not start a handshake")
		}
		time.Sleep(5 * time.Millisecond)
	}
	r.server.answerNew()
	start := time.Now()
	r.link.callback()
	r.waitNewSession(first, 2*time.Second)
	if took := time.Since(start); took > time.Second {
		t.Fatalf("the provider callback's session took %v; it waited for the fallback", took)
	}
}

// The handshakes after a provider callback run with the fallback disarmed,
// and when they all go unanswered the client asks the provider for a new
// connection. It used to stop there: the fallback that the callback should
// have disarmed ran three more attempts after the callback's five, and then
// nothing was left to retry.
func TestFailedCallbackHandshakesAskProviderAgain(t *testing.T) {
	r := newRig(t, func(c *Client) {
		c.livenessFallback = 50 * time.Millisecond
		c.handshakeTimeout = 100 * time.Millisecond
		c.retryDelay = 10 * time.Millisecond
	})
	first := r.sessionID()
	r.server.stopAnswering()
	r.loseSession()
	before := r.server.sessions.Load()

	r.link.callback()
	r.waitRequest(reconnectHandshake, 3*time.Second)
	if got, want := int(r.server.sessions.Load()-before), maxHandshakeAttempts(reconnectProvider); got != want {
		t.Fatalf("handshake attempts before asking the provider again = %d, want the callback's %d alone", got, want)
	}

	r.server.answerNew()
	r.link.callback()
	r.waitNewSession(first, time.Second)
	// A session clears the streak, so the next outage starts at the plain
	// fallback window again.
	if got := r.client.failedRounds.Load(); got != 0 {
		t.Fatalf("failed rounds after a session came back = %d, want 0", got)
	}
}

// A fallback whose handshakes go unanswered asks the provider for a new
// connection too, instead of leaving the tunnel without a session and
// nothing to retry it.
func TestFailedFallbackHandshakesAskProviderAgain(t *testing.T) {
	r := newRig(t, func(c *Client) {
		c.livenessFallback = 20 * time.Millisecond
		c.handshakeTimeout = 100 * time.Millisecond
		c.retryDelay = 10 * time.Millisecond
	})
	first := r.sessionID()
	r.server.stopAnswering()
	r.loseSession()

	r.waitRequest(reconnectHandshake, 3*time.Second)
	r.server.answerNew()
	r.link.callback()
	r.waitNewSession(first, time.Second)
}

// An engine can call back before the request that asked for the rebuild has
// even returned. The callback owns the recovery from then on: the fallback
// armed for that request is off, and the handshake starts at once rather
// than when the fallback would have fired.
func TestCallbackInsideTheRequestOwnsTheRecovery(t *testing.T) {
	r := newRig(t, func(c *Client) { c.livenessFallback = 5 * time.Second })
	first := r.sessionID()
	r.link.mu.Lock()
	r.link.onRequest = func(reason string) {
		if reason == reconnectPeerClose {
			r.link.callback()
		}
	}
	r.link.mu.Unlock()

	if err := r.server.closeSession(); err != nil {
		t.Fatalf("closeSession() error = %v", err)
	}
	r.waitNewSession(first, time.Second)
}

// A control loop can end after its session has been replaced: its stream is
// closed by the teardown, but it may have seen the end before it was told to
// stop. What it reports is the death of its own session, not of the one a
// provider callback has put in its place since.
func TestLateSessionDeathLeavesTheReplacementAlone(t *testing.T) {
	r := newRig(t, nil)
	first := r.sessionID()
	r.client.sessMu.RLock()
	dead := r.client.controlStrm
	r.client.sessMu.RUnlock()

	r.link.callback()
	r.waitNewSession(first, 2*time.Second)
	second := r.sessionID()

	r.client.onSessionDeath(r.ctx, Config{}, r.cancel, dead, reconnectLiveness)
	if got := r.sessionID(); got != second || !r.client.sessionEstablished() {
		t.Fatalf("session after a late death of the one it replaced = %q (established=%v), want %q",
			got, r.client.sessionEstablished(), second)
	}
	select {
	case reason := <-r.link.requests:
		t.Fatalf("the client asked the provider to reconnect (%s) for a session already replaced", reason)
	default:
	}
}

// ai-generated: the tests below (review of #20).

// A round stops at a refusal instead of spending its five attempts: the peer
// answered, and the answer would be the same.
func TestRefusedHandshakeEndsTheRound(t *testing.T) {
	r := newRig(t, func(c *Client) {
		c.livenessFallback = time.Hour
		c.handshakeTimeout = time.Second
		c.retryDelay = 10 * time.Millisecond
	})
	r.server.refuse.Store(true)
	before := r.server.sessions.Load()

	r.link.callback()
	deadline := time.Now().Add(2 * time.Second)
	for r.server.refused.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if r.server.refused.Load() == 0 {
		t.Fatal("the server never refused a hello")
	}
	time.Sleep(300 * time.Millisecond)

	select {
	case reason := <-r.link.requests:
		t.Fatalf("the client asked the provider to reconnect (%s) although the peer answered", reason)
	default:
	}
	if got := r.server.sessions.Load() - before; got != 1 {
		t.Fatalf("handshakes after the refusal = %d, want the one that was answered", got)
	}
	if got := r.client.failedRounds.Load(); got != 1 {
		t.Fatalf("failed rounds = %d, want 1", got)
	}
}

// Every round that ends without a session doubles the wait before the next
// one, up to the cap: a server that is gone is still retried, but a client
// whose provider is Jitsi no longer asks for a MUC rejoin every minute or
// two for as long as it runs.
func TestRecoveryPauseGrowsWithFailedRounds(t *testing.T) {
	c := &Client{livenessFallback: 30 * time.Second}
	for _, want := range []time.Duration{30 * time.Second, time.Minute, 2 * time.Minute, 4 * time.Minute} {
		if got := c.recoveryPause(); got != want {
			t.Fatalf("pause after %d failed rounds = %v, want %v", c.failedRounds.Load(), got, want)
		}
		c.failedRounds.Add(1)
	}
	c.failedRounds.Store(100)
	if got := c.recoveryPause(); got != maxRecoveryPause {
		t.Fatalf("pause after a long outage = %v, want the cap %v", got, maxRecoveryPause)
	}
	if got := (&Client{}).recoveryPause(); got != defaultLivenessFallback {
		t.Fatalf("pause with no window set = %v, want %v", got, defaultLivenessFallback)
	}
}

// A liveness death that read the generation a provider callback holds must
// leave the recovery to that callback: it drops the session it reported and
// stops there, without asking the provider again or cancelling the handshake
// the callback is about to run.
func TestDeathBehindALiveCallbackKeepsItsRecovery(t *testing.T) {
	r := newRig(t, func(c *Client) { c.livenessFallback = time.Hour })
	// The callback has taken the recovery; its handshake has not started.
	run, gen := r.client.recovery.take(r.ctx)
	defer r.client.recovery.release(gen)

	r.client.onSessionDeath(r.ctx, Config{}, r.cancel, nil, reconnectLiveness)

	select {
	case reason := <-r.link.requests:
		t.Fatalf("the death asked the provider to reconnect (%s) although a callback owned the recovery", reason)
	default:
	}
	select {
	case <-run.Done():
		t.Fatal("the death cancelled the callback's recovery")
	default:
	}
	if got := r.client.recovery.generation(); got != gen {
		t.Fatalf("recovery generation = %d after the death, want the callback's %d", got, gen)
	}
}

// A callback whose recovery was taken over while it waited for reconnectMu
// leaves everything alone: the session it would have torn down belongs to
// whoever took over.
func TestSupersededCallbackLeavesTheSessionAlone(t *testing.T) {
	r := newRig(t, nil)
	first := r.sessionID()
	run, _ := r.client.recovery.take(r.ctx)
	_, newer := r.client.recovery.take(r.ctx)
	defer r.client.recovery.release(newer)

	r.client.handleReconnect(r.ctx, run, Config{}, r.cancel, reconnectProvider)

	if got := r.sessionID(); got != first {
		t.Fatalf("session after a superseded callback = %q, want the untouched %q", got, first)
	}
	select {
	case reason := <-r.link.requests:
		t.Fatalf("a superseded callback asked the provider to reconnect (%s)", reason)
	default:
	}
}

// A handshake that finishes after a newer recovery took over is dropped, not
// installed: it ran over the connection that recovery has just replaced.
func TestHandshakeAfterTakeoverIsNotInstalled(t *testing.T) {
	r := newRig(t, func(c *Client) { c.livenessFallback = time.Hour })
	welcomed := r.server.welcomed.Load()
	letPeerThrough := r.link.parkPeer()

	run, _ := r.client.recovery.take(r.ctx)
	done := make(chan roundResult, 1)
	go func() { done <- r.client.tryReopenSession(r.ctx, run, Config{}, r.cancel, 1) }()

	deadline := time.Now().Add(2 * time.Second)
	for r.server.welcomed.Load() == welcomed && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if r.server.welcomed.Load() == welcomed {
		t.Fatal("the handshake never reached the server")
	}
	_, newer := r.client.recovery.take(r.ctx) // a newer recovery takes over
	defer r.client.recovery.release(newer)
	letPeerThrough()

	select {
	case got := <-done:
		if got != roundStopped {
			t.Fatalf("handshake after a takeover = %v, want roundStopped", got)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("the handshake did not end after the takeover")
	}
	// The session the server welcomed for that handshake must never be
	// installed; the client is free to give the one it had up, since the
	// attempt replaced the conns under it.
	welcomedID := fmt.Sprintf("rig-%d", r.server.welcomed.Load())
	deadline = time.Now().Add(300 * time.Millisecond)
	for time.Now().Before(deadline) {
		if got := r.sessionID(); got == welcomedID {
			t.Fatalf("session id = %q: the taken-over handshake was installed", got)
		}
		time.Sleep(5 * time.Millisecond)
	}
}

// Only the peer deciding about this client - a rejection, or a protocol
// version it will not speak - ends the round: the other bad answers are what
// reordered records and the bytes of a session just torn down read like, and
// those are worth another attempt and, failing that, a new connection.
//
// ai-generated: the whole test (review of #20).
func TestOnlyARefusalEndsTheRound(t *testing.T) {
	live := context.Background()
	for _, tc := range []struct {
		name string
		err  error
		// empty says the classifier found nothing in the room; a refusal
		// outranks it, since the refusal came from someone who is there.
		// ai-generated: the field and the last three cases (port of #39).
		empty bool
		want  roundResult
	}{
		{name: "rejected", err: handshake.ErrRejected, want: roundRefused},
		{name: "protocol version", err: handshake.ErrProtocolVersion, want: roundRefused},
		{name: "frame too large", err: handshake.ErrFrameTooLarge, want: roundSilent},
		{name: "unexpected message", err: handshake.ErrUnexpectedMessage, want: roundSilent},
		{name: "challenge mismatch", err: handshake.ErrChallengeMismatch, want: roundSilent},
		{name: "nobody answered", err: errRigGateClosed, want: roundSilent},
		{name: "empty room", err: errRigGateClosed, empty: true, want: roundEmpty},
		{name: "empty room and a refusal", err: handshake.ErrRejected, empty: true, want: roundRefused},
		{name: "peer there but silent", err: errRigGateClosed, want: roundSilent},
	} {
		t.Run(tc.name, func(t *testing.T) {
			wrapped := fmt.Errorf("handshake client: %w", tc.err)
			classified := wrapped
			if tc.empty {
				classified = fmt.Errorf("%w: %w", ErrNoPeer, wrapped)
			}
			if got := handshakeOutcome(live, wrapped, classified); got != tc.want {
				t.Fatalf("handshakeOutcome(%v) = %v, want %v", tc.err, got, tc.want)
			}
		})
	}
	stopped, cancel := context.WithCancel(context.Background())
	cancel()
	if got := handshakeOutcome(stopped, handshake.ErrRejected, handshake.ErrRejected); got != roundStopped {
		t.Fatalf("handshakeOutcome() after a takeover = %v, want roundStopped", got)
	}
}

// The pause after a failed round comes before the provider is asked again,
// not after: a provider that answers every ask at once - Jitsi rejoins its
// MUC in seconds - would otherwise be asked again the moment the round ended,
// which is how the client came to rejoin the room every minute or two for as
// long as it ran.
//
// ai-generated: the whole test (review of #20).
func TestTheAskAfterAFailedRoundWaits(t *testing.T) {
	r := newRig(t, func(c *Client) {
		c.livenessFallback = 400 * time.Millisecond
		c.handshakeTimeout = 50 * time.Millisecond
		c.retryDelay = 5 * time.Millisecond
	})
	r.server.stopAnswering()

	start := time.Now()
	r.loseSession() // the death asks at once, and arms the fallback
	r.waitRequest(reconnectHandshake, 5*time.Second)
	// 400 ms of fallback, a round of three 50 ms handshakes, then the 400 ms
	// pause of the first failed round. Asked the moment that round ended, it
	// would be about 600 ms.
	if took := time.Since(start); took < 800*time.Millisecond {
		t.Fatalf("the provider was asked again %v after the session went: the pause was skipped", took)
	}
}

// And no round that ends in a refusal asks the provider for a new
// connection, however many of them there are: the next connection to that
// peer would be refused the same way. A server that rejected every hello had
// the client rejoin the room every few seconds until the provider's reconnect
// budget ran out and the session ended.
//
// ai-generated: the whole test (review of #20).
func TestRefusedRoundsNeverAskTheProvider(t *testing.T) {
	r := newRig(t, func(c *Client) {
		c.livenessFallback = 100 * time.Millisecond
		c.handshakeTimeout = 200 * time.Millisecond
		c.retryDelay = 10 * time.Millisecond
	})
	r.server.refuse.Store(true)
	before := r.server.sessions.Load()

	r.link.callback()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		select {
		case reason := <-r.link.requests:
			t.Fatalf("the client asked the provider to reconnect (%s) after a refusal", reason)
		case <-time.After(20 * time.Millisecond):
		}
	}
	// It kept trying on its own, at the pace the pause sets.
	if got := r.server.sessions.Load() - before; got < 2 {
		t.Fatalf("handshakes over the second = %d, want the refused rounds to go on", got)
	}
}

// A hello queued at the relay behind a dead session's backlog reaches a
// server that is in the room after the attempt that sent it has given up.
// The transport says the peer it would handshake with is there (PeerSeen),
// so the timeout is a silent peer, not an empty room: the round goes on to
// its next attempt at once, even for a client that ends its run on an empty
// room, instead of stopping at one attempt and pausing for a whole recovery
// window before it asks the provider for a new connection.
//
// ai-generated: the whole test (PeerObserver bound to the confirmed server,
// olcrtc#49).
func TestAQueuedHelloIsRetriedInsideTheRound(t *testing.T) {
	r := newRig(t, func(c *Client) {
		c.endOnEmptyRoom = true
		c.livenessFallback = time.Hour
		c.handshakeTimeout = 200 * time.Millisecond
		c.retryDelay = 10 * time.Millisecond
	})
	first := r.sessionID()
	before := r.server.sessions.Load()
	// The round's first hello is never answered; its second is.
	r.server.answerFrom.Store(before + 2)

	r.link.callback()
	r.waitNewSession(first, 3*time.Second)
	if got := r.server.sessions.Load() - before; got != 2 {
		t.Fatalf("handshake attempts in the round = %d, want the silent one and the retry inside the round", got)
	}
	if r.ctx.Err() != nil {
		t.Fatal("a silent peer ended the run of a client that ends it on an empty room")
	}
	select {
	case reason := <-r.link.requests:
		t.Fatalf("the client asked the provider to reconnect (%s) although the retry inside the round was answered", reason)
	default:
	}
}
