package vp8channel

// ai-generated: the whole file (issue #12: a data lane on a path that loses
// a few percent of its RTP packets).

import (
	"context"
	"encoding/binary"
	"math"
	"math/rand/v2"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	kcp "github.com/xtaci/kcp-go/v5"

	"github.com/openlibrecommunity/olcrtc/internal/transport"
)

// rtpPayload is about what one RTP packet of a VP8 frame carries.
const rtpPayload = 1200

// lossyRelay is one direction of a path that loses each RTP packet with
// probability loss, the way two of the public JVBs lost about one in
// twenty-five of the big ones. A frame reaches the receiver only when every
// packet of it does, so a frame of fifty packets is lost seven times in
// eight.
type lossyRelay struct {
	loss   float64
	delay  time.Duration
	mu     sync.Mutex
	rng    *rand.Rand
	frames chan relayedFrame
}

type relayedFrame struct {
	due  time.Time
	data []byte
}

func newLossyRelay(loss float64, delay time.Duration, seed uint64) *lossyRelay {
	return &lossyRelay{
		loss: loss, delay: delay, frames: make(chan relayedFrame, 4096),
		rng: rand.New(rand.NewPCG(seed, seed)), //nolint:gosec // a reproducible loss pattern, not a secret
	}
}

func (r *lossyRelay) write(frame []byte) bool {
	r.mu.Lock()
	lost := false
	for range (len(frame) + rtpPayload - 1) / rtpPayload {
		lost = lost || r.rng.Float64() < r.loss
	}
	r.mu.Unlock()
	if !lost {
		select {
		case r.frames <- relayedFrame{due: time.Now().Add(r.delay), data: append([]byte(nil), frame...)}:
		default:
		}
	}
	return true
}

func (r *lossyRelay) run(ctx context.Context, to func([]byte)) {
	for {
		select {
		case <-ctx.Done():
			return
		case frame := <-r.frames:
			time.Sleep(time.Until(frame.due))
			to(frame.data)
		}
	}
}

// TestDataLaneKeepsMovingOnAPathThatLosesPackets pulls through a path that
// loses one RTP packet in sixteen, 25 ms each way. With frames of fifty
// packets almost every frame is lost, and so is every KCP segment in it:
// KCP resends the same segments in the same full frames, backs their
// timeouts off each time they are lost again, and the transfer crawls while
// the small control frames still get through. Live, S0 ran into its five
// minutes that way on two of the public JVBs, which lost about one packet
// in twenty-five. A lane that shrinks its frames until most of them get
// through keeps the transfer moving.
func TestDataLaneKeepsMovingOnAPathThatLosesPackets(t *testing.T) {
	const (
		loss     = 0.06
		delay    = 25 * time.Millisecond
		pull     = 1 << 20
		deadline = 30 * time.Second
	)
	toClient, toServer := newLossyRelay(loss, delay, 1), newLossyRelay(loss, delay, 2)
	var pulled atomic.Int64
	client := newLossyTestTransport(t, transport.Config{
		OnData: func(b []byte) { pulled.Add(int64(len(b))) },
	}, toServer)
	server := newLossyTestTransport(t, transport.Config{OnPeerData: func(string, []byte) {}}, toClient)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go toClient.run(ctx, client.handleIncomingFrame)
	go toServer.run(ctx, server.handleIncomingFrame)
	if err := client.ConfirmPeer(server.LocalPeerID()); err != nil {
		t.Fatalf("ConfirmPeer: %v", err)
	}
	waitFor(t, 5*time.Second, "the server to see the client", func() bool {
		return server.peers.get(client.localEpochValue()) != nil
	})
	peer := client.LocalPeerID()
	start := time.Now()
	go sendChunks(ctx, pull, func(b []byte) error { return server.SendTo(peer, b) })
	for pulled.Load() < pull {
		if time.Since(start) > deadline {
			t.Fatalf("pulled %d of %d in %s", pulled.Load(), pull, deadline)
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Logf("pulled %d KiB in %s", pull>>10, time.Since(start).Round(time.Millisecond))
}

// newLossyTestTransport is a transport with the app's vp8channel numbers
// that writes its samples to relay.
func newLossyTestTransport(t *testing.T, cfg transport.Config, relay *lossyRelay) *streamTransport {
	t.Helper()
	cfg.ChannelID = "lossy-test"
	tr := newStreamTransport(&fakeVideoStream{canSend: true}, nil, cfg, Options{FPS: 60, BatchSize: 64})
	tr.sampleWriter = relay.write
	if err := tr.Connect(context.Background()); err != nil {
		t.Fatalf("Connect: %v", err)
	}
	t.Cleanup(func() { _ = tr.Close() })
	return tr
}

func TestDeliveryTrackJudgesABucketOnceALaterOneIsAnswered(t *testing.T) {
	var track deliveryTrack
	const at = 10_000 // ms, bucket 41
	bucket := int64(at/deliveryBucketMs + 1)
	for i := range uint32(8) {
		track.count(at+i, 1, 0, 0)
	}
	track.count(at+1, 0, 1, 0)
	track.count(at+7, 0, 1, 0)
	if pushes, acks, _ := track.take(0); pushes != 0 || acks != 0 {
		t.Fatalf("take = %d pushes, %d acks before a later bucket was answered, want none judged", pushes, acks)
	}
	track.count(at+deliveryBucketMs, 1, 0, 0)
	track.count(at+deliveryBucketMs, 0, 1, 0)
	track.count(at-20*deliveryBucketMs, 0, 1, 0) // an answer to a push no longer counted
	if pushes, acks, _ := track.take(0); pushes != 8 || acks != 2 {
		t.Fatalf("take = %d pushes, %d acks; want the first bucket's 8 and 2", pushes, acks)
	}
	track.count(at+2, 0, 1, 0) // late, for a bucket already judged
	track.count(at+2*deliveryBucketMs, 0, 1, 0)
	if pushes, acks, _ := track.take(0); pushes != 1 || acks != 1 {
		t.Fatalf("take = %d pushes, %d acks; want the next bucket's 1 and 1 and no late answer", pushes, acks)
	}
	track.count(at+3*deliveryBucketMs, 5, 5, 0)
	track.count(at+4*deliveryBucketMs, 0, 1, 0)
	if pushes, acks, _ := track.take(bucket + 4); pushes != 0 || acks != 0 {
		t.Fatalf("take = %d pushes, %d acks from before from, want them cleared, not counted", pushes, acks)
	}
}

func TestFrameCapShrinksOnLossAndGrowsBackWhenClean(t *testing.T) {
	var (
		f     frameCap
		track deliveryTrack
		ts    uint32 = 10_000
	)
	// judged pushes 100 packets in a bucket past the last judgement and the
	// grow interval, of which answered come back, and has a later push
	// answered so that the bucket is judged.
	judged := func(answered int) (float64, bool) {
		ts += (frameGrowBuckets + 1) * deliveryBucketMs
		for i := range 100 {
			f.pushed(&track, ts)
			if i%4 == 3 { // a frame of four packets
				f.wrote(&track, ts)
			}
			if i < answered {
				track.count(ts, 0, 1, 0)
			}
		}
		track.count(ts+deliveryBucketMs, 0, 1, 0)
		share, verdict := f.judge(&track, 64)
		return share, verdict == verdictFrames
	}
	if _, changed := judged(1); changed || f.limit != 0 {
		t.Fatalf("limit = %d after a dark bucket, want the cap left to the dark spells", f.limit)
	}
	if _, changed := judged(20); changed || f.limit != 0 {
		t.Fatalf("limit = %d after one lossy reading, want a second one before the frames shrink", f.limit)
	}
	if share, changed := judged(20); !changed || f.limit != shrunk(64, share) || f.limit > 32 {
		t.Fatalf("limit = %d after %.0f%% came back twice, want %d", f.limit, share*100, shrunk(64, share))
	}
	limit := f.limit
	for limit != 0 {
		if limit += max(limit/4, 1); limit >= 64 {
			limit = 0
		}
		if _, changed := judged(70); !changed || f.limit != limit {
			t.Fatalf("limit = %d after a reading that is not lossy, want %d, a quarter up", f.limit, limit)
		}
	}
}

func TestFrameCapHalvesAsTheLaneGoesDarkAndForgets(t *testing.T) {
	var (
		f     frameCap
		track deliveryTrack
	)
	f.pushed(&track, 10_000)
	f.pushes, f.acks = 50, 1
	if !f.halve(64) || f.limit != 32 || f.pushes != 0 || f.acks != 0 || f.since != f.newest+1 {
		t.Fatalf("after going dark: %+v, want half of full and the evidence dropped", f)
	}
	for f.limit > minFramePackets {
		f.halve(64)
	}
	if f.halve(64) || f.limit != minFramePackets {
		t.Fatalf("limit = %d, want it to stop at %d", f.limit, minFramePackets)
	}
}

func TestShrunkAimsAtTheShareAndStaysInItsBounds(t *testing.T) {
	for _, tc := range []struct {
		limit int
		share float64
		want  int
	}{
		{64, 0.1, 25},  // 64 * ln 0.40 / ln 0.1 = 25.5
		{64, 0.45, 32}, // the model says 57, at most half
		{8, 0.49, 4},   // the model says 10, at most half, and the floor
		{6, 0.2, 4},    // the floor
	} {
		if got := shrunk(tc.limit, tc.share); got != tc.want {
			t.Errorf("shrunk(%d, %.2f) = %d, want %d", tc.limit, tc.share, got, tc.want)
		}
	}
}

// judgedShare drives a frame cap with readings of a fixed answered share, as
// a policer answers whatever the lane writes: a tbf or shaper drops by rate,
// and a frame half the size is answered at the same share. It returns the
// verdicts, in the order the readings produced them.
func judgedShare(readings, answered int) []capVerdict {
	var (
		f     frameCap
		track deliveryTrack
		ts    uint32 = 10_000
	)
	verdicts := make([]capVerdict, 0, readings)
	for range readings {
		ts += (frameGrowBuckets + 1) * deliveryBucketMs
		for i := range 100 {
			f.pushed(&track, ts)
			if i%4 == 3 {
				f.wrote(&track, ts)
			}
			if i < answered {
				track.count(ts, 0, 1, 0)
			}
		}
		track.count(ts+deliveryBucketMs, 0, 1, 0)
		_, verdict := f.judge(&track, 64)
		verdicts = append(verdicts, verdict)
	}
	return verdicts
}

// A path whose loss follows the lane's rate, not chance: shrinking the
// frames lifts the answered share nowhere, and after two such shrinks the
// rate is handed to pace (the frames run full again) instead of shrinking
// on to the floor. Once it holds the rate, a share that keeps falling under
// the cut line is cut again, and a healthy one leaves it alone.
func TestFrameCapHandsTheRateToPaceWhenShrinksBuyNothing(t *testing.T) {
	verdicts := judgedShare(7, 20)
	rate := 0
	for i, v := range verdicts {
		if v == verdictRate {
			rate = i
		}
	}
	if rate == 0 {
		t.Fatalf("no reading handed the rate to pace: %v", verdicts)
	}
	if verdicts[len(verdicts)-1] != verdictRateCut {
		t.Fatalf("the share kept under the cut line after the handover, want a cut: %v", verdicts)
	}

	// Once the share is healthy the judgements say nothing: pace grows back
	// on its own and the frames stay full.
	var (
		f     frameCap
		track deliveryTrack
		ts    uint32 = 10_000
	)
	healthy := func(answered int) capVerdict {
		ts += (frameGrowBuckets + 1) * deliveryBucketMs
		for i := range 100 {
			f.pushed(&track, ts)
			if i%4 == 3 {
				f.wrote(&track, ts)
			}
			if i < answered {
				track.count(ts, 0, 1, 0)
			}
		}
		track.count(ts+deliveryBucketMs, 0, 1, 0)
		_, verdict := f.judge(&track, 64)
		return verdict
	}
	f.handRateToPace()
	for range 4 {
		if v := healthy(90); v != verdictNone {
			t.Fatalf("a healthy share under pace produced %v, want no verdict", v)
		}
	}
	if f.limit != 0 {
		t.Fatalf("limit = %d under pace, want full frames", f.limit)
	}
	f.handBackToFrames()
	if healthy(20) == verdictRateCut || !f.lossy {
		t.Fatalf("after the hand back a lossy reading must shrink frames again, not cut rate")
	}
}

// A chancy path, where a shrink lifts the answered share as the model
// predicts, never hands the rate to pace however long it runs.
func TestFrameCapKeepsItsFramesOnAChancyPath(t *testing.T) {
	var (
		f     frameCap
		track deliveryTrack
		ts    uint32 = 10_000
		share        = 0.2
	)
	for range 12 {
		ts += (frameGrowBuckets + 1) * deliveryBucketMs
		answered := int(share*100 + 0.5)
		for i := range 100 {
			f.pushed(&track, ts)
			if i%4 == 3 {
				f.wrote(&track, ts)
			}
			if i < answered {
				track.count(ts, 0, 1, 0)
			}
		}
		track.count(ts+deliveryBucketMs, 0, 1, 0)
		read, verdict := f.judge(&track, 64)
		if verdict == verdictRate || f.rateKept {
			t.Fatalf("a chancy path handed its rate to pace at share %.2f", read)
		}
		// The next reading answers what the model promises for the frames
		// now in force, or better.
		share = min(math.Pow(share, float64(max(f.limit, 1))/64), 0.9)
	}
}

// kcpSegmentAt is one KCP segment of a command, a timestamp and a body.
func kcpSegmentAt(cmd byte, ts uint32, body string) []byte {
	seg := make([]byte, kcp.IKCP_OVERHEAD+len(body))
	seg[kcpCmdOff] = cmd
	binary.LittleEndian.PutUint32(seg[kcpTSOff:], ts)
	binary.LittleEndian.PutUint32(seg[kcpLenOff:], uint32(len(body))) //nolint:gosec // test body
	copy(seg[kcp.IKCP_OVERHEAD:], body)
	return seg
}

func TestStampsOfReadsTheLastPushAndAck(t *testing.T) {
	segment := kcpSegmentAt
	packet := append(append(append(segment(kcp.IKCP_CMD_ACK, 5, ""), segment(kcp.IKCP_CMD_ACK, 6, "")...),
		segment(kcp.IKCP_CMD_PUSH, 7, "ab")...), segment(kcp.IKCP_CMD_PUSH, 8, "c")...)
	if st := stampsOf(packet); !st.pushed || !st.acked || st.push != 8 || st.ack != 6 {
		t.Fatalf("stampsOf = %+v, want push 8 and ack 6", st)
	}
	hostile := segment(kcp.IKCP_CMD_ACK, 9, "")
	binary.LittleEndian.PutUint32(hostile[kcpLenOff:], 0xffffffff)
	hostile = append(hostile, segment(kcp.IKCP_CMD_PUSH, 10, "")...)
	if st := stampsOf(hostile); st.pushed || !st.acked || st.ack != 9 {
		t.Fatalf("stampsOf read past a length longer than the packet: %+v", st)
	}
}

// judgeCleanPath drives a frame cap over a lossless path in steps of 50 ms: each
// step pushes 20 packets stamped now, and every packet pushed a round trip
// ago comes back acknowledged. It returns the lowest share judged and the cap
// at the end.
func judgeCleanPath(start uint32, steps int) (float64, int) {
	const (
		stepMs = 50
		rttMs  = 150
		n      = 20
	)
	var (
		f     frameCap
		track deliveryTrack
	)
	low, sent, ts := 1.0, map[uint32]int{}, start
	for range steps {
		for range n {
			f.pushed(&track, ts)
		}
		f.wrote(&track, ts)
		sent[ts] = n
		for range sent[ts-rttMs] {
			track.count(ts-rttMs, 0, 1, 0)
		}
		delete(sent, ts-rttMs)
		if share, verdict := f.judge(&track, 64); verdict != verdictNone || share != 0 {
			low = min(low, share)
		}
		ts += stepMs
	}
	return low, f.limit
}

// TestFrameCapKeepsFullFramesAcrossTheKCPClockWrap runs a lossless path over
// the instant kcp-go's millisecond clock wraps, 49.7 days into a process.
// Read as 32-bit numbers the timestamps after it fall behind the newest one
// acknowledged before it, every bucket is judged before its acknowledgements
// arrive, and the lane ends up capped at its smallest frame for the rest of
// the session.
func TestFrameCapKeepsFullFramesAcrossTheKCPClockWrap(t *testing.T) {
	for _, tc := range []struct {
		name  string
		start uint32
	}{
		{"away from the wrap", 1_000_000},
		{"across the wrap", math.MaxUint32 - 5000},
	} {
		if low, limit := judgeCleanPath(tc.start, 600); limit != 0 {
			t.Errorf("%s: a lossless path ends capped at %d packets a frame (lowest share judged %.2f)",
				tc.name, limit, low)
		}
	}
}

// TestDeliveryTrackJudgesTheWrappedBucketsInOrder is the tracker side of the
// same wrap: read as 32-bit numbers, the buckets after it fall behind the
// newest one answered before it, so the newest answered pins there and every
// bucket after the wrap is judged the moment it is counted, before its
// answers are back.
func TestDeliveryTrackJudgesTheWrappedBucketsInOrder(t *testing.T) {
	var track deliveryTrack
	before := uint32(math.MaxUint32 - 100)
	track.count(before, 1, 0, 1) // a push, answered, just before the wrap
	track.count(before, 0, 1, 0)
	track.count(50, 1, 0, 1) // a push after it, answered
	track.count(50, 0, 1, 0)
	track.count(300, 1, 0, 1) // and one still on its way
	pushes, acks, _ := track.take(0)
	if pushes != 1 || acks != 1 {
		t.Fatalf("take = %d pushes, %d acks across the wrap, want the one bucket answered before it: "+
			"a bucket after the wrap must wait for a later answer like any other", pushes, acks)
	}
}

func TestStampClockUnwrapsAcrossTheWrap(t *testing.T) {
	var c stampClock
	if got := c.unwrap(math.MaxUint32 - 100); got != math.MaxUint32-100 {
		t.Fatalf("first stamp = %d, want it as it came", got)
	}
	if got, want := c.unwrap(50), int64(math.MaxUint32)+51; got != want {
		t.Fatalf("a stamp past the wrap = %d, want %d", got, want)
	}
	if got, want := c.unwrap(math.MaxUint32-40), int64(math.MaxUint32)-40; got != want {
		t.Fatalf("an older stamp = %d, want %d", got, want)
	}
	if c.newest != int64(math.MaxUint32)+51 {
		t.Fatalf("newest = %d, want the newest stamp seen", c.newest)
	}
}
