package livekit

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	lksdk "github.com/owenewans/owenlivekit/v2"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
)

// ai-generated: whole file, cover for the join budget, for giving up on a
// join and for what a join leaves behind (ghostlane#38).

const (
	// testJoinBudget stands in for connectTimeout. It is well under the
	// SDK's own 5 s default, so a join still going testGrace past it shows
	// the budget never reached the SDK.
	testJoinBudget = 1500 * time.Millisecond
	// testJoinSlack is how much earlier than its budget a join may end.
	testJoinSlack = 500 * time.Millisecond
	// testGrace is how long a test waits past a deadline, a budget or a
	// cancel, before it calls Connect stuck.
	testGrace = 2 * time.Second
)

// joinOnly is the JoinResponse the stub sends: the subscriber is the primary
// peer connection and its offer never comes, so ICE cannot start and the SDK
// can only wait out its budget. The SDK reads a text frame as protojson.
const joinOnly = `{"join":{"room":{"sid":"RM_stub","name":"stub"},` +
	`"participant":{"sid":"PA_stub","identity":"stub"},` +
	`"subscriberPrimary":true,"serverInfo":{"edition":"Standard"}}}`

// signalStub is a LiveKit signalling endpoint that answers the join and says
// nothing after it. joined fires once the JoinResponse is out, left once the
// client has closed the socket.
type signalStub struct {
	url    string
	joined chan struct{}
	left   chan struct{}
}

func newSignalStub(t *testing.T) *signalStub {
	t.Helper()
	stub := &signalStub{joined: make(chan struct{}, 1), left: make(chan struct{}, 1)}
	upgrader := websocket.Upgrader{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !websocket.IsWebSocketUpgrade(r) {
			// The SDK checks the endpoint over plain HTTP after a failed join.
			w.WriteHeader(http.StatusOK)
			return
		}
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		defer func() {
			_ = conn.Close()
			notify(stub.left)
		}()
		if err := conn.WriteMessage(websocket.TextMessage, []byte(joinOnly)); err != nil {
			return
		}
		notify(stub.joined)
		for {
			if _, _, err := conn.ReadMessage(); err != nil {
				return
			}
		}
	}))
	t.Cleanup(srv.Close)
	stub.url = "ws" + strings.TrimPrefix(srv.URL, "http")
	return stub
}

func notify(ch chan<- struct{}) {
	select {
	case ch <- struct{}{}:
	default:
	}
}

// newStubSession returns a session that joins url on testJoinBudget.
func newStubSession(t *testing.T, url string) *Session {
	t.Helper()
	sess, err := New(context.Background(), engine.Config{URL: url, Token: "stub-token"})
	if err != nil {
		t.Fatalf("New() error = %v", err)
	}
	s, ok := sess.(*Session)
	if !ok {
		t.Fatalf("New() type = %T, want *Session", sess)
	}
	s.joinTimeout = testJoinBudget
	t.Cleanup(func() { _ = s.Close() })
	return s
}

func connectInBackground(ctx context.Context, s *Session) <-chan error {
	done := make(chan error, 1)
	go func() { done <- s.Connect(ctx) }()
	return done
}

func TestConnectWaitsOutTheJoinBudget(t *testing.T) {
	t.Parallel()
	stub := newSignalStub(t)
	s := newStubSession(t, stub.url)

	start := time.Now()
	done := connectInBackground(context.Background(), s)
	var err error
	select {
	case err = <-done:
	case <-time.After(testJoinBudget + testGrace):
		t.Fatalf("Connect() still waiting %s past its %s budget", testGrace, testJoinBudget)
	}
	elapsed := time.Since(start)
	if !errors.Is(err, lksdk.ErrConnectionTimeout) {
		t.Fatalf("Connect() error = %v, want %v", err, lksdk.ErrConnectionTimeout)
	}
	if elapsed < testJoinBudget-testJoinSlack {
		t.Fatalf("Connect() gave up after %s, want no sooner than its %s budget",
			elapsed.Round(time.Millisecond), testJoinBudget)
	}
	t.Logf("Connect() gave up after %s on a %s budget", elapsed.Round(time.Millisecond), testJoinBudget)
}

func TestConnectReturnsWhenContextIsCancelled(t *testing.T) {
	t.Parallel()
	stub := newSignalStub(t)
	s := newStubSession(t, stub.url)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := connectInBackground(ctx, s)
	select {
	case <-stub.joined:
	case <-time.After(testJoinBudget + testGrace):
		t.Fatal("the join never reached the signalling stub")
	}
	cancel()
	cancelled := time.Now()
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("Connect() error = %v, want %v", err, context.Canceled)
		}
	case <-time.After(testGrace):
		t.Fatalf("Connect() still waiting %s after its context was cancelled", testGrace)
	}
	t.Logf("Connect() returned %s after the cancel", time.Since(cancelled).Round(time.Microsecond))
	// Nobody waits for that join any more; it still ends on its budget and
	// closes its signalling socket instead of lingering.
	select {
	case <-stub.left:
	case <-time.After(testJoinBudget + testGrace):
		t.Fatal("the abandoned join never closed its signalling socket")
	}
}

// TestConnectLeavesAJoinThatLandsLate covers a join that succeeds after its
// caller gave up: nobody will use that room, so it must not stay in it.
func TestConnectLeavesAJoinThatLandsLate(t *testing.T) {
	t.Parallel()
	tests := []struct {
		name    string
		giveUp  func(s *Session, cancel context.CancelFunc)
		wantErr error
	}{
		{
			name:    "context cancelled",
			giveUp:  func(_ *Session, cancel context.CancelFunc) { cancel() },
			wantErr: context.Canceled,
		},
		{
			name:    "session closed",
			giveUp:  func(s *Session, _ context.CancelFunc) { _ = s.Close() },
			wantErr: ErrSessionClosed,
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			t.Parallel()
			room := newFakeRoom()
			release := make(chan struct{})
			var once sync.Once
			releaseJoin := func() { once.Do(func() { close(release) }) }
			t.Cleanup(releaseJoin)
			s := &Session{
				url:   testOldURL,
				token: testOldToken,
				connectRoom: func(string, string, *lksdk.RoomCallback, ...lksdk.ConnectOption) (roomHandle, error) {
					<-release
					return room, nil
				},
				closeCh:   make(chan struct{}),
				sendQueue: make(chan []byte, engine.DefaultSendQueueSize),
				done:      make(chan struct{}),
			}
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()

			done := connectInBackground(ctx, s)
			tt.giveUp(s, cancel)
			select {
			case err := <-done:
				if !errors.Is(err, tt.wantErr) {
					t.Fatalf("Connect() error = %v, want %v", err, tt.wantErr)
				}
			case <-time.After(testGrace):
				t.Fatal("Connect() kept waiting on a join its caller gave up on")
			}
			releaseJoin()
			waitFor(t, func() bool {
				room.mu.Lock()
				defer room.mu.Unlock()
				return room.disconnected == 1
			})
			if s.currentRoom() != nil {
				t.Fatal("a join that landed after its caller left was installed")
			}
		})
	}
}

// TestDisconnectOfALeftRoomIsIgnored covers the SDK reporting the end of a
// room after a reconnect left it for another: the room in use stays.
func TestDisconnectOfALeftRoomIsIgnored(t *testing.T) {
	t.Parallel()
	connector := newFakeConnector()
	s := &Session{
		url:         testOldURL,
		token:       testOldToken,
		connectRoom: connector.connect,
		closeCh:     make(chan struct{}),
		sendQueue:   make(chan []byte, engine.DefaultSendQueueSize),
		done:        make(chan struct{}),
	}
	t.Cleanup(func() { _ = s.Close() })
	// With reconnects refused, a disconnect that is acted on ends the
	// session there and then.
	s.SetShouldReconnect(func() bool { return false })
	ended := make(chan string, 1)
	s.SetEndedCallback(func(reason string) { ended <- reason })

	ctx := context.Background()
	if err := s.Connect(ctx); err != nil {
		t.Fatalf("Connect() error = %v", err)
	}
	if err := s.reconnect(ctx); err != nil {
		t.Fatalf("reconnect() error = %v", err)
	}
	connector.callback(0).OnDisconnected()
	select {
	case reason := <-ended:
		t.Fatalf("the disconnect of the room reconnect left ended the session: %s", reason)
	default:
	}
	if s.currentRoom() != roomHandle(connector.room(1)) {
		t.Fatal("the disconnect of the room reconnect left replaced the room in use")
	}

	connector.callback(1).OnDisconnected()
	select {
	case <-ended:
	case <-time.After(testGrace):
		t.Fatal("the disconnect of the room in use was ignored")
	}
}

// TestConnectLeavesARoomThatLandsAfterClose covers Close landing while the
// join returns: the room is left, not installed on a closed session.
func TestConnectLeavesARoomThatLandsAfterClose(t *testing.T) {
	t.Parallel()
	room := newFakeRoom()
	s := &Session{
		url:       testOldURL,
		token:     testOldToken,
		closeCh:   make(chan struct{}),
		sendQueue: make(chan []byte, engine.DefaultSendQueueSize),
		done:      make(chan struct{}),
	}
	s.connectRoom = func(string, string, *lksdk.RoomCallback, ...lksdk.ConnectOption) (roomHandle, error) {
		_ = s.Close()
		return room, nil
	}
	if err := s.Connect(context.Background()); !errors.Is(err, ErrSessionClosed) {
		t.Fatalf("Connect() error = %v, want %v", err, ErrSessionClosed)
	}
	waitFor(t, func() bool {
		room.mu.Lock()
		defer room.mu.Unlock()
		return room.disconnected == 1
	})
	if s.currentRoom() != nil {
		t.Fatal("a room that landed after Close was installed")
	}
	// Whether joinRoom saw the join or the Close first is the scheduler's
	// call, so the gate behind it is checked on its own as well.
	if s.setRoom(newFakeRoom()) {
		t.Fatal("setRoom() installed a room on a closed session")
	}
}
