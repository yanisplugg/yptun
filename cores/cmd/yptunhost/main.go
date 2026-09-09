// Command yptunhost is the Chrome native-messaging helper behind the "YPtun VPN" browser
// extension. Chrome extensions cannot speak VLESS or AmneziaWG (no raw sockets), only point
// chrome.proxy at a proxy — so this binary runs the real cores locally and exposes ONE loopback
// SOCKS5 port the extension proxies the browser through.
//
//	{"cmd":"start","kind":"vless","uri":"vless://..."}  -> {"ok":true,"port":51423}
//	{"cmd":"start","kind":"awg","config":"[Interface]…"} -> {"ok":true,"port":51424}
//	{"cmd":"stop"}      -> {"ok":true}
//	{"cmd":"status"}    -> {"ok":true,"running":true,"port":51423,"kind":"vless"}
//	{"cmd":"ping"}      -> {"ok":true}   (keeps the MV3 service worker + this process alive)
//
// Registration (once, ID is shown by the extension popup):
//
//	yptunhost --install <extension-id>     / --uninstall
//
// Build (from cores/): go build -o yptunhost.exe ./cmd/yptunhost
package main

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"sync"

	"github.com/olc/awgproxy/awg"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"
	_ "github.com/xtls/xray-core/main/distro/all"
)

const hostName = "org.yptun.host"

type request struct {
	Cmd    string `json:"cmd"`
	Kind   string `json:"kind"`
	URI    string `json:"uri"`
	Config string `json:"config"`
}

type response struct {
	OK      bool   `json:"ok"`
	Error   string `json:"error,omitempty"`
	Running bool   `json:"running"`
	Port    int    `json:"port,omitempty"`
	Kind    string `json:"kind,omitempty"`
	Version string `json:"version,omitempty"`
}

var (
	mu      sync.Mutex
	xray    *core.Instance
	awgInst *awg.Instance
	curPort int
	curKind string
)

func main() {
	if len(os.Args) > 1 {
		if err := runCLI(os.Args[1:]); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		return
	}
	serve(os.Stdin, os.Stdout)
	stop() // Chrome closed the port (extension unloaded / browser quit) -> tear the tunnel down
}

func serve(in io.Reader, out io.Writer) {
	for {
		req, err := readMessage(in)
		if err != nil {
			return // EOF or malformed framing: Chrome is gone
		}
		writeMessage(out, handle(req))
	}
}

func handle(req request) response {
	switch req.Cmd {
	case "ping":
		return response{OK: true, Running: running(), Port: curPort, Kind: curKind}
	case "status":
		return response{OK: true, Running: running(), Port: curPort, Kind: curKind, Version: core.Version()}
	case "stop":
		stop()
		return response{OK: true}
	case "start":
		port, err := start(req)
		if err != nil {
			stop()
			return response{Error: err.Error()}
		}
		return response{OK: true, Running: true, Port: port, Kind: curKind}
	default:
		return response{Error: "unknown cmd " + req.Cmd}
	}
}

func running() bool {
	mu.Lock()
	defer mu.Unlock()
	return xray != nil || awgInst != nil
}

func start(req request) (int, error) {
	stop()
	port, err := freePort()
	if err != nil {
		return 0, err
	}
	mu.Lock()
	defer mu.Unlock()

	kind := req.Kind
	if kind == "" {
		if strings.HasPrefix(strings.TrimSpace(req.URI), "vless://") {
			kind = "vless"
		} else {
			kind = "awg"
		}
	}

	switch kind {
	case "vless":
		cfgJSON, err := vlessToXray(req.URI, port)
		if err != nil {
			return 0, err
		}
		cfg, err := serial.LoadJSONConfig(bytes.NewReader([]byte(cfgJSON)))
		if err != nil {
			return 0, err
		}
		inst, err := core.New(cfg)
		if err != nil {
			return 0, err
		}
		if err := inst.Start(); err != nil {
			return 0, err
		}
		xray = inst
	case "awg":
		conf := req.Config
		if conf == "" {
			conf = req.URI
		}
		if strings.TrimSpace(conf) == "" {
			return 0, errors.New("empty AmneziaWG config")
		}
		inst := awg.NewInstance()
		if err := inst.Start(conf, fmt.Sprintf("127.0.0.1:%d", port)); err != nil {
			return 0, err
		}
		awgInst = inst
	default:
		return 0, errors.New("unknown kind " + kind)
	}

	curPort, curKind = port, kind
	return port, nil
}

func stop() {
	mu.Lock()
	defer mu.Unlock()
	if xray != nil {
		_ = xray.Close()
		xray = nil
	}
	if awgInst != nil {
		awgInst.Stop()
		awgInst = nil
	}
	curPort, curKind = 0, ""
}

// ── native messaging framing: uint32 length (native byte order) + JSON ──────────

func readMessage(r io.Reader) (request, error) {
	var length uint32
	if err := binary.Read(r, binary.LittleEndian, &length); err != nil {
		return request{}, err
	}
	if length == 0 || length > 8*1024*1024 {
		return request{}, errors.New("bad message length")
	}
	buf := make([]byte, length)
	if _, err := io.ReadFull(r, buf); err != nil {
		return request{}, err
	}
	var req request
	return req, json.Unmarshal(buf, &req)
}

func writeMessage(w io.Writer, resp response) {
	body, err := json.Marshal(resp)
	if err != nil {
		return
	}
	_ = binary.Write(w, binary.LittleEndian, uint32(len(body)))
	_, _ = w.Write(body)
}
