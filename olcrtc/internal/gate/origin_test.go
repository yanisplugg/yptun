package gate

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"testing"
)

// ai-generated: whole file, unit cover for the local HTTP origin.

// originReply is what one request to the origin came back with.
type originReply struct {
	status int
	body   []byte
	length int64 // the Content-Length the response announced, -1 if none
}

// startTestOrigin starts an origin the test closes when it ends.
func startTestOrigin(t *testing.T, bigBytes int64) *Origin {
	t.Helper()
	o, err := StartOrigin(bigBytes)
	if err != nil {
		t.Fatalf("StartOrigin(%d): %v", bigBytes, err)
	}
	t.Cleanup(o.Close)
	return o
}

// originDo sends one request on a connection of its own, as the gate's
// client does. It never calls t.Fatal, so a goroutine may use it.
func originDo(ctx context.Context, method, url string, body []byte) (originReply, error) {
	req, err := http.NewRequestWithContext(ctx, method, url, bytes.NewReader(body))
	if err != nil {
		return originReply{}, err
	}
	req.Close = true
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return originReply{}, err
	}
	defer func() { _ = resp.Body.Close() }()
	got, err := io.ReadAll(resp.Body)
	return originReply{status: resp.StatusCode, body: got, length: resp.ContentLength}, err
}

func TestOriginServesSmallBigAndSink(t *testing.T) {
	o := startTestOrigin(t, 3<<20)
	if o.URLs.BigBytes != 3<<20 {
		t.Fatalf("BigBytes = %d", o.URLs.BigBytes)
	}
	for url, want := range map[string]int64{o.URLs.Small: 1024, o.URLs.Big: 3 << 20} {
		r, err := originDo(t.Context(), http.MethodGet, url, nil)
		if err != nil {
			t.Fatal(err)
		}
		if r.status != http.StatusOK || int64(len(r.body)) != want || r.length != want {
			t.Fatalf("GET %s = %d, %d bytes, Content-Length %d; want 200, %d bytes, Content-Length %d",
				url, r.status, len(r.body), r.length, want, want)
		}
	}
	r, err := originDo(t.Context(), http.MethodPost, o.URLs.Sink, make([]byte, 70000))
	if err != nil {
		t.Fatal(err)
	}
	if r.status != http.StatusOK || string(r.body) != "70000" || o.SinkBytes() != 70000 {
		t.Fatalf("POST sink = %d %q, sink bytes %d", r.status, r.body, o.SinkBytes())
	}
}

func TestOriginBigIsExactlyTheSizeAsked(t *testing.T) {
	// Sizes around the block /big repeats, so the last short block is cut
	// right too.
	for _, size := range []int64{0, 1, patternBytes - 1, patternBytes + 1, 3*patternBytes + 17} {
		o := startTestOrigin(t, size)
		r, err := originDo(t.Context(), http.MethodGet, o.URLs.Big, nil)
		if err != nil {
			t.Fatal(err)
		}
		if r.status != http.StatusOK || int64(len(r.body)) != size || r.length != size {
			t.Fatalf("GET /big of %d = %d, %d bytes, Content-Length %d", size, r.status, len(r.body), r.length)
		}
	}
}

func TestOriginSinkAddsUpConcurrentPostsAndTakesNothingElse(t *testing.T) {
	o := startTestOrigin(t, 0)
	const posts, size = 4, 50_000
	errs := make(chan error, posts)
	var wg sync.WaitGroup
	for range posts {
		wg.Go(func() {
			r, err := originDo(t.Context(), http.MethodPost, o.URLs.Sink, make([]byte, size))
			if err == nil && (r.status != http.StatusOK || string(r.body) != strconv.Itoa(size)) {
				err = fmt.Errorf("POST sink = %d %q", r.status, r.body)
			}
			errs <- err
		})
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		if err != nil {
			t.Fatal(err)
		}
	}
	if got := o.SinkBytes(); got != posts*size {
		t.Fatalf("sink bytes = %d, want %d", got, posts*size)
	}
	r, err := originDo(t.Context(), http.MethodGet, o.URLs.Sink, nil)
	if err != nil {
		t.Fatal(err)
	}
	if r.status != http.StatusMethodNotAllowed || o.SinkBytes() != posts*size {
		t.Fatalf("GET sink = %d, sink bytes %d; want 405, %d", r.status, o.SinkBytes(), posts*size)
	}
}

// An upload the tunnel cuts short must not come back 200: Push counts a 200
// as a push that arrived whole.
func TestOriginSinkRefusesAShortBody(t *testing.T) {
	o := startTestOrigin(t, 0)
	var d net.Dialer
	conn, err := d.DialContext(t.Context(), "tcp4", o.Addr)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = conn.Close() }()
	tcp, ok := conn.(*net.TCPConn)
	if !ok {
		t.Fatalf("dial gave %T", conn)
	}
	// 40 of the 100 bytes announced, then the write side closes while the
	// read side waits for the answer.
	req := "POST /sink HTTP/1.1\r\nHost: origin\r\nContent-Length: 100\r\n\r\n" + strings.Repeat("x", 40)
	if _, err = io.WriteString(tcp, req); err != nil {
		t.Fatal(err)
	}
	if err = tcp.CloseWrite(); err != nil {
		t.Fatal(err)
	}
	resp, err := http.ReadResponse(bufio.NewReader(tcp), nil)
	if err != nil {
		t.Fatal(err)
	}
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest || o.SinkBytes() != 40 {
		t.Fatalf("short POST sink = %d, sink bytes %d; want 400, 40", resp.StatusCode, o.SinkBytes())
	}
}

func TestStartOriginRefusesANegativeSize(t *testing.T) {
	o, err := StartOrigin(-1)
	if !errors.Is(err, ErrOriginSize) || o != nil {
		t.Fatalf("StartOrigin(-1) = %v, %v; want nil, ErrOriginSize", o, err)
	}
}

func TestOriginCloseStopsServing(t *testing.T) {
	o, err := StartOrigin(0)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := originDo(t.Context(), http.MethodGet, o.URLs.Small, nil); err != nil {
		t.Fatalf("GET before Close: %v", err)
	}
	o.Close()
	if _, err := originDo(t.Context(), http.MethodGet, o.URLs.Small, nil); err == nil {
		t.Fatal("GET after Close succeeded")
	}
	o.Close() // a second Close, as a defer and a cleanup may both make, is harmless
}
