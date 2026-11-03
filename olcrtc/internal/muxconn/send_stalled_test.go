package muxconn

import (
	"errors"
	"sync/atomic"
	"testing"
	"time"
)

// A Write that waits out the whole deadline must leave a mark, and the first
// Write that gets through must clear it.
//
// Nothing else records this. smux is handed a net.Error whose Timeout reports
// true and keeps the session open, so without the mark the only evidence that
// a link has stopped carrying anything is a log line per failed stream.
func TestSendStalledIsSetByATimeoutAndClearedByASend(t *testing.T) {
	clientKeys, _ := newTestKeyPair(t)
	var ready atomic.Bool
	link := &stubLink{canSendFn: ready.Load}
	conn := New(link, clientKeys)
	conn.writeTimeout = 20 * time.Millisecond

	if conn.SendStalled() {
		t.Fatal("a fresh conn must not read as stalled")
	}

	if _, err := conn.Write([]byte("payload")); !errors.Is(err, ErrWriteTimeout) {
		t.Fatalf("Write() error = %v, want %v", err, ErrWriteTimeout)
	}
	if !conn.SendStalled() {
		t.Fatal("a Write that waited out the deadline must leave the conn stalled")
	}

	ready.Store(true)
	if _, err := conn.Write([]byte("payload")); err != nil {
		t.Fatalf("Write() after the transport recovered: %v", err)
	}
	if conn.SendStalled() {
		t.Fatal("a Write that got through must clear the stall")
	}
}

// The liveness check reads this through a conn that may already be nil.
func TestSendStalledOnNilConn(t *testing.T) {
	var conn *Conn
	if conn.SendStalled() {
		t.Fatal("a nil conn must not report a stall")
	}
}
