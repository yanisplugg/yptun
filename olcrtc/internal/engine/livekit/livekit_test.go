package livekit

import (
	"bytes"
	"context"
	"errors"
	"log"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	lksdk "github.com/owenewans/owenlivekit/v2"
	"github.com/pion/webrtc/v4"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
)

const (
	testOldURL   = "wss://old"
	testOldToken = "old-token"
)

var errFakeConnect = errors.New("boom")

type fakeRoom struct {
	mu           sync.Mutex
	state        lksdk.ConnectionState
	published    [][]byte
	datagrams    [][]byte
	datagramPeer []string
	tracks       int
	unpublished  int
	disconnected int
	// ai-generated: gone and publisherDown.
	gone          chan struct{}
	publisherDown bool
	// ai-generated: reason (olcrtc#19).
	reason string
}

func newFakeRoom() *fakeRoom {
	return &fakeRoom{state: lksdk.ConnectionStateConnected, gone: make(chan struct{})}
}

func (r *fakeRoom) publishData(data []byte) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.published = append(r.published, append([]byte(nil), data...))
	return nil
}

func (r *fakeRoom) publishDatagram(data []byte, peerID string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.datagrams = append(r.datagrams, append([]byte(nil), data...))
	r.datagramPeer = append(r.datagramPeer, peerID)
	return nil
}

func (r *fakeRoom) publishTrack(webrtc.TrackLocal) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.tracks++
	return nil
}

func (r *fakeRoom) unpublishLocalTracks() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.unpublished++
}

func (r *fakeRoom) disconnect() {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.disconnected == 0 {
		close(r.gone) // ai-generated: this branch.
	}
	r.disconnected++
	r.state = lksdk.ConnectionStateDisconnected
}

// ai-generated: left, publisherReady and setPublisherDown.
func (r *fakeRoom) left() <-chan struct{} {
	return r.gone
}

func (r *fakeRoom) publisherReady() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return !r.publisherDown
}

func (r *fakeRoom) disconnectReason() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.reason
}

func (r *fakeRoom) setPublisherDown(down bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.publisherDown = down
}

func (r *fakeRoom) connectionState() lksdk.ConnectionState {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.state
}

type fakeConnector struct {
	mu           sync.Mutex
	urls         []string
	tokens       []string
	callbacks    []*lksdk.RoomCallback
	optionCounts []int
	rooms        []*fakeRoom
	connected    chan struct{}
	err          error
}

func newFakeConnector() *fakeConnector {
	return &fakeConnector{connected: make(chan struct{}, 8)}
}

func (c *fakeConnector) connect(
	url, token string, cb *lksdk.RoomCallback, opts ...lksdk.ConnectOption,
) (roomHandle, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.err != nil {
		return nil, c.err
	}
	room := newFakeRoom()
	c.urls = append(c.urls, url)
	c.tokens = append(c.tokens, token)
	c.callbacks = append(c.callbacks, cb)
	c.optionCounts = append(c.optionCounts, len(opts))
	c.rooms = append(c.rooms, room)
	c.connected <- struct{}{}
	return room, nil
}

func (c *fakeConnector) count() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return len(c.rooms)
}

func (c *fakeConnector) callback(i int) *lksdk.RoomCallback {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.callbacks[i]
}

func (c *fakeConnector) room(i int) *fakeRoom {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.rooms[i]
}

func (c *fakeConnector) snapshot() ([]string, []string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return append([]string(nil), c.urls...), append([]string(nil), c.tokens...)
}

func (c *fakeConnector) options() []int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return append([]int(nil), c.optionCounts...)
}

func waitFor(t *testing.T, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("condition was not met before timeout")
}

func TestReconnectRefreshesCredentialsAndReplacesRoom(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	refreshes := 0
	sess, err := New(ctx, engine.Config{
		URL:   testOldURL,
		Token: testOldToken,
		Refresh: func(context.Context) (engine.Credentials, error) {
			refreshes++
			return engine.Credentials{URL: "wss://new", Token: "new-token"}, nil
		},
	})
	if err != nil {
		t.Fatalf("New() error = %v", err)
	}
	s, ok := sess.(*Session)
	if !ok {
		t.Fatalf("New() type = %T, want *Session", sess)
	}
	connector := newFakeConnector()
	s.connectRoom = connector.connect

	reconnected := make(chan struct{}, 1)
	s.SetReconnectCallback(func() {
		reconnected <- struct{}{}
	})

	if err := s.Connect(ctx); err != nil {
		t.Fatalf("Connect() error = %v", err)
	}
	go s.WatchConnection(ctx)

	connector.callback(0).OnDisconnected()

	waitFor(t, func() bool { return connector.count() == 2 })
	select {
	case <-reconnected:
	case <-time.After(time.Second):
		t.Fatal("reconnect callback was not called")
	}

	urls, tokens := connector.snapshot()
	if got, want := urls, []string{testOldURL, "wss://new"}; !equalStrings(got, want) {
		t.Fatalf("connect urls = %v, want %v", got, want)
	}
	if got, want := tokens, []string{testOldToken, "new-token"}; !equalStrings(got, want) {
		t.Fatalf("connect tokens = %v, want %v", got, want)
	}
	if refreshes != 1 {
		t.Fatalf("refreshes = %d, want 1", refreshes)
	}
	if got := connector.options(); len(got) != 2 || got[0] < 2 || got[0] != got[1] {
		t.Fatalf("connect option counts = %v, want the same resolver options on reconnect", got)
	}
	oldRoom := connector.room(0)
	oldRoom.mu.Lock()
	if oldRoom.disconnected != 1 || oldRoom.unpublished != 1 {
		t.Fatalf("old room cleanup disconnected=%d unpublished=%d, want 1/1",
			oldRoom.disconnected, oldRoom.unpublished)
	}
	oldRoom.mu.Unlock()
	if !s.CanSend() {
		t.Fatal("CanSend() = false after reconnect, want true")
	}

	if err := s.Close(); err != nil {
		t.Fatalf("Close() error = %v", err)
	}
}

// TestReconnectCallbackRunsOnASendableSession guards olcrtc#19. The upper
// layer's reconnect callback is where it re-handshakes (client) or tells its
// peers their sessions are gone (server), so it has to be able to send on the
// room it was just handed. The rejoin used to stay marked in progress until
// the callback returned, which held CanSend false for the whole callback: a
// WB Stream client spent five 15 s handshakes that never got their first
// frame out, and the server's close notices waited out their deadline.
//
// ai-generated: the whole test.
func TestReconnectCallbackRunsOnASendableSession(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	sess, err := New(ctx, engine.Config{URL: testOldURL, Token: testOldToken})
	if err != nil {
		t.Fatalf("New() error = %v", err)
	}
	s, ok := sess.(*Session)
	if !ok {
		t.Fatalf("New() type = %T, want *Session", sess)
	}
	connector := newFakeConnector()
	s.connectRoom = connector.connect

	type sendable struct{ canSend, subscriber bool }
	seen := make(chan sendable, 1)
	s.SetReconnectCallback(func() {
		seen <- sendable{canSend: s.CanSend(), subscriber: s.SubscriberCanSend()}
	})

	if err := s.Connect(ctx); err != nil {
		t.Fatalf("Connect() error = %v", err)
	}
	go s.WatchConnection(ctx)
	connector.callback(0).OnDisconnected()

	select {
	case got := <-seen:
		if !got.canSend || !got.subscriber {
			t.Fatalf("inside the reconnect callback CanSend() = %v, SubscriberCanSend() = %v, want both true",
				got.canSend, got.subscriber)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("reconnect callback was not called")
	}
	if err := s.Close(); err != nil {
		t.Fatalf("Close() error = %v", err)
	}
}

// A room the server ends is logged with the reason the server gave, the only
// clue to why WB Stream drops a connection (olcrtc#19).
//
// ai-generated: the whole test.
func TestRoomDropIsLoggedWithItsReason(t *testing.T) {
	var buf lockedBuffer
	log.SetOutput(&buf)
	t.Cleanup(func() { log.SetOutput(os.Stderr) })

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	sess, err := New(ctx, engine.Config{URL: testOldURL, Token: testOldToken})
	if err != nil {
		t.Fatalf("New() error = %v", err)
	}
	s, ok := sess.(*Session)
	if !ok {
		t.Fatalf("New() type = %T, want *Session", sess)
	}
	connector := newFakeConnector()
	s.connectRoom = connector.connect
	if err := s.Connect(ctx); err != nil {
		t.Fatalf("Connect() error = %v", err)
	}
	go s.WatchConnection(ctx)

	room := connector.room(0)
	room.mu.Lock()
	room.reason = "MEDIA_FAILURE"
	room.mu.Unlock()
	connector.callback(0).OnDisconnected()
	waitFor(t, func() bool { return connector.count() == 2 })
	if got := buf.String(); !strings.Contains(got, "reason=MEDIA_FAILURE") {
		t.Fatalf("log after the room dropped us = %q, want the reason", got)
	}
	if err := s.Close(); err != nil {
		t.Fatalf("Close() error = %v", err)
	}
}

// lockedBuffer is a log destination safe for the goroutines that write it.
type lockedBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *lockedBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

func (b *lockedBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}

func TestDisconnectedEndsWhenReconnectDisallowed(t *testing.T) {
	ctx := context.Background()
	sess, err := New(ctx, engine.Config{URL: testOldURL, Token: testOldToken})
	if err != nil {
		t.Fatalf("New() error = %v", err)
	}
	s, ok := sess.(*Session)
	if !ok {
		t.Fatalf("New() type = %T, want *Session", sess)
	}
	connector := newFakeConnector()
	s.connectRoom = connector.connect
	s.SetShouldReconnect(func() bool { return false })

	ended := make(chan string, 1)
	s.SetEndedCallback(func(reason string) {
		ended <- reason
	})

	if err := s.Connect(ctx); err != nil {
		t.Fatalf("Connect() error = %v", err)
	}
	connector.callback(0).OnDisconnected()

	select {
	case reason := <-ended:
		if reason != "disconnected from livekit" {
			t.Fatalf("ended reason = %q, want disconnected from livekit", reason)
		}
	case <-time.After(time.Second):
		t.Fatal("ended callback was not called")
	}
	if !s.closed.Load() {
		t.Fatal("closed = false after terminal disconnect")
	}
	if connector.count() != 1 {
		t.Fatalf("connect count = %d, want 1", connector.count())
	}
	room := connector.room(0)
	room.mu.Lock()
	if room.disconnected != 1 || room.unpublished != 1 {
		t.Fatalf("terminal room cleanup disconnected=%d unpublished=%d, want 1/1",
			room.disconnected, room.unpublished)
	}
	room.mu.Unlock()

	if err := s.Close(); err != nil {
		t.Fatalf("Close() error = %v", err)
	}
	room.mu.Lock()
	if room.disconnected != 1 || room.unpublished != 1 {
		t.Fatalf("second close cleanup disconnected=%d unpublished=%d, want still 1/1",
			room.disconnected, room.unpublished)
	}
	room.mu.Unlock()
}

func TestCanSendRequiresConnectedRoomAndQueueHeadroom(t *testing.T) {
	s := &Session{
		sendQueue: make(chan []byte, engine.DefaultSendQueueSize),
		done:      make(chan struct{}),
		closeCh:   make(chan struct{}),
	}
	if s.CanSend() {
		t.Fatal("CanSend() = true without room")
	}

	room := newFakeRoom()
	room.state = lksdk.ConnectionStateDisconnected
	s.setRoom(room)
	if s.CanSend() {
		t.Fatal("CanSend() = true for disconnected room")
	}

	room.state = lksdk.ConnectionStateConnected
	if !s.CanSend() {
		t.Fatal("CanSend() = false for connected room")
	}

	for range engine.DefaultSendQueueCapHard {
		s.sendQueue <- []byte("x")
	}
	if s.CanSend() {
		t.Fatal("CanSend() = true at queue high watermark")
	}
}

func TestReconnectFailureRetriesUntilContextDone(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	s := &Session{
		url:   testOldURL,
		token: testOldToken,
		connectRoom: func(string, string, *lksdk.RoomCallback, ...lksdk.ConnectOption) (roomHandle, error) {
			cancel()
			return nil, errFakeConnect
		},
		closeCh:   make(chan struct{}),
		sendQueue: make(chan []byte, engine.DefaultSendQueueSize),
		done:      make(chan struct{}),
	}
	s.Configure(engine.ReconnectorConfig{
		MaxAttempts: maxReconnects,
		Reconnect:   s.reconnect,
	})
	finished := make(chan struct{})
	go func() {
		s.WatchConnection(ctx)
		close(finished)
	}()
	s.queueReconnect()
	select {
	case <-finished:
	case <-time.After(time.Second):
		t.Fatal("WatchConnection did not stop after context cancellation")
	}
}

func TestWaitForConnectedRoomIsBounded(t *testing.T) {
	s := &Session{
		sendQueue: make(chan []byte, engine.DefaultSendQueueSize),
		done:      make(chan struct{}),
		closeCh:   make(chan struct{}),
		roomReady: 30 * time.Millisecond,
	}
	room := newFakeRoom()
	room.state = lksdk.ConnectionStateDisconnected
	s.setRoom(room)

	got, err := s.waitForConnectedRoom()
	if got != nil {
		t.Fatalf("waitForConnectedRoom() = %v, want nil", got)
	}
	if !errors.Is(err, ErrRoomNotConnected) {
		t.Fatalf("waitForConnectedRoom() error = %v, want %v", err, ErrRoomNotConnected)
	}
}

func TestWaitForConnectedRoomStopsOnShutdown(t *testing.T) {
	s := &Session{
		sendQueue: make(chan []byte, engine.DefaultSendQueueSize),
		done:      make(chan struct{}),
		closeCh:   make(chan struct{}),
	}
	room := newFakeRoom()
	room.state = lksdk.ConnectionStateDisconnected
	s.setRoom(room)

	go func() {
		time.Sleep(10 * time.Millisecond)
		close(s.done)
	}()

	if _, err := s.waitForConnectedRoom(); !errors.Is(err, ErrSessionClosed) {
		t.Fatalf("waitForConnectedRoom() error = %v, want %v", err, ErrSessionClosed)
	}
}

func TestGetBufferedAmountTracksQueue(t *testing.T) {
	s := &Session{
		sendQueue: make(chan []byte, engine.DefaultSendQueueSize),
		done:      make(chan struct{}),
		closeCh:   make(chan struct{}),
	}
	if got := s.GetBufferedAmount(); got != 0 {
		t.Fatalf("GetBufferedAmount() = %d, want 0", got)
	}
	if err := s.Send([]byte("0123456789")); err != nil {
		t.Fatalf("Send() error = %v", err)
	}
	if got := s.GetBufferedAmount(); got != 10 {
		t.Fatalf("GetBufferedAmount() = %d, want 10", got)
	}
}

func TestCloseSignalIsNilSafe(t *testing.T) {
	engine.CloseSignal(nil)
	ch := make(chan struct{})
	engine.CloseSignal(ch)
	engine.CloseSignal(ch)
	select {
	case <-ch:
	default:
		t.Fatal("closeSignal() did not close the channel")
	}
}

func equalStrings(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func userPacket(topic string, data []byte) *lksdk.UserDataPacket {
	packet := lksdk.UserData(data)
	packet.Topic = topic
	return packet
}

func TestDatagramSendAndReceive(t *testing.T) {
	ctx := context.Background()
	gotData := make(chan string, 1)
	gotDatagram := make(chan string, 1)
	gotPeerDatagram := make(chan string, 1)
	sess, err := New(ctx, engine.Config{
		URL:            testOldURL,
		Token:          testOldToken,
		OnData:         func(data []byte) { gotData <- string(data) },
		OnDatagram:     func(data []byte) { gotDatagram <- string(data) },
		OnPeerDatagram: func(peerID string, data []byte) { gotPeerDatagram <- peerID + ":" + string(data) },
	})
	if err != nil {
		t.Fatalf("New() error = %v", err)
	}
	s, ok := sess.(*Session)
	if !ok {
		t.Fatalf("New() returned %T, want *Session", sess)
	}
	connector := newFakeConnector()
	s.connectRoom = connector.connect

	if err := s.SendDatagram([]byte("early")); !errors.Is(err, ErrRoomNotConnected) {
		t.Fatalf("SendDatagram() before Connect error = %v, want %v", err, ErrRoomNotConnected)
	}
	if err := s.Connect(ctx); err != nil {
		t.Fatalf("Connect() error = %v", err)
	}
	if !s.DatagramCanSend() {
		t.Fatal("DatagramCanSend() = false, want true")
	}
	if err := s.SendDatagram([]byte("udp")); err != nil {
		t.Fatalf("SendDatagram() error = %v", err)
	}
	if err := s.SendDatagramTo("peer-a", []byte("direct")); err != nil {
		t.Fatalf("SendDatagramTo() error = %v", err)
	}

	room := connector.room(0)
	room.mu.Lock()
	if got, want := len(room.datagrams), 2; got != want {
		room.mu.Unlock()
		t.Fatalf("datagram count = %d, want %d", got, want)
	}
	if string(room.datagrams[0]) != "udp" || room.datagramPeer[0] != "" ||
		string(room.datagrams[1]) != "direct" || room.datagramPeer[1] != "peer-a" {
		room.mu.Unlock()
		t.Fatalf("datagrams = %q peers=%v", room.datagrams, room.datagramPeer)
	}
	room.mu.Unlock()

	cb := connector.callback(0)
	cb.OnDataPacket(userPacket(dataPublishTopic, []byte("tcp")), lksdk.DataReceiveParams{})
	cb.OnDataPacket(userPacket(datagramPublishTopic, []byte("udp-in")), lksdk.DataReceiveParams{})
	cb.OnDataPacket(userPacket(datagramPublishTopic, []byte("udp-peer")), lksdk.DataReceiveParams{
		SenderIdentity: "peer-b",
	})
	if cb.OnDataReceived != nil {
		t.Fatal("OnDataReceived is set next to OnDataPacket: every packet would arrive twice")
	}

	assertChanString(t, gotData, "tcp")
	assertChanString(t, gotDatagram, "udp-in")
	assertChanString(t, gotPeerDatagram, "peer-b:udp-peer")

	_ = s.Close()
	if err := s.SendDatagram([]byte("late")); !errors.Is(err, ErrSessionClosed) {
		t.Fatalf("SendDatagram() after Close error = %v, want %v", err, ErrSessionClosed)
	}
}

func assertChanString(t *testing.T, ch <-chan string, want string) {
	t.Helper()
	select {
	case got := <-ch:
		if got != want {
			t.Fatalf("received %q, want %q", got, want)
		}
	case <-time.After(time.Second):
		t.Fatalf("timed out waiting for %q", want)
	}
}

// TestSessionDeclaresThePublishRateLimit holds what olcrtc#26 measured: a
// transport on this engine publishes under WB Stream's ceiling, and reads it
// through the engine.PublishRateLimited shape rather than a number of its own.
// ai-generated: the whole test.
func TestSessionDeclaresThePublishRateLimit(t *testing.T) {
	var s any = &Session{}
	limited, ok := s.(engine.PublishRateLimited)
	if !ok {
		t.Fatal("the livekit session does not declare a publish rate limit")
	}
	got := limited.PublishRateLimit()
	if got != publishRateLimit {
		t.Fatalf("PublishRateLimit = %d, want %d", got, publishRateLimit)
	}
	// The measured boundary was 1.5 MB/s sustained; anything at or above it
	// is removed, so the ceiling has to stay under it with room to spare.
	if got <= 0 || got >= 1_500_000 {
		t.Fatalf("PublishRateLimit = %d, outside what the measurements allow", got)
	}
}
