// Package netctl предоставляет глобальный Control-хук для сокетов (VpnService.protect).
//
// БЫЛ ЛОКАЛЬНЫМ ПАТЧЕМ: upstream ПРИНЯЛ пакет к себе и с 3.4.0 держит хук в
// atomic.Pointer (гонки при переустановке хука на живом клиенте). Сам файл теперь
// апстримовский - брать его версию при ре-вендоре. Локальным остаётся только то,
// ради чего пакет заводился: вызовы netctl.Apply в диалерах, чтобы исходящие сокеты
// клиента (netconn.DirectNet, dnsdial, turndial) шли МИМО туннеля - иначе собственный
// TURN/VK/DNS-трафик заворачивается сам в себя и всё виснет. ИХ не терять.
package netctl

import (
	"sync/atomic"
	"syscall"
)

type ControlFunc func(network, address string, c syscall.RawConn) error

var control atomic.Pointer[ControlFunc]

// SetControl регистрирует функцию защиты сокетов хоста (nil - no-op).
func SetControl(fn ControlFunc) {
	if fn == nil {
		control.Store(nil)
		return
	}
	control.Store(&fn)
}

// Apply вызывается из net.Dialer и net.ListenConfig для защиты создаваемых сокетов.
func Apply(network, address string, c syscall.RawConn) error {
	if fn := control.Load(); fn != nil {
		return (*fn)(network, address, c)
	}
	return nil
}
