// Package gate runs the engine the way users break it: a real relay, a real
// server, bulk transfer with connects on top, a phone's memory budget, and a
// report a release is compared against. See the design in the olcbox
// repository, docs/superpowers/specs/2026-09-15-release-gate-design.md.
package gate

import (
	"cmp"
	"context"
	"fmt"
	"net"
	"net/http"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
)

// ai-generated: the whole file (the shared types, the scenario registry and
// the plan).

// Pair is one provider and one transport of it.
type Pair struct {
	Provider  string
	Transport string
}

// String names the pair provider/transport, the way subtests and logs do.
func (p Pair) String() string { return p.Provider + "/" + p.Transport }

// Endpoint is everything a client needs to join a server's room.
type Endpoint struct {
	Provider  string
	Transport string
	Room      string // a URL or an id, as the provider wants it
	Channel   string // peer-routing channel both ends set, fresh per local pair; empty = the room's default
	Key       string
	DNS       string
	VP8FPS    int
	VP8Batch  int
	// DTLSProfile is the DTLS ClientHello profile both ends handshake with
	// (dtls.profile), empty for the stock handshake. Not a secret.
	DTLSProfile string
	// ClientToken is a provider token for the CLIENT side alone (the max
	// pair runs two identities: the server joins with its own token, the
	// client with this one); empty when the client is a guest.
	ClientToken string
	// ServerLog is the raw log of the server behind this endpoint, for a
	// target that runs one; empty for the link target, whose server is a
	// fleet node. A failed cell is read for what the relay did to it (see
	// RelayDropFailures). ai-generated: this field (olcrtc#26).
	ServerLog string
	// ServerJoined is the stamp of that server's join line, which a failed
	// cell names its relay drops against (LogMark); zero when unknown.
	// ai-generated: this field (the streaming reader).
	ServerJoined time.Time
}

// DTLSProfile reads a run's dtls.profile the way the engine does: empty and
// off are the stock handshake, returned as empty so a report and a server
// config name a profile only when one is on; any other name must be one the
// engine knows, refused here before a server or a client starts.
func DTLSProfile(value string) (string, error) {
	p := engine.DTLSProfile(strings.TrimSpace(value))
	if err := engine.ValidateDTLSProfile(p); err != nil {
		return "", fmt.Errorf("dtls profile: %w", err)
	}
	if p == engine.DTLSProfileOff {
		return "", nil
	}
	return string(p), nil
}

// String describes the endpoint without its secrets: a room or a key that is
// set prints as <room> or <key>, the scrubber's placeholders, so a log line or
// a test failure that prints an endpoint leaks neither.
func (e Endpoint) String() string {
	s := fmt.Sprintf("%s/%s room=%s key=%s dns=%s vp8=%d/%d", e.Provider, e.Transport,
		withheld(e.Room, "<room>"), withheld(e.Key, "<key>"), e.DNS, e.VP8FPS, e.VP8Batch)
	if e.DTLSProfile != "" {
		s += " dtls=" + e.DTLSProfile
	}
	return s
}

// GoString is String, so %#v withholds the same fields.
func (e Endpoint) GoString() string { return e.String() }

// withheld stands in for a secret: the placeholder when it is set, <unset>
// when it is not, so a printed endpoint still tells the two apart.
func withheld(secret, placeholder string) string {
	if secret == "" {
		return "<unset>"
	}
	return placeholder
}

// Tunnel is a running client: a SOCKS5 listener and a way to stop it.
type Tunnel struct {
	SocksAddr string
	Stop      func()
}

// Client is a way of running the engine's client: the CLI path or the phone's.
type Client interface {
	Name() string // "cli" or "mobile"
	Start(ctx context.Context, ep Endpoint) (*Tunnel, error)
}

// OpenOptions tunes how a target brings a server up. Only Local honours them.
type OpenOptions struct {
	// BridgeDelay makes the server open its relay bridge this much later than
	// it would; the child process is started with OLCRTC_TEST_BRIDGE_DELAY.
	BridgeDelay time.Duration
}

// Target is where the server side of a pair comes from.
type Target interface {
	Name() string     // "local" or "link"
	Platform() string // the first element of a cell id, e.g. "engine-linux"
	Pairs() []Pair
	// Open brings up the server side for p (or, for a link, checks that the
	// link is p) and returns what the client needs plus a stop function.
	Open(ctx context.Context, p Pair, dir string, opt OpenOptions) (Endpoint, func(), error)
	Load() LoadURLs
}

// LoadURLs is what the load scenarios pull from and push to through the tunnel.
type LoadURLs struct {
	Small    string // a ~1 KB resource
	Big      string // BigBytes long
	Sink     string // accepts POST bodies
	BigBytes int64
}

// Metrics is what a scenario measured, by the keys the verdict knows.
type Metrics map[string]float64

// DialFunc dials through the tunnel.
type DialFunc func(ctx context.Context, network, addr string) (net.Conn, error)

// Env is one cell's world: the pair, the client, the tunnel and the helpers.
type Env struct {
	Target   Target
	Pair     Pair
	Client   Client
	Endpoint Endpoint
	Tunnel   *Tunnel
	Load     LoadURLs
	HTTP     *http.Client
	Dial     DialFunc
	UDP      func(ctx context.Context) (*UDPAssoc, error)
	Sampler  *Sampler
	Log      *CellLog
	Dir      string
	Logf     func(format string, args ...any)
	// Thresholds are what the cell is judged by: Local or Link, for the
	// pair's provider (Thresholds.For).
	Thresholds Thresholds
	// Delayed opens a fresh server for the pair with the given options and
	// returns its endpoint; nil for targets that cannot (Link). S6 uses it.
	Delayed func(ctx context.Context, opt OpenOptions) (Endpoint, func(), error)
	// handshake is how long the client's Start took to hand over a listening
	// SOCKS port, the relay join and the handshake included. The runner times
	// it; S0 records it, and records none while it is zero, so a run that
	// never timed the start fails S0 rather than passing it at 0 ms.
	handshake time.Duration
}

// Scenario is one named cell body.
type Scenario struct {
	ID   string
	Name string
	// Applies says whether the scenario runs for a target, a pair and a
	// client flavour; nil means it runs everywhere.
	Applies func(target Target, p Pair, client string) bool
	Run     func(ctx context.Context, env *Env) (Metrics, error)
}

var (
	registryMu sync.Mutex //nolint:gochecknoglobals // guards registry
	registry   []Scenario //nolint:gochecknoglobals // filled from init in scenarios.go; tests swap it
)

// Register adds a scenario. Called from init in scenarios.go. It panics on an
// empty or repeated ID: two scenarios under one ID would share a cell, and
// one's pass would stand in for the other's failure.
func Register(s Scenario) {
	registryMu.Lock()
	defer registryMu.Unlock()
	switch {
	case s.ID == "":
		panic("gate: a scenario without an ID")
	case slices.ContainsFunc(registry, func(r Scenario) bool { return r.ID == s.ID }):
		panic("gate: scenario " + s.ID + " registered twice")
	}
	registry = append(registry, s)
}

// Scenarios returns the registered scenarios sorted by ID.
func Scenarios() []Scenario {
	registryMu.Lock()
	out := slices.Clone(registry)
	registryMu.Unlock()
	slices.SortFunc(out, func(a, b Scenario) int { return cmp.Compare(a.ID, b.ID) })
	return out
}

// CellID names a cell: platform/provider/transport/client/scenario. Never a
// room, a key or a link: this is what the report and the release show.
func CellID(platform string, p Pair, client, scenario string) string {
	return platform + "/" + p.Provider + "/" + p.Transport + "/" + client + "/" + scenario
}

// PlanCells enumerates every cell a target and a set of clients will run, in
// scenario order, so the recorder can hold each one to an outcome.
func PlanCells(t Target, clients []string) []Cell {
	platform, pairs := t.Platform(), t.Pairs()
	var cells []Cell
	for _, s := range Scenarios() {
		for _, p := range pairs {
			for _, c := range clients {
				if s.Applies != nil && !s.Applies(t, p, c) {
					continue
				}
				cells = append(cells, Cell{
					ID: CellID(platform, p, c, s.ID), Platform: platform,
					Provider: p.Provider, Transport: p.Transport, Client: c, Scenario: s.ID,
					Status: StatusPlanned,
				})
			}
		}
	}
	return cells
}
