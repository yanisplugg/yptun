// Command yptuncore builds the desktop (Windows/Linux/macOS) c-shared library that exposes
// every YPtun core to the JVM app via a flat C ABI (consumed with JNA from sharedUI/jvmMain).
//
// It mirrors what the gomobile AAR provides on Android — sing-box, xray, AmneziaWG,
// Hysteria2, VK-TURN (freeturn) and olcrtc in ONE shared Go runtime — but with plain C
// functions instead of gomobile bindings. On desktop there is no VpnService.protect(), so a core's
// own sockets are kept off the tunnel three ways: sing-box uses its native auto_detect_interface,
// the TUN bridge adds host routes for known upstreams, and xray is pinned to the physical adapter
// with YpBindOutboundInterface (which is also what keeps `direct`-routed traffic from looping).
//
// Memory contract: every *C.char returned by an exported function is allocated with
// C.CString and MUST be released by the caller via YpFree. Returned error strings are
// NULL on success.
//
// Build (from cores/):
//
//	GOOS=windows CGO_ENABLED=1 go build -buildmode=c-shared \
//	  -tags with_gvisor,with_dhcp,with_wireguard,with_utls,with_clash_api \
//	  -o yptuncore.dll ./cmd/yptuncore
package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
	"unsafe"

	"github.com/olc/awgproxy/awg"
	"github.com/openlibrecommunity/olcrtc/mobile"
	"www.bamsoftware.com/git/dnstt.git/dnsttmobile"
	"wg-turn-client/wdttmobile"
	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json"
	"github.com/samosvalishe/free-turn-proxy/freeturn"
	"github.com/xtls/xray-core/core"
	_ "github.com/xtls/xray-core/main/distro/all"
	"github.com/xtls/xray-core/infra/conf/serial"
	"github.com/xtls/xray-core/transport/internet"

	"bytes"
	"errors"
	"net"
	"net/http"
	"os"
	"strings"

	xnet "github.com/xtls/xray-core/common/net"
)

func main() {} // required by -buildmode=c-shared; never called

// ---------------------------------------------------------------------------
// helpers

func cs(s string) *C.char { return C.CString(s) }

// errOut converts a Go error to a C string (NULL = success).
func errOut(err error) *C.char {
	if err == nil {
		return nil
	}
	return cs(err.Error())
}

//export YpFree
func YpFree(p *C.char) {
	if p != nil {
		C.free(unsafe.Pointer(p))
	}
}

// ---------------------------------------------------------------------------
// log bus: every core writes into one bounded channel the JVM polls.

var logCh = make(chan string, 4096)

func pushLog(tag, line string) {
	select {
	case logCh <- tag + ": " + line:
	default: // drop on overflow rather than block a core goroutine
	}
}

type tagWriter struct{ tag string }

func (w tagWriter) WriteLog(line string) { pushLog(w.tag, line) }

// YpPollLog returns the next buffered log line, waiting up to timeoutMs.
// Returns NULL when the timeout elapses with no line.
//
//export YpPollLog
func YpPollLog(timeoutMs C.int) *C.char {
	select {
	case line := <-logCh:
		return cs(line)
	case <-time.After(time.Duration(timeoutMs) * time.Millisecond):
		return nil
	}
}

// ---------------------------------------------------------------------------
// sing-box

var (
	sbMu       sync.Mutex
	sbInstance *box.Box
	sbCancel   context.CancelFunc
)

// YpSbVersion mirrors libbox.Version() on Android. constant.Version defaults to "unknown" and is
// stamped at build time via -ldflags "-X .../constant.Version=…" (see desktopApp/build.gradle.kts).
//
//export YpSbVersion
func YpSbVersion() *C.char { return cs(constant.Version) }

//export YpSbStart
func YpSbStart(configJSON *C.char) *C.char {
	sbMu.Lock()
	defer sbMu.Unlock()
	if sbInstance != nil {
		return cs("sing-box already running")
	}
	ctx, cancel := context.WithCancel(include.Context(context.Background()))
	opts, err := json.UnmarshalExtendedContext[option.Options](ctx, []byte(C.GoString(configJSON)))
	if err != nil {
		cancel()
		return errOut(err)
	}
	inst, err := box.New(box.Options{Context: ctx, Options: opts})
	if err != nil {
		cancel()
		return errOut(err)
	}
	if err := inst.Start(); err != nil {
		_ = inst.Close()
		cancel()
		return errOut(err)
	}
	sbInstance = inst
	sbCancel = cancel
	pushLog("sb", "sing-box started")
	return nil
}

//export YpSbStop
func YpSbStop() {
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
	pushLog("sb", "sing-box stopped")
}

//export YpSbRunning
func YpSbRunning() C.int {
	sbMu.Lock()
	defer sbMu.Unlock()
	if sbInstance != nil {
		return 1
	}
	return 0
}

// ---------------------------------------------------------------------------
// xray (mirrors cores/xraybridge, duplicated here because that package is
// gomobile-shaped; same xray-core instance semantics)

var (
	xrayMu       sync.Mutex
	xrayInstance *core.Instance
)

// Interface every xray socket is pinned to, or 0 for "don't pin" (see YpBindOutboundInterface).
var (
	bindIfIndex      atomic.Uint32
	bindIfUdp        atomic.Bool
	bindIfControlSet sync.Once
)

// YpBindOutboundInterface pins the sockets xray opens to network interface [index] — the desktop
// stand-in for Android's VpnService.protect(), which does not exist on Windows.
//
// Without it, xray's `direct`/freedom outbound dials through the OS routing table, where the TUN's
// 0.0.0.0/1 + 128.0.0.0/1 sit at metric 1. Anything a routing profile sends direct therefore came
// back IN through tun2socks, was handed to xray again, dialed direct again… a hard loop that ate
// the ephemeral port range within seconds ("Only one usage of each socket address…" in the tun log)
// and made both routing profiles and cascades look dead in TUN mode. sing-box was never affected —
// it has auto_detect_interface.
//
// [pinUdp] must be 0 for a config whose UDP goes to a LOCAL hop: xray hands the controller the
// socket's BIND address (0.0.0.0:0) for UDP, not the destination, so a pinned UDP socket can no
// longer reach 127.0.0.1 — which is exactly how the VK-TURN WireGuard-over-Xray exit talks to the
// relay. Everything else (Standard/Chain/dnstt) wants 1, so direct DNS escapes the tunnel too.
//
// Pass the PHYSICAL interface index before starting the core, and 0 after stopping it.
//
//export YpBindOutboundInterface
func YpBindOutboundInterface(index C.int, pinUdp C.int) {
	if index < 0 {
		index = 0
	}
	bindIfIndex.Store(uint32(index))
	bindIfUdp.Store(pinUdp != 0)
	// Registered once and kept: the controller is a no-op while the index is 0, so installing it
	// unconditionally avoids racing a re-registration against a live dial.
	bindIfControlSet.Do(func() {
		internet.RegisterDialerController(func(network, address string, conn syscall.RawConn) error {
			idx := bindIfIndex.Load()
			if idx == 0 || !shouldPinSocket(network, address) {
				return nil
			}
			return bindSocketToInterface(conn, idx)
		})
	})
}

// shouldPinSocket decides whether one dial gets pinned to the physical interface.
//
// IPv4 only (the desktop TUN captures IPv4 only), never loopback (every internal hop — the olcRTC
// chain port, awgproxy, dnstt, the VK-TURN listener — lives there).
func shouldPinSocket(network, address string) bool {
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		host = address
	}
	ip := net.ParseIP(host)
	if ip == nil || ip.To4() == nil || ip.IsLoopback() {
		return false
	}
	if ip.IsUnspecified() {
		// xray's UDP path is a ListenPacket, so [address] is the bind address rather than the
		// destination and we cannot tell a local hop from a remote one — the caller does.
		return strings.HasPrefix(network, "udp") && bindIfUdp.Load()
	}
	return true
}

// YpAddNativeSearchPath adds [dir] to the library search paths this process looks in.
//
// It exists for NaïveProxy: cronet ships as a shared library (libcronet.dll / .so / .dylib) that the
// `with_purego` loader finds by NAME, scanning the executable's directory plus PATH (Windows) or
// LD_LIBRARY_PATH / DYLD_LIBRARY_PATH (Unix). On desktop our natives are unpacked out of the app jar
// into the YPtun data dir, which none of those cover, so the app points us at that directory. The
// loader reads these through os.Getenv, so setting them in-process is enough.
//
// Harmless when NaïveProxy is unused — nothing loads cronet until a naive outbound is dialed.
//
//export YpAddNativeSearchPath
func YpAddNativeSearchPath(dir *C.char) {
	d := C.GoString(dir)
	if d == "" {
		return
	}
	for _, name := range []string{"PATH", "LD_LIBRARY_PATH", "DYLD_LIBRARY_PATH"} {
		prependSearchPath(name, d)
	}
}

func prependSearchPath(name, dir string) {
	sep := string(os.PathListSeparator)
	current := os.Getenv(name)
	if current == "" {
		_ = os.Setenv(name, dir)
		return
	}
	for _, entry := range strings.Split(current, sep) {
		if entry == dir {
			return
		}
	}
	_ = os.Setenv(name, dir+sep+current)
}

//export YpXraySetAssetPath
func YpXraySetAssetPath(dir *C.char) {
	d := C.GoString(dir)
	if d == "" {
		_ = os.Unsetenv("xray.location.asset")
		return
	}
	_ = os.Setenv("xray.location.asset", d)
}

//export YpXrayVersion
func YpXrayVersion() *C.char { return cs(core.Version()) }

//export YpXrayStart
func YpXrayStart(configJSON *C.char) *C.char {
	xrayMu.Lock()
	defer xrayMu.Unlock()
	if xrayInstance != nil {
		return cs("xray already running")
	}
	config, err := serial.LoadJSONConfig(bytes.NewReader([]byte(C.GoString(configJSON))))
	if err != nil {
		return errOut(err)
	}
	inst, err := core.New(config)
	if err != nil {
		return errOut(err)
	}
	if err := inst.Start(); err != nil {
		return errOut(err)
	}
	xrayInstance = inst
	pushLog("xray", "xray started")
	return nil
}

//export YpXrayStop
func YpXrayStop() {
	xrayMu.Lock()
	defer xrayMu.Unlock()
	if xrayInstance != nil {
		_ = xrayInstance.Close()
		xrayInstance = nil
	}
	pushLog("xray", "xray stopped")
}

//export YpXrayRunning
func YpXrayRunning() C.int {
	xrayMu.Lock()
	defer xrayMu.Unlock()
	if xrayInstance != nil {
		return 1
	}
	return 0
}

// YpXrayMeasureDelay matches xraybridge.MeasureDelay: throwaway instance, fetch url through
// its proxy outbound, RTT in ms or -1.
//
//export YpXrayMeasureDelay
func YpXrayMeasureDelay(configJSON, url, method *C.char, timeoutMs C.int) (result C.longlong) {
	defer func() {
		if r := recover(); r != nil {
			result = -1
		}
	}()
	config, err := serial.LoadJSONConfig(bytes.NewReader([]byte(C.GoString(configJSON))))
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
	m := C.GoString(method)
	if m == "" {
		m = "HEAD"
	}
	req, err := http.NewRequest(m, C.GoString(url), nil)
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
	return C.longlong(time.Since(start).Milliseconds())
}

// ---------------------------------------------------------------------------
// AmneziaWG (awgproxy)

//export YpAwgStart
func YpAwgStart(iniConfig, listenAddr *C.char) *C.char {
	awg.SetLogWriter(tagWriter{"awg"})
	return errOut(awg.Start(C.GoString(iniConfig), C.GoString(listenAddr)))
}

//export YpAwgStop
func YpAwgStop() { awg.Stop() }

//export YpAwgRunning
func YpAwgRunning() C.int {
	if awg.IsRunning() {
		return 1
	}
	return 0
}

//export YpAwgProbe
func YpAwgProbe(iniConfig *C.char) C.longlong {
	return C.longlong(awg.Probe(C.GoString(iniConfig)))
}

//export YpAwgMeasureDelay
func YpAwgMeasureDelay(iniConfig, url, method *C.char, timeoutMs C.int) C.longlong {
	return C.longlong(awg.MeasureDelay(C.GoString(iniConfig), C.GoString(url), C.GoString(method), int(timeoutMs)))
}

// YpAwgGenerateKeyPair returns "privateKey|publicKey" (base64), used by the WARP config generator's
// direct-Cloudflare-registration fallback.
//
//export YpAwgGenerateKeyPair
func YpAwgGenerateKeyPair() *C.char { return cs(awg.GenerateKeyPair()) }

// ---------------------------------------------------------------------------
// Telegram-over-WARP proxy: a SECOND, independent AmneziaWG tunnel exposing its own authenticated
// SOCKS5. It must not disturb the package-level awg used by the main AmneziaWG transport, so it runs
// on its own awg.Instance (the same thing TelegramProxyService does on Android). Only one is ever
// needed, so a single dedicated slot beats a general handle table.

var (
	tgAwgMu       sync.Mutex
	tgAwgInstance *awg.Instance
)

//export YpTgAwgStart
func YpTgAwgStart(iniConfig, listenAddr, user, pass *C.char) *C.char {
	tgAwgMu.Lock()
	defer tgAwgMu.Unlock()
	if tgAwgInstance != nil {
		tgAwgInstance.Stop()
		tgAwgInstance = nil
	}
	inst := awg.NewInstance()
	inst.SetDebug(false)
	inst.SetLogWriter(tagWriter{"tgwarp"})
	// RFC 1929 credentials so no other local app can quietly ride the WARP proxy.
	if u := C.GoString(user); u != "" {
		inst.SetAuth(u, C.GoString(pass))
	}
	// No SetSplitCIDRs: full tunnel, every connection the SOCKS accepts rides WARP. A Telegram-only
	// split sent the non-DC parts (and DNS) out over the blocked network, which is why the same WARP
	// config "worked in TUN but not as a proxy".
	if err := inst.Start(C.GoString(iniConfig), C.GoString(listenAddr)); err != nil {
		inst.Stop()
		return errOut(err)
	}
	tgAwgInstance = inst
	return nil
}

//export YpTgAwgStop
func YpTgAwgStop() {
	tgAwgMu.Lock()
	defer tgAwgMu.Unlock()
	if tgAwgInstance != nil {
		tgAwgInstance.Stop()
		tgAwgInstance = nil
	}
}

//export YpTgAwgRunning
func YpTgAwgRunning() C.int {
	tgAwgMu.Lock()
	defer tgAwgMu.Unlock()
	if tgAwgInstance != nil && tgAwgInstance.IsRunning() {
		return 1
	}
	return 0
}

// ---------------------------------------------------------------------------
// Hysteria2 is now a NATIVE sing-box outbound (since the 1.13 upgrade), so the standalone
// hysteria2proxy bridge was removed upstream. The old YpHy2Start/Stop/Running exports are gone —
// desktop hy2 rides sing-box like every other proxy transport.

// ---------------------------------------------------------------------------
// VK-TURN (freeturn)

//export YpFtVersion
func YpFtVersion() *C.char { return cs(freeturn.Version()) }

//export YpFtStart
func YpFtStart(uri, listenAddr, vkLink *C.char, nStreams C.int) *C.char {
	freeturn.SetLogWriter(tagWriter{"vkturn"})
	return errOut(freeturn.Start(C.GoString(uri), C.GoString(listenAddr), C.GoString(vkLink), int(nStreams)))
}

//export YpFtStop
func YpFtStop() { freeturn.Stop() }

//export YpFtRunning
func YpFtRunning() C.int {
	if freeturn.IsRunning() {
		return 1
	}
	return 0
}

//export YpFtConnectedStreams
func YpFtConnectedStreams() C.int { return C.int(freeturn.ConnectedStreams()) }

// ---------------------------------------------------------------------------
// WDTT (wg-turn-client) — the alternative VK-TURN transport core.
//
// wdttmobile hands the server's WireGuard config back through a ConfigSink callback. A Go→C
// callback would drag a JNA Callback and its threading rules into every caller, so the config is
// parked in a buffered channel instead and the JVM blocks on YpWdttWaitConfig — the same
// "wait for the relay, then build the WG outbound" gate the Android path gets from OnConfig.

var (
	wdttMu       sync.Mutex
	wdttConfigCh chan string
)

type wdttSink struct{ ch chan string }

func (s wdttSink) OnConfig(wgConf string) {
	pushLog("wdtt", "server WG config received")
	select {
	case s.ch <- wgConf:
	default: // a config is already parked; the first one wins
	}
}

//export YpWdttStart
func YpWdttStart(peer, vkHashes, password, listen *C.char, numWorkers C.int, deviceID, fingerprint, clientIDs *C.char) *C.char {
	wdttMu.Lock()
	ch := make(chan string, 1)
	wdttConfigCh = ch
	wdttMu.Unlock()
	wdttmobile.Start(
		C.GoString(peer), C.GoString(vkHashes), C.GoString(password), C.GoString(listen),
		int(numWorkers), C.GoString(deviceID), C.GoString(fingerprint), C.GoString(clientIDs),
		wdttSink{ch: ch},
	)
	return nil
}

// YpWdttWaitConfig blocks up to timeoutMs for the wdtt-server's WireGuard config (GETCONF).
// Returns NULL on timeout, which the caller treats as "fall back to the stored WG config".
//
//export YpWdttWaitConfig
func YpWdttWaitConfig(timeoutMs C.int) *C.char {
	wdttMu.Lock()
	ch := wdttConfigCh
	wdttMu.Unlock()
	if ch == nil {
		return nil
	}
	select {
	case conf := <-ch:
		return cs(conf)
	case <-time.After(time.Duration(timeoutMs) * time.Millisecond):
		return nil
	}
}

//export YpWdttStop
func YpWdttStop() {
	wdttmobile.Stop()
	wdttMu.Lock()
	wdttConfigCh = nil
	wdttMu.Unlock()
}

//export YpWdttRunning
func YpWdttRunning() C.int {
	if wdttmobile.IsRunning() {
		return 1
	}
	return 0
}

//export YpWdttPushCaptcha
func YpWdttPushCaptcha(token *C.char) { wdttmobile.PushCaptcha(C.GoString(token)) }

//export YpWdttVersion
func YpWdttVersion() *C.char { return cs(wdttmobile.Version()) }

// ---------------------------------------------------------------------------
// dnstt (DNS tunnel): a transparent TCP forwarder on the local port; the dnstt-server relays each
// connection to its own upstream SOCKS5, so the local port behaves as that SOCKS5. No socket
// protector here — desktop has no VpnService, the TUN layer routes the DNS resolver around the
// tunnel instead.

var (
	dnsttMu     sync.Mutex
	dnsttClient *dnsttmobile.DnsttClient
)

//export YpDnsttStart
func YpDnsttStart(resolver, domain, pubKeyHex, listenAddr *C.char) *C.char {
	dnsttMu.Lock()
	defer dnsttMu.Unlock()
	if dnsttClient != nil {
		return cs("dnstt already running")
	}
	client, err := dnsttmobile.NewClient(
		C.GoString(resolver), C.GoString(domain), C.GoString(pubKeyHex), C.GoString(listenAddr))
	if err != nil {
		return errOut(err)
	}
	client.SetShareProxy(false)
	if err := client.Start(); err != nil {
		return errOut(err)
	}
	dnsttClient = client
	pushLog("dnstt", "dnstt started on "+C.GoString(listenAddr))
	return nil
}

//export YpDnsttStop
func YpDnsttStop() {
	dnsttMu.Lock()
	defer dnsttMu.Unlock()
	if dnsttClient != nil {
		dnsttClient.Stop()
		dnsttClient = nil
		pushLog("dnstt", "dnstt stopped")
	}
}

//export YpDnsttRunning
func YpDnsttRunning() C.int {
	dnsttMu.Lock()
	defer dnsttMu.Unlock()
	if dnsttClient != nil && dnsttClient.IsRunning() {
		return 1
	}
	return 0
}

// ---------------------------------------------------------------------------
// olcrtc (Stealth engine)

// rtcRuntime is the one olcRTC runtime this core drives. Upstream b22f336
// replaced the package-level mobile singleton with a Runtime object, but the C
// ABI below is unchanged, so a single process-wide instance keeps exactly the
// old semantics - Check/Ping included, since they inherit whatever was
// configured through the YpRtcSet* calls.
var rtcRuntime = mobile.New()

// rtcStopWaitMs mirrors Kotlin's PREVIOUS_STOP_WAIT_MS. Runtime.Stop defaults to
// 5 s, and on timeout the runtime stays in "stopping" - every later Start then
// returns ErrAlreadyRunning and the engine never comes back. Wait long enough
// that an ordinary teardown always wins.
const rtcStopWaitMs = 12_000

// rtcSet reports a failed setter on the log bus: the C ABI for these is void,
// and a rejected value must not pass silently.
func rtcSet(what string, err error) {
	if err != nil {
		pushLog("olcrtc", what+" failed: "+err.Error())
	}
}

//export YpRtcVersion
func YpRtcVersion() *C.char { return cs(mobile.Version()) }

//export YpRtcSetTransport
func YpRtcSetTransport(transport *C.char) {
	rtcSet("set transport", rtcRuntime.SetTransport(C.GoString(transport)))
}

//export YpRtcSetTelemostCookies
func YpRtcSetTelemostCookies(cookies *C.char) { rtcRuntime.SetTelemostCookies(C.GoString(cookies)) }

//export YpRtcSetDNS
func YpRtcSetDNS(dnsServer *C.char) { rtcSet("set dns", rtcRuntime.SetDNS(C.GoString(dnsServer))) }

//export YpRtcSetSocksListenHost
func YpRtcSetSocksListenHost(host *C.char) {
	rtcSet("set socks listen host", rtcRuntime.SetSocksListenHost(C.GoString(host)))
}

//export YpRtcSetVP8Options
func YpRtcSetVP8Options(fps, batchSize C.int) {
	rtcSet("set vp8 options", rtcRuntime.SetVP8Options(int(fps), int(batchSize)))
}

//export YpRtcSetLivenessOptions
func YpRtcSetLivenessOptions(intervalMs, timeoutMs, failures C.int) {
	rtcSet("set liveness options",
		rtcRuntime.SetLivenessOptions(int(intervalMs), int(timeoutMs), int(failures)))
}

//export YpRtcStart
func YpRtcStart(carrier, transport, roomID, clientID, keyHex *C.char, socksPort C.int, socksUser, socksPass *C.char) *C.char {
	rtcRuntime.SetLogWriter(tagWriter{"olcrtc"})
	rtcRuntime.SetDeviceID(C.GoString(clientID))
	if err := errors.Join(
		rtcRuntime.SetProvider(C.GoString(carrier)),
		rtcRuntime.SetRoom(C.GoString(roomID)),
		rtcRuntime.SetKey(C.GoString(keyHex)),
		rtcRuntime.SetSocksPort(int(socksPort)),
		rtcRuntime.SetSocksCredentials(C.GoString(socksUser), C.GoString(socksPass)),
	); err != nil {
		return errOut(err)
	}
	// An empty transport means "keep whatever YpRtcSetTransport installed",
	// which is what the old Start (vs StartWithTransport) pair expressed.
	if t := C.GoString(transport); t != "" {
		if err := rtcRuntime.SetTransport(t); err != nil {
			return errOut(err)
		}
	}
	return errOut(rtcRuntime.Start())
}

//export YpRtcWaitReady
func YpRtcWaitReady(timeoutMs C.int) *C.char { return errOut(rtcRuntime.WaitReady(int(timeoutMs))) }

//export YpRtcStop
func YpRtcStop() { rtcSet("stop", rtcRuntime.Stop(rtcStopWaitMs)) }

//export YpRtcRunning
func YpRtcRunning() C.int {
	if rtcRuntime.IsRunning() {
		return 1
	}
	return 0
}

// YpRtcCheck mirrors Runtime.Check: returns ms or -1 (error text discarded into the log bus).
//
//export YpRtcCheck
func YpRtcCheck(carrier, transport, roomID, clientID, keyHex *C.char, socksPort, timeoutMs, vp8FPS, vp8Batch C.int) C.longlong {
	ms, err := rtcRuntime.Check(
		C.GoString(carrier), C.GoString(transport), C.GoString(roomID), C.GoString(clientID), C.GoString(keyHex),
		int(socksPort), int(timeoutMs), int(vp8FPS), int(vp8Batch))
	if err != nil {
		if !errors.Is(err, context.DeadlineExceeded) {
			pushLog("olcrtc", "check failed: "+err.Error())
		}
		return -1
	}
	return C.longlong(ms)
}

// YpRtcPing mirrors Runtime.Ping: returns ms or -1.
//
//export YpRtcPing
func YpRtcPing(carrier, transport, roomID, clientID, keyHex *C.char, socksPort, timeoutMs C.int, pingURL *C.char, vp8FPS, vp8Batch C.int) C.longlong {
	ms, err := rtcRuntime.Ping(
		C.GoString(carrier), C.GoString(transport), C.GoString(roomID), C.GoString(clientID), C.GoString(keyHex),
		int(socksPort), int(timeoutMs), C.GoString(pingURL), int(vp8FPS), int(vp8Batch))
	if err != nil {
		if !errors.Is(err, context.DeadlineExceeded) {
			pushLog("olcrtc", "ping failed: "+err.Error())
		}
		return -1
	}
	return C.longlong(ms)
}
