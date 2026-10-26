package vp8channel

import (
	"encoding/binary"
	"hash/crc32"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/logger"
)

// wireCRCLen is the size of the CRC32 trailer appended to every KCP packet
// on the wire. KCP is handed to kcp-go with block=nil (no FEC, no checksum),
// so the vp8channel provider - a video stream an SFU may transcode or reorder -
// has no integrity protection at all. A real UDP datagram carries a checksum
// and is dropped on mismatch; without an equivalent, a single flipped byte
// rides through KCP as valid in-order data and corrupts the encrypted muxconn
// stream above it, tripping "chacha20poly1305: message authentication failed"
// (issue #109). The CRC restores UDP-equivalent semantics: a corrupted packet
// is dropped so KCP retransmits it.
const wireCRCLen = 4

// warnInterval rate-limits the drop/truncation warnings.
const warnInterval = 5 * time.Second

// crcTable uses the Castagnoli polynomial for hardware-accelerated checksums
// (SSE4.2 on amd64) on this throughput hot path.
var crcTable = crc32.MakeTable(crc32.Castagnoli) //nolint:gochecknoglobals // shared read-only CRC table

func fakeUDPAddr() *net.UDPAddr {
	return &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 1}
}

// kcpConn is a net.PacketConn implementation that bridges kcp-go on top of
// the vp8channel byte-message provider.
//
//	kcp.UDPSession  ──Write──▶  WriteTo  ──▶ outbound chan  ──▶ VP8 wire
//	kcp.UDPSession  ◀──Read──   ReadFrom  ◀── inbound (deliver) ◀── VP8 wire
//
// All packet boundaries are preserved by the underlying transport, which is
// exactly what KCP expects from a UDP-like conn.
type kcpConn struct {
	out chan<- *packetBuffer
	// acks, when set, takes the packets that only acknowledge the peer, so
	// the lane writes them ahead of its queued data: an acknowledgement
	// behind seconds of pushes tells the peer the path is dark.
	// ai-generated: issue #12.
	acks      chan<- *packetBuffer
	in        chan *packetBuffer
	inPools   [4]sync.Pool
	outPools  [4]sync.Pool
	addr      *net.UDPAddr
	closed    chan struct{}
	closeOnce sync.Once

	// epochHdr is prepended to every outgoing KCP packet so that the peer
	// can detect a session restart on our side (see transport.go for the
	// layout). The dst portion can be re-pointed via setHeader once the
	// remote peer's epoch is learned, so downlink/uplink can be addressed
	// to a specific participant instead of broadcast; a server's per-peer
	// session has its src re-pointed too when the server's epoch rotates
	// (readdressPeers). Guarded by hdrMu.
	hdrMu    sync.RWMutex
	epochHdr [epochHdrLen]byte

	// dropped counts packets discarded because the inbound queue was full,
	// corrupt counts packets rejected by the CRC check, and truncated counts
	// reads whose destination buffer was too small for the packet. All three
	// are otherwise-invisible failure modes, so they are reported through
	// warnRateLimitedf.
	dropped      atomic.Uint64
	corrupt      atomic.Uint64
	truncated    atomic.Uint64
	lastWarnNano atomic.Int64

	// lastAck is when the peer last answered a push or a window probe, on
	// monoNow's clock. The data lane reads it to tell a dark path from a
	// slow one. holdEvery is non-zero while the lane holds pushes back:
	// WriteTo then drops the pushes KCP resends but one per holdEvery, the
	// next due at probeAt, and lets through whatever answers the peer.
	// ai-generated: issue #12.
	lastAck   atomic.Int64
	holdEvery atomic.Int64
	probeAt   atomic.Int64
	// probeMu guards probeBuf, the newest push held back, which probe()
	// sends again when the next one is due. probeOut is probe()'s copy of
	// it, touched by the lane's writer alone.
	// ai-generated: issue #12.
	probeMu  sync.Mutex
	probeBuf []byte
	probeOut []byte

	// delivery counts the packets with a push the data lane writes and the
	// acknowledgements that come back for them, for the lane's frame cap.
	// ai-generated: issue #12.
	delivery deliveryTrack

	mu        sync.Mutex
	rDeadline time.Time
	wDeadline time.Time
}

type packetBuffer struct {
	data []byte
	pool *sync.Pool
	// queued is when WriteTo queued the packet, on monoNow's clock; zero on
	// the inbound side. ai-generated: issue #12, a capped lane drops pushes
	// that waited too long.
	queued int64
}

func packetBufferClass(size int) (int, int, bool) {
	switch {
	case size <= 256:
		return 0, 256, true
	case size <= 512:
		return 1, 512, true
	case size <= 1024:
		return 2, 1024, true
	case size <= 1536:
		return 3, 1536, true
	default:
		return 0, size, false
	}
}

func acquirePacketBuffer(pools *[4]sync.Pool, size int) *packetBuffer {
	class, capacity, pooled := packetBufferClass(size)
	if !pooled {
		return &packetBuffer{data: make([]byte, size)}
	}
	pool := &pools[class]
	if value := pool.Get(); value != nil {
		packet, ok := value.(*packetBuffer)
		if ok {
			packet.data = packet.data[:size]
			packet.pool = pool
			return packet
		}
	}
	return &packetBuffer{data: make([]byte, size, capacity), pool: pool}
}

func (p *packetBuffer) release() {
	if p == nil || p.pool == nil {
		return
	}
	pool := p.pool
	p.pool = nil
	p.data = p.data[:0]
	pool.Put(p)
}

// setHeader re-points the outgoing frame header (used to update the dst epoch
// after the peer is latched). Safe for concurrent use with WriteTo.
func (c *kcpConn) setHeader(hdr [epochHdrLen]byte) {
	c.hdrMu.Lock()
	c.epochHdr = hdr
	c.hdrMu.Unlock()
}

func newKCPConn(out chan<- *packetBuffer, inboundCap int, epochHdr [epochHdrLen]byte) *kcpConn {
	if inboundCap <= 0 {
		inboundCap = 1024
	}
	conn := &kcpConn{
		out:      out,
		in:       make(chan *packetBuffer, inboundCap),
		addr:     fakeUDPAddr(),
		closed:   make(chan struct{}),
		epochHdr: epochHdr,
	}
	return conn
}

// deliver hands an incoming wire payload to the KCP read loop. The trailing
// CRC32 is verified and stripped first: a mismatch means the provider corrupted
// the packet, so we drop it (KCP retransmits via SACK) instead of feeding
// garbage into KCP and, ultimately, the muxconn AEAD (issue #109). Drops on
// overflow are intentional - KCP will detect the loss via SACK and retransmit -
// but they are counted and reported, because a queue that keeps overflowing is
// the difference between "KCP recovered a packet" and "the reader cannot keep
// up and throughput is quietly capped".
func (c *kcpConn) deliver(payload []byte) {
	if len(payload) < wireCRCLen {
		c.corrupt.Add(1)
		return
	}
	body := payload[:len(payload)-wireCRCLen]
	want := binary.BigEndian.Uint32(payload[len(payload)-wireCRCLen:])
	if crc32.Checksum(body, crcTable) != want {
		c.corrupt.Add(1)
		return
	}
	if answers(body) { // ai-generated: issue #12
		c.lastAck.Store(monoNow())
	}
	if st := stampsOf(body); st.acked { // ai-generated: issue #12
		c.delivery.count(st.ack, 0, 1, 0)
	}
	packet := acquirePacketBuffer(&c.inPools, len(body))
	copy(packet.data, body)
	select {
	case c.in <- packet:
	case <-c.closed:
		packet.release()
	default:
		packet.release()
		c.warnRateLimitedf("inbound queue full, dropped %d packet(s) (corrupt=%d)",
			c.dropped.Add(1), c.corrupt.Load())
	}
}

// warnRateLimitedf emits at most one warning per warnInterval so a sustained
// drop storm cannot itself become the problem.
func (c *kcpConn) warnRateLimitedf(format string, args ...any) {
	now := time.Now().UnixNano()
	last := c.lastWarnNano.Load()
	if now-last < int64(warnInterval) {
		return
	}
	if !c.lastWarnNano.CompareAndSwap(last, now) {
		return
	}

	logger.Warnf("vp8channel: "+format, args...)
}

func (c *kcpConn) ReadFrom(p []byte) (int, net.Addr, error) {
	c.mu.Lock()
	deadline := c.rDeadline
	c.mu.Unlock()

	var timerC <-chan time.Time
	if !deadline.IsZero() {
		d := time.Until(deadline)
		if d <= 0 {
			return 0, nil, TimeoutError{}
		}
		t := time.NewTimer(d)
		defer t.Stop()
		timerC = t.C
	}

	select {
	case packet := <-c.in:
		n := copy(p, packet.data)
		packetLen := len(packet.data)
		packet.release()
		if n < packetLen {
			// KCP always reads with a full-MTU buffer, so this means the
			// packet exceeded the MTU: the tail is lost and KCP will see a
			// malformed segment. Never silently.
			c.warnRateLimitedf("read buffer too small: %d of %d bytes, %d truncated read(s)",
				n, packetLen, c.truncated.Add(1))
		}
		return n, c.addr, nil
	case <-c.closed:
		return 0, nil, net.ErrClosed
	case <-timerC:
		return 0, nil, TimeoutError{}
	}
}

// hold has WriteTo drop pushes but one per every; zero lets them all through.
//
// ai-generated: issue #12.
func (c *kcpConn) hold(every time.Duration) {
	c.probeAt.Store(0)
	c.holdEvery.Store(int64(every))
}

// withheld reports whether WriteTo drops p: a push, while pushes are held
// back. What answers the peer always goes. The newest push held back is kept
// for probe() to send.
//
// ai-generated: issue #12.
func (c *kcpConn) withheld(p []byte) bool {
	if c.holdEvery.Load() == 0 || answers(p) || !pushes(p) {
		return false
	}
	c.keepProbe(p)
	return true
}

// keepProbe remembers a push that was dropped rather than sent, as the one
// probe() sends while the lane is dark.
//
// ai-generated: issue #12.
func (c *kcpConn) keepProbe(p []byte) {
	c.probeMu.Lock()
	c.probeBuf = append(c.probeBuf[:0], p...)
	c.probeMu.Unlock()
}

// probe sends the newest push held back, once per hold interval, so the peer
// has something to answer while the lane is dark. Leaving that to whatever
// KCP happens to resend does not work: kcp-go backs each segment's timeout
// off every time it goes unanswered, so the next resend can be seconds away
// and the lane never learns the path is back.
//
// ai-generated: the whole function (issue #12).
func (c *kcpConn) probe() {
	every := c.holdEvery.Load()
	if every == 0 {
		return
	}
	now := monoNow()
	at := c.probeAt.Load()
	if now < at || !c.probeAt.CompareAndSwap(at, now+every) {
		return
	}
	c.probeMu.Lock()
	c.probeOut = append(c.probeOut[:0], c.probeBuf...)
	c.probeMu.Unlock()
	if len(c.probeOut) == 0 {
		return
	}
	// The lane's own writer calls this, and it is what drains the queue, so
	// a probe waits for no room: with none there is plenty on its way out
	// already, and the next interval brings another probe.
	packet := c.wrap(c.probeOut)
	select {
	case c.out <- packet:
	default:
		packet.release()
	}
}

func (c *kcpConn) WriteTo(p []byte, _ net.Addr) (int, error) {
	if c.withheld(p) { // ai-generated: issue #12, as a full path would drop it
		return len(p), nil
	}
	return c.enqueue(p)
}

// wrap builds the wire packet for a KCP packet. Layout: [epoch header][KCP
// packet p][CRC32(p)]. The receiver strips the epoch header before
// deliver(), which then verifies and strips the CRC.
func (c *kcpConn) wrap(p []byte) *packetBuffer {
	packet := acquirePacketBuffer(&c.outPools, epochHdrLen+len(p)+wireCRCLen)
	packet.queued = monoNow()
	buf := packet.data
	c.hdrMu.RLock()
	copy(buf, c.epochHdr[:])
	c.hdrMu.RUnlock()
	copy(buf[epochHdrLen:], p)
	binary.BigEndian.PutUint32(buf[epochHdrLen+len(p):], crc32.Checksum(p, crcTable))
	return packet
}

// enqueue wraps a KCP packet for the wire and queues it for the lane.
func (c *kcpConn) enqueue(p []byte) (int, error) {
	packet := c.wrap(p)
	if c.acks != nil && answers(p) && !pushes(p) { // ai-generated: issue #12
		select {
		case c.acks <- packet:
			return len(p), nil
		default: // full: it waits with the data
		}
	}

	c.mu.Lock()
	deadline := c.wDeadline
	c.mu.Unlock()

	var timerC <-chan time.Time
	if !deadline.IsZero() {
		d := time.Until(deadline)
		if d <= 0 {
			return 0, TimeoutError{}
		}
		t := time.NewTimer(d)
		defer t.Stop()
		timerC = t.C
	}

	select {
	case c.out <- packet:
		return len(p), nil
	case <-c.closed:
		packet.release()
		return 0, net.ErrClosed
	case <-timerC:
		packet.release()
		return 0, TimeoutError{}
	}
}

func (c *kcpConn) Close() error {
	c.closeOnce.Do(func() { close(c.closed) })
	return nil
}

func (c *kcpConn) LocalAddr() net.Addr { return c.addr }

func (c *kcpConn) SetDeadline(t time.Time) error {
	_ = c.SetReadDeadline(t)
	_ = c.SetWriteDeadline(t)
	return nil
}

func (c *kcpConn) SetReadDeadline(t time.Time) error {
	c.mu.Lock()
	c.rDeadline = t
	c.mu.Unlock()
	return nil
}

func (c *kcpConn) SetWriteDeadline(t time.Time) error {
	c.mu.Lock()
	c.wDeadline = t
	c.mu.Unlock()
	return nil
}

// TimeoutError is a net.Error indicating a deadline exceeded.
type TimeoutError struct{}

// kcp-go and everything else on this path classify read/write failures with
// errors.As(err, &net.Error). That interface still lists the deprecated
// Temporary method, so dropping it would silently stop TimeoutError being
// recognised as a timeout at all.
var _ net.Error = TimeoutError{}

func (TimeoutError) Error() string { return "i/o timeout" }

// Timeout reports that this error is a timeout.
func (TimeoutError) Timeout() bool { return true }

// Temporary reports that this error is temporary.
func (TimeoutError) Temporary() bool { return true }
