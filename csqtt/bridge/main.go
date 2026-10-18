// Command csqtthost runs the csqtt Rust client (github.com/amurcanov/csqtt — the VK TURN/RTP tunnel)
// for YPtun and serves its tunnel as a loopback SOCKS5.
//
// csqtt's client speaks raw IP packets (it was written around an Android TUN fd). YPtun's hosts own
// the TUN themselves and feed every engine through a local SOCKS5, so here the packets go through a
// userspace gVisor netstack instead — the same shape as the qWDTT "Raw" mode (wdtt/raw_socks.go) — and
// the netstack is served as SOCKS5 (CONNECT + UDP ASSOCIATE). The host then treats it like the
// AmneziaWG exit: chain proxy, routing and DNS come for free.
//
// Protocol with the host (Kotlin):
//
//	stdin   first line:  OPTS <json>            (see options)
//	        later lines: STOP | PAUSE | RESUME
//	stdout  LOG <text>                          client / bridge log line
//	        READY <ip>|<dns,dns>|<mtu>          tunnel configured, SOCKS5 is listening
//	        STATS <active> <bytesUp> <bytesDown>
//	        ERROR <fatal 0|1> <code> <message>
//	        STOPPED
//
// Closing stdin stops everything, so a crashed host never leaves the Rust client behind.
package main

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const eventPrefix = "__CSQTT_EVENT__|"

// options is what the host writes after "OPTS ". Names are the stored contract with
// VkTurnConfig.csqttCoreOptionsJson.
type options struct {
	Client      string `json:"client"` // path to the csqtt client executable
	Listen      string `json:"listen"` // local SOCKS5 address
	Peer        string `json:"peer"`   // server host:port
	VKHashes    string `json:"vk_hashes"`
	Password    string `json:"password"`
	Workers     int    `json:"workers"`
	DeviceID    string `json:"device_id"`
	Obfs        string `json:"obfs"`         // audio | video
	TurnTCP     bool   `json:"turn_tcp"`     // TURN over TCP/TLS instead of UDP
	Fingerprint string `json:"fingerprint"`  // chrome | firefox | safari | edge | opera
	ClientIDs   string `json:"client_ids"`   // VK app ids, comma separated
	VKAuthMode  string `json:"vk_auth_mode"` // vkcalls | legacy
	Captcha     string `json:"captcha_mode"` // auto | rjs
	TurnHost    string `json:"turn_host"`    // TURN override
	TurnPort    string `json:"turn_port"`    // TURN override
	Redistrib   bool   `json:"redistribute"` // allow more workers than hashes×27
	MTU         int    `json:"mtu"`          // netstack MTU, default 1300 (csqtt's own)
	Generation  int64  `json:"generation"`   // -gen
	Salt        string `json:"salt"`         // -salt
}

var out = struct {
	sync.Mutex
	w *bufio.Writer
}{w: bufio.NewWriter(os.Stdout)}

func emit(format string, args ...any) {
	line := fmt.Sprintf(format, args...)
	line = strings.ReplaceAll(strings.ReplaceAll(line, "\r", ""), "\n", " ")
	out.Lock()
	out.w.WriteString(line)
	out.w.WriteByte('\n')
	out.w.Flush()
	out.Unlock()
}

func logf(format string, args ...any) { emit("LOG "+format, args...) }

func main() {
	br := bufio.NewReader(os.Stdin)
	first, err := br.ReadString('\n')
	if err != nil || !strings.HasPrefix(first, "OPTS ") {
		emit("ERROR 1 bad_options expected OPTS <json> on stdin")
		os.Exit(2)
	}
	var opts options
	if err := json.Unmarshal([]byte(strings.TrimSpace(strings.TrimPrefix(first, "OPTS "))), &opts); err != nil {
		emit("ERROR 1 bad_options %v", err)
		os.Exit(2)
	}
	if err := run(br, opts); err != nil {
		emit("ERROR 1 failed %v", err)
		emit("STOPPED")
		os.Exit(1)
	}
	emit("STOPPED")
}

func run(stdin *bufio.Reader, o options) error {
	if o.Client == "" || o.Peer == "" || strings.TrimSpace(o.VKHashes) == "" || o.Password == "" {
		return fmt.Errorf("client, peer, vk_hashes and password are required")
	}
	if o.Listen == "" {
		o.Listen = "127.0.0.1:0"
	}
	if o.MTU < 576 {
		o.MTU = 1300
	}

	// The Rust client binds its own UDP socket; pick a free loopback port for it up front.
	udpPort, err := freeUDPPort()
	if err != nil {
		return err
	}
	clientAddr := &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: udpPort}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	cmd := exec.CommandContext(ctx, o.Client, clientArgs(o, udpPort)...)
	cmd.Env = append(os.Environ(), "CSQTT_EVENTS=1", "RAYON_NUM_THREADS=2")
	cmd.Cancel = func() error { return cmd.Process.Kill() }
	cmd.WaitDelay = 2 * time.Second
	cin, err := cmd.StdinPipe()
	if err != nil {
		return err
	}
	// One reader sees stdout and stderr; exec's own copier finishes before Wait returns.
	cout, pw := io.Pipe()
	cmd.Stdout, cmd.Stderr = pw, pw
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("start %s: %w", o.Client, err)
	}

	var cinMu sync.Mutex
	send := func(line string) {
		cinMu.Lock()
		defer cinMu.Unlock()
		_, _ = io.WriteString(cin, line+"\n")
	}

	pump := &pumper{addr: clientAddr, mtu: o.MTU}
	defer pump.close()
	socks := &socksServer{}
	var ready atomic.Bool
	fatal := make(chan string, 1)

	go func() { // host control
		for {
			line, err := stdin.ReadString('\n')
			cmdLine := strings.TrimSpace(line)
			switch cmdLine {
			case "PAUSE", "RESUME":
				send(cmdLine)
			case "STOP":
				cancel()
				return
			}
			if err != nil { // host gone
				cancel()
				return
			}
		}
	}()

	onConfig := func(conf string) {
		ip, dns, err := parseTunConf(conf)
		if err != nil {
			logf("[bridge] %v", err)
			return
		}
		tnet, err := pump.attach(ip, dns)
		if err != nil {
			select {
			case fatal <- err.Error():
			default:
			}
			return
		}
		if tnet == nil { // same address as before — nothing changed
			return
		}
		socks.setNet(tnet)
		if !ready.Swap(true) {
			if err := socks.listen(ctx, o.Listen); err != nil {
				select {
				case fatal <- err.Error():
				default:
				}
				return
			}
		}
		emit("READY %s|%s|%d", ip, joinAddrs(dns), o.MTU)
	}

	go func() {
		sc := bufio.NewScanner(cout)
		sc.Buffer(make([]byte, 64*1024), 1024*1024)
		for sc.Scan() {
			handleLine(sc.Text(), onConfig, send)
		}
	}()

	done := make(chan error, 1)
	go func() { err := cmd.Wait(); _ = pw.Close(); done <- err }()
	select {
	case msg := <-fatal:
		cancel()
		<-done
		return fmt.Errorf("%s", msg)
	case err := <-done:
		if ctx.Err() != nil {
			return nil
		}
		if err != nil {
			return fmt.Errorf("client exited: %w", err)
		}
		return fmt.Errorf("client exited")
	case <-ctx.Done():
		// polite stop first: the client closes its TURN allocations on STOP
		send("STOP")
		select {
		case <-done:
		case <-time.After(3 * time.Second):
			_ = cmd.Process.Kill()
			<-done
		}
		return nil
	}
}

// handleLine routes one line of client output: protocol events to the host, the rest as log.
func handleLine(line string, onConfig func(string), send func(string)) {
	line = strings.TrimRight(line, "\r")
	if line == "" {
		return
	}
	if strings.HasPrefix(line, "CAPTCHA_SOLVE|") {
		// No WebView on a headless host: say so, and the client moves on to its next solver (Rust v2).
		logf("[bridge] captcha needs a WebView — not available, skipped")
		send("CAPTCHA_RESULT|error:no-webview")
		return
	}
	if !strings.HasPrefix(line, eventPrefix) {
		logf("%s", line)
		return
	}
	rest := strings.TrimPrefix(line, eventPrefix)
	kind, payload, _ := strings.Cut(rest, "|")
	var p map[string]any
	_ = json.Unmarshal([]byte(payload), &p)
	switch kind {
	case "CONFIG":
		if s, _ := p["config"].(string); strings.HasPrefix(s, "TUNCONF:") {
			onConfig(s)
		}
	case "STATS":
		emit("STATS %d %d %d", int(num(p["active"])), int64(num(p["bytes_up"])), int64(num(p["bytes_down"])))
	case "ERROR":
		fatalFlag := 0
		if b, _ := p["fatal"].(bool); b {
			fatalFlag = 1
		}
		code, _ := p["code"].(string)
		msg, _ := p["message"].(string)
		emit("ERROR %d %s %s", fatalFlag, code, msg)
	case "READY", "PROCESS", "PROGRESS", "STOPPED":
		// worker-level readiness etc. — the tunnel is ready on CONFIG
	default:
		logf("[event] %s %s", kind, payload)
	}
}

func num(v any) float64 { f, _ := v.(float64); return f }

// parseTunConf decodes "TUNCONF:<ip>:<dns,dns>:<n>:<revision>".
func parseTunConf(conf string) (netip.Addr, []netip.Addr, error) {
	parts := strings.SplitN(strings.TrimPrefix(conf, "TUNCONF:"), ":", 3)
	if len(parts) < 2 {
		return netip.Addr{}, nil, fmt.Errorf("bad TUNCONF %q", conf)
	}
	ip, err := netip.ParseAddr(strings.TrimSpace(parts[0]))
	if err != nil {
		return netip.Addr{}, nil, fmt.Errorf("TUNCONF ip: %w", err)
	}
	var dns []netip.Addr
	for _, d := range strings.Split(parts[1], ",") {
		if a, e := netip.ParseAddr(strings.TrimSpace(d)); e == nil {
			dns = append(dns, a)
		}
	}
	if len(dns) == 0 {
		dns = []netip.Addr{netip.MustParseAddr("1.1.1.1")}
	}
	return ip, dns, nil
}

func joinAddrs(a []netip.Addr) string {
	s := make([]string, len(a))
	for i, v := range a {
		s[i] = v.String()
	}
	return strings.Join(s, ",")
}

func clientArgs(o options, udpPort int) []string {
	args := []string{
		"--peer", o.Peer,
		"--listen", "127.0.0.1:" + strconv.Itoa(udpPort),
		"--vk", o.VKHashes,
		"--vk-hash-mode", "manual",
		"--password", o.Password,
	}
	if o.Workers > 0 {
		args = append(args, "-n", strconv.Itoa(o.Workers))
	}
	if o.Redistrib {
		args = append(args, "--allow-hash-redistribution")
	}
	add := func(flag, v string) {
		if strings.TrimSpace(v) != "" {
			args = append(args, flag, strings.TrimSpace(v))
		}
	}
	add("--device-id", o.DeviceID)
	add("--obfs", o.Obfs)
	add("--fingerprint", o.Fingerprint)
	add("--client-ids", o.ClientIDs)
	add("--vk-auth-mode", o.VKAuthMode)
	add("--captcha-mode", o.Captcha)
	add("--turn", o.TurnHost)
	add("--port", o.TurnPort)
	add("--salt", o.Salt)
	if o.Generation > 0 {
		args = append(args, "--gen", strconv.FormatInt(o.Generation, 10))
	}
	if o.TurnTCP {
		args = append(args, "--turn-transport", "tcp_tls")
	}
	return args
}

func freeUDPPort() (int, error) {
	c, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		return 0, err
	}
	defer c.Close()
	return c.LocalAddr().(*net.UDPAddr).Port, nil
}
