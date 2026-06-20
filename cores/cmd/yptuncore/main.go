// Command yptuncore builds the desktop (Windows/Linux/macOS) c-shared library that exposes
// every YPtun core to the JVM app via a flat C ABI (consumed with JNA from sharedUI/jvmMain).
//
// It mirrors what the gomobile AAR provides on Android — sing-box, xray, AmneziaWG,
// Hysteria2, VK-TURN (freeturn) and olcrtc in ONE shared Go runtime — but with plain C
// functions instead of gomobile bindings. On desktop there is no VpnService, so no socket
// protectors are installed: the TUN bridge (tun2socks/wintun) adds a host route for the
// proxy server instead, and sing-box uses its native auto_detect_interface support.
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
	"time"
	"unsafe"

	"github.com/olc/awgproxy/awg"
	"github.com/olc/hysteria2proxy/hy2"
	"github.com/openlibrecommunity/olcrtc/mobile"
	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json"
	"github.com/samosvalishe/free-turn-proxy/freeturn"
	"github.com/xtls/xray-core/core"
	_ "github.com/xtls/xray-core/main/distro/all"
	"github.com/xtls/xray-core/infra/conf/serial"

	"bytes"
	"errors"
	"net"
	"net/http"
	"os"

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

// ---------------------------------------------------------------------------
// Hysteria2 (hysteria2proxy)

//export YpHy2Start
func YpHy2Start(configJSON, listenAddr *C.char) *C.char {
	hy2.SetLogWriter(tagWriter{"hy2"})
	return errOut(hy2.Start(C.GoString(configJSON), C.GoString(listenAddr)))
}

//export YpHy2Stop
func YpHy2Stop() { hy2.Stop() }

//export YpHy2Running
func YpHy2Running() C.int {
	if hy2.IsRunning() {
		return 1
	}
	return 0
}

// ---------------------------------------------------------------------------
// VK-TURN (freeturn)

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
// olcrtc (Stealth engine)

//export YpRtcSetTransport
func YpRtcSetTransport(transport *C.char) { mobile.SetTransport(C.GoString(transport)) }

//export YpRtcSetTelemostCookies
func YpRtcSetTelemostCookies(cookies *C.char) { mobile.SetTelemostCookies(C.GoString(cookies)) }

//export YpRtcSetDNS
func YpRtcSetDNS(dnsServer *C.char) { mobile.SetDNS(C.GoString(dnsServer)) }

//export YpRtcSetSocksListenHost
func YpRtcSetSocksListenHost(host *C.char) { mobile.SetSocksListenHost(C.GoString(host)) }

//export YpRtcSetVP8Options
func YpRtcSetVP8Options(fps, batchSize C.int) { mobile.SetVP8Options(int(fps), int(batchSize)) }

//export YpRtcSetLivenessOptions
func YpRtcSetLivenessOptions(intervalMs, timeoutMs, failures C.int) {
	mobile.SetLivenessOptions(int(intervalMs), int(timeoutMs), int(failures))
}

//export YpRtcStart
func YpRtcStart(carrier, transport, roomID, clientID, keyHex *C.char, socksPort C.int, socksUser, socksPass *C.char) *C.char {
	mobile.SetLogWriter(tagWriter{"olcrtc"})
	t := C.GoString(transport)
	if t == "" {
		return errOut(mobile.Start(
			C.GoString(carrier), C.GoString(roomID), C.GoString(clientID), C.GoString(keyHex),
			int(socksPort), C.GoString(socksUser), C.GoString(socksPass)))
	}
	return errOut(mobile.StartWithTransport(
		C.GoString(carrier), t, C.GoString(roomID), C.GoString(clientID), C.GoString(keyHex),
		int(socksPort), C.GoString(socksUser), C.GoString(socksPass)))
}

//export YpRtcWaitReady
func YpRtcWaitReady(timeoutMs C.int) *C.char { return errOut(mobile.WaitReady(int(timeoutMs))) }

//export YpRtcStop
func YpRtcStop() { mobile.Stop() }

//export YpRtcRunning
func YpRtcRunning() C.int {
	if mobile.IsRunning() {
		return 1
	}
	return 0
}

// YpRtcCheck mirrors mobile.Check: returns ms or -1 (error text discarded into the log bus).
//
//export YpRtcCheck
func YpRtcCheck(carrier, transport, roomID, clientID, keyHex *C.char, socksPort, timeoutMs, vp8FPS, vp8Batch C.int) C.longlong {
	ms, err := mobile.Check(
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

// YpRtcPing mirrors mobile.Ping: returns ms or -1.
//
//export YpRtcPing
func YpRtcPing(carrier, transport, roomID, clientID, keyHex *C.char, socksPort, timeoutMs C.int, pingURL *C.char, vp8FPS, vp8Batch C.int) C.longlong {
	ms, err := mobile.Ping(
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
