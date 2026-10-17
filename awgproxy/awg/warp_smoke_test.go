package awg

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"strings"
	"testing"
	"time"

	"golang.org/x/net/proxy"
)

// Live smoke against real Cloudflare WARP. Opt-in: WARP_SMOKE=1 (and WARP_I1_FILE=<file with the I1 token>).
// It answers "does WARP actually carry data through awgproxy's SOCKS, and does it survive idling" for
// plain WG vs AmneziaWG(+I1), IP vs domain endpoint — the exact shapes the Android WARP feature uses.

func warpRegister(t *testing.T) (priv, peer, v4, v6 string, reserved string) {
	kp := strings.Split(GenerateKeyPair(), "|")
	body := fmt.Sprintf(`{"key":%q,"install_id":"","fcm_token":"","tos":%q,"model":"PC","type":"Android","locale":"en_US"}`,
		kp[1], time.Now().UTC().Format("2006-01-02T15:04:05.000Z"))
	req, _ := http.NewRequest("POST", "https://api.cloudflareclient.com/v0a2158/reg", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", "okhttp/3.12.1")
	req.Header.Set("CF-Client-Version", "a-6.3-2158")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Skipf("cloudflare registration unreachable: %v", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	var r struct {
		Config struct {
			ClientID string `json:"client_id"`
			Peers    []struct{ PublicKey string `json:"public_key"` } `json:"peers"`
			Interface struct{ Addresses struct{ V4, V6 string } `json:"addresses"` } `json:"interface"`
		} `json:"config"`
	}
	if err := json.Unmarshal(raw, &r); err != nil || len(r.Config.Peers) == 0 {
		t.Skipf("registration failed (%d): %s", resp.StatusCode, raw)
	}
	if id, err := base64.StdEncoding.DecodeString(r.Config.ClientID); err == nil && len(id) >= 3 {
		reserved = fmt.Sprintf("%d, %d, %d", id[0], id[1], id[2])
	}
	return kp[0], r.Config.Peers[0].PublicKey, r.Config.Interface.Addresses.V4, r.Config.Interface.Addresses.V6, reserved
}

func fetchTrace(port int, timeout time.Duration) (string, time.Duration, error) {
	d, _ := proxy.SOCKS5("tcp", fmt.Sprintf("127.0.0.1:%d", port), nil, proxy.Direct)
	c := &http.Client{Timeout: timeout, Transport: &http.Transport{
		DialContext: func(ctx context.Context, n, a string) (net.Conn, error) { return d.(proxy.ContextDialer).DialContext(ctx, n, a) },
	}}
	t0 := time.Now()
	resp, err := c.Get("http://www.cloudflare.com/cdn-cgi/trace")
	if err != nil {
		return "", time.Since(t0), err
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	for _, l := range strings.Split(string(b), "\n") {
		if strings.HasPrefix(l, "warp=") {
			return l, time.Since(t0), nil
		}
	}
	return "no warp= line", time.Since(t0), nil
}

func TestWarpSmoke(t *testing.T) {
	if os.Getenv("WARP_SMOKE") == "" {
		t.Skip("set WARP_SMOKE=1")
	}
	i1, _ := os.ReadFile(os.Getenv("WARP_I1_FILE"))
	priv, peer, v4, v6, reserved := warpRegister(t)
	t.Logf("registered: reserved=%q", reserved)

	ini := func(endpoint string, awg bool, withReserved bool) string {
		var b strings.Builder
		fmt.Fprintf(&b, "[Interface]\nPrivateKey = %s\nAddress = %s/32, %s/128\nDNS = 1.1.1.1\nMTU = 1280\n", priv, v4, v6)
		if withReserved && reserved != "" {
			fmt.Fprintf(&b, "Reserved = %s\n", reserved)
		}
		if awg {
			b.WriteString("Jc = 4\nJmin = 40\nJmax = 70\nS1 = 0\nS2 = 0\nH1 = 1\nH2 = 2\nH3 = 3\nH4 = 4\n")
			if len(i1) > 0 {
				fmt.Fprintf(&b, "I1 = %s\n", i1)
			}
		}
		fmt.Fprintf(&b, "\n[Peer]\nPublicKey = %s\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = %s\nPersistentKeepalive = 25\n", peer, endpoint)
		return b.String()
	}
	cases := []struct {
		name, ep string
		awg, rsv bool
	}{
		{"WG  ip   no-reserved", "162.159.192.1:2408", false, false},
		{"WG  dom  no-reserved", "engage.cloudflareclient.com:2408", false, false},
		{"WG  ip   reserved", "162.159.192.1:2408", false, true},
		{"AWG ip   +I1", "162.159.192.1:2408", true, false},
		{"AWG dom  +I1", "engage.cloudflareclient.com:2408", true, false},
	}
	var logs bytes.Buffer
	logSink = &logs
	debug.Store(true)
	for i, c := range cases {
		port := 18700 + i
		logs.Reset()
		if err := Start(ini(c.ep, c.awg, c.rsv), fmt.Sprintf("127.0.0.1:%d", port)); err != nil {
			t.Logf("[%s] START FAILED: %v", c.name, err)
			continue
		}
		var res []string
		// first request, then after 12 s idle, then after 30 s idle (WARP is said to drop idle sessions ~15 s)
		for _, idle := range []time.Duration{0, 12 * time.Second, 30 * time.Second} {
			time.Sleep(idle)
			w, d, err := fetchTrace(port, 20*time.Second)
			if err != nil {
				res = append(res, fmt.Sprintf("idle%v: FAIL %.1fs (%v)", idle, d.Seconds(), err))
			} else {
				res = append(res, fmt.Sprintf("idle%v: %s %.1fs", idle, w, d.Seconds()))
			}
		}
		Stop()
		hs := strings.Count(logs.String(), "Sending handshake initiation")
		t.Logf("[%s] %s | handshakes=%d", c.name, strings.Join(res, " | "), hs)
	}
}

// Time-to-first-data and steady-state loss for AWG variants (the "AWG on the server is unstable" report).
func TestWarpSmokeAwgVariants(t *testing.T) {
	if os.Getenv("WARP_SMOKE") == "" {
		t.Skip("set WARP_SMOKE=1")
	}
	i1, _ := os.ReadFile(os.Getenv("WARP_I1_FILE"))
	priv, peer, v4, v6, _ := warpRegister(t)
	mk := func(jc, i1on bool) string {
		var b strings.Builder
		fmt.Fprintf(&b, "[Interface]\nPrivateKey = %s\nAddress = %s/32, %s/128\nDNS = 1.1.1.1\nMTU = 1280\n", priv, v4, v6)
		if jc {
			b.WriteString("Jc = 4\nJmin = 40\nJmax = 70\nS1 = 0\nS2 = 0\nH1 = 1\nH2 = 2\nH3 = 3\nH4 = 4\n")
		}
		if i1on {
			fmt.Fprintf(&b, "I1 = %s\n", i1)
		}
		fmt.Fprintf(&b, "\n[Peer]\nPublicKey = %s\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = 162.159.192.1:2408\nPersistentKeepalive = 25\n", peer)
		return b.String()
	}
	for i, c := range []struct {
		name     string
		jc, i1on bool
	}{{"AWG Jc only (no I1)", true, false}, {"AWG Jc + I1", true, true}, {"AWG Jc + I1 (2nd)", true, true}} {
		port := 18710 + i
		if err := Start(mk(c.jc, c.i1on), fmt.Sprintf("127.0.0.1:%d", port)); err != nil {
			t.Logf("[%s] START FAILED %v", c.name, err)
			continue
		}
		t0 := time.Now()
		first := time.Duration(-1)
		ok, fail := 0, 0
		for time.Since(t0) < 70*time.Second {
			if _, _, err := fetchTrace(port, 3*time.Second); err == nil {
				if first < 0 {
					first = time.Since(t0)
				}
				ok++
			} else {
				fail++
			}
			time.Sleep(700 * time.Millisecond)
		}
		Stop()
		t.Logf("[%s] first data after %.1fs; over 70s ok=%d fail=%d", c.name, first.Seconds(), ok, fail)
	}
}

// Keeps a live WARP SOCKS up for WARP_SERVE seconds on 127.0.0.1:18900 so a real xray/sing-box chain can be run
// against it by hand (the engine INI shape is the one the apps use: plain WG, endpoint pinned to the anycast IP).
func TestWarpServe(t *testing.T) {
	secs := os.Getenv("WARP_SERVE")
	if secs == "" {
		t.Skip("set WARP_SERVE=<seconds>")
	}
	priv, peer, v4, v6, _ := warpRegister(t)
	ini := fmt.Sprintf("[Interface]\nPrivateKey = %s\nAddress = %s/32, %s/128\nDNS = 1.1.1.1\nMTU = 1280\n\n[Peer]\nPublicKey = %s\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = 162.159.192.1:2408\nPersistentKeepalive = 25\n", priv, v4, v6, peer)
	if err := Start(ini, "127.0.0.1:18900"); err != nil {
		t.Fatal(err)
	}
	defer Stop()
	var n int
	fmt.Sscan(secs, &n)
	time.Sleep(time.Duration(n) * time.Second)
}
