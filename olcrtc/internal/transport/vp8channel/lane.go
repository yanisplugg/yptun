package vp8channel

// ai-generated: the whole file (issue #12: a data lane that goes quiet while
// the peer acknowledges nothing, so an SFU that stopped forwarding the stream
// can take it back, and then stays under what the SFU let through).

import (
	"cmp"
	"encoding/binary"
	"fmt"
	"time"

	kcp "github.com/xtaci/kcp-go/v5"

	"github.com/openlibrecommunity/olcrtc/internal/logger"
)

const (
	// defaultBlackoutAfter is how long a push may go out with no
	// acknowledgement arriving at all before the lane counts its direction
	// as dark. An SFU that finds a stream over its budget for the receiver
	// stops forwarding all of it, control included, and forwards it again
	// only once the stream is back under the budget. KCP never gets it
	// there: each retransmission timeout resends every unacknowledged
	// segment, so the stream stays loud, and dark, for minutes (issue #12).
	// Acknowledgements come back within a round trip, a few hundred
	// milliseconds on these relays; on a lossy one a burst of loss can
	// hold them back for two seconds or so, and three seconds of none while
	// pushes go out is a dark path. JVB, which re-allocates every 5 s, takes
	// a stream back at the next allocation after it went quiet.
	defaultBlackoutAfter = 3 * time.Second
	// defaultProbeEvery is how often a dark lane's conn lets one of the
	// pushes KCP resends through: a trickle any budget forwards, and the
	// push brings back the acknowledgement that ends the dark spell once
	// the path is back.
	defaultProbeEvery = 250 * time.Millisecond
	// capQueue is how long KCP's send window takes to go out at a capped
	// lane's pace, so a capped lane does not queue seconds of data for
	// timeouts to resend on top of. It is what a capped lane waits before
	// its own queue, and KCP reads that wait as the round trip: a second of
	// it put every retransmission a second and a half out and cost a third
	// of the throughput on a path losing one packet in thirty (4.6 Mbit/s
	// at 300 ms against 3.7 at a second, with 4.5 uncapped). Below a
	// round trip the window stops covering the pipe instead, which is what
	// the floor of minCapWindow segments is for.
	capQueue = 300 * time.Millisecond
	// minCapWindow is the fewest segments a capped lane lets KCP keep in
	// flight.
	minCapWindow = 32

	// The KCP segment header as kcp-go lays it out: conv, cmd, frg, wnd,
	// ts, sn, una, len, little-endian.
	kcpCmdOff = 4
	kcpLenOff = 20
)

// monoBase anchors monoNow. The lane compares instants taken on two
// goroutines, so they come off the monotonic clock: a wall clock stepped by
// the system would fake a dark spell or hide one.
var monoBase = time.Now() //nolint:gochecknoglobals // process-wide monotonic anchor, read-only

// monoNow is nanoseconds on the monotonic clock since monoBase, never zero.
func monoNow() int64 { return int64(time.Since(monoBase)) + 1 }

// dataLane carries one reliable data queue to the track: the client's from
// writerLoop, a server peer's from its pump. It watches the pushes it writes
// and the acknowledgements its KCP conn receives. While the peer acknowledges
// nothing it has the conn hold pushes back; once the peer answers again it
// writes at most half of what it was writing, and grows back from there.
type dataLane struct {
	out chan *packetBuffer
	// acks holds what only acknowledges the peer, written ahead of out;
	// nil where the conn queues everything on out.
	acks chan *packetBuffer
	// conn is the KCP conn of the queue, whose inbound acknowledgements
	// answer the pushes; nil while there is none.
	conn func() *kcpConn
	// window sets the send window of the queue's KCP session, in segments.
	window func(segments int)
	// name tells the lane apart in the log.
	name string

	pending  *packetBuffer
	batchBuf []byte
	// ackPending and ackBuf are pending and batchBuf for acks.
	ackPending *packetBuffer
	ackBuf     []byte

	// seen is the conn the state below refers to: a restarted plane is a
	// new session, not an unanswered or capped old one.
	seen *kcpConn
	// pushSince is when the oldest push with no acknowledgement arriving
	// after it went out, zero when there is none.
	pushSince int64
	darkSince int64
	// darkRate is the busiest second the lane wrote before its current dark
	// spell, the rate the spell's cap is cut from.
	darkRate float64

	// pace is the cap the lane keeps once it has been dark, frames the one
	// it keeps on a path that loses packets.
	pace   pace
	frames frameCap
	// heldBack says the last flush had a frame to write and did not, because
	// the publisher's bucket or the lane's own cap held it: the writer loop
	// reads it so that a held tick does not count as an idle one and turn
	// into a keepalive (olcrtc#26).
	heldBack bool
}

// flush writes this tick's data through write and reports whether a sample
// went out. While the lane is dark its conn drops the pushes KCP resends, so
// what is queued is what answers the peer, and a probe now and then.
func (l *dataLane) flush(p *streamTransport, write func([]byte) bool) bool {
	now := monoNow()
	switch {
	case l.watch(p, now):
		l.seen.probe() // on the lane's cadence, see kcpConn.probe
	case l.pace.grow(now, cmp.Or(p.growEvery, defaultGrowEvery), p.fullRate()):
		l.resize(p)
		if l.pace.rate == 0 {
			l.frames.handBackToFrames()
		}
	}
	acked := l.sendAcks(p, write)
	sent := l.send(p, now, write)
	if l.seen != nil {
		share, verdict := l.frames.judge(&l.seen.delivery, p.fullPackets())
		switch verdict {
		case verdictNone:
		case verdictFrames:
			l.resize(p)
			logger.Infof("vp8channel: %s: %d%% of pushed packets answered, %s", l.name, int(share*100), l.frameNote())
		case verdictRate:
			l.pace.cutTo(share * l.pace.sentRate(now))
			l.resize(p)
			logger.Infof("vp8channel: %s: %d%% answered, smaller frames lift none - pacing at delivered: at most %d KiB/s",
				l.name, int(share*100), int(l.pace.rate)>>10)
		case verdictRateCut:
			l.pace.cutTo(share * l.pace.sentRate(now))
			l.resize(p)
			logger.Infof("vp8channel: %s: %d%% answered over the pace - cut to what is delivered: at most %d KiB/s",
				l.name, int(share*100), int(l.pace.rate)>>10)
		}
	}
	return sent || acked
}

// frameNote says what the frame cap is now, for the log.
func (l *dataLane) frameNote() string {
	if l.frames.limit == 0 {
		return "full frames again"
	}
	return fmt.Sprintf("at most %d packets a frame from now", l.frames.limit)
}

// sendAcks writes the queued packets that only acknowledge the peer, in one
// sample, and reports whether it wrote one. They go uncapped: they are small,
// and the peer's lane counts its path dark without them. The publisher's
// bucket is charged for them where the sample is written, as it is for
// control frames and keepalives, but it never holds them back (olcrtc#26).
func (l *dataLane) sendAcks(p *streamTransport, write func([]byte) bool) bool {
	first := l.ackPending
	l.ackPending = nil
	if first == nil {
		select {
		case first = <-l.acks:
		default:
			return false
		}
	}
	if !p.canBatch(first.data) {
		_ = write(first.data)
		first.release()
		return true
	}
	sample, pending := p.batchSampleFrom(l.acks, first, l.ackBuf[:0])
	l.ackPending = pending
	_ = write(sample)
	l.ackBuf = sample[:0]
	return true
}

// watch reports whether pushes have gone unanswered for the blackout window.
// Going dark, it has the conn drop pushes and clears the ones already queued;
// coming back, it lets pushes through again, and after a long spell halves
// the lane's pace. Both are logged.
func (l *dataLane) watch(p *streamTransport, now int64) bool {
	conn := l.conn()
	if conn != l.seen {
		if l.seen != nil {
			l.seen.hold(0)
		}
		l.seen, l.pushSince, l.darkSince, l.pace, l.frames = conn, 0, 0, pace{}, frameCap{}
	}
	if conn == nil {
		return false
	}
	if l.pushSince != 0 && conn.lastAck.Load() >= l.pushSince {
		l.pushSince = 0
	}
	dark := l.pushSince != 0 && now-l.pushSince >= int64(cmp.Or(p.blackoutAfter, defaultBlackoutAfter))
	switch {
	case dark && l.darkSince == 0:
		l.darkSince, l.darkRate = now, l.pace.sentRate(now)
		conn.hold(cmp.Or(p.probeEvery, defaultProbeEvery))
		l.sift()
		frames := ""
		if l.frames.halve(p.fullPackets()) {
			l.resize(p)
			frames = ", " + l.frameNote()
		}
		logger.Infof("vp8channel: %s: nothing acknowledged for %s, holding pushes back until the peer answers%s",
			l.name, time.Duration(now-l.pushSince).Round(time.Millisecond), frames)
	case !dark && l.darkSince != 0:
		conn.hold(0)
		l.settle(p, now)
		l.darkSince = 0
		l.frames.forget()
	}
	return dark
}

// settle ends a dark spell. A spell held for half the blackout window or
// longer was an SFU that stopped forwarding the stream, which takes seconds
// to take it back, and the lane goes on at half of what it was writing; a
// shorter one was a burst of loss, and the lane goes on as it was.
func (l *dataLane) settle(p *streamTransport, now int64) {
	held := time.Duration(now - l.darkSince)
	after := ""
	if held >= cmp.Or(p.blackoutAfter, defaultBlackoutAfter)/2 && l.pace.slow(now, l.darkRate, p.fullRate()) {
		l.resize(p)
		after = fmt.Sprintf(", at most %d KiB/s from now", int(l.pace.rate)>>10)
	}
	logger.Infof("vp8channel: %s: the peer answers again after %s dark%s", l.name, held.Round(time.Millisecond), after)
}

// resize sets the KCP send window to what the lane writes in capQueue at the
// lower of its caps, or back to the transport's own window without one. That
// window, which the publish limiter cuts for a paced writer (olcrtc#26), is
// the ceiling: a lane only ever asks for less than the transport runs with.
func (l *dataLane) resize(p *streamTransport) {
	if l.window == nil {
		return
	}
	segments := p.sendWindow
	rate := l.pace.rate
	if l.frames.limit > 0 {
		frameRate := float64(l.frames.limit*kcpMTU) * float64(time.Second) / float64(p.frameInterval)
		if rate == 0 || frameRate < rate {
			rate = frameRate
		}
	}
	if rate > 0 {
		segments = min(max(int(rate*capQueue.Seconds())/kcpMTU, minCapWindow), segments)
	}
	l.window(segments)
}

// fullRate is the most a data lane writes per second uncapped: a full sample
// every frame.
func (p *streamTransport) fullRate() float64 {
	sample := min(p.batchSize*(epochHdrLen+kcpMTU+wireCRCLen), defaultMaxPayloadSize)
	return float64(sample) * float64(time.Second) / float64(p.frameInterval)
}

// fullPackets is how many KCP packets an uncapped sample carries: the batch
// size, or what fits in a sample's payload, whichever is smaller. The frame
// cap is a share of this, so the batch size alone would put it out by the
// ratio between the two: with the app's numbers a sample holds 43 packets,
// not 64. A sample's size is defaultMaxPayloadSize, the one publishBurstBytes
// (olcrtc#26) measures the publisher's bucket in; this reads the same number
// in packets.
func (p *streamTransport) fullPackets() int {
	room := (defaultMaxPayloadSize - epochHdrLen - len(kcpBatchMagic)) / (batchLenLen + kcpMTU + wireCRCLen)
	return max(min(p.batchSize, room), 1)
}

// send writes the next queued packet, batched with those behind it as far as
// the transport batches and the cap allows, notes when it carried the first
// unanswered push, and reports whether a sample went out.
func (l *dataLane) send(p *streamTransport, now int64, write func([]byte) bool) bool {
	limit := cmp.Or(l.frames.limit, p.fullPackets())
	if allow := l.pace.allow(now); allow >= 0 {
		if allow == 0 {
			queued := l.pending != nil || len(l.out) > 0
			l.pace.held, l.heldBack = l.pace.held || queued, queued
			return false
		}
		limit = min(max(allow/kcpMTU, 1), limit)
	}
	frame := l.takeFresh(now)
	if frame == nil {
		l.heldBack = false
		return false
	}
	// ai-generated: the rate check (olcrtc#26). What a lane pushes counts
	// against the publisher's ceiling, so it waits in the lane's own pending
	// slot for the bucket, where the cap and the dark spells can still see
	// it. Only one of the two holds a frame at a time, and nothing is
	// charged here: every sample is charged once, where it is written.
	if !p.readyToSend(frame.data) {
		l.pending, l.heldBack = frame, true
		l.pace.held = l.pace.held || l.pace.rate > 0
		return false
	}
	var sample []byte
	if p.canBatch(frame.data) {
		sample, l.pending = p.batchSampleUpTo(l.out, frame, l.batchBuf[:0], limit)
		l.batchBuf = sample[:0]
	} else {
		sample = frame.data
		defer frame.release()
	}
	if write(sample) && l.notePushes(sample) && l.pushSince == 0 {
		l.pushSince = now
	}
	l.heldBack = false
	l.pace.wrote(now, len(sample))
	l.pace.held = l.pace.held || (l.pace.rate > 0 && (l.pending != nil || len(l.out) > 0))
	return true
}

// sift drops the pushes already queued when the lane goes dark, keeping what
// answers the peer: the peer's own lane may be dark too, and it only comes
// back on the acknowledgements this one sends. KCP resends whatever pushes
// it still needs, and the last push dropped here is the conn's first probe.
func (l *dataLane) sift() {
	var keep []*packetBuffer
	for range cap(l.out) + 1 {
		frame := l.take()
		if frame == nil {
			break
		}
		if len(frame.data) > epochHdrLen+wireCRCLen && answers(frame.data[epochHdrLen:]) {
			keep = append(keep, frame)
			continue
		}
		if l.seen != nil && len(frame.data) > epochHdrLen+wireCRCLen {
			l.seen.keepProbe(frame.data[epochHdrLen : len(frame.data)-wireCRCLen])
		}
		frame.release()
	}
	for _, frame := range keep {
		select {
		case l.out <- frame:
		default:
			frame.release()
		}
	}
}

// takeFresh is take for a capped lane: pushes that have waited for twice
// capQueue are dropped on the way. Coming out of a dark spell KCP resends
// its whole window, far more than the cap carries, and at the cap the queue
// behind that would hold its resends of the head for seconds. KCP resends
// what it still needs; what answers the peer always goes.
func (l *dataLane) takeFresh(now int64) *packetBuffer {
	for range cap(l.out) + 1 {
		frame := l.take()
		if frame == nil || (l.pace.rate == 0 && l.frames.limit == 0) || now-frame.queued <= 2*int64(capQueue) ||
			(len(frame.data) > epochHdrLen && answers(frame.data[epochHdrLen:])) {
			return frame
		}
		frame.release()
	}
	return nil
}

// take returns the packet carried over from the last batch or the next
// queued one, nil when there is none.
func (l *dataLane) take() *packetBuffer {
	if frame := l.pending; frame != nil {
		l.pending = nil
		return frame
	}
	select {
	case frame := <-l.out:
		return frame
	default:
		return nil
	}
}

// release returns the carried-over packets to their pool.
func (l *dataLane) release() {
	for _, frame := range []**packetBuffer{&l.pending, &l.ackPending} {
		if *frame != nil {
			(*frame).release()
			*frame = nil
		}
	}
}

// notePushes counts the packets of a written data sample, one queued packet
// or a batch of them behind the epoch header, that hold a KCP push, for the
// frame cap, and reports whether any did.
func (l *dataLane) notePushes(sample []byte) bool {
	if len(sample) <= epochHdrLen {
		return false
	}
	found, last := false, uint32(0)
	splitKCPPayload(sample[epochHdrLen:], func(packet []byte) {
		if len(packet) < wireCRCLen {
			return
		}
		st := stampsOf(packet[:len(packet)-wireCRCLen])
		if st.pushed && l.seen != nil {
			l.frames.pushed(&l.seen.delivery, st.push)
		}
		found, last = found || st.pushed, cmp.Or(st.push, last)
	})
	if found && l.seen != nil {
		l.frames.wrote(&l.seen.delivery, last)
	}
	return found
}

// pushes reports whether a KCP packet holds a push segment. kcp-go packs
// acknowledgements first, so a packet of them alone is walked to its end.
func pushes(segs []byte) bool {
	for len(segs) >= kcp.IKCP_OVERHEAD {
		if segs[kcpCmdOff] == kcp.IKCP_CMD_PUSH {
			return true
		}
		size := binary.LittleEndian.Uint32(segs[kcpLenOff:])
		rest := segs[kcp.IKCP_OVERHEAD:]
		if uint64(size) > uint64(len(rest)) {
			return false
		}
		segs = rest[size:]
	}
	return false
}

// answers reports whether a KCP packet answers the other side: it opens with
// an acknowledgement, or with the window size a probe asked for. kcp-go
// writes those ahead of any push, so the first segment tells.
func answers(packet []byte) bool {
	if len(packet) < kcp.IKCP_OVERHEAD {
		return false
	}
	cmd := packet[kcpCmdOff]
	return cmd == kcp.IKCP_CMD_ACK || cmd == kcp.IKCP_CMD_WINS
}
