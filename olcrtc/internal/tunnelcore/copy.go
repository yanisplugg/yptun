package tunnelcore

import (
	"context"
	"errors"
	"io"
	"sync"
	"sync/atomic"
	"time"

	"github.com/xtaci/smux"
)

// HalfOpenGrace bounds how long the second direction of a copy may stay open
// and silent after the first has finished.
//
// A clean EOF on one side half-closes the other, and both halves here really
// do half-close: net.TCPConn and smux.Stream both send their FIN and keep
// reading. That is correct, and it is also how a connection outlives its
// use: when the peer that still owes an EOF never sends one — a remote that
// went away behind the exit, an app that was killed with its socket open — the
// remaining copy waits on a Read with no deadline, and the three goroutines,
// the stream and the fd behind it stay for the life of the session. On a
// phone that is hours. The grace is measured from the last byte that moved,
// never from the EOF, so a half-closed transfer that is still carrying data
// is left alone.
const HalfOpenGrace = 60 * time.Second

// ErrHalfOpenIdle reports that a copy was ended because its remaining
// direction carried nothing for HalfOpenGrace after the other had finished.
var ErrHalfOpenIdle = errors.New("half-open connection idle past its grace")

// copyBufPool serves the tunnel copy loops. 32 KiB matches io.Copy's own
// default and one smux frame, so a full frame moves per iteration.
var copyBufPool = sync.Pool{ //nolint:gochecknoglobals // shared per-connection copy buffers
	New: func() any {
		b := make([]byte, 32*1024)
		return &b
	},
}

// CopyCounts reports bytes copied in both directions.
type CopyCounts struct {
	LeftToRight uint64
	RightToLeft uint64
}

type copyResult struct {
	leftToRight bool
	bytes       uint64
	err         error
}

// CopyBidirectional copies until both directions finish or ctx is canceled.
// A clean EOF half-closes the destination when supported; errors close both
// sides, and so does a remaining direction that stays idle for HalfOpenGrace.
func CopyBidirectional(
	ctx context.Context,
	left io.ReadWriteCloser,
	right io.ReadWriteCloser,
) (CopyCounts, error) {
	return copyBidirectional(ctx, left, right, HalfOpenGrace)
}

func copyBidirectional(
	ctx context.Context,
	left io.ReadWriteCloser,
	right io.ReadWriteCloser,
	grace time.Duration,
) (CopyCounts, error) {
	var moved atomic.Uint64
	results := make(chan copyResult, 2)
	go copyOneWay(results, true, right, left, &moved)
	go copyOneWay(results, false, left, right, &moved)

	var counts CopyCounts
	closeBoth := func() {
		_ = left.Close()
		_ = right.Close()
	}
	// Every error is gathered before the counts are returned: drain fills
	// them in as the last goroutine reports.
	var err error
	select {
	case result := <-results:
		setCopyCount(&counts, result)
		if result.err != nil {
			closeBoth()
			err = errors.Join(result.err, drain(results, &counts))
			return counts, err
		}
		closeCopyWrite(result.leftToRight, left, right)
	case <-ctx.Done():
		closeBoth()
		err = errors.Join(drain(results, &counts), drain(results, &counts), ctx.Err())
		return counts, err
	}
	err = errors.Join(awaitHalfOpen(ctx, results, &counts, &moved, grace, closeBoth)...)
	return counts, err
}

// awaitHalfOpen waits for the second direction once the first has finished
// cleanly. Every grace period it checks whether any byte moved since the last
// check; a direction that stayed silent for a whole period is closed, so a
// half-open connection ends within two grace periods of its last byte.
func awaitHalfOpen(
	ctx context.Context,
	results <-chan copyResult,
	counts *CopyCounts,
	moved *atomic.Uint64,
	grace time.Duration,
	closeBoth func(),
) []error {
	timer := time.NewTimer(grace)
	defer timer.Stop()
	seen := moved.Load()
	for {
		select {
		case result := <-results:
			setCopyCount(counts, result)
			if result.err != nil {
				return []error{result.err}
			}
			return nil
		case <-ctx.Done():
			closeBoth()
			return []error{drain(results, counts), ctx.Err()}
		case <-timer.C:
			if now := moved.Load(); now != seen {
				seen = now
				timer.Reset(grace)
				continue
			}
			closeBoth()
			return []error{ErrHalfOpenIdle, drain(results, counts)}
		}
	}
}

// drain takes one result after both sides were closed, so the goroutine
// behind it is gone before the caller returns.
func drain(results <-chan copyResult, counts *CopyCounts) error {
	result := <-results
	setCopyCount(counts, result)
	return result.err
}

func copyOneWay(results chan<- copyResult, leftToRight bool, dst io.Writer, src io.Reader, moved *atomic.Uint64) {
	n, err := copyStream(countingWriter{dst, moved}, src)
	result := copyResult{leftToRight: leftToRight, err: err}
	if n > 0 {
		result.bytes = uint64(n)
	}
	results <- result
}

// countingWriter adds every byte written to the shared progress counter the
// half-open grace is measured against.
type countingWriter struct {
	io.Writer
	moved *atomic.Uint64
}

func (w countingWriter) Write(p []byte) (int, error) {
	n, err := w.Writer.Write(p)
	if n > 0 {
		w.moved.Add(uint64(n))
	}
	return n, err //nolint:wrapcheck // a transparent wrapper; callers classify this error themselves
}

// onlyReader hides a reader's WriteTo so io.CopyBuffer actually uses the
// buffer it was given.
type onlyReader struct{ io.Reader }

// copyStream moves src into dst with one pooled buffer, except when src is a
// smux stream: that one hands over its internal buffers through WriteTo, which
// is strictly better than copying through ours. Plain io.Copy would pick
// net.TCPConn's WriteTo instead, whose generic path allocates a fresh 32 KiB
// buffer for every tunneled connection.
func copyStream(dst io.Writer, src io.Reader) (int64, error) {
	if stream, ok := src.(*smux.Stream); ok {
		return io.Copy(dst, stream) //nolint:wrapcheck // callers classify this error themselves
	}
	bufPtr, ok := copyBufPool.Get().(*[]byte)
	if !ok {
		return io.Copy(dst, src) //nolint:wrapcheck // callers classify this error themselves
	}
	defer copyBufPool.Put(bufPtr)
	return io.CopyBuffer(dst, onlyReader{src}, *bufPtr) //nolint:wrapcheck // same
}

func setCopyCount(counts *CopyCounts, result copyResult) {
	if result.leftToRight {
		counts.LeftToRight = result.bytes
		return
	}
	counts.RightToLeft = result.bytes
}

func closeCopyWrite(leftToRight bool, left, right io.Closer) {
	dst := left
	if leftToRight {
		dst = right
	}
	if closer, ok := dst.(interface{ CloseWrite() error }); ok {
		_ = closer.CloseWrite()
		return
	}
	_ = dst.Close()
}
