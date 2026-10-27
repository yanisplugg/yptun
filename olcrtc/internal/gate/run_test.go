package gate

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/engine/builtin"
	"github.com/openlibrecommunity/olcrtc/internal/link"
)

// ai-generated: whole file, unit cover for the runner: every planned cell
// ends pass or fail through a subtest of its own, a stuck or panicking
// scenario fails its cell alone, a server gets one more try, the run starts
// no cell once it has ended, and no room, key or token of the run leaves it.

// Made-up secrets of a run: none of them is real.
const (
	fakeRoomURL  = "https://meet.example.invalid/gate-fakeroom0001"
	fakeRoomSlug = "gate-fakeroom0001"
	fakeChannel  = "gate-fakechan0001"
	fakeToken    = "fake-wb-token-0001"
	fakeKey      = "5a5a5a5a5a5a5a5a" + "5a5a5a5a5a5a5a5a" + "5a5a5a5a5a5a5a5a" + "5a5a5a5a5a5a5a5a"
)

// fakeClient comes up on an address nothing listens on: the runner builds its
// dialer, and the scenarios here never dial. It can write a line to the
// process log as it starts, take a while, and fail.
type fakeClient struct {
	name    string
	line    string
	delay   time.Duration
	err     error
	first   []error // the first starts fail with these, in order, before err applies
	started atomic.Int32
	stopped atomic.Int32
	at      atomic.Pointer[time.Time] // when Start was last called
}

func (c *fakeClient) Name() string { return c.name }

// startedAt is when Start was last called; the zero time before any call.
func (c *fakeClient) startedAt() time.Time {
	if at := c.at.Load(); at != nil {
		return *at
	}
	return time.Time{}
}

func (c *fakeClient) Start(context.Context, Endpoint) (*Tunnel, error) {
	now := time.Now()
	c.at.Store(&now)
	n := int(c.started.Add(1))
	if c.line != "" {
		log.Print(c.line)
	}
	time.Sleep(c.delay)
	if n <= len(c.first) {
		return nil, c.first[n-1]
	}
	if c.err != nil {
		return nil, c.err
	}
	return &Tunnel{SocksAddr: "127.0.0.1:1", Stop: func() { c.stopped.Add(1) }}, nil
}

// scriptTarget fails its first opens with errs, in order, and then hands out
// ep for the pair asked. It counts the opens and the stops.
type scriptTarget struct {
	pairs []Pair
	ep    Endpoint
	errs  []error
	mu    sync.Mutex
	opens int
	stops int
}

func (*scriptTarget) Name() string     { return "fake" }
func (*scriptTarget) Platform() string { return "engine-test" }
func (s *scriptTarget) Pairs() []Pair  { return s.pairs }
func (*scriptTarget) Load() LoadURLs   { return LoadURLs{} }
func (s *scriptTarget) counts() (int, int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.opens, s.stops
}

func (s *scriptTarget) Open(_ context.Context, p Pair, _ string, _ OpenOptions) (Endpoint, func(), error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.opens++
	if s.opens <= len(s.errs) {
		return Endpoint{}, nil, s.errs[s.opens-1]
	}
	ep := s.ep
	ep.Provider, ep.Transport = p.Provider, p.Transport
	return ep, func() {
		s.mu.Lock()
		s.stops++
		s.mu.Unlock()
	}, nil
}

// harness is a runner test's world: options over a fresh recorder, capture
// and artifacts directory, the lines the runner logged and what each cell's
// run returned, by the cell's name.
type harness struct {
	opt     Options
	secrets []string
	mu      sync.Mutex
	lines   []string
	errs    map[string]error
}

func newHarness(t *testing.T, target Target, clients ...Client) *harness {
	t.Helper()
	capture := StartCapture()
	t.Cleanup(capture.Stop)
	h := &harness{errs: map[string]error{}}
	h.opt = Options{
		Target: target, Clients: clients, Thresholds: Local, Dir: t.TempDir(),
		Recorder: NewRecorder(Report{Target: target.Name()}), Capture: capture, Secrets: &h.secrets, Logf: h.logf,
	}
	return h
}

func (h *harness) logf(format string, args ...any) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.lines = append(h.lines, fmt.Sprintf(format, args...))
}

// run walks the plan with every cell a subtest of t, as the entry does, and
// returns the report.
func (h *harness) run(ctx context.Context, t *testing.T) Report {
	t.Helper()
	RunPlan(ctx, h.opt, func(name string, cell func() error) {
		t.Run(name, func(*testing.T) { h.errs[name] = cell() })
	})
	return h.opt.Recorder.Report()
}

// fixed is a scenario that measures m and returns err whatever the tunnel.
func fixed(id string, m Metrics, err error) Scenario {
	return Scenario{ID: id, Name: "fixed " + id, Applies: always, Run: func(context.Context, *Env) (Metrics, error) {
		return m, err
	}}
}

// Metrics that pass S0, S1 and S7.
func passS0() Metrics { return Metrics{MetricHandshakeMs: 10, MetricPullOK: 1, MetricPushOK: 1} }
func passS1() Metrics {
	return Metrics{MetricConnectOK: 2, MetricConnectTotal: 2, MetricConnectP95Ms: 1}
}
func passS7() Metrics {
	return Metrics{MetricHeapBaselineBytes: 1 << 20, MetricHeapPeakBytes: 2 << 20, MetricRSSBaselineBytes: 20 << 20,
		MetricRSSPeakBytes: 21 << 20, MetricGoroutinesIdle: 10, MetricGoroutinesAfter: 10}
}

// cellsByID indexes a report's cells.
func cellsByID(rep Report) map[string]Cell {
	out := make(map[string]Cell, len(rep.Cells))
	for _, c := range rep.Cells {
		out[c.ID] = c
	}
	return out
}

func TestRunPlanRecordsEveryCellThroughASubtestOfItsOwn(t *testing.T) {
	resetRegistryForTest(t)
	Register(fixed("S0", passS0(), nil))
	Register(fixed("S1", Metrics{MetricConnectOK: 1, MetricConnectTotal: 2, MetricConnectP95Ms: 1}, nil))
	target := &scriptTarget{pairs: []Pair{{"jitsi", "datachannel"}}}
	client := &fakeClient{name: "cli"}
	h := newHarness(t, target, client)
	rep := h.run(context.Background(), t)
	if rep.Planned != 2 || rep.Executed != 2 || rep.Passed != 1 || rep.Failed != 1 {
		t.Fatalf("report = planned %d executed %d passed %d failed %d", rep.Planned, rep.Executed, rep.Passed, rep.Failed)
	}
	for _, c := range rep.Cells {
		if strings.ContainsAny(c.ID, "@#") {
			t.Fatalf("cell id carries link material: %s", c.ID)
		}
		if want := "jitsi-datachannel/cli-" + c.Scenario + ".log"; c.Log != want {
			t.Fatalf("%s log = %q, want %q, relative to the artifacts", c.ID, c.Log, want)
		}
		if _, err := os.Stat(filepath.Join(h.opt.Dir, c.Log)); err != nil {
			t.Fatalf("%s: %v", c.ID, err)
		}
		if c.Thresholds[MetricConnectP95Ms] != 5000 {
			t.Fatalf("%s carries thresholds %v, not Local's", c.ID, c.Thresholds)
		}
	}
	s1 := h.errs["jitsi/datachannel/cli/S1"]
	if err := h.errs["jitsi/datachannel/cli/S0"]; err != nil || len(h.errs) != 2 {
		t.Fatalf("cell runs = %v, want S0 passed and every cell run under its name", h.errs)
	}
	if !errors.Is(s1, ErrCellFailed) || !strings.Contains(s1.Error(), "connect_ok 1 of connect_total 2") {
		t.Fatalf("S1's run = %v, want the verdict", s1)
	}
	if opens, stops := target.counts(); opens != 1 || stops != 1 || client.started.Load() != 1 ||
		client.stopped.Load() != 1 {
		t.Fatalf("opened %d servers, stopped %d, started %d clients, stopped %d; want one of each",
			opens, stops, client.started.Load(), client.stopped.Load())
	}
	if _, err := os.Stat(filepath.Join(h.opt.Dir, "jitsi-datachannel", "cli-start.log")); err != nil {
		t.Fatalf("no start log: %v", err)
	}
}

// ai-generated: a pair is judged by its provider's thresholds, SaluteJazz's
// floors for its cells and the target's for the others, in the verdict and in
// what the report records next to the metrics, for a cell that ran and for
// one whose server never came up.
func TestRunPlanJudgesAPairByItsProvidersThresholds(t *testing.T) {
	resetRegistryForTest(t)
	sj := Local.For("salutejazz")
	Register(fixed("S2", Metrics{MetricPullOK: 1, MetricPullTotal: 1, MetricThroughputDownBps: sj.ThroughputDownBps,
		MetricOnTopOK: 1, MetricOnTopTotal: 1, MetricOnTopP95Ms: 1}, nil))
	target := &scriptTarget{pairs: []Pair{{"jitsi", "datachannel"}, {"salutejazz", "datachannel"}}}
	cells := cellsByID(newHarness(t, target, &fakeClient{name: "cli"}).run(context.Background(), t))
	jitsi, jazz := cells["engine-test/jitsi/datachannel/cli/S2"], cells["engine-test/salutejazz/datachannel/cli/S2"]
	if jazz.Status != "pass" || jitsi.Status != "fail" {
		t.Fatalf("at %v bps: salutejazz %s, jitsi %s; want salutejazz at its own floor, jitsi under Local's",
			sj.ThroughputDownBps, jazz.Status, jitsi.Status)
	}
	if jazz.Thresholds[MetricThroughputDownBps] != sj.ThroughputDownBps ||
		jitsi.Thresholds[MetricThroughputDownBps] != Local.ThroughputDownBps {
		t.Fatalf("recorded floors: salutejazz %v, jitsi %v", jazz.Thresholds, jitsi.Thresholds)
	}
	never := &scriptTarget{pairs: []Pair{{"salutejazz", "datachannel"}}, errs: []error{ErrPairNotCarried}}
	cell := cellsByID(newHarness(t, never, &fakeClient{name: "cli"}).run(context.Background(), t))["engine-test/salutejazz/datachannel/cli/S2"]
	if cell.Status != "fail" || cell.Thresholds[MetricThroughputDownBps] != sj.ThroughputDownBps {
		t.Fatalf("a cell whose server never came up: %s with %v, want a failure under salutejazz's floors",
			cell.Status, cell.Thresholds)
	}
}

// ai-generated: the report on disk follows the run, a cell at a time.
func TestRunPlanKeepsTheReportOnDiskAfterEveryCell(t *testing.T) {
	resetRegistryForTest(t)
	path := filepath.Join(t.TempDir(), reportName)
	var seen [][]byte // the report as S1 found it on disk, read in S1's subtest
	look := func(context.Context, *Env) (Metrics, error) {
		raw, err := os.ReadFile(path)
		seen = append(seen, raw)
		return passS1(), err
	}
	Register(fixed("S0", passS0(), nil))
	Register(Scenario{ID: "S1", Applies: always, Run: look})
	h := newHarness(t, &scriptTarget{pairs: []Pair{{"jitsi", "datachannel"}}}, &fakeClient{name: "cli"})
	h.opt.ReportPath = path
	h.run(context.Background(), t)
	var during Report
	if len(seen) != 1 || json.Unmarshal(seen[0], &during) != nil {
		t.Fatalf("S1 found %q on disk", seen)
	}
	if cells := cellsByID(during); cells["engine-test/jitsi/datachannel/cli/S0"].Status != StatusPass ||
		!slices.Equal(cells["engine-test/jitsi/datachannel/cli/S1"].Failures, []string{reasonDidNotRun}) {
		t.Fatalf("the report on disk while S1 ran = %+v, want S0 passed and S1 not run yet", during.Cells)
	}
	if after := readReport(t, path); after.Passed != 2 {
		t.Fatalf("the report on disk after the run = %+v, want both cells passed", after.Cells)
	}
}

// envCrashReport is where the child run of
// TestRunPlanLeavesAReportWhenTheProcessDies keeps its report.
const envCrashReport = "OLCRTC_GATE_TEST_CRASH_REPORT"

// ai-generated: a panic no recover in the runner reaches, on a goroutine of
// the client or of the load, ends the process with no TestMain write. The
// report the runner kept on disk still holds the cell that finished and
// fails the one the process died in and the one after it as not run.
func TestRunPlanLeavesAReportWhenTheProcessDies(t *testing.T) {
	if path := os.Getenv(envCrashReport); path != "" {
		crashingRun(t, path)
		return
	}
	path := filepath.Join(t.TempDir(), reportName)
	// #nosec G204,G702 -- the test runs its own binary with fixed arguments.
	cmd := exec.CommandContext(t.Context(), os.Args[0],
		"-test.run=^TestRunPlanLeavesAReportWhenTheProcessDies$", "-test.timeout=2m")
	cmd.Env = append(os.Environ(), envCrashReport+"="+path)
	out, err := cmd.CombinedOutput()
	if err == nil || !strings.Contains(string(out), "fake crash") {
		t.Fatalf("the child run did not die of its panic: %v\n%s", err, out)
	}
	cells := cellsByID(readReport(t, path))
	if c := cells["engine-test/jitsi/datachannel/cli/S0"]; c.Status != StatusPass {
		t.Fatalf("the cell finished before the crash = %+v", c)
	}
	for _, id := range []string{"engine-test/jitsi/datachannel/cli/S1", "engine-test/jitsi/datachannel/cli/S2"} {
		if c := cells[id]; c.Status != StatusFail || !slices.Equal(c.Failures, []string{reasonDidNotRun}) {
			t.Fatalf("%s = %+v, want it failed as not run", id, c)
		}
	}
}

// crashingRun walks a plan whose second cell starts a goroutine that
// panics, as a client or a load worker might, and waits: the process dies
// there.
func crashingRun(t *testing.T, path string) {
	t.Helper()
	resetRegistryForTest(t)
	Register(fixed("S0", passS0(), nil))
	Register(Scenario{ID: "S1", Applies: always, Run: func(ctx context.Context, _ *Env) (Metrics, error) {
		go func() { panic("fake crash on a goroutine of the client") }()
		<-ctx.Done()
		return nil, ctx.Err()
	}})
	Register(fixed("S2", nil, nil))
	h := newHarness(t, &scriptTarget{pairs: []Pair{{"jitsi", "datachannel"}}}, &fakeClient{name: "cli"})
	h.opt.ReportPath = path
	h.run(context.Background(), t)
	t.Fatal("the run outlived its crash")
}

// readReport reads a report the runner wrote.
func readReport(t *testing.T, path string) Report {
	t.Helper()
	var rep Report
	if err := json.Unmarshal([]byte(readTargetFile(t, path)), &rep); err != nil {
		t.Fatal(err)
	}
	return rep
}

// ai-generated: the baseline S7 judges memory growth over is read before the
// client starts, whatever the client does on its way up.
func TestRunPlanReadsTheBaselineBeforeTheClientStarts(t *testing.T) {
	resetRegistryForTest(t)
	var (
		baseline Sample
		marked   bool
	)
	Register(Scenario{ID: "S7", Applies: always, Run: func(_ context.Context, env *Env) (Metrics, error) {
		baseline, marked = env.Sampler.sampleAt(markBaseline)
		return passS7(), nil
	}})
	client := &fakeClient{name: "mobile", delay: 20 * time.Millisecond}
	start := time.Now()
	newHarness(t, &scriptTarget{pairs: []Pair{{"jitsi", "datachannel"}}}, client).run(context.Background(), t)
	if !marked || baseline.At.Before(start) || !baseline.At.Before(client.startedAt()) {
		t.Fatalf("baseline %+v (marked %t), client started at %v: want a reading taken before the start",
			baseline, marked, client.startedAt())
	}
}

func TestRunPlanTimesTheClientsStartForS0(t *testing.T) {
	resetRegistryForTest(t)
	var handshake time.Duration
	Register(Scenario{ID: "S0", Applies: always, Run: func(_ context.Context, env *Env) (Metrics, error) {
		handshake = env.handshake
		return passS0(), nil
	}})
	h := newHarness(t, &scriptTarget{pairs: []Pair{{"jitsi", "datachannel"}}},
		&fakeClient{name: "cli", delay: 30 * time.Millisecond})
	h.run(context.Background(), t)
	if handshake < 30*time.Millisecond || handshake > 5*time.Second {
		t.Fatalf("handshake = %s for a client that took 30 ms", handshake)
	}
}

func TestRunCellTimesOutAStuckScenario(t *testing.T) {
	cellDeadline = 100 * time.Millisecond
	t.Cleanup(func() { cellDeadline = 5 * time.Minute })
	stuck := Scenario{ID: "S0", Run: func(ctx context.Context, _ *Env) (Metrics, error) {
		<-ctx.Done()
		return nil, ctx.Err()
	}}
	env := &Env{Logf: func(string, ...any) {}, Log: &CellLog{}, Thresholds: Local}
	m, failures, took := RunCell(context.Background(), env, stuck)
	if m == nil || took > time.Second || !slices.Contains(failures, "cell deadline 100ms reached") {
		t.Fatalf("stuck scenario: metrics %v, failures %q, took %v", m, failures, took)
	}
}

func TestRunCellFailsAPanickingScenarioAlone(t *testing.T) {
	resetRegistryForTest(t)
	Register(Scenario{ID: "S0", Applies: always, Run: func(context.Context, *Env) (Metrics, error) {
		panic("fake scenario bug")
	}})
	Register(fixed("S1", passS1(), nil))
	h := newHarness(t, &scriptTarget{pairs: []Pair{{"jitsi", "datachannel"}}}, &fakeClient{name: "cli"})
	cells := cellsByID(h.run(context.Background(), t))
	s0 := cells["engine-test/jitsi/datachannel/cli/S0"]
	if s0.Status != StatusFail || !slices.Contains(s0.Failures, "scenario error: scenario panicked: fake scenario bug") {
		t.Fatalf("panicking S0 = %+v", s0)
	}
	if s1 := cells["engine-test/jitsi/datachannel/cli/S1"]; s1.Status != StatusPass {
		t.Fatalf("the cell after a panic = %+v, want it run and passed", s1)
	}
}

func TestRunPlanTriesAServerOnceMoreThenFailsItsCells(t *testing.T) {
	resetRegistryForTest(t)
	openRetryDelay = 10 * time.Millisecond
	t.Cleanup(func() { openRetryDelay = 30 * time.Second })
	Register(fixed("S0", passS0(), nil))
	Register(fixed("S1", passS1(), nil))
	target := &scriptTarget{pairs: []Pair{{"telemost", "vp8channel"}},
		errs: []error{errors.New("fake: status 429"), errors.New("fake: status 403")}}
	client := &fakeClient{name: "mobile"}
	h := newHarness(t, target, client)
	rep := h.run(context.Background(), t)
	if opens, _ := target.counts(); opens != 2 || client.started.Load() != 0 {
		t.Fatalf("opened %d times and started %d clients, want two opens and no client", opens, client.started.Load())
	}
	if rep.Planned != 2 || rep.Executed != 2 || rep.Failed != 2 {
		t.Fatalf("report = planned %d executed %d failed %d", rep.Planned, rep.Executed, rep.Failed)
	}
	want := "server: tried twice, 10ms apart: fake: status 403"
	for _, c := range rep.Cells {
		if !slices.Equal(c.Failures, []string{want}) {
			t.Fatalf("%s failures = %q, want %q", c.ID, c.Failures, want)
		}
		name := strings.TrimPrefix(c.ID, "engine-test/")
		if err := h.errs[name]; !errors.Is(err, ErrCellFailed) || !strings.Contains(err.Error(), want) {
			t.Fatalf("%s ran as %v", name, err)
		}
	}
	if !slices.ContainsFunc(h.lines, func(l string) bool { return strings.Contains(l, "fake: status 429") }) {
		t.Fatalf("the first failure was not logged: %q", h.lines)
	}
}

func TestRunPlanRunsAPairWhoseServerCameUpOnTheRetry(t *testing.T) {
	resetRegistryForTest(t)
	openRetryDelay = 10 * time.Millisecond
	t.Cleanup(func() { openRetryDelay = 30 * time.Second })
	Register(fixed("S0", passS0(), nil))
	target := &scriptTarget{pairs: []Pair{{"jitsi", "datachannel"}}, errs: []error{errors.New("fake: status 503")}}
	rep := newHarness(t, target, &fakeClient{name: "cli"}).run(context.Background(), t)
	if opens, stops := target.counts(); opens != 2 || stops != 1 || rep.Passed != 1 {
		t.Fatalf("opens %d stops %d passed %d, want the second open to carry the cell", opens, stops, rep.Passed)
	}
}

// ai-generated: the client-start retry tests.
// refused is a client error as the engine reports a provider's refusal.
func refused(why string) error {
	return fmt.Errorf("cli client: run public client: open engine session: %w: %s", builtin.ErrAuthFailed, why)
}

func TestRunPlanTriesAClientTheProviderRefusedOnceMore(t *testing.T) {
	resetRegistryForTest(t)
	openRetryDelay = 10 * time.Millisecond
	t.Cleanup(func() { openRetryDelay = 30 * time.Second })
	Register(fixed("S0", passS0(), nil))
	client := &fakeClient{name: "cli", first: []error{refused("guest register failed: status 502")}}
	h := newHarness(t, &scriptTarget{pairs: []Pair{{"wbstream", "vp8channel"}}}, client)
	rep := h.run(context.Background(), t)
	if client.started.Load() != 2 || rep.Passed != 1 || rep.Failed != 0 {
		t.Fatalf("started %d, passed %d, failed %d; want the second start to carry the cell",
			client.started.Load(), rep.Passed, rep.Failed)
	}
	if !slices.ContainsFunc(h.lines, func(l string) bool { return strings.Contains(l, "refused the client") }) {
		t.Fatalf("the first refusal was not logged: %q", h.lines)
	}
}

func TestRunPlanFailsAClientTheProviderRefusedTwice(t *testing.T) {
	resetRegistryForTest(t)
	openRetryDelay = 10 * time.Millisecond
	t.Cleanup(func() { openRetryDelay = 30 * time.Second })
	Register(fixed("S0", passS0(), nil))
	client := &fakeClient{name: "cli", err: refused("status 502")}
	rep := newHarness(t, &scriptTarget{pairs: []Pair{{"wbstream", "vp8channel"}}}, client).run(context.Background(), t)
	if client.started.Load() != 2 || rep.Failed != 1 {
		t.Fatalf("started %d, failed %d; want two starts and a failed cell", client.started.Load(), rep.Failed)
	}
	if got := rep.Cells[0].Failures; len(got) != 1 || !strings.Contains(got[0], "tried twice, 10ms apart") {
		t.Fatalf("failures = %q", got)
	}
}

func TestRunPlanDoesNotRetryAClientTheProviderDidNotRefuse(t *testing.T) {
	resetRegistryForTest(t)
	openRetryDelay = time.Hour // a retry would hang the test
	t.Cleanup(func() { openRetryDelay = 30 * time.Second })
	Register(fixed("S0", passS0(), nil))
	client := &fakeClient{name: "cli", err: errors.New("handshake: peer did not answer the handshake")}
	var rep Report
	within(t, 5*time.Second, "a client whose failure a retry cannot change", func() {
		rep = newHarness(t, &scriptTarget{pairs: []Pair{{"jitsi", "datachannel"}}}, client).run(context.Background(), t)
	})
	if client.started.Load() != 1 || rep.Failed != 1 {
		t.Fatalf("started %d, failed %d; want one start", client.started.Load(), rep.Failed)
	}
}

func TestRunPlanDoesNotRetryWhatASecondTryCannotChange(t *testing.T) {
	openRetryDelay = time.Hour // a retry would hang the test
	t.Cleanup(func() { openRetryDelay = 30 * time.Second })
	for _, err := range []error{
		ErrNoWBStreamToken,
		fmt.Errorf("telemost: %w: the pool is empty", ErrPoolRoom),
		fmt.Errorf("%w: wbstream/datachannel", ErrPairNotCarried),
	} {
		resetRegistryForTest(t)
		Register(fixed("S0", passS0(), nil))
		target := &scriptTarget{pairs: []Pair{{"wbstream", "vp8channel"}}, errs: []error{err}}
		var rep Report
		within(t, 5*time.Second, "a plan with a server that cannot come up", func() {
			rep = newHarness(t, target, &fakeClient{name: "mobile"}).run(context.Background(), t)
		})
		opens, _ := target.counts()
		if opens != 1 || rep.Failed != 1 || !slices.Equal(rep.Cells[0].Failures, []string{"server: " + err.Error()}) {
			t.Fatalf("%v: opened %d times, cells %+v", err, opens, rep.Cells)
		}
	}
}

func TestRunPlanFailsTheCellsOfAClientThatNeverStarts(t *testing.T) {
	resetRegistryForTest(t)
	Register(fixed("S0", passS0(), nil))
	target := &scriptTarget{pairs: []Pair{{"jitsi", "datachannel"}}, ep: Endpoint{Room: fakeRoomURL, Key: fakeKey}}
	dead := &fakeClient{name: "cli", err: fmt.Errorf("fake: joining %s: status 429", fakeRoomURL)}
	live := &fakeClient{name: "mobile"}
	h := newHarness(t, target, dead, live)
	cells := cellsByID(h.run(context.Background(), t))
	want := "client: fake: joining <room>: status 429"
	if c := cells["engine-test/jitsi/datachannel/cli/S0"]; !slices.Equal(c.Failures, []string{want}) || c.Log != "" {
		t.Fatalf("the dead client's cell = %+v, want %q", c, want)
	}
	if err := h.errs["jitsi/datachannel/cli/S0"]; !errors.Is(err, ErrCellFailed) || !strings.Contains(err.Error(), want) {
		t.Fatalf("the dead client's cell ran as %v", err)
	}
	if c := cells["engine-test/jitsi/datachannel/mobile/S0"]; c.Status != StatusPass {
		t.Fatalf("the live client's cell = %+v", c)
	}
	for _, name := range []string{"cli-start.log", "mobile-start.log"} {
		if _, err := os.Stat(filepath.Join(h.opt.Dir, "jitsi-datachannel", name)); err != nil {
			t.Fatalf("no %s: %v", name, err)
		}
	}
	if opens, stops := target.counts(); opens != 1 || stops != 1 || live.stopped.Load() != 1 {
		t.Fatalf("opens %d stops %d, live client stopped %d times", opens, stops, live.stopped.Load())
	}
}

// ai-generated: a known failure's run says so with its issue, and the entry
// logs it rather than failing it; a known cell that passed runs as any pass;
// a known cell whose server never came up is no known failure, and neither
// is a failure off the list.
func TestRunPlanRunsAKnownFailureWithItsIssue(t *testing.T) {
	resetRegistryForTest(t)
	withKnown(t,
		KnownFailure{Cell: "engine-test/jitsi/*/cli/S1", Issue: fakeIssue, Why: "fake: connects fail"},
		KnownFailure{Cell: "engine-test/jitsi/datachannel/cli/S7", Issue: fakeIssue2, Why: "fake: fixed since"},
	)
	Register(fixed("S0", Metrics{}, nil))
	Register(fixed("S1", Metrics{}, nil))
	Register(fixed("S7", passS7(), nil))
	target := &scriptTarget{pairs: []Pair{{"jitsi", "vp8channel"}, {"jitsi", "datachannel"}},
		errs: []error{fmt.Errorf("fake: %w: the pool is empty", ErrPoolRoom)}}
	h := newHarness(t, target, &fakeClient{name: "cli"})
	rep := h.run(context.Background(), t)
	if rep.Planned != 6 || rep.Passed != 1 || rep.Failed != 5 || rep.FailedKnown != 1 {
		t.Fatalf("report = planned %d passed %d failed %d failed_known %d",
			rep.Planned, rep.Passed, rep.Failed, rep.FailedKnown)
	}
	known := h.errs["jitsi/datachannel/cli/S1"]
	if !errors.Is(known, ErrCellFailed) || !errors.Is(known, ErrKnownFailure) ||
		!strings.Contains(known.Error(), fakeIssue) || !strings.Contains(known.Error(), "connect_ok 0 of connect_total 0") {
		t.Fatalf("the known failure ran as %v, want it failed and known, with its issue and its reasons", known)
	}
	if err := h.errs["jitsi/datachannel/cli/S7"]; err != nil {
		t.Fatalf("the known cell that passed ran as %v", err)
	}
	for _, name := range []string{"jitsi/datachannel/cli/S0", "jitsi/vp8channel/cli/S0", "jitsi/vp8channel/cli/S1",
		"jitsi/vp8channel/cli/S7"} {
		if err := h.errs[name]; !errors.Is(err, ErrCellFailed) || errors.Is(err, ErrKnownFailure) {
			t.Fatalf("%s ran as %v, want a failure no known issue excuses", name, err)
		}
	}
	cells := cellsByID(rep)
	for id, want := range map[string]string{
		"engine-test/jitsi/datachannel/cli/S1": fakeIssue, "engine-test/jitsi/datachannel/cli/S7": fakeIssue2,
		"engine-test/jitsi/vp8channel/cli/S1": "", "engine-test/jitsi/datachannel/cli/S0": "",
	} {
		if c := cells[id]; c.Known != want {
			t.Fatalf("%s = %+v, want known %q", id, c, want)
		}
	}
}

func TestRunPlanStartsNoCellOnceTheRunEnds(t *testing.T) {
	resetRegistryForTest(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	Register(Scenario{ID: "S0", Applies: always, Run: func(context.Context, *Env) (Metrics, error) {
		cancel()
		return passS0(), nil
	}})
	Register(fixed("S1", passS1(), nil))
	target := &scriptTarget{pairs: []Pair{{"jitsi", "datachannel"}, {"jitsi", "vp8channel"}}}
	h := newHarness(t, target, &fakeClient{name: "cli"})
	rep := h.run(ctx, t)
	if rep.Planned != 4 || rep.Executed != 1 || rep.Failed != 4 || len(h.errs) != 1 {
		t.Fatalf("report = planned %d executed %d failed %d, cells run %v",
			rep.Planned, rep.Executed, rep.Failed, h.errs)
	}
	for id, c := range cellsByID(rep) {
		cut := id == "engine-test/jitsi/datachannel/cli/S0"
		if cut && !slices.Contains(c.Failures, "the run ended during the cell: context canceled") {
			t.Fatalf("the cell the run ended in = %+v", c)
		}
		if !cut && !slices.Equal(c.Failures, []string{"did not run"}) {
			t.Fatalf("%s = %+v, want it never run", id, c)
		}
	}
	if opens, stops := target.counts(); opens != 1 || stops != 1 {
		t.Fatalf("opens %d stops %d, want the second pair never opened", opens, stops)
	}
}

func TestRunPlanKeepsTheRunsSecretsOut(t *testing.T) {
	resetRegistryForTest(t)
	Register(Scenario{ID: "S0", Applies: always, Run: func(_ context.Context, env *Env) (Metrics, error) {
		ep := env.Endpoint
		log.Printf("joining %s with key %s on %s, token %s", ep.Room, ep.Key, ep.Channel, fakeToken)
		env.Logf("S0 saw %s and %s", fakeRoomSlug, ep.Key)
		return Metrics{MetricHandshakeMs: 1}, fmt.Errorf("fake: refused in %s for %s (%s)", ep.Room, ep.Key, fakeToken)
	}})
	target := &scriptTarget{pairs: []Pair{{"jitsi", "datachannel"}},
		ep: Endpoint{Room: fakeRoomURL, Key: fakeKey, Channel: fakeChannel, DNS: "192.0.2.53:53"}}
	h := newHarness(t, target, &fakeClient{name: "cli", line: "dialing " + fakeRoomURL + " key " + fakeKey})
	h.secrets = append(h.secrets, fakeToken)
	rep := h.run(context.Background(), t)
	reportPath := filepath.Join(t.TempDir(), "gate-report.json")
	if err := h.opt.Recorder.Write(reportPath); err != nil {
		t.Fatal(err)
	}
	out := map[string]string{
		"report":    readTargetFile(t, reportPath),
		"cell log":  readTargetFile(t, filepath.Join(h.opt.Dir, "jitsi-datachannel", "cli-S0.log")),
		"start log": readTargetFile(t, filepath.Join(h.opt.Dir, "jitsi-datachannel", "cli-start.log")),
		"logf":      strings.Join(h.lines, "\n"),
		"subtest":   fmt.Sprint(h.errs["jitsi/datachannel/cli/S0"]),
	}
	for what, text := range out {
		for _, secret := range []string{fakeRoomSlug, fakeKey, fakeChannel, fakeToken} {
			if strings.Contains(text, secret) {
				t.Fatalf("the %s keeps %q:\n%s", what, secret, text)
			}
		}
	}
	for what, kept := range map[string]string{
		"report":    "fake: refused in <room> for <key> (<room>)",
		"cell log":  "joining <room> with key <key> on <room>, token <room>",
		"start log": "dialing <room> key <key>",
		"logf":      "S0 saw <room> and <key>",
	} {
		if !strings.Contains(out[what], kept) {
			t.Fatalf("the %s lost %q:\n%s", what, kept, out[what])
		}
	}
	if rep.Failed != 1 {
		t.Fatalf("report failed %d cells, want the one", rep.Failed)
	}
}

// TestTheRunKeepsASecretWhileAnotherGoroutineLogs: a sampler logs a jump's
// profile from its own goroutine while S6, on the cell's, keeps a late
// server's room and key. The list they share must take the secret under
// -race, and a line logged after the keep is scrubbed of it.
func TestTheRunKeepsASecretWhileAnotherGoroutineLogs(t *testing.T) {
	var (
		mu    sync.Mutex
		lines []string
	)
	o := prepared(Options{Recorder: NewRecorder(Report{}), Logf: func(format string, args ...any) {
		mu.Lock()
		defer mu.Unlock()
		lines = append(lines, fmt.Sprintf(format, args...))
	}})
	stop, done := make(chan struct{}), make(chan struct{})
	go func() {
		defer close(done)
		for {
			select {
			case <-stop:
				return
			default:
				o.Logf("a jump's profile")
			}
		}
	}()
	for i := range 100 {
		o.keep(fmt.Sprintf("gate-fakeroom%04d", i))
	}
	close(stop)
	<-done
	o.Logf("late servers in %s and %s", "gate-fakeroom0000", "gate-fakeroom0099")
	mu.Lock()
	defer mu.Unlock()
	if last := lines[len(lines)-1]; last != "late servers in <room> and <room>" {
		t.Fatalf("logged %q after the keeps, want it scrubbed of both", last)
	}
}

func TestRunPlanWritesNoCellLogForALinkTarget(t *testing.T) {
	resetRegistryForTest(t)
	delayed := true
	Register(Scenario{ID: "S0", Applies: always, Run: func(_ context.Context, env *Env) (Metrics, error) {
		delayed = env.Delayed != nil
		return passS0(), nil
	}})
	Register(fixed("S7", passS7(), nil))
	l := link.Link{Provider: "telemost", Transport: "vp8channel", Room: "fake-telemost-room-1", Key: fakeKey}
	h := newHarness(t, NewLinkTarget(l, LoadURLs{}, "192.0.2.53:53"), &fakeClient{name: "mobile"})
	rep := h.run(context.Background(), t)
	if rep.Passed != 2 || delayed {
		t.Fatalf("passed %d of %d cells, a late server offered: %t", rep.Passed, rep.Planned, delayed)
	}
	var files []string
	err := filepath.WalkDir(h.opt.Dir, func(path string, d fs.DirEntry, err error) error {
		if err == nil && !d.IsDir() {
			files = append(files, strings.TrimPrefix(path, h.opt.Dir+string(filepath.Separator)))
		}
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
	if want := []string{filepath.Join("telemost-vp8channel", "mobile-samples.csv")}; !slices.Equal(files, want) {
		t.Fatalf("a link run left %q, want only the samples: the fleet's rooms are not ours to show", files)
	}
	for _, c := range rep.Cells {
		if c.Log != "" {
			t.Fatalf("%s names a log: %q", c.ID, c.Log)
		}
	}
}

func TestRunPlanGivesALocalTargetsCellsALateServer(t *testing.T) {
	resetRegistryForTest(t)
	work := t.TempDir()
	lt := fakeLocal(t, work, LocalOptions{
		Providers: []string{"jitsi"}, Transports: []string{"datachannel"}, JitsiHosts: []string{"meet.example.invalid"},
	}, `trap 'exit 0' TERM
echo "bridge delay $OLCRTC_TEST_BRIDGE_DELAY"; echo "Link connected"
i=0; while [ "$i" -lt 600 ]; do sleep 0.05; i=$((i+1)); done`)
	lt.probe = func(context.Context, string) bool { return true }
	var late Endpoint
	Register(Scenario{ID: "S6", Applies: always, Run: func(ctx context.Context, env *Env) (Metrics, error) {
		if env.Delayed == nil {
			return nil, errors.New("no late server on a local target")
		}
		ep, stop, err := env.Delayed(ctx, OpenOptions{BridgeDelay: 3 * time.Second})
		if err != nil {
			return nil, err
		}
		late = ep
		stop()
		// ai-generated: a client ready after each delay, as S6 wants it.
		return Metrics{MetricReady3sMs: 4100, MetricReady8sMs: 9200}, nil
	}})
	h := newHarness(t, lt, &fakeClient{name: "cli"})
	if rep := h.run(context.Background(), t); rep.Passed != 1 {
		t.Fatalf("report = %+v", rep.Cells)
	}
	srv := readTargetFile(t, filepath.Join(h.opt.Dir, "jitsi-datachannel", "delay-3s", "srv.log"))
	if !strings.Contains(srv, "bridge delay 3s") || !strings.Contains(srv, "<key>") {
		t.Fatalf("the late server's log:\n%s", srv)
	}
	for _, s := range late.Secrets() {
		if strings.Contains(srv, s) || !slices.Contains(h.secrets, s) {
			t.Fatalf("the late server's %q: in its log %t, kept as a secret %t",
				s, strings.Contains(srv, s), slices.Contains(h.secrets, s))
		}
	}
	if _, err := os.Stat(filepath.Join(h.opt.Dir, "jitsi-datachannel", "srv.log")); err != nil {
		t.Fatalf("the pair's own server log: %v", err)
	}
	if left, _ := os.ReadDir(work); len(left) != 0 {
		t.Fatalf("private files left after the run: %v", left)
	}
}

// TestRunPlanReadsAFailedCellsOwnPartOfTheServerLog holds the runner to the
// mark it takes as a cell begins: a failed cell names the drop its scenario
// saw, with the join the target noted, which is not in the cell's part of
// the log, and never copies the 16 MiB before that part into the process.
// ai-generated: this test (the streaming reader).
func TestRunPlanReadsAFailedCellsOwnPartOfTheServerLog(t *testing.T) {
	resetRegistryForTest(t)
	path := filepath.Join(t.TempDir(), "srv.log")
	line := time.Now().Add(-time.Hour).Format(logTimeLayout) + " datachannel: frame sid=3 len=16384\n"
	if err := os.WriteFile(path, []byte(strings.Repeat(line, 16<<20/len(line))), 0o600); err != nil {
		t.Fatal(err)
	}
	joined := time.Now().Add(-time.Hour).Truncate(time.Second)
	Register(Scenario{ID: "S0", Applies: always, Run: func(context.Context, *Env) (Metrics, error) {
		// A log stamps whole seconds: a drop stamped in the second the cell
		// began reads as before it. The relay drops this one a second later.
		time.Sleep(time.Until(time.Now().Truncate(time.Second).Add(time.Second)))
		f, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0)
		if err != nil {
			return nil, err
		}
		defer f.Close()
		_, err = f.WriteString(time.Now().Format(logTimeLayout) +
			" livekit: disconnected from the room, reason=PARTICIPANT_REMOVED\n")
		return Metrics{}, err
	}})
	target := &scriptTarget{pairs: []Pair{{"telemost", "vp8channel"}},
		ep: Endpoint{ServerLog: path, ServerJoined: joined}}
	h := newHarness(t, target, &fakeClient{name: "cli"})
	var before, after runtime.MemStats
	runtime.ReadMemStats(&before)
	rep := h.run(context.Background(), t)
	runtime.ReadMemStats(&after)
	c := cellsByID(rep)["engine-test/telemost/vp8channel/cli/S0"]
	drop := regexp.MustCompile(`^the relay ended the server's session \(PARTICIPANT_REMOVED\) \d+s into the cell, ` +
		`1h0m[0-2]s after it joined$`)
	if c.Status != StatusFail || !slices.ContainsFunc(c.Failures, drop.MatchString) {
		t.Fatalf("the failed cell = %+v, want the drop and the join the target noted", c)
	}
	if alloc := after.TotalAlloc - before.TotalAlloc; alloc >= 8<<20 {
		t.Fatalf("the run allocated %d KiB over a 16 MiB log: the cell read more than its own part", alloc>>10)
	}
}
