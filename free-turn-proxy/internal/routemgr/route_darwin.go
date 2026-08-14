//go:build darwin

package routemgr

import (
	"bufio"
	"bytes"
	"fmt"
	"os/exec"
	"strings"
)

func discoverGateway() (string, error) {
	cmd := exec.Command("route", "-n", "get", "default") //nolint:gosec,noctx
	out, err := cmd.Output()
	if err != nil {
		return "", fmt.Errorf("failed to run route get default: %w", err)
	}

	scanner := bufio.NewScanner(bytes.NewReader(out))
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if strings.HasPrefix(line, "gateway:") {
			fields := strings.Fields(line)
			if len(fields) >= 2 {
				return fields[1], nil
			}
		}
	}
	if err := scanner.Err(); err != nil {
		return "", fmt.Errorf("error parsing route output: %w", err)
	}

	return "", fmt.Errorf("default gateway not found")
}

func addRoute(ip, gateway string) error {
	// Пытаемся удалить перед добавлением, игнорируем ошибку
	_ = delRoute(ip)

	cmd := exec.Command("route", "-n", "add", "-host", ip, gateway) //nolint:gosec,noctx
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("failed to add route for %s via %s: %w", ip, gateway, err)
	}
	return nil
}

func delRoute(ip string) error {
	cmd := exec.Command("route", "-n", "delete", "-host", ip) //nolint:gosec,noctx
	out, err := cmd.CombinedOutput()
	if err != nil {
		if bytes.Contains(out, []byte("not in table")) {
			return nil // Маршрут не существует
		}
		return fmt.Errorf("failed to delete route for %s: %w", ip, err)
	}
	return nil
}
