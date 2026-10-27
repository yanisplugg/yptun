package gate

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"slices"
	"sync"
	"time"
)

// ai-generated: the whole file (the load the scenarios put through the
// tunnel: pulls, pushes, connect bursts and connects on top of a load).

var (
	// ErrHTTPStatus is an answer other than 200 OK.
	ErrHTTPStatus = errors.New("http status is not 200 OK")
	// ErrPullSize is a body of another length than the one asked for: a
	// transfer the tunnel cut short, or a resource that is not the one the
	// run expects.
	ErrPullSize = errors.New("pulled a body of another size")
)

// Outcome is what a batch of requests came to.
type Outcome struct {
	OK    int           // requests that did what was asked
	Total int           // requests made
	P95   time.Duration // 95th percentile of a request, the failed ones included
	Bytes int64         // bytes moved, what failed requests moved included
	Took  time.Duration // wall time of the batch
}

// Pull GETs url and insists on a 200 and exactly want bytes. It returns the
// bytes it read, a failed pull's included.
func Pull(ctx context.Context, hc *http.Client, url string, want int64) (int64, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, http.NoBody)
	if err != nil {
		return 0, fmt.Errorf("pull request: %w", err)
	}
	resp, err := hc.Do(req)
	if err != nil {
		return 0, fmt.Errorf("pull: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return 0, fmt.Errorf("pull: %w: %s", ErrHTTPStatus, resp.Status)
	}
	n, err := io.Copy(io.Discard, resp.Body)
	if err != nil {
		return n, fmt.Errorf("pull body after %d bytes: %w", n, err)
	}
	if n != want {
		return n, fmt.Errorf("pull: %w: %d bytes, want %d", ErrPullSize, n, want)
	}
	return n, nil
}

// Push POSTs size bytes to url and wants a 200. The body is made as it goes
// out, so a push holds a buffer, not its size: S7 weighs this process, and
// S3's four bodies held whole would be 20 MiB of heap that is not the
// client's.
func Push(ctx context.Context, hc *http.Client, url string, size int64) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, io.LimitReader(&filler{}, size))
	if err != nil {
		return fmt.Errorf("push request: %w", err)
	}
	req.ContentLength = size
	req.Header.Set("Content-Type", "application/octet-stream")
	resp, err := hc.Do(req)
	if err != nil {
		return fmt.Errorf("push: %w", err)
	}
	_, _ = io.Copy(io.Discard, resp.Body)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("push: %w: %s", ErrHTTPStatus, resp.Status)
	}
	return nil
}

// filler reads as an endless run of the bytes 0 to 255, a push's body.
type filler struct{ next byte }

func (f *filler) Read(p []byte) (int, error) {
	for i := range p {
		p[i] = f.next
		f.next++
	}
	return len(p), nil
}

// Parallel runs each n times at once, handing it the index, and folds the
// results. Took is the wall time of the whole batch, what a rate is over.
func Parallel(ctx context.Context, n int, each func(ctx context.Context, i int) (int64, error)) Outcome {
	out, _ := parallel(ctx, n, each)
	return out
}

// parallel is Parallel that also returns every request's duration.
func parallel(
	ctx context.Context, n int, each func(ctx context.Context, i int) (int64, error),
) (Outcome, []time.Duration) {
	var (
		wg sync.WaitGroup
		mu sync.Mutex
	)
	out := Outcome{Total: n}
	durations := make([]time.Duration, 0, n)
	start := time.Now()
	for i := range n {
		wg.Go(func() {
			t0 := time.Now()
			b, err := each(ctx, i)
			took := time.Since(t0)
			mu.Lock()
			defer mu.Unlock()
			out.Bytes += b
			durations = append(durations, took)
			if err == nil {
				out.OK++
			}
		})
	}
	wg.Wait()
	out.Took = time.Since(start)
	out.P95 = p95(durations)
	return out, durations
}

// ConnectBurst fetches the small resource at url concurrent times at once,
// then sequential times one after another. Every fetch is a fresh connect
// through the tunnel, as HTTPClient keeps no connection alive, and P95 is
// over all of them.
func ConnectBurst(ctx context.Context, hc *http.Client, url string, concurrent, sequential int) Outcome {
	start := time.Now()
	fetch := func(ctx context.Context, _ int) (int64, error) { return Pull(ctx, hc, url, smallBytes) }
	out, durations := parallel(ctx, concurrent, fetch)
	for i := range sequential {
		t0 := time.Now()
		b, err := fetch(ctx, i)
		durations = append(durations, time.Since(t0))
		out.Total++
		out.Bytes += b
		if err == nil {
			out.OK++
		}
	}
	out.Took = time.Since(start)
	out.P95 = p95(durations)
	return out
}

// OnTop fetches the small resource at url at once and then every interval,
// the way a browser keeps opening connections while a download runs. The
// first fetch goes out at once so a load shorter than the interval still had
// a connect on top of it. The returned stop ends the fetching, waits for a
// fetch in flight and says what the fetches came to; calling it again says
// the same.
func OnTop(ctx context.Context, hc *http.Client, url string, every time.Duration) func() Outcome {
	stop := make(chan struct{})
	done := make(chan Outcome, 1)
	go func() { done <- onTop(ctx, hc, url, every, stop) }()
	return sync.OnceValue(func() Outcome {
		close(stop)
		return <-done
	})
}

// onTop is OnTop's loop: a fetch, then the next tick, until stop closes or
// ctx ends. A fetch slower than the interval leaves a tick waiting when it
// returns, and select picks among ready cases at random, so a tick is let
// through only while stop and ctx still say go on: a fetch started after
// the end would run past the load and count in its connects.
func onTop(ctx context.Context, hc *http.Client, url string, every time.Duration, stop <-chan struct{}) Outcome {
	tick := time.NewTicker(every)
	defer tick.Stop()
	start := time.Now()
	var (
		out       Outcome
		durations []time.Duration
	)
	for {
		t0 := time.Now()
		b, err := Pull(ctx, hc, url, smallBytes)
		durations = append(durations, time.Since(t0))
		out.Total++
		out.Bytes += b
		if err == nil {
			out.OK++
		}
		select {
		case <-tick.C:
			select {
			case <-stop:
			case <-ctx.Done():
			default:
				continue
			}
		case <-stop:
		case <-ctx.Done():
		}
		out.Took, out.P95 = time.Since(start), p95(durations)
		return out
	}
}

// p95 is the 95th percentile by nearest rank, the smallest duration that at
// least 95 % of ds do not exceed; 0 for none. ds is left as it was.
func p95(ds []time.Duration) time.Duration {
	if len(ds) == 0 {
		return 0
	}
	sorted := slices.Sorted(slices.Values(ds))
	return sorted[(len(sorted)*95+99)/100-1]
}

// bps is b bytes over took in bits per second; 0 for a batch that took no
// time.
func bps(b int64, took time.Duration) float64 {
	if took <= 0 {
		return 0
	}
	return float64(b) * 8 / took.Seconds()
}
