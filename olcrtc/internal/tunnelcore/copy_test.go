package tunnelcore

import (
	"bytes"
	"context"
	"io"
	"sync"
	"testing"
	"time"
)

type memoryConn struct {
	reader *bytes.Reader
	writer bytes.Buffer
}

func (c *memoryConn) Read(buffer []byte) (int, error)  { return c.reader.Read(buffer) }
func (c *memoryConn) Write(buffer []byte) (int, error) { return c.writer.Write(buffer) }
func (c *memoryConn) Close() error                     { return nil }
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
func (c *blockingConn) Close() error {
	c.once.Do(func() { close(c.closed) })
	return nil
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
