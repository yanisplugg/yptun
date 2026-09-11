// Package coreapi is every YPtun core behind one flat, Go-typed API — the single implementation
// behind both the desktop c-shared library (cmd/yptuncore wraps each function in a C export for
// JNA) and the iOS framework (`gomobile bind -target=ios ./coreapi`, called from the app and the
// packet-tunnel extension).
//
// Only gomobile-bindable signatures here: string, int, int64, bool, error.
package coreapi

import (
	"bytes"
	"context"
	"errors"
	"net"
	"net/http"
	"os"
	"sync"
	"sync/atomic"
	"time"

	"github.com/olc/awgproxy/awg"
	"github.com/openlibrecommunity/olcrtc/mobile"
	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json"
	"github.com/samosvalishe/free-turn-proxy/freeturn"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"
	_ "github.com/xtls/xray-core/main/distro/all"
	"masterdnsvpn-go/mdnsmobile"
	"wg-turn-client/wdttmobile"

	xnet "github.com/xtls/xray-core/common/net"
)

// ---------------------------------------------------------------------------
// log bus: every core writes into one bounded channel the host polls.

var logCh = make(chan string, 4096)

// PushLog queues one line for PollLog; drops on overflow rather than block a core goroutine.
func PushLog(tag, line string) {
	select {
	case logCh <- tag + ": " + line:
	default:
	}
}

type tagWriter struct{ tag string }

func (w tagWriter) WriteLog(line string) { PushLog(w.tag, line) }

// PollLog returns the next buffered log line, waiting up to timeoutMs; "" when none arrived.
func PollLog(timeoutMs int) string {
	select {
	case line := <-logCh:
		return line
	case <-time.After(time.Duration(timeoutMs) * time.Millisecond):
		return ""
	}
}

// ---------------------------------------------------------------------------
// sing-box

var (
	sbMu       sync.Mutex
	sbInstance *box.Box
	sbCancel   context.CancelFunc
)

// SbVersion is stamped at build time via -ldflags "-X .../constant.Version=…".
func SbVersion() string { return constant.Version }

func SbStart(configJSON string) error {
	sbMu.Lock()
	defer sbMu.Unlock()
	if sbInstance != nil {
		return errors.New("sing-box already running")
	}
	ctx, cancel := context.WithCancel(include.Context(context.Background()))
	opts, err := json.UnmarshalExtendedContext[option.Options](ctx, []byte(configJSON))
	if err != nil {
		cancel()
		return err
	}
	inst, err := box.New(box.Options{Context: ctx, Options: opts})
	if err != nil {
		cancel()
		return err
	}
	if err := inst.Start(); err != nil {
		_ = inst.Close()
		cancel()
		return err
	}
	sbInstance = inst
	sbCancel = cancel
	PushLog("sb", "sing-box started")
	return nil
}

func SbStop() {
	sbMu.Lock()
	defer sbMu.Unlock()
	if sbInstance != nil {
		_ = sbInstance.Close()
		sbInstance = nil
	}
	if sbCancel != nil {
		sbCancel()
		sbCancel = nil
	}
	PushLog("sb", "sing-box stopped")
}

func SbRunning() bool {
	sbMu.Lock()
	defer sbMu.Unlock()
	return sbInstance != nil
}

// ---------------------------------------------------------------------------
// xray

var (
	xrayMu       sync.Mutex
	xrayInstance *core.Instance
)

// XraySetAssetPath points xray at the directory holding geosite.dat / geoip.dat ("" = unset).
func XraySetAssetPath(dir string) {
	if dir == "" {
		_ = os.Unsetenv("xray.location.asset")
		return
	}
	_ = os.Setenv("xray.location.asset", dir)
}

func XrayVersion() string { return core.Version() }

func XrayStart(configJSON string) error {
	xrayMu.Lock()
	defer xrayMu.Unlock()
	if xrayInstance != nil {
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
	xrayInstance = inst
	PushLog("xray", "xray started")
	return nil
}

func XrayStop() {
	xrayMu.Lock()
	defer xrayMu.Unlock()
	if xrayInstance != nil {
		_ = xrayInstance.Close()
		xrayInstance = nil
	}
	PushLog("xray", "xray stopped")
}

func XrayRunning() bool {
	xrayMu.Lock()
	defer xrayMu.Unlock()
	return xrayInstance != nil
}

// XrayMeasureDelay starts a throwaway instance, fetches url through its proxy outbound and returns
// the RTT in ms, or -1.
func XrayMeasureDelay(configJSON, url, method string, timeoutMs int) (result int64) {
	defer func() {
		if r := recover(); r != nil {
			result = -1
		}
	}()
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
	defer func() {
		defer func() { _ = recover() }()
		time.Sleep(50 * time.Millisecond)
		_ = inst.Close()
	}()

	timeout := time.Duration(timeoutMs) * time.Millisecond
	if timeout <= 0 {
		timeout = 10 * time.Second
	}
	client := &http.Client{
		Timeout: timeout,
		Transport: &http.Transport{
			DisableKeepAlives: true,
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

// ---------------------------------------------------------------------------
// AmneziaWG (awgproxy)

func AwgStart(iniConfig, listenAddr string) error {
	awg.SetLogWriter(tagWriter{"awg"})
	return awg.Start(iniConfig, listenAddr)
}

func AwgVersion() string { return awg.Version() }
func AwgStop()           { awg.Stop() }
func AwgRunning() bool   { return awg.IsRunning() }

func AwgProbe(iniConfig string) int64 { return int64(awg.Probe(iniConfig)) }

func AwgMeasureDelay(iniConfig, url, method string, timeoutMs int) int64 {
	return int64(awg.MeasureDelay(iniConfig, url, method, timeoutMs))
}

// AwgGenerateKeyPair returns "privateKey|publicKey" (base64).
func AwgGenerateKeyPair() string { return awg.GenerateKeyPair() }

// ---------------------------------------------------------------------------
// Telegram-over-WARP proxy: a SECOND, independent AmneziaWG tunnel exposing its own authenticated
// SOCKS5, on its own awg.Instance so it never disturbs the main AmneziaWG transport.

var (
	tgAwgMu       sync.Mutex
	tgAwgInstance *awg.Instance
)

func TgAwgStart(iniConfig, listenAddr, user, pass string) error {
	tgAwgMu.Lock()
	defer tgAwgMu.Unlock()
	if tgAwgInstance != nil {
		tgAwgInstance.Stop()
		tgAwgInstance = nil
	}
	inst := awg.NewInstance()
	inst.SetDebug(false)
	inst.SetLogWriter(tagWriter{"tgwarp"})
	if user != "" {
		inst.SetAuth(user, pass)
	}
	// Full tunnel: a Telegram-only split sent DNS and the non-DC parts over the blocked network.
	if err := inst.Start(iniConfig, listenAddr); err != nil {
		inst.Stop()
		return err
	}
	tgAwgInstance = inst
	return nil
}

func TgAwgStop() {
	tgAwgMu.Lock()
	defer tgAwgMu.Unlock()
	if tgAwgInstance != nil {
		tgAwgInstance.Stop()
		tgAwgInstance = nil
	}
}

func TgAwgRunning() bool {
	tgAwgMu.Lock()
	defer tgAwgMu.Unlock()
	return tgAwgInstance != nil && tgAwgInstance.IsRunning()
}

// ---------------------------------------------------------------------------
// VK-TURN (freeturn)

func FtVersion() string { return freeturn.Version() }

func FtStart(uri, listenAddr, vkLink string, nStreams int) error {
	freeturn.SetLogWriter(tagWriter{"vkturn"})
	// Without a presenter freeturn opens the captcha in a browser itself and never raises
	// CaptchaActive, so the host's relay-ready wait expires while the user is still solving.
	freeturn.SetCaptchaPresenter(ftCaptchaPresenter{})
	return freeturn.Start(uri, listenAddr, vkLink, nStreams)
}

// Pending manual VK captcha URL (served by freeturn on localhost), published to the host.
var ftCaptchaURL atomic.Value

type ftCaptchaPresenter struct{}

func (ftCaptchaPresenter) Show(url string) {
	ftCaptchaURL.Store(url)
	PushLog("vkturn", "VK просит капчу — открываю "+url)
}

func (ftCaptchaPresenter) Hide() { ftCaptchaURL.Store("") }

func FtCaptchaURL() string {
	url, _ := ftCaptchaURL.Load().(string)
	return url
}

func FtCaptchaActive() bool   { return freeturn.CaptchaActive() }
func FtStop()                 { freeturn.Stop() }
func FtRunning() bool         { return freeturn.IsRunning() }
func FtConnectedStreams() int { return freeturn.ConnectedStreams() }

// ---------------------------------------------------------------------------
// WDTT Plus — the server's WireGuard config arrives through a callback; it is parked in a channel so
// the host can simply block on WdttWaitConfig.

var (
	wdttMu       sync.Mutex
	wdttConfigCh chan string
)

type wdttSink struct{ ch chan string }

func (s wdttSink) OnConfig(wgConf string) {
	PushLog("wdtt", "server WG config received")
	select {
	case s.ch <- wgConf:
	default: // a config is already parked; the first one wins
	}
}

// WdttStart starts WDTT Plus from a wdttmobile.Options JSON.
func WdttStart(optionsJSON string) error {
	wdttMu.Lock()
	ch := make(chan string, 1)
	wdttConfigCh = ch
	wdttMu.Unlock()
	return wdttmobile.Start(optionsJSON, wdttSink{ch: ch})
}

// WdttLastError is why the core stopped on its own ("" while fine).
func WdttLastError() string { return wdttmobile.LastError() }

// WdttCheckHashes probes VK call hashes; "index|hash|status|message" lines. Blocks.
func WdttCheckHashes(vkHashes string) string { return wdttmobile.CheckHashes(vkHashes) }

// WdttWaitConfig blocks up to timeoutMs for the server's WireGuard config; "" on timeout.
func WdttWaitConfig(timeoutMs int) string {
	wdttMu.Lock()
	ch := wdttConfigCh
	wdttMu.Unlock()
	if ch == nil {
		return ""
	}
	select {
	case conf := <-ch:
		return conf
	case <-time.After(time.Duration(timeoutMs) * time.Millisecond):
		return ""
	}
}

func WdttStop() {
	wdttmobile.Stop()
	wdttMu.Lock()
	wdttConfigCh = nil
	wdttMu.Unlock()
}

func WdttRunning() bool            { return wdttmobile.IsRunning() }
func WdttPushCaptcha(token string) { wdttmobile.PushCaptcha(token) }
func WdttVersion() string          { return wdttmobile.Version() }

// ---------------------------------------------------------------------------
// MasterDNS (DNS tunnel): a local SOCKS5 whose traffic rides inside DNS queries.

var (
	mdnsMu     sync.Mutex
	mdnsClient *mdnsmobile.MasterDnsClient
)

// MasterDnsStart — zero / out-of-range tuning values keep the upstream defaults.
func MasterDnsStart(
	workDir, domains, key string,
	encryptionMethod int,
	resolvers, listenAddr, socksUser, socksPass string,
	balancingStrategy, packetDuplication, uploadCompression, downloadCompression int,
) error {
	mdnsMu.Lock()
	defer mdnsMu.Unlock()
	if mdnsClient != nil {
		return errors.New("masterdns already running")
	}
	client, err := mdnsmobile.NewClient(workDir, domains, key, encryptionMethod, resolvers, listenAddr, socksUser, socksPass)
	if err != nil {
		return err
	}
	client.SetResolverBalancingStrategy(balancingStrategy)
	client.SetPacketDuplication(packetDuplication)
	client.SetCompression(uploadCompression, downloadCompression)
	if err := client.Start(); err != nil {
		return err
	}
	mdnsClient = client
	PushLog("masterdns", "MasterDNS started on "+listenAddr)
	return nil
}

func MasterDnsStop() {
	mdnsMu.Lock()
	defer mdnsMu.Unlock()
	if mdnsClient != nil {
		mdnsClient.Stop()
		mdnsClient = nil
		PushLog("masterdns", "MasterDNS stopped")
	}
}

func MasterDnsRunning() bool {
	mdnsMu.Lock()
	defer mdnsMu.Unlock()
	return mdnsClient != nil && mdnsClient.IsRunning()
}

// MasterDnsLastError is "" while fine.
func MasterDnsLastError() string {
	mdnsMu.Lock()
	defer mdnsMu.Unlock()
	if mdnsClient == nil {
		return ""
	}
	return mdnsClient.LastError()
}

func MasterDnsVersion() string { return mdnsmobile.Version() }

// ---------------------------------------------------------------------------
// olcRTC (Stealth engine) — one process-wide runtime; Check/Ping inherit whatever the RtcSet*
// calls configured.

var rtcRuntime = mobile.New()

// rtcStopWaitMs mirrors Kotlin's PREVIOUS_STOP_WAIT_MS: on a Stop timeout the runtime stays in
// "stopping" and every later Start fails, so wait long enough that an ordinary teardown wins.
const rtcStopWaitMs = 12_000

func RtcVersion() string                      { return mobile.Version() }
func RtcSetTransport(transport string) error  { return rtcRuntime.SetTransport(transport) }
func RtcSetTelemostCookies(cookies string)    { rtcRuntime.SetTelemostCookies(cookies) }
func RtcSetDNS(dnsServer string) error        { return rtcRuntime.SetDNS(dnsServer) }
func RtcSetSocksListenHost(host string) error { return rtcRuntime.SetSocksListenHost(host) }

func RtcSetVP8Options(fps, batchSize int) error { return rtcRuntime.SetVP8Options(fps, batchSize) }

// RtcSetSEIOptions configures seichannel (fps, frames per tick, fragment bytes, ACK timeout ms).
func RtcSetSEIOptions(fps, batchSize, fragmentSize, ackTimeoutMs int) error {
	return rtcRuntime.SetSEIOptions(fps, batchSize, fragmentSize, ackTimeoutMs)
}

// RtcSetVideoOptions configures videochannel; codec "qrcode" or "tile" (tile needs 1080x1080).
func RtcSetVideoOptions(width, height, fps, qrSize int, qrRecovery, codec string, tileModule, tileRS int) error {
	return rtcRuntime.SetVideoOptions(width, height, fps, qrSize, qrRecovery, codec, tileModule, tileRS)
}

func RtcSetLivenessOptions(intervalMs, timeoutMs, failures int) error {
	return rtcRuntime.SetLivenessOptions(intervalMs, timeoutMs, failures)
}

// RtcStart — an empty transport keeps whatever RtcSetTransport installed.
func RtcStart(carrier, transport, roomID, clientID, keyHex string, socksPort int, socksUser, socksPass string) error {
	rtcRuntime.SetLogWriter(tagWriter{"olcrtc"})
	rtcRuntime.SetDeviceID(clientID)
	if err := errors.Join(
		rtcRuntime.SetProvider(carrier),
		rtcRuntime.SetRoom(roomID),
		rtcRuntime.SetKey(keyHex),
		rtcRuntime.SetSocksPort(socksPort),
		rtcRuntime.SetSocksCredentials(socksUser, socksPass),
	); err != nil {
		return err
	}
	if transport != "" {
		if err := rtcRuntime.SetTransport(transport); err != nil {
			return err
		}
	}
	return rtcRuntime.Start()
}

func RtcWaitReady(timeoutMs int) error { return rtcRuntime.WaitReady(timeoutMs) }
func RtcStop() error                   { return rtcRuntime.Stop(rtcStopWaitMs) }
func RtcRunning() bool                 { return rtcRuntime.IsRunning() }

// RtcCheck returns ms or -1 (the error goes to the log bus).
func RtcCheck(carrier, transport, roomID, clientID, keyHex string, socksPort, timeoutMs, vp8FPS, vp8Batch int) int64 {
	ms, err := rtcRuntime.Check(carrier, transport, roomID, clientID, keyHex, socksPort, timeoutMs, vp8FPS, vp8Batch)
	if err != nil {
		if !errors.Is(err, context.DeadlineExceeded) {
			PushLog("olcrtc", "check failed: "+err.Error())
		}
		return -1
	}
	return int64(ms)
}

// RtcPing returns ms or -1.
func RtcPing(carrier, transport, roomID, clientID, keyHex string, socksPort, timeoutMs int, pingURL string, vp8FPS, vp8Batch int) int64 {
	ms, err := rtcRuntime.Ping(carrier, transport, roomID, clientID, keyHex, socksPort, timeoutMs, pingURL, vp8FPS, vp8Batch)
	if err != nil {
		if !errors.Is(err, context.DeadlineExceeded) {
			PushLog("olcrtc", "ping failed: "+err.Error())
		}
		return -1
	}
	return int64(ms)
}
