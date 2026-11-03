package server

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/xtaci/smux"

	"github.com/openlibrecommunity/olcrtc/internal/control"
	cryptopkg "github.com/openlibrecommunity/olcrtc/internal/crypto"
	"github.com/openlibrecommunity/olcrtc/internal/framing"
	"github.com/openlibrecommunity/olcrtc/internal/handshake"
	"github.com/openlibrecommunity/olcrtc/internal/muxconn"
	"github.com/openlibrecommunity/olcrtc/internal/runtime"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
	"github.com/openlibrecommunity/olcrtc/internal/tunnelcore"
)

// ai-generated: the whole file (a client that retries its handshake under the
// same relay identity, olcrtc#49).

// peerTestLiveness pings too seldom to put a ping on the wire while a test
// counts what the server writes.
var peerTestLiveness = control.Config{Interval: time.Hour}

// relayLinkStub is a peer-routing transport without a control plane of its
// own - datachannel over a relay that stamps sender identities - whose sends
// to a peer go to toPeer.
type relayLinkStub struct {
	peerRoutingStub
	features transport.Features
	mu       sync.Mutex
	toPeer   func(peerID string, data []byte)
}

func (l *relayLinkStub) Features() transport.Features { return l.features }

func (l *relayLinkStub) SendTo(peerID string, data []byte) error {
	l.mu.Lock()
	deliver := l.toPeer
	l.mu.Unlock()
	if deliver != nil {
		deliver(peerID, append([]byte(nil), data...))
	}
	return nil
}

// newRelayServer is a server routing peers over link, the way Run sets one up
// for datachannel.
func newRelayServer(t *testing.T, link *relayLinkStub) *Server {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	s := &Server{
		baseCtx: ctx, ln: link, peerLn: link, ring: cryptopkg.SingleEntry(newServerTestKeys(t), ""),
		authHook: defaultAuthHook, onOpen: func(string, string, map[string]any) {},
		onClose: func(string, string) {}, health: runtime.NewHealthTracker(nil),
		liveness:     peerTestLiveness,
		peerSessions: make(map[string]*peerSession), peerStats: make(map[string]peerStat),
		done: make(chan struct{}), meter: newMeter(),
	}
	t.Cleanup(func() {
		cancel()
		s.shutdown()
		s.wg.Wait()
	})
	return s
}

// relayClient is one client smux session under peerID, over its own conn: a
// client builds a new one for every handshake it tries.
type relayClient struct {
	conn    *muxconn.Conn
	session *smux.Session
}

// clientLinkStub is the client's side: what it sends reaches the server as
// the peer's.
type clientLinkStub struct {
	serverLinkStub
	features transport.Features
	send     func([]byte)
}

func (l *clientLinkStub) Features() transport.Features { return l.features }

func (l *clientLinkStub) Send(data []byte) error {
	l.send(append([]byte(nil), data...))
	return nil
}

func newRelayClient(t *testing.T, s *Server, peerID string) *relayClient {
	t.Helper()
	keys, err := cryptopkg.NewKeySet([]byte("01234567890123456789012345678901"), cryptopkg.Client)
	if err != nil {
		t.Fatal(err)
	}
	link := &clientLinkStub{features: s.ln.Features(), send: func(data []byte) { s.onPeerData(peerID, data) }}
	conn := muxconn.New(link, keys)
	session, err := tunnelcore.NewSession(conn, tunnelcore.ClientRole, runtime.SmuxConfigFor(link))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_ = session.Close()
		_ = conn.Close()
	})
	return &relayClient{conn: conn, session: session}
}

// handshake opens the client's control stream and runs its hello.
func (c *relayClient) handshake(t *testing.T) *smux.Stream {
	t.Helper()
	stream, err := c.session.OpenStream()
	if err != nil {
		t.Fatalf("OpenStream() error = %v", err)
	}
	_ = stream.SetDeadline(time.Now().Add(5 * time.Second))
	if _, _, err := handshake.Client(stream, "relay-client", nil); err != nil {
		t.Fatalf("handshake.Client() error = %v", err)
	}
	_ = stream.SetDeadline(time.Time{})
	return stream
}

// A client that closes its control stream has left that session: nothing the
// server writes into it now reaches the client, and what it did write would
// land in the session the client runs next under the same relay identity -
// on the stream with the number this one had, which is where a retried hello
// runs. The server ends such a session without a word, and reports it closed
// as left: liveness did not end it.
func TestAPeerThatLeftItsSessionIsToldNothing(t *testing.T) {
	link := &relayLinkStub{}
	s := newRelayServer(t, link)
	reasons := make(chan string, 4)
	s.onClose = func(_, reason string) { reasons <- reason }
	client := newRelayClient(t, s, "peer")
	var sent atomic.Int64
	link.mu.Lock()
	link.toPeer = func(_ string, data []byte) {
		sent.Add(1)
		client.conn.Push(data)
	}
	link.mu.Unlock()

	stream := client.handshake(t)
	peer := lookupPeer(s, "peer")
	if peer == nil {
		t.Fatal("the server built no session for the peer")
	}
	waitPeerControl(t, peer)
	before := sent.Load()
	_ = stream.Close()

	deadline := time.Now().Add(2 * time.Second)
	for lookupPeer(s, "peer") == peer {
		if time.Now().After(deadline) {
			t.Fatal("the server kept the session its peer left")
		}
		time.Sleep(2 * time.Millisecond)
	}
	time.Sleep(50 * time.Millisecond)
	if extra := sent.Load() - before; extra != 0 {
		t.Fatalf("the server wrote %d records to a peer that had left the session", extra)
	}
	select {
	case reason := <-reasons:
		if reason != "left" {
			t.Fatalf("the session its peer left was reported closed with reason %q, want %q", reason, "left")
		}
	default:
		t.Fatal("the session its peer left was never reported closed")
	}
}

// A peer's handshake that fails is that peer's failure: the server ends that
// peer's session and nobody else's. Reinstalling peer routing for it - the
// path the server's own session takes - closed every other client's session
// with it, and a client that gives a handshake up under its relay identity
// leaves the server just such a handshake to fail.
func TestAFailedPeerHandshakeLeavesTheOtherPeersAlone(t *testing.T) {
	link := &relayLinkStub{}
	s := newRelayServer(t, link)
	other := newRelayClient(t, s, "other")
	failing := newRelayClient(t, s, "failing")
	clients := map[string]*relayClient{"other": other, "failing": failing}
	link.mu.Lock()
	link.toPeer = func(peerID string, data []byte) {
		if client := clients[peerID]; client != nil {
			client.conn.Push(data)
		}
	}
	link.mu.Unlock()
	other.handshake(t)
	kept := lookupPeer(s, "other")
	if kept == nil {
		t.Fatal("the server built no session for the other peer")
	}

	// A peer whose session goes away before its hello is read.
	if _, err := failing.session.OpenStream(); err != nil {
		t.Fatal(err)
	}
	peer := lookupPeer(s, "failing")
	if peer == nil {
		t.Fatal("the server built no session for the failing peer")
	}
	_ = peer.dataSession().Close()

	deadline := time.Now().Add(2 * time.Second)
	for lookupPeer(s, "failing") == peer {
		if time.Now().After(deadline) {
			t.Fatal("the server kept the session whose handshake failed")
		}
		time.Sleep(2 * time.Millisecond)
	}
	if got := lookupPeer(s, "other"); got != kept {
		t.Fatal("a failed handshake of one peer ended another peer's session")
	}
	if kept.sid() == "" || kept.dataSession().IsClosed() {
		t.Fatal("the other peer's session was closed")
	}
}

// waitPeerControl waits until the server runs peer's control loop.
func waitPeerControl(t *testing.T, peer *peerSession) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for {
		peer.mu.Lock()
		running := peer.controlStrm != nil
		peer.mu.Unlock()
		if running {
			return
		}
		if time.Now().After(deadline) {
			t.Fatal("the server never started the peer's control loop")
		}
		time.Sleep(2 * time.Millisecond)
	}
}

// A peer that starts its session over is answered by a session of its own at
// once, from the record the new session begins with - its first stream and
// the hello on it, one record - in the pin group of the session it left: the
// key it holds is the key it held, and a new session that had to learn it
// from the next record would sit on the welcome until the peer sent one.
func TestAPeerThatStartsItsSessionOverIsAnsweredAtOnce(t *testing.T) {
	link := &relayLinkStub{}
	s := newRelayServer(t, link)
	client := newRelayClient(t, s, "peer")
	welcomed := make(chan []byte, 16)
	link.mu.Lock()
	link.toPeer = func(_ string, data []byte) {
		client.conn.Push(data)
		welcomed <- data
	}
	link.mu.Unlock()
	client.handshake(t)
	left := lookupPeer(s, "peer")
	if left == nil {
		t.Fatal("the server built no session for the peer")
	}
	for len(welcomed) > 0 {
		<-welcomed
	}

	// The retry: a new smux session's first stream, opened again, and the
	// hello on it, in one record.
	keys, err := cryptopkg.NewKeySet([]byte("01234567890123456789012345678901"), cryptopkg.Client)
	if err != nil {
		t.Fatal(err)
	}
	var hello bytes.Buffer
	err = framing.WriteJSON(&hello, handshake.Hello{
		Version: handshake.ProtoVersion, Type: handshake.TypeHello, DeviceID: "relay-client",
		Challenge: strings.Repeat("ab", 16),
	}, handshake.MaxMessageSize)
	if err != nil {
		t.Fatal(err)
	}
	version := byte(runtime.SmuxConfigFor(link).Version) //nolint:gosec // smux versions are 1 and 2
	record := append(smuxFrameV(version, 0, 3, nil), smuxFrameV(version, 2, 3, hello.Bytes())...)
	sealed, err := keys.Seal(record, []byte("olcrtc/muxconn/v2/data"))
	if err != nil {
		t.Fatal(err)
	}
	s.onPeerData("peer", sealed)

	deadline := time.After(2 * time.Second)
	for {
		select {
		case data := <-welcomed:
			opened, err := keys.Open(data, []byte("olcrtc/muxconn/v2/data"))
			if err == nil && bytes.Contains(opened, []byte(handshake.TypeWelcome)) {
				if next := lookupPeer(s, "peer"); next == left || next == nil {
					t.Fatal("the welcome came from the session the peer left")
				}
				return
			}
		case <-deadline:
			t.Fatal("the server did not answer the hello a peer started its session over with")
		}
	}
}

// smuxFrameV lays one smux frame out the way xtaci/smux writes it.
func smuxFrameV(version, cmd byte, sid uint32, data []byte) []byte {
	frame := make([]byte, 8, 8+len(data))
	frame[0], frame[1] = version, cmd
	binary.LittleEndian.PutUint16(frame[2:4], uint16(len(data))) //nolint:gosec // test frames are small
	binary.LittleEndian.PutUint32(frame[4:8], sid)
	return append(frame, data...)
}

// datachannelFeatures are the link features datachannel asks for: records of
// 12 KiB, and small frames batched for 5 ms.
var datachannelFeatures = transport.Features{MaxPayloadSize: 12 * 1024, WriteInterval: 5 * time.Millisecond}

// A client opens streams at once - a tunnel per SOCKS connection, each on its
// own goroutine, and every request parked on a session that has just come up -
// and smux writes their SYNs in no set order. A SYN below one the server has
// seen is still that session's: the peer has not started its session over, and
// the session it is on stays.
func TestStreamsOpenedAtOnceStayOnThePeersSession(t *testing.T) {
	link := &relayLinkStub{features: datachannelFeatures}
	s := newRelayServer(t, link)
	var restarted atomic.Int64
	s.onClose = func(_, reason string) {
		if reason == "restarted" {
			restarted.Add(1)
		}
	}
	client := newRelayClient(t, s, "peer")
	link.mu.Lock()
	link.toPeer = func(_ string, data []byte) { client.conn.Push(data) }
	link.mu.Unlock()
	client.handshake(t)
	peer := lookupPeer(s, "peer")
	if peer == nil {
		t.Fatal("the server built no session for the peer")
	}
	waitPeerControl(t, peer)

	// Uploads keep the client's send path busy while the streams open.
	chunk := bytes.Repeat([]byte{0x5a}, 32*1024)
	for range 3 {
		go func() {
			stream, err := client.session.OpenStream()
			if err != nil {
				return
			}
			for {
				if _, err := stream.Write(chunk); err != nil {
					return
				}
			}
		}()
	}
	const bursts, opens = 4, 32
	for range bursts {
		start := make(chan struct{})
		var wg sync.WaitGroup
		for range opens {
			wg.Add(1)
			go func() {
				defer wg.Done()
				<-start
				_, _ = client.session.OpenStream()
			}()
		}
		close(start)
		wg.Wait()
	}

	deadline := time.Now().Add(2 * time.Second)
	for lookupPeer(s, "peer") == peer && peer.dataSession().NumStreams() < bursts*opens {
		if time.Now().After(deadline) {
			t.Fatalf("the server holds %d of the %d streams opened", peer.dataSession().NumStreams(), bursts*opens)
		}
		time.Sleep(2 * time.Millisecond)
	}
	if lookupPeer(s, "peer") != peer || restarted.Load() != 0 {
		t.Fatal("streams opened at once were taken for the peer starting its session over")
	}
}

// A peer that liveness ends on a stalled link - the relay window toward it
// full, the conn's flusher parked in SendTo, smux's send loop stuck behind it -
// is told it is closed as far as the link lets it, and no further: the close
// notice has a budget, and the session is closed when it runs out. Queued
// behind that send loop, the notice waited out its write deadline, and the FIN
// of CloseWrite and the FIN of Close each waited out smux's 30 s stream close
// timeout, a minute in which onClose and retirePeer did not run. Once the link
// moved again, all of it went to the peer's identity: onto stream 3 of the
// session it runs next there, where its retried hello or its new control
// stream is.
func TestAPeerEndedOnAStalledLinkIsClosedWithinTheNoticeBudget(t *testing.T) {
	link := &relayLinkStub{features: datachannelFeatures}
	s := newRelayServer(t, link)
	s.liveness = control.Config{Interval: 50 * time.Millisecond, Timeout: 150 * time.Millisecond}
	closed := make(chan struct{}, 1)
	s.onClose = func(string, string) {
		select {
		case closed <- struct{}{}:
		default:
		}
	}
	client := newRelayClient(t, s, "peer")
	release := make(chan struct{})
	var releaseOnce sync.Once
	releaseLink := func() { releaseOnce.Do(func() { close(release) }) }
	t.Cleanup(releaseLink)
	var (
		stalled atomic.Bool
		lateMu  sync.Mutex
		late    [][]byte
	)
	link.mu.Lock()
	link.toPeer = func(_ string, data []byte) {
		if !stalled.Load() {
			client.conn.Push(data)
			return
		}
		<-release
		lateMu.Lock()
		late = append(late, data)
		lateMu.Unlock()
	}
	link.mu.Unlock()

	stream := client.handshake(t)
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	go func() { _ = control.Run(ctx, stream, control.Config{Interval: time.Hour}) }()
	peer := lookupPeer(s, "peer")
	if peer == nil {
		t.Fatal("the server built no session for the peer")
	}
	waitPeerControl(t, peer)

	// The link stalls with a batch due: bulk on a stream of the server's.
	stalled.Store(true)
	bulk, err := peer.dataSession().OpenStream()
	if err != nil {
		t.Fatalf("OpenStream() error = %v", err)
	}
	go func() { _, _ = bulk.Write(make([]byte, 256*1024)) }()

	deadline := time.Now().Add(5 * time.Second)
	for lookupPeer(s, "peer") == peer {
		if time.Now().After(deadline) {
			t.Fatal("liveness never ended the peer on a stalled link")
		}
		time.Sleep(2 * time.Millisecond)
	}
	removed := time.Now()
	const budget = 3 * time.Second
	select {
	case <-closed:
	case <-time.After(budget):
	}
	took := time.Since(removed)
	releaseLink()
	time.Sleep(300 * time.Millisecond)

	if took >= budget {
		t.Errorf("the session was reported closed %v after its peer was ended, want under %v", took, budget)
	}
	keys, err := cryptopkg.NewKeySet([]byte("01234567890123456789012345678901"), cryptopkg.Client)
	if err != nil {
		t.Fatal(err)
	}
	lateMu.Lock()
	defer lateMu.Unlock()
	for _, record := range late {
		opened, err := keys.Open(record, []byte("olcrtc/muxconn/v2/data"))
		if err != nil {
			continue
		}
		for _, frame := range smuxFrames(opened) {
			switch {
			case frame.sid != 3:
			case frame.cmd == smuxFIN:
				t.Error("a FIN for stream 3 reached the peer after the link moved again")
			case bytes.Contains(frame.data, []byte(control.TypeClose)):
				t.Error("CONTROL_CLOSE reached the peer after the link moved again")
			}
		}
	}
}

// smuxFIN is smux's stream close command.
const smuxFIN = 1

type smuxFrameView struct {
	cmd  byte
	sid  uint32
	data []byte
}

// smuxFrames splits a record into the smux frames it carries.
func smuxFrames(record []byte) []smuxFrameView {
	var frames []smuxFrameView
	for len(record) >= 8 {
		body := min(int(binary.LittleEndian.Uint16(record[2:4])), len(record)-8)
		frames = append(frames, smuxFrameView{
			cmd: record[1], sid: binary.LittleEndian.Uint32(record[4:8]), data: record[8 : 8+body],
		})
		record = record[8+body:]
	}
	return frames
}

// errHeldSendEnded is what epochLinkStub answers a send held across a
// retirement with, as the SaluteJazz engine answers ErrDestinationEnded.
var errHeldSendEnded = errors.New("destination ended while held")

// epochLinkStub is relayLinkStub with the part of the SaluteJazz relay window
// the server's teardown order answers to. While held, a send to the peer
// waits, as one does on a full window; the send takes the peer's epoch when it
// begins, and one still held when the server retires the peer is refused: its
// frame was the session's that ended. A send begun after the retirement goes
// once the hold lifts.
type epochLinkStub struct {
	relayLinkStub
	emu     sync.Mutex
	moved   *sync.Cond
	held    bool
	epoch   uint64
	entered int
	parked  int
}

func newEpochLinkStub() *epochLinkStub {
	l := &epochLinkStub{relayLinkStub: relayLinkStub{features: datachannelFeatures}}
	l.moved = sync.NewCond(&l.emu)
	return l
}

func (l *epochLinkStub) SendTo(peerID string, data []byte) error {
	l.emu.Lock()
	epoch := l.epoch
	l.entered++
	for l.held && l.epoch == epoch {
		l.parked++
		l.moved.Wait()
		l.parked--
	}
	ended := l.epoch != epoch
	l.emu.Unlock()
	if ended {
		return errHeldSendEnded
	}
	return l.relayLinkStub.SendTo(peerID, data)
}

// RetirePeer moves the peer's epoch on and refuses every send held on it.
func (l *epochLinkStub) RetirePeer(string) {
	l.emu.Lock()
	l.epoch++
	l.moved.Broadcast()
	l.emu.Unlock()
}

// hold holds every send to the peer from now on, or lets them go.
func (l *epochLinkStub) hold(on bool) {
	l.emu.Lock()
	l.held = on
	l.moved.Broadcast()
	l.emu.Unlock()
}

// sends is how many sends have begun, and how many of them are held now.
func (l *epochLinkStub) sends() (int, int) {
	l.emu.Lock()
	defer l.emu.Unlock()
	return l.entered, l.parked
}

// waitSends waits until cond holds of sends.
func (l *epochLinkStub) waitSends(t *testing.T, what string, cond func(entered, parked int) bool) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for !cond(l.sends()) {
		if time.Now().After(deadline) {
			t.Fatalf("waiting for %s", what)
		}
		time.Sleep(2 * time.Millisecond)
	}
}

// A peer that liveness ends on a slow leg is sent a close notice that the
// full window toward it holds. The client has given the session up by then
// and retries its hello under the same relay identity, and the server builds
// the retry a session at once, while the old one still waits on its notice.
// The old session's retirement has to come before the new session's first
// send: coming after, as it did when the old session retired the peer at the
// end of its own teardown, it refused the new session's welcome, held on the
// same full window, and the retry's hello timed out with the new session dead
// of the refusal.
func TestAHelloRetriedWhileTheOldSessionIsClosingIsAnswered(t *testing.T) {
	link := newEpochLinkStub()
	s := newRelayServer(t, &link.relayLinkStub)
	s.ln, s.peerLn = link, link
	t.Cleanup(func() { link.hold(false) })
	var current atomic.Pointer[relayClient]
	link.mu.Lock()
	link.toPeer = func(_ string, data []byte) {
		if client := current.Load(); client != nil {
			client.conn.Push(data)
		}
	}
	link.mu.Unlock()

	first := newRelayClient(t, s, "peer")
	current.Store(first)
	first.handshake(t)
	old := lookupPeer(s, "peer")
	if old == nil {
		t.Fatal("the server built no session for the peer")
	}
	waitPeerControl(t, old)

	// The window toward the peer fills, and liveness ends the session: its
	// close notice is held.
	link.hold(true)
	ended := make(chan struct{})
	go func() {
		defer close(ended)
		s.removePeer(old, "liveness")
	}()
	link.waitSends(t, "the close notice to be held", func(_, parked int) bool { return parked > 0 })
	before, _ := link.sends()

	// The client retries its hello under the same identity.
	retry := newRelayClient(t, s, "peer")
	current.Store(retry)
	answered := make(chan error, 1)
	go func() {
		stream, err := retry.session.OpenStream()
		if err != nil {
			answered <- err
			return
		}
		_ = stream.SetDeadline(time.Now().Add(5 * time.Second))
		_, _, err = handshake.Client(stream, "relay-client", nil)
		answered <- err
	}()
	link.waitSends(t, "the retry's welcome to be sent", func(entered, _ int) bool { return entered > before })

	// The old session's teardown ends, and the window opens again.
	select {
	case <-ended:
	case <-time.After(5 * time.Second):
		t.Fatal("the old session's teardown never ended")
	}
	link.hold(false)
	select {
	case err := <-answered:
		if err != nil {
			t.Fatalf("the hello retried while the old session was closing = %v, want a welcome", err)
		}
	case <-time.After(6 * time.Second):
		t.Fatal("the hello retried while the old session was closing was never answered")
	}
	next := lookupPeer(s, "peer")
	if next == nil || next == old || next.dataSession().IsClosed() {
		t.Fatal("the session that answered the retried hello did not outlive the old one's teardown")
	}
}
