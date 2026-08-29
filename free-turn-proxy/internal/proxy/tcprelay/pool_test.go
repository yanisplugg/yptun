package tcprelay

import (
	"sync/atomic"
	"testing"

	"github.com/samosvalishe/free-turn-proxy/internal/netconn"
	"github.com/samosvalishe/free-turn-proxy/internal/transport/kcpmux"
	"github.com/xtaci/smux"
)

// fakeSession - живая smux-сессия поверх пары в памяти; закрытие видно через IsClosed.
func fakeSession(t *testing.T) *smux.Session {
	t.Helper()
	a, b := netconn.DatagramPipe(2048, 64)
	t.Cleanup(func() { _ = a.Close(); _ = b.Close() })

	kcpSess, err := kcpmux.Dial(a, kcpmux.DefaultProfile())
	if err != nil {
		t.Fatalf("kcp dial: %v", err)
	}
	sess, err := smux.Client(kcpSess, kcpmux.SmuxConfig())
	if err != nil {
		t.Fatalf("smux client: %v", err)
	}
	t.Cleanup(func() { _ = sess.Close() })
	return sess
}

func TestPoolPickRoundRobin(t *testing.T) {
	t.Parallel()

	var active atomic.Int32
	pool := newSessionPool(&active)
	if pool.Pick() != nil {
		t.Fatal("empty pool must yield nil")
	}

	ps1 := pool.Add(1, fakeSession(t), nil)
	ps2 := pool.Add(2, fakeSession(t), nil)
	if pool.Count() != 2 || active.Load() != 2 {
		t.Fatalf("count=%d active=%d, want 2/2", pool.Count(), active.Load())
	}

	seen := map[int]int{}
	for range 4 {
		seen[pool.Pick().id]++
	}
	if seen[ps1.id] != 2 || seen[ps2.id] != 2 {
		t.Errorf("round-robin skewed: %v", seen)
	}

	pool.Remove(ps1)
	if pool.Count() != 1 || active.Load() != 1 {
		t.Fatalf("after remove: count=%d active=%d, want 1/1", pool.Count(), active.Load())
	}
	for range 3 {
		if got := pool.Pick(); got != ps2 {
			t.Fatalf("Pick() = %v, want session 2", got)
		}
	}

	pool.Remove(ps2)
	if pool.Pick() != nil || active.Load() != 0 {
		t.Errorf("drained pool must yield nil, active=%d", active.Load())
	}
}

func TestPoolPickSkipsClosed(t *testing.T) {
	t.Parallel()

	pool := newSessionPool(nil)
	ps := pool.Add(1, fakeSession(t), nil)
	_ = ps.sess.Close()
	if got := pool.Pick(); got != nil {
		t.Errorf("Pick() = %v, want nil for closed session", got)
	}
}

func TestPoolReadyAndCloseAll(t *testing.T) {
	t.Parallel()

	pool := newSessionPool(nil)
	select {
	case <-pool.Ready():
		t.Fatal("Ready() closed before first session")
	default:
	}

	ps := pool.Add(1, fakeSession(t), nil)
	<-pool.Ready()

	pool.CloseAll()
	if !ps.sess.IsClosed() {
		t.Error("CloseAll left session open")
	}
	// CloseAll не трогает состав пула: переподнимает сессии maintainSession.
	if pool.Count() != 1 {
		t.Errorf("Count() = %d, want 1", pool.Count())
	}
}
