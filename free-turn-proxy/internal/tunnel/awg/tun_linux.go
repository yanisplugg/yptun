//go:build linux

package awg

import (
	"fmt"
	"os"
	"syscall"

	"github.com/amnezia-vpn/amneziawg-go/v3/tun"
)

// openTUN оборачивает готовый дескриптор tun-интерфейса. Создаёт его платформа
// (на Android - VpnService.Builder.establish), потому что для этого нужны её
// разрешения и настройки маршрутизации.
//
// Unmonitored - единственный вариант для приложения без CAP_NET_ADMIN:
// CreateTUNFromFile поднимает netlink-сокет, читает ifindex и выставляет MTU
// ioctl'ом, и на Android всё это падает с EPERM. Имя, MTU и маршруты уже
// проставила платформа, следить за ними изнутри незачем.
//
// Дескриптор переходит во владение openTUN, включая её собственную неудачу:
// вызывающий не закрывает fd ни при каком исходе.
func openTUN(fd int) (tun.Device, error) {
	// SetNonblock - единственный шаг upstream до os.NewFile; дальше fd владеет
	// файл с финалайзером, и закрытие снаружи попадёт вторым Close уже в чужой
	// переиспользованный номер.
	if err := syscall.SetNonblock(fd, true); err != nil {
		CloseTUNFD(fd)
		return nil, fmt.Errorf("awg: set tun nonblock: %w", err)
	}
	dev, _, err := tun.CreateUnmonitoredTUNFromFD(fd)
	if err != nil {
		return nil, fmt.Errorf("awg: create tun: %w", err)
	}
	return dev, nil
}

// CloseTUNFD закрывает дескриптор, не дошедший до openTUN: владение переходит к
// Backend с момента Up, включая неудачную попытку, а до Up его закрывает
// вызывающий.
func CloseTUNFD(fd int) {
	_ = os.NewFile(uintptr(fd), "tun").Close()
}
