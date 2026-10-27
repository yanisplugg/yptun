package vkcalls

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
)

// The server side of the test is one actor: gorilla allows a single
// concurrent reader and writer per connection, so all server-side access is
// serialized through one mutex.
type fakeSFU struct {
	conn     *websocket.Conn
	mu       sync.Mutex
	seenPong bool
	reject   bool
}

func (f *fakeSFU) write(frame any) {
	f.mu.Lock()
	defer f.mu.Unlock()
	_ = f.conn.WriteJSON(frame)
}

func (f *fakeSFU) serve() {
	f.mu.Lock()
	_ = f.conn.SetReadDeadline(time.Now().Add(10 * time.Second))
	f.mu.Unlock()
	for {
		_, data, err := f.conn.ReadMessage()
		if err != nil {
			return
		}
		if string(data) == "pong" {
			f.mu.Lock()
			f.seenPong = true
			f.mu.Unlock()
			continue
		}
		var frame map[string]any
		if json.Unmarshal(data, &frame) != nil {
			continue
		}
		command, isString := frame["command"].(string)
		if !isString {
			continue
		}
		reply := map[string]any{"sequence": frame["sequence"], "response": command, "type": "response"}
		if f.reject {
			reply = map[string]any{"sequence": frame["sequence"], "type": "error"}
		}
		f.write(reply)
	}
}

// TestSignalingCommandFlow drives the signaling client through its contract:
// the connection notification, command response correlation, the ping/pong
// keepalive, rejection and the closed-session failure.
func TestSignalingCommandFlow(t *testing.T) {
	upgrader := websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}
	fsfu := &fakeSFU{}
	connected := make(chan struct{})
	var once sync.Once
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		fsfu.conn = conn
		once.Do(func() { close(connected) })
		go fsfu.serve()
		fsfu.write(map[string]any{
			"type": "notification", "notification": "connection",
			"peerId": map[string]any{"id": 111},
			"conversation": map[string]any{"topology": "SERVER", "participants": []any{
				map[string]any{"id": 42, "state": "ACCEPTED"},
				map[string]any{"id": 43, "state": "HUNGUP"},
			}},
		})
	}))
	defer server.Close()

	client, err := dialSignaling(context.Background(), "ws"+server.URL[4:]+"?token=t&userId=42", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer client.close()
	<-connected

	connection, err := client.awaitJoin(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if connection.Conversation == nil || connection.Conversation.Topology != "SERVER" {
		t.Fatalf("connection topology missing: %+v", connection)
	}
	if ids := participantIDs(connection, "42"); len(ids) != 0 {
		t.Fatalf("expected self filtered out, got %v", ids)
	}

	response, err := client.command(context.Background(), "allocate-consumer", map[string]any{"capabilities": referenceCapabilities()})
	if err != nil {
		t.Fatal(err)
	}
	if response.Response != "allocate-consumer" {
		t.Fatalf("response %q", response.Response)
	}

	// The keepalive: a text "ping" from the server is answered "pong"; the
	// serve loop records it.
	fsfu.mu.Lock()
	_ = fsfu.conn.WriteMessage(websocket.TextMessage, []byte("ping"))
	fsfu.mu.Unlock()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		fsfu.mu.Lock()
		seen := fsfu.seenPong
		fsfu.mu.Unlock()
		if seen {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	fsfu.mu.Lock()
	seen := fsfu.seenPong
	fsfu.mu.Unlock()
	if !seen {
		t.Fatal("client did not answer ping with pong")
	}

	fsfu.mu.Lock()
	fsfu.reject = true
	fsfu.mu.Unlock()
	// A command with no payload fields is still one JSON object, so the fake
	// SFU parses it and its error frame maps to ErrSignalingRejected.
	if _, err := client.command(context.Background(), "update-media-modifiers", nil); !errors.Is(err, ErrSignalingRejected) {
		t.Fatalf("got %v, want ErrSignalingRejected", err)
	}

	// Closing the socket fails pending and future commands.
	fsfu.mu.Lock()
	_ = fsfu.conn.Close()
	fsfu.mu.Unlock()
	select {
	case <-client.Closed():
	case <-time.After(2 * time.Second):
		t.Fatal("closed channel not signalled")
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if _, err := client.command(ctx, "hangup", nil); err == nil {
		t.Fatal("expected closed error")
	}
}

func TestSignalingURL(t *testing.T) {
	got, err := signalingURL("wss://calls.example.okcdn.ru/fb?token=t&userId=1")
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"platform=WEB", "appVersion=1.1", "version=5", "capabilities=2F7F", "tgt=join"} {
		if !strings.Contains(got, want) {
			t.Fatalf("url %q missing %q", got, want)
		}
	}
	if _, err := signalingURL("https://calls.example/"); err == nil {
		t.Fatal("expected endpoint error")
	}
}

// frameHeader is the first header of a client frame as it arrives on the wire.
type frameHeader struct {
	fin    bool
	opcode byte
	length uint64
}

// readFrameHeader parses one raw WebSocket frame header (RFC 6455 5.2):
// gorilla's own reader would join continuation frames and hide a split.
func readFrameHeader(r io.Reader) (frameHeader, error) {
	var b [2]byte
	if _, err := io.ReadFull(r, b[:]); err != nil {
		return frameHeader{}, err
	}
	h := frameHeader{fin: b[0]&0x80 != 0, opcode: b[0] & 0x0f, length: uint64(b[1] & 0x7f)}
	switch h.length {
	case 126:
		var ext [2]byte
		if _, err := io.ReadFull(r, ext[:]); err != nil {
			return frameHeader{}, err
		}
		h.length = uint64(binary.BigEndian.Uint16(ext[:]))
	case 127:
		var ext [8]byte
		if _, err := io.ReadFull(r, ext[:]); err != nil {
			return frameHeader{}, err
		}
		h.length = binary.BigEndian.Uint64(ext[:])
	}
	return h, nil
}

// TestSignalingLargeCommandIsOneFrame pins the wire shape of a large command.
// The SFU parses each text frame on its own: an accept-producer description
// (~50 KB) split into 4 KiB continuation frames came back as invalid-request
// "Invalid message format" with no sequence (live engine smoke, 2026-09-28),
// while the probe's single frame with the same bytes was accepted.
func TestSignalingLargeCommandIsOneFrame(t *testing.T) {
	upgrader := websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}
	headers := make(chan frameHeader, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		defer func() { _ = conn.Close() }()
		raw := conn.NetConn()
		_ = raw.SetReadDeadline(time.Now().Add(5 * time.Second))
		header, err := readFrameHeader(raw)
		if err != nil {
			close(headers)
			return
		}
		headers <- header
		_ = conn.WriteMessage(websocket.TextMessage,
			[]byte(`{"stamp":0,"sequence":1,"response":"accept-producer","type":"response"}`))
		_, _ = io.Copy(io.Discard, raw)
	}))
	defer server.Close()

	client, err := dialSignaling(context.Background(), "ws"+server.URL[4:]+"?token=t&userId=42", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer client.close()

	payload := map[string]any{fieldDescription: strings.Repeat("a=rtcp-fb:96 nack pli\r\n", 3000)}
	want, err := orderedCommandFrame("accept-producer", 1, payload)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := client.command(context.Background(), "accept-producer", payload); err != nil {
		t.Fatal(err)
	}
	header, ok := <-headers
	if !ok {
		t.Fatal("server read no frame header")
	}
	if !header.fin || header.opcode != websocket.TextMessage || header.length != uint64(len(want)) {
		t.Fatalf("accept-producer of %d bytes went out as fin=%v opcode=%d length=%d; want one text frame",
			len(want), header.fin, header.opcode, header.length)
	}

	// A command larger than the write buffer is refused before any byte goes
	// out, never split.
	huge := map[string]any{fieldDescription: strings.Repeat("x", signalingWriteBuffer)}
	if _, err := client.command(context.Background(), "accept-producer", huge); !errors.Is(err, ErrSignalingFrameTooLarge) {
		t.Fatalf("oversized command: got %v, want ErrSignalingFrameTooLarge", err)
	}
}

// TestOrderedCommandFrameIsJSON pins the frame to one JSON object whatever
// the payload holds: none, no fields, or fields.
func TestOrderedCommandFrameIsJSON(t *testing.T) {
	for _, payload := range []any{nil, map[string]any{}, map[string]any{"a": 1}} {
		raw, err := orderedCommandFrame("x", 2, payload)
		if err != nil {
			t.Fatal(err)
		}
		if !json.Valid(raw) || !strings.HasPrefix(string(raw), `{"command":"x","sequence":2`) {
			t.Fatalf("payload %v: frame %s", payload, raw)
		}
	}
	if _, err := orderedCommandFrame("x", 2, []int{1}); !errors.Is(err, ErrSignalingPayload) {
		t.Fatalf("array payload: got %v, want ErrSignalingPayload", err)
	}
}

// TestSignalingWaitsForServerTopology pins the fresh-room path: a lone guest
// is handed DIRECT, and the wait ends only on the topology-changed
// notification a second participant triggers - or with the caller's context,
// naming the topology as the reason.
func TestSignalingWaitsForServerTopology(t *testing.T) {
	upgrader := websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}
	fsfu := &fakeSFU{}
	connected := make(chan struct{})
	var once sync.Once
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		fsfu.conn = conn
		once.Do(func() { close(connected) })
		go fsfu.serve()
		fsfu.write(map[string]any{
			"type": "notification", "notification": "connection",
			"peerId":       map[string]any{"id": 111},
			"conversation": map[string]any{"topology": "DIRECT", "participants": []any{}},
		})
	}))
	defer server.Close()

	client, err := dialSignaling(context.Background(), "ws"+server.URL[4:]+"?token=t&userId=42", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer client.close()
	<-connected

	ctx, cancel := context.WithCancel(context.Background())
	got := make(chan error, 1)
	go func() {
		note, err := client.awaitJoin(ctx)
		if err != nil {
			got <- err
			return
		}
		got <- client.waitServerTopology(ctx, note)
	}()

	// Still waiting while the room is DIRECT.
	select {
	case err := <-got:
		t.Fatalf("wait ended early: %v", err)
	case <-time.After(150 * time.Millisecond):
	}
	fsfu.write(map[string]any{"type": "notification", "notification": "topology-changed", "topology": "SERVER"})
	select {
	case err := <-got:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("the switch to SERVER did not end the wait")
	}
	cancel()

	// A context that ends in DIRECT names the topology. The first wait
	// consumed the connection notification, so the fake sends it again.
	fsfu.write(map[string]any{
		"type": "notification", "notification": "connection",
		"conversation": map[string]any{"topology": "DIRECT", "participants": []any{}},
	})
	ctx2, cancel2 := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel2()
	if _, err := func() (notification, error) {
		note, err := client.awaitJoin(ctx2)
		if err != nil {
			return notification{}, err
		}
		return note, client.waitServerTopology(ctx2, note)
	}(); !errors.Is(err, ErrSignalingNotServer) {
		t.Fatalf("want ErrSignalingNotServer, got %v", err)
	}
}

// TestRecruitFlipsFreshRoom models the measured fresh-room behaviour: the
// first two connections stay DIRECT, and the third simultaneous participant
// is what flips the SFU to SERVER - which it announces to everyone. The
// session's wait, with its recruiter running, must end on that flip.
func TestRecruitFlipsFreshRoom(t *testing.T) {
	upgrader := websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}
	var mu sync.Mutex
	var conns []*websocket.Conn
	flip := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		mu.Lock()
		conns = append(conns, conn)
		count := len(conns)
		mu.Unlock()
		write := func(frame any) { _ = conn.WriteJSON(frame) }
		write(map[string]any{
			"type": "notification", "notification": "connection",
			"conversation": map[string]any{"topology": "DIRECT", "participants": []any{}},
		})
		if count == 3 {
			close(flip)
		}
		go func() {
			<-flip
			write(map[string]any{"type": "notification", "notification": "topology-changed", "topology": "SERVER"})
		}()
	}))
	defer server.Close()
	endpoint := "ws" + server.URL[4:] + "?token=t&userId=42"

	s := &Session{cfg: engine.Config{
		URL: endpoint,
		Refresh: func(context.Context) (engine.Credentials, error) {
			return engine.Credentials{URL: endpoint}, nil
		},
	}}
	signal, err := dialSignaling(context.Background(), endpoint, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer signal.close()
	done := make(chan error, 1)
	go func() {
		_, err := s.waitConnected(context.Background(), signal)
		done <- err
	}()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(15 * time.Second):
		t.Fatal("the recruited third participant did not flip the room")
	}
	// The recruiter's guests leave with the wait.
	mu.Lock()
	defer mu.Unlock()
	if len(conns) != 3 {
		t.Fatalf("connections=%d, want the session plus two recruits", len(conns))
	}
}
