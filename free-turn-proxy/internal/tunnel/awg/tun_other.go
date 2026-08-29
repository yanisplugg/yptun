//go:build !linux

package awg

import (
	"errors"

	"github.com/amnezia-vpn/amneziawg-go/v3/tun"
)

// openTUN доступен только там, где tun создаётся из готового дескриптора -
// это Linux и Android. На остальных платформах пакет собирается (чтобы тесты
// конфига и статистики шли везде), но туннель не поднимается.
//
// Дескриптор переходит во владение openTUN, включая её неудачу (см. tun_linux.go);
// закрывать здесь нечего - настоящий fd сюда не попадает.
func openTUN(int) (tun.Device, error) {
	return nil, errors.New("awg: tun from fd is supported on linux/android only")
}

// CloseTUNFD - no-op: сюда дескриптор попасть не может.
func CloseTUNFD(int) {}
