//go:build !windows

package wdtt

// YPtun: Raw straight into the host's TUN (Android). The host establishes its VpnService TUN from the
// RAWCONF address, then hands over a dup of the fd with AttachTunFD — upstream's rawtun, minus its unix
// socket (the core is in-process here, so a plain fd is enough).

import (
	"context"
	"io"
	"os"
	"syscall"
)

// One pending fd: a newer one replaces (and closes) a stale one nobody picked up.
var tunFdCh = make(chan int, 1)

// AttachTunFD hands a TUN fd to the Raw run waiting for it. The core owns the fd from here on and closes
// it when the run ends.
func AttachTunFD(fd int) {
	for {
		select {
		case tunFdCh <- fd:
			return
		default:
		}
		select {
		case old := <-tunFdCh:
			_ = syscall.Close(old)
		default:
		}
	}
}

// dropStaleTunFD closes an fd left over from a run that never took it.
func dropStaleTunFD() {
	select {
	case fd := <-tunFdCh:
		_ = syscall.Close(fd)
	default:
	}
}

// waitHostTun blocks until the host attaches its TUN or ctx ends. The fd is switched to non-blocking so
// the returned file is served by the runtime poller: closing it on shutdown wakes a pending Read instead
// of leaving a goroutine stuck on the fd — and with it the VPN interface alive.
func waitHostTun(ctx context.Context) (io.ReadWriteCloser, error) {
	select {
	case fd := <-tunFdCh:
		if err := syscall.SetNonblock(fd, true); err != nil {
			_ = syscall.Close(fd)
			return nil, err
		}
		return ipv4Only{os.NewFile(uintptr(fd), "tun")}, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}
