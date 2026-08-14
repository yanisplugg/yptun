//go:build linux

package awg

import (
	"fmt"
	"os"

	"github.com/amnezia-vpn/amneziawg-go/tun"
)

// openTUN оборачивает готовый дескриптор tun-интерфейса. Создаёт его платформа
// (на Android - VpnService.Builder.establish), потому что для этого нужны её
// разрешения и настройки маршрутизации.
//
// os.NewFile забирает дескриптор во владение: закрытие устройства закроет и его.
func openTUN(fd, mtu int) (tun.Device, error) {
	file := os.NewFile(uintptr(fd), "tun")
	if file == nil {
		return nil, fmt.Errorf("awg: bad tun fd %d", fd)
	}
	dev, err := tun.CreateTUNFromFile(file, mtu)
	if err != nil {
		return nil, fmt.Errorf("awg: create tun: %w", err)
	}
	return dev, nil
}
