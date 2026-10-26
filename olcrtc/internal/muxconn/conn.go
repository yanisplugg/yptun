// Package muxconn adapts a link.Link into an io.ReadWriteCloser suitable for
// driving a smux session. The wrapper applies AEAD on every wire-bound write
// and inverts it on every received message before exposing the bytes as a
// byte stream.
//
// Link semantics are message-oriented: each Send produces exactly one OnData
// on the peer. smux operates on a pure byte stream (header + payload may be
// glued or split across reads). We bridge by:
//
//   - Treating each Push as an opaque chunk handed off via a channel that
//     Read drains in arbitrary slices, retaining any tail bytes that did
//     not fit the caller's buffer for the next Read.
//   - Letting smux's sendLoop call Write once per frame; we encrypt and hand
//     the whole buffer to the link as a single message. Length boundaries
//     are preserved end-to-end by the transport (KCP length-prefix framing
//     in vp8channel, native message boundaries in datachannel). A link that
//     asks for a write interval gets the small frames written close together
//     in one message instead (see batcher); the peer reads a byte stream and
//     cannot tell.
package muxconn

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"runtime"
	"slices"
	"sync"
	"sync/atomic"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/crypto"
	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
)

// ErrClosed is returned from Read/Write after the conn has been closed.
var ErrClosed = errors.New("muxconn: closed")

// ErrWriteTimeout is returned from Write when the transport never reports
// CanSend within writeReadyTimeout. It satisfies net.Error with Timeout()
// true, which is how smux (and the net package contract) recognises a
// deadline rather than a protocol fault.
var ErrWriteTimeout net.Error = writeTimeoutError{}

type writeTimeoutError struct{}

func (writeTimeoutError) Error() string { return "muxconn: write timeout waiting for transport" }

func (writeTimeoutError) Timeout() bool { return true }

func (writeTimeoutError) Temporary() bool { return true }

// errReadClosed is what Read reports once the conn is closed and drained.
//
// smux stores whatever error the underlying conn returns and hands it to
// stream readers verbatim; its recvLoop never compares against io.EOF, so
// widening the error is safe there. We still unwrap to io.EOF because
// every io.Reader consumer (and the io contract) treats EOF as the clean
// end of a stream, while errors.Is(err, ErrClosed) now lets callers tell a
// deliberate close from an aborted link.
var errReadClosed error = closedReadError{}

type closedReadError struct{}

func (closedReadError) Error() string { return "muxconn: closed (EOF)" }

func (closedReadError) Unwrap() []error { return []error{io.EOF, ErrClosed} }

const (
	dataRecordAAD    = "olcrtc/muxconn/v2/data"
	controlRecordAAD = "olcrtc/muxconn/v2/control"

	// inboundQueue is the buffered capacity of the Push -> Read pipeline.
	// It absorbs short Read stalls without applying back-pressure to the
	// transport callback. Frames are typically smux-sized (up to 32 KiB),
	// so 128 amounts to a few MiB of in-flight data, which is
	// enough for sustained throughput without letting a stuck reader retain
	// a large pool-backed working set per connection.
	inboundQueue = 128

	// pooledFrameCap is the capacity each pooled plaintext buffer is born
	// with. It is sized to fit the largest smux frame any of our
	// transports will deliver after AEAD overhead is stripped (datachannel
	// caps at 12 KiB on the wire, vp8channel at 60 KiB; we round up to
	// give Open room to write in place without growing the slice).
	pooledFrameCap = 64 * 1024

	// writeReadyTimeout bounds how long Write waits for the transport to
	// accept data. A transport that cannot send for this long is dead in
	// every practical sense - it exceeds the smux keepalive timeout - so
	// failing the write (and with it the session) beats parking the smux
	// send loop for the lifetime of the process.
	writeReadyTimeout = 30 * time.Second

	// decryptLogInterval bounds how often a conn reports frames that failed
	// to decrypt. Any participant in the same SFU room can spray junk into
	// our channel, so the steady state must not be one log line per frame.
	decryptLogInterval = 30 * time.Second

	// The smux frame header, as xtaci/smux lays it out: version, command,
	// little-endian length, little-endian stream id. smux writes every frame
	// with one Write and a record carries whole frames, one or, from a
	// batching sender, several, so a record opened here starts with this
	// header.
	smuxHeaderSize = 8
	smuxCmdSYN     = 0
	smuxCmdPSH     = 2

	// smuxFirstStream is the id of the first stream a client's smux session
	// opens: a client numbers its streams from 1 up by two, and the first
	// id it hands out is 3.
	// ai-generated (olcrtc#49).
	smuxFirstStream = 3
)

// frameBufPool recycles plaintext buffers between Push (decrypts a wire
// frame into a buffer) and Read (consumes the buffer fully then returns
// it). It is global so all Conn instances share the same hot cache -
// most clients in the same process talk to a handful of peers, and
// per-Conn pools fragment the warm set unnecessarily.
var frameBufPool = sync.Pool{ //nolint:gochecknoglobals // intentional process-wide buffer pool
	New: func() any {
		b := make([]byte, 0, pooledFrameCap)
		return &b
	},
}

func acquireFrameBuf() *[]byte {
	bp, ok := frameBufPool.Get().(*[]byte)
	if !ok {
		b := make([]byte, 0, pooledFrameCap)
		return &b
	}
	*bp = (*bp)[:0]
	return bp
}

func releaseFrameBuf(bp *[]byte) {
	if bp == nil {
		return
	}
	// Drop oversized buffers so a one-off huge frame can't poison the
	// pool's working set forever.
	if cap(*bp) > pooledFrameCap*2 {
		return
	}
	*bp = (*bp)[:0]
	frameBufPool.Put(bp)
}

// Conn is an io.ReadWriteCloser over a [transport.Transport] with v2 record protection.
//
// Push produces decrypted plaintext frames into an internal channel; Read
// drains the channel and slices each frame across as many caller buffers
// as needed. The hot path is lock-free: a single producer (the transport
// callback) and a single consumer (smux's read loop) communicate via a
// buffered channel without any cond/mutex ping-pong.
//
// Plaintext buffers are recycled through frameBufPool: Push borrows a
// buffer to decrypt into, ships it through the channel, and Read returns
// the buffer to the pool once its caller has consumed all the bytes.
type Conn struct {
	ln      transport.Transport
	send    func([]byte) error
	canSend func() bool // if nil, uses ln.CanSend
	// group carries the candidate ring and the pin decision, shared with
	// every conn of the same peer. Single-key callers pass a PrePinned
	// group, which keeps the single-key behaviour exact.
	group *PinGroup
	aad   []byte

	in        chan *[]byte
	closeOnce sync.Once
	closeCh   chan struct{}
	closed    atomic.Bool

	// leftoverBuf holds the pool buffer whose tail is still in
	// `leftover`. When `leftover` empties we return leftoverBuf to the
	// pool and clear both fields. Touched only by Read.
	leftoverBuf *[]byte
	leftover    []byte

	// inBytes counts every byte of plaintext opened from the peer, and
	// payloadBytes only the data of PSH frames addressed to a stream. The
	// control-stream liveness check reads the second, through PayloadBytes,
	// which says why the first is not enough.
	inBytes      atomic.Uint64
	payloadBytes atomic.Uint64

	// opened is set once a SYN from the peer has opened a stream on this
	// conn, for PushSession: the session's first stream opened after that is
	// the session after it.
	// ai-generated (olcrtc#49).
	opened atomic.Bool

	// writeStalls counts Writes that waited out the full send deadline
	// without the transport ever accepting data, and is cleared by the
	// first Write that gets through. The liveness check reads it through
	// SendStalled. See that method for why it has to.
	writeStalls atomic.Uint32

	// decrypt failure accounting for the rate-limited log in Push, and by
	// kind for the client's handshake classifier (see DecryptStats).
	decryptFails  atomic.Uint64
	decryptLogged atomic.Uint64
	decryptLogAt  atomic.Int64
	badMagic      atomic.Uint64
	authFailed    atomic.Uint64

	// reordering accounting for the rate-limited log in noteReordering.
	reorderLogged atomic.Uint64
	reorderLogAt  atomic.Int64

	// writeTimeout overrides writeReadyTimeout. Zero means the default;
	// only tests set it.
	writeTimeout time.Duration

	// batch gathers small frames into shared records when the link asks for
	// a write interval; nil sends every Write as its own record.
	batch *batcher
}

func (c *Conn) sendDeadline() time.Duration {
	if c.writeTimeout > 0 {
		return c.writeTimeout
	}
	return writeReadyTimeout
}

// New wires a Conn over the given transport. Push must be set as the
// transport's OnData callback before this conn is used.
func New(ln transport.Transport, keys *crypto.KeySet) *Conn {
	return NewGrouped(ln, PrePinned(keys, ""))
}

// NewGrouped is New with an explicit pin group (multi-key server paths).
func NewGrouped(ln transport.Transport, g *PinGroup) *Conn {
	c := &Conn{
		ln:      ln,
		send:    ln.Send,
		group:   g,
		aad:     []byte(dataRecordAAD),
		in:      make(chan *[]byte, inboundQueue),
		closeCh: make(chan struct{}),
	}
	c.startBatching(ln.Features())
	return c
}

// NewControl wires a Conn that routes through the transport's isolated
// control-plane channel (transport.ControlPlane). Returns nil if the
// transport does not implement ControlPlane.
func NewControl(ln transport.Transport, keys *crypto.KeySet) *Conn {
	return NewControlGrouped(ln, PrePinned(keys, ""))
}

// NewControlGrouped is NewControl with an explicit pin group.
func NewControlGrouped(ln transport.Transport, g *PinGroup) *Conn {
	cp, ok := ln.(transport.ControlPlane)
	if !ok {
		return nil
	}
	c := &Conn{
		ln:      ln,
		send:    cp.ControlSend,
		canSend: cp.ControlCanSend,
		group:   g,
		aad:     []byte(controlRecordAAD),
		in:      make(chan *[]byte, inboundQueue),
		closeCh: make(chan struct{}),
	}
	cp.SetControlOnData(func(data []byte) { c.Push(data) })
	return c
}

// NewPeer wires a Conn whose writes are addressed to a specific transport peer.
func NewPeer(ln transport.PeerTransport, keys *crypto.KeySet, peerID string) *Conn {
	return NewPeerGrouped(ln, PrePinned(keys, ""), peerID)
}

// NewPeerGrouped is NewPeer with an explicit pin group.
func NewPeerGrouped(ln transport.PeerTransport, g *PinGroup, peerID string) *Conn {
	c := &Conn{
		ln: ln,
		send: func(data []byte) error {
			return ln.SendTo(peerID, data)
		},
		group:   g,
		aad:     []byte(dataRecordAAD),
		in:      make(chan *[]byte, inboundQueue),
		closeCh: make(chan struct{}),
	}
	c.startBatching(ln.Features())
	return c
}

// NewPeerControlUnbound wires a Conn to the per-peer control plane of a
// transport.PeerControlPlane. Returns nil if the transport does not implement
// PeerControlPlane.
//
// Unlike NewControl, the returned conn is NOT bound to the transport: the
// caller must feed it via Push. PeerControlPlane exposes a single
// SetControlOnPeerData callback covering every peer, so registering here
// would clobber the caller's demultiplexer - hence the name.
func NewPeerControlUnbound(ln transport.Transport, keys *crypto.KeySet, peerID string) *Conn {
	return NewPeerControlUnboundGrouped(ln, PrePinned(keys, ""), peerID)
}

// NewPeerControlUnboundGrouped is NewPeerControlUnbound with an explicit pin group.
func NewPeerControlUnboundGrouped(ln transport.Transport, g *PinGroup, peerID string) *Conn {
	cp, ok := ln.(transport.PeerControlPlane)
	if !ok {
		return nil
	}
	c := &Conn{
		ln: ln,
		send: func(data []byte) error {
			return cp.ControlSendTo(peerID, data)
		},
		canSend: func() bool {
			return cp.ControlPeerCanSend(peerID)
		},
		group:   g,
		aad:     []byte(controlRecordAAD),
		in:      make(chan *[]byte, inboundQueue),
		closeCh: make(chan struct{}),
	}
	return c
}

// Group exposes the conn's pin group (metering reads the pinned key id).
func (c *Conn) Group() *PinGroup { return c.group }

// Push hands an encrypted wire payload (one OnData event) to the conn.
//
// On the producer side: borrow a pooled plaintext buffer, decrypt into
// it, then either deliver via the inbound channel or, if the caller has
// Close'd, return the buffer to the pool. Blocking forever on a wedged
// reader would wedge the transport callback and trip its watchdog, so we
// also bail on closeCh.
func (c *Conn) Push(ciphertext []byte) {
	if bufPtr := c.open(ciphertext); bufPtr != nil {
		c.take(bufPtr)
	}
}

// PushSession is Push for a peer that can start its smux session over under
// the same transport identity, which is what a client does when it gives a
// handshake up and tries again: a server keys its peer sessions on that
// identity, so the new session's records arrive on the conn of the old one.
// A client opens its session's first stream alone, for its hello, before any
// other stream can exist, so a record that opens that stream on a conn that
// has seen a stream opened belongs to the session after the one this conn
// carries. That record is not taken: its plaintext is returned, for the conn
// built for the new session to take (PushOpened), and every other record is
// taken as Push takes it and nil returned.
//
// No other stream id says which session a record is from. smux hands an id
// out under a lock it lets go before it writes the SYN, and its shaper writes
// round robin across streams, so the SYNs of streams opened at once - a
// tunnel per SOCKS connection, each on its own goroutine - reach the wire in
// no set order: a SYN below one already seen is still the session's own.
//
// ai-generated: the whole method (olcrtc#49).
func (c *Conn) PushSession(ciphertext []byte) []byte {
	bufPtr := c.open(ciphertext)
	if bufPtr == nil {
		return nil
	}
	first, opens := openedStreams(*bufPtr)
	if first && c.opened.Load() {
		record := slices.Clone(*bufPtr)
		releaseFrameBuf(bufPtr)
		return record
	}
	if opens {
		c.opened.Store(true)
	}
	c.take(bufPtr)
	return nil
}

// PushOpened takes a record another conn of the same peer has opened and
// handed back (PushSession), as if this conn had opened it.
//
// ai-generated: the whole method (olcrtc#49).
func (c *Conn) PushOpened(record []byte) {
	bufPtr := acquireFrameBuf()
	*bufPtr = append(*bufPtr, record...)
	if _, opens := openedStreams(record); opens {
		c.opened.Store(true)
	}
	c.take(bufPtr)
}

// open decrypts one wire payload into a pooled buffer, pinning the group's
// key on the first record any ring entry opens. A payload that does not open
// is counted and nil returned.
//
// ai-generated: split out of Push as it was, for PushSession (olcrtc#49).
func (c *Conn) open(ciphertext []byte) *[]byte {
	bufPtr := acquireFrameBuf()
	var (
		pt  []byte
		err error
	)
	if entry := c.group.Pinned(); entry != nil {
		pt, err = entry.Keys.OpenInto(*bufPtr, ciphertext, c.aad)
	} else {
		// Pairing is still open: the first record any ring entry opens
		// becomes the pin for this conn's whole group (data and control).
		var matched *crypto.RingEntry
		pt, matched, err = c.group.ring.TryOpen(*bufPtr, ciphertext, c.aad)
		if err == nil {
			c.group.pin(matched)
		}
	}
	if err != nil {
		releaseFrameBuf(bufPtr)
		c.noteDecryptFailure(len(ciphertext), err)
		return nil
	}
	*bufPtr = pt
	return bufPtr
}

// take counts an opened record and queues it for Read.
//
// ai-generated: split out of Push as it was, for PushSession (olcrtc#49).
func (c *Conn) take(bufPtr *[]byte) {
	pt := *bufPtr
	c.inBytes.Add(uint64(len(pt)))
	c.payloadBytes.Add(streamPayloadLen(pt))
	c.noteReordering()
	if c.closed.Load() {
		releaseFrameBuf(bufPtr)
		return
	}
	select {
	case c.in <- bufPtr:
	case <-c.closeCh:
		releaseFrameBuf(bufPtr)
	}
}

// ReorderStats reports the record reordering the pinned key has seen on this
// conn's lane: records skipped over and records that arrived late. The
// difference is what the window is still missing. Zero until the peer's key
// is pinned.
func (c *Conn) ReorderStats() crypto.ReorderStats {
	if c == nil {
		return crypto.ReorderStats{}
	}
	entry := c.group.Pinned()
	if entry == nil {
		return crypto.ReorderStats{}
	}
	return entry.Keys.ReorderStats(c.aad)
}

// noteReordering logs, at most once per decryptLogInterval, when records on
// this lane have arrived out of order since the last report. Every record is
// a whole smux frame, so a relay that drops or reorders messages does not
// corrupt a frame, it removes or swaps one - which the control stream reports
// as a frame that is too large, because the length it read was the body of
// the frame it should have got first. This line is how that report gets its
// cause: reordering fills its gaps, loss never does.
func (c *Conn) noteReordering() {
	stats := c.ReorderStats()
	total := stats.Skipped + stats.Late
	if total == 0 || total == c.reorderLogged.Load() {
		return
	}
	now := time.Now().UnixNano()
	last := c.reorderLogAt.Load()
	if last != 0 && now-last < int64(decryptLogInterval) {
		return
	}
	if !c.reorderLogAt.CompareAndSwap(last, now) {
		return
	}
	c.reorderLogged.Store(total)
	logger.Debugf("muxconn: records out of order on this lane so far: %d skipped, %d arrived late, %d still missing",
		stats.Skipped, stats.Late, stats.Skipped-min(stats.Late, stats.Skipped))
}

// noteDecryptFailure records an undecryptable frame and logs the first one
// plus a periodic summary carrying how many were dropped since the last
// report. Frames from unrelated room participants are expected traffic, not
// an error worth one line each.
func (c *Conn) noteDecryptFailure(size int, err error) {
	switch {
	case errors.Is(err, crypto.ErrBadRecordMagic), errors.Is(err, crypto.ErrRecordTooShort):
		c.badMagic.Add(1)
	case errors.Is(err, crypto.ErrAuthentication), errors.Is(err, crypto.ErrNoRingMatch):
		c.authFailed.Add(1)
	}
	total := c.decryptFails.Add(1)
	now := time.Now().UnixNano()
	if total == 1 {
		c.decryptLogAt.Store(now)
		c.decryptLogged.Store(total)
		logger.Warnf("muxconn: decrypt failed len=%d: %v", size, err)
		return
	}
	last := c.decryptLogAt.Load()
	if now-last < int64(decryptLogInterval) {
		return
	}
	if !c.decryptLogAt.CompareAndSwap(last, now) {
		return
	}
	dropped := total - c.decryptLogged.Swap(total)
	logger.Warnf("muxconn: decrypt failed for %d more frames in the last %s, latest len=%d: %v",
		dropped, decryptLogInterval, size, err)
}

// InboundBytes reports the plaintext this conn has opened from the peer since
// it was created: every frame that authenticated, whatever it carried. It
// only ever grows. Liveness no longer reads it; see PayloadBytes.
func (c *Conn) InboundBytes() uint64 {
	if c == nil {
		return 0
	}
	return c.inBytes.Load()
}

// PayloadBytes reports the smux payload this conn has opened from the peer
// since it was created: the data of PSH frames addressed to a stream, and
// nothing else. It only ever grows, and the control-stream liveness check
// reads it to tell a pong queued behind a bulk transfer from a dead link.
//
// Not InboundBytes, and the difference is olcbox#25. A server that closes a
// session for liveness builds the next one under the same key ring, so the
// new session seals with the same sender prefix and a counter that simply
// carries on: its frames authenticate, pass the replay window, and count as
// inbound bytes on a conn whose session they have nothing to do with. The
// SYN, NOP and UPD frames of such a session kept a client's "peer still
// sending" excuse alive for three and a half minutes on a session the server
// had already closed. A stream open, a keepalive or a window update is not
// the peer delivering anything; payload on a stream is, and a session that
// is not ours has no stream of ours to deliver payload on.
func (c *Conn) PayloadBytes() uint64 {
	if c == nil {
		return 0
	}
	return c.payloadBytes.Load()
}

// streamPayloadLen returns the payload a plaintext record carries for
// streams: the data of its PSH frames with a non-zero stream id. SYN, FIN,
// NOP and UPD frames, and anything that is not a smux frame at all, carry
// none. A record holds whole frames, several from a batching sender.
//
// ai-generated: the walk over several frames (olcrtc#11).
func streamPayloadLen(record []byte) uint64 {
	var total uint64
	for len(record) >= smuxHeaderSize {
		version, cmd := record[0], record[1]
		if version != 1 && version != 2 {
			return total
		}
		body := min(int(binary.LittleEndian.Uint16(record[2:4])), len(record)-smuxHeaderSize)
		if cmd == smuxCmdPSH && binary.LittleEndian.Uint32(record[4:8]) != 0 {
			total += uint64(body) //nolint:gosec // body is non-negative
		}
		record = record[smuxHeaderSize+body:]
	}
	return total
}

// openedStreams reports whether the SYN frames of a plaintext record open a
// session's first stream (smuxFirstStream), and whether they open any stream.
// A record holds whole frames, several from a batching sender.
//
// ai-generated: the whole function (olcrtc#49).
func openedStreams(record []byte) (bool, bool) {
	var first, opens bool
	for len(record) >= smuxHeaderSize {
		version, cmd := record[0], record[1]
		if version != 1 && version != 2 {
			return first, opens
		}
		body := min(int(binary.LittleEndian.Uint16(record[2:4])), len(record)-smuxHeaderSize)
		if id := binary.LittleEndian.Uint32(record[4:8]); cmd == smuxCmdSYN && id != 0 {
			first = first || id == smuxFirstStream
			opens = true
		}
		record = record[smuxHeaderSize+body:]
	}
	return first, opens
}

// SendStalled reports whether a Write has waited out the whole send deadline
// with the transport never once accepting data, and nothing has got through
// since.
//
// It exists because nothing else notices. smux is handed a net.Error whose
// Timeout reports true, which it treats as a deadline rather than a fault, so
// the session stays open and every later stream fails the same way. And the
// liveness check cannot see it either: data and control ride separate KCP
// sessions with separate send windows, so pongs keep arriving on time down a
// link that has not carried a byte of payload in half a minute. A phone in
// that state reports a healthy tunnel, loads nothing, and never reconnects,
// because the only thing that triggers a reconnect is the control stream
// dying.
//
// One stall is the whole answer: the deadline is writeReadyTimeout, and a
// transport that has refused every byte for that long is dead by the same
// measure smux's own keepalive uses.
func (c *Conn) SendStalled() bool {
	if c == nil {
		return false
	}
	return c.writeStalls.Load() > 0
}

// DecryptStats counts inbound records this conn could not open, by kind: a
// bad magic is a peer on a pre-v2 record layer, a failed authentication is a
// peer on another key. The client's handshake classifier reads them.
type DecryptStats struct {
	BadMagic   uint64
	AuthFailed uint64
}

// DecryptStats returns the failure counters so far.
func (c *Conn) DecryptStats() DecryptStats {
	return DecryptStats{BadMagic: c.badMagic.Load(), AuthFailed: c.authFailed.Load()}
}

// Read implements io.Reader. Blocks until at least one byte is available;
// after that, drains additional ready frames non-blockingly to fill p, so
// a single Read can absorb several queued frames in one go. This matches
// the prior cond/append-based implementation's concatenation behaviour
// and lets smux's bufio reader pull large chunks at a time.
func (c *Conn) Read(p []byte) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	if len(c.leftover) == 0 {
		bufPtr, ok := c.takeFrame()
		if !ok {
			return 0, errReadClosed
		}
		c.leftoverBuf = bufPtr
		c.leftover = *bufPtr
	}
	n := copy(p, c.leftover)
	c.leftover = c.leftover[n:]
	c.recycleIfDrained()

	// Greedily pull additional frames already sitting in the queue,
	// without blocking. This keeps the channel from accumulating a
	// backlog when the consumer asks for a large buffer.
	for n < len(p) && len(c.leftover) == 0 {
		select {
		case bufPtr, ok := <-c.in:
			if !ok {
				return n, nil
			}
			data := *bufPtr
			m := copy(p[n:], data)
			n += m
			if m < len(data) {
				c.leftoverBuf = bufPtr
				c.leftover = data[m:]
			} else {
				releaseFrameBuf(bufPtr)
			}
		default:
			return n, nil
		}
	}
	return n, nil
}

// takeFrame blocks until a frame is available or the conn is closed.
// On close it still picks up a frame that raced past Close's drain, so a
// peer that shuts us down right after a final write doesn't lose data.
func (c *Conn) takeFrame() (*[]byte, bool) {
	select {
	case bufPtr, ok := <-c.in:
		if !ok {
			return nil, false
		}
		return bufPtr, true
	case <-c.closeCh:
		select {
		case bufPtr, ok := <-c.in:
			if !ok {
				return nil, false
			}
			return bufPtr, true
		default:
			return nil, false
		}
	}
}

func (c *Conn) recycleIfDrained() {
	if len(c.leftover) == 0 && c.leftoverBuf != nil {
		releaseFrameBuf(c.leftoverBuf)
		c.leftoverBuf = nil
	}
}

// Write encrypts p and ships it to the link as a single message. Blocks while
// the link signals back-pressure, up to writeReadyTimeout, and until the
// group knows which key the peer holds. On a link that asks for a write
// interval, p then joins the batch instead, which may hold it for up to an
// interval (see batcher); a failed send is reported by the Write after it.
func (c *Conn) Write(p []byte) (int, error) {
	if c.batch == nil {
		return c.writeRecord(p)
	}
	// ai-generated: the wait before a frame joins the batch (olcrtc#11). The
	// wait is what a Write promises, batched or not: the client's handshake
	// counts its reply window from the moment the first SYN's Write returns,
	// which must be when this side's path is open (olcrtc#10); a batched
	// Write that returned at once counted it from the join again. The flusher
	// waits once more before it sends, in writeRecord, for a link that went
	// away in between; on a link that can send, that wait is a single check.
	if err := c.awaitLink(); err != nil {
		return 0, err
	}
	return c.batch.add(p, c.closeCh)
}

// writeRecord seals p as one record and sends it.
func (c *Conn) writeRecord(p []byte) (int, error) {
	if err := c.awaitLink(); err != nil {
		return 0, err
	}
	enc, err := c.group.Pinned().Keys.SealInto(nil, p, c.aad)
	if err != nil {
		return 0, fmt.Errorf("encrypt: %w", err)
	}
	if err := c.send(enc); err != nil {
		return 0, fmt.Errorf("send: %w", err)
	}
	return len(p), nil
}

// awaitLink blocks until a record may go: the link accepts data and the
// group knows which key the peer holds. A wait on the link that runs out
// marks the conn stalled (see SendStalled), and one that does not clears it.
//
// ai-generated: moved out of writeRecord as it was, for the batched Write
// (olcrtc#11).
func (c *Conn) awaitLink() error {
	if err := c.waitSendReady(); err != nil {
		if errors.Is(err, ErrWriteTimeout) {
			c.writeStalls.Add(1)
		}
		return err
	}
	c.writeStalls.Store(0)
	// Sealing under a guessed key would hand the peer a frame it silently
	// drops and desynchronise the smux stream, so wait for the pin. A
	// pre-pinned group makes this free; an unpinned one is released by the
	// peer's first record or by Close (smux closes the conn when its
	// keepalive times out, so a peer that never speaks cannot park us).
	select {
	case <-c.group.PinWait():
		return nil
	case <-c.closeCh:
		return ErrClosed
	}
}

// waitSendReady blocks until the link accepts data, the conn closes, or
// writeReadyTimeout elapses.
//
// A short spin comes first: on a healthy link CanSend usually clears well
// under a millisecond, and sleeping straight away adds visible per-frame
// latency to interactive request/response traffic. Only a genuinely
// congested link falls through to the polling loop.
func (c *Conn) waitSendReady() error {
	const (
		fastSpinAttempts = 16
		slowPollDelay    = 2 * time.Millisecond
	)
	canSend := c.canSend
	if canSend == nil {
		canSend = c.ln.CanSend
	}
	for range fastSpinAttempts {
		if c.closed.Load() {
			return ErrClosed
		}
		if canSend() {
			return nil
		}
		runtime.Gosched()
	}

	deadline := time.NewTimer(c.sendDeadline())
	defer deadline.Stop()
	ticker := time.NewTicker(slowPollDelay)
	defer ticker.Stop()
	for {
		if c.closed.Load() {
			return ErrClosed
		}
		if canSend() {
			return nil
		}
		select {
		case <-c.closeCh:
			return ErrClosed
		case <-deadline.C:
			return ErrWriteTimeout
		case <-ticker.C:
		}
	}
}

// Close unblocks any pending Read with errReadClosed and returns every
// queued inbound buffer to the pool. Frames still sitting in the queue are
// dropped: the caller asked to tear the conn down, and holding pooled
// buffers alive past that only costs the pool its warm working set.
func (c *Conn) Close() error { //nolint:unparam // io.Closer requires an error result
	c.closeOnce.Do(func() {
		c.closed.Store(true)
		close(c.closeCh)
		c.drainInbound()
	})
	return nil
}

func (c *Conn) drainInbound() {
	for {
		select {
		case bufPtr := <-c.in:
			releaseFrameBuf(bufPtr)
		default:
			return
		}
	}
}
