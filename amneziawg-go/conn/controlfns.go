/* SPDX-License-Identifier: MIT
 *
 * Copyright (C) 2017-2025 WireGuard LLC. All Rights Reserved.
 */

package conn

import (
	"net"
	"sync"
	"syscall"
)

// UDP socket read/write buffer size (7MB). The value of 7MB is chosen as it is
// the max supported by a default configuration of macOS. Some platforms will
// silently clamp the value to other maximums, such as linux clamping to
// net.core.{r,w}mem_max (see _linux.go for additional implementation that works
// around this limitation)
const socketBufferSize = 7 << 20

// controlFn is the callback function signature from net.ListenConfig.Control.
// It is used to apply platform specific configuration to the socket prior to
// bind.
type controlFn func(network, address string, c syscall.RawConn) error

// controlFns is a list of functions that are called from the listen config
// that can apply socket options.
var controlFns = []controlFn{}

var (
	socketProtectorMu sync.RWMutex
	socketProtector   func(fd int) bool
)

// SetSocketProtector registers a socket protection function (e.g. Android VpnService.protect)
// that will be called in ListenConfig.Control on every newly created socket before bind.
func SetSocketProtector(fn func(fd int) bool) {
	socketProtectorMu.Lock()
	socketProtector = fn
	socketProtectorMu.Unlock()
}

func init() {
	controlFns = append(controlFns, func(network, address string, c syscall.RawConn) error {
		socketProtectorMu.RLock()
		fn := socketProtector
		socketProtectorMu.RUnlock()
		if fn == nil {
			return nil
		}
		return c.Control(func(fd uintptr) {
			fn(int(fd))
		})
	})
}

// listenConfig returns a net.ListenConfig that applies the controlFns to the
// socket prior to bind. This is used to apply socket buffer sizing and packet
// information OOB configuration for sticky sockets.
func listenConfig() *net.ListenConfig {
	return &net.ListenConfig{
		Control: func(network, address string, c syscall.RawConn) error {
			for _, fn := range controlFns {
				if err := fn(network, address, c); err != nil {
					return err
				}
			}
			return nil
		},
	}
}
