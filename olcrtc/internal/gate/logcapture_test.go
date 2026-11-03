package gate

import (
	"log"
	"os"
	"sync"
	"testing"
)

// ai-generated: whole file, cover for the per-cell capture of the engine log.

func TestCaptureRoutesLinesToTheCurrentCell(t *testing.T) {
	c := StartCapture()
	defer c.Stop()
	first := c.Begin()
	log.Printf("control missed pong role=client missed=1")
	log.Printf("session abc opened (device=x)")
	second := c.Begin()
	log.Printf("control missed pong role=client missed=2")
	if first.Count("control missed pong") != 1 || second.Count("control missed pong") != 1 {
		t.Fatalf("first=%q second=%q", first.Text(), second.Text())
	}
	if first.Count("session abc opened") != 1 || second.Count("session abc opened") != 0 {
		t.Fatalf("lines leaked between cells: %q / %q", first.Text(), second.Text())
	}
}

func TestCaptureStopRestoresStderr(t *testing.T) {
	c := StartCapture()
	if log.Writer() == os.Stderr {
		c.Stop()
		t.Fatal("StartCapture left the process log on stderr")
	}
	c.Stop()
	if log.Writer() != os.Stderr {
		t.Fatalf("after Stop the process log goes to %T, not stderr", log.Writer())
	}
}

// The engine logs from many goroutines while the runner switches cells and
// reads counts: no line may be lost or counted twice, and -race must stay
// quiet.
func TestCaptureKeepsEveryLineAcrossASwitch(t *testing.T) {
	c := StartCapture()
	defer c.Stop()
	first := c.Begin()
	const writers, each = 4, 200
	start := make(chan struct{})
	var wg sync.WaitGroup
	for w := range writers {
		wg.Go(func() {
			<-start
			for i := range each {
				log.Printf("gate line w=%d i=%d", w, i)
			}
		})
	}
	close(start)
	_ = first.Count("gate line")
	second := c.Begin()
	_ = second.Text()
	wg.Wait()
	if got := first.Count("gate line") + second.Count("gate line"); got != writers*each {
		t.Fatalf("captured %d lines across the switch, want %d", got, writers*each)
	}
}
