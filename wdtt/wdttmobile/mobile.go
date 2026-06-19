// Package wdttmobile is the gomobile-bound surface for the WDTT VK-TURN core. It
// is kept deliberately tiny (Start/Stop/IsRunning/PushCaptcha + a ConfigSink
// callback) so gomobile binds only this clean API, not the whole wdtt internals.
// The package name is distinct from olcrtc's `mobile` so the generated Java
// classes don't collide (this becomes Wdttmobile, that stays Mobile).
package wdttmobile

import (
	"context"
	"sync"

	core "wg-turn-client"
)

// ConfigSink receives the WireGuard config the wdtt-server hands back (GETCONF),
// already MTU-normalised. Implemented on the Kotlin side and passed to Start; the
// host parses it and brings up the WireGuard tunnel.
type ConfigSink interface {
	OnConfig(wgConf string)
}

var (
	mu     sync.Mutex
	cancel context.CancelFunc
)

// Start launches the WDTT core in the background and returns immediately. peer is
// the wdtt-server "host:port", vkHashes the VK call hashes (comma/space/newline
// separated), password the tunnel password. listen is the local UDP address
// WireGuard dials (empty → 127.0.0.1:9000). numWorkers is clamped/rounded inside.
// Any previous run is stopped first. The WireGuard config arrives via
// sink.OnConfig once the first worker has fetched it.
func Start(peer, vkHashes, password, listen string, numWorkers int, deviceID, fingerprint, clientIDs string, sink ConfigSink) {
	mu.Lock()
	defer mu.Unlock()
	if cancel != nil {
		cancel()
		cancel = nil
	}
	ctx, c := context.WithCancel(context.Background())
	cancel = c
	cfg := core.Config{
		Peer:        peer,
		VKHashes:    vkHashes,
		Password:    password,
		Listen:      listen,
		NumWorkers:  numWorkers,
		DeviceID:    deviceID,
		Fingerprint: fingerprint,
		ClientIDs:   clientIDs,
		CaptchaMode: "auto",
	}
	if sink != nil {
		cfg.OnConfig = func(conf string) { sink.OnConfig(conf) }
	}
	go func() { _ = core.Run(ctx, cfg) }()
}

// Stop cancels the running WDTT core. Idempotent.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	if cancel != nil {
		cancel()
		cancel = nil
	}
}

// IsRunning reports whether the core is currently active.
func IsRunning() bool { return core.IsRunning() }

// PushCaptcha feeds a captcha token solved by the Android WebView into the VK
// auth flow.
func PushCaptcha(token string) { core.PushCaptchaResult(token) }

// SetPaused toggles the worker-group pause (e.g. on Android Doze).
func SetPaused(paused bool) { core.SetPaused(paused) }
