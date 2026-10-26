package gate

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"sync/atomic"
	"time"
)

// ai-generated: the whole file (the local target's HTTP origin).

const (
	// smallBytes is the size of /kb, what every connect fetches.
	smallBytes = 1024
	// patternBytes is the block /big repeats, one write per block.
	patternBytes = 64 << 10
	// originHeaderTimeout only keeps a stuck connection from living forever.
	// It is long on purpose: a tunnel that stalls a request must show up as
	// a slow connect in the client's numbers, not as the origin hanging up.
	originHeaderTimeout = 2 * time.Minute
)

// ErrOriginSize is a /big size the origin cannot announce.
var ErrOriginSize = errors.New("origin: negative big file size")

// Origin is the HTTP server the local target's client pulls from and pushes
// to. It lives in the test process on loopback; the server side of the pair
// reaches it as any exit would.
type Origin struct {
	Addr string   // host:port on 127.0.0.1
	URLs LoadURLs // the local target's Load()

	srv     *http.Server
	big     int64
	pattern []byte
	sink    atomic.Int64
}

// StartOrigin listens on a free loopback port and serves GET /kb (1 KB),
// GET /big (bigBytes of a repeating pattern, with a Content-Length) and
// POST /sink (the body counted and discarded, its length the answer).
func StartOrigin(bigBytes int64) (*Origin, error) {
	if bigBytes < 0 {
		return nil, fmt.Errorf("%w: %d", ErrOriginSize, bigBytes)
	}
	var lc net.ListenConfig
	ln, err := lc.Listen(context.Background(), "tcp4", "127.0.0.1:0")
	if err != nil {
		return nil, fmt.Errorf("listen origin: %w", err)
	}
	o := &Origin{Addr: ln.Addr().String(), big: bigBytes, pattern: make([]byte, patternBytes)}
	for i := range o.pattern {
		o.pattern[i] = byte(i*7 + 13)
	}
	base := "http://" + o.Addr
	o.URLs = LoadURLs{Small: base + "/kb", Big: base + "/big", Sink: base + "/sink", BigBytes: bigBytes}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /kb", o.serveSmall)
	mux.HandleFunc("GET /big", o.serveBig)
	mux.HandleFunc("POST /sink", o.serveSink)
	o.srv = &http.Server{Handler: mux, ReadHeaderTimeout: originHeaderTimeout}
	go func() { _ = o.srv.Serve(ln) }()
	return o, nil
}

// serveSmall writes the 1 KB a connect fetches.
func (o *Origin) serveSmall(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Length", strconv.Itoa(smallBytes))
	_, _ = w.Write(o.pattern[:smallBytes])
}

// serveBig writes the pattern block over and over, the last one cut to size.
// The length goes out first, so a body the tunnel cuts short reads as one.
func (o *Origin) serveBig(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Length", strconv.FormatInt(o.big, 10))
	for left := o.big; left > 0; {
		n := min(left, int64(len(o.pattern)))
		if _, err := w.Write(o.pattern[:n]); err != nil {
			return
		}
		left -= n
	}
}

// serveSink counts and discards a body. A body that ends before its length
// is refused, so an upload the tunnel cut short never passes as a push.
func (o *Origin) serveSink(w http.ResponseWriter, r *http.Request) {
	n, err := io.Copy(io.Discard, r.Body)
	o.sink.Add(n)
	if err != nil {
		http.Error(w, "sink: "+err.Error(), http.StatusBadRequest)
		return
	}
	_, _ = io.WriteString(w, strconv.FormatInt(n, 10))
}

// SinkBytes is how much the sink has swallowed so far, short bodies included.
func (o *Origin) SinkBytes() int64 { return o.sink.Load() }

// Close stops the server and drops its connections. A second Close is a no-op.
func (o *Origin) Close() { _ = o.srv.Close() }
