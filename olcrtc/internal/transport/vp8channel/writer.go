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
	// pendingControl holds a control frame that failed WriteSample and must be
	// retried on the next tick before consuming more frames.
	pendingControl *packetBuffer
	pendingData    *packetBuffer
	batchBuf       []byte
}

func (w *writerState) releasePending() {
	if w.pendingControl != nil {
		w.pendingControl.release()
	}
	if w.pendingData != nil {
		w.pendingData.release()
	}
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
	p.writeMu.Lock()
	defer p.writeMu.Unlock()
	if p.sampleWriter != nil {
		return p.sampleWriter(data)
	}
	return p.track.WriteSample(media.Sample{
		Data:     data,
		Duration: p.frameInterval,
	}) == nil
}

// forceKeepalive emits a clean, fully-decodable VP8 keepalive keyframe at a
// steady cadence even while bulk data is flowing. During a sustained bulk
// transfer every emitted "frame" is the epoch header plus opaque KCP bytes,
// which never forms a decodable VP8 keyframe. The SFU asks for a keyframe (PLI)
// and, receiving none within its decode-timeout (~40 s), stops forwarding the
// track to subscribers. The periodic bare keyframe keeps the SFU's decoder
// satisfied.
func (w *writerState) forceKeepalive() {
	w.ticksSinceKeepalive++
	if w.ticksSinceKeepalive >= w.forceKeepaliveEvery {
		w.ticksSinceKeepalive = 0
		hdr := w.p.epochHeader()
		_ = w.writeSample(hdr[:])
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

// drainData sends one batched data frame, or a keepalive when idle.
func (w *writerState) drainData() {
	frame := w.pendingData
	w.pendingData = nil
	if frame == nil {
		select {
		case frame = <-w.p.data.out:
		default:
		}
	}
	if frame == nil {
		w.idleTicks++
		if w.idleTicks >= w.keepaliveEvery {
			w.idleTicks = 0
			hdr := w.p.epochHeader()
			_ = w.writeSample(hdr[:])
		}
		return
	}
	w.idleTicks = 0
	if !w.p.canBatch(frame.data) {
		_ = w.writeSample(frame.data)
		frame.release()
		return
	}
	sample, pending := w.p.batchSampleFrom(w.p.data.out, frame, w.batchBuf[:0])
	w.pendingData = pending
	_ = w.writeSample(sample)
	w.batchBuf = sample[:0]
}

func (p *streamTransport) writerLoop() {
	defer close(p.writerDone)

	ticker := time.NewTicker(p.frameInterval)
	defer ticker.Stop()

	w := &writerState{
		p:                   p,
		keepaliveEvery:      max(int(keepaliveIdlePeriod/p.frameInterval), 1),
		forceKeepaliveEvery: max(int(forceKeepalivePeriod/p.frameInterval), 1),
	}
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
// below and lets batchSampleFrom coalesce segments into full samples. Stops
// when the peer session is released or the transport shuts down.
func (p *streamTransport) peerWriterPump(out chan *packetBuffer, done <-chan struct{}) {
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
	var batchBuf []byte
	var pending *packetBuffer
	defer func() {
		if pending != nil {
			pending.release()
		}
	}()

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
			frame := pending
			pending = nil
			if frame == nil {
				select {
				case next, ok := <-out:
					if !ok {
						return
					}
					frame = next
				default:
				}
			}
			if frame == nil {
				continue
			}
			if !p.canBatch(frame.data) {
				_ = p.writeSampleLocked(frame.data)
				frame.release()
				continue
			}
			var sample []byte
			sample, pending = p.batchSampleFrom(out, frame, batchBuf[:0])
			_ = p.writeSampleLocked(sample)
			batchBuf = sample[:0]
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
	if !p.canBatch(first.data) {
		return first.data, nil
	}

	sample := p.prepareBatchBuffer(dst, src, first.data)
	sample = append(sample, first.data[:epochHdrLen]...)
	sample = append(sample, kcpBatchMagic[:]...)
	sample = appendBatchPacket(sample, first.data[epochHdrLen:])
	first.release()

	for packets := 1; packets < p.batchSize; packets++ {
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

func (p *streamTransport) prepareBatchBuffer(dst []byte, src <-chan *packetBuffer, first []byte) []byte {
	packetSize := len(first) - epochHdrLen
	packetCount := min(p.batchSize, len(src)+1)
	want := epochHdrLen + len(kcpBatchMagic) + packetCount*(2+packetSize)
	if want > defaultMaxPayloadSize {
		want = defaultMaxPayloadSize
	}
	if cap(dst) < want {
		return make([]byte, 0, want)
	}
	return dst[:0]
}

func appendBatchPacket(dst, packet []byte) []byte {
	if len(packet) > 0xffff {
		return dst
	}
	var lenBuf [2]byte
	binary.BigEndian.PutUint16(lenBuf[:], uint16(len(packet))) //nolint:gosec // bounded above
	dst = append(dst, lenBuf[:]...)
	return append(dst, packet...)
}
