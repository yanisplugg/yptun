package gate

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"maps"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/link"
)

// ai-generated: whole file, cover for the scenarios' bodies against the
// loopback origin with no tunnel, a fake SOCKS relay, and fakes of a delayed
// server and a client: the logic, not the tunnel.

// directEnv is a cell's env without a tunnel: the origin reached directly,
// the process log captured into the cell's log, a sampler running.
func directEnv(t *testing.T) (*Env, *Origin) {
	t.Helper()
	dial, o := directHTTP(t)
	c := StartCapture()
	t.Cleanup(c.Stop)
	s := NewSampler(20 * time.Millisecond)
	s.Start()
	t.Cleanup(s.Stop)
	return &Env{
		Load: o.URLs, HTTP: HTTPClient(dial, 30*time.Second), Dial: dial,
		Sampler: s, Thresholds: Local, Log: c.Begin(), Dir: t.TempDir(), Logf: t.Logf,
	}, o
}

// scenario is the registered scenario with id.
func scenario(t *testing.T, id string) Scenario {
	t.Helper()
	for _, s := range Scenarios() {
		if s.ID == id {
			return s
		}
	}
	t.Fatalf("scenario %s not registered", id)
	return Scenario{}
}

func TestTheEightScenariosAreRegistered(t *testing.T) {
	want := []string{"S0 connect", "S1 idle burst", "S2 download saturation", "S3 upload saturation",
		"S4 quiet after load", "S5 resolver burst", "S6 late server bridge", "S7 phone memory"}
	got := make([]string, 0, len(want))
	for _, s := range Scenarios() {
		if s.Applies == nil || s.Run == nil {
			t.Fatalf("%s registered without Applies or Run", s.ID)
		}
		got = append(got, s.ID+" "+s.Name)
	}
	if !slices.Equal(got, want) {
		t.Fatalf("registered %q\nwant %q", got, want)
	}
}

func TestS0S1S2S3PassAgainstTheOrigin(t *testing.T) {
	env, o := directEnv(t)
	env.Tunnel = &Tunnel{SocksAddr: "direct"}
	env.Endpoint = Endpoint{Provider: "test", Transport: "datachannel"}
	env.handshake = 1500 * time.Millisecond
	want := map[string]Metrics{
		"S0": {MetricHandshakeMs: 1500, MetricPullOK: 1, MetricPushOK: 1},
		"S1": {MetricConnectOK: 48, MetricConnectTotal: 48},
		"S2": {MetricPullOK: 6, MetricPullTotal: 6},
		"S3": {MetricPushOK: 4, MetricPushTotal: 4},
	}
	for _, id := range []string{"S0", "S1", "S2", "S3"} {
		m, err := scenario(t, id).Run(context.Background(), env)
		if err != nil {
			t.Fatalf("%s error = %v", id, err)
		}
		if f := Evaluate(id, m, env.Thresholds); len(f) != 0 {
			t.Fatalf("%s failed on loopback: %v (metrics %v)", id, f, m)
		}
		for k, v := range want[id] {
			if m[k] != v {
				t.Fatalf("%s %s = %v, want %v (metrics %v)", id, k, m[k], v, m)
			}
		}
	}
	if got := o.SinkBytes(); got != 5*pushBytes {
		t.Fatalf("the sink took %d bytes, want S0's push and S3's four, %d", got, 5*pushBytes)
	}
	for _, mark := range []string{markIdle, markLoadStart} {
		if _, ok := env.Sampler.sampleAt(mark); !ok {
			t.Fatalf("no sample at %s for S7 to read", mark)
		}
	}
}

// TestS0FlagsWhatFailed runs S0 against an origin that is gone and without a
// handshake time: both transfers read 0 with their causes logged, and no
// handshake_ms is recorded, so a runner that never timed the client fails
// the cell instead of passing it at 0 ms.
func TestS0FlagsWhatFailed(t *testing.T) {
	env, o := directEnv(t)
	o.Close()
	var logged []string
	env.Logf = func(format string, args ...any) { logged = append(logged, fmt.Sprintf(format, args...)) }
	m, err := scenario(t, "S0").Run(context.Background(), env)
	if err != nil {
		t.Fatal(err)
	}
	if !maps.Equal(m, Metrics{MetricPullOK: 0, MetricPushOK: 0}) {
		t.Fatalf("S0 against no origin = %v", m)
	}
	if len(logged) != 2 || !strings.HasPrefix(logged[0], "S0 pull: ") || !strings.HasPrefix(logged[1], "S0 push: ") {
		t.Fatalf("S0 logged %q, want the pull's and the push's causes", logged)
	}
	f := Evaluate("S0", m, env.Thresholds)
	if !slices.ContainsFunc(f, func(s string) bool { return strings.Contains(s, "handshake_ms not measured") }) {
		t.Fatalf("S0 without a handshake time judged %v", f)
	}
}

func TestS4ReadsTheCapturedLog(t *testing.T) {
	env, _ := directEnv(t)
	quietAfterLoad = 50 * time.Millisecond
	t.Cleanup(func() { quietAfterLoad = 60 * time.Second })
	logPrintf("control missed pong role=client missed=1")
	// One reconnect writes its reason line and then a line per attempt.
	logPrintf("client reconnect reason=liveness - tearing down smux session")
	logPrintf("client reconnect attempt=1 reason=liveness")
	logPrintf("client reconnect attempt=2 reason=liveness")
	s4 := scenario(t, "S4")
	m, err := s4.Run(context.Background(), env)
	if err != nil {
		t.Fatal(err)
	}
	want := Metrics{MetricMissedPong: 1, MetricReconnects: 1, MetricAlive: 1, MetricFinalPullOK: 1}
	if !maps.Equal(m, want) {
		t.Fatalf("S4 metrics = %v, want %v", m, want)
	}
	if _, ok := env.Sampler.sampleAt(markQuietEnd); !ok {
		t.Fatalf("no sample at %s for S7 to read", markQuietEnd)
	}
	env.Log = &CellLog{}
	env.Log.add("Client link reported conference end: fake reason\n")
	if m, err = s4.Run(context.Background(), env); err != nil || m[MetricAlive] != 0 {
		t.Fatalf("S4 after a conference end = %v, %v; want alive 0", m, err)
	}
	// A death at the end of the load logs its reason line in S3's cell; the
	// fallback's attempts 30 s later run in the quiet with no reason line.
	env.Log = &CellLog{}
	env.Log.add("client reconnect: no provider callback within 30s - re-establishing session\n")
	env.Log.add("client reconnect attempt=1 reason=liveness-fallback\n")
	env.Log.add("client reconnect attempt=2 reason=liveness-fallback\n")
	if m, err = s4.Run(context.Background(), env); err != nil || m[MetricReconnects] != 1 {
		t.Fatalf("S4 over attempts alone = %v, %v; want one reconnect", m, err)
	}
}

func TestS4EndsWithItsContext(t *testing.T) {
	env, _ := directEnv(t)
	s4 := scenario(t, "S4")
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	var err error
	within(t, 5*time.Second, "S4 on a context that ends", func() { _, err = s4.Run(ctx, env) })
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("S4 = %v, want the context's end", err)
	}
	if _, ok := env.Sampler.sampleAt(markQuietEnd); ok {
		t.Fatal("S4 marked the end of a quiet it never finished")
	}
}

// engineSource is the non-test Go source of internal/<dir>, one string.
func engineSource(t *testing.T, dir string) string {
	t.Helper()
	fsys := os.DirFS(filepath.Join("..", dir))
	names, err := fs.Glob(fsys, "*.go")
	if err != nil || len(names) == 0 {
		t.Fatalf("no source in internal/%s: %v", dir, err)
	}
	var b strings.Builder
	for _, name := range names {
		if strings.HasSuffix(name, "_test.go") {
			continue
		}
		raw, err := fs.ReadFile(fsys, name)
		if err != nil {
			t.Fatal(err)
		}
		_, _ = b.Write(raw)
	}
	return b.String()
}

// TestS4CountsLinesTheEngineWrites pins what S4 counts to the engine's own
// log calls, each written in one place: a reworded line would leave S4
// counting nothing and passing a tunnel that died in the quiet, and a second
// call with the same text would count one event twice.
func TestS4CountsLinesTheEngineWrites(t *testing.T) {
	for _, c := range []struct{ dir, call string }{
		{"tunnelcore", `logger.Warnf("` + logMissedPong + " "},
		{"client", `logger.Infof("` + logReconnect},
		{"client", `logger.Infof("` + logReconnectAttempt},
		{"client", `logger.Infof("` + logConferenceEnd + ": "},
	} {
		if n := strings.Count(engineSource(t, c.dir), c.call); n != 1 {
			t.Errorf("internal/%s has %d calls %s, want one", c.dir, n, c.call)
		}
	}
}

func TestS5CountsEachBurstOnAnAssociationOfItsOwn(t *testing.T) {
	socks := startFakeSocks(t, func(r *net.UDPAddr) []byte { return udpReply(0, r.IP, r) })
	var opened atomic.Int32
	env := &Env{Logf: t.Logf, UDP: func(ctx context.Context) (*UDPAssoc, error) {
		opened.Add(1)
		return UDPAssociate(ctx, socks.addr())
	}}
	m, err := scenario(t, "S5").Run(context.Background(), env)
	if err != nil {
		t.Fatal(err)
	}
	// The fake relay sends every query back as its own answer.
	if !maps.Equal(m, Metrics{MetricAnswered1: 64, MetricAnswered2: 64}) || opened.Load() != 2 {
		t.Fatalf("S5 = %v over %d associations, want 64 and 64 over two", m, opened.Load())
	}
	if f := Evaluate("S5", m, Local); len(f) != 0 {
		t.Fatalf("S5 judged %v", f)
	}
}

// TestS5SkipsADatagramItCannotRead has each association's first datagram be
// one S5 cannot read: it is no answer, and the ones after it still count.
func TestS5SkipsADatagramItCannotRead(t *testing.T) {
	socks := startFakeSocks(t, func(r *net.UDPAddr) []byte { return udpReply(0, r.IP, r) })
	socks.junkFirst.Store(true)
	env := &Env{Logf: t.Logf, UDP: func(ctx context.Context) (*UDPAssoc, error) {
		return UDPAssociate(ctx, socks.addr())
	}}
	m, err := scenario(t, "S5").Run(context.Background(), env)
	if err != nil || !maps.Equal(m, Metrics{MetricAnswered1: 64, MetricAnswered2: 64}) {
		t.Fatalf("S5 past a datagram it cannot read = %v, %v; want 64 and 64", m, err)
	}
}

func TestS5NeedsAUDPAssociate(t *testing.T) {
	s5 := scenario(t, "S5")
	if _, err := s5.Run(context.Background(), &Env{Logf: t.Logf}); !errors.Is(err, ErrNoUDP) {
		t.Fatalf("S5 without a UDP associate = %v, want ErrNoUDP", err)
	}
	refused := startFakeSocks(t, func(r *net.UDPAddr) []byte { return udpReply(4, net.IPv4zero, r) })
	env := &Env{Logf: t.Logf, UDP: func(ctx context.Context) (*UDPAssoc, error) {
		return UDPAssociate(ctx, refused.addr())
	}}
	if _, err := s5.Run(context.Background(), env); !errors.Is(err, ErrSocksReply) {
		t.Fatalf("S5 on a refused associate = %v, want ErrSocksReply", err)
	}
}

// lateServers stands in for Env.Delayed: it records the delays asked for and
// the servers stopped, and fails the delays listed in fail.
type lateServers struct {
	mu      sync.Mutex
	delays  []time.Duration
	stopped int
	fail    map[time.Duration]bool
}

func (l *lateServers) open(_ context.Context, opt OpenOptions) (Endpoint, func(), error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.delays = append(l.delays, opt.BridgeDelay)
	if l.fail[opt.BridgeDelay] {
		return Endpoint{}, nil, errors.New("fake server never linked")
	}
	return fakeEndpoint("jitsi", "datachannel"), func() {
		l.mu.Lock()
		l.stopped++
		l.mu.Unlock()
	}, nil
}

// readyClient is a client that is ready after a while, or fails to start.
type readyClient struct {
	after            time.Duration
	err              error
	started, stopped atomic.Int32
}

func (*readyClient) Name() string { return "fake" }

func (c *readyClient) Start(context.Context, Endpoint) (*Tunnel, error) {
	c.started.Add(1)
	if c.err != nil {
		return nil, c.err
	}
	time.Sleep(c.after)
	return &Tunnel{SocksAddr: "127.0.0.1:1", Stop: func() { c.stopped.Add(1) }}, nil
}

func TestS6TimesTheClientAgainstEachLateBridge(t *testing.T) {
	servers := &lateServers{}
	client := &readyClient{after: 20 * time.Millisecond}
	m, err := scenario(t, "S6").Run(context.Background(), &Env{Logf: t.Logf, Client: client, Delayed: servers.open})
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(servers.delays, []time.Duration{3 * time.Second, 8 * time.Second}) {
		t.Fatalf("S6 asked for bridges %v late, want 3s then 8s", servers.delays)
	}
	for _, key := range []string{MetricReady3sMs, MetricReady8sMs} {
		if m[key] < 20 || m[key] > 5000 {
			t.Fatalf("%s = %v for a client ready after 20 ms", key, m[key])
		}
	}
	if servers.stopped != 2 || client.stopped.Load() != 2 {
		t.Fatalf("stopped %d servers and %d tunnels, want both of each", servers.stopped, client.stopped.Load())
	}
	// ai-generated: these fake servers hold no bridge back, and a client
	// ready in 20 ms against them is what a dead hook looks like.
	if f := Evaluate("S6", m, Local); len(f) != 2 || !strings.Contains(strings.Join(f, ";"), "was not late") {
		t.Fatalf("S6 against servers that were not late judged %v, want both delays failed", f)
	}
}

func TestS6RecordsNeverReadyAndStillStopsTheServer(t *testing.T) {
	s6 := scenario(t, "S6")
	servers := &lateServers{fail: map[time.Duration]bool{3 * time.Second: true}}
	client := &readyClient{err: errors.New("fake client never ready")}
	m, err := s6.Run(context.Background(), &Env{Logf: t.Logf, Client: client, Delayed: servers.open})
	if err != nil {
		t.Fatal(err)
	}
	if !maps.Equal(m, Metrics{MetricReady3sMs: 0, MetricReady8sMs: 0}) {
		t.Fatalf("S6 with a dead server and a dead client = %v, want both never ready", m)
	}
	if client.started.Load() != 1 || servers.stopped != 1 {
		t.Fatalf("started %d clients and stopped %d servers, want the 8 s pair's one of each",
			client.started.Load(), servers.stopped)
	}
	if _, err := s6.Run(context.Background(), &Env{Logf: t.Logf}); !errors.Is(err, ErrNoDelayedServer) {
		t.Fatalf("S6 on a target that cannot delay a server = %v, want ErrNoDelayedServer", err)
	}
}

// TestS7ReadsTheWindowAndTheMarks gives S7 a sampler that saw the runner's
// baseline and S0 to S6: the peaks come from S2's start to S4's end alone,
// the baseline and the goroutines from the marks, and nothing from after S4,
// where S5's queries and S6's clients still run. The heap profiles counted
// are the ones written in the window, whose garbage its peak may carry.
func TestS7ReadsTheWindowAndTheMarks(t *testing.T) {
	s := NewSampler(time.Second)
	t0 := time.Now()
	at := func(ms int) time.Time { return t0.Add(time.Duration(ms) * time.Millisecond) }
	s.samples = []Sample{
		{At: at(-10), HeapInuse: 3 << 20, RSS: 28 << 20, Goroutines: 12}, // the baseline, before the client
		{At: at(0), HeapInuse: 1 << 20, RSS: 20 << 20, Goroutines: 90},   // S0's transfers
		{At: at(10), HeapInuse: 2 << 20, RSS: 21 << 20, Goroutines: 40},  // S1's mark
		{At: at(15), HeapInuse: 3 << 20, RSS: 22 << 20, Goroutines: 200}, // S1's burst
		{At: at(20), HeapInuse: 5 << 20, RSS: 30 << 20, Goroutines: 150}, // S2's mark
		{At: at(30), HeapInuse: 9 << 20, RSS: 33 << 20, Goroutines: 160}, // S3
		{At: at(40), HeapInuse: 4 << 20, RSS: 31 << 20, Goroutines: 45},  // S4's end
		{At: at(50), HeapInuse: 30 << 20, RSS: 60 << 20, Goroutines: 300},
	}
	s.marks[markBaseline] = at(-10)
	s.marks[markIdle], s.marks[markLoadStart], s.marks[markQuietEnd] = at(10), at(20), at(40)
	s.profiled = []time.Time{at(15), at(20), at(30), at(40), at(50)} // ai-generated: profiles on jumps
	m, err := scenario(t, "S7").Run(context.Background(), &Env{Sampler: s})
	if err != nil {
		t.Fatal(err)
	}
	want := Metrics{MetricHeapBaselineBytes: 3 << 20, MetricRSSBaselineBytes: 28 << 20,
		MetricHeapPeakBytes: 9 << 20, MetricRSSPeakBytes: 33 << 20, MetricGoroutinesIdle: 40, MetricGoroutinesAfter: 45,
		MetricHeapProfilesInWindow: 2}
	if !maps.Equal(m, want) {
		t.Fatalf("S7 = %v\nwant %v", m, want)
	}
	if f := Evaluate("S7", m, Local); len(f) != 0 {
		t.Fatalf("S7 judged %v", f)
	}
}

func TestS7ReadsALiveSampler(t *testing.T) {
	env, _ := directEnv(t)
	env.Sampler.Mark(markBaseline)
	env.Sampler.Mark(markIdle)
	env.Sampler.Mark(markLoadStart)
	time.Sleep(60 * time.Millisecond)
	env.Sampler.Mark(markQuietEnd)
	m, err := scenario(t, "S7").Run(context.Background(), env)
	if err != nil {
		t.Fatal(err)
	}
	if m[MetricHeapPeakBytes] <= 0 || m[MetricHeapBaselineBytes] <= 0 || m[MetricGoroutinesIdle] <= 0 ||
		m[MetricGoroutinesAfter] <= 0 {
		t.Fatalf("S7 metrics = %v", m)
	}
	if runtime.GOOS == "linux" && (m[MetricRSSPeakBytes] <= 0 || m[MetricRSSBaselineBytes] <= 0) {
		t.Fatalf("S7 read no RSS on linux: %v", m)
	}
}

func TestS7NamesWhatItCannotRead(t *testing.T) {
	s7 := scenario(t, "S7")
	stopped := NewSampler(time.Second) // never started: a mark takes no sample
	for _, mark := range []string{markBaseline, markIdle, markLoadStart, markQuietEnd} {
		stopped.Mark(mark)
	}
	if _, err := s7.Run(context.Background(), &Env{Sampler: stopped}); !errors.Is(err, ErrNoSample) {
		t.Fatalf("S7 on a sampler that never ran = %v, want ErrNoSample", err)
	}
	env, _ := directEnv(t)
	env.Sampler.Mark(markLoadStart)
	env.Sampler.Mark(markQuietEnd)
	// ai-generated: without the runner's baseline there is no growth to judge.
	_, err := s7.Run(context.Background(), env)
	if !errors.Is(err, ErrNoSample) || !strings.Contains(err.Error(), markBaseline) {
		t.Fatalf("S7 without the runner's baseline = %v, want ErrNoSample naming %s", err, markBaseline)
	}
	env.Sampler.Mark(markBaseline)
	_, err = s7.Run(context.Background(), env)
	if !errors.Is(err, ErrNoSample) || !strings.Contains(err.Error(), markIdle) {
		t.Fatalf("S7 without S1's mark = %v, want ErrNoSample naming %s", err, markIdle)
	}
}

// planned lists a plan's cells without the platform, the way the test reads.
func planned(target Target, clients ...string) []string {
	cells := PlanCells(target, clients)
	out := make([]string, 0, len(cells))
	for _, c := range cells {
		out = append(out, strings.TrimPrefix(c.ID, c.Platform+"/"))
	}
	return out
}

// TestScenariosApplyWhereTheSpecRunsThem walks the plan (spec section 4,
// amendment A1): S0 everywhere; S1-S5 and S7 with the mobile flavour, on one
// pair per provider of the local target or on the link's own pair; S6 on
// the local target's jitsi/datachannel with both flavours.
func TestScenariosApplyWhereTheSpecRunsThem(t *testing.T) {
	lt, err := NewLocalTarget(LocalOptions{WorkDir: t.TempDir(), JitsiHosts: []string{"meet.example.invalid"},
		Providers: []string{"jitsi", "telemost", "salutejazz"}, Transports: []string{"datachannel", "vp8channel"}})
	if err != nil {
		t.Fatal(err)
	}
	want := make([]string, 0, 30)
	want = append(want, "jitsi/datachannel/cli/S0", "jitsi/datachannel/mobile/S0", "jitsi/vp8channel/cli/S0",
		"jitsi/vp8channel/mobile/S0", "telemost/vp8channel/cli/S0", "telemost/vp8channel/mobile/S0",
		"salutejazz/datachannel/cli/S0", "salutejazz/datachannel/mobile/S0")
	for _, id := range []string{"S1", "S2", "S3", "S4", "S5"} {
		want = append(want, "jitsi/datachannel/mobile/"+id, "telemost/vp8channel/mobile/"+id,
			"salutejazz/datachannel/mobile/"+id)
	}
	want = append(want, "jitsi/datachannel/cli/S6", "jitsi/datachannel/mobile/S6",
		"jitsi/datachannel/mobile/S7", "telemost/vp8channel/mobile/S7", "salutejazz/datachannel/mobile/S7")
	if got := planned(lt, "cli", "mobile"); !slices.Equal(got, want) {
		t.Fatalf("local plan:\n%q\nwant\n%q", got, want)
	}
	fake := link.Link{Provider: "telemost", Transport: "videochannel", Room: "fake-telemost-room",
		Key: strings.Repeat("cd", 32)}
	lk := NewLinkTarget(fake, LoadURLs{}, "192.0.2.53:53")
	wantLink := make([]string, 0, 8)
	wantLink = append(wantLink, "telemost/videochannel/cli/S0", "telemost/videochannel/mobile/S0")
	for _, id := range []string{"S1", "S2", "S3", "S4", "S5", "S7"} {
		wantLink = append(wantLink, "telemost/videochannel/mobile/"+id)
	}
	if got := planned(lk, "cli", "mobile"); !slices.Equal(got, wantLink) {
		t.Fatalf("link plan:\n%q\nwant\n%q", got, wantLink)
	}
	s6 := scenario(t, "S6")
	if s6.Applies(fakeTarget{}, Pair{"jitsi", "datachannel"}, "cli") {
		t.Fatal("S6 must not apply to a target that cannot delay a server")
	}
}
