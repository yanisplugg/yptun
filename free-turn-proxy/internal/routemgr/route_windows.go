//go:build windows

package routemgr

import (
	"bufio"
	"bytes"
	"fmt"
	"os/exec"
	"strings"
)

func discoverGateway() (string, error) {
	cmd := exec.Command("cmd", "/c", "route", "print", "0.0.0.0") //nolint:gosec,noctx
	out, err := cmd.Output()
	if err != nil {
		return "", fmt.Errorf("failed to run route print: %w", err)
	}

	scanner := bufio.NewScanner(bytes.NewReader(out))
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "=") || strings.HasPrefix(line, "Network") || strings.HasPrefix(line, "Active") {
			continue
		}

		fields := strings.Fields(line)
		// Ожидаем строку: 0.0.0.0 0.0.0.0 <Gateway> ...
		if len(fields) >= 3 && fields[0] == "0.0.0.0" && fields[1] == "0.0.0.0" {
			return fields[2], nil
		}
	}
	if err := scanner.Err(); err != nil {
		return "", fmt.Errorf("error parsing route print output: %w", err)
	}

	return "", fmt.Errorf("default gateway not found")
}

func addRoute(ip, gateway string) error {
	cmd := exec.Command("cmd", "/c", "route", "add", ip, "mask", "255.255.255.255", gateway) //nolint:gosec,noctx
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("failed to add route for %s via %s: %w", ip, gateway, err)
	}
	return nil
}

func delRoute(ip string) error {
	cmd := exec.Command("cmd", "/c", "route", "delete", ip) //nolint:gosec,noctx
	// Игнорируем ошибки (например, если маршрут уже удалён)
	_ = cmd.Run()
	return nil
}
