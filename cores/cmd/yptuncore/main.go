// Command yptuncore builds the desktop (Windows/Linux/macOS) c-shared library that exposes
// every YPtun core to the JVM app via a flat C ABI (consumed with JNA from sharedUI/jvmMain).
//
// The cores themselves live in package coreapi, which the iOS framework binds too; this file only
// converts C strings/ints and adds the desktop-only pieces. On desktop there is no
// VpnService.protect(), so a core's own sockets are kept off the tunnel three ways: sing-box uses
// its native auto_detect_interface, the TUN bridge adds host routes for known upstreams, and xray
// is pinned to the physical adapter with YpBindOutboundInterface (which is also what keeps
// `direct`-routed traffic from looping).
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
	"net"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"unsafe"

	"github.com/xtls/xray-core/transport/internet"
	"kazcores/coreapi"
)

func main() {} // required by -buildmode=c-shared; never called

// ---------------------------------------------------------------------------
// helpers

func cs(s string) *C.char { return C.CString(s) }

func gs(p *C.char) string { return C.GoString(p) }

// errOut converts a Go error to a C string (NULL = success).
func errOut(err error) *C.char {
	if err == nil {
		return nil
	}
	return cs(err.Error())
}

// csOrNil maps "" to NULL (the "none" value of the functions that used to return NULL).
func csOrNil(s string) *C.char {
	if s == "" {
		return nil
	}
	return cs(s)
}

func cbool(b bool) C.int {
	if b {
		return 1
	}
	return 0
}

//export YpFree
func YpFree(p *C.char) {
	if p != nil {
		C.free(unsafe.Pointer(p))
	}
}

// YpPollLog returns the next buffered log line, waiting up to timeoutMs.
// Returns NULL when the timeout elapses with no line.
//
//export YpPollLog
func YpPollLog(timeoutMs C.int) *C.char { return csOrNil(coreapi.PollLog(int(timeoutMs))) }

// ---------------------------------------------------------------------------
// sing-box

//export YpSbVersion
func YpSbVersion() *C.char { return cs(coreapi.SbVersion()) }

//export YpSbStart
func YpSbStart(configJSON *C.char) *C.char { return errOut(coreapi.SbStart(gs(configJSON))) }

//export YpSbStop
func YpSbStop() { coreapi.SbStop() }

//export YpSbRunning
func YpSbRunning() C.int { return cbool(coreapi.SbRunning()) }

// ---------------------------------------------------------------------------
// xray

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
// relay. Everything else (Standard/Chain/MasterDNS) wants 1, so direct DNS escapes the tunnel too.
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
// chain port, awgproxy, MasterDNS, the VK-TURN listener — lives there).
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
	d := gs(dir)
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
func YpXraySetAssetPath(dir *C.char) { coreapi.XraySetAssetPath(gs(dir)) }

//export YpXrayVersion
func YpXrayVersion() *C.char { return cs(coreapi.XrayVersion()) }

//export YpXrayStart
func YpXrayStart(configJSON *C.char) *C.char { return errOut(coreapi.XrayStart(gs(configJSON))) }

//export YpXrayStop
func YpXrayStop() { coreapi.XrayStop() }

//export YpXrayRunning
func YpXrayRunning() C.int { return cbool(coreapi.XrayRunning()) }

// YpXrayMeasureDelay: throwaway instance, fetch url through its proxy outbound, RTT in ms or -1.
//
//export YpXrayMeasureDelay
func YpXrayMeasureDelay(configJSON, url, method *C.char, timeoutMs C.int) C.longlong {
	return C.longlong(coreapi.XrayMeasureDelay(gs(configJSON), gs(url), gs(method), int(timeoutMs)))
}

// ---------------------------------------------------------------------------
// AmneziaWG (awgproxy)

//export YpAwgStart
func YpAwgStart(iniConfig, listenAddr *C.char) *C.char {
	return errOut(coreapi.AwgStart(gs(iniConfig), gs(listenAddr)))
}

//export YpAwgVersion
func YpAwgVersion() *C.char { return cs(coreapi.AwgVersion()) }

//export YpAwgStop
func YpAwgStop() { coreapi.AwgStop() }

//export YpAwgRunning
func YpAwgRunning() C.int { return cbool(coreapi.AwgRunning()) }

//export YpAwgProbe
func YpAwgProbe(iniConfig *C.char) C.longlong { return C.longlong(coreapi.AwgProbe(gs(iniConfig))) }

//export YpAwgMeasureDelay
func YpAwgMeasureDelay(iniConfig, url, method *C.char, timeoutMs C.int) C.longlong {
	return C.longlong(coreapi.AwgMeasureDelay(gs(iniConfig), gs(url), gs(method), int(timeoutMs)))
}

// YpAwgGenerateKeyPair returns "privateKey|publicKey" (base64), used by the WARP config generator's
// direct-Cloudflare-registration fallback.
//
//export YpAwgGenerateKeyPair
func YpAwgGenerateKeyPair() *C.char { return cs(coreapi.AwgGenerateKeyPair()) }

// ---------------------------------------------------------------------------
// Telegram-over-WARP proxy (its own awg.Instance, see coreapi.TgAwgStart)

//export YpTgAwgStart
func YpTgAwgStart(iniConfig, listenAddr, user, pass *C.char) *C.char {
	return errOut(coreapi.TgAwgStart(gs(iniConfig), gs(listenAddr), gs(user), gs(pass)))
}

//export YpTgAwgStop
func YpTgAwgStop() { coreapi.TgAwgStop() }

//export YpTgAwgRunning
func YpTgAwgRunning() C.int { return cbool(coreapi.TgAwgRunning()) }

// ---------------------------------------------------------------------------
// VK-TURN (freeturn)

//export YpFtVersion
func YpFtVersion() *C.char { return cs(coreapi.FtVersion()) }

//export YpFtStart
func YpFtStart(uri, listenAddr, vkLink *C.char, nStreams C.int) *C.char {
	return errOut(coreapi.FtStart(gs(uri), gs(listenAddr), gs(vkLink), int(nStreams)))
}

//export YpFtCaptchaURL
func YpFtCaptchaURL() *C.char { return cs(coreapi.FtCaptchaURL()) }

//export YpFtCaptchaActive
func YpFtCaptchaActive() C.int { return cbool(coreapi.FtCaptchaActive()) }

//export YpFtStop
func YpFtStop() { coreapi.FtStop() }

//export YpFtRunning
func YpFtRunning() C.int { return cbool(coreapi.FtRunning()) }

//export YpFtConnectedStreams
func YpFtConnectedStreams() C.int { return C.int(coreapi.FtConnectedStreams()) }

// ---------------------------------------------------------------------------
// WDTT Plus

// YpWdttStart starts WDTT Plus from a wdttmobile.Options JSON. Returns NULL, or the error text when
// the JSON doesn't parse.
//
//export YpWdttStart
func YpWdttStart(optionsJSON *C.char) *C.char { return errOut(coreapi.WdttStart(gs(optionsJSON))) }

// YpWdttLastError is why the core stopped on its own ("" while fine).
//
//export YpWdttLastError
func YpWdttLastError() *C.char { return cs(coreapi.WdttLastError()) }

// YpWdttCheckHashes probes VK call hashes; "index|hash|status|message" lines. Blocks.
//
//export YpWdttCheckHashes
func YpWdttCheckHashes(vkHashes *C.char) *C.char { return cs(coreapi.WdttCheckHashes(gs(vkHashes))) }

// YpWdttWaitConfig blocks up to timeoutMs for the wdtt-server's WireGuard config (GETCONF).
// Returns NULL on timeout, which the caller treats as "fall back to the stored WG config".
//
//export YpWdttWaitConfig
func YpWdttWaitConfig(timeoutMs C.int) *C.char {
	return csOrNil(coreapi.WdttWaitConfig(int(timeoutMs)))
}

//export YpWdttStop
func YpWdttStop() { coreapi.WdttStop() }

//export YpWdttRunning
func YpWdttRunning() C.int { return cbool(coreapi.WdttRunning()) }

//export YpWdttPushCaptcha
func YpWdttPushCaptcha(token *C.char) { coreapi.WdttPushCaptcha(gs(token)) }

//export YpWdttVersion
func YpWdttVersion() *C.char { return cs(coreapi.WdttVersion()) }

// ---------------------------------------------------------------------------
// MasterDNS. No socket protector here — desktop has no VpnService, so the TUN layer routes the DNS
// resolvers around the tunnel instead (see DesktopVpnManager's bypass list).

//export YpMasterDnsStart
func YpMasterDnsStart(
	workDir, domains, key *C.char,
	encryptionMethod C.int,
	resolvers, listenAddr, socksUser, socksPass *C.char,
	balancingStrategy, packetDuplication, uploadCompression, downloadCompression C.int,
) *C.char {
	return errOut(coreapi.MasterDnsStart(
		gs(workDir), gs(domains), gs(key), int(encryptionMethod),
		gs(resolvers), gs(listenAddr), gs(socksUser), gs(socksPass),
		int(balancingStrategy), int(packetDuplication), int(uploadCompression), int(downloadCompression),
	))
}

//export YpMasterDnsStop
func YpMasterDnsStop() { coreapi.MasterDnsStop() }

//export YpMasterDnsRunning
func YpMasterDnsRunning() C.int { return cbool(coreapi.MasterDnsRunning()) }

//export YpMasterDnsLastError
func YpMasterDnsLastError() *C.char { return csOrNil(coreapi.MasterDnsLastError()) }

//export YpMasterDnsVersion
func YpMasterDnsVersion() *C.char { return cs(coreapi.MasterDnsVersion()) }

// ---------------------------------------------------------------------------
// olcrtc (Stealth engine). The setters are void in the C ABI; a rejected value is reported on the
// log bus so it never passes silently.

func rtcSet(what string, err error) {
	if err != nil {
		coreapi.PushLog("olcrtc", what+" failed: "+err.Error())
	}
}

//export YpRtcVersion
func YpRtcVersion() *C.char { return cs(coreapi.RtcVersion()) }

//export YpRtcSetTransport
func YpRtcSetTransport(transport *C.char) {
	rtcSet("set transport", coreapi.RtcSetTransport(gs(transport)))
}

//export YpRtcSetTelemostCookies
func YpRtcSetTelemostCookies(cookies *C.char) { coreapi.RtcSetTelemostCookies(gs(cookies)) }

//export YpRtcSetDNS
func YpRtcSetDNS(dnsServer *C.char) { rtcSet("set dns", coreapi.RtcSetDNS(gs(dnsServer))) }

//export YpRtcSetSocksListenHost
func YpRtcSetSocksListenHost(host *C.char) {
	rtcSet("set socks listen host", coreapi.RtcSetSocksListenHost(gs(host)))
}

//export YpRtcSetVP8Options
func YpRtcSetVP8Options(fps, batchSize C.int) {
	rtcSet("set vp8 options", coreapi.RtcSetVP8Options(int(fps), int(batchSize)))
}

//export YpRtcSetLivenessOptions
func YpRtcSetLivenessOptions(intervalMs, timeoutMs, failures C.int) {
	rtcSet("set liveness options", coreapi.RtcSetLivenessOptions(int(intervalMs), int(timeoutMs), int(failures)))
}

//export YpRtcStart
func YpRtcStart(carrier, transport, roomID, clientID, keyHex *C.char, socksPort C.int, socksUser, socksPass *C.char) *C.char {
	return errOut(coreapi.RtcStart(gs(carrier), gs(transport), gs(roomID), gs(clientID), gs(keyHex),
		int(socksPort), gs(socksUser), gs(socksPass)))
}

//export YpRtcWaitReady
func YpRtcWaitReady(timeoutMs C.int) *C.char { return errOut(coreapi.RtcWaitReady(int(timeoutMs))) }

//export YpRtcStop
func YpRtcStop() { rtcSet("stop", coreapi.RtcStop()) }

//export YpRtcRunning
func YpRtcRunning() C.int { return cbool(coreapi.RtcRunning()) }

// YpRtcCheck returns ms or -1 (error text goes to the log bus).
//
//export YpRtcCheck
func YpRtcCheck(carrier, transport, roomID, clientID, keyHex *C.char, socksPort, timeoutMs, vp8FPS, vp8Batch C.int) C.longlong {
	return C.longlong(coreapi.RtcCheck(gs(carrier), gs(transport), gs(roomID), gs(clientID), gs(keyHex),
		int(socksPort), int(timeoutMs), int(vp8FPS), int(vp8Batch)))
}

// YpRtcPing returns ms or -1.
//
//export YpRtcPing
func YpRtcPing(carrier, transport, roomID, clientID, keyHex *C.char, socksPort, timeoutMs C.int, pingURL *C.char, vp8FPS, vp8Batch C.int) C.longlong {
	return C.longlong(coreapi.RtcPing(gs(carrier), gs(transport), gs(roomID), gs(clientID), gs(keyHex),
		int(socksPort), int(timeoutMs), gs(pingURL), int(vp8FPS), int(vp8Batch)))
}
