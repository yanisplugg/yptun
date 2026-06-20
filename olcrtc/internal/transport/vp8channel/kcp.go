// Package vp8channel provides byte transport over VP8 video frames using KCP.
/*
ЯНДЕКС РАБ ИЛИ КТО ТЫ НАУЙ СУКА КАРОЧЕ ХУЙЛО КЕБАНОГЕ ИЗ ЯНДЕКС ЧИТАЮЩИЙ ЭТО
МЫ ОСЕМЕНИМ ВТОЮ МАМАШКУ МЕРТВУЮ
*/
package vp8channel

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"sync"

	kcp "github.com/xtaci/kcp-go/v5"
)

// Both peers establish a KCP session with the same convid. KCP does not
// require a handshake - packets are matched by conv field, so a static
// constant gives us a symmetrical P2P setup.
const kcpConvID = 0xC0FFEE01

// KCP tuning targets a lossy, bursty carrier (VP8 over an SFU). The defaults
// are TCP-like and recover slowly after burst losses.
const (
	// kcp-go hardcodes mtuLimit=1500, so SetMtu() above this is silently
	// clamped. Stay below that with headroom for KCP overhead (24 bytes).
	kcpMTU = 1400

	// Send/receive window in segments, sized to the bandwidth-delay product
	// of the policed video path. VP8 over QR encoding has very low throughput
	// (~0.3 MB/s observed). We use a moderate send window to balance:
	// - Large enough for reasonable throughput (don't underutilize the pipe)
	// - Small enough that control-plane pongs can get through within liveness timeout
	// With 512 segments * 1400 bytes = ~716KB in-flight, and ~0.3 MB/s throughput,
	// data sits in queue for ~2-3 seconds, giving control a chance to pass.
	kcpSndWnd = 512
	kcpRcvWnd = 1024

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
	writeMu   sync.Mutex // serializes length-prefix + payload writes
	closeOnce sync.Once
}

func startKCP(out chan<- []byte, onData func([]byte), epochHdr [epochHdrLen]byte) (*kcpRuntime, error) {
	c := newKCPConn(out, inboundQueueSize, epochHdr)

	sess, err := kcp.NewConn3(kcpConvID, fakeUDPAddr(), nil, 0, 0, c)
	if err != nil {
		_ = c.Close()
		return nil, fmt.Errorf("kcp new conn: %w", err)
	}

	// nodelay=1, interval=5ms, fast resend=2, congestion control OFF (nc=1).
	// KCP does NOT regulate the send rate here - the writerLoop byte pacer
	// does, fed at a fixed rate just under the carrier's policer knee. KCP's
	// own loss-based congestion control is the wrong controller for a hard
	// policer: with nc=0 the unavoidable ~4% drops collapsed cwnd and starved
	// the wire to ~45 KiB/s. With nc=1 KCP just keeps the BDP-sized window
	// full and retransmits the few losses; the pacer caps the rate so we
	// never overdrive the policer into its collapse zone.
	// nodelay=1, interval=20ms (slower for QR-encoded VP8), fast resend=2, congestion control OFF (nc=1).
	// QR-encoded VP8 has very low throughput (~0.3 MB/s), so we use a larger interval
	// to allow batching and reduce overhead. KCP's own loss-based congestion control
	// is disabled (nc=1) because the carrier has hard bandwidth limits; the writerLoop
	// byte pacer handles rate limiting.
	sess.SetNoDelay(1, 20, 2, 1)
	sess.SetWindowSize(kcpSndWnd, kcpRcvWnd)
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

// send queues an application message for reliable delivery. The length
// prefix + payload pair is written under a mutex so that interleaved
// concurrent senders cannot tear the framing.
func (r *kcpRuntime) send(msg []byte) error {
	if len(msg) > kcpMaxMessage {
		return ErrKCPMessageTooLarge
	}
	var hdr [kcpLenPrefix]byte
	binary.BigEndian.PutUint32(hdr[:], uint32(len(msg))) //nolint:gosec,lll // G115: bounded conversion verified by surrounding logic

	r.writeMu.Lock()
	defer r.writeMu.Unlock()

	if _, err := r.sess.Write(hdr[:]); err != nil {
		return fmt.Errorf("kcp write header: %w", err)
	}
	if _, err := r.sess.Write(msg); err != nil {
		return fmt.Errorf("kcp write payload: %w", err)
	}
	return nil
}

func (r *kcpRuntime) close() {
	r.closeOnce.Do(func() {
		_ = r.sess.Close()
		_ = r.conn.Close()
	})
}
