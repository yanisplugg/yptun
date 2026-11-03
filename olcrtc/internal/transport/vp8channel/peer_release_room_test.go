package vp8channel

// ai-generated: the whole file. Real server and client sessions over vp8channel
// transports that meet in an in-memory SFU, for what only the full stack shows:
// which peer state survives the server ending a peer's session.

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/pion/webrtc/v4"

	"github.com/openlibrecommunity/olcrtc/internal/client"
	"github.com/openlibrecommunity/olcrtc/internal/control"
	"github.com/openlibrecommunity/olcrtc/internal/server"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
)

const roomKeyHex = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"

// memRoom forwards every sample a member writes to every other member's frame
// reader, as an SFU forwards a published track to each subscriber.
type memRoom struct {
	mu      sync.Mutex
	members map[*memMember]struct{}
	server  atomic.Pointer[streamTransport]
}

// memMember is one participant: the provider session under its transport.
type memMember struct {
	room      *memRoom
	tr        *streamTransport
	in        chan []byte
	done      chan struct{}
	closeOnce sync.Once
	reconnect atomic.Pointer[func()]
	// deaf drops everything forwarded to this member.
	deaf atomic.Bool
	// bytesOut counts what this member has written into the room.
	bytesOut atomic.Int64
}

func (m *memMember) Connect(context.Context) error { return nil }

func (m *memMember) Close() error {
	m.closeOnce.Do(func() {
		m.room.mu.Lock()
		delete(m.room.members, m)
		m.room.mu.Unlock()
		close(m.done)
	})
	return nil
}

func (m *memMember) SetReconnectCallback(cb func())      { m.reconnect.Store(&cb) }
func (m *memMember) SetShouldReconnect(func() bool)      {}
func (m *memMember) SetEndedCallback(func(string))       {}
func (m *memMember) WatchConnection(ctx context.Context) { <-ctx.Done() }
func (m *memMember) CanSend() bool                       { return true }
func (m *memMember) SubscriberCanSend() bool             { return true }
func (m *memMember) AddTrack(webrtc.TrackLocal) error    { return nil }

func (m *memMember) SetTrackHandler(func(*webrtc.TrackRemote, *webrtc.RTPReceiver)) {}

// Reconnect is a provider rebuild that succeeds at once.
func (m *memMember) Reconnect(string) {
	if cb := m.reconnect.Load(); cb != nil && *cb != nil {
		go (*cb)()
	}
}

func (m *memMember) write(sample []byte) bool {
	m.bytesOut.Add(int64(len(sample)))
	m.room.mu.Lock()
	targets := make([]*memMember, 0, len(m.room.members))
	for other := range m.room.members {
		if other != m && !other.deaf.Load() {
			targets = append(targets, other)
		}
	}
	m.room.mu.Unlock()
	for _, other := range targets {
		select {
		case other.in <- append([]byte(nil), sample...):
		case <-other.done:
		default:
		}
	}
	return true
}

func (m *memMember) readLoop() {
	for {
		select {
		case <-m.done:
			return
		case frame := <-m.in:
			m.tr.handleIncomingFrame(frame)
		}
	}
}

// newMemRoom registers a transport that joins every link built with it to one
// room, and returns its name.
func newMemRoom(t *testing.T) (string, *memRoom) {
	t.Helper()
	name := "vp8channel-room-" + t.Name()
	room := &memRoom{members: make(map[*memMember]struct{})}
	transport.Register(name, func(_ context.Context, cfg transport.Config) (transport.Transport, error) {
		m := &memMember{room: room, in: make(chan []byte, inboundQueueSize), done: make(chan struct{})}
		tr := newStreamTransport(m, nil, cfg, Options{FPS: 60}.withDefaults())
		tr.sampleWriter = m.write
		m.tr = tr
		room.mu.Lock()
		room.members[m] = struct{}{}
		room.mu.Unlock()
		if cfg.OnPeerData != nil {
			room.server.Store(tr)
		}
		go m.readLoop()
		return tr, nil
	})
	return name, room
}

// serverMember is the room's server side, the one whose writes every client
// reads.
func (r *memRoom) serverMember() *memMember {
	r.mu.Lock()
	defer r.mu.Unlock()
	for m := range r.members {
		if m.tr == r.server.Load() {
			return m
		}
	}
	return nil
}

func (r *memRoom) clients() []*memMember {
	r.mu.Lock()
	defer r.mu.Unlock()
	var out []*memMember
	for m := range r.members {
		if m.tr != r.server.Load() {
			out = append(out, m)
		}
	}
	return out
}

// startRoomServer runs a server in the room and returns the channel its
// session-close callbacks land on.
func startRoomServer(ctx context.Context, t *testing.T, name string, room *memRoom, liveness control.Config) <-chan string {
	t.Helper()
	closed := make(chan string, 64)
	done := make(chan struct{})
	go func() {
		defer close(done)
		err := server.Run(ctx, server.Config{
			Transport: name, Provider: "memory", RoomURL: "room", KeyHex: roomKeyHex,
			// ai-generated: the policy lift, the echo target is on loopback (egress hardening).
			DNSServer: "127.0.0.1:53", Liveness: liveness, UnsafeAllowPrivateTargets: true,
			OnSessionClose: func(id, reason string) { closed <- id + " " + reason },
		})
		if err != nil && ctx.Err() == nil {
			t.Errorf("server.Run: %v", err)
		}
	}()
	t.Cleanup(func() { <-done })
	for deadline := time.Now().Add(5 * time.Second); room.server.Load() == nil; {
		if time.Now().After(deadline) {
			t.Fatal("server transport never joined the room")
		}
		time.Sleep(5 * time.Millisecond)
	}
	return closed
}

// startRoomClient runs a client in the room and returns its SOCKS address and
// its stop function.
func startRoomClient(parent context.Context, t *testing.T, name, device string) (string, func()) {
	t.Helper()
	ctx, cancel := context.WithCancel(parent)
	ready := make(chan string, 1)
	done := make(chan struct{})
	go func() {
		defer close(done)
		_ = client.RunWithAddress(ctx, client.Config{
			Transport: name, Provider: "memory", RoomURL: "room", KeyHex: roomKeyHex,
			LocalAddr: "127.0.0.1:0", DNSServer: "127.0.0.1:53", DeviceID: device,
		}, func(addr string) { ready <- addr })
	}()
	stop := func() {
		cancel()
		<-done
	}
	select {
	case addr := <-ready:
		return addr, stop
	case <-done:
	case <-time.After(30 * time.Second):
	}
	stop()
	t.Fatalf("client %s never became ready", device)
	return "", nil
}

func startEcho(t *testing.T) string {
	t.Helper()
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ln.Close() })
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer func() { _ = conn.Close() }()
				_, _ = io.Copy(conn, conn)
			}()
		}
	}()
	return ln.Addr().String()
}

// echoThrough sends size bytes through the tunnel to the echo target and
// checks what comes back.
func echoThrough(socksAddr, target string, size int) error {
	conn, err := net.DialTimeout("tcp4", socksAddr, 5*time.Second)
	if err != nil {
		return fmt.Errorf("dial socks: %w", err)
	}
	defer func() { _ = conn.Close() }()
	_ = conn.SetDeadline(time.Now().Add(30 * time.Second))
	if _, err := conn.Write([]byte{5, 1, 0}); err != nil {
		return fmt.Errorf("socks greeting: %w", err)
	}
	if _, err := io.ReadFull(conn, make([]byte, 2)); err != nil {
		return fmt.Errorf("socks method: %w", err)
	}
	host, portText, _ := net.SplitHostPort(target)
	port, _ := strconv.Atoi(portText)
	request := append([]byte{5, 1, 0, 1}, net.ParseIP(host).To4()...)
	request = binary.BigEndian.AppendUint16(request, uint16(port)) //nolint:gosec // a listener port
	if _, err := conn.Write(request); err != nil {
		return fmt.Errorf("socks request: %w", err)
	}
	reply := make([]byte, 10)
	if _, err := io.ReadFull(conn, reply); err != nil {
		return fmt.Errorf("socks reply: %w", err)
	}
	if reply[1] != 0 {
		return fmt.Errorf("socks connect refused: %d", reply[1])
	}
	payload := bytes.Repeat([]byte("olcrtc peer release "), size/20)
	go func() { _, _ = conn.Write(payload) }()
	got := make([]byte, len(payload))
	if _, err := io.ReadFull(conn, got); err != nil {
		return fmt.Errorf("read back: %w", err)
	}
	if !bytes.Equal(got, payload) {
		return errors.New("echo mismatch")
	}
	return nil
}

// TestServerReleasesDepartedClientEpochs: clients join, move traffic and
// leave; the server ends each session on liveness. Every departed epoch's KCP
// sessions and writer pump must go with it, not three minutes later.
func TestServerReleasesDepartedClientEpochs(t *testing.T) {
	if testing.Short() {
		t.Skip("runs real server and client sessions through a liveness timeout")
	}
	t.Parallel()
	const clients = 4
	name, room := newMemRoom(t)
	// One missed pong ends a session; 16 s is just above the floor a
	// control-plane transport raises shorter timeouts to.
	closed := startRoomServer(t.Context(), t, name, room,
		control.Config{Interval: 250 * time.Millisecond, Timeout: 16 * time.Second, Failures: 1})
	echo := startEcho(t)
	for i := range clients {
		addr, stop := startRoomClient(t.Context(), t, name, "departing-"+strconv.Itoa(i))
		if err := echoThrough(addr, echo, 256*1024); err != nil {
			t.Fatalf("client %d: %v", i, err)
		}
		stop()
	}
	for i := range clients {
		select {
		case line := <-closed:
			t.Logf("server closed %s", line)
		case <-time.After(60 * time.Second):
			t.Fatalf("server closed %d of %d sessions", i, clients)
		}
	}
	srv := room.server.Load()
	// Released on the spot when the epoch was already silent, else by the
	// sweep once it is. A client whose CLOSE reached the server leaves a late
	// data frame behind often enough, and the session that frame rebuilds
	// waits 60 s for a handshake: the bound stays well under that.
	start := time.Now()
	deadline := start.Add(peerSweepInterval + retiredPeerIdle + 15*time.Second)
	for srv.peers.len() > 0 {
		if time.Now().After(deadline) {
			t.Fatalf("the transport still holds %d departed epochs", srv.peers.len())
		}
		time.Sleep(100 * time.Millisecond)
	}
	t.Logf("departed epochs released %s after the last session closed", time.Since(start).Round(100*time.Millisecond))
}

// TestClientRetryOnTheSameEpochRecovers: after a provider rebuild the client
// handshakes on its new epoch but does not hear the server for longer than
// one attempt. The attempt times out and closes its streams, which ends the
// server's session for that epoch; the client's next attempt runs on the same
// epoch and must still be answered.
func TestClientRetryOnTheSameEpochRecovers(t *testing.T) {
	if testing.Short() {
		t.Skip("waits out a handshake timeout")
	}
	t.Parallel()
	name, room := newMemRoom(t)
	startRoomServer(t.Context(), t, name, room, control.Config{})
	echo := startEcho(t)
	addr, stop := startRoomClient(t.Context(), t, name, "retrying")
	defer stop()
	if err := echoThrough(addr, echo, 16*1024); err != nil {
		t.Fatalf("before the rebuild: %v", err)
	}
	member := room.clients()[0]
	member.deaf.Store(true)
	member.Reconnect("test")
	time.Sleep(17 * time.Second)
	member.deaf.Store(false)
	deadline := time.Now().Add(60 * time.Second)
	for {
		err := echoThrough(addr, echo, 16*1024)
		if err == nil {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("the client never recovered: %v", err)
		}
		time.Sleep(time.Second)
	}
}
