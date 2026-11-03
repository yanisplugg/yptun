package vp8channel

import (
	"encoding/binary"
	"time"

	"github.com/pion/webrtc/v4/pkg/media"
)

// writerState holds the per-loop bookkeeping for writerLoop, extracted so the
// loop body stays within cognitive-complexity limits.
type writerState struct {
	p                   *streamTransport
	keepaliveEvery      int
	idleTicks           int
	forceKeepaliveEvery int
	ticksSinceKeepalive int
	// keyframeGap is the fewest ticks between two keyframes a relay asked
	// for: one that takes none of them asks without end.
	keyframeGap int
	// pendingControl holds a control frame that failed WriteSample and must be
	// retried on the next tick before consuming more frames.
	pendingControl *packetBuffer
	// data carries the single-peer data queue (ai-generated: issue #12).
	data dataLane
	// pendingDatagram is a lossy sample that failed WriteSample, or the
	// first datagram of the next route split off by batchDatagramSampleFrom.
	pendingDatagram []byte
}

// newWriterState is writerLoop's bookkeeping for p.
//
// ai-generated: the constructor, with the data lane.
func newWriterState(p *streamTransport) *writerState {
	return &writerState{
		p:                   p,
		keepaliveEvery:      max(int(keepaliveIdlePeriod/p.frameInterval), 1),
		forceKeepaliveEvery: max(int(forceKeepalivePeriod/p.frameInterval), 1),
		keyframeGap:         max(int(keyframeRequestGap/p.frameInterval), 1),
		data: dataLane{
			out: p.data.out, acks: p.data.acks, conn: p.dataConn, window: p.dataWindow, name: "data",
		},
	}
}

func (w *writerState) releasePending() {
	if w.pendingControl != nil {
		w.pendingControl.release()
	}
	w.data.release()
}

func (w *writerState) writeSample(data []byte) bool {
	return w.p.writeSampleLocked(data)
}

// writeSampleLocked serializes every WriteSample call on the shared video
// track behind a single mutex. pion's TrackLocalStaticSample.WriteSample is
// NOT safe for concurrent use: it packetizes under its own lock but then
// releases that lock before pushing the resulting RTP packets onto the wire.
// Two concurrent callers therefore each reserve a contiguous block of RTP
// sequence numbers and then race to emit their packets, interleaving them on
// the wire. The receiver's VP8 reassembler enforces strict sequence
// contiguity, so any interleaved frame is discarded - which is exactly the
// server->client bulk-data stall in issue #95 (the server runs a per-peer
// peerWriterPump for data plus writerLoop for control/keepalive, both hitting
// this track at once). Funneling all writes through this mutex makes each
// sample's packetize+send atomic and keeps sequence numbers monotonic. Pion's
// VP8 payloader copies sample.Data into RTP payloads during Packetize, before
// WriteSample returns, so the writer can release its packet buffer afterward.
func (p *streamTransport) writeSampleLocked(data []byte) bool {
	return p.writeLocked(data, false)
}

// writeKeyframeLocked writes a frame the relay has to count as a keyframe:
// the periodic one that keeps it forwarding, and the answer to its request.
// On the transport's own VP8 every frame is one already.
func (p *streamTransport) writeKeyframeLocked(data []byte) bool {
	return p.writeLocked(data, true)
}

func (p *streamTransport) writeLocked(data []byte, keyframe bool) bool {
	p.writeMu.Lock()
	defer p.writeMu.Unlock()
	// A carrier that is not the transport's own VP8 re-heads the frame for
	// its track. The payloader copies the sample before WriteSample returns,
	// so one buffer serves every write.
	if p.carrier != nil && p.carrier.dress != nil {
		p.dressBuf = p.carrier.dress(p.dressBuf, data, keyframe)
		data = p.dressBuf
	}
	// ai-generated: the charge and the written result (olcrtc#26). Every
	// sample this transport publishes passes here, so this is where the rate
	// the relay sees is counted - control frames and keepalives included,
	// which are charged but never held back.
	var written bool
	if p.sampleWriter != nil {
		written = p.sampleWriter(data)
	} else {
		written = p.track.WriteSample(media.Sample{
			Data:     data,
			Duration: p.frameInterval,
		}) == nil
	}
	if written {
		p.limiter.Charge(len(data))
	}
	return written
}

// readyToSend reports whether the bucket has room for a bulk frame of this
// size. The control plane never asks. ai-generated: readyToSend (olcrtc#26).
func (p *streamTransport) readyToSend(frame []byte) bool {
	return p.limiter.Ready(len(frame))
}

// forceKeepalive emits a clean, fully-decodable VP8 keepalive keyframe at a
// steady cadence even while bulk data is flowing. During a sustained bulk
// transfer every emitted "frame" is the epoch header plus opaque KCP bytes,
// which never forms a decodable VP8 keyframe. The SFU asks for a keyframe (PLI)
// and, receiving none within its decode-timeout (~40 s), stops forwarding the
// track to subscribers. The periodic bare keyframe keeps the SFU's decoder
// satisfied.
//
// A relay that asks for one (requestKeyframe) is answered on the next tick
// rather than at the next beat, keyframeGap ticks after the last at the
// soonest: until it has a keyframe it forwards nothing of the track.
func (w *writerState) forceKeepalive() {
	w.ticksSinceKeepalive++
	due := w.ticksSinceKeepalive >= w.forceKeepaliveEvery
	if !due && w.ticksSinceKeepalive >= w.keyframeGap && w.p.keyframeWanted.Load() {
		due = true
	}
	if !due {
		return
	}
	w.p.keyframeWanted.Store(false)
	w.ticksSinceKeepalive = 0
	hdr := w.p.epochHeader()
	_ = w.p.writeKeyframeLocked(hdr[:])
}

// requestKeyframe is the relay asking for a keyframe. Only a carrier whose
// relay asks this way answers; on the others the two-second beat is all there
// is, as before.
func (p *streamTransport) requestKeyframe() {
	if p.carrier.answersKeyframeRequests() {
		p.keyframeWanted.Store(true)
	}
}

// drainControl flushes all queued control frames. Returns false when a frame
// failed to send (stored in pendingControl for retry next tick).
func (w *writerState) drainControl() bool {
	if w.pendingControl != nil {
		if !w.writeSample(w.pendingControl.data) {
			return false
		}
		w.pendingControl.release()
		w.pendingControl = nil
	}
	for {
		select {
		case frame := <-w.p.control.out:
			w.idleTicks = 0
			if !w.writeSample(frame.data) {
				w.pendingControl = frame
				return false
			}
			frame.release()
		default:
			return true
		}
	}
}

// drainDatagram sends queued lossy datagrams after control frames and before
// reliable KCP data. Returns false when a sample must be retried next tick.
func (w *writerState) drainDatagram() bool {
	if w.pendingDatagram != nil {
		if !w.writeSample(w.pendingDatagram) {
			return false
		}
		w.pendingDatagram = nil
	}
	for {
		select {
		case frame := <-w.p.datagram:
			// ai-generated: the rate check (olcrtc#26). The lossy lane rides
			// the same track as the bulk data and counts against the same
			// ceiling, so it waits for the bucket like the bulk data does.
			if !w.p.readyToSend(frame) {
				w.pendingDatagram = frame
				return false
			}
			sample := w.batchDatagramSampleFrom(w.p.datagram, frame)
			w.idleTicks = 0
			if !w.writeSample(sample) {
				w.pendingDatagram = sample
				return false
			}
		default:
			return true
		}
	}
}

// batchDatagramSampleFrom packs first and the datagrams queued behind it that
// share its epoch header into one OLUB sample, up to batchSize packets and
// defaultMaxPayloadSize bytes. A datagram bound elsewhere, or one that no
// longer fits, waits in pendingDatagram for the next sample.
func (w *writerState) batchDatagramSampleFrom(src <-chan []byte, first []byte) []byte {
	if len(first) <= epochHdrLen || w.p.batchSize <= 1 {
		return first
	}
	sample := make([]byte, 0, defaultMaxPayloadSize)
	sample = append(sample, first[:epochHdrLen]...)
	sample = append(sample, datagramBatchMagic[:]...)
	sample = appendBatchPacket(sample, first[epochHdrLen:])
	for packets := 1; packets < w.p.batchSize; packets++ {
		select {
		case frame := <-src:
			if !sameEpochHeader(first, frame) {
				w.pendingDatagram = frame
				return sample
			}
			payload := frame[epochHdrLen:]
			if len(sample)+2+len(payload) > defaultMaxPayloadSize {
				w.pendingDatagram = frame
				return sample
			}
			sample = appendBatchPacket(sample, payload)
		default:
			if packets == 1 {
				return first
			}
			return sample
		}
	}
	return sample
}

// drainData sends one batched data frame, or a keepalive when idle.
//
// ai-generated: the data lane in place of the inline batching.
func (w *writerState) drainData() {
	if w.data.flush(w.p, w.writeSample) {
		w.idleTicks = 0
		return
	}
	// ai-generated: the held check (olcrtc#26). A tick that had data and
	// waited for the publisher's bucket is not an idle tick, so it does not
	// turn into a keepalive on top of what is already queued.
	if w.data.heldBack {
		return
	}
	w.idleTicks++
	if w.idleTicks >= w.keepaliveEvery {
		w.idleTicks = 0
		hdr := w.p.epochHeader()
		_ = w.writeSample(hdr[:])
	}
}

// dataConn is the single-peer data plane's KCP conn, nil while the plane is
// down or mid-restart.
//
// ai-generated: the whole function.
func (p *streamTransport) dataConn() *kcpConn {
	if rt := p.data.get(); rt != nil {
		return rt.conn
	}
	return nil
}

// dataWindow sets the single-peer data plane's KCP send window.
//
// ai-generated: the whole function.
func (p *streamTransport) dataWindow(segments int) {
	if rt := p.data.get(); rt != nil {
		rt.sess.SetWindowSize(segments, 0)
	}
}

func (p *streamTransport) writerLoop() {
	defer close(p.writerDone)

	ticker := time.NewTicker(p.frameInterval)
	defer ticker.Stop()

	w := newWriterState(p)
	defer w.releasePending()

	for {
		select {
		case <-p.closeCh:
			return
		case <-ticker.C:
			// Priority 0: keep a decodable keyframe flowing for the SFU.
			w.forceKeepalive()
			// Priority 1+2: drain all control frames before any bulk data.
			if !w.drainControl() {
				continue // a control frame is still failing; retry next tick
			}
			// Priority 2: lossy datagrams go before reliable bulk data.
			if !w.drainDatagram() {
				continue
			}
			// Priority 3: drain a batched data frame (or send keepalive).
			w.drainData()
		}
	}
}

// peerWriterPump drains a peer's outbound KCP queue and writes frames to the
// shared video track on the same frame ticker writerLoop uses for the
// client->server path, batching queued frames into one VP8 sample per tick.
// Draining on the ticker (rather than emitting each frame the instant it is
// queued) keeps the per-peer writes interleaved with the keyframe injection
// below and lets batchSampleFrom coalesce segments into full samples. The
// queue goes out through a data lane, which holds it back while the peer
// acknowledges nothing (lane.go). Stops when the peer session is released or
// the transport shuts down.
//
// ai-generated: the lane in place of the pump's own batching.
func (p *streamTransport) peerWriterPump(lane dataLane, done <-chan struct{}) {
	ticker := time.NewTicker(p.frameInterval)
	defer ticker.Stop()

	// Inject a decodable VP8 keyframe on the same cadence writerLoop uses for
	// the client->server path. The server's per-peer bulk path previously
	// emitted only opaque KCP data frames, which never form a decodable VP8
	// keyframe: the SFU's decoder times out (~40s without a keyframe) and stops
	// forwarding the server's track to the subscriber. The client side was
	// kept alive by writerLoop.forceKeepalive; the server side had no
	// equivalent, so the server->client direction collapsed first while the
	// client->server direction kept flowing (issue #95).
	keyframeEvery := max(int(forceKeepalivePeriod/p.frameInterval), 1)
	ticksSinceKeyframe := 0
	defer lane.release()

	for {
		select {
		case <-p.closeCh:
			return
		case <-done:
			return
		case <-ticker.C:
			ticksSinceKeyframe++
			if ticksSinceKeyframe >= keyframeEvery {
				ticksSinceKeyframe = 0
				hdr := p.epochHeader()
				_ = p.writeSampleLocked(hdr[:])
			}
			lane.flush(p, p.writeSampleLocked)
		}
	}
}

func (p *streamTransport) canBatch(frame []byte) bool {
	return len(frame) > epochHdrLen && p.batchSize > 1
}

// batchSampleFrom coalesces up to batchSize KCP frames drained from src into a
// single VP8 sample, bounded by defaultMaxPayloadSize. The shared writerLoop
// drains the single-peer outbound queue; per-peer pumps drain their own queue
// through the same batching so the server->client path is built identically to
// the client.
func (p *streamTransport) batchSampleFrom(
	src <-chan *packetBuffer,
	first *packetBuffer,
	dst []byte,
) ([]byte, *packetBuffer) {
	return p.batchSampleUpTo(src, first, dst, p.batchSize)
}

// batchSampleUpTo is batchSampleFrom with at most limit packets in the
// sample: a data lane under a cap asks for fewer.
//
// ai-generated: the limit, split out of batchSampleFrom (issue #12).
func (p *streamTransport) batchSampleUpTo(
	src <-chan *packetBuffer,
	first *packetBuffer,
	dst []byte,
	limit int,
) ([]byte, *packetBuffer) {
	if !p.canBatch(first.data) {
		return first.data, nil
	}

	sample := prepareBatchBuffer(dst, src, first.data, limit)
	sample = append(sample, first.data[:epochHdrLen]...)
	sample = append(sample, kcpBatchMagic[:]...)
	sample = appendBatchPacket(sample, first.data[epochHdrLen:])
	first.release()

	for packets := 1; packets < limit; packets++ {
		select {
		case frame, ok := <-src:
			if !ok {
				return sample, nil
			}
			if len(frame.data) <= epochHdrLen {
				frame.release()
				continue
			}
			payload := frame.data[epochHdrLen:]
			if len(sample)+2+len(payload) > defaultMaxPayloadSize {
				return sample, frame
			}
			sample = appendBatchPacket(sample, payload)
			frame.release()
		default:
			return sample, nil
		}
	}
	return sample, nil
}

func prepareBatchBuffer(dst []byte, src <-chan *packetBuffer, first []byte, limit int) []byte {
	packetSize := len(first) - epochHdrLen
	packetCount := max(min(limit, len(src)+1), 1)
	want := epochHdrLen + len(kcpBatchMagic) + packetCount*(batchLenLen+packetSize)
	if want > defaultMaxPayloadSize {
		want = defaultMaxPayloadSize
	}
	if cap(dst) < want {
		return make([]byte, 0, want)
	}
	return dst[:0]
}

// batchLenLen is the length prefix appendBatchPacket puts before a packet.
const batchLenLen = 2

func appendBatchPacket(dst, packet []byte) []byte {
	if len(packet) > 0xffff {
		return dst
	}
	var lenBuf [2]byte
	binary.BigEndian.PutUint16(lenBuf[:], uint16(len(packet))) //nolint:gosec // bounded above
	dst = append(dst, lenBuf[:]...)
	return append(dst, packet...)
}
