// Package wdtt is the vendored WDTT Plus (github.com/Ivan4537/WDTT-Plus, go_client) WireGuard-
// over-VK-TURN client, turned from a CLI (package main) into a library so it can be gomobile-bound
// (Android) and linked into the desktop core alongside freeturn as an ALTERNATIVE VK-TURN core.
// Transport: WireGuard → local UDP → chunked Dispatcher → N DTLS-over-VK-TURN worker sessions
// (9 per VK call hash, up to 108) → wdtt-server → internet. Upstream license: GPLv3.
//
// Only the CLI shell was replaced (flags → Config, stdin control → exported functions, stdout
// captcha marker → SetCaptchaRequestHook, file output → Config.OnConfig); everything else is
// upstream verbatim. To re-vendor: copy go_client/*.go over, `package main` → `package wdtt`, and
// port upstream's main() changes into Run below.
package wdtt

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"log"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type CaptchaResult struct {
	RequestID string
	Value     string
}

// CaptchaResultChan — канал для получения токена капчи из внешнего решателя (WebView)
var CaptchaResultChan = make(chan CaptchaResult, 8)
var captchaRequestSequence atomic.Uint64
var captchaResultWaiters = struct {
	sync.Mutex
	byRequestID map[string]chan CaptchaResult
}{
	byRequestID: make(map[string]chan CaptchaResult),
}

var captchaModeValue atomic.Value
var vkCallsPreflightEnabled atomic.Bool

func init() {
	captchaModeValue.Store("auto")
	vkCallsPreflightEnabled.Store(true)
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

func setVKCallsPreflight(enabled bool) {
	vkCallsPreflightEnabled.Store(enabled)
}

// CheckHashes probes each VK call hash for TURN credentials (the upstream "-check-hashes" mode) and
// returns one "index|hash|status|message" line per hash. status is ok/captcha/dead/blocked/full/
// limited/network/error (see classifyHashCheckError).
func CheckHashes(ctx context.Context, rawHashes string) []string {
	hashes := ParseHashes(rawHashes)
	SetHashCheckMode(true)
	defer SetHashCheckMode(false)
	out := make([]string, 0, len(hashes))
	for i, hash := range hashes {
		checkCtx, cancel := context.WithTimeout(ctx, 90*time.Second)
		_, _, turnURLs, err := GetCreds(checkCtx, hash, 9000+i)
		cancel()
		status, message := classifyHashCheckError(err)
		if err == nil {
			message = fmt.Sprintf("TURN urls=%d", len(turnURLs))
		}
		out = append(out, fmt.Sprintf("%d|%s|%s|%s", i+1, hash, status, sanitizeHashCheckMessage(message)))
	}
	return out
}

func classifyHashCheckError(err error) (string, string) {
	if err == nil {
		return "ok", ""
	}
	text := strings.ToLower(err.Error())
	switch {
	case strings.Contains(text, "captcha_required") || strings.Contains(text, "captcha_wait_required"):
		return "captcha", "VK просит капчу"
	case strings.Contains(text, "invalid_join_link") ||
		strings.Contains(text, "call not found") ||
		strings.Contains(text, "join link is not valid") ||
		strings.Contains(text, "error 9000") ||
		strings.Contains(text, "error 9008") ||
		strings.Contains(text, "error_code:9000") ||
		strings.Contains(text, "error_code:9008"):
		return "dead", "Звонок не найден или закрыт"
	case strings.Contains(text, "anon_blocked") || strings.Contains(text, "anonymous join is disabled"):
		return "blocked", "В звонке запрещён анонимный вход"
	case strings.Contains(text, "call_full") || strings.Contains(text, "call is full"):
		return "full", "В звонке сейчас нет свободных мест"
	case strings.Contains(text, "flood") || strings.Contains(text, "rate limit") || strings.Contains(text, "error_code:29"):
		return "limited", "VK временно ограничил запросы"
	case strings.Contains(text, "timeout") || strings.Contains(text, "deadline") || strings.Contains(text, "lookup") ||
		strings.Contains(text, "network") || strings.Contains(text, "vk https"):
		return "network", "Сетевая ошибка"
	default:
		return "error", err.Error()
	}
}

func sanitizeHashCheckMessage(message string) string {
	message = strings.ReplaceAll(message, "\n", " ")
	message = strings.ReplaceAll(message, "\r", " ")
	message = strings.ReplaceAll(message, "|", "/")
	if len(message) > 180 {
		return message[:180]
	}
	return message
}

func nextCaptchaRequestID(streamID int) string {
	return fmt.Sprintf("%d-%d", streamID, captchaRequestSequence.Add(1))
}

func parseCaptchaResultPayload(payload string) CaptchaResult {
	parts := strings.SplitN(payload, "|", 2)
	if len(parts) == 2 && strings.TrimSpace(parts[0]) != "" {
		return CaptchaResult{RequestID: strings.TrimSpace(parts[0]), Value: strings.TrimSpace(parts[1])}
	}
	return CaptchaResult{Value: strings.TrimSpace(payload)}
}

func captchaResultMatchesRequest(result CaptchaResult, requestID string) bool {
	return result.RequestID == "" || result.RequestID == requestID
}

func registerCaptchaResultWaiter(requestID string) (<-chan CaptchaResult, func()) {
	requestID = strings.TrimSpace(requestID)
	if requestID == "" {
		return CaptchaResultChan, func() {}
	}

	ch := make(chan CaptchaResult, 1)
	captchaResultWaiters.Lock()
	captchaResultWaiters.byRequestID[requestID] = ch
	captchaResultWaiters.Unlock()

	cleanup := func() {
		captchaResultWaiters.Lock()
		if captchaResultWaiters.byRequestID[requestID] == ch {
			delete(captchaResultWaiters.byRequestID, requestID)
		}
		captchaResultWaiters.Unlock()
	}
	return ch, cleanup
}

func deliverCaptchaResult(ch chan CaptchaResult, result CaptchaResult) bool {
	select {
	case ch <- result:
		return true
	default:
		return false
	}
}

func enqueueCaptchaResult(result CaptchaResult) {
	if result.RequestID != "" {
		captchaResultWaiters.Lock()
		ch := captchaResultWaiters.byRequestID[result.RequestID]
		captchaResultWaiters.Unlock()
		if ch == nil {
			log.Printf("[КАПЧА] Запоздалый результат без активного ожидателя request=%q", result.RequestID)
			return
		}
		if !deliverCaptchaResult(ch, result) {
			log.Printf("[КАПЧА] Очередь результата заполнена request=%q", result.RequestID)
		}
		return
	}

	select {
	case CaptchaResultChan <- result:
		return
	default:
	}
	select {
	case <-CaptchaResultChan:
	default:
	}
	select {
	case CaptchaResultChan <- result:
	default:
	}
}
// pauseFlag pauses the worker groups (e.g. on Android Doze). 0 = run, 1 = pause.
var pauseFlag int32

// activeDispatcher is the running Run's dispatcher, for the device sleep/wake hints.
var activeDispatcher atomic.Pointer[Dispatcher]

// running reports whether a Run is currently active (for the mobile IsRunning).
var running atomic.Bool

// captchaRequestHook receives a WebView captcha request (upstream printed it to stdout as
// "CAPTCHA_SOLVE|id|mode|redirectURI|sessionToken" for its Android host). nil = no WebView bridge:
// the request fails at once, so the chain moves straight on to the built-in Go solver instead of
// waiting out the WebView timeout.
var captchaRequestHook atomic.Pointer[func(requestID, mode, redirectURI, sessionToken string)]

// SetCaptchaRequestHook installs (or, with nil, removes) the WebView captcha bridge. Answer through
// PushCaptchaResultFor.
func SetCaptchaRequestHook(hook func(requestID, mode, redirectURI, sessionToken string)) {
	if hook == nil {
		captchaRequestHook.Store(nil)
		return
	}
	captchaRequestHook.Store(&hook)
}

// PushCaptchaResult feeds a captcha token to whichever request is waiting.
func PushCaptchaResult(token string) {
	enqueueCaptchaResult(CaptchaResult{Value: strings.TrimSpace(token)})
}

// PushCaptchaResultFor answers one specific captcha request ("error:..." values report a failure).
func PushCaptchaResultFor(requestID, value string) {
	enqueueCaptchaResult(CaptchaResult{RequestID: strings.TrimSpace(requestID), Value: strings.TrimSpace(value)})
}

// SetPaused toggles the Doze pause for the worker groups.
func SetPaused(paused bool) {
	if paused {
		atomic.StoreInt32(&pauseFlag, 1)
	} else {
		atomic.StoreInt32(&pauseFlag, 0)
	}
}

// NoteDeviceSleep tells the dispatcher the screen went off (true) or on (false): network timeouts
// are not enforced while asleep, and waking sends an immediate channel check.
func NoteDeviceSleep(asleep bool) {
	d := activeDispatcher.Load()
	if d == nil {
		return
	}
	if asleep {
		d.noteDeviceSleep()
	} else {
		d.noteDeviceWake(time.Now())
	}
}

// IsRunning reports whether a Run is active.
func IsRunning() bool { return running.Load() }

// Config configures a WDTT Plus run. Peer, VKHashes and Password are required.
type Config struct {
	Peer        string // VPS wdtt-server "host:port" (required)
	VKHashes    string // comma/space/newline-separated VK call hashes (required)
	Password    string // connection password — the WRAP key is HKDF-derived from it (required)
	Listen      string // local UDP addr WireGuard dials; default "127.0.0.1:9000"
	NumWorkers  int    // clamped to [workersPerGroup, 108] and rounded down to a multiple of workersPerGroup
	DeviceID    string // unique device id (default "unknown")
	DeviceInfo  string // JSON with safe device info shown to the server admin (optional)
	Fingerprint string // TLS fingerprint: firefox/chrome/safari/ios/android (default "firefox")
	ClientIDs   string // VK client IDs, comma-separated (optional override)
	CaptchaMode string // auto/wv/rjs (default auto)
	TurnHost    string // optional TURN IP override
	TurnPort    string // optional TURN port override

	// VKCallsPreflight tries the VK Calls API before the captcha chain (upstream default: on).
	VKCallsPreflight bool
	// ConfigFirstStart waits for the server's WireGuard config before starting the other workers.
	ConfigFirstStart bool
	// HashFallback lets a group fall back to the remaining VK hashes when its own one dies.
	HashFallback bool
	// TurnStreamFirst is the «Сеть РТ» mode: TURN/TLS, then TURN/TCP to every VK address first,
	// keeping UDP as the reserve (for networks that throttle or block UDP to VK).
	TurnStreamFirst bool
	// TurnSNI is the whitelisted SNI for the outer TURN/TLS connection (TurnStreamFirst only).
	TurnSNI string
	// Masque adds a Cloudflare WARP CONNECT-IP (HTTP/2, then HTTP/3) reserve after the direct
	// «Сеть РТ» paths (TurnStreamFirst only). MasqueConfigPath is where the WARP enrollment is kept;
	// MasqueAcceptTOS records the user's consent to Cloudflare's terms for the first enrollment.
	Masque           bool
	MasqueConfigPath string
	MasqueAcceptTOS  bool
	// CustomVKClientID/Secret add an independent VK app as a credential provider (both or neither).
	CustomVKClientID     string
	CustomVKClientSecret string

	// OnConfig receives the WireGuard config fetched from the server (GETCONF), MTU-normalised.
	// The host parses it and brings up the WG tunnel.
	OnConfig func(wgConf string)
}

// Run sets up the local UDP listener, the chunked dispatcher and the worker groups, and blocks until
// ctx is cancelled or every worker exits. It is the library entry point replacing upstream's CLI
// main(): flags became Config fields, the stdin control channel became the exported functions above.
func Run(parent context.Context, cfg Config) error {
	setupGlobalResolver()
	ctx, cancel := context.WithCancel(parent)
	defer cancel()

	activeCaptchaMode := setCaptchaMode(cfg.CaptchaMode)
	setVKCallsPreflight(cfg.VKCallsPreflight)

	if strings.TrimSpace(cfg.Peer) == "" || strings.TrimSpace(cfg.VKHashes) == "" {
		return fmt.Errorf("wdtt: Peer and VKHashes are required")
	}
	if cfg.Password == "" {
		return fmt.Errorf("wdtt: Password is required (the WRAP key derives from it)")
	}

	var normalizedTurnSNI string
	if cfg.TurnStreamFirst {
		var err error
		normalizedTurnSNI, err = normalizeTURNFrontSNI(cfg.TurnSNI)
		if err != nil {
			return fmt.Errorf("wdtt: invalid TURN SNI: %w", err)
		}
		log.Printf("[TURN] Режим «Сеть РТ»: TURN/TLS, затем TCP ко всем адресам VK; UDP остаётся резервом (SNI=%q)", normalizedTurnSNI)
	}
	if cfg.Masque && !cfg.TurnStreamFirst {
		log.Printf("[MASQUE] Проигнорирован: механизм доступен только вместе с режимом «Сеть РТ»")
	}

	fingerprint := strings.TrimSpace(cfg.Fingerprint)
	if fingerprint == "" {
		fingerprint = "firefox"
	}
	SetActiveFingerprint(fingerprint)
	if strings.TrimSpace(cfg.ClientIDs) != "" {
		SetActiveClientIds(cfg.ClientIDs)
	}
	if cfg.CustomVKClientID != "" || cfg.CustomVKClientSecret != "" {
		if err := SetCustomVKCredentials(cfg.CustomVKClientID, cfg.CustomVKClientSecret); err != nil {
			return fmt.Errorf("wdtt: invalid custom VK credentials: %w", err)
		}
	}

	hashes := ParseHashes(cfg.VKHashes)
	if len(hashes) == 0 {
		return fmt.Errorf("wdtt: no usable VK hashes")
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

	var masqueManager *warpMasqueManager
	if cfg.TurnStreamFirst && cfg.Masque {
		masqueManager, err = newWarpMasqueManager(ctx, cfg.MasqueConfigPath, normalizedTurnSNI, cfg.MasqueAcceptTOS)
		if err != nil {
			log.Printf("[MASQUE] Не удалось включить: %v; прямые пути «Сети РТ» остаются доступны", err)
			masqueManager = nil
		} else {
			defer masqueManager.Close()
			go masqueManager.prewarmConfig()
		}
	}

	tp := &TurnParams{
		Host:        cfg.TurnHost,
		Port:        cfg.TurnPort,
		Hashes:      hashes,
		TLSFrontSNI: normalizedTurnSNI,
		Masque:      masqueManager,
		WrapKey:     wrapKey,
	}

	listen := strings.TrimSpace(cfg.Listen)
	if listen == "" {
		listen = "127.0.0.1:9000"
	}
	// Bind the local UDP listener WireGuard dials, waiting for a previous run to release the port,
	// then falling back to a dynamic port.
	var localConn net.PacketConn
	for i := 0; i < 5; i++ {
		localConn, err = net.ListenPacket("udp", listen)
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
		localConn, err = net.ListenPacket("udp", "127.0.0.1:0")
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
	transportSession := newTransportSession()
	numGroups := numW / workersPerGroup
	log.Printf("[WDTT] workers=%d groups=%d hashes=%d listen=%s peer=%s captcha=%s fingerprint=%s",
		numW, numGroups, len(hashes), localConn.LocalAddr(), cleanPeerAddr, activeCaptchaMode, GetActiveFingerprint())

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
	activeDispatcher.Store(disp)
	defer activeDispatcher.CompareAndSwap(disp, nil)
	defer disp.Shutdown()

	// The first group fetches the WireGuard config (GETCONF); normalise MTU and hand it to the host.
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
	workerStarts := newStartPacer(workerStartInterval(len(hashes), cfg.TurnStreamFirst))
	credentialRequests := newCredentialRequestGate(credentialRequestCooldown)
	configStartGate := newConfigFirstStartGate(cfg.ConfigFirstStart)
	primaryCredentialsReady := make(chan struct{})
	log.Printf("[WDTT] распределение потоков по VK-хешам: %v", workerDistributionByHash(numW, len(hashes)))

	for g := 0; g < numGroups; g++ {
		isFirst := g == 0
		var waitPrimary <-chan struct{}
		var signalPrimary chan<- struct{}
		if isFirst {
			signalPrimary = primaryCredentialsReady
		} else {
			waitPrimary = primaryCredentialsReady
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
		go func(groupID int, isFirstGroup bool, configChan chan<- string, workerIDs []int, startHashIndex int,
			waitR <-chan struct{}, signalR chan<- struct{}) {
			defer wg.Done()
			WorkerGroup(ctx, cancel, groupID, startHashIndex, tp, peer, disp, localPort,
				isFirstGroup, configChan, workerIDs, numW, cfg.HashFallback, &pauseFlag,
				deviceID, cfg.Password, cfg.DeviceInfo, transportSession, stats, cfg.TurnStreamFirst,
				configStartGate, workerStarts, credentialRequests, waitR, signalR)
		}(g+1, isFirst, cc, ids, g, waitPrimary, signalPrimary)
	}

	wg.Wait()
	close(configCh)
	<-configDone
	log.Println("[WDTT] all workers finished")
	return nil
}

func newTransportSession() string {
	var value [16]byte
	if _, err := rand.Read(value[:]); err == nil {
		return hex.EncodeToString(value[:])
	}
	return fmt.Sprintf("fallback-%d", time.Now().UnixNano())
}

func normalizeTransportSession(value string) string {
	value = strings.TrimSpace(value)
	if len(value) < 16 || len(value) > 64 {
		return ""
	}
	for _, char := range value {
		if (char >= 'a' && char <= 'z') ||
			(char >= 'A' && char <= 'Z') ||
			(char >= '0' && char <= '9') ||
			char == '-' || char == '_' {
			continue
		}
		return ""
	}
	return value
}
