package client

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"

	"github.com/xtaci/smux"

	"github.com/openlibrecommunity/olcrtc/internal/control"
	"github.com/openlibrecommunity/olcrtc/internal/runtime"
)

// A client that stops says so. Before this, the run context's cancellation
// closed the control stream first and the close notice was written into a
// stream that was already gone, so the server kept the session, its streams
// and its KCP state until liveness gave up on a peer that had been gone for
// most of a minute.
func TestStoppingTellsTheServerRatherThanLeavingItToLiveness(t *testing.T) {
	a, b := net.Pipe()
	defer func() {
		_ = a.Close()
		_ = b.Close()
	}()

	serverSess, err := smux.Server(a, testSmuxCfg())
	if err != nil {
		t.Fatalf("smux.Server() error = %v", err)
	}
	defer func() { _ = serverSess.Close() }()
	clientSess, err := smux.Client(b, testSmuxCfg())
	if err != nil {
		t.Fatalf("smux.Client() error = %v", err)
	}
	defer func() { _ = clientSess.Close() }()

	peerStreamCh := make(chan *smux.Stream, 1)
	go func() {
		stream, acceptErr := serverSess.AcceptStream()
		if acceptErr == nil {
			peerStreamCh <- stream
		}
	}()
	stream, err := clientSess.OpenStream()
	if err != nil {
		t.Fatalf("OpenStream() error = %v", err)
	}
	peerStream := <-peerStreamCh

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	c := &Client{sessionID: "sid-closing", health: runtime.NewHealthTracker(nil)}
	c.health.RecordSession("sid-closing")
	c.startControlLoop(ctx, Config{
		Liveness: control.Config{Interval: time.Hour, Timeout: time.Hour, Failures: 4},
	}, cancel, stream)

	// The server's side of the same stream, with a liveness window far longer
	// than this test: only a notice can end it in time.
	peerErr := make(chan error, 1)
	go func() {
		peerErr <- control.Run(context.Background(), peerStream, control.Config{
			Interval: time.Hour, Timeout: time.Hour, Failures: 4,
		})
	}()

	cancel()
	select {
	case err := <-peerErr:
		if !errors.Is(err, control.ErrClosedByPeer) {
			t.Fatalf("server saw %v, want ErrClosedByPeer", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("the server was left to find out through liveness")
	}
}

// The notice goes out once, whoever gets there first: shutdown() after a stop
// that already sent it must not write a second close into the stream.
func TestTheClosingNoticeIsSentOnce(t *testing.T) {
	c := &Client{}
	sent := 0
	c.controlNotify = func() { sent++ }
	c.shutdown()
	if sent != 1 {
		t.Fatalf("notices sent = %d, want 1", sent)
	}
	if c.controlNotify != nil {
		t.Fatal("shutdown() left the notice behind for the next session")
	}
}
