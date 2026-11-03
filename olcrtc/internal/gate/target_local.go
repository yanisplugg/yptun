package gate

import (
	"bufio"
	"bytes"
	"cmp"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

// ai-generated: the whole file (the local target: cmd/olcrtc built once with
// the test hooks and run as a mode: srv child per pair, from a private
// directory, with only a scrubbed copy of its log handed out).

// EnvWBStreamToken names the variable the WB account token comes from. The
// entry reads it and passes it in LocalOptions: never a flag, so the token
// shows in no process list and no test log (amendment A2).
const EnvWBStreamToken = "OLCRTC_GATE_WBSTREAM_TOKEN"

// EnvMaxKeys names the variable the max pair's two calls sessions come
// from: exactly two comma-separated session keys, the server's first.
// Like the WB token, the entry reads them into LocalOptions - never a
// flag - and they join the run's secrets, scrubbed from every log.
const EnvMaxKeys = "OLCRTC_GATE_MAX_KEYS"

var (
	// ErrLocalOptions is a local target asked for a plan it cannot run: a
	// name it does not know, a provider left with no transport, a transport
	// no provider carries. Each would be a cell that could never pass.
	ErrLocalOptions = errors.New("local target options")
	// ErrPairNotCarried is a pair a target does not offer.
	ErrPairNotCarried = errors.New("pair not carried by the target")
	// ErrNoWBStreamToken fails every wbstream pair of a run without the
	// token: a guest cannot be the first participant of an idle WB room.
	// ErrNoMaxKeys fails every max pair of a run without both session keys:
	// the two engine sides are two identities, or the SFU's same-uid dedup
	// tears the pair apart (measured: a newer join of one identity Byes the
	// older's signaling socket).
	ErrNoMaxKeys = errors.New("max: " + EnvMaxKeys +
		" must hold two comma-separated calls session keys, the server's first")
	ErrNoWBStreamToken = errors.New("wbstream: " + EnvWBStreamToken +
		" is not set (WB refuses a guest as the first participant of an idle room)")
	// ErrServerExited is a server that ended before it joined its room.
	ErrServerExited = errors.New("server exited before the link connected")
	// ErrLineNotSeen is a log that did not show the line waited for in time.
	ErrLineNotSeen = errors.New("line not seen in the log")
)

// Target and platform names, the first two parts of a cell's identity.
const (
	targetLocal         = "local"
	targetLink          = "link"
	platformEngineLinux = "engine-linux"
)

// Provider and transport names, as the engine's YAML spells them.
const (
	providerJitsi      = "jitsi"
	providerTelemost   = "telemost"
	providerMTSLink    = "mtslink"
	providerVKCalls    = "vkcalls"
	providerWBStream   = "wbstream"
	providerSaluteJazz = "salutejazz"
	providerMAX        = "max"

	transportData  = "datachannel"
	transportVideo = "videochannel"
	transportSEI   = "seichannel"
	transportVP8   = "vp8channel"
)

const (
	// defaultDNS is the resolver the servers use when none is given.
	defaultDNS = "8.8.8.8:53"
	// linkedLine is what the server logs once it has joined its room.
	linkedLine = "Link connected"
	// linkBudget is how long a server may take to join its room.
	linkBudget = 60 * time.Second
	// stopGrace is how long a server has to leave its room after SIGTERM
	// before it is killed, so no ghost of it stays in a pool room for the
	// next pair.
	stopGrace = 10 * time.Second
	// linePoll is how often a log is read again for the line waited for.
	linePoll = 200 * time.Millisecond
	// tailBytes bounds the server line an error quotes.
	tailBytes = 400
	// serverLogName is the server's log, raw in the private directory and
	// scrubbed in the pair's.
	serverLogName = "srv.log"
	// bridgeDelayEnv is what internal/testhooks reads in the child.
	bridgeDelayEnv = "OLCRTC_TEST_BRIDGE_DELAY"
	// privateTargetsEnv lifts the child's egress policy, which refuses the
	// loopback origin the local target pulls from. ai-generated (egress
	// hardening).
	privateTargetsEnv = "OLCRTC_TEST_ALLOW_PRIVATE_TARGETS"
)

// localProviders is the providers a default run walks, in order
// (-olcrtc.gate-providers). It is every provider the local target carries
// but three, whose cells can only fail today. WB Stream closed its beta to
// its own staff on 2026-10-10 and answers every join of ours 401. Telemost's
// relay has cut every published track to a trickle since about 2026-10-01
// (#58), and the platform stopped handing out its lines on 2026-10-18. The
// MAX account the gate's pair signs in with is locked (2026-10-19). Each is
// still carried, for the day that changes, and walked when named.
//
// ai-generated: Telemost and MAX left out of the default walk.
func localProviders() []string {
	return []string{providerJitsi, providerMTSLink, providerSaluteJazz, providerVKCalls}
}

// transportsOf is which transports a provider carries (amendment A1, the
// engine's real E2E expectations, confirmed live on 2026-09-18). Telemost
// drops SCTP, so no datachannel, and seichannel fails there by design; WB
// guests cannot publish data, so no datachannel there either. SaluteJazz is
// the other way round: it admits a guest to the room's data channels and
// never to a media track, so datachannel alone.
func transportsOf(provider string) []string {
	switch provider {
	case providerJitsi:
		return []string{transportData, transportVideo, transportSEI, transportVP8}
	case providerTelemost:
		return []string{transportVP8, transportVideo}
	case providerWBStream:
		return []string{transportVP8, transportVideo, transportSEI}
	case providerVKCalls:
		// The SFU re-stamps forwarded RTP (red-wrapped VP8 observed in the
		// spike), so the codec-agnostic videochannel is the proven lane;
		// vp8channel rides the same publish slot.
		return []string{transportVP8, transportVideo}
	case providerMTSLink:
		// The SFU relays no data between guests and forwards VP9 and H264,
		// never VP8: vp8channel dresses its frames for it, the other video
		// transports publish what it does not take.
		return []string{transportVP8}
	case providerSaluteJazz:
		return []string{transportData}
	case providerMAX:
		// The measured lane: the P2P pair's datagram datachannel carried
		// 2.7-5.7 Mbit/s up and 777 kbit/s down through the room's SFU
		// relay; a sendable media track the counterparty never answers,
		// so no video transport (2026-10-18 carrier design, section 4).
		return []string{transportData}
	default:
		return nil
	}
}

// LocalOptions configures a target whose servers are child processes.
type LocalOptions struct {
	ModuleRoot string // the engine repository root, where go build runs
	// WorkDir is private: the server binary, each server's YAML (room, key,
	// WB token) and its raw log. Never upload it (amendment A9).
	WorkDir       string
	Instances     string   // docs/jitsi.instances.yaml
	JitsiHosts    []string // used instead of Instances when it names a host
	TelemostRooms []string // pool: bare ids or room URLs
	WBStreamRooms []string // pool: bare ids or room URLs
	VKCallsRooms  []string // pool: VK Calls join links
	MTSLinkRooms  []string // pool: MTS Link event join links
	// WBStreamToken is the WB account token the server joins with; the
	// client stays a guest, as the app is. From EnvWBStreamToken.
	WBStreamToken string
	// MaxRooms is the pool of max join links (max.ru/joincall/<hash>),
	// created by the account browser the way the gates' other pools are.
	MaxRooms []string
	// MaxKeys are the pair's two calls session keys (server, client), from
	// EnvMaxKeys; both sides join under identities of their own.
	MaxKeys    []string
	RunNumber  int
	Providers  []string
	Transports []string
	DNS        string // default 8.8.8.8:53
	// DTLSProfile is the dtls.profile every server and client of the run
	// handshakes with; empty (or off) is the stock handshake.
	DTLSProfile string
	Origin      *Origin
}

// LocalTarget builds cmd/olcrtc once and runs it as mode: srv per pair.
type LocalTarget struct {
	opts       LocalOptions
	pairs      []Pair
	jitsiHosts []string
	probe      func(ctx context.Context, host string) bool
	// saluteJazzRoom makes a fresh SaluteJazz room (SaluteJazzRoom; tests
	// swap it).
	saluteJazzRoom func(ctx context.Context) (string, error)
	linkWait       time.Duration

	buildMu sync.Mutex
	binary  string
}

// NewLocalTarget checks the plan and prepares the target; the server is
// built on the first Open (or by Build). Pairs are the providers crossed
// with the transports in the order given, less the combinations a provider
// does not carry.
func NewLocalTarget(opts LocalOptions) (*LocalTarget, error) {
	if opts.WorkDir == "" {
		return nil, fmt.Errorf("%w: no work directory", ErrLocalOptions)
	}
	work, err := filepath.Abs(opts.WorkDir)
	if err != nil {
		return nil, fmt.Errorf("work directory: %w", err)
	}
	opts.WorkDir = work
	if opts.DNS == "" {
		opts.DNS = defaultDNS
	}
	if opts.DTLSProfile, err = DTLSProfile(opts.DTLSProfile); err != nil {
		return nil, fmt.Errorf("%w: %w", ErrLocalOptions, err)
	}
	pairs, err := planPairs(opts.Providers, opts.Transports)
	if err != nil {
		return nil, err
	}
	t := &LocalTarget{opts: opts, pairs: pairs, probe: ProbeHTTPS, saluteJazzRoom: SaluteJazzRoom, linkWait: linkBudget}
	if slices.Contains(opts.Providers, providerJitsi) {
		if t.jitsiHosts, err = JitsiHosts(opts.Instances, opts.JitsiHosts); err != nil {
			return nil, err
		}
	}
	return t, nil
}

// planPairs crosses providers with transports in the order given and drops
// what a provider does not carry. An unknown or repeated name, a provider
// left with no transport and a transport no provider carries are errors.
func planPairs(providers, transports []string) ([]Pair, error) {
	if err := checkNames("provider", providers, func(p string) bool { return transportsOf(p) != nil }); err != nil {
		return nil, err
	}
	known := transportsOf(providerJitsi)
	if err := checkNames("transport", transports, func(tr string) bool { return slices.Contains(known, tr) }); err != nil {
		return nil, err
	}
	var pairs []Pair
	carried := make(map[string]bool, len(transports))
	for _, p := range providers {
		n := len(pairs)
		for _, tr := range transports {
			if slices.Contains(transportsOf(p), tr) {
				pairs = append(pairs, Pair{Provider: p, Transport: tr})
				carried[tr] = true
			}
		}
		if len(pairs) == n {
			return nil, fmt.Errorf("%w: %s carries none of %s", ErrLocalOptions, p, strings.Join(transports, ","))
		}
	}
	for _, tr := range transports {
		if !carried[tr] {
			return nil, fmt.Errorf("%w: none of %s carries %s", ErrLocalOptions, strings.Join(providers, ","), tr)
		}
	}
	return pairs, nil
}

// checkNames refuses an empty list, a name known does not accept and a name
// given twice.
func checkNames(kind string, names []string, known func(string) bool) error {
	if len(names) == 0 {
		return fmt.Errorf("%w: no %s", ErrLocalOptions, kind)
	}
	for i, name := range names {
		if !known(name) {
			return fmt.Errorf("%w: unknown %s %q", ErrLocalOptions, kind, name)
		}
		if slices.Contains(names[:i], name) {
			return fmt.Errorf("%w: %s %s listed twice", ErrLocalOptions, kind, name)
		}
	}
	return nil
}

// Name implements Target.
func (t *LocalTarget) Name() string { return targetLocal }

// Platform implements Target.
func (t *LocalTarget) Platform() string { return platformEngineLinux }

// Pairs implements Target.
func (t *LocalTarget) Pairs() []Pair { return slices.Clone(t.pairs) }

// Load is the loopback origin's URLs; none without an origin.
func (t *LocalTarget) Load() LoadURLs {
	if t.opts.Origin == nil {
		return LoadURLs{}
	}
	return t.opts.Origin.URLs
}

// Build compiles the server with the test hooks into WorkDir, once.
func (t *LocalTarget) Build(ctx context.Context) error {
	_, err := t.build(ctx)
	return err
}

func (t *LocalTarget) build(ctx context.Context) (string, error) {
	t.buildMu.Lock()
	defer t.buildMu.Unlock()
	if t.binary != "" {
		return t.binary, nil
	}
	out := filepath.Join(t.opts.WorkDir, "olcrtc")
	cmd := exec.CommandContext(ctx, "go", "build", "-tags", "olcrtc_testhooks", "-o", out, "./cmd/olcrtc")
	cmd.Dir = t.opts.ModuleRoot
	if b, err := cmd.CombinedOutput(); err != nil {
		return "", fmt.Errorf("build server: %w\n%s", err, b)
	}
	t.binary = out
	return out, nil
}

// Open picks a room for the pair and starts its server from a private
// directory, returning once the server has joined the room. The stop
// function ends the server and leaves a scrubbed copy of its log in dir as
// srv.log; a server that never joins leaves the same, and the error quotes
// its last line (the provider's answer) scrubbed too.
func (t *LocalTarget) Open(ctx context.Context, p Pair, dir string, opt OpenOptions) (Endpoint, func(), error) {
	if !slices.Contains(t.pairs, p) {
		return Endpoint{}, nil, fmt.Errorf("%w: %s", ErrPairNotCarried, p)
	}
	ep, err := t.endpoint(ctx, p)
	if err != nil {
		return Endpoint{}, nil, err
	}
	binary, err := t.build(ctx)
	if err != nil {
		return Endpoint{}, nil, err
	}
	secrets := t.secrets(ep)
	srv, err := startServer(ctx, binary, t.opts.WorkDir, RenderServerConfig(ep, t.token(p.Provider)), opt)
	if err != nil {
		return Endpoint{}, nil, err
	}
	stop := func() { srv.stop(filepath.Join(dir, serverLogName), secrets) }
	ep.ServerLog = srv.log // ai-generated: this line (olcrtc#26)
	if err := waitForLine(srv.log, linkedLine, t.linkWait, srv.exited); err != nil {
		tail := clip(Scrub(lastLine(srv.log), secrets...))
		stop()
		if ctx.Err() != nil {
			return Endpoint{}, nil, fmt.Errorf("server: %w", ctx.Err())
		}
		return Endpoint{}, nil, fmt.Errorf("%w; last server line: %s", err, tail)
	}
	ep.ServerJoined = serverJoined(srv.log) // ai-generated: this line (the streaming reader)
	return ep, stop, nil
}

// endpoint is a fresh room, key and channel for the pair.
func (t *LocalTarget) endpoint(ctx context.Context, p Pair) (Endpoint, error) {
	room, err := t.room(ctx, p.Provider)
	if err != nil {
		return Endpoint{}, err
	}
	key, err := NewKey()
	if err != nil {
		return Endpoint{}, err
	}
	channel, err := NewChannel()
	if err != nil {
		return Endpoint{}, err
	}
	// VP8FPS and VP8Batch are the app's vp8channel numbers.
	return Endpoint{Provider: p.Provider, Transport: p.Transport, Room: room, Key: key, Channel: channel,
		ClientToken: t.clientToken(p.Provider),
		DNS:         t.opts.DNS, VP8FPS: 60, VP8Batch: 64, DTLSProfile: t.opts.DTLSProfile}, nil
}

// room is where the pair's server goes: a fresh room on a Jitsi host that
// answers, a fresh SaluteJazz room, or the pool entry of the run in the form
// its provider joins by.
func (t *LocalTarget) room(ctx context.Context, provider string) (string, error) {
	if pool, verbatim := t.linkPool(provider); verbatim {
		return t.poolLink(provider, pool)
	}
	switch provider {
	case providerJitsi:
		return JitsiRoom(ctx, t.jitsiHosts, t.probe)
	case providerSaluteJazz:
		room, err := t.saluteJazzRoom(ctx)
		if err != nil {
			return "", fmt.Errorf("salutejazz: %w", err)
		}
		return room, nil
	case providerTelemost:
		entry, err := PoolRoom(t.opts.TelemostRooms, t.opts.RunNumber)
		if err != nil {
			return "", fmt.Errorf("telemost: %w", err)
		}
		return TelemostURL(entry), nil
	case providerWBStream:
		if t.opts.WBStreamToken == "" {
			return "", ErrNoWBStreamToken
		}
		entry, err := PoolRoom(t.opts.WBStreamRooms, t.opts.RunNumber)
		if err != nil {
			return "", fmt.Errorf("wbstream: %w", err)
		}
		id := WBStreamRoomID(entry)
		if id == "" {
			return "", fmt.Errorf("wbstream: %w: the entry names no room id", ErrPoolRoom)
		}
		return id, nil
	default:
		return "", fmt.Errorf("%w: provider %s", ErrPairNotCarried, provider)
	}
}

// linkPool is the pool of a provider whose entries are join links its auth
// provider reads as they are: vk.ru/vk.com /call/join/<id>,
// my.mts-link.ru/j/<owner>/<event>, max.ru/joincall/<hash>.
func (t *LocalTarget) linkPool(provider string) ([]string, bool) {
	switch provider {
	case providerVKCalls:
		return t.opts.VKCallsRooms, true
	case providerMTSLink:
		return t.opts.MTSLinkRooms, true
	case providerMAX:
		return t.opts.MaxRooms, true
	default:
		return nil, false
	}
}

// poolLink is the run's entry of a link pool. The max pair needs its two
// session keys first: with one or none it would half-join under one
// identity, which the SFU's dedup tears apart.
func (t *LocalTarget) poolLink(provider string, pool []string) (string, error) {
	if provider == providerMAX && len(t.opts.MaxKeys) != 2 {
		return "", ErrNoMaxKeys
	}
	link, err := PoolRoom(pool, t.opts.RunNumber)
	if err != nil {
		return "", fmt.Errorf("%s: %w", provider, err)
	}
	return link, nil
}

// token is the account token the provider's server joins with, if any.
func (t *LocalTarget) token(provider string) string {
	switch provider {
	case providerWBStream:
		return t.opts.WBStreamToken
	case providerMAX:
		if len(t.opts.MaxKeys) > 0 {
			return t.opts.MaxKeys[0]
		}
		return ""
	default:
		return ""
	}
}

// clientToken is the CLIENT side's provider token; only the max pair runs
// one (its second identity).
func (t *LocalTarget) clientToken(provider string) string {
	if provider == providerMAX && len(t.opts.MaxKeys) > 1 {
		return t.opts.MaxKeys[1]
	}
	return ""
}

// secrets is what a server's log and errors lose before they leave: the
// endpoint's, the WB token and the Jitsi hosts of an override, which come
// from a secret too, in every form a log writes them (see hostForms).
func (t *LocalTarget) secrets(ep Endpoint) []string {
	out := append(ep.Secrets(), t.opts.WBStreamToken)
	out = append(out, t.opts.MaxKeys...)
	if len(jitsiHostList(t.opts.JitsiHosts)) > 0 {
		out = append(out, hostForms(t.jitsiHosts)...) // ai-generated: bare and lowercased too
	}
	return out
}

// RenderServerConfig writes the mode: srv YAML for an endpoint. token is a
// provider account token for the server alone (WB Stream), empty for none.
// vp8channel and seichannel take the app's options, and the UDP relay is on
// so a client's UDP ASSOCIATE reaches the exit.
func RenderServerConfig(ep Endpoint, token string) string {
	auth := "auth: { provider: " + ep.Provider + " }"
	if token != "" {
		auth = "auth: { provider: " + ep.Provider + ", token: " + strconv.Quote(token) + " }"
	}
	room := "room: { id: " + strconv.Quote(ep.Room) + " }"
	if ep.Channel != "" {
		room = "room: { id: " + strconv.Quote(ep.Room) + ", channel: " + strconv.Quote(ep.Channel) + " }"
	}
	lines := []string{
		"mode: srv", auth, room,
		"crypto: { key: " + strconv.Quote(ep.Key) + " }",
		"net: { transport: " + ep.Transport + ", dns: " + strconv.Quote(ep.DNS) + " }",
		"udp: { enabled: true }",
	}
	switch ep.Transport {
	case transportVP8: // the app's numbers, 60 and 64, where the endpoint names none
		lines = append(lines, fmt.Sprintf("vp8: { fps: %d, batch_size: %d }", cmp.Or(ep.VP8FPS, 60), cmp.Or(ep.VP8Batch, 64)))
	case transportSEI:
		lines = append(lines, "sei: { fps: 60, batch_size: 64, fragment_size: 900, ack_timeout_ms: 2000 }")
	}
	if ep.DTLSProfile != "" {
		lines = append(lines, "dtls: { profile: "+strconv.Quote(ep.DTLSProfile)+" }")
	}
	return strings.Join(append(lines, "debug: true"), "\n") + "\n"
}

// server is one child mode: srv process and the private directory it runs
// from: its YAML and its raw log.
type server struct {
	dir    string
	log    string
	cancel context.CancelFunc
	exited chan struct{}
	once   sync.Once
}

// startServer writes cfg into a fresh private directory under work and runs
// binary on it, stdout and stderr into the raw log. Cancelling ctx, or stop,
// sends SIGTERM so the server leaves its room; stopGrace later it is killed.
func startServer(ctx context.Context, binary, work, cfg string, opt OpenOptions) (*server, error) {
	dir, err := os.MkdirTemp(work, "srv-")
	if err != nil {
		return nil, fmt.Errorf("server directory: %w", err)
	}
	cfgPath, logPath := filepath.Join(dir, "srv.yaml"), filepath.Join(dir, serverLogName)
	logFile, err := createPrivate(cfgPath, cfg, logPath)
	if err != nil {
		_ = os.RemoveAll(dir)
		return nil, err
	}
	runCtx, cancel := context.WithCancel(ctx)
	cmd := exec.CommandContext(runCtx, binary, cfgPath)
	cmd.Stdout, cmd.Stderr = logFile, logFile
	cmd.Env = append(os.Environ(), bridgeDelayEnv+"="+opt.BridgeDelay.String(), privateTargetsEnv+"=1")
	cmd.Cancel = func() error { return cmd.Process.Signal(syscall.SIGTERM) }
	cmd.WaitDelay = stopGrace
	err = cmd.Start()
	_ = logFile.Close() // the child holds its own descriptor
	if err != nil {
		cancel()
		_ = os.RemoveAll(dir)
		return nil, fmt.Errorf("start server: %w", err)
	}
	s := &server{dir: dir, log: logPath, cancel: cancel, exited: make(chan struct{})}
	go func() {
		_ = cmd.Wait()
		close(s.exited)
	}()
	return s, nil
}

// createPrivate writes the config and opens the log, both readable by the
// owner alone: the config holds the room, the key and the WB token.
func createPrivate(cfgPath, cfg, logPath string) (*os.File, error) {
	if err := os.WriteFile(cfgPath, []byte(cfg), 0o600); err != nil {
		return nil, fmt.Errorf("write server config: %w", err)
	}
	f, err := os.OpenFile(logPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return nil, fmt.Errorf("create server log: %w", err)
	}
	return f, nil
}

// stop ends the server, writes the scrubbed copy of its log to artifact and
// removes the private directory. Only the first call does anything.
func (s *server) stop(artifact string, secrets []string) {
	s.once.Do(func() {
		s.cancel()
		<-s.exited
		_ = ScrubFile(s.log, artifact, secrets...)
		_ = os.RemoveAll(s.dir)
	})
}

// WaitForLine polls a log file until it contains substr.
func WaitForLine(path, substr string, timeout time.Duration) error {
	return waitForLine(path, substr, timeout, nil)
}

// waitForLine is WaitForLine that also gives up when done closes: the
// process writing the log has ended, and the line will not come.
func waitForLine(path, substr string, timeout time.Duration, done <-chan struct{}) error {
	deadline := time.NewTimer(timeout)
	defer deadline.Stop()
	tick := time.NewTicker(linePoll)
	defer tick.Stop()
	for {
		if logHas(path, substr) {
			return nil
		}
		select {
		case <-done:
			return ErrServerExited
		case <-deadline.C:
			return fmt.Errorf("%w: %q within %s", ErrLineNotSeen, substr, timeout)
		case <-tick.C:
		}
	}
}

// logHas reports whether the log at path contains substr yet.
func logHas(path, substr string) bool {
	raw, err := os.ReadFile(path)
	return err == nil && strings.Contains(string(raw), substr)
}

// lastLine is the last non-blank line of a log.
func lastLine(path string) string {
	raw, err := os.ReadFile(path)
	if err != nil {
		return "(no log: " + err.Error() + ")"
	}
	text := strings.TrimSpace(string(raw))
	return strings.TrimSpace(text[strings.LastIndexByte(text, '\n')+1:])
}

// clip cuts a line an error quotes to tailBytes. Scrub before clipping: a
// cut through a secret would leave its head unscrubbed.
func clip(line string) string {
	if len(line) <= tailBytes {
		return line
	}
	return strings.ToValidUTF8(line[:tailBytes], "") + "..."
}

// ai-generated: the rest of the file (olcrtc#26).

const (
	// relayDropMarker is how the engine names the end of a room session and
	// the protocol reason the relay gave for it.
	relayDropMarker = "disconnected from the room, reason="
	// logTimeLayout is what the server stamps every line with.
	logTimeLayout = "2006/01/02 15:04:05"
	// logLineMax bounds how much of one log line is read: a line is judged by
	// its first logLineMax bytes and the rest of it is skipped. The lines
	// read for, the join and a relay's drop, are short; a debug line can
	// quote a whole XMPP stanza or SDP, 20 KB and more.
	logLineMax = 64 << 10
)

// LogMark is where a server log stood when a cell began: its size, so a
// failed cell reads only what the log gained during it, and the stamp of the
// server's join, which comes before every cell's part. ai-generated: this
// type (the streaming reader).
type LogMark struct {
	Offset int64
	Joined time.Time
}

// markLog is where the endpoint's server log stands now: its size and the
// join its target noted. A log that is not there marks its start.
// ai-generated: this function (the streaming reader).
func markLog(ep Endpoint) LogMark {
	at := LogMark{Joined: ep.ServerJoined}
	if info, err := os.Stat(ep.ServerLog); err == nil {
		at.Offset = info.Size()
	}
	return at
}

// RelayDropFailures reads a server log from a cell's mark and reports, for
// every session the relay ended inside [from, to], a line naming the reason
// and when it happened. A failed cell gets these next to its own failures so
// a red cell says at once whether the relay took the server out from under
// it.
//
// It judges nothing: a removal is usually our own doing (the relay policing
// what we publish, olcrtc#26), so it never turns a failure into a pass and
// never fires for a cell that passed. A log it cannot read yields nothing.
//
// ai-generated: the read from the mark, a bounded line at a time. The whole
// log copied into a string, 20 MB and more late in a pair, grew the process
// S7 weighs after every failed cell. Every line before the mark was written
// before the cell began and stamped no later, so no drop inside [from, to]
// lies there; the join does, and comes with the mark.
func RelayDropFailures(logPath string, at LogMark, from, to time.Time) []string {
	var out []string
	joined := at.Joined
	linked, dropped := []byte(linkedLine), []byte(relayDropMarker)
	_ = eachLogLine(logPath, at.Offset, func(line []byte) bool {
		isJoin := bytes.Contains(line, linked)
		if !isJoin && !bytes.Contains(line, dropped) {
			return true // the stamp is parsed only for a line that counts
		}
		stamp, ok := logTime(line)
		switch {
		case !ok: // a line without a stamp places nothing
		case isJoin:
			joined = stamp
		case !stamp.Before(from) && !stamp.After(to):
			out = append(out, relayDropLine(string(line), stamp, joined, from))
		}
		return true
	})
	return out
}

// serverJoined is the stamp of the first join line of a server log, zero
// when there is none or it carries no stamp. Read once the server has
// joined, while its log is short. ai-generated: this function (the
// streaming reader).
func serverJoined(logPath string) time.Time {
	var joined time.Time
	linked := []byte(linkedLine)
	_ = eachLogLine(logPath, 0, func(line []byte) bool {
		if !bytes.Contains(line, linked) {
			return true
		}
		joined, _ = logTime(line)
		return false
	})
	return joined
}

// eachLogLine calls fn with each line of the log at path from offset on,
// without its newline and cut to logLineMax bytes, until fn returns false or
// the log ends; a last line with no newline yet is a line. The slice is only
// valid during the call. It holds one buffer of logLineMax, whatever the
// log's size or its longest line. ai-generated: this function (the
// streaming reader).
func eachLogLine(path string, offset int64, fn func(line []byte) bool) error {
	f, err := os.Open(path)
	if err != nil {
		return fmt.Errorf("open log: %w", err)
	}
	defer func() { _ = f.Close() }()
	if _, err := f.Seek(offset, io.SeekStart); err != nil {
		return fmt.Errorf("seek log: %w", err)
	}
	r := bufio.NewReaderSize(f, logLineMax)
	for {
		line, err := r.ReadSlice('\n')
		if len(line) > 0 && !fn(bytes.TrimSuffix(line, []byte{'\n'})) {
			return nil
		}
		for errors.Is(err, bufio.ErrBufferFull) { // the rest of a long line
			_, err = r.ReadSlice('\n')
		}
		switch {
		case errors.Is(err, io.EOF):
			return nil
		case err != nil:
			return fmt.Errorf("read log: %w", err)
		}
	}
}

// withRelayDrops adds what the relay did to a cell that failed. A cell that
// passed is left alone, whatever the log says.
func withRelayDrops(failures []string, logPath string, at LogMark, from, to time.Time) []string {
	if len(failures) == 0 {
		return failures
	}
	return append(failures, RelayDropFailures(logPath, at, from, to)...)
}

// relayDropLine is one such failure, in the words of the reason the relay
// gave and the two times that place it.
func relayDropLine(line string, stamp, joined, from time.Time) string {
	reason := strings.Fields(line[strings.Index(line, relayDropMarker)+len(relayDropMarker):])
	name := "UNKNOWN_REASON"
	if len(reason) > 0 {
		name = reason[0]
	}
	msg := fmt.Sprintf("the relay ended the server's session (%s) %s into the cell",
		name, stamp.Sub(from).Round(time.Second))
	if !joined.IsZero() && !stamp.Before(joined) {
		msg += fmt.Sprintf(", %s after it joined", stamp.Sub(joined).Round(time.Second))
	}
	return msg
}

// logTime reads the stamp a server log line opens with.
func logTime(line []byte) (time.Time, bool) {
	if len(line) < len(logTimeLayout) {
		return time.Time{}, false
	}
	stamp, err := time.ParseInLocation(logTimeLayout, string(line[:len(logTimeLayout)]), time.Local)
	if err != nil {
		return time.Time{}, false
	}
	return stamp, true
}
