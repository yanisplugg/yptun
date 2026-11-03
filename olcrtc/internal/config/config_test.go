package config

import (
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"github.com/openlibrecommunity/olcrtc/internal/app/session"
)

const (
	testModeSrv   = "srv"
	testProvider  = "wbstream"
	testRoomID    = "r1"
	testCryptoKey = "deadbeef"
	testDNSServer = "8.8.8.8:53"
)

func TestLoadAndApply(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "olcrtc.yaml")
	body := `
mode: srv
auth:
  provider: wbstream
room:
  id: r1
crypto:
  key: deadbeef
net:
  transport: datachannel
  dns: 8.8.8.8:53
socks:
  host: 127.0.0.1
  port: 1080
  user: u
  pass: p
vp8:
  fps: 25
  batch_size: 4
liveness:
  interval: 2s
  timeout: 500ms
  failures: 4
lifecycle:
  max_session_duration: 6h
traffic:
  max_payload_size: 4096
  min_delay: 5ms
  max_delay: 30ms
gen:
  amount: 3
debug: true
`
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}

	f, err := Load(path)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	requireLoadedFile(t, f)

	got := Apply(f)
	requireAppliedConfig(t, got)
}

func requireLoadedFile(t *testing.T, f File) {
	t.Helper()
	if f.Mode != testModeSrv {
		t.Fatalf("Mode = %q, want %q", f.Mode, testModeSrv)
	}
	if f.Auth.Provider != testProvider {
		t.Fatalf("Auth.Provider = %q, want %q", f.Auth.Provider, testProvider)
	}
	if f.Room.ID != testRoomID {
		t.Fatalf("Room.ID = %q, want %q", f.Room.ID, testRoomID)
	}
	if f.Crypto.Key != testCryptoKey {
		t.Fatalf("Crypto.Key = %q, want %q", f.Crypto.Key, testCryptoKey)
	}
}

func requireAppliedConfig(t *testing.T, got session.Config) {
	t.Helper()
	want := session.Config{
		Mode:                  testModeSrv,
		Provider:              testProvider,
		RoomID:                testRoomID,
		KeyHex:                testCryptoKey,
		Transport:             "datachannel",
		DNSServer:             testDNSServer,
		SOCKSHost:             "127.0.0.1",
		SOCKSPort:             1080,
		SOCKSUser:             "u",
		SOCKSPass:             "p",
		VP8:                   session.VP8Config{FPS: 25, BatchSize: 4},
		LivenessInterval:      "2s",
		LivenessTimeout:       "500ms",
		LivenessFailures:      4,
		MaxSessionDuration:    "6h",
		TrafficMaxPayloadSize: 4096,
		TrafficMinDelay:       "5ms",
		TrafficMaxDelay:       "30ms",
		Amount:                3,
		UDPDisabled:           true,
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("Apply produced wrong config: %+v, want %+v", got, want)
	}
}

func TestApplySettingsOverlaysNonZeroFields(t *testing.T) {
	base := session.Config{
		KeyHex:    "kept",
		SOCKSHost: "kept-host",
		SOCKSPort: 9999,
	}

	got := ApplySettings(base, Settings{
		Crypto: Crypto{Key: "override"},
		SOCKS:  SOCKS{Port: 1234},
	})

	if got.KeyHex != "override" {
		t.Errorf("KeyHex: got %q, want override", got.KeyHex)
	}

	if got.SOCKSPort != 1234 {
		t.Errorf("SOCKSPort: got %d, want 1234", got.SOCKSPort)
	}

	if got.SOCKSHost != "kept-host" {
		t.Errorf("SOCKSHost: got %q, want kept-host (zero override keeps base)", got.SOCKSHost)
	}
}

func TestApplyMapsEverySection(t *testing.T) {
	got := Apply(File{
		Mode: testModeSrv,
		Gen:  Gen{Amount: 2},
		Settings: Settings{
			Auth:      Auth{Provider: testProvider, Token: "acct"},
			Room:      Room{ID: testRoomID, Channel: "chan"},
			Engine:    Engine{Name: "livekit", URL: "wss://x", Token: "tok"},
			SOCKS:     SOCKS{ProxyAddr: "127.0.0.1", ProxyPort: 1080, ProxyUser: "pu", ProxyPass: "pp"},
			Video:     Video{Width: 640, Height: 480, QRSize: 128, Codec: "tile", TileModule: 4, TileRS: 2},
			SEI:       SEI{FPS: 15, BatchSize: 8, FragmentSize: 700, AckTimeoutMS: 1500},
			Lifecycle: Lifecycle{MaxSessionDuration: "1h"},
		},
	})

	want := session.Config{
		Mode:               testModeSrv,
		Amount:             2,
		Provider:           testProvider,
		ProviderToken:      "acct",
		RoomID:             testRoomID,
		ChannelID:          "chan",
		Engine:             "livekit",
		URL:                "wss://x",
		Token:              "tok",
		SOCKSProxyAddr:     "127.0.0.1",
		SOCKSProxyPort:     1080,
		SOCKSProxyUser:     "pu",
		SOCKSProxyPass:     "pp",
		Video:              session.VideoConfig{Width: 640, Height: 480, QRSize: 128, Codec: "tile", TileModule: 4, TileRS: 2},
		SEI:                session.SEIConfig{FPS: 15, BatchSize: 8, FragmentSize: 700, AckTimeoutMS: 1500},
		MaxSessionDuration: "1h",
		UDPDisabled:        true,
	}

	if !reflect.DeepEqual(got, want) {
		t.Fatalf("Apply() = %+v, want %+v", got, want)
	}
}

func TestLoadRejectsUnknownKeys(t *testing.T) {
	path := filepath.Join(t.TempDir(), "olcrtc.yaml")
	if err := os.WriteFile(path, []byte("mode: srv\nlinnk: direct\n"), 0o600); err != nil {
		t.Fatalf("write config: %v", err)
	}

	if _, err := Load(path); err == nil {
		t.Fatal("Load() error = nil, want failure for an unknown key")
	}
}

func TestLoadAcceptsIgnoredLegacyFields(t *testing.T) {
	path := filepath.Join(t.TempDir(), "olcrtc.yaml")
	body := `
mode: cnc
link: direct
ffmpeg: /usr/bin/ffmpeg
video:
  bitrate: 5000k
  hw: nvenc
`
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatalf("write config: %v", err)
	}

	file, err := Load(path)
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if file.Link != "direct" || file.FFmpeg != "/usr/bin/ffmpeg" ||
		file.Video.Bitrate != "5000k" || file.Video.HW != "nvenc" {
		t.Fatalf("legacy fields = %#v", file)
	}
	if got := Apply(file); !reflect.DeepEqual(got, session.Config{Mode: "cnc", UDPDisabled: true}) {
		t.Fatalf("Apply() mapped ignored legacy fields: %#v", got)
	}
}

func TestUDPRelayIsOptIn(t *testing.T) {
	if got := Apply(File{}); !got.UDPDisabled {
		t.Fatal("no udp block: UDPDisabled = false, want true")
	}
	enabled := true
	if got := Apply(File{Settings: Settings{UDP: UDP{Enabled: &enabled}}}); got.UDPDisabled {
		t.Fatal("udp.enabled=true: UDPDisabled = true, want false")
	}
	disabled := true
	got := Apply(File{Settings: Settings{UDP: UDP{Enabled: &enabled, Disabled: &disabled}}})
	if !got.UDPDisabled {
		t.Fatal("udp.enabled+disabled: UDPDisabled = false, want disabled to win")
	}
	maxFlows := 7
	if got := Apply(File{Settings: Settings{UDP: UDP{MaxFlows: &maxFlows}}}); got.UDPMaxFlows != 7 {
		t.Fatalf("UDPMaxFlows = %d, want 7", got.UDPMaxFlows)
	}
}

func TestApplyProfileOverridesOnlyTheUDPKeysItNames(t *testing.T) {
	base := Apply(File{})
	enabled := true
	if got := ApplyProfile(base, Profile{Settings: Settings{UDP: UDP{Enabled: &enabled}}}); got.UDPDisabled {
		t.Fatal("profile udp.enabled=true over the gated base: UDPDisabled = true, want false")
	}
	on := session.Config{UDPDisabled: false, UDPMaxFlows: 10}
	if got := ApplyProfile(on, Profile{}); got.UDPDisabled || got.UDPMaxFlows != 10 {
		t.Fatalf("profile without a udp block changed it: %+v", got)
	}
	zero := 0
	notDisabled := false
	got := ApplyProfile(session.Config{UDPDisabled: true, UDPMaxFlows: 10},
		Profile{Settings: Settings{UDP: UDP{Disabled: &notDisabled, MaxFlows: &zero}}})
	if !got.UDPDisabled {
		t.Fatal("a bare disabled: false turned the relay on; only enabled: true may")
	}
	if got.UDPMaxFlows != 0 {
		t.Fatalf("UDPMaxFlows = %d, want the profile's explicit 0", got.UDPMaxFlows)
	}
}

func TestApplyMapsStatsListen(t *testing.T) {
	got := Apply(File{Mode: testModeSrv, Stats: Stats{Listen: "127.0.0.1:9464"}})
	if got.StatsListen != "127.0.0.1:9464" {
		t.Fatalf("StatsListen = %q, want the stats.listen value", got.StatsListen)
	}
}

func TestLoadCryptoKeysListAndFile(t *testing.T) {
	dir := t.TempDir()
	ring := []string{
		"0011223344556677889900112233445566778899001122334455667788990011",
		"aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899",
		"ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100",
	}

	inline := filepath.Join(dir, "inline.yaml")
	body := "mode: srv\ncrypto:\n  keys:\n    - " + ring[0] + "\n    - " + ring[1] + "\n    - " + ring[2] + "\n"
	if err := os.WriteFile(inline, []byte(body), 0o600); err != nil {
		t.Fatalf("write config: %v", err)
	}
	file, err := Load(inline)
	if err != nil {
		t.Fatalf("Load(inline) error = %v", err)
	}
	if got := Apply(file).KeysHex; !reflect.DeepEqual(got, ring) {
		t.Fatalf("inline KeysHex = %v, want %v", got, ring)
	}

	keysFile := filepath.Join(dir, "keys.txt")
	listing := "# the room key\n" + ring[0] + "\n\n  " + ring[1] + "  \n# a per-user key\n" + ring[2] + "\n"
	if err = os.WriteFile(keysFile, []byte(listing), 0o600); err != nil {
		t.Fatalf("write keys file: %v", err)
	}
	fromFile := filepath.Join(dir, "file.yaml")
	if err = os.WriteFile(fromFile, []byte("mode: srv\ncrypto:\n  keys_file: keys.txt\n"), 0o600); err != nil {
		t.Fatalf("write config: %v", err)
	}
	file, err = Load(fromFile)
	if err != nil {
		t.Fatalf("Load(keys_file) error = %v", err)
	}
	if got := Apply(file).KeysHex; !reflect.DeepEqual(got, ring) {
		t.Fatalf("keys_file KeysHex = %v, want %v", got, ring)
	}
	if file.Crypto.KeysFile != "" {
		t.Fatalf("Load kept keys_file = %q after reading it", file.Crypto.KeysFile)
	}
}

func TestLoadCryptoKeysConflicts(t *testing.T) {
	const key = "0011223344556677889900112233445566778899001122334455667788990011"
	bodies := map[string]string{
		"keys+key":       "mode: srv\ncrypto:\n  key: " + key + "\n  keys:\n    - " + key + "\n",
		"keys+key_file":  "mode: srv\ncrypto:\n  key_file: k\n  keys:\n    - " + key + "\n",
		"keys+keys_file": "mode: srv\ncrypto:\n  keys_file: k\n  keys:\n    - " + key + "\n",
	}
	for name, body := range bodies {
		t.Run(name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "olcrtc.yaml")
			if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
				t.Fatalf("write config: %v", err)
			}
			if _, err := Load(path); !errors.Is(err, ErrCryptoKeysConflict) {
				t.Fatalf("Load() error = %v, want %v", err, ErrCryptoKeysConflict)
			}
		})
	}
}

func TestLoadAndApplyProfile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "olcrtc.yaml")
	body := `
mode: srv
crypto:
  key: shared-key
net:
  dns: 8.8.8.8:53
liveness:
  interval: 5s
  timeout: 2s
  failures: 5
lifecycle:
  max_session_duration: 6h
traffic:
  max_payload_size: 8192
  min_delay: 10ms
  max_delay: 40ms
profiles:
  - name: wb-vp8
    auth:
      provider: wbstream
    room:
      id: wb-room
    net:
      transport: vp8channel
    vp8:
      fps: 30
    liveness:
      interval: 1s
    lifecycle:
      max_session_duration: 30m
    traffic:
      max_payload_size: 4096
      max_delay: 20ms
  - name: jitsi-dc
    auth:
      provider: jitsi
    room:
      id: https://meet.example/room
    net:
      transport: datachannel
      dns: 8.8.8.8:53
failover:
  retry_delay: 100ms
  max_cycles: 2
`
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatalf("write config: %v", err)
	}

	f, err := Load(path)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if len(f.Profiles) != 2 {
		t.Fatalf("profiles = %d, want 2", len(f.Profiles))
	}
	if f.Failover.RetryDelay != "100ms" || f.Failover.MaxCycles != 2 {
		t.Fatalf("Failover = %+v, want retry_delay 100ms max_cycles 2", f.Failover)
	}

	base := Apply(f)
	first := ApplyProfile(base, f.Profiles[0])
	if first.Provider != "wbstream" || first.Transport != "vp8channel" || first.RoomID != "wb-room" {
		t.Fatalf("first profile = %+v", first)
	}
	if first.KeyHex != "shared-key" || first.DNSServer != testDNSServer || first.VP8.FPS != 30 ||
		first.LivenessInterval != "1s" || first.LivenessTimeout != "2s" || first.LivenessFailures != 5 ||
		first.MaxSessionDuration != "30m" || first.TrafficMaxPayloadSize != 4096 ||
		first.TrafficMinDelay != "10ms" || first.TrafficMaxDelay != "20ms" {
		t.Fatalf("first inherited/overlaid fields = %+v", first)
	}
	second := ApplyProfile(base, f.Profiles[1])
	if second.Provider != "jitsi" || second.Transport != "datachannel" ||
		second.RoomID != "https://meet.example/room" || second.DNSServer != testDNSServer {
		t.Fatalf("second profile = %+v", second)
	}
	if second.LivenessInterval != "5s" || second.LivenessTimeout != "2s" || second.LivenessFailures != 5 ||
		second.MaxSessionDuration != "6h" || second.TrafficMaxPayloadSize != 8192 ||
		second.TrafficMinDelay != "10ms" || second.TrafficMaxDelay != "40ms" {
		t.Fatalf("second lifecycle/liveness fields = %+v", second)
	}
}

func TestLoadProfileCryptoKeyFile(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "profile.key"), []byte(testCryptoKey+"\n"), 0o600); err != nil {
		t.Fatalf("write key: %v", err)
	}
	path := filepath.Join(dir, "olcrtc.yaml")
	body := `
profiles:
  - name: file-key
    crypto:
      key_file: profile.key
`
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatalf("write config: %v", err)
	}

	f, err := Load(path)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if got := f.Profiles[0].Crypto.Key; got != testCryptoKey {
		t.Fatalf("profile key = %q, want %q", got, testCryptoKey)
	}
}

func TestLoadCryptoKeyFileRelativeToConfig(t *testing.T) {
	dir := t.TempDir()
	keyPath := filepath.Join(dir, "secret.key")
	if err := os.WriteFile(keyPath, []byte(testCryptoKey+"\n"), 0o600); err != nil {
		t.Fatalf("write key: %v", err)
	}
	path := filepath.Join(dir, "olcrtc.yaml")
	body := `
mode: srv
crypto:
  key_file: secret.key
`
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatalf("write config: %v", err)
	}

	f, err := Load(path)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if f.Crypto.Key != testCryptoKey {
		t.Fatalf("Crypto.Key = %q, want %q", f.Crypto.Key, testCryptoKey)
	}
}

func TestLoadCryptoKeyFileConflict(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "olcrtc.yaml")
	body := `
crypto:
  key: deadbeef
  key_file: secret.key
`
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatalf("write config: %v", err)
	}

	_, err := Load(path)
	if !errors.Is(err, ErrCryptoKeyConflict) {
		t.Fatalf("Load() error = %v, want %v", err, ErrCryptoKeyConflict)
	}
}

func TestLoadCryptoKeyFileEmpty(t *testing.T) {
	dir := t.TempDir()
	keyPath := filepath.Join(dir, "secret.key")
	if err := os.WriteFile(keyPath, []byte("\n"), 0o600); err != nil {
		t.Fatalf("write key: %v", err)
	}
	path := filepath.Join(dir, "olcrtc.yaml")
	body := `
crypto:
  key_file: secret.key
`
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatalf("write config: %v", err)
	}

	_, err := Load(path)
	if !errors.Is(err, ErrCryptoKeyFileEmpty) {
		t.Fatalf("Load() error = %v, want %v", err, ErrCryptoKeyFileEmpty)
	}
}

func TestLoadMissing(t *testing.T) {
	_, err := Load(filepath.Join(t.TempDir(), "nope.yaml"))
	if err == nil {
		t.Fatal("expected error for missing file")
	}
}

func TestLoadInvalidUTF8(t *testing.T) {
	path := filepath.Join(t.TempDir(), "olcrtc.yaml")
	if err := os.WriteFile(path, []byte{'m', 'o', 'd', 'e', ':', ' ', 0xff}, 0o600); err != nil {
		t.Fatalf("write config: %v", err)
	}

	_, err := Load(path)
	if !errors.Is(err, ErrConfigInvalidUTF8) {
		t.Fatalf("Load() error = %v, want invalid UTF-8 error", err)
	}
}

func TestLoadRouteInlineAndFile(t *testing.T) {
	dir := t.TempDir()
	const listing = "# ru\ndomain:ru\n1.2.3.0/24\n"
	if err := os.WriteFile(filepath.Join(dir, "rules.txt"), []byte(listing), 0o600); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "olcrtc.yaml")
	body := "mode: cnc\nroute:\n  direct:\n    - full:a.test\n  direct_file: rules.txt\n"
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	file, err := Load(path)
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if file.Route.DirectFile != "" {
		t.Fatalf("Load() left direct_file = %q, want it read into direct", file.Route.DirectFile)
	}
	if got, want := Apply(file).DirectRules, "full:a.test\n"+listing; got != want {
		t.Fatalf("DirectRules = %q, want %q", got, want)
	}
}

func TestLoadRouteDirectFileMissing(t *testing.T) {
	path := filepath.Join(t.TempDir(), "olcrtc.yaml")
	if err := os.WriteFile(path, []byte("mode: cnc\nroute:\n  direct_file: nope.txt\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := Load(path); err == nil {
		t.Fatal("Load() accepted a missing direct_file")
	}
}

func TestApplyProfileOverridesRouteOnlyWhenNamed(t *testing.T) {
	base := Apply(File{Mode: "cnc", Settings: Settings{Route: Route{Direct: []string{"domain:ru"}}}})
	if base.DirectRules != "domain:ru\n" {
		t.Fatalf("base DirectRules = %q", base.DirectRules)
	}
	kept := ApplyProfile(base, Profile{Name: "same"})
	if kept.DirectRules != "domain:ru\n" {
		t.Fatalf("profile without route changed DirectRules to %q", kept.DirectRules)
	}
	replaced := ApplyProfile(base, Profile{
		Name: "by", Settings: Settings{Route: Route{Direct: []string{"domain:by", "10.0.0.0/8"}}},
	})
	if replaced.DirectRules != "domain:by\n10.0.0.0/8\n" {
		t.Fatalf("profile route = %q", replaced.DirectRules)
	}
}

func TestApplySettingsDTLSProfileInheritsAndOverrides(t *testing.T) {
	base := ApplySettings(session.Config{UDPDisabled: true}, Settings{DTLS: DTLS{Profile: "chrome-linux-138-compat-v1"}})
	if base.DTLSProfile != "chrome-linux-138-compat-v1" {
		t.Fatalf("base profile not applied: %q", base.DTLSProfile)
	}
	// A profile without dtls inherits the base value.
	inherited := ApplyProfile(base, Profile{Settings: Settings{Room: Room{ID: "r2"}}})
	if inherited.DTLSProfile != "chrome-linux-138-compat-v1" {
		t.Fatalf("profile lost inherited dtls profile: %q", inherited.DTLSProfile)
	}
	// An explicit off overrides the inherited profile.
	off := ApplyProfile(base, Profile{Settings: Settings{DTLS: DTLS{Profile: "off"}}})
	if off.DTLSProfile != "off" {
		t.Fatalf("explicit off did not override: %q", off.DTLSProfile)
	}
}
