// Package wdttmobile is the gomobile-bound surface for the WDTT Plus VK-TURN core, also linked into the
// desktop core (cores/cmd/yptuncore). Options travel as ONE JSON string (see Options) so a new core
// setting never changes the bound signatures on either platform. The package name is distinct from
// olcrtc's `mobile` so the generated Java classes don't collide (this becomes Wdttmobile).
package wdttmobile

import (
	"context"
	"encoding/json"
	"strings"
	"sync"

	core "wg-turn-client"
)

// ConfigSink receives the WireGuard config the wdtt-server hands back (GETCONF), already
// MTU-normalised. The host parses it and brings up the WireGuard tunnel.
type ConfigSink interface {
	OnConfig(wgConf string)
}

// Options mirrors core.Config; the JSON names are what the Kotlin side writes.
type Options struct {
	Peer                 string `json:"peer"`
	VKHashes             string `json:"vk_hashes"`
	Password             string `json:"password"`
	Listen               string `json:"listen"`
	NumWorkers           int    `json:"workers"`
	DeviceID             string `json:"device_id"`
	DeviceInfo           string `json:"device_info"`
	Fingerprint          string `json:"fingerprint"`
	ClientIDs            string `json:"client_ids"`
	CaptchaMode          string `json:"captcha_mode"`
	TurnHost             string `json:"turn_host"`
	TurnPort             string `json:"turn_port"`
	VKCallsPreflight     *bool  `json:"vkcalls_preflight"` // nil → on (upstream default)
	ConfigFirstStart     bool   `json:"config_first_start"`
	HashFallback         bool   `json:"hash_fallback"`
	TurnStreamFirst      bool   `json:"turn_stream_first"`
	TurnSNI              string `json:"turn_sni"`
	Masque               bool   `json:"masque"`
	MasqueConfigPath     string `json:"masque_config_path"`
	MasqueAcceptTOS      bool   `json:"masque_accept_tos"`
	CustomVKClientID     string `json:"custom_vk_client_id"`
	CustomVKClientSecret string `json:"custom_vk_client_secret"`
}

func (o Options) config() core.Config {
	preflight := o.VKCallsPreflight == nil || *o.VKCallsPreflight
	return core.Config{
		Peer:                 o.Peer,
		VKHashes:             o.VKHashes,
		Password:             o.Password,
		Listen:               o.Listen,
		NumWorkers:           o.NumWorkers,
		DeviceID:             o.DeviceID,
		DeviceInfo:           o.DeviceInfo,
		Fingerprint:          o.Fingerprint,
		ClientIDs:            o.ClientIDs,
		CaptchaMode:          o.CaptchaMode,
		TurnHost:             o.TurnHost,
		TurnPort:             o.TurnPort,
		VKCallsPreflight:     preflight,
		ConfigFirstStart:     o.ConfigFirstStart,
		HashFallback:         o.HashFallback,
		TurnStreamFirst:      o.TurnStreamFirst,
		TurnSNI:              o.TurnSNI,
		Masque:               o.Masque,
		MasqueConfigPath:     o.MasqueConfigPath,
		MasqueAcceptTOS:      o.MasqueAcceptTOS,
		CustomVKClientID:     o.CustomVKClientID,
		CustomVKClientSecret: o.CustomVKClientSecret,
	}
}

var (
	mu      sync.Mutex
	cancel  context.CancelFunc
	lastErr string
)

// wdttVersion identifies the vendored core: WDTT Plus release + upstream commit.
const wdttVersion = "Plus v17 (abf0a0f)"

// Version returns the WDTT (VK-TURN) core version for display in the app's settings.
func Version() string { return wdttVersion }

// Start launches the WDTT core in the background and returns immediately; any previous run is
// stopped first. optionsJSON is an Options object. An unparsable JSON is returned as an error, a
// failure inside the core later is reported by LastError. The WireGuard config arrives via
// sink.OnConfig once the first worker has fetched it.
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

// LastError is why the last Start's core stopped on its own ("" while fine / after a normal Stop).
func LastError() string {
	mu.Lock()
	defer mu.Unlock()
	return lastErr
}

// CheckHashes probes the VK call hashes (comma/space/newline separated) for TURN credentials and
// returns one "index|hash|status|message" line per hash, newline-joined. status: ok, captcha, dead,
// blocked, full, limited, network, error. Blocks up to ~90 s per hash.
func CheckHashes(vkHashes string) string {
	return strings.Join(core.CheckHashes(context.Background(), vkHashes), "\n")
}

// PushCaptcha feeds a captcha token solved outside the core into the VK auth flow.
func PushCaptcha(token string) { core.PushCaptchaResult(token) }

// SetPaused toggles the worker-group pause (e.g. on Android Doze).
func SetPaused(paused bool) { core.SetPaused(paused) }

// SetDeviceSleep tells the core the screen went off (true) or on (false).
func SetDeviceSleep(asleep bool) { core.NoteDeviceSleep(asleep) }
