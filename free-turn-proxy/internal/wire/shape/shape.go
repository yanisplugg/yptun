// Package shape реализует пейсинг пакетов (packet pacing) с джиттером и всплесками для мимикрии под аудиопоток.
package shape

import (
	"net"
	"sync"
	"time"

	dtlsnet "github.com/pion/dtls/v3/pkg/net"

	"github.com/samosvalishe/free-turn-proxy/internal/randx"
)

const (
	jitterPct    = 0.10
	burstMax     = 3
	burstPercent = 30
)

// Shaper рассчитывает задержки между отправками пакетов.
type Shaper struct {
	interval time.Duration

	mu       sync.Mutex
	lastSend time.Time
	burst    int
}

func New(interval time.Duration) *Shaper {
	return &Shaper{interval: interval}
}

func (s *Shaper) Wait() {
	if s.interval <= 0 {
		return
	}

	s.mu.Lock()
	if s.burst > 0 {
		s.burst--
		s.lastSend = time.Now()
		s.mu.Unlock()
		return
	}
	if randx.Intn(100) < burstPercent {
		s.burst = randx.Intn(burstMax)
		s.lastSend = time.Now()
		s.mu.Unlock()
		return
	}

	wait := s.interval - time.Since(s.lastSend)
	if wait > 0 {
		if jitter := time.Duration(float64(s.interval) * jitterPct); jitter > 0 {
			wait += time.Duration(randx.Intn(int(jitter)*2+1)) - jitter
		}
	}
	s.lastSend = time.Now().Add(max(wait, 0))
	s.mu.Unlock()

	if wait > 0 {
		time.Sleep(wait)
	}
}

// ShapedPacketConn применяет пейсинг к операциям WriteTo.
type ShapedPacketConn struct {
	net.PacketConn
	shaper *Shaper
}

func (s *ShapedPacketConn) WriteTo(b []byte, addr net.Addr) (int, error) {
	s.shaper.Wait()
	return s.PacketConn.WriteTo(b, addr)
}

// WrapPacketConn оборачивает net.PacketConn с межпакетной задержкой interval.
func WrapPacketConn(conn net.PacketConn, interval time.Duration) net.PacketConn {
	if interval <= 0 {
		return conn
	}
	return &ShapedPacketConn{PacketConn: conn, shaper: New(interval)}
}

type shapedPacketListener struct {
	inner    dtlsnet.PacketListener
	interval time.Duration
}

func (l *shapedPacketListener) Accept() (net.PacketConn, net.Addr, error) {
	pc, addr, err := l.inner.Accept()
	if err != nil {
		return pc, addr, err
	}
	return &ShapedPacketConn{PacketConn: pc, shaper: New(l.interval)}, addr, nil
}

func (l *shapedPacketListener) Close() error   { return l.inner.Close() }
func (l *shapedPacketListener) Addr() net.Addr { return l.inner.Addr() }

// WrapPacketListener добавляет пейсинг к WriteTo для входящих соединений listener.
func WrapPacketListener(l dtlsnet.PacketListener, interval time.Duration) dtlsnet.PacketListener {
	if interval <= 0 {
		return l
	}
	return &shapedPacketListener{inner: l, interval: interval}
}
