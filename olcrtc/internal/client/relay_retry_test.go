package client

import (
	"context"
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/control"
	cryptopkg "github.com/openlibrecommunity/olcrtc/internal/crypto"
	"github.com/openlibrecommunity/olcrtc/internal/engine"
	enginebuiltin "github.com/openlibrecommunity/olcrtc/internal/engine/builtin"
	"github.com/openlibrecommunity/olcrtc/internal/muxconn"
	"github.com/openlibrecommunity/olcrtc/internal/runtime"
	"github.com/openlibrecommunity/olcrtc/internal/server"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
	"github.com/openlibrecommunity/olcrtc/internal/transport/datachannel"
	"github.com/openlibrecommunity/olcrtc/internal/tunnelcore"
)

// ai-generated: the whole file (a hello retried on the same relay identity,
// olcrtc#49).
//
// A real client and a real server over a relay shaped like LiveKit behind
// SaluteJazz: every packet is stamped with its sender's identity, which the
// server keys its per-peer sessions on, and each participant is handed what
// is sent to it in order, one packet at a time. Nothing is lost; a leg can be
// held, which is what a slow SFU-to-receiver leg is to the frames behind it -
// they arrive later, in order.

const (
	relayServerID  = "server"
	relayClientID  = "client-1"
	relayRejoinID  = "client-2"
	relayRetryWait = 500 * time.Millisecond
)

// relayRoom is the relay: its members, by the order they joined.
type relayRoom struct {
	mu      sync.Mutex
	members []*relayMember
}

type relayFrame struct {
	from string
	data []byte
}

// relayMember is one participant: an engine.Session with the peer routing,
// identity and PeerObserver surfaces the datachannel transport reaches for.
type relayMember struct {
	room   *relayRoom
	onData func([]byte)
	onPeer func(string, []byte)
	wake   chan struct{}

	mu          sync.Mutex
	id          string
	queue       []relayFrame
	held        bool
	closed      bool
	onReconnect func()
}

// open is the provider factory: a member that routes by peer is the server.
func (r *relayRoom) open(_ context.Context, cfg enginebuiltin.Config) (engine.Session, error) {
	m := &relayMember{room: r, onData: cfg.OnData, onPeer: cfg.OnPeerData, wake: make(chan struct{}, 1), id: relayClientID}
	if cfg.OnPeerData != nil {
		m.id = relayServerID
	}
	r.mu.Lock()
	r.members = append(r.members, m)
	r.mu.Unlock()
	go m.deliver()
	return m, nil
}

func (r *relayRoom) member(id string) *relayMember {
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, m := range r.members {
		if m.identity() == id {
			return m
		}
	}
	return nil
}

func (m *relayMember) identity() string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.id
}

func (m *relayMember) kick() {
	select {
	case m.wake <- struct{}{}:
	default:
	}
}

func (m *relayMember) push(from string, data []byte) {
	m.mu.Lock()
	m.queue = append(m.queue, relayFrame{from: from, data: append([]byte(nil), data...)})
	m.mu.Unlock()
	m.kick()
}

// hold stops the leg toward m, or lets it go on with what it queued.
func (m *relayMember) hold(held bool) {
	m.mu.Lock()
	m.held = held
	m.mu.Unlock()
	m.kick()
}

func (m *relayMember) queued() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.queue)
}

// deliver hands m its queue in order, one packet at a time.
func (m *relayMember) deliver() {
	for {
		m.mu.Lock()
		if m.closed {
			m.mu.Unlock()
			return
		}
		if m.held || len(m.queue) == 0 {
			m.mu.Unlock()
			<-m.wake
			continue
		}
		frame := m.queue[0]
		m.queue = m.queue[1:]
		m.mu.Unlock()
		if m.onPeer != nil {
			m.onPeer(frame.from, frame.data)
		} else if m.onData != nil {
			m.onData(frame.data)
		}
	}
}

// relay sends data from m to every other member whose identity to allows.
func (m *relayMember) relay(data []byte, to func(string) bool) {
	from := m.identity()
	m.room.mu.Lock()
	others := slices.Clone(m.room.members)
	m.room.mu.Unlock()
	for _, other := range others {
		if other != m && to(other.identity()) {
			other.push(from, data)
		}
	}
}

// rejoin gives m the fresh identity a SaluteJazz rejoin does and calls the
// provider back, as the engine does once the rejoin is up.
func (m *relayMember) rejoin(id string) {
	m.mu.Lock()
	m.id = id
	callback := m.onReconnect
	m.mu.Unlock()
	callback()
}

func (m *relayMember) Connect(context.Context) error { return nil }
func (m *relayMember) Send(data []byte) error {
	m.relay(data, func(string) bool { return true })
	return nil
}

func (m *relayMember) SendTo(peerID string, data []byte) error {
	m.relay(data, func(id string) bool { return id == peerID })
	return nil
}

func (m *relayMember) Close() error {
	m.mu.Lock()
	m.closed = true
	m.mu.Unlock()
	m.kick()
	return nil
}

func (m *relayMember) SetReconnectCallback(cb func()) {
	m.mu.Lock()
	m.onReconnect = cb
	m.mu.Unlock()
}
func (*relayMember) SetShouldReconnect(func() bool)      {}
func (*relayMember) SetEndedCallback(func(string))       {}
func (*relayMember) WatchConnection(ctx context.Context) { <-ctx.Done() }
func (*relayMember) CanSend() bool                       { return true }
func (*relayMember) SubscriberCanSend() bool             { return true }
func (*relayMember) GetBufferedAmount() uint64           { return 0 }
func (*relayMember) Reconnect(string)                    {}
func (m *relayMember) LocalPeerID() string               { return m.identity() }
func (*relayMember) ConfirmPeer(string) error            { return nil }
func (*relayMember) ResetPeer()                          {}

// PeerSeen is the confirmed server in the room, which it is throughout.
func (m *relayMember) PeerSeen() bool { return m.room.member(relayServerID) != nil }

// serverSessions is what the server reported opening and closing, in order.
type serverSessions struct {
	mu     sync.Mutex
	opened []string
	closed []string
}

func (s *serverSessions) open(id string) {
	s.mu.Lock()
	s.opened = append(s.opened, id)
	s.mu.Unlock()
}

func (s *serverSessions) close(id string) {
	s.mu.Lock()
	s.closed = append(s.closed, id)
	s.mu.Unlock()
}

func (s *serverSessions) snapshot() ([]string, []string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return slices.Clone(s.opened), slices.Clone(s.closed)
}

// waitFor polls cond until it holds, and fails the test naming what if it
// does not within timeout.
func waitFor(t *testing.T, timeout time.Duration, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out after %v waiting for %s", timeout, what)
		}
		time.Sleep(2 * time.Millisecond)
	}
}

// clientConn is the data conn the client's current handshake runs over.
func (c *Client) clientConn() *muxconn.Conn {
	c.sessMu.RLock()
	defer c.sessMu.RUnlock()
	return c.conn
}

// TestAHelloRetriedOnTheSameIdentityIsAnsweredByANewSession is the retry the
// empty-room classifier now allows on SaluteJazz. The client's first hello
// after a rejoin waits at the SFU behind a dead session's backlog and reaches
// the server after the attempt that sent it has given up; the round's next
// attempt runs over a new smux session, under the same identity, on its heels.
//
// The server keys its peer sessions on that identity, and smux numbers a new
// session's streams from the start again, so the retry's hello reused the
// stream the late one had opened: the session the server built for the late
// hello read the retry's hello as a control message on its control stream and
// died of it, the hello with it, and closing that session wrote a stream close
// into the retry's hello stream. With one hello an attempt the round spent
// attempt after attempt on the session before it. The retry must be answered
// by a session of its own, and that session must stay up.
func TestAHelloRetriedOnTheSameIdentityIsAnsweredByANewSession(t *testing.T) {
	setHelloResend(t, time.Hour) // one hello an attempt: the attempt's hello is the one that must be answered
	transport.Register("datachannel", datachannel.New)
	room := &relayRoom{}
	provider := "relay-retry-" + t.Name()
	enginebuiltin.Register(provider, room.open)
	liveness := control.Config{Interval: 100 * time.Millisecond, Timeout: 5 * time.Second}

	ctx, cancel := context.WithCancel(context.Background())
	sessions := &serverSessions{}
	serverDone := make(chan error, 1)
	go func() {
		serverDone <- server.Run(ctx, server.Config{
			Transport: "datachannel", Provider: provider, RoomURL: "room", KeyHex: listenerTestKey,
			Liveness:       liveness,
			OnSessionOpen:  func(id, _ string, _ map[string]any) { sessions.open(id) },
			OnSessionClose: func(id, _ string) { sessions.close(id) },
		})
	}()
	t.Cleanup(func() {
		cancel()
		waitListenerRun(t, "server", serverDone)
	})
	waitFor(t, 2*time.Second, "the server to join", func() bool { return room.member(relayServerID) != nil })

	keys, err := tunnelcore.SetupKeySet(listenerTestKey, cryptopkg.Client)
	if err != nil {
		t.Fatal(err)
	}
	c := &Client{
		keys: keys, deviceID: "relay-retry", health: runtime.NewHealthTracker(nil),
		sessionReady: make(chan struct{}), shutdownGrace: time.Second,
		handshakeTimeout: relayRetryWait, retryDelay: 10 * time.Millisecond, livenessFallback: time.Hour,
	}
	clientCtx, clientCancel := context.WithCancel(ctx)
	if err := c.bringUpLink(clientCtx, Config{
		Transport: "datachannel", Provider: provider, RoomURL: "room", Liveness: liveness,
	}, clientCancel); err != nil {
		clientCancel()
		t.Fatalf("bringUpLink() error = %v", err)
	}
	t.Cleanup(func() {
		clientCancel()
		c.shutdown()
	})
	r := &rig{t: t, ctx: clientCtx, client: c}
	first := r.sessionID()
	before, _ := sessions.snapshot()

	// The leg to the server backs up, and the client rejoins under a new
	// identity: its first attempt's hello waits in the backlog until that
	// attempt has given up and the retry's hello is behind it.
	toServer := room.member(relayServerID)
	toServer.hold(true)
	dead := c.clientConn()
	room.member(relayClientID).rejoin(relayRejoinID)
	var attempt1 *muxconn.Conn
	waitFor(t, 2*time.Second, "the round's first attempt", func() bool {
		attempt1 = c.clientConn()
		return attempt1 != nil && attempt1 != dead
	})
	var retry *muxconn.Conn
	waitFor(t, 3*relayRetryWait, "the retry inside the round", func() bool {
		retry = c.clientConn()
		return retry != nil && retry != attempt1
	})
	queued := toServer.queued()
	waitFor(t, relayRetryWait/2, "the retry's hello to be sent", func() bool { return toServer.queued() > queued })
	time.Sleep(relayRetryWait / 5) // the hello's own frames, when they leave apart
	toServer.hold(false)

	r.waitNewSession(first, 10*relayRetryWait)
	if c.clientConn() != retry {
		t.Fatal("the retry's hello went unanswered: a later attempt opened the session")
	}
	// The late hello may have had a session of its own before the retry's
	// frames came in behind it; if it did, the server does not keep it.
	installed := r.sessionID()
	opened, _ := sessions.snapshot()
	opened = opened[len(before):]
	if len(opened) == 0 || opened[len(opened)-1] != installed || len(opened) > 2 {
		t.Fatalf("the server opened %d sessions since the rejoin, the client's at %d; "+
			"want the retry's last, after at most the late hello's",
			len(opened), slices.Index(opened, installed)+1)
	}
	waitFor(t, 2*time.Second, "the server to end the session of the hello the client gave up on", func() bool {
		_, closed := sessions.snapshot()
		for _, id := range opened[:len(opened)-1] {
			if !slices.Contains(closed, id) {
				return false
			}
		}
		return true
	})

	// And the session is sound: pongs go on coming back over its control
	// stream, and the server keeps it.
	since := time.Now()
	waitFor(t, 2*time.Second, "pongs on the session the retry opened", func() bool {
		last, ok := c.controlLastPong.Load().(time.Time)
		return ok && last.After(since.Add(3*liveness.Interval))
	})
	if _, closed := sessions.snapshot(); slices.Contains(closed, installed) {
		t.Fatal("the server closed the session the client runs")
	}
	if got := r.sessionID(); got != installed || !c.sessionEstablished() {
		t.Fatal("the client lost the session the retry opened")
	}
}
