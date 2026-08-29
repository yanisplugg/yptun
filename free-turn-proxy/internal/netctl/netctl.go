// Package netctl предоставляет глобальный Control-хук для сокетов (VpnService.protect).
package netctl

import "syscall"

var control func(network, address string, c syscall.RawConn) error

// SetControl регистрирует функцию защиты сокетов хоста (nil - no-op).
func SetControl(fn func(network, address string, c syscall.RawConn) error) {
	control = fn
}

// Apply вызывается из net.Dialer и net.ListenConfig для защиты создаваемых сокетов.
func Apply(network, address string, c syscall.RawConn) error {
	if control != nil {
		return control(network, address, c)
	}
	return nil
}
