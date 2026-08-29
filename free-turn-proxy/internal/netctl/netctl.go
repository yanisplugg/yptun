// Package netctl предоставляет глобальный Control-хук для сокетов (VpnService.protect).
//
// ЛОКАЛЬНЫЙ ПАТЧ: у upstream этого пакета НЕТ. Хост ставит хук, чтобы исходящие
// сокеты клиента (netconn.DirectNet, dnsdial, turndial) шли МИМО туннеля - иначе
// собственный TURN/VK/DNS-трафик заворачивается сам в себя и всё виснет. Не терять
// при ре-вендоре: нужны и сам пакет, и вызовы Apply в диалерах.
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
