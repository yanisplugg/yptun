package control

import (
	"context"
	"io"
	"testing"
	"time"
)

// recordingRW records the order of what happens to a control stream: whatever
// BeforeClose does, and the Close that follows it.
type recordingRW struct {
	io.ReadWriteCloser
	events chan<- string
}

func (r *recordingRW) Close() error {
	select {
	case r.events <- "closed":
	default:
	}
	return r.ReadWriteCloser.Close()
}

func TestRunTellsThePeerBeforeItClosesTheStream(t *testing.T) {
	a, _ := controlPair(t)
	events := make(chan string, 4)
	ctx, cancel := context.WithCancel(context.Background())

	done := make(chan struct{})
	go func() {
		defer close(done)
		_ = Run(ctx, &recordingRW{ReadWriteCloser: a, events: events}, Config{
			Interval:    time.Hour,
			BeforeClose: func() { events <- "notice" },
		})
	}()

	cancel()
	for _, want := range []string{"notice", "closed"} {
		select {
		case got := <-events:
			if got != want {
				t.Fatalf("event = %q, want %q", got, want)
			}
		case <-time.After(2 * time.Second):
			t.Fatalf("timed out waiting for %q", want)
		}
	}
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("Run() did not return after cancellation")
	}
}

func TestANoticeThatNeverReturnsStillLetsTheStreamGo(t *testing.T) {
	a, _ := controlPair(t)
	closed := make(chan string, 2)
	stuck := make(chan struct{})
	t.Cleanup(func() { close(stuck) })
	ctx, cancel := context.WithCancel(context.Background())

	go func() {
		_ = Run(ctx, &recordingRW{ReadWriteCloser: a, events: closed}, Config{
			Interval:    time.Hour,
			BeforeClose: func() { <-stuck },
		})
	}()

	cancel()
	select {
	case got := <-closed:
		if got != "closed" {
			t.Fatalf("event = %q, want %q", got, "closed")
		}
	case <-time.After(closeNoticeBudget + 2*time.Second):
		t.Fatal("a notice that never returned held the stream open")
	}
}
