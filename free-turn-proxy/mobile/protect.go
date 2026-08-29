package mobile

import (
	"syscall"

	"github.com/samosvalishe/free-turn-proxy/internal/netctl"
)

// Protector реализуется хостом для исключения сокетов клиента из VPN-туннеля (VpnService.protect).
// Без этого TURN / VK API / DNS трафик заворачивается обратно в туннель.
type Protector interface {
	Protect(fd int) bool
}

// SetProtect устанавливает обработчик защиты сокетов хоста (nil - no-op).
func SetProtect(p Protector) {
	if p == nil {
		netctl.SetControl(nil)
		return
	}
	netctl.SetControl(func(_, _ string, c syscall.RawConn) error {
		return c.Control(func(fd uintptr) { p.Protect(int(fd)) })
	})
}
