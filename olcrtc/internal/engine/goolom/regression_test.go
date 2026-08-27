// Regression tests for the signaling-write and shared-state defects fixed in
// the engine. Each test names the failure mode it pins down so a future
// regression points back at the original bug.
package goolom

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// wsTestServer is an in-process WebSocket endpoint that counts the frames it
// receives. It stands in for the SFU signaling channel.
type wsTestServer struct {
	srv *httptest.Server

	mu       sync.Mutex
	received int
}

func newWSTestServer(t *testing.T) *wsTestServer {
	t.Helper()
	ts := &wsTestServer{}
	upgrader := websocket.Upgrader{}
	ts.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		defer func() { _ = conn.Close() }()
		for {
			var msg map[string]any
			if err := conn.ReadJSON(&msg); err != nil {
				return
			}
			ts.mu.Lock()
			ts.received++
			ts.mu.Unlock()
		}
	}))
	t.Cleanup(ts.srv.Close)
	return ts
}

func (ts *wsTestServer) count() int {
	ts.mu.Lock()
	defer ts.mu.Unlock()
	return ts.received
}

// dial connects a Session to the test server.
func (ts *wsTestServer) dial(t *testing.T, s *Session) {
	t.Helper()
	url := "ws" + strings.TrimPrefix(ts.srv.URL, "http")
	conn, resp, err := websocket.DefaultDialer.Dial(url, nil)
	if err != nil {
		t.Fatalf("dial test ws: %v", err)
	}
	if resp != nil && resp.Body != nil {
		_ = resp.Body.Close()
	}
	s.wsMu.Lock()
	s.ws = conn
	s.wsMu.Unlock()
	t.Cleanup(s.closeWebSocket)
}

// TestWriteJSONSerialisesConcurrentWriters pins the single-writer contract.
// Signaling writes came from the ICE callbacks, the keepalive goroutine, the
// signaling loop and Close; gorilla panics with "concurrent write to
// websocket connection" as soon as two of them overlap, so every write has to
// go through the one method that owns wsMu.
func TestWriteJSONSerialisesConcurrentWriters(t *testing.T) {
	ts := newWSTestServer(t)
	s := &Session{}
	ts.dial(t, s)

	const writers, perWriter = 8, 50
	var wg sync.WaitGroup
	for w := range writers {
		wg.Go(func() {
			for i := range perWriter {
				if err := s.writeJSON(testFrame(frameID(w, i))); err != nil {
					t.Errorf("writeJSON: %v", err)
					return
				}
			}
		})
	}
	wg.Wait()

	want := writers * perWriter
	deadline := time.Now().Add(5 * time.Second)
	for ts.count() < want && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if got := ts.count(); got != want {
		t.Fatalf("server received %d frames, want %d", got, want)
	}
}

// TestWriteJSONWithoutConnection covers the nil check every ad-hoc write site
// used to skip: a write attempted before dial or after teardown must report
// the closed socket instead of dereferencing nil.
func TestWriteJSONWithoutConnection(t *testing.T) {
	s := &Session{}

	if err := s.writeJSON(testFrame("uid")); !errors.Is(err, ErrWebSocketClosed) {
		t.Fatalf("writeJSON err = %v, want ErrWebSocketClosed", err)
	}
	if s.sendLeave("uid") {
		t.Fatal("sendLeave reported success without a connection")
	}
	if err := s.sendSetSlots(); !errors.Is(err, ErrWebSocketClosed) {
		t.Fatalf("sendSetSlots err = %v, want ErrWebSocketClosed", err)
	}
	// Fire-and-forget helpers must stay panic-free.
	s.sendAck("uid")
	s.sendPong("uid")
	if !s.sendAppPing() {
		t.Fatal("sendAppPing must not trigger a reconnect without a connection")
	}
}

// TestWriteJSONAfterCloseWebSocket pins the ownership of the ws pointer:
// closeWebSocket clears it under wsMu, so later writes fail cleanly rather
// than racing a torn-down connection.
func TestWriteJSONAfterCloseWebSocket(t *testing.T) {
	ts := newWSTestServer(t)
	s := &Session{}
	ts.dial(t, s)

	if err := s.writeJSON(testFrame("uid")); err != nil {
		t.Fatalf("writeJSON before close: %v", err)
	}
	s.closeWebSocket()
	s.closeWebSocket() // must be idempotent

	if err := s.writeJSON(testFrame("uid")); !errors.Is(err, ErrWebSocketClosed) {
		t.Fatalf("writeJSON after close = %v, want ErrWebSocketClosed", err)
	}
	if s.wsConn() != nil {
		t.Fatal("closeWebSocket left the connection installed")
	}
}

// TestWriteJSONRacesConnectionSwap races signaling writes against the dial and
// teardown a reconnect performs. Before the fix s.ws was written without a
// lock in dialWebSocket while every writer read it under wsMu.
func TestWriteJSONRacesConnectionSwap(t *testing.T) {
	ts := newWSTestServer(t)
	s := &Session{}

	stop := make(chan struct{})
	var wg sync.WaitGroup
	wg.Go(func() {
		for {
			select {
			case <-stop:
				return
			default:
			}
			_ = s.writeJSON(testFrame("uid"))
			_ = s.wsConn()
		}
	})
	wg.Go(func() {
		for {
			select {
			case <-stop:
				return
			default:
			}
			url := "ws" + strings.TrimPrefix(ts.srv.URL, "http")
			conn, resp, err := websocket.DefaultDialer.Dial(url, nil)
			if err != nil {
				continue
			}
			if resp != nil && resp.Body != nil {
				_ = resp.Body.Close()
			}
			s.wsMu.Lock()
			old := s.ws
			s.ws = conn
			s.wsMu.Unlock()
			if old != nil {
				_ = old.Close()
			}
		}
	})

	time.Sleep(150 * time.Millisecond)
	close(stop)
	wg.Wait()
	s.closeWebSocket()
}

// TestSubscriberConnSignalIsRaceFree races the subscriber-ready signal against
// the channel swap a reconnect performs. Callbacks of the superseded PC keep
// firing, so an unguarded closeSignal both raced the swap and could close an
// already-closed channel.
func TestSubscriberConnSignalIsRaceFree(_ *testing.T) {
	s := &Session{subscriberConn: make(chan struct{})}

	stop := make(chan struct{})
	var wg sync.WaitGroup
	for range 4 {
		wg.Go(func() {
			for {
				select {
				case <-stop:
					return
				default:
				}
				s.signalSubscriberConn()
			}
		})
	}
	wg.Go(func() {
		for {
			select {
			case <-stop:
				return
			default:
			}
			s.resetMediaState()
			<-time.After(time.Microsecond * 50)
		}
	})

	time.Sleep(150 * time.Millisecond)
	close(stop)
	wg.Wait()
}

// TestWaitForMediaReadyTracksSubscriberOnly documents the readiness contract
// after the unused publisherConn signal was removed: Connect gates on the
// subscriber PC alone, exactly as CanSend does.
func TestWaitForMediaReadyTracksSubscriberOnly(t *testing.T) {
	s := &Session{subscriberConn: make(chan struct{})}

	if err := s.waitForMediaReady(t.Context(), 20*time.Millisecond); !errors.Is(err, ErrSubscriberMediaTimeout) {
		t.Fatalf("waitForMediaReady err = %v, want ErrSubscriberMediaTimeout", err)
	}

	// Publisher readiness alone must not unblock it.
	s.publisherReady.Store(true)
	if err := s.waitForMediaReady(t.Context(), 20*time.Millisecond); !errors.Is(err, ErrSubscriberMediaTimeout) {
		t.Fatalf("publisher readiness unblocked waitForMediaReady: %v", err)
	}

	s.signalSubscriberConn()
	if err := s.waitForMediaReady(t.Context(), time.Second); err != nil {
		t.Fatalf("waitForMediaReady after subscriber signal: %v", err)
	}
}

// TestCloseIsIdempotent guards the teardown path against a double close of
// closeCh, which panics and takes the process down.
func TestCloseIsIdempotent(t *testing.T) {
	s := newTestSession()

	var wg sync.WaitGroup
	for range 4 {
		wg.Go(func() { _ = s.Close() })
	}
	wg.Wait()

	select {
	case <-s.closeCh:
	default:
		t.Fatal("Close did not signal closeCh")
	}
}

// TestDataChannelAccessorRaceFree races the data-channel swap a reconnect
// performs against the readers on the send path.
func TestDataChannelAccessorRaceFree(t *testing.T) {
	s := newTestSession()

	stop := make(chan struct{})
	var wg sync.WaitGroup
	wg.Go(func() {
		for {
			select {
			case <-stop:
				return
			default:
			}
			s.closeDataChannel()
		}
	})
	wg.Go(func() {
		for {
			select {
			case <-stop:
				return
			default:
			}
			if s.CanSend() {
				t.Error("CanSend() = true without a data channel")
				return
			}
			_ = s.GetBufferedAmount()
			if err := s.Send([]byte("x")); !errors.Is(err, ErrDataChannelNotReady) {
				t.Errorf("Send err = %v, want ErrDataChannelNotReady", err)
				return
			}
		}
	})

	time.Sleep(100 * time.Millisecond)
	close(stop)
	wg.Wait()
}

func newTestSession() *Session {
	return &Session{
		onData:         func([]byte) {},
		closeCh:        make(chan struct{}),
		keepAliveCh:    make(chan struct{}),
		sessionCloseCh: make(chan struct{}),
		telemetryCh:    make(chan struct{}, 1),
		sendQueue:      make(chan []byte, 8),
		ackWaiters:     make(map[string]chan struct{}),
		subscriberConn: make(chan struct{}),
	}
}

// testFrame builds a minimal signaling frame for the write tests.
func testFrame(uid string) map[string]any {
	return map[string]any{
		keyUID: uid,
		"pong": map[string]any{},
	}
}

// frameID builds a deterministic frame id for the concurrency tests.
func frameID(writer, index int) string {
	return "w" + strconv.Itoa(writer) + "-" + strconv.Itoa(index)
}
