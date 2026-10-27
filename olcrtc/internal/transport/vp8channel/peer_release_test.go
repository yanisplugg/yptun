package vp8channel

import (
	"bytes"
	"runtime"
	"sync"
	"testing"
	"time"
)

// ai-generated: create real KCP pairs without an external media provider.
func releaseTestTransport(t *testing.T) *streamTransport {
	t.Helper()
	p := &streamTransport{
		stream: &fakeVideoStream{}, closeCh: make(chan struct{}),
		frameInterval: time.Hour, localEpoch: 1, serverMode: true,
		data: newKCPPlane(1, nil), control: newKCPPlane(4096, nil),
	}
	t.Cleanup(func() { _ = p.Close() })
	return p
}

// silence backdates the last frame heard from epoch by d.
// ai-generated: the whole helper.
func silence(p *streamTransport, epoch uint32, d time.Duration) {
	p.peers.mu.Lock()
	if sess := p.peers.sessions[epoch]; sess != nil {
		sess.lastSeen = time.Now().Add(-d).UnixNano()
	}
	p.peers.mu.Unlock()
}

// ai-generated: an ended, silent epoch is released and its neighbor keeps working.
func TestRetirePeerReleasesSilentEpochAndKeepsNeighbor(t *testing.T) {
	p := releaseTestTransport(t)
	gone := p.peerSessionFor(2)
	p.peerControlFor(2)
	live := p.peerSessionFor(3)
	ctrl := p.peerControlFor(3)
	silence(p, 2, time.Minute)
	p.RetirePeer(formatPeerID(2))
	p.RetirePeer(formatPeerID(2))
	for name, done := range map[string]<-chan struct{}{
		"writer pump": gone.done, "data KCP": gone.data.conn.closed,
	} {
		select {
		case <-done:
		default:
			t.Fatalf("%s of the ended epoch is still running", name)
		}
	}
	if p.peers.lookup(2) != nil {
		t.Fatal("ended epoch is still in the peer table")
	}
	if p.peerSessionFor(3) != live || p.peerControlFor(3) != ctrl {
		t.Fatal("neighbor runtime was replaced")
	}
	assertReleaseNeighborTraffic(t, live)
}

// ai-generated: transfer an actual application message over the surviving KCP.
func assertReleaseNeighborTraffic(t *testing.T, live *peerSession) {
	t.Helper()
	received := make(chan []byte, 1)
	out := make(chan *packetBuffer, 64)
	client, err := startKCP(out, func(b []byte) { received <- b }, testEpochHdr(3), 0)
	if err != nil {
		t.Fatal(err)
	}
	defer client.abortPeer()
	stop := make(chan struct{})
	defer close(stop)
	go pumpPackets(stop, live.out, client)
	go pumpPackets(stop, out, live.data)
	want := []byte("neighbor survives another session closing")
	if err := live.data.send(want); err != nil {
		t.Fatal(err)
	}
	select {
	case got := <-received:
		if !bytes.Equal(got, want) {
			t.Fatal("neighbor payload changed")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("neighbor traffic stalled")
	}
}

// ai-generated: an ended epoch still heard from keeps its KCP state, because the
// client retries its handshake on it, and goes once it falls silent.
func TestRetirePeerKeepsAnEpochStillHeard(t *testing.T) {
	p := releaseTestTransport(t)
	sess := p.peerSessionFor(2)
	control := p.peerControlFor(2)
	p.RetirePeer(formatPeerID(2))
	p.handlePeerFrame(2, nil)
	if p.peerSessionFor(2) != sess || p.peerControlFor(2) != control {
		t.Fatal("an epoch still heard from lost its KCP state")
	}
	p.peers.sweep(peerIdleTTL, retiredPeerIdle)
	if p.peers.lookup(2) != sess {
		t.Fatal("the sweep released an ended epoch that is still heard from")
	}
	silence(p, 2, 2*retiredPeerIdle)
	p.peers.sweep(peerIdleTTL, retiredPeerIdle)
	if p.peers.lookup(2) != nil {
		t.Fatal("an ended, silent epoch waited for the idle TTL")
	}
}

// ai-generated: a control send (a new session's welcome or ping) claims an ended
// epoch back, so only the idle TTL applies to it again; a data send, which a
// session rebuilt from a late frame makes with its smux keepalive, does not.
func TestOnlyAControlSendClaimsAnEndedEpoch(t *testing.T) {
	p := releaseTestTransport(t)
	p.peerSessionFor(2)
	p.peerControlFor(2)
	p.peerSessionFor(3)
	p.RetirePeer(formatPeerID(2))
	p.RetirePeer(formatPeerID(3))
	if err := p.ControlSendTo(formatPeerID(2), []byte("welcome")); err != nil {
		t.Fatalf("ControlSendTo: %v", err)
	}
	if err := p.SendTo(formatPeerID(3), []byte("keepalive")); err != nil {
		t.Fatalf("SendTo: %v", err)
	}
	silence(p, 2, 2*retiredPeerIdle)
	silence(p, 3, 2*retiredPeerIdle)
	p.peers.sweep(peerIdleTTL, retiredPeerIdle)
	if p.peers.lookup(2) == nil {
		t.Fatal("an epoch claimed by a control send was released on the ended-session timeout")
	}
	if p.peers.lookup(3) != nil {
		t.Fatal("a data send claimed an ended epoch")
	}
}

// ai-generated: sends neither build a peer nor keep a silent one alive.
func TestSendPathNeverBuildsOrRefreshesAPeer(t *testing.T) {
	p := releaseTestTransport(t)
	if err := p.ControlSendTo(formatPeerID(7), []byte("x")); err == nil {
		t.Fatal("control send to an unknown peer succeeded")
	}
	if err := p.SendTo(formatPeerID(7), []byte("x")); err == nil {
		t.Fatal("data send to an unknown peer succeeded")
	}
	if n := p.peers.len(); n != 0 {
		t.Fatalf("sends built %d peers", n)
	}
	p.peerSessionFor(2)
	silence(p, 2, time.Minute)
	_ = p.ControlSendTo(formatPeerID(2), []byte("ping"))
	_ = p.SendTo(formatPeerID(2), []byte("data"))
	_ = p.ControlPeerCanSend(formatPeerID(2))
	p.peers.sweep(30*time.Second, retiredPeerIdle)
	if p.peers.lookup(2) != nil {
		t.Fatal("the server's own writes kept a silent peer alive")
	}
}

// ai-generated: a full queue must not deadlock release or whole-room shutdown.
func TestPeerRetirementUnblocksFullQueue(t *testing.T) {
	p := releaseTestTransport(t)
	sess := p.peerSessionFor(2)
	for range cap(sess.out) {
		sess.out <- &packetBuffer{data: []byte{1}}
	}
	wrote := make(chan struct{})
	go func() {
		_, _ = sess.data.conn.WriteTo([]byte("blocked"), fakeUDPAddr())
		close(wrote)
	}()
	finished := make(chan struct{})
	go func() {
		silence(p, 2, time.Minute)
		p.RetirePeer(formatPeerID(2))
		_ = p.Close()
		close(finished)
	}()
	for _, done := range []<-chan struct{}{wrote, finished} {
		select {
		case <-done:
		case <-time.After(3 * time.Second):
			t.Fatal("full outbound queue blocked peer shutdown")
		}
	}
}

// ai-generated: inbound frames, retirement and the sweep race without leaking: the
// table's session is open, every session it dropped is closed.
func TestPeerRetirementRacesInboundFrames(t *testing.T) {
	p := releaseTestTransport(t)
	var (
		mu   sync.Mutex
		seen = make(map[*peerSession]struct{})
		work sync.WaitGroup
	)
	stop := make(chan struct{})
	for range 4 {
		work.Go(func() {
			for {
				select {
				case <-stop:
					return
				default:
				}
				p.handlePeerFrame(2, nil)
				p.handleControlFrame(2|controlEpochFlag, 0, []byte{0})
				if sess := p.peerSessionFor(2); sess != nil {
					mu.Lock()
					seen[sess] = struct{}{}
					mu.Unlock()
				}
			}
		})
	}
	work.Go(func() {
		for range 200 {
			silence(p, 2, time.Minute)
			p.RetirePeer(formatPeerID(2))
			p.peers.sweep(peerIdleTTL, retiredPeerIdle)
		}
		close(stop)
	})
	work.Wait()
	current := p.peers.lookup(2)
	for sess := range seen {
		select {
		case <-sess.done:
			if sess == current {
				t.Fatal("the table holds a closed session")
			}
		default:
			if sess != current {
				t.Fatal("a session dropped from the table is still open")
			}
		}
	}
}

// ai-generated: 100 departed epochs leave no KCP, queue or writer pump behind.
func TestRepeatedSessionReleaseBoundsKCPObjects(t *testing.T) {
	p := releaseTestTransport(t)
	base := runtime.NumGoroutine()
	for epoch := uint32(2); epoch < 102; epoch++ {
		p.peerSessionFor(epoch)
		p.peerControlFor(epoch)
		silence(p, epoch, time.Minute)
		p.RetirePeer(formatPeerID(epoch))
	}
	if n := p.peers.len(); n != 0 {
		t.Fatalf("after 100 departures: peers=%d", n)
	}
	deadline := time.Now().Add(5 * time.Second)
	for runtime.NumGoroutine() > base+2 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if n := runtime.NumGoroutine(); n > base+2 {
		t.Fatalf("goroutines after 100 departures = %d, baseline %d", n, base)
	}
}
