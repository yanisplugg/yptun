package mobile

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"math"
	"net"
	"strings"
	"testing"

	"github.com/samosvalishe/free-turn-proxy/internal/config"
	"github.com/samosvalishe/free-turn-proxy/internal/logx"
	"github.com/samosvalishe/free-turn-proxy/internal/tunnel"
)

func wgKey(b byte) string {
	raw := make([]byte, tunnel.KeyLen)
	for i := range raw {
		raw[i] = b
	}
	return base64.StdEncoding.EncodeToString(raw)
}

func wgConf() string {
	return "[Interface]\nPrivateKey = " + wgKey(1) + "\nAddress = 10.8.0.2/32\n\n" +
		"[Peer]\nPublicKey = " + wgKey(2) + "\nAllowedIPs = 0.0.0.0/0\n" +
		"Endpoint = 1.2.3.4:51820\n"
}

func awgConf() string {
	return "[Interface]\nPrivateKey = " + wgKey(1) + "\nAddress = 10.8.0.2/32\n" +
		"Jc = 4\nJmin = 40\nJmax = 70\nS1 = 15\n\n" +
		"[Peer]\nPublicKey = " + wgKey(2) + "\nAllowedIPs = 0.0.0.0/0\n"
}

func tunnelConfigJSON(t *testing.T, mode, conf string) string {
	t.Helper()
	payload := map[string]any{
		"peer":     "1.2.3.4:5000",
		"clientId": "deadbeef",
		"vk":       map[string]any{"links": []string{"CODE"}},
		"tunnel":   map[string]any{"mode": mode, "config": conf},
	}
	b, err := json.Marshal(payload)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func parsedConfig(t *testing.T, jsonCfg string) *config.Client {
	t.Helper()
	cfg, err := config.ParseClientJSON([]byte(jsonCfg), "")
	if err != nil {
		t.Fatalf("ParseClientJSON() error = %v", err)
	}
	return cfg
}

func TestBuildTunnelWiresPipeToBind(t *testing.T) {
	cfg := parsedConfig(t, tunnelConfigJSON(t, "awg", awgConf()))

	parts, tunCfg, err := buildTunnel(cfg, logx.Nop())
	if err != nil {
		t.Fatalf("buildTunnel() error = %v", err)
	}
	t.Cleanup(func() {
		parts.close()
		_ = parts.relaySide.Close()
	})

	if parts.bind == nil || parts.backend == nil || parts.relaySide == nil {
		t.Fatalf("parts = %+v", parts)
	}
	if !tunCfg.Amnezia.Enabled() {
		t.Error("awg params dropped in mode=awg")
	}
	if tunCfg.MTU != tunnel.DefaultMTU {
		t.Errorf("MTU = %d, want %d", tunCfg.MTU, tunnel.DefaultMTU)
	}
	if tunCfg.Peers[0].Endpoint != "" {
		t.Errorf("Endpoint = %q, want empty", tunCfg.Peers[0].Endpoint)
	}
}

// Проверка освобождения пайпов при закрытии TunnelParts.
func TestTunnelPartsCloseReleasesPipes(t *testing.T) {
	cfg := parsedConfig(t, tunnelConfigJSON(t, "wg", wgConf()))

	parts, _, err := buildTunnel(cfg, logx.Nop())
	if err != nil {
		t.Fatalf("buildTunnel() error = %v", err)
	}

	parts.close()
	parts.close()

	if _, err := parts.relaySide.WriteTo([]byte{1}, nil); !errors.Is(err, net.ErrClosed) {
		t.Errorf("relaySide.WriteTo() error = %v, want %v", err, net.ErrClosed)
	}
	if _, err := parts.deviceSide.WriteTo([]byte{1}, nil); !errors.Is(err, net.ErrClosed) {
		t.Errorf("deviceSide.WriteTo() error = %v, want %v", err, net.ErrClosed)
	}
}

// Режим wg отключает параметры AmneziaWG.
func TestBuildTunnelStripsAmneziaInWGMode(t *testing.T) {
	cfg := parsedConfig(t, tunnelConfigJSON(t, "wg", awgConf()))

	parts, tunCfg, err := buildTunnel(cfg, logx.Nop())
	if err != nil {
		t.Fatalf("buildTunnel() error = %v", err)
	}
	t.Cleanup(func() {
		parts.close()
		_ = parts.relaySide.Close()
	})

	if tunCfg.Amnezia.Enabled() {
		t.Errorf("amnezia params kept in mode=wg: %+v", tunCfg.Amnezia)
	}
}

func TestBuildTunnelRejectsBrokenConfig(t *testing.T) {
	cfg := parsedConfig(t, tunnelConfigJSON(t, "wg", wgConf()))
	cfg.Tunnel.Config = "[Interface]\nPrivateKey = nonsense\n"

	if _, _, err := buildTunnel(cfg, logx.Nop()); err == nil {
		t.Fatal("buildTunnel() error = nil for broken config")
	}
}

func TestStartRejectsTunnelMode(t *testing.T) {
	t.Cleanup(Stop)
	err := Start(tunnelConfigJSON(t, "awg", awgConf()))
	if !errors.Is(err, ErrTunnelRequiresStartTunnel) {
		t.Fatalf("Start() error = %v, want ErrTunnelRequiresStartTunnel", err)
	}
}

func TestStartTunnelRejectsBadFD(t *testing.T) {
	t.Cleanup(Stop)
	if err := StartTunnel(tunnelConfigJSON(t, "wg", wgConf()), -1); err == nil {
		t.Fatal("StartTunnel() error = nil for negative fd")
	}
}

func TestValidateConfigRejectsTunnelWithoutConfig(t *testing.T) {
	msg := ValidateConfig(tunnelConfigJSON(t, "wg", ""))
	if !strings.Contains(msg, "tunnel config") {
		t.Fatalf("ValidateConfig() = %q, want missing-config error", msg)
	}
}

func TestValidateConfigRejectsUnknownTunnelMode(t *testing.T) {
	msg := ValidateConfig(tunnelConfigJSON(t, "openvpn", wgConf()))
	if !strings.Contains(msg, "invalid tunnel mode") {
		t.Fatalf("ValidateConfig() = %q, want invalid-mode error", msg)
	}
}

func TestParseTunnelConfig(t *testing.T) {
	conf := "[Interface]\nPrivateKey = " + wgKey(1) + "\nAddress = 10.8.0.2/32, fd00::2/128\n" +
		"DNS = 1.1.1.1, 8.8.8.8\nMTU = 1420\n\n" +
		"[Peer]\nPublicKey = " + wgKey(2) + "\nAllowedIPs = 0.0.0.0/0\n\n" +
		"[Peer]\nPublicKey = " + wgKey(3) + "\nAllowedIPs = 0.0.0.0/0, 10.9.0.0/24\n"

	p, err := ParseTunnelConfig(conf, 0)
	if err != nil {
		t.Fatalf("ParseTunnelConfig() error = %v", err)
	}
	if p.Addresses != "10.8.0.2/32,fd00::2/128" {
		t.Errorf("Addresses = %q", p.Addresses)
	}
	if p.DNS != "1.1.1.1,8.8.8.8" {
		t.Errorf("DNS = %q", p.DNS)
	}
	if p.AllowedIPs != "0.0.0.0/0,10.9.0.0/24" {
		t.Errorf("AllowedIPs = %q", p.AllowedIPs)
	}
	if p.MTU != 1420 {
		t.Errorf("MTU = %d, want 1420", p.MTU)
	}
}

func TestParseTunnelConfigMTUOverride(t *testing.T) {
	p, err := ParseTunnelConfig(wgConf(), 1280)
	if err != nil {
		t.Fatalf("ParseTunnelConfig() error = %v", err)
	}
	if p.MTU != 1280 {
		t.Errorf("MTU = %d, want 1280", p.MTU)
	}
}

func TestParseTunnelConfigRejectsBroken(t *testing.T) {
	if _, err := ParseTunnelConfig("[Interface]\nPrivateKey = nonsense\n", 0); err == nil {
		t.Fatal("ParseTunnelConfig() error = nil for broken config")
	}
}

func TestTunnelStatsWithoutTunnel(t *testing.T) {
	Stop()
	st := TunnelStats()
	if st.Up || st.HandshakeAgeSec != -1 {
		t.Fatalf("TunnelStats() = %+v, want down", st)
	}
}

// Проверка насыщения при math.MaxUint64 для gomobile int64.
func TestClampToInt64Overflow(t *testing.T) {
	if got := clampToInt64(math.MaxUint64); got != math.MaxInt64 {
		t.Errorf("clampToInt64(MaxUint64) = %d, want MaxInt64 (%d)", got, int64(math.MaxInt64))
	}
	if got := clampToInt64(0); got != 0 {
		t.Errorf("clampToInt64(0) = %d, want 0", got)
	}
	if got := clampToInt64(uint64(math.MaxInt64)); got != math.MaxInt64 {
		t.Errorf("clampToInt64(MaxInt64) = %d, want MaxInt64", got)
	}
}

// В tcp-режиме ядро tun не читает: принятый fd остался бы установленным вхолостую.
func TestStartTunnelRejectsTCPMode(t *testing.T) {
	const cfg = `{"peer":"1.2.3.4:5000","clientId":"deadbeef","proxy":{"mode":"tcp"},"vk":{"links":["https://vk.ru/call/join/CODE"]}}`
	if err := StartTunnel(cfg, 7); !errors.Is(err, ErrTCPModeRequiresStart) {
		t.Fatalf("StartTunnel() error = %v, want ErrTCPModeRequiresStart", err)
	}
}
