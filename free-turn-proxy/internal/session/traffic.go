package session

import (
	"math"
	"sync"
	"time"

	"github.com/samosvalishe/free-turn-proxy/internal/stats"
)

// traffic агрегирует счётчики и считает скорость передачи.
type traffic struct {
	stats *stats.Stats

	mu     sync.Mutex
	prevTx uint64
	prevRx uint64
	prevAt time.Time
}

func newTraffic() *traffic {
	return &traffic{stats: stats.New(true)}
}

// rates - средняя скорость (байт/с) с прошлого вызова. Считается на запрос, а не
// фоновым тикером: спрашивает только открытый экран, и будить процесс ради цифры,
// которую никто не читает, незачем.
func (t *traffic) rates() (txRate, rxRate int64) {
	tx, rx := t.stats.Counters()
	now := time.Now()

	t.mu.Lock()
	defer t.mu.Unlock()
	prevTx, prevRx, prevAt := t.prevTx, t.prevRx, t.prevAt
	t.prevTx, t.prevRx, t.prevAt = tx, rx, now

	elapsed := now.Sub(prevAt)
	if prevAt.IsZero() || elapsed <= 0 {
		return 0, 0
	}
	sec := elapsed.Seconds()
	return perSecond(tx-prevTx, sec), perSecond(rx-prevRx, sec)
}

func perSecond(delta uint64, sec float64) int64 {
	rate := float64(delta) / sec
	if rate >= math.MaxInt64 {
		return math.MaxInt64
	}
	return int64(rate)
}
