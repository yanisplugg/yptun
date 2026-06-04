// Package xraybridge is a minimal gomobile-friendly wrapper around xray-core, so the app can
// run Xray as a userspace proxy (SOCKS inbound + outbound) alongside sing-box and olcrtc in a
// single shared Go runtime. Android socket protection is done via RegisterDialerController,
// mirroring how v2rayNG keeps Xray traffic off the VPN tun.
package xraybridge

import (
	"bytes"
	"context"
	"errors"
	"net"
	"net/http"
	"os"
	"sync"
	"syscall"
	"time"

	xnet "github.com/xtls/xray-core/common/net"
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

// Version returns the embedded xray-core version (e.g. "25.3.27"), for display in the app.
func Version() string {
	return core.Version()
}

// MeasureDelay spins up a THROWAWAY xray instance from [configJSON], fetches [url] (with HTTP
// [method], "GET"/"HEAD") THROUGH that instance's proxy outbound, and returns the round-trip in
// milliseconds — or -1 on any failure. This is the per-server "real delay" probe (à la v2rayNG /
// Happ): it needs no system VPN/TUN and is independent of the main running instance, so the server
// list can be pinged through the proxy while disconnected.
func MeasureDelay(configJSON, url, method string, timeoutMs int) int64 {
	config, err := serial.LoadJSONConfig(bytes.NewReader([]byte(configJSON)))
	if err != nil {
		return -1
	}
	inst, err := core.New(config)
	if err != nil {
		return -1
	}
	if err := inst.Start(); err != nil {
		return -1
	}
	defer inst.Close()

	timeout := time.Duration(timeoutMs) * time.Millisecond
	if timeout <= 0 {
		timeout = 10 * time.Second
	}
	client := &http.Client{
		Timeout: timeout,
		Transport: &http.Transport{
			DisableKeepAlives: true,
			// Dial every connection THROUGH the test instance (its default/proxy outbound).
			DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				dest, derr := xnet.ParseDestination(network + ":" + addr)
				if derr != nil {
					return nil, derr
				}
				return core.Dial(ctx, inst, dest)
			},
		},
	}
	if method == "" {
		method = "HEAD"
	}
	req, err := http.NewRequest(method, url, nil)
	if err != nil {
		return -1
	}
	req.Header.Set("User-Agent", "olcbox-ping")
	start := time.Now()
	resp, err := client.Do(req)
	if err != nil {
		return -1
	}
	_ = resp.Body.Close()
	return time.Since(start).Milliseconds()
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
