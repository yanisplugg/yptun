//go:build linux && !android

package routemgr

import (
	"bytes"
	"fmt"
	"os"
	"os/exec"
	"strings"
)

func discoverGateway() (string, error) {
	// Если это стандартный линуксовый бинарь (GOOS=linux), но запущенный под Android
	// (в Termux или как переименованный JNI-воркер), отключаем routemgr.
	if os.Getenv("ANDROID_DATA") != "" || os.Getenv("ANDROID_ROOT") != "" {
		return "", nil
	}

	cmd := exec.Command("ip", "-o", "-4", "route", "show", "to", "default") //nolint:gosec,noctx
	out, err := cmd.Output()
	if err != nil {
		return "", fmt.Errorf("failed to get default route: %w", err)
	}

	// Пример: default via X.X.X.X dev eth0 proto dhcp metric 100
	fields := strings.Fields(string(out))
	for i, field := range fields {
		if field == "via" && i+1 < len(fields) {
			return fields[i+1], nil
		}
	}

	return "", fmt.Errorf("default gateway not found")
}

func addRoute(ip, gateway string) error {
	cmd := exec.Command("ip", "route", "replace", fmt.Sprintf("%s/32", ip), "via", gateway) //nolint:gosec,noctx
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("failed to add route for %s via %s: %w", ip, gateway, err)
	}
	return nil
}

func delRoute(ip string) error {
	cmd := exec.Command("ip", "route", "del", fmt.Sprintf("%s/32", ip)) //nolint:gosec,noctx
	out, err := cmd.CombinedOutput()
	if err != nil {
		if bytes.Contains(out, []byte("No such process")) {
			return nil // Маршрут не найден, всё ок
		}
		return fmt.Errorf("failed to delete route for %s: %w", ip, err)
	}
	return nil
}
