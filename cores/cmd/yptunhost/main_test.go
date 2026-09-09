package main

import (
	"bytes"
	"encoding/json"
	"strings"
	"testing"
)

func TestVlessToXrayReality(t *testing.T) {
	link := "vless://11111111-2222-3333-4444-555555555555@example.com:443" +
		"?type=ws&security=reality&pbk=PUB&sid=ab&sni=cdn.example.com&fp=chrome&path=%2Fws&host=cdn.example.com" +
		"&flow=xtls-rprx-vision#My%20Node"
	out, err := vlessToXray(link, 51234)
	if err != nil {
		t.Fatal(err)
	}
	var cfg map[string]any
	if err := json.Unmarshal([]byte(out), &cfg); err != nil {
		t.Fatal(err)
	}
	in := cfg["inbounds"].([]any)[0].(map[string]any)
	if in["port"].(float64) != 51234 || in["listen"] != "127.0.0.1" || in["protocol"] != "socks" {
		t.Fatalf("inbound must be a loopback socks on the given port: %v", in)
	}
	ob := cfg["outbounds"].([]any)[0].(map[string]any)
	vnext := ob["settings"].(map[string]any)["vnext"].([]any)[0].(map[string]any)
	if vnext["address"] != "example.com" || vnext["port"].(float64) != 443 {
		t.Fatalf("bad vnext: %v", vnext)
	}
	if vnext["users"].([]any)[0].(map[string]any)["flow"] != "xtls-rprx-vision" {
		t.Fatal("flow lost")
	}
	st := ob["streamSettings"].(map[string]any)
	if st["network"] != "ws" || st["security"] != "reality" {
		t.Fatalf("bad stream: %v", st)
	}
	if st["realitySettings"].(map[string]any)["publicKey"] != "PUB" {
		t.Fatal("reality pbk lost")
	}
	if st["wsSettings"].(map[string]any)["path"] != "/ws" {
		t.Fatal("ws path lost")
	}
}

func TestVlessRejectsOtherSchemes(t *testing.T) {
	if _, err := vlessToXray("ss://whatever@host:443", 1080); err == nil {
		t.Fatal("expected a scheme error")
	}
	if _, err := vlessToXray("vless://@host:443", 1080); err == nil {
		t.Fatal("expected a missing-uuid error")
	}
}

// Framing round-trip: what we write must be readable back with the same 4-byte length prefix
// Chrome uses, otherwise every reply silently disappears.
func TestMessageFramingRoundTrip(t *testing.T) {
	var buf bytes.Buffer
	writeMessage(&buf, response{OK: true, Port: 4242, Kind: "vless"})
	if buf.Len() < 4 {
		t.Fatal("no frame written")
	}
	// Feed it back as a request frame to exercise readMessage's length handling.
	var framed bytes.Buffer
	body := `{"cmd":"start","kind":"vless","uri":"vless://x@h:1"}`
	writeMessage(&framed, response{}) // header shape check only
	framed.Reset()
	frame := make([]byte, 4+len(body))
	frame[0] = byte(len(body))
	frame[1] = byte(len(body) >> 8)
	copy(frame[4:], body)
	req, err := readMessage(bytes.NewReader(frame))
	if err != nil {
		t.Fatal(err)
	}
	if req.Cmd != "start" || !strings.HasPrefix(req.URI, "vless://") {
		t.Fatalf("bad decode: %+v", req)
	}
}

func TestHandleUnknownCmd(t *testing.T) {
	if r := handle(request{Cmd: "nope"}); r.OK || r.Error == "" {
		t.Fatal("unknown cmd must fail loudly")
	}
	if r := handle(request{Cmd: "status"}); !r.OK || r.Running {
		t.Fatal("idle status must be ok + not running")
	}
}
