package wgconf

import (
	"encoding/base64"
	"strings"
	"testing"

	"github.com/samosvalishe/free-turn-proxy/internal/tunnel"
)

var (
	privKey = base64.StdEncoding.EncodeToString(bytesOf(0x01))
	pubKey  = base64.StdEncoding.EncodeToString(bytesOf(0x02))
	pskKey  = base64.StdEncoding.EncodeToString(bytesOf(0x03))
)

func bytesOf(b byte) []byte {
	out := make([]byte, tunnel.KeyLen)
	for i := range out {
		out[i] = b
	}
	return out
}

func minimalConf() string {
	return "[Interface]\nPrivateKey = " + privKey + "\nAddress = 10.8.0.2/32\n\n" +
		"[Peer]\nPublicKey = " + pubKey + "\nAllowedIPs = 0.0.0.0/0\n"
}

func TestParseMinimal(t *testing.T) {
	cfg, err := Parse(minimalConf())
	if err != nil {
		t.Fatalf("Parse() error = %v", err)
	}
	if cfg.PrivateKey.IsZero() || cfg.Peers[0].PublicKey.IsZero() {
		t.Fatal("keys not parsed")
	}
	if cfg.MTU != tunnel.DefaultMTU {
		t.Errorf("MTU = %d, want default %d", cfg.MTU, tunnel.DefaultMTU)
	}
	if len(cfg.Addresses) != 1 || cfg.Addresses[0].String() != "10.8.0.2/32" {
		t.Errorf("Addresses = %v", cfg.Addresses)
	}
	if cfg.Amnezia.Enabled() {
		t.Errorf("Amnezia must stay empty: %+v", cfg.Amnezia)
	}
}

func TestParseFullConfig(t *testing.T) {
	conf := `
# комментарий
[Interface]
PrivateKey = ` + privKey + `
Address = 10.8.0.2/32, fd00::2/128
DNS = 1.1.1.1, 8.8.8.8, example.org
MTU = 1420
Table = off          ; игнорируемый ключ wg-quick
PostUp = iptables -A FORWARD -j ACCEPT

[Peer]
PublicKey = ` + pubKey + `
PresharedKey = ` + pskKey + `
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = 1.2.3.4:51820
PersistentKeepalive = 25
`
	cfg, err := Parse(conf)
	if err != nil {
		t.Fatalf("Parse() error = %v", err)
	}
	if cfg.MTU != 1420 {
		t.Errorf("MTU = %d", cfg.MTU)
	}
	if len(cfg.Addresses) != 2 {
		t.Errorf("Addresses = %v", cfg.Addresses)
	}
	// example.org - search domain, а не резолвер: в DNS попасть не должен.
	if len(cfg.DNS) != 2 {
		t.Errorf("DNS = %v, want two addresses", cfg.DNS)
	}
	peer := cfg.Peers[0]
	if peer.PresharedKey.IsZero() || peer.Endpoint != "1.2.3.4:51820" || peer.Keepalive != 25 {
		t.Errorf("peer = %+v", peer)
	}
	if len(peer.AllowedIPs) != 2 {
		t.Errorf("AllowedIPs = %v", peer.AllowedIPs)
	}
}

func TestParseAmneziaParams(t *testing.T) {
	// awg-параметры живут в [Interface], поэтому конфиг собирается вручную:
	// после секции [Peer] те же ключи относились бы к пиру.
	conf := "[Interface]\nPrivateKey = " + privKey + "\n" +
		"Jc = 4\nJmin = 40\nJmax = 70\nS1 = 15\nS2 = 22\n" +
		"H1 = 1\nH2 = 2-5\nH3 = 3\nH4 = 4\nI1 = <b 0xf1><c>\nI5 = <r 10>\n\n" +
		"[Peer]\nPublicKey = " + pubKey + "\nAllowedIPs = 0.0.0.0/0\n"

	cfg, err := Parse(conf)
	if err != nil {
		t.Fatalf("Parse() error = %v", err)
	}
	a := cfg.Amnezia
	if !a.Enabled() {
		t.Fatal("Amnezia params not parsed")
	}
	if a.Jc != 4 || a.Jmin != 40 || a.Jmax != 70 || a.S1 != 15 || a.S2 != 22 {
		t.Errorf("amnezia ints = %+v", a)
	}
	if a.H1 != "1" || a.H2 != "2-5" || a.H3 != "3" || a.H4 != "4" {
		t.Errorf("amnezia headers = %+v", a)
	}
	if a.I[0] != "<b 0xf1><c>" || a.I[4] != "<r 10>" {
		t.Errorf("amnezia i-packets = %v", a.I)
	}
}

func TestParseMultiplePeers(t *testing.T) {
	conf := minimalConf() + "\n[Peer]\nPublicKey = " + pskKey + "\nAllowedIPs = 10.9.0.0/24\n"
	cfg, err := Parse(conf)
	if err != nil {
		t.Fatalf("Parse() error = %v", err)
	}
	if len(cfg.Peers) != 2 {
		t.Fatalf("peers = %d, want 2", len(cfg.Peers))
	}
	if cfg.Peers[1].AllowedIPs[0].String() != "10.9.0.0/24" {
		t.Errorf("second peer = %+v", cfg.Peers[1])
	}
}

func TestParseCaseInsensitiveKeys(t *testing.T) {
	conf := "[interface]\nprivatekey = " + privKey + "\nmtu = 1300\n\n" +
		"[PEER]\nPUBLICKEY = " + pubKey + "\nallowedips = 0.0.0.0/0\n"
	cfg, err := Parse(conf)
	if err != nil {
		t.Fatalf("Parse() error = %v", err)
	}
	if cfg.MTU != 1300 {
		t.Errorf("MTU = %d", cfg.MTU)
	}
}

// Голый адрес без маски клиенты пишут наравне с CIDR.
func TestParseBareAddress(t *testing.T) {
	conf := "[Interface]\nPrivateKey = " + privKey + "\nAddress = 10.8.0.2\n\n" +
		"[Peer]\nPublicKey = " + pubKey + "\nAllowedIPs = 0.0.0.0/0\n"
	cfg, err := Parse(conf)
	if err != nil {
		t.Fatalf("Parse() error = %v", err)
	}
	if cfg.Addresses[0].String() != "10.8.0.2/32" {
		t.Errorf("Addresses = %v", cfg.Addresses)
	}
}

func TestParseKeepaliveOff(t *testing.T) {
	conf := minimalConf() + "PersistentKeepalive = off\n"
	cfg, err := Parse(conf)
	if err != nil {
		t.Fatalf("Parse() error = %v", err)
	}
	if cfg.Peers[0].Keepalive != 0 {
		t.Errorf("Keepalive = %d, want 0", cfg.Peers[0].Keepalive)
	}
}

func TestParseErrors(t *testing.T) {
	cases := []struct {
		name string
		conf string
		want string
	}{
		{"unknown section", "[Router]\n", "unknown section"},
		{"key outside section", "PrivateKey = " + privKey + "\n", "outside of a section"},
		{"no equals sign", "[Interface]\nPrivateKey\n", "expected key = value"},
		{"unknown interface key", "[Interface]\nMagic = 1\n", "unknown [Interface] key"},
		{"unknown peer key", minimalConf() + "Magic = 1\n", "unknown [Peer] key"},
		{"bad key encoding", "[Interface]\nPrivateKey = not-base64!\n", "base64"},
		{"short key", "[Interface]\nPrivateKey = YWJj\n", "must be 32 bytes"},
		{"bad mtu", "[Interface]\nPrivateKey = " + privKey + "\nMTU = big\n", "mtu"},
		{"bad address", "[Interface]\nPrivateKey = " + privKey + "\nAddress = nowhere\n", "bad address"},
		{"bad amnezia int", "[Interface]\nPrivateKey = " + privKey + "\nJc = many\n", "jc"},
		{"no peers", "[Interface]\nPrivateKey = " + privKey + "\n", "at least one peer"},
		{"no private key", "[Peer]\nPublicKey = " + pubKey + "\nAllowedIPs = 0.0.0.0/0\n", "PrivateKey"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			_, err := Parse(tc.conf)
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("Parse() error = %v, want mention of %q", err, tc.want)
			}
		})
	}
}

// Разобранный конфиг обязан доходить до UAPI без дополнительной обработки.
func TestParseFeedsUAPI(t *testing.T) {
	cfg, err := Parse(minimalConf())
	if err != nil {
		t.Fatal(err)
	}
	uapi, err := tunnel.UAPI(cfg)
	if err != nil {
		t.Fatalf("UAPI() error = %v", err)
	}
	if !strings.Contains(uapi, "allowed_ip=0.0.0.0/0\n") {
		t.Errorf("UAPI() = %s", uapi)
	}
}
