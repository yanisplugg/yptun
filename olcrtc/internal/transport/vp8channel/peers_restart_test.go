package vp8channel

import (
	"context"
	"sync/atomic"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/transport"
)

// ai-generated: the whole file (olcrtc#19).
//
// A server whose provider rebuilds rotates its epoch, and a client bound to
// the old one reads frames under the new one as the restart they are. The
// server's per-peer sessions used to go on under the epoch they were created
// with, so a client whose own provider was fine kept seeing its peer alive
// and waited out the control liveness window instead.

const restartTestChannel = "restart-test"

func newRestartTestTransport(stream *fakeVideoStream, cfg transport.Config) *streamTransport {
	cfg.ChannelID = restartTestChannel
	return newStreamTransport(stream, nil, cfg, Options{FPS: 200, BatchSize: 1})
}

// srcTo returns the src of the first frame addressed to dst that next
// yields within a second.
func srcTo(t *testing.T, next <-chan []byte, dst uint32) uint32 {
	t.Helper()
	timeout := time.After(time.Second)
	for {
		select {
		case frame := <-next:
			if _, src, got, ok := parseEpochHeader(frame); ok && got == dst {
				return src
			}
		case <-timeout:
			t.Fatalf("no frame to 0x%08x", dst)
			return 0
		}
	}
}

func TestServerRestartReaddressesPeerSessions(t *testing.T) {
	const client uint32 = 0x0222
	srv := newRestartTestTransport(&fakeVideoStream{canSend: true}, transport.Config{
		OnPeerData: func(string, []byte) {},
	})
	// The peer's pump writes its data frames here; control frames stay on
	// the shared control queue, which only the writer loop of a connected
	// transport drains.
	samples := make(chan []byte, 64)
	srv.sampleWriter = func(sample []byte) bool {
		select {
		case samples <- append([]byte(nil), sample...):
		default:
		}
		return true
	}
	defer func() { _ = srv.Close() }()

	sess := srv.peerSessionFor(client)
	control := srv.peerControlFor(client)
	if sess == nil || control == nil {
		t.Fatal("peer session was not created")
	}
	srv.restartPlanes()
	restarted := srv.localEpochValue()

	if err := sess.data.send([]byte("data")); err != nil {
		t.Fatalf("data send error = %v", err)
	}
	if src := srcTo(t, samples, client); src != restarted {
		t.Fatalf("per-peer data frames carry src 0x%08x after the restart, want the new epoch 0x%08x", src, restarted)
	}
	controlFrames := make(chan []byte, 64)
	stop := make(chan struct{})
	defer close(stop)
	go func() {
		for {
			select {
			case <-stop:
				return
			case packet := <-srv.control.out:
				controlFrames <- append([]byte(nil), packet.data...)
				packet.release()
			}
		}
	}()
	if err := control.send([]byte("control")); err != nil {
		t.Fatalf("control send error = %v", err)
	}
	if src := srcTo(t, controlFrames, client|controlEpochFlag); src != restarted|controlEpochFlag {
		t.Fatalf("per-peer control frames carry src 0x%08x after the restart, want 0x%08x",
			src, restarted|controlEpochFlag)
	}
}

// The two ends wired as the SFU would: every frame one writes reaches the
// other. The server is sending its client data when its provider rebuilds.
// The client's acks still go to the old epoch and the server now drops them,
// so its KCP goes on sending, as it does after the real thing. A client whose
// control plane has gone quiet must read that as the restart it is and
// rebuild its provider; while the server was up it must not.
func TestClientSeesServerRestartThroughPeerTraffic(t *testing.T) {
	clientStream := &fakeVideoStream{canSend: true}
	client := newRestartTestTransport(clientStream, transport.Config{})
	client.peerRestartGrace = 50 * time.Millisecond
	srv := newRestartTestTransport(&fakeVideoStream{canSend: true}, transport.Config{
		OnPeerData: func(string, []byte) {},
	})
	var wired atomic.Bool
	wired.Store(true)
	wire := func(to *streamTransport) func([]byte) bool {
		return func(sample []byte) bool {
			if wired.Load() {
				to.handleIncomingFrame(append([]byte(nil), sample...))
			}
			return true
		}
	}
	srv.sampleWriter = wire(client)
	client.sampleWriter = wire(srv)
	for _, tr := range []*streamTransport{srv, client} {
		if err := tr.Connect(context.Background()); err != nil {
			t.Fatalf("Connect() error = %v", err)
		}
	}
	defer func() {
		wired.Store(false)
		_ = srv.Close()
		_ = client.Close()
	}()

	// The server has served this client, and the handshake bound the client
	// to the server's epoch.
	if srv.peerSessionFor(client.localEpochValue()) == nil {
		t.Fatal("server has no session for the client")
	}
	if err := client.ConfirmPeer(srv.LocalPeerID()); err != nil {
		t.Fatalf("ConfirmPeer() error = %v", err)
	}
	client.NotifyLinkHealth(true)

	stop := make(chan struct{})
	defer close(stop)
	go func() {
		ticker := time.NewTicker(5 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-stop:
				return
			case <-ticker.C:
				_ = srv.SendTo(client.LocalPeerID(), []byte("download"))
			}
		}
	}()

	time.Sleep(300 * time.Millisecond)
	if got := clientStream.reconnects.Load(); got != 0 {
		t.Fatalf("client rebuilt its provider %d times while the server was up, want 0", got)
	}

	srv.restartPlanes()
	deadline := time.Now().Add(2 * time.Second)
	for clientStream.reconnects.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if got := clientStream.reconnects.Load(); got != 1 {
		t.Fatalf("client rebuilt its provider %d times after the server restarted, want 1", got)
	}
}

// A restart waits for a peer session that is being created, so the session
// either lands in the table for readdressPeers to find or reads the epoch the
// restart has just installed. Without that, a session created while the
// restart ran kept the old epoch and went on vouching for a peer that had
// restarted (olcrtc#19, review of #20).
//
// ai-generated: the whole test.
func TestRestartWaitsForAPeerSessionBeingCreated(t *testing.T) {
	srv := newRestartTestTransport(&fakeVideoStream{canSend: true}, transport.Config{
		OnPeerData: func(string, []byte) {},
	})
	srv.sampleWriter = func([]byte) bool { return true }
	defer func() { _ = srv.Close() }()
	if srv.peerSessionFor(0x0333) == nil {
		t.Fatal("peer session was not created")
	}

	// A session is being built right now: peerSessionFor holds createMu from
	// before it reads the epoch until after it installs the session.
	srv.peers.createMu.Lock()
	done := make(chan struct{})
	go func() {
		srv.restartPlanes()
		close(done)
	}()
	select {
	case <-done:
		srv.peers.createMu.Unlock()
		t.Fatal("the restart readdressed the peers while one was being created")
	case <-time.After(100 * time.Millisecond):
	}
	srv.peers.createMu.Unlock()

	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("the restart did not finish once the session was in the table")
	}
}
