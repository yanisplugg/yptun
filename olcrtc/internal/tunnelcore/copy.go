package tunnelcore

import (
	"context"
	"errors"
	"io"
	"sync"

	"github.com/xtaci/smux"
)

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
// A clean EOF half-closes the destination when supported; errors close both sides.
func CopyBidirectional(
	ctx context.Context,
	left io.ReadWriteCloser,
	right io.ReadWriteCloser,
) (CopyCounts, error) {
	results := make(chan copyResult, 2)
	go copyOneWay(results, true, right, left)
	go copyOneWay(results, false, left, right)

	var counts CopyCounts
	var errs []error
	for completed := 0; completed < 2; completed++ {
		select {
		case result := <-results:
			setCopyCount(&counts, result)
			if result.err != nil {
				errs = append(errs, result.err)
				_ = left.Close()
				_ = right.Close()
				continue
			}
			closeCopyWrite(result.leftToRight, left, right)
		case <-ctx.Done():
			_ = left.Close()
			_ = right.Close()
			for ; completed < 2; completed++ {
				result := <-results
				setCopyCount(&counts, result)
				if result.err != nil {
					errs = append(errs, result.err)
				}
			}
			return counts, errors.Join(append(errs, ctx.Err())...)
		}
	}
	return counts, errors.Join(errs...)
}

func copyOneWay(results chan<- copyResult, leftToRight bool, dst io.Writer, src io.Reader) {
	n, err := copyStream(dst, src)
	result := copyResult{leftToRight: leftToRight, err: err}
	if n > 0 {
		result.bytes = uint64(n)
	}
	results <- result
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
