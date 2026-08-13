//go:build !linux

package awg

import (
	"errors"

	"github.com/amnezia-vpn/amneziawg-go/tun"
)

// openTUN доступен только там, где tun создаётся из готового дескриптора -
// это Linux и Android. На остальных платформах пакет собирается (чтобы тесты
// конфига и статистики шли везде), но туннель не поднимается.
func openTUN(int, int) (tun.Device, error) {
	return nil, errors.New("awg: tun from fd is supported on linux/android only")
}
