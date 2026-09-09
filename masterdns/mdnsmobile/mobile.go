// Package mdnsmobile is the olcvpn-client wrapper around the MasterDnsVPN client. It is the single
// entry point for BOTH platforms: gomobile binds it into the Android cores AAR, and the desktop
// yptuncore links it through cgo, exactly as the dnstt wrapper it replaces did.
//
// The upstream client is a CLI that reads a TOML/JSON config plus a resolver list from disk, and its
// config loader always reads the resolver file, so this wrapper materialises both files in a caller-
// supplied work directory and then drives the library API (client.Bootstrap → Run → cancel).
//
// gomobile can only bind basic types, so lists arrive as comma/newline separated strings and the
// socket protector arrives as an interface rather than a bare func.
package mdnsmobile

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"masterdnsvpn-go/internal/client"
	"masterdnsvpn-go/internal/config"
	"masterdnsvpn-go/internal/netutil"
	"masterdnsvpn-go/internal/version"
)

// Encryption methods, mirroring DATA_ENCRYPTION_METHOD in the upstream config. They must match the
// server. XOR is the light one; the AEAD modes cost more but are real encryption.
const (
	EncryptionNone      = 0
	EncryptionXOR       = 1
	EncryptionChaCha20  = 2
	EncryptionAES128GCM = 3
	EncryptionAES192GCM = 4
	EncryptionAES256GCM = 5
)

// SocketProtector lets the host (Android VpnService) exclude the tunnel's DNS sockets from the VPN
// routes, so queries egress over the real network instead of looping into the tunnel that carries
// them. gomobile cannot bind a bare func parameter, so the callback is exposed as an interface.
type SocketProtector interface {
	Protect(fd int) bool
}

// Version reports the vendored MasterDnsVPN version.
func Version() string { return version.GetVersion() }

// MasterDnsClient is one running DNS tunnel: a local SOCKS5 listener whose traffic is carried inside
// DNS queries to the MasterDnsVPN server.
type MasterDnsClient struct {
	mu sync.Mutex

	workDir      string
	configPath   string
	resolverPath string

	domains          []string
	resolvers        []string
	encryptionKey    string
	encryptionMethod int
	listenHost       string
	listenPort       int
	socksUser        string
	socksPass        string

	// Tuning that the UI exposes; zero means "leave the upstream default alone".
	balancingStrategy   int
	packetDuplication   int
	uploadCompression   int
	downloadCompression int
	logLevel            string

	protector SocketProtector

	app     *client.Client
	cancel  context.CancelFunc
	done    chan struct{}
	running bool
	runErr  error
}

// NewClient prepares a tunnel client.
//
//   - workDir is a writable directory owned by the caller: the generated config and resolver list
//     live there (Android: the app's files dir; desktop: %APPDATA%\YPtun).
//   - domains is the delegated tunnel domain(s), comma or newline separated — they must match the
//     server's DOMAIN.
//   - resolvers is the DNS resolver list, comma or newline separated, each "ip" or "ip:port"
//     (port 53 when omitted); CIDR entries are expanded by the upstream parser.
//   - encryptionMethod is one of the Encryption* constants and must match the server.
//   - listenAddr is the local SOCKS5 listener, "host:port".
//   - socksUser/socksPass enable username/password auth on that listener; both blank = no auth.
func NewClient(
	workDir string,
	domains string,
	encryptionKey string,
	encryptionMethod int,
	resolvers string,
	listenAddr string,
	socksUser string,
	socksPass string,
) (*MasterDnsClient, error) {
	domainList := splitList(domains)
	if len(domainList) == 0 {
		return nil, fmt.Errorf("masterdns: no tunnel domain")
	}
	resolverList := splitList(resolvers)
	if len(resolverList) == 0 {
		return nil, fmt.Errorf("masterdns: no DNS resolvers")
	}
	if strings.TrimSpace(encryptionKey) == "" {
		return nil, fmt.Errorf("masterdns: encryption key is required")
	}
	if encryptionMethod < EncryptionNone || encryptionMethod > EncryptionAES256GCM {
		return nil, fmt.Errorf("masterdns: unsupported encryption method %d", encryptionMethod)
	}

	host, portText, err := net.SplitHostPort(strings.TrimSpace(listenAddr))
	if err != nil {
		return nil, fmt.Errorf("masterdns: bad listen address %q: %w", listenAddr, err)
	}
	port, err := strconv.Atoi(portText)
	if err != nil || port <= 0 || port > 65535 {
		return nil, fmt.Errorf("masterdns: bad listen port %q", portText)
	}

	workDir = strings.TrimSpace(workDir)
	if workDir == "" {
		return nil, fmt.Errorf("masterdns: work directory is required")
	}

	return &MasterDnsClient{
		workDir:          workDir,
		domains:          domainList,
		resolvers:        resolverList,
		encryptionKey:    strings.TrimSpace(encryptionKey),
		encryptionMethod: encryptionMethod,
		listenHost:       host,
		listenPort:       port,
		socksUser:        socksUser,
		socksPass:        socksPass,
		logLevel:         "INFO",
	}, nil
}

// SetProtectSocket installs the host socket protector; call it before Start.
func (c *MasterDnsClient) SetProtectSocket(protector SocketProtector) {
	if c == nil {
		return
	}
	c.mu.Lock()
	c.protector = protector
	c.mu.Unlock()
}

// SetResolverBalancingStrategy picks how resolvers are chosen (RESOLVER_BALANCING_STRATEGY, 0..8).
// Out-of-range values keep the upstream default.
func (c *MasterDnsClient) SetResolverBalancingStrategy(strategy int) {
	if c == nil || strategy < 0 || strategy > 8 {
		return
	}
	c.mu.Lock()
	c.balancingStrategy = strategy
	c.mu.Unlock()
}

// SetPacketDuplication sets how many copies of each outgoing packet are sent. More copies survive a
// lossy network at the cost of traffic; 0 keeps the upstream default.
func (c *MasterDnsClient) SetPacketDuplication(count int) {
	if c == nil || count < 0 {
		return
	}
	c.mu.Lock()
	c.packetDuplication = count
	c.mu.Unlock()
}

// SetCompression sets the upload/download compression types (0=off, 1=ZSTD, 2=LZ4, 3=ZLIB).
func (c *MasterDnsClient) SetCompression(upload int, download int) {
	if c == nil {
		return
	}
	c.mu.Lock()
	if upload >= 0 && upload <= 3 {
		c.uploadCompression = upload
	}
	if download >= 0 && download <= 3 {
		c.downloadCompression = download
	}
	c.mu.Unlock()
}

// SetLogLevel sets the client log level (DEBUG/INFO/WARN/ERROR).
func (c *MasterDnsClient) SetLogLevel(level string) {
	if c == nil {
		return
	}
	level = strings.ToUpper(strings.TrimSpace(level))
	if level == "" {
		return
	}
	c.mu.Lock()
	c.logLevel = level
	c.mu.Unlock()
}

// ListenAddress is the local SOCKS5 endpoint the tunnel serves.
func (c *MasterDnsClient) ListenAddress() string {
	if c == nil {
		return ""
	}
	return net.JoinHostPort(c.listenHost, strconv.Itoa(c.listenPort))
}

// Start writes the config files and brings the tunnel up in the background. It returns as soon as
// the client has bootstrapped (config parsed, resolver catalog built); the session itself is
// established asynchronously, so callers wait on the local port like they do for every other core.
func (c *MasterDnsClient) Start() error {
	if c == nil {
		return fmt.Errorf("masterdns: nil client")
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.running {
		return fmt.Errorf("masterdns: already running")
	}

	// The protector is global to the process: only one tunnel runs at a time, and the desktop never
	// installs one at all.
	if c.protector != nil {
		protector := c.protector
		netutil.SetProtectFD(func(fd int) bool { return protector.Protect(fd) })
	} else {
		netutil.SetProtectFD(nil)
	}

	if err := c.writeConfigFiles(); err != nil {
		netutil.SetProtectFD(nil)
		return err
	}

	app, err := client.Bootstrap(c.configPath, "", config.ClientConfigOverrides{})
	if err != nil {
		netutil.SetProtectFD(nil)
		return fmt.Errorf("masterdns: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	c.app = app
	c.cancel = cancel
	c.done = done
	c.running = true
	c.runErr = nil

	go func() {
		err := app.Run(ctx)
		c.mu.Lock()
		c.running = false
		c.runErr = err
		c.mu.Unlock()
		close(done)
	}()

	return nil
}

// Stop tears the tunnel down and waits briefly for the run loop to unwind.
func (c *MasterDnsClient) Stop() {
	if c == nil {
		return
	}
	c.mu.Lock()
	cancel := c.cancel
	done := c.done
	c.cancel = nil
	c.mu.Unlock()

	if cancel != nil {
		cancel()
	}
	if done != nil {
		select {
		case <-done:
		case <-time.After(5 * time.Second):
		}
	}

	c.mu.Lock()
	c.app = nil
	c.done = nil
	c.running = false
	c.mu.Unlock()
	netutil.SetProtectFD(nil)
}

// IsRunning reports whether the tunnel's run loop is still alive.
func (c *MasterDnsClient) IsRunning() bool {
	if c == nil {
		return false
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.running
}

// LastError is the error the run loop exited with, if any (empty while running or after a clean stop).
func (c *MasterDnsClient) LastError() string {
	if c == nil {
		return ""
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.runErr == nil {
		return ""
	}
	return c.runErr.Error()
}

// writeConfigFiles materialises the resolver list and the JSON config the upstream loader expects.
// Caller holds the lock.
func (c *MasterDnsClient) writeConfigFiles() error {
	if err := os.MkdirAll(c.workDir, 0o700); err != nil {
		return fmt.Errorf("masterdns: work dir: %w", err)
	}
	c.resolverPath = filepath.Join(c.workDir, "client_resolvers.txt")
	c.configPath = filepath.Join(c.workDir, "client_config.json")

	resolverFile := strings.Join(c.resolvers, "\n") + "\n"
	if err := os.WriteFile(c.resolverPath, []byte(resolverFile), 0o600); err != nil {
		return fmt.Errorf("masterdns: resolver file: %w", err)
	}

	// Keys are the upstream TOML names — the JSON loader looks them up by the very same tag.
	cfg := map[string]any{
		"PROTOCOL_TYPE":          "SOCKS5",
		"DOMAINS":                c.domains,
		"DATA_ENCRYPTION_METHOD": c.encryptionMethod,
		"ENCRYPTION_KEY":         c.encryptionKey,
		"LISTEN_IP":              c.listenHost,
		"LISTEN_PORT":            c.listenPort,
		"SOCKS5_AUTH":            c.socksUser != "" || c.socksPass != "",
		"SOCKS5_USER":            c.socksUser,
		"SOCKS5_PASS":            c.socksPass,
		// The app owns DNS itself (the TUN bridge / the core in front), and a local :53 listener would
		// collide with it. Nothing may write to disk either: on Android the config dir is not a
		// scratch space the tunnel should grow files in.
		"LOCAL_DNS_ENABLED":               false,
		"LOCAL_DNS_CACHE_PERSIST_TO_FILE": false,
		"SAVE_MTU_SERVERS_TO_FILE":        false,
		"LOG_LEVEL":                       c.logLevel,
	}
	if c.balancingStrategy > 0 {
		cfg["RESOLVER_BALANCING_STRATEGY"] = c.balancingStrategy
	}
	if c.packetDuplication > 0 {
		cfg["PACKET_DUPLICATION_COUNT"] = c.packetDuplication
		cfg["SETUP_PACKET_DUPLICATION_COUNT"] = c.packetDuplication
	}
	if c.uploadCompression > 0 {
		cfg["UPLOAD_COMPRESSION_TYPE"] = c.uploadCompression
	}
	if c.downloadCompression > 0 {
		cfg["DOWNLOAD_COMPRESSION_TYPE"] = c.downloadCompression
	}

	raw, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return fmt.Errorf("masterdns: config encode: %w", err)
	}
	if err := os.WriteFile(c.configPath, raw, 0o600); err != nil {
		return fmt.Errorf("masterdns: config file: %w", err)
	}
	return nil
}

// splitList accepts the comma/newline separated lists gomobile forces us to pass as strings.
func splitList(value string) []string {
	fields := strings.FieldsFunc(value, func(r rune) bool {
		return r == ',' || r == '\n' || r == '\r' || r == ';' || r == ' ' || r == '\t'
	})
	out := make([]string, 0, len(fields))
	seen := make(map[string]struct{}, len(fields))
	for _, field := range fields {
		field = strings.TrimSpace(field)
		if field == "" {
			continue
		}
		if _, dup := seen[field]; dup {
			continue
		}
		seen[field] = struct{}{}
		out = append(out, field)
	}
	return out
}
