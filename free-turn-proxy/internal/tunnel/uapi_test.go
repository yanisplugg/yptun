package tunnel

import (
	"net/netip"
	"strings"
	"testing"
)

func testKey(b byte) Key {
	var k Key
	for i := range k {
		k[i] = b
	}
	return k
}

func minimalConfig() *Config {
	cfg := Defaults()
	cfg.PrivateKey = testKey(0x01)
	cfg.Peers = []Peer{{
		PublicKey:  testKey(0x02),
		AllowedIPs: []netip.Prefix{netip.MustParsePrefix("0.0.0.0/0")},
	}}
	return &cfg
}

func TestUAPIVanillaWireGuard(t *testing.T) {
	got, err := UAPI(minimalConfig())
	if err != nil {
		t.Fatalf("UAPI() error = %v", err)
	}

	want := strings.Join([]string{
		"private_key=0101010101010101010101010101010101010101010101010101010101010101",
		"listen_port=0",
		"replace_peers=true",
		"public_key=0202020202020202020202020202020202020202020202020202020202020202",
		"endpoint=single-peer",
		"replace_allowed_ips=true",
		"allowed_ip=0.0.0.0/0",
		"",
	}, "\n")
	if got != want {
		t.Errorf("UAPI() =\n%s\nwant\n%s", got, want)
	}
}

func TestUAPIOmitsAmneziaWhenDisabled(t *testing.T) {
	got, err := UAPI(minimalConfig())
	if err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{"jc=", "jmin=", "jmax=", "s1=", "s2=", "s3=", "s4=", "h1=", "h2=", "h3=", "h4=", "i1="} {
		if strings.Contains(got, key) {
			t.Errorf("UAPI() contains %q for vanilla config:\n%s", key, got)
		}
	}
}

func TestUAPIAmneziaParams(t *testing.T) {
	cfg := minimalConfig()
	cfg.Amnezia = AmneziaParams{
		Jc: 4, Jmin: 40, Jmax: 70,
		S1: 15, S2: 22, S3: 0, S4: 0,
		H1: "1", H2: "2-5", H3: "3", H4: "4",
	}
	cfg.Amnezia.I[0] = "<b 0xf1><c><t>"
	cfg.Amnezia.I[4] = "<r 10>"

	got, err := UAPI(cfg)
	if err != nil {
		t.Fatalf("UAPI() error = %v", err)
	}
	for _, want := range []string{
		"jc=4", "jmin=40", "jmax=70",
		"s1=15", "s2=22",
		"h1=1", "h2=2-5", "h3=3", "h4=4",
		"i1=<b 0xf1><c><t>", "i5=<r 10>",
	} {
		if !strings.Contains(got, want+"\n") {
			t.Errorf("UAPI() missing %q:\n%s", want, got)
		}
	}
	for _, unwanted := range []string{"s3=", "s4=", "i2=", "i3=", "i4="} {
		if strings.Contains(got, unwanted) {
			t.Errorf("UAPI() contains %q for zero value:\n%s", unwanted, got)
		}
	}
}

func TestUAPIFullPeer(t *testing.T) {
	cfg := minimalConfig()
	cfg.Peers[0].PresharedKey = testKey(0x03)
	cfg.Peers[0].Endpoint = "1.2.3.4:51820"
	cfg.Peers[0].Keepalive = 25
	cfg.Peers[0].AllowedIPs = []netip.Prefix{
		netip.MustParsePrefix("10.0.0.0/24"),
		netip.MustParsePrefix("::/0"),
	}

	got, err := UAPI(cfg)
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{
		"preshared_key=0303030303030303030303030303030303030303030303030303030303030303",
		"endpoint=1.2.3.4:51820",
		"persistent_keepalive_interval=25",
		"allowed_ip=10.0.0.0/24",
		"allowed_ip=::/0",
	} {
		if !strings.Contains(got, want+"\n") {
			t.Errorf("UAPI() missing %q:\n%s", want, got)
		}
	}
}

func TestUAPIMultiplePeers(t *testing.T) {
	cfg := minimalConfig()
	cfg.Peers = append(cfg.Peers, Peer{
		PublicKey:  testKey(0x04),
		AllowedIPs: []netip.Prefix{netip.MustParsePrefix("10.1.0.0/16")},
	})

	got, err := UAPI(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Count(got, "public_key=") != 2 {
		t.Errorf("UAPI() must list both peers:\n%s", got)
	}
	if strings.Count(got, "replace_peers=true") != 1 {
		t.Errorf("replace_peers must appear once:\n%s", got)
	}
}

func TestUAPIRejectsInvalidConfig(t *testing.T) {
	cases := []struct {
		name   string
		mutate func(*Config)
	}{
		{"no private key", func(c *Config) { c.PrivateKey = Key{} }},
		{"no peers", func(c *Config) { c.Peers = nil }},
		{"no public key", func(c *Config) { c.Peers[0].PublicKey = Key{} }},
		{"no allowed ips", func(c *Config) { c.Peers[0].AllowedIPs = nil }},
		{"zero mtu", func(c *Config) { c.MTU = 0 }},
		{"negative keepalive", func(c *Config) { c.Peers[0].Keepalive = -1 }},
		{"partial junk", func(c *Config) { c.Amnezia = AmneziaParams{Jc: 4} }},
		{"junk range", func(c *Config) { c.Amnezia = AmneziaParams{Jc: 4, Jmin: 70, Jmax: 40} }},
		{"negative padding", func(c *Config) { c.Amnezia = AmneziaParams{S1: -1} }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			cfg := minimalConfig()
			tc.mutate(cfg)
			if _, err := UAPI(cfg); err == nil {
				t.Fatal("UAPI() error = nil, want validation error")
			}
		})
	}
}

func TestModeValid(t *testing.T) {
	for _, m := range []Mode{ModeNone, ModeWG, ModeAWG} {
		if !m.Valid() {
			t.Errorf("Mode(%q).Valid() = false", m)
		}
	}
	if Mode("openvpn").Valid() {
		t.Error("unknown mode reported as valid")
	}
}

func TestDefaultsMTU(t *testing.T) {
	if got := Defaults().MTU; got != DefaultMTU {
		t.Errorf("Defaults().MTU = %d, want %d", got, DefaultMTU)
	}
}

func TestUAPIAWG3Params(t *testing.T) {
	cfg := minimalConfig()
	cfg.Amnezia = AmneziaParams{
		S1: 12, S2: 12, S3: 12, S4: 12,
		HeaderProtectionKey:    testKey(0x04),
		ContentPaddingAddition: "10-40",
		RekeyAfterTime:         "90-140",
		RekeyTimeout:           "5",
		RejectAfterTime:        "180",
		KeepaliveTimeout:       "10-15",
		MaxHandshakeAttempts:   "18",
		RandomTrailers:         true,
		DisableCookies:         true,
	}

	got, err := UAPI(cfg)
	if err != nil {
		t.Fatalf("UAPI() error = %v", err)
	}
	for _, want := range []string{
		"header_protection_key=" + strings.Repeat("04", KeyLen),
		"content_padding_addition=10-40",
		"rekey_after_time=90-140",
		"rekey_timeout=5",
		"reject_after_time=180",
		"keepalive_timeout=10-15",
		"max_handshake_attempts=18",
		"random_trailers=true",
		"disable_cookies=true",
	} {
		if !strings.Contains(got, want+"\n") {
			t.Errorf("UAPI() missing %q:\n%s", want, got)
		}
	}
}

func TestUAPIOmitsAWG3Defaults(t *testing.T) {
	cfg := minimalConfig()
	cfg.Amnezia = AmneziaParams{Jc: 4, Jmin: 40, Jmax: 70}

	got, err := UAPI(cfg)
	if err != nil {
		t.Fatalf("UAPI() error = %v", err)
	}
	for _, unwanted := range []string{
		"header_protection_key=", "content_padding_addition=",
		"rekey_after_time=", "rekey_timeout=", "reject_after_time=",
		"keepalive_timeout=", "max_handshake_attempts=",
		"random_trailers=", "disable_cookies=",
	} {
		if strings.Contains(got, unwanted) {
			t.Errorf("UAPI() contains %q for zero value:\n%s", unwanted, got)
		}
	}
}

func TestValidateRange(t *testing.T) {
	for _, ok := range []string{"", "0", "25", "10-40", "7-7"} {
		if err := ValidateRange(ok); err != nil {
			t.Errorf("ValidateRange(%q) = %v, want nil", ok, err)
		}
	}
	for _, bad := range []string{"40-10", "a", "-5", "1-", "1-2-3", "off"} {
		if err := ValidateRange(bad); err == nil {
			t.Errorf("ValidateRange(%q) = nil, want error", bad)
		}
	}
}

func TestValidateHeaderProtectionNeedsPadding(t *testing.T) {
	cfg := minimalConfig()
	cfg.Amnezia = AmneziaParams{
		S1: MinHeaderProtectionPadding, S2: MinHeaderProtectionPadding,
		S3: MinHeaderProtectionPadding, S4: MinHeaderProtectionPadding - 1,
		HeaderProtectionKey: testKey(0x04),
	}
	if err := cfg.Validate(); err == nil {
		t.Fatal("Validate() = nil, want error for short S4")
	}

	cfg.Amnezia.S4 = MinHeaderProtectionPadding
	if err := cfg.Validate(); err != nil {
		t.Fatalf("Validate() = %v, want nil", err)
	}
}

func TestValidateRejectsBadRange(t *testing.T) {
	cfg := minimalConfig()
	cfg.Amnezia = AmneziaParams{RekeyTimeout: "40-10"}
	if err := cfg.Validate(); err == nil {
		t.Fatal("Validate() = nil, want error for inverted range")
	}
}
