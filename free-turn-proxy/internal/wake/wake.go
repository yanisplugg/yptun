// Package wake оповещает компоненты о выходе устройства из режима сна.
package wake

import (
	"context"
	"sync"
	"time"
)

// Notifier рассылает сигнал пробуждения слушателям.
type Notifier struct {
	mu sync.Mutex
	ch chan struct{}
}

func New() *Notifier {
	return &Notifier{ch: make(chan struct{})}
}

// Chan возвращает канал, закрывающийся при следующем вызове Fire (nil для nil-ресивера).
func (n *Notifier) Chan() <-chan struct{} {
	if n == nil {
		return nil
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	n.initLocked()
	return n.ch
}

func (n *Notifier) Fire() {
	if n == nil {
		return
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	n.initLocked()
	close(n.ch)
	n.ch = make(chan struct{})
}

func (n *Notifier) initLocked() {
	if n.ch == nil {
		n.ch = make(chan struct{})
	}
}

// Watch отслеживает разрывы времени между тиками и вызывает Fire при обнаружении сна.
func (n *Notifier) Watch(ctx context.Context, tick, threshold time.Duration, onGap func(time.Duration)) {
	t := time.NewTicker(tick)
	defer t.Stop()

	last := time.Now()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			now := time.Now()
			// Round(0) убирает монотонные часы: их разница с wall clock отражает сон.
			gap := now.Round(0).Sub(last.Round(0)) - now.Sub(last)
			last = now
			if gap < threshold {
				continue
			}
			if onGap != nil {
				onGap(gap)
			}
			n.Fire()
		}
	}
}
