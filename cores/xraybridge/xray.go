// Package xraybridge is a minimal gomobile-friendly wrapper around xray-core, so the app can
// run Xray as a userspace proxy (SOCKS inbound + outbound) alongside sing-box and olcrtc in a
// single shared Go runtime. Android socket protection is done via RegisterDialerController,
// mirroring how v2rayNG keeps Xray traffic off the VPN tun.
package xraybridge

import (
	"bytes"
	"errors"
	"os"
	"sync"
	"syscall"

	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"
	_ "github.com/xtls/xray-core/main/distro/all" // register all protocols/transports (vless, xhttp, reality, ...)
	"github.com/xtls/xray-core/transport/internet"
)

// Protector protects a socket fd from the VPN (implemented in Kotlin via VpnService.protect).
type Protector interface {
	Protect(fd int) bool
}

var (
	mu       sync.Mutex
	instance *core.Instance
)

// SetProtector installs a dialer controller that protects every outbound socket Xray opens.
// Must be called before Start. Passing nil clears it.
func SetProtector(p Protector) {
	if p == nil {
		internet.RegisterDialerController(nil)
		return
	}
	internet.RegisterDialerController(func(network, address string, conn syscall.RawConn) error {
		return conn.Control(func(fd uintptr) {
			p.Protect(int(fd))
		})
	})
}

// SetAssetPath points xray-core at the directory holding geoip.dat / geosite.dat so geosite:/geoip:
// routing selectors resolve. xray reads the "xray.location.asset" env flag when loading geodata, so
// this must be called before Start (it is process-global; harmless to set repeatedly). Empty clears it.
func SetAssetPath(dir string) {
	if dir == "" {
		_ = os.Unsetenv("xray.location.asset")
		return
	}
	_ = os.Setenv("xray.location.asset", dir)
}

// Start launches Xray with the given JSON config. Returns an error if already running or invalid.
func Start(configJSON string) error {
	mu.Lock()
	defer mu.Unlock()
	if instance != nil {
		return errors.New("xray already running")
	}
	config, err := serial.LoadJSONConfig(bytes.NewReader([]byte(configJSON)))
	if err != nil {
		return err
	}
	inst, err := core.New(config)
	if err != nil {
		return err
	}
	if err := inst.Start(); err != nil {
		return err
	}
	instance = inst
	return nil
}

// Stop gracefully stops Xray.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	if instance != nil {
		_ = instance.Close()
		instance = nil
	}
}

// IsRunning reports whether an Xray instance is active.
func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return instance != nil
}
