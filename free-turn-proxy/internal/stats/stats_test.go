package stats

import (
	"io"
	"net"
	"strings"
	"testing"
	"time"
)

func TestFormatBitsPerSecond(t *testing.T) {
	t.Parallel()

	cases := []struct {
		bytes    uint64
		interval time.Duration
		want     string
	}{
		{0, time.Second, "0 bit/s"},
		{125, time.Second, "1.0 kbit/s"},      // 1000 bit/s
		{125_000, time.Second, "1.00 Mbit/s"}, // 1_000_000 bit/s
		{50, time.Second, "400 bit/s"},
		{1_000_000, 5 * time.Second, "1.60 Mbit/s"},
	}
	for _, c := range cases {
		got := FormatBitsPerSecond(c.bytes, c.interval)
		if got != c.want {
			t.Errorf("FormatBitsPerSecond(%d, %s) = %q, want %q", c.bytes, c.interval, got, c.want)
		}
	}

	// interval <= 0 should not panic and should default to 1s.
	if got := FormatBitsPerSecond(125, 0); !strings.Contains(got, "kbit/s") {
		t.Errorf("zero interval fallback: got %q", got)
	}
}

func TestStatsDisabledNoOp(t *testing.T) {
	t.Parallel()

	s := New(false)
	s.AddTx(1234)
	s.AddRx(5678)
	if got := s.tx.Load(); got != 0 {
		t.Errorf("disabled AddTx leaked: tx=%d", got)
	}
	if got := s.rx.Load(); got != 0 {
		t.Errorf("disabled AddRx leaked: rx=%d", got)
	}
}

func TestStatsEnabledAccumulates(t *testing.T) {
	t.Parallel()

	s := New(true)
	s.AddTx(100)
	s.AddTx(50)
	s.AddRx(7)
	s.AddTx(-5)
	s.AddRx(0)
	if got := s.tx.Load(); got != 150 {
		t.Errorf("tx=%d, want 150", got)
	}
	if got := s.rx.Load(); got != 7 {
		t.Errorf("rx=%d, want 7", got)
	}
	tx, rx := s.Counters()
	if tx != 150 || rx != 7 {
		t.Fatalf("Counters() = (%d, %d), want (150, 7)", tx, rx)
	}
}

func TestFormatByteCount(t *testing.T) {
	t.Parallel()

	cases := []struct {
		bytes uint64
		want  string
	}{
		{0, "0 B"},
		{1023, "1023 B"},
		{1024, "1.0 KiB"},
		{1024 * 1024, "1.00 MiB"},
		{2 * 1024 * 1024, "2.00 MiB"},
	}
	for _, c := range cases {
		got := FormatByteCount(c.bytes)
		if got != c.want {
			t.Errorf("FormatByteCount(%d) = %q, want %q", c.bytes, got, c.want)
		}
	}
}

func TestCountingConnCounts(t *testing.T) {
	t.Parallel()

	c1, c2 := net.Pipe()
	defer func() { _ = c1.Close() }()
	defer func() { _ = c2.Close() }()

	st := New(true)
	conn := &CountingConn{Conn: c1, Stats: st}

	go func() {
		peer := make([]byte, 4)
		_, _ = io.ReadFull(c2, peer)
		_, _ = c2.Write([]byte("pong"))
	}()

	if _, err := conn.Write([]byte("ping")); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 4)
	if _, err := io.ReadFull(conn, buf); err != nil {
		t.Fatal(err)
	}
	tx, rx := st.Counters()
	if tx != 4 || rx != 4 {
		t.Errorf("tx=%d rx=%d, want 4/4", tx, rx)
	}
}

// Живость канала подтверждают и служебные кадры транспорта, а UI они врали бы.
func TestLivenessRxSeparateFromUserCounters(t *testing.T) {
	t.Parallel()

	s := New(true)
	s.AddRx(100)
	s.AddTx(50)
	s.AddWireRx(900)

	tx, rx := s.Counters()
	if tx != 50 || rx != 100 {
		t.Errorf("Counters() = %d/%d, want 50/100", tx, rx)
	}
	if got := s.LivenessRx(); got != 1000 {
		t.Errorf("LivenessRx() = %d, want 1000", got)
	}
}

func TestAddWireRxRespectsDisabled(t *testing.T) {
	t.Parallel()

	s := New(false)
	s.AddWireRx(512)
	if got := s.LivenessRx(); got != 0 {
		t.Errorf("LivenessRx() = %d, want 0 when disabled", got)
	}
}
