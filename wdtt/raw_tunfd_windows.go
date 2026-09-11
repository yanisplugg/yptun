//go:build windows

package wdtt

import (
	"context"
	"errors"
	"io"
)

// Raw straight into a host TUN is Android-only (see raw_tunfd_unix.go); Windows uses the SOCKS form.

func AttachTunFD(fd int) {}

func dropStaleTunFD() {}

func waitHostTun(ctx context.Context) (io.ReadWriteCloser, error) {
	return nil, errors.New("host TUN fd is not supported on Windows")
}
