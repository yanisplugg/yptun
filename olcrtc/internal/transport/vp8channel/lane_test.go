package vp8channel

// ai-generated: the whole file (issue #12: the data lane against an SFU that
// stops forwarding a stream over its budget).

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"hash/crc32"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	kcp "github.com/xtaci/kcp-go/v5"

	"github.com/openlibrecommunity/olcrtc/internal/transport"
)

// budgetRelay is one direction of an SFU the way JVB forwards a stream: it
// measures what the sender pushes at it and, at every allocation, stops
// forwarding all of it while the last window was over the budget, and
// forwards it again once a window is back under. JVB allocates every 5 s;
// what matters is that the window outlasts KCP's retransmission rounds, so
// resending keeps the stream over the budget. Frames reach the receiver on
// their own goroutine, as they would off the network.
type budgetRelay struct {
	budget int // bytes per window; zero forwards everything
	window time.Duration

	mu       sync.Mutex
	arrivals []relayArrival
	dark     bool
	frames   chan []byte
}

type relayArrival struct {
	at   time.Time
	size int
}

func newBudgetRelay(budget int, window time.Duration) *budgetRelay {
	return &budgetRelay{budget: budget, window: window, frames: make(chan []byte, 4096)}
}

// write is the sender's track.
func (r *budgetRelay) write(frame []byte) bool {
	r.mu.Lock()
	r.arrivals = append(r.arrivals, relayArrival{at: time.Now(), size: len(frame)})
	dark := r.dark
	r.mu.Unlock()
	if dark {
		return true
	}
	select {
	case r.frames <- append([]byte(nil), frame...):
	default:
	}
	return true
}

// run re-decides every period and delivers frames to the receiver until ctx ends.
func (r *budgetRelay) run(ctx context.Context, period time.Duration, to func([]byte)) {
	go func() {
		for {
			select {
			case <-ctx.Done():
				return
			case frame := <-r.frames:
				to(frame)
			}
		}
	}()
	ticker := time.NewTicker(period)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case now := <-ticker.C:
			r.allocate(now)
		}
	}
}

func (r *budgetRelay) allocate(now time.Time) {
	r.mu.Lock()
	defer r.mu.Unlock()
	kept, total := r.arrivals[:0], 0
	for _, a := range r.arrivals {
		if now.Sub(a.at) <= r.window {
			kept = append(kept, a)
			total += a.size
		}
	}
	r.arrivals = kept
	r.dark = r.budget > 0 && total > r.budget
}

// newLaneTestTransport is a transport on a relay, with the lane's timers
// scaled down to the relay's.
func newLaneTestTransport(t *testing.T, cfg transport.Config, relay *budgetRelay) *streamTransport {
	t.Helper()
	cfg.ChannelID = "lane-test"
	tr := newStreamTransport(&fakeVideoStream{canSend: true}, nil, cfg, Options{FPS: 50, BatchSize: 8})
	tr.sampleWriter = relay.write
	tr.blackoutAfter = 600 * time.Millisecond
	tr.probeEvery = 100 * time.Millisecond
	tr.growEvery = 500 * time.Millisecond
	if err := tr.Connect(context.Background()); err != nil {
		t.Fatalf("Connect: %v", err)
	}
	t.Cleanup(func() { _ = tr.Close() })
	return tr
}

// TestDataLaneBringsBackAStreamTheSFUStoppedForwarding is issue #12 in
// small: a server pulls to a client through an SFU that stops forwarding the
// server's stream as soon as a burst takes it over its budget. KCP resends
// every unacknowledged segment on each timeout, so a lane that keeps writing
// whatever KCP queues keeps the stream over the budget and dark: the client
// stops receiving for as long as that lasts, which on JVB was minutes. A lane
// that goes quiet once nothing is acknowledged lets the SFU take the stream
// back, and the transfer keeps moving.
func TestDataLaneBringsBackAStreamTheSFUStoppedForwarding(t *testing.T) {
	runLanePair(t, lanePair{toClient: 512 << 10, pull: 768 << 10})
}

// TestDataLanesKeepAnsweringWhileBothAreDark pulls and pushes at once through
// an SFU that polices both directions, so both lanes go dark together, as a
// push started on the tail of a pull did live. A dark lane must still carry
// what answers the peer: holding back acknowledgements too leaves each side
// waiting for the other's, and neither direction comes back.
func TestDataLanesKeepAnsweringWhileBothAreDark(t *testing.T) {
	runLanePair(t, lanePair{toClient: 512 << 10, toServer: 512 << 10, pull: 512 << 10, push: 512 << 10})
}

// lanePair is a server and a client on budget relays: the budgets per
// window each way (zero forwards everything) and what to move each way. A
// budget is above what a lane paces itself down to after a spell, half of
// the 560 KiB/s these options allow, so coming back does not trip it again
// by construction.
type lanePair struct {
	toClient, toServer int
	pull, push         int
}

// runLanePair moves the pair's bytes and fails once nothing has arrived
// either way for five seconds before they all did.
func runLanePair(t *testing.T, pair lanePair) {
	t.Helper()
	const (
		window   = time.Second
		allocate = 500 * time.Millisecond
		stallFor = 5 * time.Second
	)
	toClient := newBudgetRelay(pair.toClient, window)
	toServer := newBudgetRelay(pair.toServer, window)

	var pulled, pushed atomic.Int64
	client := newLaneTestTransport(t, transport.Config{
		OnData: func(b []byte) { pulled.Add(int64(len(b))) },
	}, toServer)
	server := newLaneTestTransport(t, transport.Config{
		OnPeerData: func(_ string, b []byte) { pushed.Add(int64(len(b))) },
	}, toClient)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go toClient.run(ctx, allocate, client.handleIncomingFrame)
	go toServer.run(ctx, allocate, server.handleIncomingFrame)

	if err := client.ConfirmPeer(server.LocalPeerID()); err != nil {
		t.Fatalf("ConfirmPeer: %v", err)
	}
	peer := client.LocalPeerID()
	waitFor(t, 5*time.Second, "the server to see the client", func() bool {
		return server.peers.get(client.localEpochValue()) != nil
	})

	go sendChunks(ctx, pair.pull, func(b []byte) error { return server.SendTo(peer, b) })
	go sendChunks(ctx, pair.push, client.Send)

	want := int64(pair.pull + pair.push)
	last, lastMove := int64(0), time.Now()
	for pulled.Load()+pushed.Load() < want {
		time.Sleep(20 * time.Millisecond)
		if n := pulled.Load() + pushed.Load(); n != last {
			last, lastMove = n, time.Now()
			continue
		}
		if time.Since(lastMove) > stallFor {
			t.Fatalf("stalled: pulled %d of %d, pushed %d of %d, nothing moved for %s",
				pulled.Load(), pair.pull, pushed.Load(), pair.push, stallFor)
		}
	}
}

// sendChunks sends size random bytes through send in 16 KiB messages.
func sendChunks(ctx context.Context, size int, send func([]byte) error) {
	const chunk = 16 << 10
	payload := make([]byte, size)
	_, _ = rand.Read(payload)
	for off := 0; off < size && ctx.Err() == nil; off += chunk {
		if err := send(payload[off:min(off+chunk, size)]); err != nil {
			return
		}
	}
}

// waitFor polls cond until it holds or the timeout passes.
func waitFor(t *testing.T, timeout time.Duration, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

// kcpSegments builds a KCP packet of the given commands, each with body as
// its data.
func kcpSegments(body []byte, cmds ...byte) []byte {
	var packet []byte
	for _, cmd := range cmds {
		hdr := make([]byte, kcp.IKCP_OVERHEAD)
		hdr[kcpCmdOff] = cmd
		binary.LittleEndian.PutUint32(hdr[kcpLenOff:], uint32(len(body))) //nolint:gosec // test body
		packet = append(append(packet, hdr...), body...)
	}
	return packet
}

func TestPushesWalksTheSegmentsAndStopsAtAHostileLength(t *testing.T) {
	ack, push := byte(kcp.IKCP_CMD_ACK), byte(kcp.IKCP_CMD_PUSH)
	if !pushes(kcpSegments(nil, ack, ack, push)) {
		t.Fatal("pushes() missed a push behind two acknowledgements")
	}
	if pushes(kcpSegments(nil, ack, ack)) || pushes(kcpSegments([]byte("x"), ack)) {
		t.Fatal("pushes() found a push among acknowledgements")
	}
	hostile := kcpSegments(nil, ack, push)
	binary.LittleEndian.PutUint32(hostile[kcpLenOff:], 0xffffffff)
	if pushes(hostile) {
		t.Fatal("pushes() read past a length longer than the packet")
	}
	if !answers(kcpSegments(nil, ack, push)) || !answers(kcpSegments(nil, kcp.IKCP_CMD_WINS)) ||
		answers(kcpSegments(nil, push, ack)) || answers([]byte{1, 2}) {
		t.Fatal("answers() misread the first segment")
	}
}

func TestKCPConnHoldsPushesBackButNotAnswers(t *testing.T) {
	out := make(chan *packetBuffer, 16)
	acks := make(chan *packetBuffer, 16)
	c := newKCPConn(out, 16, testEpochHdr(1))
	c.acks = acks
	write := func(p []byte) { _, _ = c.WriteTo(p, nil) }
	ack, push := byte(kcp.IKCP_CMD_ACK), byte(kcp.IKCP_CMD_PUSH)

	write(kcpSegments(nil, ack))
	write(kcpSegments([]byte("d"), push))
	write(kcpSegments([]byte("d"), ack, push))
	if len(acks) != 1 || len(out) != 2 {
		t.Fatalf("acks=%d out=%d, want the lone acknowledgement apart and the rest with the data", len(acks), len(out))
	}

	c.hold(time.Hour)
	write(kcpSegments([]byte("d"), push)) // held back
	write(kcpSegments([]byte("d"), push)) // held back, and kept as the probe
	write(kcpSegments([]byte("d"), ack, push))
	write(kcpSegments(nil, ack))
	if len(out) != 3 || len(acks) != 2 {
		t.Fatalf("while held: out=%d acks=%d, want only what answers the peer", len(out), len(acks))
	}
	c.probe()
	if len(out) != 4 {
		t.Fatalf("after the first probe: out=%d, want the push held back sent again", len(out))
	}
	c.probe()
	if len(out) != 4 {
		t.Fatalf("out=%d, want at most one probe per interval", len(out))
	}
	c.hold(0)
	write(kcpSegments([]byte("d"), push))
	if len(out) != 5 {
		t.Fatalf("after the hold: out=%d, want the push through", len(out))
	}
}

// TestDataLaneGoingDarkHoldsPushesAndKeepsProbing drives one lane by hand
// over a path that answers nothing. Going dark it has to hold the conn's
// pushes back, halve its frames and its KCP send window, and then keep one
// probe going out per interval on its own cadence: left to KCP, whose
// segment timeouts back off every time they go unanswered, the next push
// can be seconds away and the lane never learns the path is back. Once the
// peer answers, the hold lifts and a loud lane is paced.
func TestDataLaneGoingDarkHoldsPushesAndKeepsProbing(t *testing.T) {
	tr := newStreamTransport(&fakeVideoStream{canSend: true}, nil,
		transport.Config{ChannelID: "dark-lane"}, Options{FPS: 50, BatchSize: 64})
	tr.blackoutAfter = 100 * time.Millisecond
	tr.probeEvery = 20 * time.Millisecond
	out := make(chan *packetBuffer, 512)
	conn := newKCPConn(out, 16, testEpochHdr(1))
	windows := make(chan int, 16)
	lane := &dataLane{
		out: out, acks: make(chan *packetBuffer, 8), name: "dark-lane",
		conn: func() *kcpConn { return conn }, window: func(segments int) { windows <- segments },
	}
	samples := 0
	write := func([]byte) bool { samples++; return true }
	body := make([]byte, kcpMTU-kcp.IKCP_OVERHEAD)
	push := func() { _, _ = conn.WriteTo(kcpSegments(body, kcp.IKCP_CMD_PUSH), nil) }

	for range 400 {
		push()
	}
	for len(out) > 0 {
		lane.flush(tr, write)
	}
	for range 20 { // queued when the spell starts, for sift to drop
		push()
	}
	if lane.frames.limit != 0 || conn.holdEvery.Load() != 0 {
		t.Fatalf("frames %d, hold %d before any spell; want neither", lane.frames.limit, conn.holdEvery.Load())
	}

	time.Sleep(2 * tr.blackoutAfter)
	lane.flush(tr, write)
	if conn.holdEvery.Load() == 0 {
		t.Fatal("going dark left the conn sending pushes")
	}
	if want := tr.fullPackets() / 2; lane.frames.limit != want {
		t.Fatalf("frames %d going dark, want half of a full one (%d)", lane.frames.limit, want)
	}
	select {
	case segments := <-windows:
		if segments >= tr.sendWindow {
			t.Fatalf("KCP send window %d going dark, want it under the transport's %d", segments, tr.sendWindow)
		}
	default:
		t.Fatal("going dark left the KCP send window alone")
	}
	// Nothing is pushed from here on: the probes have to come from what the
	// lane dropped as it went dark, as they would when KCP has backed the
	// timeouts of everything it holds off past the spell.
	probes := 0
	for range 10 {
		time.Sleep(tr.probeEvery)
		before := samples
		lane.flush(tr, write)
		probes += samples - before
	}
	if probes < 5 {
		t.Fatalf("%d probes over ten intervals, want about one each", probes)
	}
	push()
	if len(out) != 0 {
		t.Fatalf("%d packets queued while the lane held pushes back", len(out))
	}

	conn.lastAck.Store(monoNow())
	lane.flush(tr, write)
	if conn.holdEvery.Load() != 0 {
		t.Fatal("the peer answered and the lane went on holding pushes back")
	}
	if lane.pace.rate == 0 {
		t.Fatal("a loud lane came back from a long spell unpaced")
	}
	if len(windows) == 0 {
		t.Fatal("the pace cap left the KCP send window alone")
	}
}

// TestKCPConnCountsWhatComesBackForItsPushes is the frame cap's evidence: a
// conn has to count the acknowledgements it delivers against the pushes they
// answer, or the cap never learns what share of the frames gets through.
func TestKCPConnCountsWhatComesBackForItsPushes(t *testing.T) {
	c := newKCPConn(make(chan *packetBuffer, 4), 8, testEpochHdr(1))
	wire := func(segs []byte) []byte {
		var crc [wireCRCLen]byte
		binary.BigEndian.PutUint32(crc[:], crc32.Checksum(segs, crcTable))
		return append(append([]byte(nil), segs...), crc[:]...)
	}
	c.delivery.count(1000, 1, 0, 0) // a push went out stamped 1000
	c.deliver(wire(kcpSegmentAt(kcp.IKCP_CMD_ACK, 1000, "")))
	c.deliver(wire(kcpSegmentAt(kcp.IKCP_CMD_ACK, 2000, ""))) // a later bucket answered
	if pushes, acks, _ := c.delivery.take(0); pushes != 1 || acks != 1 {
		t.Fatalf("the conn counted %d pushes and %d answers, want one of each", pushes, acks)
	}
}

// TestServerPeerLaneKeepsAQueueForAnswers is the server side of the ack
// queue: what only answers the peer has to go out ahead of the data a peer
// lane is holding, or a peer whose own lane is dark never hears from this
// one and both sides wait each other out.
func TestServerPeerLaneKeepsAQueueForAnswers(t *testing.T) {
	tr := newLossyTestTransport(t, transport.Config{OnPeerData: func(string, []byte) {}}, newLossyRelay(0, 0, 1))
	sess := tr.peerSessionFor(0xabcd1234)
	if sess == nil {
		t.Fatal("no peer session")
	}
	if sess.data.conn.acks == nil {
		t.Fatal("the peer's lane has no queue for what answers the peer")
	}
}

// TestDataLaneNeverAsksForMoreWindowThanTheTransportRunsWith: the publish
// limiter sets the transport's send window, a round trip of the ceiling for a
// paced writer (olcrtc#26), and a lane only ever asks for less than that.
func TestDataLaneNeverAsksForMoreWindowThanTheTransportRunsWith(t *testing.T) {
	paced := newStreamTransport(&limitedVideoStream{fakeVideoStream: &fakeVideoStream{canSend: true}, limit: 1 << 20},
		nil, transport.Config{ChannelID: "paced-lane"}, Options{FPS: 60, BatchSize: 64})
	if paced.sendWindow != kcpSendWindow(true) {
		t.Fatalf("a paced transport runs with %d segments, want %d", paced.sendWindow, kcpSendWindow(true))
	}
	windows := make(chan int, 4)
	lane := &dataLane{
		out: make(chan *packetBuffer, 4), conn: func() *kcpConn { return nil },
		window: func(segments int) { windows <- segments }, name: "paced-lane",
	}
	lane.resize(paced)
	if segments := <-windows; segments != paced.sendWindow {
		t.Fatalf("an uncapped lane asked for %d segments, want the transport's %d", segments, paced.sendWindow)
	}
	lane.frames.limit = minFramePackets
	lane.resize(paced)
	if segments := <-windows; segments >= paced.sendWindow {
		t.Fatalf("a capped lane asked for %d segments, want fewer than the transport's %d",
			segments, paced.sendWindow)
	}
}

func TestCappedLaneDropsStalePushesButNotAnswers(t *testing.T) {
	out := make(chan *packetBuffer, 8)
	l := &dataLane{out: out, pace: pace{rate: minPaceRate}}
	now := 10 * second
	hdr := testEpochHdr(1)
	queue := func(queued int64, cmds ...byte) {
		frame := append(append([]byte(nil), hdr[:]...), kcpSegments([]byte("d"), cmds...)...)
		out <- &packetBuffer{data: frame, queued: queued}
	}
	stale := now - 3*int64(capQueue)
	queue(stale, kcp.IKCP_CMD_PUSH)
	queue(stale, kcp.IKCP_CMD_ACK, kcp.IKCP_CMD_PUSH)
	queue(now, kcp.IKCP_CMD_PUSH)
	if got := l.takeFresh(now); got == nil || got.queued != stale {
		t.Fatal("takeFresh() dropped a stale packet that answers the peer")
	}
	if got := l.takeFresh(now); got == nil || got.queued != now {
		t.Fatal("takeFresh() did not skip the stale push to the fresh one")
	}
	l.pace.rate = 0
	queue(stale, kcp.IKCP_CMD_PUSH)
	if got := l.takeFresh(now); got == nil {
		t.Fatal("takeFresh() dropped a stale push on an uncapped lane")
	}
}
