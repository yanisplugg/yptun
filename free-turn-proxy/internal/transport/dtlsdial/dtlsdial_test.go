package dtlsdial

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"
)

type fakePC struct{}

func (fakePC) ReadFrom(_ []byte) (int, net.Addr, error) {
	select {}
}
func (fakePC) WriteTo(b []byte, _ net.Addr) (int, error) { return len(b), nil }
func (fakePC) Close() error                              { return nil }
func (fakePC) LocalAddr() net.Addr                       { return &net.UDPAddr{IP: net.IPv4zero} }
func (fakePC) SetDeadline(time.Time) error               { return nil }
func (fakePC) SetReadDeadline(time.Time) error           { return nil }
func (fakePC) SetWriteDeadline(time.Time) error          { return nil }

func TestDial_SemBlocksUntilCtx(t *testing.T) {
	sem := make(chan struct{}, 1)
	sem <- struct{}{}
	d := &Dialer{HandshakeSem: sem, HandshakeTimeout: 5 * time.Second}

	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	peer := &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: 1}
	_, err := d.Dial(ctx, fakePC{}, peer)
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("expected DeadlineExceeded, got %v", err)
	}
}
