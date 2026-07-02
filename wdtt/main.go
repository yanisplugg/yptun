// Package wdtt is the vendored WDTT (amurcanov/proxy-turn-vk-android) WireGuard-
// over-VK-TURN client, refactored from a CLI (package main) into a library so it
// can be gomobile-bound alongside freeturn as an ALTERNATIVE VK-TURN core. The
// transport model: WireGuard GoBackend → local UDP → chunked Dispatcher → N
// DTLS-over-VK-TURN worker sessions (9 per VK call hash, up to ~108) → wdtt-server
// → internet. Upstream license: GPLv3.
//
// Only the CLI shell (flags, signals, stdin control, file output) was replaced;
// the dispatcher/session/group/creds/captcha/obfs/wrap logic is upstream verbatim.
package wdtt

import (
	"context"
	"fmt"
	"log"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// CaptchaResultChan delivers a captcha token from an external solver (the Android
// WebView). Feed it via PushCaptchaResult.
var CaptchaResultChan = make(chan string, 1)

var captchaModeValue atomic.Value

// vkAuthModeValue selects how VK TURN creds are fetched: "vkcalls" (new upstream
// path via the VK Calls API, with automatic legacy fallback) or "legacy".
var vkAuthModeValue atomic.Value

// pauseFlag pauses the worker groups (e.g. on Android Doze). 0 = run, 1 = pause.
var pauseFlag int32

// running reports whether a Run is currently active (for the mobile IsRunning).
var running atomic.Bool

func init() {
	captchaModeValue.Store("auto")
	vkAuthModeValue.Store("vkcalls")
}

func normalizeVKAuthMode(mode string) string {
	switch strings.ToLower(strings.TrimSpace(mode)) {
	case "legacy":
		return "legacy"
	default:
		return "vkcalls"
	}
}

func setVKAuthMode(mode string) string {
	normalized := normalizeVKAuthMode(mode)
	vkAuthModeValue.Store(normalized)
	return normalized
}

func getVKAuthMode() string {
	mode, _ := vkAuthModeValue.Load().(string)
	if mode == "" {
		return "vkcalls"
	}
	return mode
}

func normalizeCaptchaMode(mode string) string {
	switch strings.ToLower(strings.TrimSpace(mode)) {
	case "auto", "rjs", "wv":
		return strings.ToLower(strings.TrimSpace(mode))
	default:
		return "auto"
	}
}

func setCaptchaMode(mode string) string {
	normalized := normalizeCaptchaMode(mode)
	captchaModeValue.Store(normalized)
	return normalized
}

func getCaptchaMode() string {
	mode, _ := captchaModeValue.Load().(string)
	if mode == "" {
		return "auto"
	}
	return mode
}

// drainCaptchaResult drops a stale captcha result from the channel.
func drainCaptchaResult() {
	select {
	case <-CaptchaResultChan:
	default:
	}
}

// PushCaptchaResult feeds a captcha token (from the Android WebView solver) to the
// waiting VK auth flow. Exported for the mobile wrapper.
func PushCaptchaResult(token string) {
	drainCaptchaResult()
	select {
	case CaptchaResultChan <- token:
	default:
	}
}

// SetPaused toggles the Doze pause for the worker groups.
func SetPaused(paused bool) {
	if paused {
		atomic.StoreInt32(&pauseFlag, 1)
	} else {
		atomic.StoreInt32(&pauseFlag, 0)
	}
}

// IsRunning reports whether a Run is active.
func IsRunning() bool { return running.Load() }

// Config configures a WDTT run. The zero value is invalid (Peer, VKHashes and
// Password are required).
type Config struct {
	Peer        string // VPS wdtt-server "host:port" (required)
	VKHashes    string // comma/space/newline-separated VK call hashes (required)
	Password    string // connection password — WRAP key is HKDF-derived from it (required)
	Listen      string // local UDP addr WireGuard dials; default "127.0.0.1:9000"
	NumWorkers  int    // clamped to [workersPerGroup, 108] and rounded to a multiple of workersPerGroup
	DeviceID    string // unique device id (default "unknown")
	Fingerprint string // TLS fingerprint: chrome/safari/ios/android/firefox (default "chrome")
	ClientIDs   string // VK client IDs, comma-separated (optional override)
	CaptchaMode string // auto/wv/rjs (default auto)
	VKAuthMode  string // vkcalls/legacy (default vkcalls, auto-falls back to legacy)
	TurnHost    string // optional TURN IP override
	TurnPort    string // optional TURN port override

	// OnConfig receives the WireGuard config fetched from the server (GETCONF),
	// MTU-normalised. The host parses it and brings up the WG tunnel.
	OnConfig func(wgConf string)
}

// Run sets up the local UDP listener, the chunked dispatcher and the worker
// groups, and blocks until ctx is cancelled or every worker exits. It is the
// library entry point (replaces the old CLI main).
func Run(ctx context.Context, cfg Config) error {
	setupGlobalResolver()
	setCaptchaMode(cfg.CaptchaMode)
	setVKAuthMode(cfg.VKAuthMode)

	if strings.TrimSpace(cfg.Peer) == "" || strings.TrimSpace(cfg.VKHashes) == "" {
		return fmt.Errorf("wdtt: Peer and VKHashes are required")
	}
	if cfg.Password == "" {
		return fmt.Errorf("wdtt: Password is required (WRAP key derives from it)")
	}

	listen := cfg.Listen
	if strings.TrimSpace(listen) == "" {
		listen = "127.0.0.1:9000"
	}

	// Resolve the VPS peer, retrying briefly (DNS may not be ready right at start).
	cleanPeerAddr := strings.TrimSpace(cfg.Peer)
	var peer *net.UDPAddr
	var err error
	for i := 0; i < 15; i++ {
		peer, err = net.ResolveUDPAddr("udp", cleanPeerAddr)
		if err == nil {
			break
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(time.Second):
		}
	}
	if err != nil {
		return fmt.Errorf("wdtt: resolve peer %q: %w", cleanPeerAddr, err)
	}

	if strings.TrimSpace(cfg.Fingerprint) != "" {
		SetActiveFingerprint(cfg.Fingerprint)
	}
	if strings.TrimSpace(cfg.ClientIDs) != "" {
		SetActiveClientIds(cfg.ClientIDs)
	}

	hashes := ParseHashes(cfg.VKHashes)
	if len(hashes) == 0 {
		return fmt.Errorf("wdtt: no usable VK hashes")
	}

	wrapKey, err := deriveWrapKey(cfg.Password)
	if err != nil {
		return fmt.Errorf("wdtt: derive WRAP key: %w", err)
	}

	numW := cfg.NumWorkers
	const maxWorkers = 108
	if numW > maxWorkers {
		numW = maxWorkers
	}
	if numW < workersPerGroup {
		numW = workersPerGroup
	}
	numW = (numW / workersPerGroup) * workersPerGroup

	tp := &TurnParams{
		Host:    cfg.TurnHost,
		Port:    cfg.TurnPort,
		Hashes:  hashes,
		WrapKey: wrapKey,
	}

	// Bind the local UDP listener WireGuard dials, waiting for a stale process to
	// release the port, then falling back to a dynamic port.
	var localConn net.PacketConn
	actualListenAddr := listen
	for i := 0; i < 5; i++ {
		localConn, err = net.ListenPacket("udp", actualListenAddr)
		if err == nil {
			break
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(time.Second):
		}
	}
	if err != nil {
		actualListenAddr = "127.0.0.1:0"
		localConn, err = net.ListenPacket("udp", actualListenAddr)
		if err != nil {
			return fmt.Errorf("wdtt: bind local UDP: %w", err)
		}
	}
	if uc, ok := localConn.(*net.UDPConn); ok {
		_ = uc.SetReadBuffer(socketBufSize)
		_ = uc.SetWriteBuffer(socketBufSize)
	}
	stopLocalConn := context.AfterFunc(ctx, func() { _ = localConn.Close() })
	defer stopLocalConn()

	_, localPort, _ := net.SplitHostPort(localConn.LocalAddr().String())
	if localPort == "" {
		localPort = "9000"
	}

	deviceID := cfg.DeviceID
	if deviceID == "" {
		deviceID = "unknown"
	}

	numGroups := numW / workersPerGroup
	log.Printf("[WDTT] workers=%d groups=%d hashes=%d listen=%s peer=%s", numW, numGroups, len(hashes), listen, cleanPeerAddr)

	running.Store(true)
	defer running.Store(false)

	stats := NewStats()
	shutdownCh := make(chan struct{})
	go func() {
		<-ctx.Done()
		close(shutdownCh)
	}()
	go stats.RunLoop(shutdownCh)

	disp := NewDispatcher(ctx, localConn, stats)
	defer disp.Shutdown()

	// The first worker fetches the WireGuard config (GETCONF); normalise MTU and
	// hand it to the host via OnConfig.
	configCh := make(chan string, 1)
	configDone := make(chan struct{})
	go func() {
		defer close(configDone)
		select {
		case rawConf, ok := <-configCh:
			if !ok || rawConf == "" {
				return
			}
			finalConf := rawConf
			if !strings.Contains(finalConf, "MTU =") {
				lines := strings.Split(finalConf, "\n")
				out := make([]string, 0, len(lines)+1)
				for _, line := range lines {
					out = append(out, line)
					if strings.TrimSpace(line) == "[Interface]" {
						out = append(out, "MTU = 1280")
					}
				}
				finalConf = strings.Join(out, "\n")
			}
			if cfg.OnConfig != nil {
				cfg.OnConfig(finalConf)
			}
		case <-ctx.Done():
		}
	}()

	var wg sync.WaitGroup
	workerIDCounter := 1
	var prevWaitReady <-chan struct{}

	for g := 0; g < numGroups; g++ {
		isFirst := g == 0

		var myWaitReady <-chan struct{}
		var mySignalReady chan<- struct{}
		if g > 0 {
			myWaitReady = prevWaitReady
		}
		if g < numGroups-1 {
			ch := make(chan struct{})
			mySignalReady = ch
			prevWaitReady = ch
		}

		ids := make([]int, workersPerGroup)
		for i := range ids {
			ids[i] = workerIDCounter
			workerIDCounter++
		}

		var cc chan<- string
		if isFirst {
			cc = configCh
		}

		wg.Add(1)
		go func(groupID int, isFirstGroup bool, configChan chan<- string, workerIds []int, startHashIndex int, waitR <-chan struct{}, sigR chan<- struct{}) {
			defer wg.Done()
			WorkerGroup(ctx, groupID, startHashIndex, tp, peer, disp, localPort,
				isFirstGroup, configChan, workerIds, &pauseFlag, deviceID, cfg.Password, stats, waitR, sigR)
		}(g+1, isFirst, cc, ids, g, myWaitReady, mySignalReady)
	}

	wg.Wait()
	close(configCh)
	<-configDone
	log.Println("[WDTT] all workers finished")
	return nil
}
