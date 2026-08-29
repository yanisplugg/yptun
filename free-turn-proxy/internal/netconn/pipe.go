package netconn

import (
	"context"
	"io"
	"net"
	"sync"
	"time"
)

// BiCopy закрывает оба conn, как только копирование в любую сторону завершится.
func BiCopy(ctx context.Context, c1, c2 net.Conn, errf func(format string, v ...any)) (int64, int64) {
	ctx2, cancel := context.WithCancel(ctx)
	setDeadline := func(t time.Time, what string) {
		if err := c1.SetDeadline(t); err != nil && errf != nil {
			errf("BiCopy: c1 %s: %v", what, err)
		}
		if err := c2.SetDeadline(t); err != nil && errf != nil {
			errf("BiCopy: c2 %s: %v", what, err)
		}
	}
	hookDone := make(chan struct{})
	stopOnCancel := context.AfterFunc(ctx2, func() {
		defer close(hookDone)
		setDeadline(time.Now(), "SetDeadline")
	})

	var wg sync.WaitGroup
	var c1FromC2, c2FromC1 int64
	wg.Go(func() {
		defer cancel()
		n, err := io.Copy(c1, c2)
		c1FromC2 = n
		if err != nil && errf != nil {
			errf("BiCopy: c1<-c2: %v", err)
		}
	})
	wg.Go(func() {
		defer cancel()
		n, err := io.Copy(c2, c1)
		c2FromC1 = n
		if err != nil && errf != nil {
			errf("BiCopy: c2<-c1: %v", err)
		}
	})
	wg.Wait()

	// Дедлайны сбрасываются только после того, как хук отменён или доработал - иначе
	// он взвёлся бы следом и оставил conn'ы просроченными навсегда.
	if !stopOnCancel() {
		<-hookDone
	}
	setDeadline(time.Time{}, "clear deadline")
	return c1FromC2, c2FromC1
}
