// Package bond is a transport-agnostic byte-stream bond: it splits ONE reliable
// stream across N "lanes" (any net.Conn — e.g. independent olcRTC room SOCKS
// connections) with per-frame sequence numbers, and reassembles them strictly
// IN ORDER on the far side. This lets a SINGLE flow (e.g. a Chain→VLESS
// connection) aggregate bandwidth across several rooms instead of being pinned
// to one (the Stage-2 "many→single→vless" goal).
//
// Because every DATA frame carries an explicit Seq and the receiver reorders by
// it, lanes may deliver out of order (different rooms have different latency)
// without corrupting the stream — unlike the WireGuard-over-VK path, which has
// no app-level sequence and instead relies on chunk affinity + the WG replay
// window (see the freeturn udprelay dispatcher).
//
// Lineage: the wire shape mirrors free-turn-proxy internal/wire/bondframe, but
// this is reimplemented net.Conn-native (no smux/tcpfwd coupling) so the same
// core drives both the client (split) and server (reassemble) ends and is unit
// testable over in-memory pipes.
package bond

import (
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"math"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

const (
	// Version/Magic identify the per-lane Hello so a multiplexed listener can
	// tell a bond lane from other protocols.
	Version uint8 = 1
	Magic         = "OLB1" // OLcrtc Bond v1

	frameData byte = 1
	frameFIN  byte = 2

	// MaxChunk bounds a single DATA frame payload (and the read buffer).
	MaxChunk = 16 * 1024

	// pendingCap bounds the reorder buffer: a peer emitting seq with permanent
	// gaps can't grow it past this many frames (then we bail rather than OOM).
	pendingCap = 1024

	// recvBuf is the per-bond fan-in channel depth from the lane readers.
	recvBuf = 1024

	helloLen = 17
	frameHdr = 13
)

// Hello is the per-lane handshake written right after a lane opens. The server
// groups lanes by ConnID and waits for LaneCount of them before reassembling.
type Hello struct {
	ConnID    uint64
	LaneIndex uint16
	LaneCount uint16
}

// Frame is one bonded DATA or FIN unit, ordered by Seq within a ConnID.
type Frame struct {
	Type byte
	Seq  uint64
	Data []byte
}

// Hooks lets callers observe bond events (all optional). Useful for logging and
// for tests to assert behaviour without parsing logs.
type Hooks struct {
	OnLaneDead func(index int, err error)
	OnOverflow func(have int)
	OnError    func(err error)
}

func (h Hooks) laneDead(i int, err error) {
	if h.OnLaneDead != nil {
		h.OnLaneDead(i, err)
	}
}

func (h Hooks) overflow(n int) {
	if h.OnOverflow != nil {
		h.OnOverflow(n)
	}
}

func (h Hooks) err(e error) {
	if h.OnError != nil {
		h.OnError(e)
	}
}

// WriteHello sends the lane handshake.
func WriteHello(w io.Writer, connID uint64, laneIndex, laneCount uint16) error {
	var hdr [helloLen]byte
	copy(hdr[0:4], Magic)
	hdr[4] = Version
	binary.BigEndian.PutUint64(hdr[5:13], connID)
	binary.BigEndian.PutUint16(hdr[13:15], laneIndex)
	binary.BigEndian.PutUint16(hdr[15:17], laneCount)
	_, err := w.Write(hdr[:])
	return err
}

// ReadHello reads and validates a lane handshake.
func ReadHello(r io.Reader) (Hello, error) {
	var hdr [helloLen]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return Hello{}, err
	}
	if string(hdr[0:4]) != Magic {
		return Hello{}, fmt.Errorf("bond: bad magic")
	}
	if hdr[4] != Version {
		return Hello{}, fmt.Errorf("bond: unsupported version %d", hdr[4])
	}
	return Hello{
		ConnID:    binary.BigEndian.Uint64(hdr[5:13]),
		LaneIndex: binary.BigEndian.Uint16(hdr[13:15]),
		LaneCount: binary.BigEndian.Uint16(hdr[15:17]),
	}, nil
}

func writeFrame(w io.Writer, typ byte, seq uint64, data []byte) error {
	if uint64(len(data)) > math.MaxUint32 {
		return fmt.Errorf("bond: frame too large: %d", len(data))
	}
	var hdr [frameHdr]byte
	hdr[0] = typ
	binary.BigEndian.PutUint64(hdr[1:9], seq)
	binary.BigEndian.PutUint32(hdr[9:13], uint32(len(data))) //nolint:gosec // bounded above
	if _, err := w.Write(hdr[:]); err != nil {
		return err
	}
	if len(data) == 0 {
		return nil
	}
	_, err := w.Write(data)
	return err
}

func readFrame(r io.Reader) (Frame, error) {
	var hdr [frameHdr]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return Frame{}, err
	}
	size := binary.BigEndian.Uint32(hdr[9:13])
	if size > MaxChunk {
		return Frame{}, fmt.Errorf("bond: frame payload too large: %d", size)
	}
	f := Frame{Type: hdr[0], Seq: binary.BigEndian.Uint64(hdr[1:9])}
	if size > 0 {
		f.Data = make([]byte, size)
		if _, err := io.ReadFull(r, f.Data); err != nil {
			return Frame{}, err
		}
	}
	return f, nil
}

// lane wraps a carrier net.Conn with a liveness flag so the splitter skips a
// lane once a write to it fails.
type lane struct {
	c    net.Conn
	dead atomic.Bool
}

// Stripe runs the CLIENT side: writes a Hello on every lane, then bonds local
// across the lanes (both directions) until either side closes or ctx is done.
// connID identifies this bonded stream to the server. Lanes are NOT closed here;
// the caller owns their lifetime.
func Stripe(ctx context.Context, local net.Conn, connID uint64, lanes []net.Conn, hooks Hooks) {
	if len(lanes) > math.MaxUint16 {
		lanes = lanes[:math.MaxUint16]
	}
	count := uint16(len(lanes)) //nolint:gosec // bounded above
	live := make([]net.Conn, 0, len(lanes))
	for i, c := range lanes {
		if err := WriteHello(c, connID, uint16(i), count); err != nil { //nolint:gosec // i < count
			hooks.laneDead(i, err)
			continue
		}
		live = append(live, c)
	}
	if len(live) == 0 {
		hooks.err(fmt.Errorf("bond: no usable lanes"))
		return
	}
	bondPair(ctx, local, live, hooks)
}

// Reassemble runs the SERVER side over lanes already correlated for one connID
// (Hello consumed by the caller): bonds them into backend, both directions.
// Lanes/backend are not closed here.
func Reassemble(ctx context.Context, backend net.Conn, lanes []net.Conn, hooks Hooks) {
	if len(lanes) == 0 {
		hooks.err(fmt.Errorf("bond: reassemble with no lanes"))
		return
	}
	bondPair(ctx, backend, lanes, hooks)
}

// bondPair is the symmetric core used by BOTH ends: split [single] across
// [lanes] (round-robin, seq-tagged) AND reassemble the lanes back into [single]
// in Seq order. The only client/server asymmetry (who writes/reads Hello) is
// handled by the callers above.
func bondPair(ctx context.Context, single net.Conn, conns []net.Conn, hooks Hooks) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()

	lanes := make([]*lane, len(conns))
	for i, c := range conns {
		lanes[i] = &lane{c: c}
	}

	// Unblock blocking IO on cancel (Reorder/Read don't watch ctx mid-syscall).
	stop := context.AfterFunc(ctx, func() {
		now := time.Now()
		_ = single.SetDeadline(now)
		for _, l := range lanes {
			_ = l.c.SetDeadline(now)
		}
	})
	defer stop()

	// Fan-in: every lane's frames into one channel, reordered into single.
	recvCh := make(chan Frame, recvBuf)
	var readWG sync.WaitGroup
	for i, l := range lanes {
		readWG.Add(1)
		go func() {
			defer readWG.Done()
			for {
				f, err := readFrame(l.c)
				if err != nil {
					l.dead.Store(true)
					hooks.laneDead(i, err)
					return
				}
				select {
				case recvCh <- f:
				case <-ctx.Done():
					return
				}
			}
		}()
	}
	go func() { readWG.Wait(); close(recvCh) }()

	var wg sync.WaitGroup
	wg.Add(2)
	go func() { // single → lanes
		defer wg.Done()
		defer cancel()
		splitToLanes(ctx, single, lanes, hooks)
	}()
	go func() { // lanes → single
		defer wg.Done()
		defer cancel()
		reorder(ctx, single, recvCh, hooks)
	}()
	wg.Wait()
}

// splitToLanes reads single and round-robins seq-tagged DATA frames across live
// lanes; on EOF it writes a FIN (carrying the final seq) to every lane so the
// peer's reorder knows where the stream ends.
func splitToLanes(ctx context.Context, single net.Conn, lanes []*lane, hooks Hooks) {
	buf := make([]byte, MaxChunk)
	var seq, laneIdx uint64
	for {
		n, rerr := single.Read(buf)
		if n > 0 {
			if !writeNextLane(lanes, frameData, seq, buf[:n], &laneIdx, hooks) {
				return // all lanes dead
			}
			seq++
		}
		if rerr != nil {
			for _, l := range lanes {
				if !l.dead.Load() {
					_ = writeFrame(l.c, frameFIN, seq, nil)
				}
			}
			return
		}
		select {
		case <-ctx.Done():
			return
		default:
		}
	}
}

// writeNextLane writes one frame to the next live lane (round-robin), marking a
// lane dead on write error and trying the next. Returns false if none are live.
func writeNextLane(lanes []*lane, typ byte, seq uint64, data []byte, laneIdx *uint64, hooks Hooks) bool {
	for range lanes {
		idx := int(*laneIdx % uint64(len(lanes)))
		*laneIdx++
		l := lanes[idx]
		if l.dead.Load() {
			continue
		}
		if err := writeFrame(l.c, typ, seq, data); err != nil {
			l.dead.Store(true)
			hooks.laneDead(idx, err)
			continue
		}
		return true
	}
	return false
}

// reorder writes recv frames into dst in Seq order, closing dst's write side at
// the FIN seq. Mirrors freeturn bondframe.Reorder.
func reorder(ctx context.Context, dst net.Conn, recv <-chan Frame, hooks Hooks) {
	pending := make(map[uint64][]byte)
	var expect uint64
	var finSeq *uint64
	for {
		if finSeq != nil && expect == *finSeq {
			closeWrite(dst)
			return
		}
		select {
		case <-ctx.Done():
			return
		case f, ok := <-recv:
			if !ok {
				return
			}
			switch f.Type {
			case frameData:
				if len(pending) >= pendingCap {
					hooks.overflow(len(pending))
					return
				}
				pending[f.Seq] = f.Data
			case frameFIN:
				v := f.Seq
				if finSeq == nil || v < *finSeq {
					finSeq = &v
				}
			default:
				hooks.err(fmt.Errorf("bond: unknown frame type %d", f.Type))
				return
			}
			for {
				data, ok := pending[expect]
				if !ok {
					break
				}
				delete(pending, expect)
				if len(data) > 0 {
					if _, err := dst.Write(data); err != nil {
						hooks.err(err)
						return
					}
				}
				expect++
			}
		}
	}
}

// closeWrite half-closes the write side if the conn supports it (TCPConn, pipe,
// smux.Stream, …); otherwise no-op.
func closeWrite(conn net.Conn) {
	if cw, ok := conn.(interface{ CloseWrite() error }); ok {
		_ = cw.CloseWrite()
	}
}
