package vp8channel

import (
	"bytes"
	"encoding/binary"
	"errors"
	"hash/crc32"
	"net"
	"sync"
	"testing"
	"time"
)

func TestKCPConnReadWriteDeadlinesAndClose(t *testing.T) {
	out := make(chan *packetBuffer, 1)
	hdr := testEpochHdr(9)
	conn := newKCPConn(out, 1, hdr)

	if err := conn.SetDeadline(time.Now().Add(time.Second)); err != nil {
		t.Fatalf("SetDeadline() error = %v", err)
	}
	if conn.LocalAddr().String() != "127.0.0.1:1" {
		t.Fatalf("LocalAddr() = %v", conn.LocalAddr())
	}

	n, err := conn.WriteTo([]byte("payload"), nil)
	if err != nil || n != len("payload") {
		t.Fatalf("WriteTo() = (%d, %v), want payload length", n, err)
	}
	wire := <-out
	// Wire layout is [epoch header][KCP packet][CRC32(packet)].
	body := wire.data[epochHdrLen : len(wire.data)-wireCRCLen]
	if !bytes.Equal(wire.data[:epochHdrLen], hdr[:]) || string(body) != "payload" {
		t.Fatalf("wire packet = %v", wire.data)
	}
	wire.release()

	// deliver expects the CRC trailer WriteTo appends; build it the same way.
	incoming := append([]byte("incoming"), make([]byte, wireCRCLen)...)
	binary.BigEndian.PutUint32(incoming[len("incoming"):], crc32.Checksum([]byte("incoming"), crcTable))
	conn.deliver(incoming)
	buf := make([]byte, 64)
	n, addr, err := conn.ReadFrom(buf)
	if err != nil || addr == nil || string(buf[:n]) != "incoming" {
		t.Fatalf("ReadFrom() = (%d, %v, %v), payload %q", n, addr, err, buf[:n])
	}

	// A corrupted packet (CRC mismatch) must be dropped, not delivered.
	corrupt := append([]byte("incoming"), make([]byte, wireCRCLen)...)
	binary.BigEndian.PutUint32(corrupt[len("incoming"):], 0xDEADBEEF)
	conn.deliver(corrupt)
	if len(conn.in) != 0 {
		t.Fatalf("corrupt packet was delivered, in-queue len = %d", len(conn.in))
	}

	if err := conn.Close(); err != nil {
		t.Fatalf("Close() error = %v", err)
	}
	if _, _, err := conn.ReadFrom(buf); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("ReadFrom() error = %v, want net.ErrClosed", err)
	}

	closedWrite := newKCPConn(make(chan *packetBuffer), 1, hdr)
	_ = closedWrite.Close()
	if _, err := closedWrite.WriteTo([]byte("x"), nil); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("WriteTo() error = %v, want net.ErrClosed", err)
	}
}

func TestKCPConnTimeouts(t *testing.T) {
	conn := newKCPConn(make(chan *packetBuffer), 1, testEpochHdr(1))
	if err := conn.SetReadDeadline(time.Now().Add(-time.Millisecond)); err != nil {
		t.Fatalf("SetReadDeadline() error = %v", err)
	}
	buf := make([]byte, 4)
	if _, _, err := conn.ReadFrom(buf); err == nil {
		t.Fatal("ReadFrom() unexpectedly succeeded")
	} else {
		var netErr net.Error
		if !errors.As(err, &netErr) || !netErr.Timeout() {
			t.Fatalf("ReadFrom() error = %T %v, want timeout net.Error", err, err)
		}
	}

	if err := conn.SetWriteDeadline(time.Now().Add(-time.Millisecond)); err != nil {
		t.Fatalf("SetWriteDeadline() error = %v", err)
	}
	if _, err := conn.WriteTo([]byte("x"), nil); err == nil {
		t.Fatal("WriteTo() unexpectedly succeeded")
	}
}

func TestKCPConnInboundPoolConcurrentIntegrity(t *testing.T) {
	const (
		writers   = 8
		perWriter = 100
		total     = writers * perWriter
	)
	conn := newKCPConn(make(chan *packetBuffer, 1), total, testEpochHdr(1))
	got := make([]bool, total)
	readDone := make(chan struct{})
	go func() {
		defer close(readDone)
		buf := make([]byte, 8)
		for range total {
			n, _, err := conn.ReadFrom(buf)
			if err != nil {
				t.Errorf("ReadFrom() error = %v", err)
				return
			}
			if n != len(buf) {
				t.Errorf("ReadFrom() length = %d", n)
				return
			}
			idx := int(binary.BigEndian.Uint32(buf[:4]))*perWriter + int(binary.BigEndian.Uint32(buf[4:]))
			if idx < 0 || idx >= total || got[idx] {
				t.Errorf("unexpected packet index %d", idx)
				return
			}
			got[idx] = true
		}
	}()

	var wg sync.WaitGroup
	wg.Add(writers)
	for writer := range writers {
		go func() {
			defer wg.Done()
			for seq := range perWriter {
				wire := make([]byte, 8+wireCRCLen)
				binary.BigEndian.PutUint32(wire[:4], uint32(writer)) //nolint:gosec // bounded fixture
				binary.BigEndian.PutUint32(wire[4:8], uint32(seq))
				binary.BigEndian.PutUint32(wire[8:], crc32.Checksum(wire[:8], crcTable))
				conn.deliver(wire)
			}
		}()
	}
	wg.Wait()
	<-readDone
	for idx, seen := range got {
		if !seen {
			t.Fatalf("packet %d was not delivered", idx)
		}
	}
}

func TestPacketBufferPoolClassesAndDropsOversized(t *testing.T) {
	var pools [4]sync.Pool
	first := acquirePacketBuffer(&pools, 1400)
	if cap(first.data) != 1536 || first.pool == nil {
		t.Fatalf("pooled packet = cap %d pool %p", cap(first.data), first.pool)
	}
	first.release()
	second := acquirePacketBuffer(&pools, 1400)
	if cap(second.data) != 1536 || second.pool == nil {
		t.Fatalf("second pooled packet = cap %d pool %p", cap(second.data), second.pool)
	}
	second.release()

	oversized := acquirePacketBuffer(&pools, 2048)
	if oversized.pool != nil {
		t.Fatal("oversized packet retained a pool owner")
	}
	oversized.release()
}
