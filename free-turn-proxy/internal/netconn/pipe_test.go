package netconn

import (
	"context"
	"io"
	"net"
	"testing"
)

func TestBiCopy(t *testing.T) {
	t.Parallel()

	a1, a2 := net.Pipe()
	b1, b2 := net.Pipe()
	defer func() { _ = a1.Close() }()
	defer func() { _ = b1.Close() }()

	done := make(chan [2]int64, 1)
	go func() {
		n1, n2 := BiCopy(context.Background(), a2, b2, nil)
		done <- [2]int64{n1, n2}
	}()

	go func() {
		_, _ = a1.Write([]byte("ping"))
		_ = a1.Close()
	}()

	got := make([]byte, 4)
	if _, err := io.ReadFull(b1, got); err != nil {
		t.Fatal(err)
	}
	if string(got) != "ping" {
		t.Fatalf("got %q, want %q", got, "ping")
	}
	_ = b1.Close()

	counts := <-done
	if counts[1] != 4 {
		t.Errorf("a2->b2 = %d, want 4", counts[1])
	}
}
