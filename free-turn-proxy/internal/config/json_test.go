package config

import (
	"encoding/json"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/samosvalishe/free-turn-proxy/internal/uri"
)

const minimalJSON = `{"peer":"1.2.3.4:5000","vk":{"links":["https://vk.ru/call/join/CODE"]}}`

// Отсутствующие поля обязаны брать значения из Defaults(), иначе строгий
// декодер заставил бы хост перечислять весь конфиг целиком.
func TestParseClientJSONFillsDefaults(t *testing.T) {
	c, err := ParseClientJSON([]byte(minimalJSON), "")
	if err != nil {
		t.Fatalf("ParseClientJSON() error = %v", err)
	}

	want := Defaults()
	want.Proxy.Peer = "1.2.3.4:5000"
	want.VK.Links = []string{"CODE"}

	if !reflect.DeepEqual(*c, want) {
		t.Errorf("parsed config differs from defaults:\n got %+v\nwant %+v", *c, want)
	}
}

func TestParseClientJSONAppliesFields(t *testing.T) {
	in := `{
		"peer": "5.6.7.8:56000",
		"clientId": "abc",
		"turn":  {"n": 3, "transport": "udp"},
		"proxy": {"mode": "tcp", "bond": true, "listen": "127.0.0.1:1080"},
		"vk":    {"links": ["A", "B"], "streamsPerCred": 4, "manualCaptcha": true, "platform": "mobile"},
		"obf":   {"profile": "rtpopus3", "key": "` + testObfKey + `"},
		"dns":   {"mode": "doh", "servers": ["1.1.1.1", "8.8.8.8"]},
		"log":   {"debug": true}
	}`
	c, err := ParseClientJSON([]byte(in), "")
	if err != nil {
		t.Fatalf("ParseClientJSON() error = %v", err)
	}

	if c.TURN.N != 3 || !c.TURN.TransportUDP {
		t.Errorf("TURN = %+v", c.TURN)
	}
	if c.Proxy.Mode != ProxyModeTCPFwdBond || c.Proxy.Listen != "127.0.0.1:1080" {
		t.Errorf("Proxy = %+v", c.Proxy)
	}
	if len(c.VK.Links) != 2 || c.VK.Platform != PlatformMobile || !c.VK.ManualCaptcha {
		t.Errorf("VK = %+v", c.VK)
	}
	if c.Obf.Profile != ObfProfileRTPOpus3 || len(c.Obf.Key) != 32 {
		t.Errorf("Obf = %+v", c.Obf)
	}
	if c.DNS.Mode != DNSModeDoH || len(c.DNS.Servers) != 2 {
		t.Errorf("DNS = %+v", c.DNS)
	}
	if !c.Log.Debug || c.ClientID != "abc" {
		t.Errorf("Log/ClientID = %+v / %q", c.Log, c.ClientID)
	}
}

// obf.timingMs учитывается только в udp-режиме - правило одно для всех
// источников, проверяем что JSON доходит до Validate.
func TestParseClientJSONRunsValidate(t *testing.T) {
	in := `{"peer":"1.2.3.4:5000","vk":{"links":["A"]},"proxy":{"mode":"tcp"},` +
		`"obf":{"profile":"rtpopus3","key":"` + testObfKey + `","timingMs":20}}`
	_, err := ParseClientJSON([]byte(in), "")
	if err == nil || !strings.Contains(err.Error(), "-obf-timing") {
		t.Fatalf("ParseClientJSON() error = %v, want obf-timing rule", err)
	}
}

func TestParseClientJSONRejectsUnknownField(t *testing.T) {
	in := `{"peer":"1.2.3.4:5000","vk":{"links":["A"]},"treads":12}`
	_, err := ParseClientJSON([]byte(in), "")
	if err == nil || !strings.Contains(err.Error(), "treads") {
		t.Fatalf("ParseClientJSON() error = %v, want unknown field error", err)
	}
}

func TestParseClientJSONRejectsMalformed(t *testing.T) {
	if _, err := ParseClientJSON([]byte("{"), ""); err == nil {
		t.Fatal("ParseClientJSON() error = nil for malformed input")
	}
}

func TestParseClientJSONOverlayURI(t *testing.T) {
	node := &uri.Config{Version: 1, Provider: ProviderVK, Peer: "9.9.9.9:56000", N: 5}

	c, err := ParseClientJSON([]byte(minimalJSON), node.String())
	if err != nil {
		t.Fatalf("ParseClientJSON() error = %v", err)
	}
	if c.Proxy.Peer != "9.9.9.9:56000" || c.TURN.N != 5 {
		t.Errorf("overlay not applied: peer %q, n %d", c.Proxy.Peer, c.TURN.N)
	}
	// Ссылки URI не несёт - значение из JSON обязано уцелеть.
	if len(c.VK.Links) != 1 || c.VK.Links[0] != "CODE" {
		t.Errorf("links overwritten by overlay: %v", c.VK.Links)
	}
}

func TestParseClientJSONRejectsBadOverlay(t *testing.T) {
	if _, err := ParseClientJSON([]byte(minimalJSON), "freeturn://not-base64!!"); err == nil {
		t.Fatal("ParseClientJSON() error = nil for broken URI")
	}
}

func TestPeekSubURLJSON(t *testing.T) {
	if got := PeekSubURLJSON([]byte(`{"subUrl":"https://example.org/sub"}`)); got != "https://example.org/sub" {
		t.Errorf("PeekSubURLJSON() = %q", got)
	}
	if got := PeekSubURLJSON([]byte(minimalJSON)); got != "" {
		t.Errorf("PeekSubURLJSON() = %q, want empty", got)
	}
	if got := PeekSubURLJSON([]byte("{")); got != "" {
		t.Errorf("PeekSubURLJSON() on malformed = %q, want empty", got)
	}
}

// DefaultClientJSON должен сам себя разбирать: хост берёт его как стартовое
// состояние формы и дописывает peer со ссылками.
func TestDefaultClientJSONRoundTrip(t *testing.T) {
	var dto ClientJSON
	if err := json.Unmarshal([]byte(DefaultClientJSON()), &dto); err != nil {
		t.Fatalf("DefaultClientJSON() is not valid JSON: %v", err)
	}
	if !reflect.DeepEqual(dto, defaultClientJSON()) {
		t.Errorf("round-trip changed defaults:\n got %+v\nwant %+v", dto, defaultClientJSON())
	}

	dto.Peer = "1.2.3.4:5000"
	dto.VK.Links = []string{"CODE"}
	b, err := json.Marshal(dto)
	if err != nil {
		t.Fatal(err)
	}
	c, err := ParseClientJSON(b, "")
	if err != nil {
		t.Fatalf("ParseClientJSON(DefaultClientJSON + required) error = %v", err)
	}
	if c.Obf.Timing != 0 || c.TURN.N != DefaultStreams {
		t.Errorf("unexpected config from defaults: %+v", c)
	}
}

func TestClientJSONTimingMsMapsToDuration(t *testing.T) {
	in := `{"peer":"1.2.3.4:5000","vk":{"links":["A"]},` +
		`"obf":{"profile":"rtpopus","key":"` + testObfKey + `","timingMs":25}}`
	c, err := ParseClientJSON([]byte(in), "")
	if err != nil {
		t.Fatal(err)
	}
	if c.Obf.Timing != 25*time.Millisecond {
		t.Errorf("Obf.Timing = %v, want 25ms", c.Obf.Timing)
	}
}
