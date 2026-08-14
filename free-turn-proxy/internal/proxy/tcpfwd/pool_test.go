package tcpfwd

import (
	"sync/atomic"
	"testing"
)

// Зеркало активных сессий - единственный источник "сколько каналов поднято" для
// watchdog и UI в tcp-режиме: раньше счётчик оставался нулевым и сессию убивал
// таймаут подключения.
func TestSessionPoolMirrorsActiveCount(t *testing.T) {
	var active atomic.Int32
	pool := &SessionPool{active: &active}

	first := pool.Add(1, nil)
	if got := active.Load(); got != 1 {
		t.Fatalf("active = %d, want 1", got)
	}

	second := pool.Add(2, nil)
	if got := active.Load(); got != 2 {
		t.Fatalf("active = %d, want 2", got)
	}

	pool.Remove(first)
	if got := active.Load(); got != 1 {
		t.Fatalf("active after remove = %d, want 1", got)
	}

	pool.Remove(second)
	if got := active.Load(); got != 0 {
		t.Fatalf("active after drain = %d, want 0", got)
	}
	if pool.Count() != 0 {
		t.Fatalf("Count() = %d, want 0", pool.Count())
	}
}

func TestSessionPoolWithoutMirror(t *testing.T) {
	pool := &SessionPool{}
	ps := pool.Add(1, nil)
	pool.Remove(ps)
	if pool.Count() != 0 {
		t.Fatalf("Count() = %d, want 0", pool.Count())
	}
}
