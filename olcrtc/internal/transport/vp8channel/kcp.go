package vp8channel

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"sync"

	kcp "github.com/xtaci/kcp-go/v5"

	runtimecfg "github.com/openlibrecommunity/olcrtc/internal/runtime"
)

// kcpRcvWindow returns the receive window in segments for this host's memory
// profile.
func kcpRcvWindow() int {
	if runtimecfg.BuffersAreConstrained() {
		return kcpConstrainedRcvWnd
	}
	return kcpRcvWnd
}

// kcpSendWindow returns the send window in segments: this host's profile, cut
// to a round trip of the ceiling when the writer publishes under one.
// ai-generated: kcpSendWindow (olcrtc#26).
func kcpSendWindow(paced bool) int {
	wnd := kcpSndWnd
	if runtimecfg.BuffersAreConstrained() {
		wnd = kcpConstrainedSndWnd
	}
	if paced && kcpPacedSndWnd < wnd {
		wnd = kcpPacedSndWnd
	}
	return wnd
}

// inboundQueueSizeFor is the packet queue between the track reader and KCP
// for this host's memory profile; see constrainedInboundQueueSize.
func inboundQueueSizeFor() int {
	if runtimecfg.BuffersAreConstrained() {
		return constrainedInboundQueueSize
	}
	return inboundQueueSize
}

// outboundQueueSizeFor is the data plane's queue between KCP and the paced
// writer for this host's memory profile; see constrainedOutboundQueueSize.
func outboundQueueSizeFor() int {
	if runtimecfg.BuffersAreConstrained() {
		return constrainedOutboundQueueSize
	}
	return outboundQueueSize
}

// Both peers establish a KCP session with the same convid. KCP does not
// require a handshake - packets are matched by conv field, so a static
// constant gives us a symmetrical P2P setup.
const kcpConvID = 0xC0FFEE01

// KCP tuning targets a lossy, bursty provider (VP8 over an SFU). The defaults
// are TCP-like and recover slowly after burst losses.
const (
	// kcp-go hardcodes mtuLimit=1500, so SetMtu() above this is silently
	// clamped. Stay below that with headroom for KCP overhead (24 bytes).
	kcpMTU = 1400

	// Send/receive window in segments. Bulk data runs on its own KCP session,
	// isolated from the control plane (ping/pong has a separate startKCP and is
	// drained with priority by writerLoop), so a large data window no longer
	// starves control liveness the way it did before that split (issue #95).
	// One VP8 frame can carry many KCP segments and ACKs only trickle back at
	// frame cadence, so a generous window is what keeps the policed path full
	// and lets throughput reach the SFU's real ceiling (~10 Mbit on Telemost)
	// instead of being clamped to a fraction of it.
	kcpSndWnd = 4096
	kcpRcvWnd = 4096

	// ai-generated: kcpPacedSndWnd and this comment (olcrtc#26).
	// kcpPacedSndWnd is the send window when the writer publishes under a
	// ceiling the engine's service polices. The send window is what a sender
	// can have unacknowledged, so at a fixed publish rate it is also how long
	// a byte handed to KCP waits behind the bulk already queued: 4096
	// segments is 5.7 MB, which an unpoliced SFU drains at whatever rate it
	// takes but a paced writer turns into 5 s of buffer in front of every new
	// stream - the gate's connect on top of six downloads went from under
	// 4 s to 9.75 s, past its 5 s bound, with nothing else changed. 512
	// segments is ~0.7 MB: a round trip of the ceiling even on WB Stream's
	// 380 ms path, and well under a second of standing queue.
	kcpPacedSndWnd = 512

	// The same windows on a host that is killed for using memory rather than
	// swapped: 4096 segments is about 5.7 MB per direction, and this
	// transport runs two KCP sessions (data and control), which on its own
	// is more than an iOS packet tunnel extension is allowed in total. 512
	// segments is ~0.7 MB, still several times the bandwidth-delay product
	// of the relays this transport rides: 5-10 Mbit/s at 100-200 ms is 90-180
	// segments. Measured on the phone profile (mobile.Runtime, lean build,
	// Telemost room, six downloads then three uploads through the DE origin):
	// the upload peak's live heap 9.1 -> 8.3 MB and HeapInuse 11.5 -> 10.8,
	// with kcp-go's segment pool the item that shrank; the download peak
	// unchanged, and every stream's speed inside the run-to-run noise
	// (downloads 170-200 KB/s, uploads 350-390 KB/s either way). 1024 had
	// been the previous step down from 4096. See runtime.UseConstrainedBuffers.
	kcpConstrainedSndWnd = 512
	kcpConstrainedRcvWnd = 512

	// Length prefix for our message framing on top of KCP stream mode.
	// We use stream mode because UDPSession.Write fragments messages > MSS
	// outside of kcp.Send, which destroys the frg field that message mode
	// relies on for boundary preservation. Adding our own length-prefix
	// framing sidesteps that bug entirely.
	kcpLenPrefix = 4

	// Hard cap on a single message. Anything larger would require an
	// unbounded reassembly buffer on the receiver and is almost certainly
	// a protocol error upstream.
	kcpMaxMessage = 8 * 1024 * 1024
)

// ErrKCPMessageTooLarge is returned by send when the message exceeds
// kcpMaxMessage.
var ErrKCPMessageTooLarge = errors.New("vp8channel: kcp message exceeds maximum size")

// kcpRuntime owns the KCP session and the goroutine that pumps reassembled
// messages from KCP up to cfg.OnData.
type kcpRuntime struct {
	conn      *kcpConn
	sess      *kcp.UDPSession
	readDone  chan struct{}
	writeMu   sync.Mutex // serializes framed writes; also guards writeBuf
	writeBuf  []byte     // ai-generated: framing scratch, reused under writeMu
	closeOnce sync.Once
}

// startKCP brings up one KCP session. sndWnd is the send window in segments;
// 0 takes this host's profile. ai-generated: the sndWnd parameter (olcrtc#26).
func startKCP(
	out chan<- *packetBuffer, onData func([]byte), epochHdr [epochHdrLen]byte, sndWnd int,
) (*kcpRuntime, error) {
	return startKCPAcks(out, nil, onData, epochHdr, sndWnd)
}

// startKCPAcks is startKCP with the packets that only acknowledge the peer
// queued on acks, apart from the data (see kcpConn.acks). A nil acks queues
// them with the data.
//
// ai-generated: the acks queue (issue #12).
func startKCPAcks(
	out, acks chan<- *packetBuffer, onData func([]byte), epochHdr [epochHdrLen]byte, sndWnd int,
) (*kcpRuntime, error) {
	c := newKCPConn(out, inboundQueueSizeFor(), epochHdr)
	c.acks = acks

	sess, err := kcp.NewConn3(kcpConvID, fakeUDPAddr(), nil, 0, 0, c)
	if err != nil {
		_ = c.Close()
		return nil, fmt.Errorf("kcp new conn: %w", err)
	}

	// nodelay=1, interval=5ms, fast resend=2, congestion control OFF (nc=1).
	// The frame ticker already paces emission at the VP8 frame cadence, so the
	// 5ms KCP tick just keeps scheduling latency low; a slower tick only adds
	// dead time before retransmits and ACKs. nc=1 disables KCP's loss-based
	// congestion control because the provider is a hard policer, not a fair
	// queue: with nc=0 the unavoidable ~4% drops collapsed cwnd and starved
	// the wire. With nc=1 KCP keeps the window full and retransmits the few
	// losses, letting throughput reach the SFU's real ceiling.
	sess.SetNoDelay(1, 5, 2, 1)
	if sndWnd <= 0 {
		sndWnd = kcpSendWindow(false)
	}
	sess.SetWindowSize(sndWnd, kcpRcvWindow())
	sess.SetMtu(kcpMTU)
	// Upstream marked SetStreamMode deprecated without providing a replacement;
	// stream framing is still required for our wire format.
	sess.SetStreamMode(true) //nolint:staticcheck // SA1019: no replacement upstream.
	sess.SetACKNoDelay(true)
	sess.SetWriteDelay(false)

	rt := &kcpRuntime{
		conn:     c,
		sess:     sess,
		readDone: make(chan struct{}),
	}

	go rt.readLoop(onData)

	return rt, nil
}

func (r *kcpRuntime) readLoop(onData func([]byte)) {
	defer close(r.readDone)

	var hdr [kcpLenPrefix]byte
	for {
		if _, err := io.ReadFull(r.sess, hdr[:]); err != nil {
			return
		}
		size := binary.BigEndian.Uint32(hdr[:])
		if size == 0 {
			continue
		}
		if size > kcpMaxMessage {
			return
		}
		payload := make([]byte, size)
		if _, err := io.ReadFull(r.sess, payload); err != nil {
			return
		}
		if onData != nil {
			onData(payload)
		}
	}
}

// deliver hands a wire payload (already reassembled out of VP8 RTP) to KCP.
func (r *kcpRuntime) deliver(payload []byte) {
	r.conn.deliver(payload)
}

// setHeader re-points the outgoing frame header so subsequent KCP packets are
// addressed to a specific destination epoch (see kcpConn.setHeader).
func (r *kcpRuntime) setHeader(hdr [epochHdrLen]byte) {
	r.conn.setHeader(hdr)
}

// send queues an application message for reliable delivery. Length prefix and
// payload go out in one Write: two writes under a mutex stop concurrent senders
// interleaving, but do not make the pair atomic against failure. If the header
// lands and the payload does not, the stream carries a prefix for bytes that
// were never sent and the reader cannot resynchronise.
//
// ai-generated: replaced the two Write calls with a single framed write into a
// reused buffer; added writeBuf and the cap check.
func (r *kcpRuntime) send(msg []byte) error {
	if len(msg) > kcpMaxMessage {
		return ErrKCPMessageTooLarge
	}

	r.writeMu.Lock()
	defer r.writeMu.Unlock()

	need := kcpLenPrefix + len(msg)
	if cap(r.writeBuf) < need {
		r.writeBuf = make([]byte, need)
	}
	buf := r.writeBuf[:need]
	binary.BigEndian.PutUint32(buf[:kcpLenPrefix], uint32(len(msg))) //nolint:gosec,lll // G115: bounded conversion verified by surrounding logic
	copy(buf[kcpLenPrefix:], msg)

	if _, err := r.sess.Write(buf); err != nil {
		return fmt.Errorf("kcp write framed message: %w", err)
	}
	return nil
}

func (r *kcpRuntime) close() {
	r.closeOnce.Do(func() {
		_ = r.sess.Close()
		_ = r.conn.Close()
	})
}

// kcpPlane owns one KCP session together with the outbound queue that carries
// its packets. The transport runs two of them - bulk data and control - with
// identical lifecycle rules, so start/restart/drain/close live here once
// instead of being written twice with only the field names changed.
type kcpPlane struct {
	out chan *packetBuffer
	// acks queues what only acknowledges the peer on a data plane, nil on
	// the control plane (ai-generated: issue #12).
	acks   chan *packetBuffer
	onData func([]byte)
	// sndWnd is the KCP send window its sessions run with; 0 takes this
	// host's profile. The transport sets it. ai-generated (olcrtc#26).
	sndWnd int

	// lifecycleMu serializes start/restart/close. Without it two concurrent
	// restarts - a provider reconnect and an upper-layer ResetPeer fire
	// together during recovery - both build a runtime and the loser is
	// overwritten in place, stranding its goroutines and KCP windows for the
	// lifetime of the process. mu stays narrow: it only guards the pointer
	// read on the send hot path.
	lifecycleMu sync.Mutex
	closed      bool

	mu   sync.RWMutex
	rt   *kcpRuntime
	once sync.Once
}

func newKCPPlane(queueSize int, onData func([]byte)) *kcpPlane {
	return &kcpPlane{out: make(chan *packetBuffer, queueSize), onData: onData}
}

// newDataPlane is a plane whose runtimes queue what only acknowledges the
// peer on acks, for the data lane to write ahead of the data.
//
// ai-generated: the whole function (issue #12).
func newDataPlane(queueSize int, onData func([]byte)) *kcpPlane {
	p := newKCPPlane(queueSize, onData)
	p.acks = make(chan *packetBuffer, ackQueueSize)
	return p
}

// get returns the live runtime, or nil when the plane has not started (or is
// mid-restart).
func (p *kcpPlane) get() *kcpRuntime {
	p.mu.RLock()
	defer p.mu.RUnlock()
	return p.rt
}

// set installs rt directly. Used by tests to attach a hand-built runtime.
func (p *kcpPlane) set(rt *kcpRuntime) {
	p.mu.Lock()
	p.rt = rt
	p.mu.Unlock()
}

// start brings the plane up exactly once however many times Connect runs. The
// first result reports whether this call was the one that started it.
func (p *kcpPlane) start(hdr [epochHdrLen]byte) (bool, error) {
	var (
		started bool
		err     error
	)

	p.lifecycleMu.Lock()
	defer p.lifecycleMu.Unlock()

	p.once.Do(func() {
		if p.closed {
			return
		}
		var rt *kcpRuntime
		rt, err = startKCPAcks(p.out, p.acks, p.onData, hdr, p.sndWnd)
		if err != nil {
			return
		}
		p.set(rt)
		started = true
	})

	return started, err
}

// restart drops queued packets and replaces the KCP state machine with a
// fresh one stamped with hdr.
func (p *kcpPlane) restart(hdr [epochHdrLen]byte) {
	p.lifecycleMu.Lock()
	defer p.lifecycleMu.Unlock()

	if p.closed {
		return
	}

	p.drain()

	p.mu.Lock()
	old := p.rt
	p.rt = nil
	p.mu.Unlock()

	if old != nil {
		old.close()
	}

	rt, err := startKCPAcks(p.out, p.acks, p.onData, hdr, p.sndWnd)
	if err != nil {
		return
	}
	p.set(rt)
}

// drain discards everything still queued for the paced writer.
func (p *kcpPlane) drain() {
	for {
		select {
		case packet := <-p.out:
			packet.release()
		case packet := <-p.acks: // ai-generated: never ready on a plane without acks
			packet.release()
		default:
			return
		}
	}
}

func (p *kcpPlane) close() {
	p.lifecycleMu.Lock()
	defer p.lifecycleMu.Unlock()

	p.closed = true

	if rt := p.get(); rt != nil {
		rt.close()
	}
}
