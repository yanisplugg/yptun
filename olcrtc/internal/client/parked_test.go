package client

import (
	"context"
	"net"
	"sync"
	"testing"
	"time"
)

// readSocksReply takes one SOCKS reply off the peer end of a pipe. net.Pipe
// is synchronous, so the writer only returns once this has run.
func readSocksReply(t *testing.T, peer net.Conn, within time.Duration) []byte {
	t.Helper()
	_ = peer.SetReadDeadline(time.Now().Add(within))
	buf := make([]byte, 64)
	n, err := peer.Read(buf)
	if err != nil {
		t.Fatalf("reading the SOCKS reply: %v", err)
	}
	return buf[:n]
}

func waitParked(t *testing.T, c *Client, want int32) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for c.parked.Load() != want {
		if time.Now().After(deadline) {
			t.Fatalf("parked = %d, want %d", c.parked.Load(), want)
		}
		time.Sleep(time.Millisecond)
	}
}

func parkConnects(ctx context.Context, c *Client, n int, wg *sync.WaitGroup) []net.Conn {
	peers := make([]net.Conn, 0, n)
	for range n {
		ours, theirs := net.Pipe()
		peers = append(peers, theirs)
		wg.Add(1)
		go func() {
			defer wg.Done()
			c.tunnelWhenReady(ctx, ours, connectJob{host: "example.com", port: 443})
		}()
	}
	return peers
}

// A CONNECT that arrives while the session is down waits for it, but only
// maxParkedRequests of them at once: every parked request is also a
// tun2socks session outside the Go heap, and a phone whose apps retry through
// a network gap parks hundreds in seconds (olcbox#37). Past the cap the reply
// is immediate, and the parked ones give their slots back when they time out.
func TestTunnelWhenReadyRefusesPastParkedCap(t *testing.T) {
	c := &Client{sessionReady: make(chan struct{}), sessionReadyTimeout: 200 * time.Millisecond}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	var wg sync.WaitGroup
	peers := parkConnects(ctx, c, maxParkedRequests, &wg)
	waitParked(t, c, maxParkedRequests)

	ours, theirs := net.Pipe()
	done := make(chan struct{})
	go func() {
		c.tunnelWhenReady(ctx, ours, connectJob{host: "example.com", port: 443})
		close(done)
	}()
	reply := readSocksReply(t, theirs, 100*time.Millisecond)
	if len(reply) < 2 || reply[1] != socksRepNetworkUnreachable {
		t.Fatalf("reply past the cap = %v, want network unreachable at once", reply)
	}
	select {
	case <-done:
	case <-time.After(100 * time.Millisecond):
		t.Fatal("the refused CONNECT did not return")
	}

	for _, peer := range peers {
		reply := readSocksReply(t, peer, time.Second)
		if len(reply) < 2 || reply[1] != socksRepHostUnreachable {
			t.Fatalf("parked reply = %v, want host unreachable after the wait", reply)
		}
	}
	wg.Wait()
	if got := c.parked.Load(); got != 0 {
		t.Fatalf("parked = %d after every request returned", got)
	}
}

// Cancelling the run releases a parked slot too.
func TestParkedSlotReleasedOnCancel(t *testing.T) {
	c := &Client{sessionReady: make(chan struct{})}
	ctx, cancel := context.WithCancel(context.Background())

	var wg sync.WaitGroup
	peers := parkConnects(ctx, c, 1, &wg)
	waitParked(t, c, 1)
	cancel()
	_ = readSocksReply(t, peers[0], time.Second)
	wg.Wait()
	if got := c.parked.Load(); got != 0 {
		t.Fatalf("parked = %d after cancel", got)
	}
}

// UDP ASSOCIATE shares the pool: at the cap it stops waiting at once.
func TestWaitSessionReadyRefusesPastParkedCap(t *testing.T) {
	c := &Client{sessionReady: make(chan struct{}), sessionReadyTimeout: 200 * time.Millisecond}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	var wg sync.WaitGroup
	peers := parkConnects(ctx, c, maxParkedRequests, &wg)
	waitParked(t, c, maxParkedRequests)

	started := time.Now()
	if c.waitSessionReady(ctx) {
		t.Fatal("waitSessionReady reported a session that does not exist")
	}
	if elapsed := time.Since(started); elapsed > 100*time.Millisecond {
		t.Fatalf("waitSessionReady waited %s at the cap, want an immediate refusal", elapsed)
	}

	for _, peer := range peers {
		_ = readSocksReply(t, peer, time.Second)
	}
	wg.Wait()
}

// Below the cap an association waits like a CONNECT does and gives the slot
// back when the wait ends.
func TestWaitSessionReadyParksBelowCap(t *testing.T) {
	c := &Client{sessionReady: make(chan struct{}), sessionReadyTimeout: 50 * time.Millisecond}
	if c.waitSessionReady(context.Background()) {
		t.Fatal("waitSessionReady reported a session that does not exist")
	}
	if got := c.parked.Load(); got != 0 {
		t.Fatalf("parked = %d after the wait timed out", got)
	}
}
