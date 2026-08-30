package wake

import (
	"testing"
	"time"
)

func TestFireWakesAllListeners(t *testing.T) {
	n := New()
	a, b := n.Chan(), n.Chan()
	n.Fire()

	for i, ch := range []<-chan struct{}{a, b} {
		select {
		case <-ch:
		case <-time.After(time.Second):
			t.Fatalf("listener %d did not wake", i)
		}
	}
}

func TestChanIsRenewedAfterFire(t *testing.T) {
	n := New()
	n.Fire()

	next := n.Chan()
	select {
	case <-next:
		t.Fatal("fresh channel is already closed")
	default:
	}

	n.Fire()
	select {
	case <-next:
	case <-time.After(time.Second):
		t.Fatal("second Fire did not reach the fresh channel")
	}
}

func TestNilNotifierBlocksForever(t *testing.T) {
	var n *Notifier
	n.Fire()

	select {
	case <-n.Chan():
		t.Fatal("nil notifier produced a ready channel")
	case <-time.After(10 * time.Millisecond):
	}
}
