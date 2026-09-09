package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
)

// runCLI handles `--install <extension-id>` / `--uninstall` / `--version`. Registration is the
// standard Chrome native-messaging manifest: a JSON file next to the binary, pointed at by a
// registry value on Windows and by a well-known directory elsewhere.
func runCLI(args []string) error {
	switch args[0] {
	case "--version", "-v":
		fmt.Println("yptunhost", hostVersion)
		return nil
	case "--install":
		if len(args) < 2 || !strings.HasPrefix(args[1], "chrome-extension://") && len(args[1]) != 32 {
			return errors.New("usage: yptunhost --install <extension-id>  (copy it from the extension popup)")
		}
		return install(strings.TrimPrefix(strings.TrimSuffix(args[1], "/"), "chrome-extension://"))
	case "--uninstall":
		return uninstall()
	}
	return fmt.Errorf("unknown option %s (--install <id> | --uninstall | --version)", args[0])
}

const hostVersion = "3.4.2"

func manifestPath() (string, error) {
	exe, err := os.Executable()
	if err != nil {
		return "", err
	}
	return filepath.Join(filepath.Dir(exe), hostName+".json"), nil
}

func install(extensionID string) error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	manifest := map[string]any{
		"name":            hostName,
		"description":     "YPtun VPN native host (VLESS / AmneziaWG -> local SOCKS5)",
		"path":            exe,
		"type":            "stdio",
		"allowed_origins": []string{"chrome-extension://" + extensionID + "/"},
	}
	body, _ := json.MarshalIndent(manifest, "", "  ")

	path, err := manifestPath()
	if err != nil {
		return err
	}
	if err := os.WriteFile(path, body, 0o644); err != nil {
		return err
	}

	for _, dir := range browserDirs() {
		if runtime.GOOS == "windows" {
			// ponytail: shelling out to reg.exe beats pulling in x/sys/windows/registry + a
			// _windows.go build-tag split for two key writes.
			_ = exec.Command("reg", "add", dir, "/ve", "/t", "REG_SZ", "/d", path, "/f").Run()
			continue
		}
		if err := os.MkdirAll(dir, 0o755); err != nil {
			continue
		}
		_ = os.WriteFile(filepath.Join(dir, hostName+".json"), body, 0o644)
	}
	fmt.Println("registered", hostName, "->", exe)
	fmt.Println("allowed extension:", extensionID)
	return nil
}

func uninstall() error {
	for _, dir := range browserDirs() {
		if runtime.GOOS == "windows" {
			_ = exec.Command("reg", "delete", dir, "/f").Run()
			continue
		}
		_ = os.Remove(filepath.Join(dir, hostName+".json"))
	}
	if path, err := manifestPath(); err == nil {
		_ = os.Remove(path)
	}
	fmt.Println("unregistered", hostName)
	return nil
}

// browserDirs lists the per-user native-messaging locations of the Chromium browsers we support:
// registry keys on Windows, directories elsewhere.
func browserDirs() []string {
	if runtime.GOOS == "windows" {
		return []string{
			`HKCU\Software\Google\Chrome\NativeMessagingHosts\` + hostName,
			`HKCU\Software\Chromium\NativeMessagingHosts\` + hostName,
			`HKCU\Software\Microsoft\Edge\NativeMessagingHosts\` + hostName,
			`HKCU\Software\Yandex\YandexBrowser\NativeMessagingHosts\` + hostName,
		}
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return nil
	}
	if runtime.GOOS == "darwin" {
		base := filepath.Join(home, "Library", "Application Support")
		return []string{
			filepath.Join(base, "Google", "Chrome", "NativeMessagingHosts"),
			filepath.Join(base, "Chromium", "NativeMessagingHosts"),
			filepath.Join(base, "Microsoft Edge", "NativeMessagingHosts"),
		}
	}
	base := filepath.Join(home, ".config")
	return []string{
		filepath.Join(base, "google-chrome", "NativeMessagingHosts"),
		filepath.Join(base, "chromium", "NativeMessagingHosts"),
		filepath.Join(base, "microsoft-edge", "NativeMessagingHosts"),
	}
}
