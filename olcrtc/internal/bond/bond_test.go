package bond

import (
	"bytes"
	"context"
	"crypto/rand"
	"io"
	"net"
	"testing"
	"time"
)

// tcpPair returns a connected pair of loopback TCP conns (both *net.TCPConn, so
// CloseWrite works — net.Pipe doesn't support it, which the FIN path needs).
func tcpPair(t *testing.T) (net.Conn, net.Conn) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer func() { _ = ln.Close() }()
	type res struct {
		c   net.Conn
		err error
	}
	ch := make(chan res, 1)
	go func() {
		c, err := ln.Accept()
		ch <- res{c, err}
	}()
	client, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	r := <-ch
	if r.err != nil {
		t.Fatalf("accept: %v", r.err)
	}
	t.Cleanup(func() { _ = client.Close(); _ = r.c.Close() })
	return client, r.c
}

// One logical stream split across N lanes must reassemble byte-exact — the core
// Stage-2 promise (single flow aggregates across rooms, reordered by Seq).
func TestBondRoundTripAggregates(t *testing.T) {
	const nLanes = 4
	appA, clientLocal := tcpPair(t)   // app  <-> client "single"
	serverBackend, appB := tcpPair(t) // server "single" <-> backend app

	clientLanes := make([]net.Conn, nLanes)
	serverLanes := make([]net.Conn, nLanes)
	for i := range clientLanes {
		clientLanes[i], serverLanes[i] = tcpPair(t)
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go Stripe(ctx, clientLocal, 42, clientLanes, Hooks{})

	// Server: consume the per-lane Hello (verify correlation), then reassemble.
	for _, sl := range serverLanes {
		h, err := ReadHello(sl)
		if err != nil {
			t.Fatalf("read hello: %v", err)
		}
		if h.ConnID != 42 || h.LaneCount != nLanes {
			t.Fatalf("hello = %+v, want ConnID 42 / LaneCount %d", h, nLanes)
		}
	}
	go Reassemble(ctx, serverBackend, serverLanes, Hooks{})

	// 1 MiB → many MaxChunk frames spread across the 4 lanes.
	payload := make([]byte, 1<<20)
	if _, err := rand.Read(payload); err != nil {
		t.Fatalf("rand: %v", err)
	}
	go func() {
		_, _ = appA.Write(payload)
		_ = appA.(*net.TCPConn).CloseWrite()
	}()

	_ = appB.SetReadDeadline(time.Now().Add(10 * time.Second))
	got, err := io.ReadAll(appB)
	if err != nil {
		t.Fatalf("read reassembled: %v", err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatalf("reassembled mismatch: got %d bytes, want %d", len(got), len(payload))
	}
}

// Both directions must work over the same lane set (split one way, reorder the other).
func TestBondBidirectional(t *testing.T) {
	const nLanes = 3
	appA, clientLocal := tcpPair(t)
	serverBackend, appB := tcpPair(t)
	clientLanes := make([]net.Conn, nLanes)
	serverLanes := make([]net.Conn, nLanes)
	for i := range clientLanes {
		clientLanes[i], serverLanes[i] = tcpPair(t)
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go Stripe(ctx, clientLocal, 7, clientLanes, Hooks{})
	for _, sl := range serverLanes {
		if _, err := ReadHello(sl); err != nil {
			t.Fatalf("hello: %v", err)
		}
	}
	go Reassemble(ctx, serverBackend, serverLanes, Hooks{})

	// app -> backend
	up := []byte("hello-from-app-going-up-and-over-the-bonded-lanes")
	if _, err := appA.Write(up); err != nil {
		t.Fatalf("write up: %v", err)
	}
	gotUp := make([]byte, len(up))
	_ = appB.SetReadDeadline(time.Now().Add(5 * time.Second))
	if _, err := io.ReadFull(appB, gotUp); err != nil {
		t.Fatalf("read up: %v", err)
	}
	if !bytes.Equal(gotUp, up) {
		t.Fatalf("up mismatch: %q != %q", gotUp, up)
	}

	// backend -> app
	down := []byte("and-a-reply-coming-back-down-through-the-bond")
	if _, err := appB.Write(down); err != nil {
		t.Fatalf("write down: %v", err)
	}
	gotDown := make([]byte, len(down))
	_ = appA.SetReadDeadline(time.Now().Add(5 * time.Second))
	if _, err := io.ReadFull(appA, gotDown); err != nil {
		t.Fatalf("read down: %v", err)
	}
	if !bytes.Equal(gotDown, down) {
		t.Fatalf("down mismatch: %q != %q", gotDown, down)
	}
}

// reorder must deliver out-of-order frames to dst strictly by Seq.
func TestReorderDeliversInOrder(t *testing.T) {
	dst, reader := tcpPair(t)
	recv := make(chan Frame, 8)
	// Arrive out of order; FIN marks the end at seq 3.
	recv <- Frame{Type: frameData, Seq: 2, Data: []byte("ccc")}
	recv <- Frame{Type: frameData, Seq: 0, Data: []byte("aaa")}
	recv <- Frame{Type: frameData, Seq: 1, Data: []byte("bbb")}
	recv <- Frame{Type: frameFIN, Seq: 3}

	go func() {
		reorder(context.Background(), dst, recv, Hooks{})
		_ = dst.Close()
	}()

	_ = reader.SetReadDeadline(time.Now().Add(5 * time.Second))
	got, err := io.ReadAll(reader)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if string(got) != "aaabbbccc" {
		t.Fatalf("reorder = %q, want %q", got, "aaabbbccc")
	}
}

func TestHelloRoundTrip(t *testing.T) {
	var buf bytes.Buffer
	if err := WriteHello(&buf, 0xDEADBEEF, 3, 5); err != nil {
		t.Fatalf("write: %v", err)
	}
	h, err := ReadHello(&buf)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if h.ConnID != 0xDEADBEEF || h.LaneIndex != 3 || h.LaneCount != 5 {
		t.Fatalf("hello = %+v", h)
	}
}

func TestReadHelloRejectsBadMagic(t *testing.T) {
	bad := make([]byte, helloLen)
	copy(bad, "XXXX")
	if _, err := ReadHello(bytes.NewReader(bad)); err == nil {
		t.Fatal("expected error on bad magic")
	}
}
