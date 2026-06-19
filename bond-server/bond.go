// bond.go is the SERVER half of the olcRTC Stage-2 stream bond. It is the byte-for-byte counterpart of
// the client (olcvpn-client OlcrtcBond.kt) and the Go reference olcrtc/internal/bond/bond.go: a single
// reliable byte stream is split across N lanes (independent olcRTC room SOCKS connections) with per-frame
// sequence numbers, and reassembled strictly IN ORDER here so a single Chain→VLESS flow aggregates
// bandwidth across rooms ("many→single→vless").
package main

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
	bondVersion uint8 = 1
	bondMagic         = "OLB1" // OLcrtc Bond v1

	frameData byte = 1
	frameFIN  byte = 2

	maxChunk   = 16 * 1024
	pendingCap = 1024
	recvBuf    = 1024
	helloLen   = 17
	frameHdr   = 13
)

// Hello is the per-lane handshake written right after a lane opens.
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

// ReadHello reads and validates a lane handshake from r.
func ReadHello(r io.Reader) (Hello, error) {
	var hdr [helloLen]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return Hello{}, err
	}
	if string(hdr[0:4]) != bondMagic {
		return Hello{}, fmt.Errorf("bond: bad magic")
	}
	if hdr[4] != bondVersion {
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
	binary.BigEndian.PutUint32(hdr[9:13], uint32(len(data)))
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
	if size > maxChunk {
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

type lane struct {
	c    net.Conn
	dead atomic.Bool
}

// Reassemble bonds lanes (Hellos already consumed) into backend, both directions. Lanes/backend are not
// closed here.
func Reassemble(ctx context.Context, backend net.Conn, lanes []net.Conn) {
	if len(lanes) == 0 {
		return
	}
	bondPair(ctx, backend, lanes)
}

// bondPair is the symmetric core: split [single] across [lanes] (round-robin, seq-tagged) AND reassemble
// the lanes back into [single] in Seq order.
func bondPair(ctx context.Context, single net.Conn, conns []net.Conn) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()

	lanes := make([]*lane, len(conns))
	for i, c := range conns {
		lanes[i] = &lane{c: c}
	}

	stop := context.AfterFunc(ctx, func() {
		now := time.Now()
		_ = single.SetDeadline(now)
		for _, l := range lanes {
			_ = l.c.SetDeadline(now)
		}
	})
	defer stop()

	recvCh := make(chan Frame, recvBuf)
	var readWG sync.WaitGroup
	for _, l := range lanes {
		l := l // capture per iteration (safe under any Go toolchain)
		readWG.Add(1)
		go func() {
			defer readWG.Done()
			for {
				f, err := readFrame(l.c)
				if err != nil {
					l.dead.Store(true)
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
	go func() {
		defer wg.Done()
		defer cancel()
		splitToLanes(ctx, single, lanes)
	}()
	go func() {
		defer wg.Done()
		defer cancel()
		reorder(ctx, single, recvCh)
	}()
	wg.Wait()
}

func splitToLanes(ctx context.Context, single net.Conn, lanes []*lane) {
	buf := make([]byte, maxChunk)
	var seq, laneIdx uint64
	for {
		n, rerr := single.Read(buf)
		if n > 0 {
			if !writeNextLane(lanes, frameData, seq, buf[:n], &laneIdx) {
				return
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

func writeNextLane(lanes []*lane, typ byte, seq uint64, data []byte, laneIdx *uint64) bool {
	for range lanes {
		idx := int(*laneIdx % uint64(len(lanes)))
		*laneIdx++
		l := lanes[idx]
		if l.dead.Load() {
			continue
		}
		if err := writeFrame(l.c, typ, seq, data); err != nil {
			l.dead.Store(true)
			continue
		}
		return true
	}
	return false
}

func reorder(ctx context.Context, dst net.Conn, recv <-chan Frame) {
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
					return
				}
				pending[f.Seq] = f.Data
			case frameFIN:
				v := f.Seq
				if finSeq == nil || v < *finSeq {
					finSeq = &v
				}
			default:
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
						return
					}
				}
				expect++
			}
		}
	}
}

func closeWrite(conn net.Conn) {
	if cw, ok := conn.(interface{ CloseWrite() error }); ok {
		_ = cw.CloseWrite()
	}
}
