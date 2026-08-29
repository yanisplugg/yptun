// Package allocpace распределяет во времени вызовы TURN Allocate: квота считается на
// username, поэтому залп от всех потоков сразу упирается в неё плотнее, чем очередь.
package allocpace

import (
	"context"
	"sync"
	"time"
)

const DefaultInterval = 200 * time.Millisecond

type Pacer struct {
	mu   sync.Mutex
	next time.Time
	step time.Duration
}

func New(step time.Duration) *Pacer {
	return &Pacer{step: step}
}

// Wait возвращает false, только если ctx отменён - слот всё равно занят и не переиспользуется.
func (p *Pacer) Wait(ctx context.Context) bool {
	if p == nil {
		return ctx.Err() == nil
	}
	wait := time.Until(p.slot())
	if wait <= 0 {
		return ctx.Err() == nil
	}
	t := time.NewTimer(wait)
	defer t.Stop()
	select {
	case <-t.C:
		return true
	case <-ctx.Done():
		return false
	}
}

func (p *Pacer) slot() time.Time {
	p.mu.Lock()
	defer p.mu.Unlock()
	now := time.Now()
	if p.next.Before(now) {
		p.next = now
	}
	slot := p.next
	p.next = slot.Add(p.step)
	return slot
}
