package session

import (
	"context"
	"math"
	"sync/atomic"
	"time"

	"github.com/samosvalishe/free-turn-proxy/internal/stats"
)

// traffic - счётчики сессии плюс мгновенная скорость. Скорость считает тикер по
// разнице счётчиков: на горячем пути это дешевле per-packet таймстемпов.
type traffic struct {
	stats  *stats.Stats
	txRate atomic.Int64
	rxRate atomic.Int64
}

func newTraffic() *traffic {
	return &traffic{stats: stats.New(true)}
}

func (t *traffic) rateMeter(ctx context.Context, interval time.Duration) {
	tick := time.NewTicker(interval)
	defer tick.Stop()
	var prevTx, prevRx uint64
	for {
		select {
		case <-ctx.Done():
			t.txRate.Store(0)
			t.rxRate.Store(0)
			return
		case <-tick.C:
			tx, rx := t.stats.Counters()
			t.txRate.Store(clampInt64(tx - prevTx))
			t.rxRate.Store(clampInt64(rx - prevRx))
			prevTx, prevRx = tx, rx
		}
	}
}

// clampInt64 насыщает uint64 до int64. Реальный трафик до MaxInt64 не доходит,
// но насыщение делает конверсию безопасной от переполнения.
func clampInt64(u uint64) int64 {
	if u > math.MaxInt64 {
		return math.MaxInt64
	}
	return int64(u)
}
