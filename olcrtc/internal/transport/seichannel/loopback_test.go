package seichannel

// ai-generated: the whole file (a pair of transports over a simulated path,
// so what the window changes is measured rather than argued).

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"math/rand/v2"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/pion/interceptor"
	"github.com/pion/rtp"
	"github.com/pion/webrtc/v4"

	"github.com/openlibrecommunity/olcrtc/internal/transport"
	"github.com/openlibrecommunity/olcrtc/internal/transport/common"
)

// h264LoopFmtp is the profile the loop binding announces.
const h264LoopFmtp = h264FmtpLine

// loopPath carries one direction's packets with a delay and, when asked,
// drops every nth of them.
type loopPath struct {
	delay   time.Duration
	lossOne int
	deliver func(*rtp.Packet)

	queue   chan timedPacket
	done    chan struct{}
	once    sync.Once
	written atomic.Uint64
	dropped atomic.Uint64
	// dark drops everything while it is set, the way an SFU that stopped
	// forwarding a stream does.
	dark atomic.Bool
}

type timedPacket struct {
	at     time.Time
	packet *rtp.Packet
}

func newLoopPath(delay time.Duration, lossOne int, deliver func(*rtp.Packet)) *loopPath {
	p := &loopPath{
		delay: delay, lossOne: lossOne, deliver: deliver,
		queue: make(chan timedPacket, 4096), done: make(chan struct{}),
	}
	go p.run()
	return p
}

func (p *loopPath) run() {
	for {
		select {
		case <-p.done:
			return
		case item := <-p.queue:
			if wait := time.Until(item.at); wait > 0 {
				timer := time.NewTimer(wait)
				select {
				case <-timer.C:
				case <-p.done:
					timer.Stop()
					return
				}
			}
			p.deliver(item.packet)
		}
	}
}

func (p *loopPath) write(header *rtp.Header, payload []byte) {
	n := p.written.Add(1)
	if p.dark.Load() {
		p.dropped.Add(1)
		return
	}
	if p.lossOne > 0 && n%uint64(p.lossOne) == 0 {
		p.dropped.Add(1)
		return
	}
	packet := &rtp.Packet{Header: *header, Payload: bytes.Clone(payload)}
	select {
	case p.queue <- timedPacket{at: time.Now().Add(p.delay), packet: packet}:
	case <-p.done:
	}
}

func (p *loopPath) close() { p.once.Do(func() { close(p.done) }) }

// loopWriter is the TrackLocalWriter a bound track writes into.
type loopWriter struct{ path *loopPath }

func (w *loopWriter) WriteRTP(header *rtp.Header, payload []byte) (int, error) {
	w.path.write(header, payload)
	return len(payload), nil
}

func (w *loopWriter) Write(b []byte) (int, error) { return len(b), nil }

// loopContext is the binding context a PeerConnection would provide.
type loopContext struct {
	ssrc   webrtc.SSRC
	writer *loopWriter
}

func (c *loopContext) CodecParameters() []webrtc.RTPCodecParameters {
	return []webrtc.RTPCodecParameters{{
		RTPCodecCapability: webrtc.RTPCodecCapability{
			MimeType: webrtc.MimeTypeH264, ClockRate: 90000,
			SDPFmtpLine: h264LoopFmtp,
		},
		PayloadType: 96,
	}}
}

func (c *loopContext) HeaderExtensions() []webrtc.RTPHeaderExtensionParameter {
	return nil
}
func (c *loopContext) SSRC() webrtc.SSRC                       { return c.ssrc }
func (c *loopContext) SSRCRetransmission() webrtc.SSRC         { return 0 }
func (c *loopContext) SSRCForwardErrorCorrection() webrtc.SSRC { return 0 }
func (c *loopContext) WriteStream() webrtc.TrackLocalWriter    { return c.writer }
func (c *loopContext) ID() string                              { return "loop" }
func (c *loopContext) RTCPReader() interceptor.RTCPReader      { return nil }

// loopSide is one transport of the pair.
type loopSide struct {
	tr     *streamTransport
	reader packetReader
	// out is the path this side writes into.
	out *loopPath

	mu       sync.Mutex
	received [][]byte
	bytes    atomic.Uint64
}

func (s *loopSide) onData(data []byte) {
	s.bytes.Add(uint64(len(data)))
	s.mu.Lock()
	s.received = append(s.received, bytes.Clone(data))
	s.mu.Unlock()
}

func (s *loopSide) messages() [][]byte {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([][]byte(nil), s.received...)
}

// newLoopSide builds one transport writing into path.
func newLoopSide(t *testing.T, deviceID string, opts Options, ssrc webrtc.SSRC) (*loopSide, *webrtc.TrackLocalStaticSample) {
	t.Helper()
	side := &loopSide{}
	track, err := common.NewVideoTrack(webrtc.RTPCodecCapability{
		MimeType: webrtc.MimeTypeH264, ClockRate: 90000,
		SDPFmtpLine: h264LoopFmtp,
	}, "loop")
	if err != nil {
		t.Fatalf("track: %v", err)
	}
	stream := &fakeVideoStream{canSend: true}
	side.tr = newStreamTransport(stream, track, transport.Config{
		DeviceID:  deviceID,
		ChannelID: "loopback",
		OnData:    side.onData,
	}, opts.withDefaults())
	_ = ssrc
	return side, track
}

// newLoopPair wires two transports to each other over paths with the given
// one-way delay, dropping every lossOne'th packet in each direction.
func newLoopPair(t *testing.T, opts Options, delay time.Duration, lossOne int) (*loopSide, *loopSide) {
	t.Helper()
	return newLoopPairAs(t, opts, delay, lossOne, false)
}

// newLoopPairAs is newLoopPair with a client that speaks as a version
// without the ordered feature: its hello announces none. The paths it wires
// are recorded on each side so a test can take them dark.
func newLoopPairAs(
	t *testing.T, opts Options, delay time.Duration, lossOne int, clientIsOld bool,
) (*loopSide, *loopSide) {
	t.Helper()
	server, serverTrack := newLoopSide(t, "", opts, 1)
	client, clientTrack := newLoopSide(t, "loop-client", opts, 2)
	if clientIsOld {
		// Before Connect, so the writer that reads it is not running yet.
		client.tr.hello = common.EncodeHello(common.RoleClient, client.tr.bindingToken)
	}

	toClient := newLoopPath(delay, lossOne, func(packet *rtp.Packet) {
		for _, payload := range client.reader.payloads(packet, nil) {
			client.tr.handlePayload(payload)
		}
	})
	toServer := newLoopPath(delay, lossOne, func(packet *rtp.Packet) {
		for _, payload := range server.reader.payloads(packet, nil) {
			server.tr.handlePayload(payload)
		}
	})
	t.Cleanup(toClient.close)
	t.Cleanup(toServer.close)
	server.out, client.out = toClient, toServer

	if _, err := serverTrack.Bind(&loopContext{ssrc: 1, writer: &loopWriter{path: toClient}}); err != nil {
		t.Fatalf("bind server track: %v", err)
	}
	if _, err := clientTrack.Bind(&loopContext{ssrc: 2, writer: &loopWriter{path: toServer}}); err != nil {
		t.Fatalf("bind client track: %v", err)
	}
	for _, side := range []*loopSide{server, client} {
		if err := side.tr.Connect(context.Background()); err != nil {
			t.Fatalf("connect: %v", err)
		}
		t.Cleanup(func() { _ = side.tr.Close() })
	}
	waitFor(t, 5*time.Second, func() bool { return server.tr.CanSend() && client.tr.CanSend() })
	return server, client
}

func waitFor(t *testing.T, budget time.Duration, ok func() bool) {
	t.Helper()
	deadline := time.Now().Add(budget)
	for time.Now().Before(deadline) {
		if ok() {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("condition not met within %s", budget)
}

func loopMessage(seq, size int) []byte {
	out := make([]byte, size)
	var key [32]byte
	binary.BigEndian.PutUint32(key[:4], uint32(seq)) //nolint:gosec // a small test index
	src := rand.NewChaCha8(key)
	_, _ = src.Read(out)
	copy(out, key[:4])
	return out
}

// TestLoopbackWindowBeatsOneAtATime is the measurement behind issues #9 and
// #16: on a path with a round trip, a sender that waits for every message to
// be acknowledged moves one message per round trip, which is an order of
// magnitude under what the path carries. The window has to do much better
// than the old ceiling of one 7 KB message per 120 ms round trip.
func TestLoopbackWindowBeatsOneAtATime(t *testing.T) {
	if testing.Short() {
		t.Skip("timing test")
	}
	const (
		delay    = 60 * time.Millisecond
		messages = 160
		size     = 7000
	)
	server, client := newLoopPair(t, Options{FPS: 60, BatchSize: 64, FragmentSize: 900}, delay, 0)
	waitFor(t, 5*time.Second, func() bool { return server.tr.peerOrdered.Load() })

	start := time.Now()
	for i := range messages {
		if err := server.tr.Send(loopMessage(i, size)); err != nil {
			t.Fatalf("Send(%d): %v", i, err)
		}
	}
	waitFor(t, 20*time.Second, func() bool { return client.bytes.Load() >= messages*size })
	took := time.Since(start)

	// One message per round trip would need messages * (2*delay + a tick)
	// - over 20 s here. Half of that is still far below what the window
	// reaches and far above what waiting for every message can.
	ceiling := time.Duration(messages) * (2*delay + 20*time.Millisecond) / 2
	if took > ceiling {
		t.Fatalf("%d messages of %d bytes took %v, want under %v (%.0f KB/s)",
			messages, size, took, ceiling, float64(messages*size)/took.Seconds()/1024)
	}
	t.Logf("%d messages, %.0f KB/s, %v", messages, float64(messages*size)/took.Seconds()/1024, took)
}

// TestLoopbackOrderAndIntegrityUnderLoss covers what ordered delivery is for:
// with packets dropped the messages still arrive whole, once each, in the
// order they were sent.
func TestLoopbackOrderAndIntegrityUnderLoss(t *testing.T) {
	if testing.Short() {
		t.Skip("timing test")
	}
	const (
		delay    = 20 * time.Millisecond
		messages = 60
		size     = 3000
	)
	server, client := newLoopPair(t, Options{FPS: 60, BatchSize: 32, FragmentSize: 900}, delay, 7)
	waitFor(t, 5*time.Second, func() bool { return server.tr.peerOrdered.Load() })

	for i := range messages {
		if err := server.tr.Send(loopMessage(i, size)); err != nil {
			t.Fatalf("Send(%d): %v", i, err)
		}
	}
	waitFor(t, 30*time.Second, func() bool { return len(client.messages()) >= messages })

	got := client.messages()
	if len(got) != messages {
		t.Fatalf("delivered %d messages, want %d", len(got), messages)
	}
	for i, message := range got {
		if !bytes.Equal(message, loopMessage(i, size)) {
			t.Fatalf("message %d differs from the one sent in that position", i)
		}
	}
}

// TestLoopbackFallsBackForAPeerWithoutTheFeature locks in that a peer whose
// hello announces nothing still gets the one-at-a-time path, the only one it
// can reassemble.
func TestLoopbackFallsBackForAPeerWithoutTheFeature(t *testing.T) {
	if testing.Short() {
		t.Skip("timing test")
	}
	server, client := newLoopPairAs(t, Options{FPS: 60, BatchSize: 32, FragmentSize: 900},
		10*time.Millisecond, 0, true)
	waitFor(t, 5*time.Second, func() bool { return client.tr.peerOrdered.Load() })
	if server.tr.peerOrdered.Load() {
		t.Fatal("the server took a plain hello for one that announces the feature")
	}

	payload := loopMessage(1, 5000)
	if err := server.tr.Send(payload); err != nil {
		t.Fatalf("Send: %v", err)
	}
	waitFor(t, 10*time.Second, func() bool { return len(client.messages()) > 0 })
	if got := client.messages()[0]; !bytes.Equal(got, payload) {
		t.Fatal("the fallback path delivered something else")
	}
	if server.tr.window.InFlight() != 0 {
		t.Fatal("the window took a message meant for the fallback path")
	}
}

// TestLoopbackSendFailsAfterClose keeps Close unblocking a window that is
// full, so the session above is never parked on a transport that is gone.
func TestLoopbackSendFailsAfterClose(t *testing.T) {
	if testing.Short() {
		t.Skip("timing test")
	}
	server, client := newLoopPair(t, Options{FPS: 60, BatchSize: 8, FragmentSize: 900}, 10*time.Millisecond, 0)
	waitFor(t, 5*time.Second, func() bool { return server.tr.peerOrdered.Load() })
	_ = client

	if err := server.tr.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
	if err := server.tr.Send(loopMessage(1, 100)); !errors.Is(err, ErrTransportClosed) {
		t.Fatalf("Send after Close = %v, want %v", err, ErrTransportClosed)
	}
}

// TestLoopbackSurvivesAPathThatGoesDark is what an SFU does when a stream is
// over the receiver's budget: it stops forwarding all of it, control
// included, and takes it back once the stream is small again. The transfer
// has to finish anyway, in order and whole.
func TestLoopbackSurvivesAPathThatGoesDark(t *testing.T) {
	if testing.Short() {
		t.Skip("timing test")
	}
	const (
		delay    = 30 * time.Millisecond
		messages = 60
		size     = 4000
	)
	server, client := newLoopPair(t, Options{FPS: 60, BatchSize: 64, FragmentSize: 900}, delay, 0)
	waitFor(t, 5*time.Second, func() bool { return server.tr.peerOrdered.Load() })

	go func() {
		for i := range messages {
			if err := server.tr.Send(loopMessage(i, size)); err != nil {
				return
			}
		}
	}()
	waitFor(t, 10*time.Second, func() bool { return client.bytes.Load() > 0 })

	server.out.dark.Store(true)
	time.Sleep(4 * time.Second)
	dark := client.bytes.Load()
	server.out.dark.Store(false)

	waitFor(t, 30*time.Second, func() bool { return len(client.messages()) >= messages })
	got := client.messages()
	for i, message := range got[:messages] {
		if !bytes.Equal(message, loopMessage(i, size)) {
			t.Fatalf("message %d differs from the one sent in that position", i)
		}
	}
	t.Logf("%d messages through a 4 s blackout, %d bytes had arrived when it started", messages, dark)
}
