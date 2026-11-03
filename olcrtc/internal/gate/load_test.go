package gate

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"runtime"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// ai-generated: whole file, cover for the load helpers, run straight against
// the loopback origin with no tunnel in between, or over a transport without
// sockets where only timing matters.

// directHTTP is a dialer that reaches the origin without a tunnel, and an
// origin with a 2 MiB big file that the test closes when it ends.
func directHTTP(t *testing.T) (DialFunc, *Origin) {
	t.Helper()
	var d net.Dialer
	return d.DialContext, startTestOrigin(t, 2<<20)
}

func TestPullPushAndBurstAgainstTheOrigin(t *testing.T) {
	dial, o := directHTTP(t)
	hc := HTTPClient(dial, 30*time.Second)
	ctx := context.Background()
	n, err := Pull(ctx, hc, o.URLs.Big, o.URLs.BigBytes)
	if err != nil || n != 2<<20 {
		t.Fatalf("Pull = %d %v", n, err)
	}
	if _, err := Pull(ctx, hc, o.URLs.Big, 1); !errors.Is(err, ErrPullSize) {
		t.Fatalf("Pull of a size that differs = %v, want ErrPullSize", err)
	}
	if _, err := Pull(ctx, hc, o.URLs.Small+"-missing", smallBytes); !errors.Is(err, ErrHTTPStatus) {
		t.Fatalf("Pull of a 404 = %v, want ErrHTTPStatus", err)
	}
	if err := Push(ctx, hc, o.URLs.Sink, 300_000); err != nil || o.SinkBytes() != 300_000 {
		t.Fatalf("Push = %v, sink %d", err, o.SinkBytes())
	}
	if err := Push(ctx, hc, o.URLs.Small, 10); !errors.Is(err, ErrHTTPStatus) {
		t.Fatalf("Push to a resource that takes no POST = %v, want ErrHTTPStatus", err)
	}
	out := ConnectBurst(ctx, hc, o.URLs.Small, 8, 4)
	if out.OK != 12 || out.Total != 12 || out.P95 <= 0 || out.Bytes != 12*smallBytes || out.Took <= 0 {
		t.Fatalf("ConnectBurst = %+v", out)
	}
}

// TestPushStreamsItsBody holds Push to a body made as it goes out. S7 weighs
// this very process, so a push that held its whole body would put S3's four
// bodies, 20 MiB, on the heap the phone's budget is judged by.
func TestPushStreamsItsBody(t *testing.T) {
	dial, o := directHTTP(t)
	hc := HTTPClient(dial, 30*time.Second)
	const size = 32 << 20
	var before, after runtime.MemStats
	runtime.ReadMemStats(&before)
	if err := Push(context.Background(), hc, o.URLs.Sink, size); err != nil {
		t.Fatal(err)
	}
	runtime.ReadMemStats(&after)
	if o.SinkBytes() != size {
		t.Fatalf("the sink took %d of %d bytes", o.SinkBytes(), size)
	}
	if grew := after.TotalAlloc - before.TotalAlloc; grew > size/8 {
		t.Fatalf("a %d MiB push allocated %d MiB", size>>20, grew>>20)
	}
}

// roundTrip is an http.RoundTripper made of a function: a transport with no
// sockets, for what depends on timing alone.
type roundTrip func(req *http.Request) (*http.Response, error)

func (f roundTrip) RoundTrip(req *http.Request) (*http.Response, error) { return f(req) }

// smallAnswer is the origin's answer for the small resource, for a roundTrip.
func smallAnswer(req *http.Request) *http.Response {
	return &http.Response{StatusCode: http.StatusOK, Status: "200 OK", ContentLength: smallBytes,
		Body: io.NopCloser(bytes.NewReader(make([]byte, smallBytes))), Request: req}
}

// TestConnectBurstP95CoversEveryConnect has three of 24 concurrent connects
// answer slowly and the 24 sequential ones at once: 3 of 48 is more than
// 5 %, so the p95 of the burst is a slow connect.
func TestConnectBurstP95CoversEveryConnect(t *testing.T) {
	var seen atomic.Int32
	hc := &http.Client{Transport: roundTrip(func(req *http.Request) (*http.Response, error) {
		if seen.Add(1) <= 3 {
			time.Sleep(300 * time.Millisecond)
		}
		return smallAnswer(req), nil
	})}
	out := ConnectBurst(context.Background(), hc, "http://origin.invalid/kb", 24, 24)
	if out.OK != 48 || out.Total != 48 {
		t.Fatalf("ConnectBurst = %+v", out)
	}
	if out.P95 < 300*time.Millisecond {
		t.Fatalf("p95 = %s with 3 of 48 connects at 300 ms, want a slow one", out.P95)
	}
}

func TestParallelRunsAtOnceAndFoldsEveryResult(t *testing.T) {
	var started sync.WaitGroup
	started.Add(5)
	var out Outcome
	within(t, 5*time.Second, "Parallel of five that wait for each other", func() {
		out = Parallel(context.Background(), 5, func(_ context.Context, i int) (int64, error) {
			started.Done()
			started.Wait() // returns only once all five run at the same time
			if i%2 == 1 {
				return int64(i), errors.New("fake transfer failed")
			}
			return int64(i), nil
		})
	})
	if out.Total != 5 || out.OK != 3 || out.Bytes != 10 || out.Took <= 0 {
		t.Fatalf("Parallel = %+v, want 3 of 5 ok and every byte counted", out)
	}
}

func TestOnTopCountsConnectsUntilStopped(t *testing.T) {
	dial, o := directHTTP(t)
	hc := HTTPClient(dial, 10*time.Second)
	stop := OnTop(context.Background(), hc, o.URLs.Small, 30*time.Millisecond)
	time.Sleep(200 * time.Millisecond)
	out := stop()
	if out.Total < 4 || out.OK != out.Total {
		t.Fatalf("OnTop = %+v", out)
	}
	if again := stop(); again != out {
		t.Fatalf("a second stop = %+v, want %+v", again, out)
	}
}

// TestOnTopConnectsAtOnce stops an OnTop right after it starts: a load that
// ends inside one interval still had a connect on top of it, or S2 and S3
// would record 0 of 0, which the verdict fails.
func TestOnTopConnectsAtOnce(t *testing.T) {
	dial, o := directHTTP(t)
	stop := OnTop(context.Background(), HTTPClient(dial, 10*time.Second), o.URLs.Small, time.Hour)
	if out := stop(); out.Total != 1 || out.OK != 1 {
		t.Fatalf("OnTop stopped at once = %+v, want the one connect it opens with", out)
	}
}

// TestOnTopStartsNoFetchOnceEnded ends the loop, by stop and by its context,
// while a fetch slower than the interval is in flight: an on-top connect
// slower than onTopEvery, the degraded tunnel S2 and S3 are there to catch.
// When that fetch returns a tick is waiting too, and select picks among
// ready cases at random. A fetch started then runs after the load, counts in
// its connects and holds up stop for as long as it takes. Each way runs 32
// times, so a loop that lets the tick win half the time passes once in 2^32.
func TestOnTopStartsNoFetchOnceEnded(t *testing.T) {
	for _, c := range []struct {
		way string
		end func(stop chan struct{}, cancel context.CancelFunc)
	}{
		{"stop", func(stop chan struct{}, _ context.CancelFunc) { close(stop) }},
		{"context", func(_ chan struct{}, cancel context.CancelFunc) { cancel() }},
	} {
		t.Run(c.way, func(t *testing.T) {
			for trial := range 32 {
				ctx, cancel := context.WithCancel(context.Background())
				stop := make(chan struct{})
				var fetches atomic.Int32
				hc := &http.Client{Transport: roundTrip(func(req *http.Request) (*http.Response, error) {
					if fetches.Add(1) == 1 {
						c.end(stop, cancel)
					}
					time.Sleep(3 * time.Millisecond) // outlasts the interval: a tick is due when it returns
					return smallAnswer(req), nil
				})}
				var out Outcome
				within(t, 5*time.Second, "onTop ended by its "+c.way, func() {
					out = onTop(ctx, hc, "http://origin.invalid/kb", time.Millisecond, stop)
				})
				cancel()
				if n := fetches.Load(); n != 1 || out.Total != 1 {
					t.Fatalf("trial %d: %d fetches, onTop = %+v; want only the one in flight", trial, n, out)
				}
			}
		})
	}
}

func TestP95AndBps(t *testing.T) {
	ds := make([]time.Duration, 0, 100)
	for i := 100; i >= 1; i-- {
		ds = append(ds, time.Duration(i)*time.Millisecond)
	}
	if got := p95(ds); got != 95*time.Millisecond {
		t.Fatalf("p95 = %v", got)
	}
	if ds[0] != 100*time.Millisecond {
		t.Fatal("p95 sorted the caller's slice")
	}
	if p95(nil) != 0 || p95([]time.Duration{7}) != 7 || p95([]time.Duration{2, 1}) != 2 {
		t.Fatal("p95 of none, of one or of two")
	}
	if got := bps(1_000_000, 4*time.Second); got != 2_000_000 {
		t.Fatalf("bps = %v", got)
	}
	if bps(10, 0) != 0 {
		t.Fatal("bps over no time")
	}
}
