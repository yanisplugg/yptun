// Package wdtt is the qWDTT VK-TURN client (github.com/SpaceNeuroX/proxy-turn-vk-android, go_client)
// as a library: upstream's CLI main() became Run(ctx, Config), its flags became Config fields, and the
// stdin/stdout protocol it spoke with its Android host (PAUSE/RESUME, CAPTCHA_SOLVE, CAPTCHA_RESULT,
// VK_AUTH_REQUIRED, the WireGuard config box) became the exported functions below. Everything else is
// upstream code; see YPTUN.md for the local patches.
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

// CaptchaResultChan — канал для получения токена капчи из внешнего решателя (WebView)
var CaptchaResultChan = make(chan string, 1)

var captchaModeValue atomic.Value

func init() {
	captchaModeValue.Store("auto")
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

// drainCaptchaResult удаляет устаревший результат капчи из канала
func drainCaptchaResult() {
	select {
	case <-CaptchaResultChan:
	default:
	}
}

// ---------------------------------------------------------------------------------------------
// Host bridges (were stdin/stdout lines in the upstream CLI)

// captchaRequestHook receives a WebView captcha request (upstream: "CAPTCHA_SOLVE|mode|uri|token" on
// stdout). nil = no WebView on this host: the request fails at once so the solver chain moves on to
// the built-in Go solver instead of waiting out the WebView timeout.
var captchaRequestHook atomic.Pointer[func(mode, redirectURI, sessionToken string)]

// SetCaptchaRequestHook installs (or, with nil, removes) the WebView captcha bridge; answer with
// PushCaptchaResult.
func SetCaptchaRequestHook(hook func(mode, redirectURI, sessionToken string)) {
	if hook == nil {
		captchaRequestHook.Store(nil)
		return
	}
	captchaRequestHook.Store(&hook)
}

// requestCaptchaFromHost asks the host for a WebView solve; false = no bridge installed.
func requestCaptchaFromHost(mode, redirectURI, sessionToken string) bool {
	hook := captchaRequestHook.Load()
	if hook == nil {
		return false
	}
	(*hook)(mode, redirectURI, sessionToken)
	return true
}

// PushCaptchaResult feeds a solved captcha token (or "error:…") to the waiting request.
func PushCaptchaResult(token string) {
	drainCaptchaResult()
	select {
	case CaptchaResultChan <- strings.TrimSpace(token):
	default:
	}
}

// vkAuthRequestHook receives an account-mode TURN credential request (upstream: "VK_AUTH_REQUIRED|link").
// nil = no VK account login on this host: the request fails at once (anonymous mode never asks).
var vkAuthRequestHook atomic.Pointer[func(link string)]

// SetVKAuthRequestHook installs the VK-account login bridge; answer with PushTurnCreds.
func SetVKAuthRequestHook(hook func(link string)) {
	if hook == nil {
		vkAuthRequestHook.Store(nil)
		return
	}
	vkAuthRequestHook.Store(&hook)
}

func requestVKAuthFromHost(link string) bool {
	hook := vkAuthRequestHook.Load()
	if hook == nil {
		return false
	}
	(*hook)(link)
	return true
}

// PushTurnCreds answers an account-mode request with `link|{"user":…,"pass":…,"urls":[…]}` (upstream's
// TURN_CREDS stdin line without its prefix).
func PushTurnCreds(payload string) {
	handleTurnCredsStdinLine("TURN_CREDS|" + payload)
}

// pauseFlag pauses the worker groups (e.g. on Android Doze). 0 = run, 1 = pause.
var pauseFlag int32

// SetPaused toggles the pause (upstream: PAUSE / RESUME on stdin).
func SetPaused(paused bool) {
	if paused {
		atomic.StoreInt32(&pauseFlag, 1)
	} else {
		atomic.StoreInt32(&pauseFlag, 0)
	}
}

// running reports whether a Run is active.
var running atomic.Bool

// IsRunning reports whether a Run is active.
func IsRunning() bool { return running.Load() }

// ---------------------------------------------------------------------------------------------
// Hash check (upstream: -check-hashes)

// CheckHashes probes the VK call hashes (comma/space/newline separated) for TURN credentials and
// returns one "index|hash|status|message" line per hash. status: ok, captcha, dead, limited, network,
// error. Blocks up to 90 s per hash.
func CheckHashes(ctx context.Context, rawHashes string) []string {
	hashes := ParseHashes(rawHashes)
	log.Printf("[CHECK] Проверка VK-хешей: %d", len(hashes))
	lines := make([]string, 0, len(hashes))
	for i, hash := range hashes {
		checkCtx, cancel := context.WithTimeout(ctx, 90*time.Second)
		_, _, turnURLs, err := GetCreds(checkCtx, hash, 9000+i)
		cancel()

		status, message := classifyHashCheckError(err)
		if err == nil {
			status = "ok"
			message = fmt.Sprintf("TURN urls=%d", len(turnURLs))
		}
		lines = append(lines, fmt.Sprintf("%d|%s|%s|%s", i+1, hash, status, sanitizeHashCheckMessage(message)))
	}
	return lines
}

func classifyHashCheckError(err error) (string, string) {
	if err == nil {
		return "ok", ""
	}
	text := strings.ToLower(err.Error())
	switch {
	case strings.Contains(text, "captcha_required") || strings.Contains(text, "captcha_wait_required"):
		return "captcha", "VK просит капчу"
	case strings.Contains(text, "call not found") ||
		strings.Contains(text, "joinconversationbylink") ||
		strings.Contains(text, "missing turn_server") ||
		strings.Contains(text, "9000") ||
		strings.Contains(text, "callunavailable"):
		return "dead", "Звонок не найден или закрыт"
	case strings.Contains(text, "flood") || strings.Contains(text, "rate limit") || strings.Contains(text, "error_code:29"):
		return "limited", "VK временно ограничил запросы"
	case strings.Contains(text, "timeout") || strings.Contains(text, "deadline") || strings.Contains(text, "lookup") || strings.Contains(text, "network"):
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

// ---------------------------------------------------------------------------------------------
// Run (upstream: main)

// Config configures a qWDTT run. Peer, VKHashes and Password are required. The names follow upstream's
// flags (in brackets).
type Config struct {
	Peer        string // VPS wdtt-server "host:port" [-peer] (required)
	VKHashes    string // VK call hashes, comma-separated [-vk] (required)
	Password    string // connection password; the WRAP key is HKDF-derived from it [-password] (required)
	Listen      string // local UDP addr WireGuard dials [-listen], default "127.0.0.1:9000"
	NumWorkers  int    // [-n], default 9; clamped like upstream
	DeviceID    string // [-device-id], default "unknown"
	CaptchaMode string // auto/wv/rjs [-captcha-mode]
	VKAuthMode  string // anonymous/account [-vk-auth], default anonymous
	VKAnonPath  string // vkcalls/legacy [-vk-anon-path], default vkcalls
	VKCredsFile string // TURN creds from a VK account [-vk-creds-file] (account mode)
	GoDNS       string // DNS for VK: yandex/cloudflare/google, doh-*, custom:IP, doh:URL [-go-dns]
	Obfs        string // audio/video RTP camouflage [-obfs], default audio
	TurnHost    string // TURN IP override [-turn]
	TurnPort    string // TURN port override [-port]
	// NoDTLS is the direct mode: RTP-obfs AEAD without DTLS over TURN [-notls]; the server must run
	// with -listen-direct.
	NoDTLS bool
	// TurnTCP dials the TURN relay over TCP instead of UDP [-turn-tcp] — for networks that throttle UDP.
	TurnTCP bool
	// RawMode is qWDTT 1.4's Raw [-mode rawtun]: raw IP packets without WireGuard, to the server's
	// -listen-raw port (Peer must point there). YPtun has no TUN fd to hand over, so the packets land in a
	// userspace netstack served as a SOCKS5 (TCP + UDP) on Listen — see raw_socks.go.
	RawMode bool
	// RawTunFromHost (with RawMode): no SOCKS — the host builds its TUN from the RAWCONF it gets via
	// OnConfig and hands the fd over with AttachTunFD, upstream's exact rawtun path (Android only).
	RawTunFromHost bool

	// OnConfig receives the WireGuard config fetched from the server (GETCONF), with an MTU line
	// guaranteed. The host parses it and brings up the WG tunnel. In RawMode it gets the server's
	// "RAWCONF:ip|dns|mtu" instead, once the SOCKS5 on Listen is accepting.
	OnConfig func(wgConf string)
}

// Run sets up the local UDP listener, the dispatcher and the worker groups, and blocks until ctx is
// cancelled or every worker exits. Upstream's "vpn" mode (the host runs WireGuard itself against
// Listen), or with RawMode its "rawtun" behind a local SOCKS5.
func Run(parent context.Context, cfg Config) error {
	if !running.CompareAndSwap(false, true) {
		return fmt.Errorf("wdtt: already running")
	}
	defer running.Store(false)

	ctx, cancel := context.WithCancel(parent)
	defer cancel()
	dropStaleTunFD()

	goDNS := strings.TrimSpace(cfg.GoDNS)
	if goDNS == "" {
		goDNS = "yandex"
	}
	setupGlobalResolver(goDNS)
	activeCaptchaMode := setCaptchaMode(cfg.CaptchaMode)
	vkAuth := strings.TrimSpace(cfg.VKAuthMode)
	if vkAuth == "" {
		vkAuth = "anonymous"
	}
	activeVkAuthMode := setVkAuthMode(vkAuth)
	anonPath := strings.TrimSpace(cfg.VKAnonPath)
	if anonPath == "" {
		anonPath = "vkcalls"
	}
	activeVkAnonPath := setVkAnonPath(anonPath)
	if err := loadVkCredsFile(cfg.VKCredsFile); err != nil {
		return fmt.Errorf("wdtt: vk-creds-file: %w", err)
	}

	if strings.TrimSpace(cfg.Peer) == "" || strings.TrimSpace(cfg.VKHashes) == "" {
		return fmt.Errorf("wdtt: Peer and VKHashes are required")
	}
	if cfg.Password == "" {
		return fmt.Errorf("wdtt: Password is required (the WRAP key derives from it)")
	}
	hashes := ParseHashes(cfg.VKHashes)
	if len(hashes) == 0 {
		return fmt.Errorf("wdtt: no usable VK hashes")
	}

	// Resolve the VPS peer, retrying briefly (DNS may not be ready right at start).
	peerAddr := strings.TrimSpace(cfg.Peer)
	var peer *net.UDPAddr
	var err error
	for i := 0; i < 15; i++ {
		peer, err = net.ResolveUDPAddr("udp", peerAddr)
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
		return fmt.Errorf("wdtt: resolve peer %q: %w", peerAddr, err)
	}

	wrapKey, err := deriveWrapKey(cfg.Password)
	if err != nil {
		return fmt.Errorf("wdtt: derive WRAP key: %w", err)
	}

	numW := cfg.NumWorkers
	if numW <= 0 {
		numW = 9
	}
	const maxWorkers = 108
	if numW > maxWorkers {
		numW = maxWorkers
	}
	if getVkAuthMode() == "account" {
		const accountMaxWorkers = 4
		if numW > accountMaxWorkers {
			log.Printf("[КЛИЕНТ] Аккаунт VK: TURN-квота ~%d relay на сессию, потоков %d -> %d", accountMaxWorkers, numW, accountMaxWorkers)
			numW = accountMaxWorkers
		}
	} else {
		if numW < workersPerGroup {
			numW = workersPerGroup
		}
		numW = (numW / workersPerGroup) * workersPerGroup
	}

	tp := &TurnParams{
		Host:         cfg.TurnHost,
		Port:         cfg.TurnPort,
		Hashes:       hashes,
		WrapKey:      wrapKey,
		ObfsMode:     normalizeObfsMode(cfg.Obfs),
		NoDTLS:       cfg.NoDTLS,
		RawMode:      cfg.RawMode,
		TCPTransport: cfg.TurnTCP,
	}

	listen := strings.TrimSpace(cfg.Listen)
	if listen == "" {
		listen = "127.0.0.1:9000"
	}
	// SO_REUSEADDR — a quick restart can reclaim the port. Raw has no WireGuard to receive from:
	// Listen is its SOCKS5 (TCP) instead, raised once the server assigns the address.
	var localConn net.PacketConn
	if !cfg.RawMode {
		localConn, err = listenUDP(listen)
		if err != nil {
			return fmt.Errorf("wdtt: listen %s: %w", listen, err)
		}
		if uc, ok := localConn.(*net.UDPConn); ok {
			_ = uc.SetReadBuffer(socketBufSize)
			_ = uc.SetWriteBuffer(socketBufSize)
		}
		stopLocalConn := context.AfterFunc(ctx, func() { _ = localConn.Close() })
		defer stopLocalConn()
	}

	_, localPort, _ := net.SplitHostPort(listen)
	if localPort == "" {
		localPort = "9000"
	}
	deviceID := strings.TrimSpace(cfg.DeviceID)
	if deviceID == "" {
		deviceID = "unknown"
	}

	numGroups := (numW + workersPerGroup - 1) / workersPerGroup
	log.Println("[КЛИЕНТ] ═══════════════════════════════════════")
	log.Printf("[КЛИЕНТ] qWDTT: воркеров %d (групп %d), хешей %d", numW, numGroups, len(hashes))
	log.Printf("[КЛИЕНТ] Слушаю: %s | Пир: %s | TURN: %s | obfs: %s | режим: %s", listen, peerAddr,
		map[bool]string{true: "TCP", false: "UDP"}[cfg.TurnTCP], tp.ObfsMode,
		map[bool]string{true: "Raw", false: "WG"}[cfg.RawMode])
	log.Printf("[КЛИЕНТ] VK auth: %s (anon path %s) | Captcha: %s", activeVkAuthMode, activeVkAnonPath, activeCaptchaMode)
	log.Println("[КЛИЕНТ] ═══════════════════════════════════════")

	stats := NewStats()
	shutdownCh := make(chan struct{})
	go func() {
		<-ctx.Done()
		close(shutdownCh)
	}()
	go stats.RunLoop(shutdownCh)

	var disp *Dispatcher
	if cfg.RawMode {
		disp = NewDispatcherPendingTUN(ctx, stats)
	} else {
		disp = NewDispatcher(ctx, localConn, stats)
	}
	defer disp.Shutdown()

	configCh := make(chan string, 1)
	configDone := make(chan struct{})
	go func() {
		defer close(configDone)
		select {
		case rawConf, ok := <-configCh:
			if !ok || rawConf == "" {
				return
			}
			if strings.HasPrefix(rawConf, "RAWCONF:") {
				if !cfg.RawMode {
					return
				}
				if cfg.RawTunFromHost {
					if cfg.OnConfig != nil {
						cfg.OnConfig(rawConf)
					}
					tunDev, err := waitHostTun(ctx)
					if err != nil {
						log.Printf("[RAW] TUN хоста: %v", err)
						return
					}
					context.AfterFunc(ctx, func() { _ = tunDev.Close() })
					disp.AttachTUN(tunDev)
					log.Println("[RAW] TUN хоста подключён, трафик пошёл")
					return
				}
				if err := startRawSocks(ctx, rawConf, disp, listen); err != nil {
					log.Printf("[RAW] %v", err)
					return
				}
				if cfg.OnConfig != nil {
					cfg.OnConfig(rawConf)
				}
				return
			}
			finalConf := rawConf
			if !strings.Contains(finalConf, "MTU =") {
				var lines []string
				for _, line := range strings.Split(finalConf, "\n") {
					lines = append(lines, line)
					if strings.TrimSpace(line) == "[Interface]" {
						lines = append(lines, "MTU = 1280")
					}
				}
				finalConf = strings.Join(lines, "\n")
			}
			log.Println("[КОНФИГ] WireGuard-конфиг получен от сервера")
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
		startIdx := g * workersPerGroup
		endIdx := startIdx + workersPerGroup
		if endIdx > numW {
			endIdx = numW
		}
		groupSize := endIdx - startIdx
		if groupSize <= 0 {
			continue
		}
		ids := make([]int, groupSize)
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
	log.Println("[КЛИЕНТ] Все воркеры завершены")
	return nil
}
