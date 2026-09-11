package wdtt

import (
	"fmt"
	"net"
	"strconv"
	"strings"
	"time"
)

// RequestConfig запрашивает WireGuard конфиг через DTLS-соединение.
func RequestConfig(conn net.Conn, localPort, deviceID, password string) (string, error) {
	payload := fmt.Sprintf("GETCONF:%s|%s|%s", localPort, deviceID, password)
	if _, err := conn.Write([]byte(payload)); err != nil {
		return "", fmt.Errorf("отправка GETCONF: %w", err)
	}

	b := make([]byte, 4096)
	if err := conn.SetReadDeadline(time.Now().Add(45 * time.Second)); err != nil {
		return "", fmt.Errorf("установка дедлайна: %w", err)
	}
	n, err := conn.Read(b)
	_ = conn.SetReadDeadline(time.Time{})
	if err != nil {
		return "", fmt.Errorf("чтение ответа конфига: %w", err)
	}

	resp := string(b[:n])
	if resp == "NOCONF" {
		return "", nil
	}

	if strings.HasPrefix(resp, "DENIED:") {
		reason := strings.TrimPrefix(resp, "DENIED:")
		switch reason {
		case "wrong_password":
			return "", fmt.Errorf("FATAL_AUTH: неверный пароль подключения")
		case "expired":
			return "", fmt.Errorf("FATAL_AUTH: срок действия пароля истёк")
		case "device_mismatch":
			return "", fmt.Errorf("FATAL_AUTH: пароль привязан к другому устройству")
		default:
			return "", fmt.Errorf("FATAL_AUTH: доступ запрещён (%s)", reason)
		}
	}

	return resp, nil
}

// SendAuth отправляет команду авторизации, чтобы сервер мог связать соединение с устройством
func SendAuth(conn net.Conn, deviceID, password string) error {
	payload := fmt.Sprintf("AUTH:%s|%s", deviceID, password)
	if _, err := conn.Write([]byte(payload)); err != nil {
		return fmt.Errorf("отправка AUTH: %w", err)
	}

	return nil
}

// RequestRawConfig запрашивает у сервера конфигурацию для raw-IP режима
// (без WireGuard) — сервер отвечает "RAWCONF:ip|dns|mtu" (см. server/raw.go
// handleConnRaw). ip пусто на первый вызов, если сервер ещё не назначил его.
func RequestRawConfig(conn net.Conn, deviceID, password string) (ip, dnsCSV string, mtu int, err error) {
	payload := fmt.Sprintf("GETCONF_RAW:%s|%s", deviceID, password)
	if _, err = conn.Write([]byte(payload)); err != nil {
		return "", "", 0, fmt.Errorf("отправка GETCONF_RAW: %w", err)
	}

	b := make([]byte, 4096)
	if err = conn.SetReadDeadline(time.Now().Add(45 * time.Second)); err != nil {
		return "", "", 0, fmt.Errorf("установка дедлайна: %w", err)
	}
	n, readErr := conn.Read(b)
	_ = conn.SetReadDeadline(time.Time{})
	if readErr != nil {
		return "", "", 0, fmt.Errorf("чтение ответа RAWCONF: %w", readErr)
	}

	resp := string(b[:n])
	if resp == "NOCONF" {
		return "", "", 0, nil
	}
	if strings.HasPrefix(resp, "DENIED:") {
		reason := strings.TrimPrefix(resp, "DENIED:")
		switch reason {
		case "wrong_password":
			return "", "", 0, fmt.Errorf("FATAL_AUTH: неверный пароль подключения")
		case "expired":
			return "", "", 0, fmt.Errorf("FATAL_AUTH: срок действия пароля истёк")
		case "device_mismatch":
			return "", "", 0, fmt.Errorf("FATAL_AUTH: пароль привязан к другому устройству")
		default:
			return "", "", 0, fmt.Errorf("FATAL_AUTH: доступ запрещён (%s)", reason)
		}
	}
	if !strings.HasPrefix(resp, "RAWCONF:") {
		return "", "", 0, fmt.Errorf("неожиданный ответ RAWCONF: %q", resp)
	}

	parts := strings.Split(strings.TrimPrefix(resp, "RAWCONF:"), "|")
	if len(parts) != 3 {
		return "", "", 0, fmt.Errorf("некорректный формат RAWCONF: %q", resp)
	}
	mtuVal, convErr := strconv.Atoi(strings.TrimSpace(parts[2]))
	if convErr != nil {
		return "", "", 0, fmt.Errorf("некорректный MTU в RAWCONF: %q", parts[2])
	}
	return strings.TrimSpace(parts[0]), strings.TrimSpace(parts[1]), mtuVal, nil
}
