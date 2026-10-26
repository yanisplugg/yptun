package tunnelcore

import (
	"bytes"
	"context"
	"errors"
	"io"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type memoryConn struct {
	reader *bytes.Reader
	writer bytes.Buffer
	closed atomic.Bool
}

func (c *memoryConn) Read(buffer []byte) (int, error)  { return c.reader.Read(buffer) }
func (c *memoryConn) Write(buffer []byte) (int, error) { return c.writer.Write(buffer) }
func (c *memoryConn) Close() error                     { c.closed.Store(true); return nil }
func (c *memoryConn) CloseWrite() error                { return nil }

func TestCopyBidirectionalCountsBothDirections(t *testing.T) {
	left := &memoryConn{reader: bytes.NewReader([]byte("left"))}
	right := &memoryConn{reader: bytes.NewReader([]byte("right"))}
	counts, err := CopyBidirectional(context.Background(), left, right)
	if err != nil {
		t.Fatalf("CopyBidirectional() error = %v", err)
	}
	if counts.LeftToRight != 4 || counts.RightToLeft != 5 {
		t.Fatalf("CopyBidirectional() counts = %+v", counts)
	}
	if left.writer.String() != "right" || right.writer.String() != "left" {
		t.Fatalf("copied data = left %q, right %q", left.writer.String(), right.writer.String())
	}
}

// blockingConn reads until it is closed, like a peer that never sends its
// EOF. It half-closes like the real halves do (net.TCPConn, smux.Stream), so a
// clean EOF on the other side leaves its read side open.
type blockingConn struct {
	closed chan struct{}
	once   sync.Once
}

func newBlockingConn() *blockingConn { return &blockingConn{closed: make(chan struct{})} }

func (c *blockingConn) Read([]byte) (int, error) {
	<-c.closed
	return 0, io.EOF
}

func (c *blockingConn) Write(buffer []byte) (int, error) { return len(buffer), nil }
func (c *blockingConn) CloseWrite() error                { return nil }
func (c *blockingConn) Close() error {
	c.once.Do(func() { close(c.closed) })
	return nil
}

func (c *blockingConn) isClosed() bool {
	select {
	case <-c.closed:
		return true
	default:
		return false
	}
}

func TestCopyBidirectionalCancellationUnblocksPumps(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	left, right := newBlockingConn(), newBlockingConn()
	done := make(chan error, 1)
	go func() {
		_, err := CopyBidirectional(ctx, left, right)
		done <- err
	}()
	cancel()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("CopyBidirectional() error = nil, want cancellation")
		}
	case <-time.After(time.Second):
		t.Fatal("CopyBidirectional() left copy goroutines blocked")
	}
}

func TestCopyBidirectionalClosesAHalfOpenConnAfterTheGrace(t *testing.T) {
	// The left side sends its bytes and its EOF; the right side never says
	// anything again. Without a grace this waits for the session to end.
	left := &memoryConn{reader: bytes.NewReader([]byte("request"))}
	right := newBlockingConn()
	const grace = 20 * time.Millisecond
	started := time.Now()
	counts, err := copyBidirectional(context.Background(), left, right, grace)
	if !errors.Is(err, ErrHalfOpenIdle) {
		t.Fatalf("copyBidirectional() error = %v, want ErrHalfOpenIdle", err)
	}
	if elapsed := time.Since(started); elapsed < grace || elapsed > 50*grace {
		t.Fatalf("copyBidirectional() returned after %v, want about the grace", elapsed)
	}
	if !right.isClosed() || !left.closed.Load() {
		t.Fatalf("both sides should be closed, right=%v left=%v", right.isClosed(), left.closed.Load())
	}
	if counts.LeftToRight != 7 {
		t.Fatalf("counts = %+v, want the request counted", counts)
	}
}

// trickleConn produces one byte every tick for a while, then goes silent until
// closed: a half-closed transfer still in progress.
type trickleConn struct {
	*blockingConn
	remaining atomic.Int32
	every     time.Duration
}

func (c *trickleConn) Read(buffer []byte) (int, error) {
	if c.remaining.Add(-1) < 0 {
		return c.blockingConn.Read(buffer)
	}
	select {
	case <-time.After(c.every):
		buffer[0] = 'x'
		return 1, nil
	case <-c.closed:
		return 0, io.EOF
	}
}

func TestCopyBidirectionalGraceRunsFromTheLastByte(t *testing.T) {
	// Ten bytes at 5 ms apart under a 20 ms grace: the grace must not cut the
	// transfer while it moves, only once it has gone quiet.
	left := &memoryConn{reader: bytes.NewReader(nil)}
	right := &trickleConn{blockingConn: newBlockingConn(), every: 5 * time.Millisecond}
	right.remaining.Store(10)
	counts, err := copyBidirectional(context.Background(), left, right, 20*time.Millisecond)
	if !errors.Is(err, ErrHalfOpenIdle) {
		t.Fatalf("copyBidirectional() error = %v, want ErrHalfOpenIdle once the trickle stopped", err)
	}
	if counts.RightToLeft != 10 {
		t.Fatalf("counts = %+v, want every trickled byte delivered before the close", counts)
	}
}
