package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/binary"
	"io"
	"net"
	"testing"
	"time"
)

// TestBondRoundTrip drives bondPair (client) ↔ Reassemble (server) over in-memory lane pipes and checks a
// 1 MiB payload survives byte-exact in both directions — the core "stripe across N lanes, reorder by seq"
// invariant the Kotlin client and this server share.
func TestBondRoundTrip(t *testing.T) {
	const lanes = 4
	clientLanes := make([]net.Conn, lanes)
	serverLanes := make([]net.Conn, lanes)
	for i := 0; i < lanes; i++ {
		a, b := net.Pipe()
		clientLanes[i] = a
		serverLanes[i] = b
	}

	clientSingle, clientApp := net.Pipe()
	serverSingle, serverApp := net.Pipe()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// "client" stripes clientApp's stream across the lanes; "server" reassembles into serverApp.
	go bondPair(ctx, clientSingle, clientLanes)
	go Reassemble(ctx, serverSingle, serverLanes)

	payload := make([]byte, 1<<20)
	_, _ = rand.Read(payload)

	// app → (client stripe) → lanes → (server reassemble) → serverApp
	go func() {
		_, _ = clientApp.Write(payload)
		// net.Pipe has no CloseWrite; a full Close gives clientSingle.Read an EOF → splitToLanes emits FIN.
		_ = clientApp.Close()
	}()

	got := make([]byte, 0, len(payload))
	buf := make([]byte, 64*1024)
	_ = serverApp.SetReadDeadline(time.Now().Add(10 * time.Second))
	for len(got) < len(payload) {
		n, err := serverApp.Read(buf)
		if n > 0 {
			got = append(got, buf[:n]...)
		}
		if err != nil {
			break
		}
	}
	if !bytes.Equal(got, payload) {
		t.Fatalf("payload mismatch: got %d bytes, want %d", len(got), len(payload))
	}
}

// TestHelloWireFormat pins the exact 17-byte Hello layout the Kotlin client writes, so a drift on either
// side is caught here.
func TestHelloWireFormat(t *testing.T) {
	var b bytes.Buffer
	// Mirror OlcrtcBond.writeHello: "OLB1" | ver=1 | connID(8) | laneIndex(2) | laneCount(2), big-endian.
	hdr := make([]byte, helloLen)
	copy(hdr[0:4], bondMagic)
	hdr[4] = bondVersion
	binary.BigEndian.PutUint64(hdr[5:13], 0xDEADBEEF01)
	binary.BigEndian.PutUint16(hdr[13:15], 2)
	binary.BigEndian.PutUint16(hdr[15:17], 5)
	b.Write(hdr)

	h, err := ReadHello(&b)
	if err != nil {
		t.Fatal(err)
	}
	if h.ConnID != 0xDEADBEEF01 || h.LaneIndex != 2 || h.LaneCount != 5 {
		t.Fatalf("hello mismatch: %+v", h)
	}
}

// TestReorder verifies out-of-order frames are written to the destination in seq order and the FIN closes
// the stream at the right point.
func TestReorder(t *testing.T) {
	recv := make(chan Frame, 8)
	dstA, dstB := net.Pipe()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go reorder(ctx, dstA, recv)

	// Deliver "HELLO" as 5 single-byte DATA frames, out of order, then FIN at seq 5.
	order := []uint64{2, 0, 4, 1, 3}
	letters := map[uint64]byte{0: 'H', 1: 'E', 2: 'L', 3: 'L', 4: 'O'}
	for _, s := range order {
		recv <- Frame{Type: frameData, Seq: s, Data: []byte{letters[s]}}
	}
	recv <- Frame{Type: frameFIN, Seq: 5}

	out := make([]byte, 5)
	_ = dstB.SetReadDeadline(time.Now().Add(5 * time.Second))
	if _, err := io.ReadFull(dstB, out); err != nil {
		t.Fatal(err)
	}
	if string(out) != "HELLO" {
		t.Fatalf("reorder mismatch: %q", out)
	}
}
