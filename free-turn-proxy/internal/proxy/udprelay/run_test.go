package udprelay

import (
	"context"
	"errors"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/samosvalishe/free-turn-proxy/internal/logx"
	"github.com/samosvalishe/free-turn-proxy/internal/netconn"
	"github.com/samosvalishe/free-turn-proxy/internal/provider"
	"github.com/samosvalishe/free-turn-proxy/internal/safego"
	"github.com/samosvalishe/free-turn-proxy/internal/transport/dtlsdial"
)

type stubAuth struct{}

func (stubAuth) IsAuthError(error) bool   { return false }
func (stubAuth) HandleAuthError(int) bool { return false }
func (stubAuth) ResetErrors(int)          {}
func (stubAuth) DropCredentials(int)      {}
func (stubAuth) BackoffUntilUnix() int64  { return 0 }

type deadlineRecorder struct {
	net.PacketConn
	mu   sync.Mutex
	last time.Time
	set  bool
}

func (d *deadlineRecorder) SetReadDeadline(t time.Time) error {
	d.mu.Lock()
	d.last, d.set = t, true
	d.mu.Unlock()
	return d.PacketConn.SetReadDeadline(t)
}

func (d *deadlineRecorder) lastDeadline() (time.Time, bool) {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.last, d.set
}

func runFatalDeps(t *testing.T) (*dtlsdial.Dialer, *Params, *net.UDPAddr, *deadlineRecorder) {
	t.Helper()

	pipe, peerSide := netconn.PacketPipe(1500, 4)
	t.Cleanup(func() { _ = pipe.Close(); _ = peerSide.Close() })

	params := &Params{
		GetCreds: func(context.Context, int) (string, string, []string, error) {
			return "", "", nil, provider.ErrFatalNoStreams
		},
	}
	return &dtlsdial.Dialer{HandshakeTimeout: 100 * time.Millisecond},
		params,
		&net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 9},
		&deadlineRecorder{PacketConn: pipe}
}

// Фатальная ошибка провайдера отменяет только runCtx, поэтому будить runListener обязан
// сам Run: на молчащем LocalPipe тот сидит в ReadFrom, и без этого wg.Wait висит вечно.
func TestRunReturnsOnFatalProviderError(t *testing.T) {
	t.Parallel()
	dialer, params, peer, local := runFatalDeps(t)

	var connected atomic.Int32
	done := make(chan error, 1)
	go func() {
		done <- Run(context.Background(), dialer, stubAuth{}, logx.Nop(), &connected, nil, params, peer, local, 1)
	}()

	select {
	case err := <-done:
		if !errors.Is(err, ErrFatal) {
			t.Fatalf("err = %v, want ErrFatal", err)
		}
	case <-time.After(30 * time.Second):
		t.Fatal("Run hung on fatal provider error")
	}

	// Тот же LocalPipe достаётся следующей попытке: просроченный дедлайн порвал бы её чтение.
	last, set := local.lastDeadline()
	if !set {
		t.Fatal("read deadline was never touched")
	}
	if !last.IsZero() {
		t.Fatalf("read deadline left at %v, want cleared", last)
	}
}

// Паника в горутине стрима эквивалентна фатальной ошибке: продолжать релей нельзя, а
// ронять процесс приложения (ядро линкуется в него) - тем более.
func TestRunReturnsOnStreamPanic(t *testing.T) {
	t.Parallel()
	dialer, params, peer, local := runFatalDeps(t)
	params.GetCreds = func(context.Context, int) (string, string, []string, error) {
		panic("boom")
	}

	var connected atomic.Int32
	done := make(chan error, 1)
	go func() {
		done <- Run(context.Background(), dialer, stubAuth{}, logx.Nop(), &connected, nil, params, peer, local, 1)
	}()

	select {
	case err := <-done:
		if !errors.Is(err, safego.ErrPanic) {
			t.Fatalf("err = %v, want ErrPanic", err)
		}
		if !errors.Is(err, ErrFatal) {
			t.Fatalf("err = %v, want ErrFatal", err)
		}
	case <-time.After(30 * time.Second):
		t.Fatal("Run hung on panic in stream goroutine")
	}
}

func TestRunFatalDoesNotWaitWarmupBarrier(t *testing.T) {
	t.Parallel()
	dialer, params, peer, local := runFatalDeps(t)

	var connected atomic.Int32
	done := make(chan error, 1)
	start := time.Now()
	go func() {
		done <- Run(context.Background(), dialer, stubAuth{}, logx.Nop(), &connected, nil, params, peer, local, 4)
	}()

	select {
	case err := <-done:
		if !errors.Is(err, ErrFatal) {
			t.Fatalf("err = %v, want ErrFatal", err)
		}
		if elapsed := time.Since(start); elapsed >= streamStartBarrier {
			t.Fatalf("Run took %v, want less than warm-up barrier %v", elapsed, streamStartBarrier)
		}
	case <-time.After(2 * streamStartBarrier):
		t.Fatal("Run hung on fatal provider error")
	}
}
