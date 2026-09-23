package l3

import (
	"sync"
	"time"
)

const (
	ctTimeoutEstablished = 5 * time.Minute
	ctTimeoutClosing     = 15 * time.Second
	ctSweepInterval      = 30 * time.Second
)

type ctEntry struct {
	lastSeen time.Time
	dying    bool
}

type conntrack struct {
	mu      sync.RWMutex
	entries map[flowKey]*ctEntry
	stop    chan struct{}
	stopped sync.Once
}

func newConntrack() *conntrack {
	ct := &conntrack{
		entries: make(map[flowKey]*ctEntry, 1024),
		stop:    make(chan struct{}),
	}
	go ct.sweepLoop()
	return ct
}

func (c *conntrack) Insert(k flowKey) {
	now := time.Now()
	c.mu.Lock()
	if e, ok := c.entries[k]; ok {
		e.lastSeen = now
	} else {
		c.entries[k] = &ctEntry{lastSeen: now}
	}
	c.mu.Unlock()
}

func (c *conntrack) Touch(k flowKey, dying bool) {
	c.mu.Lock()
	if e, ok := c.entries[k]; ok {
		e.lastSeen = time.Now()
		if dying {
			e.dying = true
		}
	}
	c.mu.Unlock()
}

func (c *conntrack) Exists(k flowKey) bool {
	c.mu.RLock()
	_, ok := c.entries[k]
	c.mu.RUnlock()
	return ok
}

func (c *conntrack) Close() {
	c.stopped.Do(func() {
		close(c.stop)
	})
}

func (c *conntrack) sweepLoop() {
	t := time.NewTicker(ctSweepInterval)
	defer t.Stop()
	for {
		select {
		case <-c.stop:
			return
		case <-t.C:
			c.sweep()
		}
	}
}

func (c *conntrack) sweep() {
	now := time.Now()
	c.mu.Lock()
	for k, e := range c.entries {
		timeout := ctTimeoutEstablished
		if e.dying {
			timeout = ctTimeoutClosing
		}
		if now.Sub(e.lastSeen) > timeout {
			delete(c.entries, k)
		}
	}
	c.mu.Unlock()
}
