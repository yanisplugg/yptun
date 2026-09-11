// Package wdttmobile is the gomobile-bound surface for the qWDTT VK-TURN core, also linked into the
// desktop/iOS core (cores/coreapi). Options travel as ONE JSON string (see Options) so a new core
// setting never changes the bound signatures on any platform. The package name is distinct from
// olcrtc's `mobile` so the generated Java classes don't collide (this becomes Wdttmobile).
package wdttmobile

import (
	"context"
	"encoding/json"
	"strings"
	"sync"

	core "wg-turn-client"
)

// ConfigSink receives the WireGuard config the wdtt-server hands back (GETCONF), with an MTU line
// guaranteed. The host parses it and brings up the WireGuard tunnel.
type ConfigSink interface {
	OnConfig(wgConf string)
}

// Options mirrors core.Config; the JSON names are what the Kotlin side writes (the shared ones are the
// same as for the former WDTT Plus core, so stored locations keep working).
type Options struct {
	Peer        string `json:"peer"`
	VKHashes    string `json:"vk_hashes"`
	Password    string `json:"password"`
	Listen      string `json:"listen"`
	NumWorkers  int    `json:"workers"`
	DeviceID    string `json:"device_id"`
	CaptchaMode string `json:"captcha_mode"`
	TurnHost    string `json:"turn_host"`
	TurnPort    string `json:"turn_port"`
	// TurnTCP dials the TURN relay over TCP (networks that throttle UDP to VK, e.g. Rostelecom).
	TurnTCP bool `json:"turn_tcp"`
	// Obfs is the RTP camouflage: "audio" (default) or "video".
	Obfs string `json:"obfs"`
	// GoDNS is the DNS the core resolves VK with: yandex/cloudflare/google, doh-*, custom:IP, doh:URL.
	GoDNS string `json:"go_dns"`
	// VKAnonPath is the anonymous TURN credential path: "vkcalls" (default) or "legacy".
	VKAnonPath string `json:"vk_anon_path"`
	// Raw is qWDTT's Raw mode (no WireGuard): Peer is the server's raw port and Listen becomes a local
	// SOCKS5; the sink then gets "RAWCONF:…" instead of a WireGuard config.
	Raw bool `json:"raw"`
}

func (o Options) config() core.Config {
	return core.Config{
		Peer:        o.Peer,
		VKHashes:    o.VKHashes,
		Password:    o.Password,
		Listen:      o.Listen,
		NumWorkers:  o.NumWorkers,
		DeviceID:    o.DeviceID,
		CaptchaMode: o.CaptchaMode,
		TurnHost:    o.TurnHost,
		TurnPort:    o.TurnPort,
		TurnTCP:     o.TurnTCP,
		Obfs:        o.Obfs,
		GoDNS:       o.GoDNS,
		VKAnonPath:  o.VKAnonPath,
		RawMode:     o.Raw,
		// Anonymous VK only: an account login needs upstream's WebView flow, which no host of ours has.
		VKAuthMode: "anonymous",
	}
}

var (
	mu      sync.Mutex
	cancel  context.CancelFunc
	lastErr string
)

// wdttVersion identifies the vendored core: qWDTT release + upstream commit.
const wdttVersion = "qWDTT 1.4.3 (fae121e)"

// Version returns the VK-TURN (qWDTT) core version for display in the app's settings.
func Version() string { return wdttVersion }

// Start launches the core in the background and returns immediately; any previous run is stopped
// first. optionsJSON is an Options object. An unparsable JSON is returned as an error; a failure inside
// the core later is reported by LastError. The WireGuard config arrives via sink.OnConfig once the
// first worker has fetched it.
func Start(optionsJSON string, sink ConfigSink) error {
	var opts Options
	if err := json.Unmarshal([]byte(optionsJSON), &opts); err != nil {
		return err
	}
	cfg := opts.config()
	if sink != nil {
		cfg.OnConfig = func(conf string) { sink.OnConfig(conf) }
	}

	mu.Lock()
	defer mu.Unlock()
	if cancel != nil {
		cancel()
	}
	lastErr = ""
	ctx, c := context.WithCancel(context.Background())
	cancel = c
	go func() {
		if err := core.Run(ctx, cfg); err != nil && ctx.Err() == nil {
			mu.Lock()
			lastErr = err.Error()
			mu.Unlock()
		}
	}()
	return nil
}

// Stop cancels the running core. Idempotent.
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

// LastError is why the last Start's core stopped on its own ("" while fine / after a normal Stop).
func LastError() string {
	mu.Lock()
	defer mu.Unlock()
	return lastErr
}

// CheckHashes probes the VK call hashes (comma/space/newline separated) for TURN credentials and
// returns one "index|hash|status|message" line per hash, newline-joined. status: ok, captcha, dead,
// limited, network, error. Blocks up to ~90 s per hash.
func CheckHashes(vkHashes string) string {
	return strings.Join(core.CheckHashes(context.Background(), vkHashes), "\n")
}

// PushCaptcha feeds a captcha token solved outside the core into the VK auth flow.
func PushCaptcha(token string) { core.PushCaptchaResult(token) }

// SetPaused toggles the worker-group pause (e.g. on Android Doze).
func SetPaused(paused bool) { core.SetPaused(paused) }

// SetDeviceSleep is kept for the host's API; qWDTT pauses through SetPaused and has no separate
// screen-off hint.
func SetDeviceSleep(asleep bool) {}
