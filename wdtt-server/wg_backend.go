package main

import (
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
)

type wgDevice interface {
	IpcSet(string) error
	Close()
}

type kernelWGDevice struct {
	iface string
	mu    sync.Mutex
}

func (d *kernelWGDevice) Close() {
	if d == nil || d.iface == "" {
		return
	}
	_, _ = runCmd("ip", "link", "del", d.iface)
}

func (d *kernelWGDevice) IpcSet(configuration string) error {
	if d == nil || d.iface == "" {
		return errors.New("kernel WireGuard is not initialized")
	}
	d.mu.Lock()
	defer d.mu.Unlock()

	values := make(map[string]string)
	for _, line := range strings.Split(configuration, "\n") {
		key, value, ok := strings.Cut(strings.TrimSpace(line), "=")
		if ok {
			values[key] = value
		}
	}
	publicHex := strings.TrimSpace(values["public_key"])
	if publicHex == "" {
		return errors.New("kernel WireGuard peer update has no public key")
	}
	publicKey, err := wireGuardHexToBase64(publicHex)
	if err != nil {
		return fmt.Errorf("kernel WireGuard public key: %w", err)
	}
	args := []string{"set", d.iface, "peer", publicKey}
	if values["remove"] == "true" {
		args = append(args, "remove")
	} else if allowedIP := strings.TrimSpace(values["allowed_ip"]); allowedIP != "" {
		args = append(args, "allowed-ips", allowedIP)
	} else {
		return errors.New("kernel WireGuard peer update has no operation")
	}
	if output, err := runCmd("wg", args...); err != nil {
		return fmt.Errorf("wg peer update: %s", output)
	}
	return nil
}

func wireGuardHexToBase64(value string) (string, error) {
	raw, err := hex.DecodeString(value)
	if err != nil {
		return "", err
	}
	if len(raw) != 32 {
		return "", fmt.Errorf("key length %d != 32", len(raw))
	}
	return base64.StdEncoding.EncodeToString(raw), nil
}

func startKernelWG(keys *wgKeys, wgPort int, configDir string) (wgDevice, error) {
	if !commandExists("wg") || !commandExists("ip") {
		return nil, errors.New("commands ip/wg are not installed")
	}
	_, _ = runCmd("ip", "link", "del", wgIfaceName)
	if output, err := runCmd("ip", "link", "add", wgIfaceName, "type", "wireguard"); err != nil {
		return nil, fmt.Errorf("kernel WireGuard unavailable: %s", output)
	}
	dev := &kernelWGDevice{iface: wgIfaceName}
	ok := false
	defer func() {
		if !ok {
			dev.Close()
		}
	}()

	runtimeDir := filepath.Join(configDir, ".runtime")
	if err := os.MkdirAll(runtimeDir, 0700); err != nil {
		return nil, fmt.Errorf("create WireGuard runtime directory: %w", err)
	}
	keyFile, err := os.CreateTemp(runtimeDir, "wg-private-*")
	if err != nil {
		return nil, fmt.Errorf("create WireGuard key file: %w", err)
	}
	keyPath := keyFile.Name()
	defer os.Remove(keyPath)
	if err := keyFile.Chmod(0600); err != nil {
		keyFile.Close()
		return nil, err
	}
	if _, err := keyFile.WriteString(keys.serverPrivate + "\n"); err != nil {
		keyFile.Close()
		return nil, err
	}
	if err := keyFile.Close(); err != nil {
		return nil, err
	}
	if output, err := runCmd(
		"wg", "set", wgIfaceName,
		"private-key", keyPath,
		"listen-port", strconv.Itoa(wgPort),
	); err != nil {
		return nil, fmt.Errorf("configure kernel WireGuard: %s", output)
	}
	if err := configureInterface(wgIfaceName); err != nil {
		return nil, err
	}
	if err := setupFullConeNAT(wgIfaceName); err != nil {
		return nil, err
	}
	ok = true
	logWGBackend("kernel")
	return dev, nil
}

func startWGBackend(mode string, keys *wgKeys, wgPort int, configDir string) (wgDevice, error) {
	mode = strings.ToLower(strings.TrimSpace(mode))
	if mode == "" {
		mode = "auto"
	}
	switch mode {
	case "kernel":
		return startKernelWG(keys, wgPort, configDir)
	case "userspace":
		dev, err := startUserspaceWG(keys, wgPort)
		if err == nil {
			logWGBackend("userspace")
		}
		return dev, err
	case "auto":
		if dev, err := startKernelWG(keys, wgPort, configDir); err == nil {
			return dev, nil
		} else {
			fmt.Printf("[WG] Kernel backend недоступен, использую userspace: %v\n", err)
		}
		dev, err := startUserspaceWG(keys, wgPort)
		if err == nil {
			logWGBackend("userspace")
		}
		return dev, err
	default:
		return nil, fmt.Errorf("unknown WireGuard backend %q", mode)
	}
}

var activeWGBackend atomicString

type atomicString struct {
	mu    sync.RWMutex
	value string
}

func (s *atomicString) Store(value string) {
	s.mu.Lock()
	s.value = value
	s.mu.Unlock()
}

func (s *atomicString) Load() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.value
}

func logWGBackend(name string) {
	activeWGBackend.Store(name)
	fmt.Printf("[WG] Backend: %s\n", name)
}
