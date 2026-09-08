package mdnsmobile

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

// The wrapper's whole job is turning the host's flat strings into the two files the upstream loader
// insists on. If that mapping drifts, the tunnel fails at Bootstrap with a config error and the app
// only sees "did not open".
func TestWriteConfigFilesProducesALoadableConfig(t *testing.T) {
	dir := t.TempDir()
	client, err := NewClient(
		dir,
		"v.example.com, v2.example.com\nv.example.com",
		"  secret  ",
		EncryptionChaCha20,
		"1.1.1.1, 8.8.8.8:5300",
		"127.0.0.1:10800",
		"user",
		"pass",
	)
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	client.SetResolverBalancingStrategy(4)
	client.SetPacketDuplication(3)

	if err := client.writeConfigFiles(); err != nil {
		t.Fatalf("writeConfigFiles: %v", err)
	}

	resolvers, err := os.ReadFile(filepath.Join(dir, "client_resolvers.txt"))
	if err != nil {
		t.Fatalf("resolver file: %v", err)
	}
	if got, want := string(resolvers), "1.1.1.1\n8.8.8.8:5300\n"; got != want {
		t.Fatalf("resolver file = %q, want %q", got, want)
	}

	raw, err := os.ReadFile(filepath.Join(dir, "client_config.json"))
	if err != nil {
		t.Fatalf("config file: %v", err)
	}
	var cfg map[string]any
	if err := json.Unmarshal(raw, &cfg); err != nil {
		t.Fatalf("config json: %v", err)
	}

	domains, _ := cfg["DOMAINS"].([]any)
	if len(domains) != 2 || domains[0] != "v.example.com" || domains[1] != "v2.example.com" {
		t.Fatalf("DOMAINS = %v, want the two distinct domains", cfg["DOMAINS"])
	}
	if cfg["ENCRYPTION_KEY"] != "secret" {
		t.Fatalf("ENCRYPTION_KEY = %v, want the trimmed key", cfg["ENCRYPTION_KEY"])
	}
	if cfg["DATA_ENCRYPTION_METHOD"] != float64(EncryptionChaCha20) {
		t.Fatalf("DATA_ENCRYPTION_METHOD = %v", cfg["DATA_ENCRYPTION_METHOD"])
	}
	if cfg["LISTEN_IP"] != "127.0.0.1" || cfg["LISTEN_PORT"] != float64(10800) {
		t.Fatalf("listener = %v:%v", cfg["LISTEN_IP"], cfg["LISTEN_PORT"])
	}
	if cfg["SOCKS5_AUTH"] != true || cfg["SOCKS5_USER"] != "user" {
		t.Fatalf("auth = %v / %v", cfg["SOCKS5_AUTH"], cfg["SOCKS5_USER"])
	}
	// Nothing may write into the config dir at runtime — on Android it is not scratch space.
	for _, key := range []string{"LOCAL_DNS_ENABLED", "LOCAL_DNS_CACHE_PERSIST_TO_FILE", "SAVE_MTU_SERVERS_TO_FILE"} {
		if cfg[key] != false {
			t.Fatalf("%s = %v, want false", key, cfg[key])
		}
	}
	if cfg["RESOLVER_BALANCING_STRATEGY"] != float64(4) || cfg["PACKET_DUPLICATION_COUNT"] != float64(3) {
		t.Fatalf("tuning not applied: %v / %v", cfg["RESOLVER_BALANCING_STRATEGY"], cfg["PACKET_DUPLICATION_COUNT"])
	}
}

// Blank credentials mean "no auth", which is what the internal chain port needs: the core dials it
// without offering any.
func TestBlankCredentialsDisableSocksAuth(t *testing.T) {
	dir := t.TempDir()
	client, err := NewClient(dir, "v.example.com", "k", EncryptionXOR, "1.1.1.1", "127.0.0.1:10801", "", "")
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	if err := client.writeConfigFiles(); err != nil {
		t.Fatalf("writeConfigFiles: %v", err)
	}
	raw, _ := os.ReadFile(filepath.Join(dir, "client_config.json"))
	var cfg map[string]any
	_ = json.Unmarshal(raw, &cfg)
	if cfg["SOCKS5_AUTH"] != false {
		t.Fatalf("SOCKS5_AUTH = %v, want false", cfg["SOCKS5_AUTH"])
	}
}

func TestNewClientRejectsIncompleteInput(t *testing.T) {
	dir := t.TempDir()
	cases := map[string][6]string{
		"no domain":    {"", "k", "1.1.1.1", "127.0.0.1:1", "", ""},
		"no key":       {"v.example.com", " ", "1.1.1.1", "127.0.0.1:1", "", ""},
		"no resolvers": {"v.example.com", "k", " , ", "127.0.0.1:1", "", ""},
		"bad listener": {"v.example.com", "k", "1.1.1.1", "127.0.0.1", "", ""},
	}
	for name, c := range cases {
		if _, err := NewClient(dir, c[0], c[1], EncryptionXOR, c[2], c[3], c[4], c[5]); err == nil {
			t.Fatalf("%s: expected an error", name)
		}
	}
	if _, err := NewClient(dir, "v.example.com", "k", 9, "1.1.1.1", "127.0.0.1:1", "", ""); err == nil {
		t.Fatal("unsupported cipher: expected an error")
	}
}
