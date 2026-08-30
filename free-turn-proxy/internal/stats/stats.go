// Package stats реализует счётчики сетевого трафика и пропускной способности.
package stats

import (
	"fmt"
	"net"
	"sync/atomic"
	"time"
)

// Stats хранит счётчики переданных и принятых байт.
type Stats struct {
	tx      atomic.Uint64
	rx      atomic.Uint64
	wireRx  atomic.Uint64
	enabled bool
}

func New(enabled bool) *Stats {
	return &Stats{enabled: enabled}
}

func (s *Stats) Counters() (tx, rx uint64) {
	return s.tx.Load(), s.rx.Load()
}

// LivenessRx - приём на проводе: служебные кадры транспорта тоже подтверждают живость
// канала, а прикладной счётчик на простое стоит.
func (s *Stats) LivenessRx() uint64 {
	return s.rx.Load() + s.wireRx.Load()
}

func (s *Stats) AddWireRx(n int) {
	if n <= 0 || !s.enabled {
		return
	}
	s.wireRx.Add(uint64(n))
}

func (s *Stats) AddTx(n int) {
	if n <= 0 {
		return
	}
	if !s.enabled {
		return
	}
	s.tx.Add(uint64(n))
}

func (s *Stats) AddRx(n int) {
	if n <= 0 {
		return
	}
	if !s.enabled {
		return
	}
	s.rx.Add(uint64(n))
}

// FormatBitsPerSecond форматирует пропускную способность (bit/s, kbit/s, Mbit/s).
func FormatBitsPerSecond(bytes uint64, interval time.Duration) string {
	if interval <= 0 {
		interval = time.Second
	}

	bps := float64(bytes*8) / interval.Seconds()
	if bps >= 1_000_000 {
		return fmt.Sprintf("%.2f Mbit/s", bps/1_000_000)
	}
	if bps >= 1_000 {
		return fmt.Sprintf("%.1f kbit/s", bps/1_000)
	}
	return fmt.Sprintf("%.0f bit/s", bps)
}

// FormatByteCount форматирует количество байт в человекочитаемый вид (B, KiB, MiB).
func FormatByteCount(bytes uint64) string {
	if bytes >= 1024*1024 {
		return fmt.Sprintf("%.2f MiB", float64(bytes)/(1024*1024))
	}
	if bytes >= 1024 {
		return fmt.Sprintf("%.1f KiB", float64(bytes)/1024)
	}
	return fmt.Sprintf("%d B", bytes)
}

// CountingConn учитывает прикладные байты - те, что видит пользователь в UI.
type CountingConn struct {
	net.Conn
	Stats *Stats
}

func (c *CountingConn) Read(p []byte) (int, error) {
	n, err := c.Conn.Read(p)
	c.Stats.AddRx(n)
	return n, err
}

func (c *CountingConn) Write(p []byte) (int, error) {
	n, err := c.Conn.Write(p)
	c.Stats.AddTx(n)
	return n, err
}

// WireConn учитывает байты на проводе (с оверхедом транспорта и ретрансмитами) - только
// для проверки живости, в пользовательские счётчики они не идут.
type WireConn struct {
	net.Conn
	Stats *Stats
}

func (c *WireConn) Read(p []byte) (int, error) {
	n, err := c.Conn.Read(p)
	c.Stats.AddWireRx(n)
	return n, err
}
