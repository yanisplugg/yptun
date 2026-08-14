package netconn

import (
	"bytes"
	"errors"
	"net"
	"os"
	"testing"
	"time"
)

func TestPacketPipeRoundTrip(t *testing.T) {
	a, b := PacketPipe(0, 0)
	defer func() { _ = a.Close() }()
	defer func() { _ = b.Close() }()

	want := []byte("hello turn")
	if _, err := a.WriteTo(want, nil); err != nil {
		t.Fatalf("WriteTo() error = %v", err)
	}

	buf := make([]byte, 64)
	n, addr, err := b.ReadFrom(buf)
	if err != nil {
		t.Fatalf("ReadFrom() error = %v", err)
	}
	if !bytes.Equal(buf[:n], want) {
		t.Errorf("read %q, want %q", buf[:n], want)
	}
	if addr == nil || addr.String() != a.LocalAddr().String() {
		t.Errorf("addr = %v, want %v", addr, a.LocalAddr())
	}
}

// Границы датаграмм не склеиваются: два записанных пакета читаются двумя.
func TestPacketPipeKeepsDatagramBoundaries(t *testing.T) {
	a, b := PacketPipe(0, 0)
	defer func() { _ = a.Close() }()
	defer func() { _ = b.Close() }()

	for _, msg := range [][]byte{[]byte("one"), []byte("two")} {
		if _, err := a.WriteTo(msg, nil); err != nil {
			t.Fatal(err)
		}
	}

	buf := make([]byte, 64)
	for _, want := range []string{"one", "two"} {
		n, _, err := b.ReadFrom(buf)
		if err != nil {
			t.Fatal(err)
		}
		if got := string(buf[:n]); got != want {
			t.Fatalf("read %q, want %q", got, want)
		}
	}
}

// Как UDP: не влезшее в буфер вызывающего отбрасывается, а не переносится.
func TestPacketPipeTruncatesToCallerBuffer(t *testing.T) {
	a, b := PacketPipe(0, 0)
	defer func() { _ = a.Close() }()
	defer func() { _ = b.Close() }()

	if _, err := a.WriteTo([]byte("0123456789"), nil); err != nil {
		t.Fatal(err)
	}
	if _, err := a.WriteTo([]byte("next"), nil); err != nil {
		t.Fatal(err)
	}

	small := make([]byte, 4)
	n, _, err := b.ReadFrom(small)
	if err != nil || n != 4 || string(small) != "0123" {
		t.Fatalf("ReadFrom() = %d, %q, %v", n, small[:n], err)
	}

	buf := make([]byte, 64)
	n, _, err = b.ReadFrom(buf)
	if err != nil || string(buf[:n]) != "next" {
		t.Fatalf("second ReadFrom() = %q, %v", buf[:n], err)
	}
}

func TestPacketPipeRejectsOversized(t *testing.T) {
	a, b := PacketPipe(16, 0)
	defer func() { _ = a.Close() }()
	defer func() { _ = b.Close() }()

	if _, err := a.WriteTo(make([]byte, 17), nil); !errors.Is(err, ErrPacketTooLarge) {
		t.Fatalf("WriteTo() error = %v, want ErrPacketTooLarge", err)
	}
}

// Полная очередь роняет пакет, но не блокирует писателя - иначе медленный
// читатель остановил бы весь релей.
func TestPacketPipeDropsWhenQueueFull(t *testing.T) {
	a, b := PacketPipe(64, 2)
	defer func() { _ = a.Close() }()
	defer func() { _ = b.Close() }()

	done := make(chan struct{})
	go func() {
		defer close(done)
		for range 10 {
			if _, err := a.WriteTo([]byte("x"), nil); err != nil {
				t.Errorf("WriteTo() error = %v", err)
				return
			}
		}
	}()

	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("WriteTo blocked on full queue")
	}

	buf := make([]byte, 8)
	for range 2 {
		if _, _, err := b.ReadFrom(buf); err != nil {
			t.Fatalf("ReadFrom() error = %v", err)
		}
	}
	// Очередь опустела - остальные восемь пакетов потеряны, чтения не будет.
	if err := b.SetReadDeadline(time.Now().Add(50 * time.Millisecond)); err != nil {
		t.Fatal(err)
	}
	if _, _, err := b.ReadFrom(buf); !errors.Is(err, os.ErrDeadlineExceeded) {
		t.Fatalf("ReadFrom() error = %v, want deadline", err)
	}
}

func TestPacketPipeReadDeadline(t *testing.T) {
	a, b := PacketPipe(0, 0)
	defer func() { _ = a.Close() }()
	defer func() { _ = b.Close() }()

	if err := b.SetDeadline(time.Now().Add(20 * time.Millisecond)); err != nil {
		t.Fatal(err)
	}
	start := time.Now()
	if _, _, err := b.ReadFrom(make([]byte, 8)); !errors.Is(err, os.ErrDeadlineExceeded) {
		t.Fatalf("ReadFrom() error = %v, want deadline", err)
	}
	if time.Since(start) > time.Second {
		t.Error("ReadFrom() ignored deadline")
	}
}

func TestPacketPipeClose(t *testing.T) {
	a, b := PacketPipe(0, 0)

	if err := b.Close(); err != nil {
		t.Fatalf("Close() error = %v", err)
	}
	if _, _, err := b.ReadFrom(make([]byte, 8)); !errors.Is(err, net.ErrClosed) {
		t.Errorf("ReadFrom() after Close error = %v, want net.ErrClosed", err)
	}
	if _, err := a.WriteTo([]byte("x"), nil); !errors.Is(err, net.ErrClosed) {
		t.Errorf("WriteTo() to closed peer error = %v, want net.ErrClosed", err)
	}
	// Повторный Close безопасен: сессия закрывает канал и по отмене ctx, и в defer.
	if err := b.Close(); err != nil {
		t.Errorf("second Close() error = %v", err)
	}
	_ = a.Close()
}

// Пара обязана удовлетворять net.PacketConn - udprelay.Run принимает именно его.
var _ net.PacketConn = func() net.PacketConn { a, _ := PacketPipe(0, 0); return a }()
