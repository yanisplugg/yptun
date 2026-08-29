//go:build !(linux && 386)

// Package ish предоставляет адаптер TCP listener для эмулятора iSH на iOS.
package ish

import "net"

func WrapListener(ln net.Listener) (net.Listener, error) {
	return ln, nil
}
