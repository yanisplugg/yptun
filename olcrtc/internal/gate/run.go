package gate

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/engine/builtin"
)

// ai-generated: the whole file (the runner: the plan walked pair by pair,
// client by client and scenario by scenario, one cell at a time under its
// deadline, every outcome recorded, and every line that leaves the run
// scrubbed of its rooms, keys and tokens).

var (
	// ErrCellFailed is what a cell's run returns when the cell failed; the
	// entry fails the cell's subtest with it, the reasons the report shows
	// included.
	ErrCellFailed = errors.New("cell failed")
	// ErrScenarioPanic is a scenario that panicked. Its cell fails and the
	// run goes on: a panic that reached the test would end the process with
	// the report unwritten.
	ErrScenarioPanic = errors.New("scenario panicked")
)

var (
	// cellDeadline bounds one scenario; the bulk transfers S2 and S3 get
	// twice as long, which also bounds each of their requests.
	cellDeadline = 5 * time.Minute //nolint:gochecknoglobals // tests shorten it
	// openRetryDelay is how long a server that did not come up waits for its
	// one more try (spec section 11): a provider's refusal may pass.
	openRetryDelay = 30 * time.Second //nolint:gochecknoglobals // tests shorten it
)

// artifactsPerm is a directory under the run's artifacts.
const artifactsPerm = 0o750

// Options is everything RunPlan needs. Recorder and Capture are required.
type Options struct {
	Target     Target
	Clients    []Client
	Thresholds Thresholds
	// Dir is where the scrubbed logs and the samples go, a directory per
	// pair; the report names each cell's log relative to it.
	Dir      string
	Recorder *Recorder
	Capture  *Capture
	// Secrets are what no log line, cell log or report may carry: the
	// caller's (a token, a link, the pools) and, as each server comes up,
	// its room, key and channel, which RunPlan adds.
	Secrets *[]string
	// ai-generated: secretsMu guards *Secrets once RunPlan has prepared the
	// options: a sampler logs a jump from its own goroutine while S6 keeps a
	// late server's room and key on the cell's.
	secretsMu *sync.Mutex
	Logf      func(format string, args ...any)
	// ReportPath is where the report is kept as the run goes, rewritten
	// after the plan and after every cell: a process that dies mid-run (a
	// panic outside a scenario, a -timeout kill) still leaves every cell it
	// finished, the rest failed as not run. Empty keeps none on disk.
	ReportPath string
}

// RunPlan records the plan, then walks it pair by pair, client by client and
// scenario by scenario, one cell at a time. Every cell goes through run,
// which the entry makes a subtest named after the cell, its id without the
// platform, so the test output and the report name the same things: run
// calls cell, and cell returns an error wrapping ErrCellFailed when the cell
// failed, a server or a client that never came up included, and
// ErrKnownFailure as well when the cell is a known failure (known.go). A cell
// run never calls, the one a -run filter leaves out or one after ctx ended,
// stays planned, and the report fails it as not run. With a ReportPath the
// report on disk follows the plan and every cell.
func RunPlan(ctx context.Context, opt Options, run func(name string, cell func() error)) {
	o := prepared(opt)
	names := make([]string, 0, len(o.Clients))
	for _, c := range o.Clients {
		names = append(names, c.Name())
	}
	o.Recorder.Plan(PlanCells(o.Target, names))
	o.save()
	for _, pair := range o.Target.Pairs() {
		if err := ctx.Err(); err != nil {
			o.Logf("the run ended before %s: %v", pair, err)
			return
		}
		runPair(ctx, o, pair, run)
	}
}

// prepared fills what a caller may leave out, makes every line the run logs
// scrubbed of the secrets known when it is written, and hands the recorder
// those known up front. From here on the run's secrets are read and added
// under a lock of their own: lines are logged from more than one goroutine.
func prepared(opt Options) Options {
	o := opt
	if o.Secrets == nil {
		o.Secrets = &[]string{}
	}
	o.secretsMu = &sync.Mutex{}
	logf, known := o.Logf, o.secrets
	if logf == nil {
		logf = func(string, ...any) {}
	}
	o.Logf = func(format string, args ...any) {
		logf("%s", Scrub(fmt.Sprintf(format, args...), known()...))
	}
	o.Recorder.Withhold(o.secrets()...)
	return o
}

// secrets is a copy of the run's secrets as they stand, taken under their
// lock, so a Scrub never reads the list while keep grows it.
func (o Options) secrets() []string {
	o.secretsMu.Lock()
	defer o.secretsMu.Unlock()
	return slices.Clone(*o.Secrets)
}

// keep adds secrets of the run: every log line and cell log from now on is
// scrubbed of them, and the report of all of them.
func (o Options) keep(secrets ...string) {
	o.secretsMu.Lock()
	*o.Secrets = append(*o.Secrets, secrets...)
	o.secretsMu.Unlock()
	o.Recorder.Withhold(secrets...)
}

// runPair brings up the pair's server and runs every client against it; a
// server that never came up fails every cell of the pair.
func runPair(ctx context.Context, o Options, pair Pair, run func(string, func() error)) {
	dir := filepath.Join(o.Dir, pair.Provider+"-"+pair.Transport)
	ep, stop, err := openPair(ctx, o, pair, dir)
	if err != nil {
		o.Logf("%s: %v", pair, err)
		for _, c := range o.Clients {
			failCells(o, pair, c.Name(), err, run)
		}
		return
	}
	defer stop()
	for _, c := range o.Clients {
		if ctx.Err() != nil {
			return
		}
		runClient(ctx, o, pair, c, ep, dir, run)
	}
}

// openPair opens the pair's server, once more after openRetryDelay when it
// did not come up and a second try could change that, and keeps the
// endpoint's secrets.
func openPair(ctx context.Context, o Options, pair Pair, dir string) (Endpoint, func(), error) {
	if err := os.MkdirAll(dir, artifactsPerm); err != nil {
		return Endpoint{}, nil, fmt.Errorf("artifacts: %w", err)
	}
	start := time.Now()
	ep, stop, err := o.Target.Open(ctx, pair, dir, OpenOptions{})
	if err != nil && retryable(err) && ctx.Err() == nil {
		o.Logf("%s: the server did not come up, one more try in %s: %v", pair, openRetryDelay, err)
		if pause(ctx, openRetryDelay) {
			start = time.Now()
			if ep, stop, err = o.Target.Open(ctx, pair, dir, OpenOptions{}); err != nil {
				err = fmt.Errorf("tried twice, %s apart: %w", openRetryDelay, err)
			}
		}
	}
	if err != nil {
		return Endpoint{}, nil, fmt.Errorf("server: %w", err)
	}
	o.keep(ep.Secrets()...)
	o.Logf("%s: server up in %s", pair, time.Since(start).Round(time.Millisecond))
	return ep, stop, nil
}

// retryable says whether a server that did not come up may on a second try:
// a provider's refusal or the network may pass, a plan without a room, a
// token or the pair never will.
func retryable(err error) bool {
	return !errors.Is(err, ErrNoWBStreamToken) && !errors.Is(err, ErrNoMaxKeys) &&
		!errors.Is(err, ErrPoolRoom) && !errors.Is(err, ErrPairNotCarried)
}

// providerRefused says whether a client failed on its provider's refusal (auth,
// a room that would not open, the provider's own outage), which a second try
// may pass. The phone flavour's errors cross gomobile's string boundary, so
// the sentinel's text counts as well as the sentinel.
func providerRefused(err error) bool {
	// ai-generated: the test for a provider refusal behind a client error.
	return errors.Is(err, builtin.ErrAuthFailed) || strings.Contains(err.Error(), builtin.ErrAuthFailed.Error())
}

// pause waits d, or less if ctx ends first, and says whether it waited d out.
func pause(ctx context.Context, d time.Duration) bool {
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-timer.C:
		return true
	case <-ctx.Done():
		return false
	}
}

// runClient starts one flavour against the pair's server and runs its
// scenarios on the one tunnel, under a sampler of their own whose baseline
// is read before the client starts; a client that never came up fails every
// cell it had.
func runClient(ctx context.Context, o Options, pair Pair, client Client, ep Endpoint, dir string,
	run func(string, func() error),
) {
	sampler := baselineSampler(dir, client.Name(), o.Logf)
	defer sampler.Stop()
	env := newEnv(o, pair, client, ep, dir, sampler)
	tun, err := startClient(ctx, o, env)
	if err != nil {
		o.Logf("%s/%s: %v", pair, client.Name(), err)
		failCells(o, pair, client.Name(), err, run)
		return
	}
	defer tun.Stop()
	for _, s := range scenariosFor(o.Target, pair, client.Name()) {
		if ctx.Err() != nil {
			return
		}
		run(cellName(pair, client.Name(), s.ID), func() error { return runCell(ctx, o, env, s) })
	}
}

// baselineSampler starts a client's sampler on a clean baseline: a GC, then
// FreeOSMemory's own GC, hand back what earlier pairs left (a sync.Pool's
// objects outlive one cycle as its victim cache) and return the freed pages,
// and the baseline mark reads the process before the client starts. S7
// judges the client's memory by how far it rose over that reading. A jump
// in memory leaves a heap profile in the pair's directory dir, under the
// client's name.
func baselineSampler(dir, client string, logf func(format string, args ...any)) *Sampler {
	// ai-generated: the clean baseline S7's memory growth is judged over.
	runtime.GC() //nolint:revive // a clean baseline for a memory verdict, not a tuning knob
	debug.FreeOSMemory()
	s := NewSampler(time.Second)
	s.ProfileJumps(dir, client, logf) // ai-generated: a profile when memory jumps
	s.Start()
	s.Mark(markBaseline)
	return s
}

// newEnv is the world a client's cells share. On a local target a cell may
// also open a server of its own with a late bridge (S6); its log goes next to
// the pair's, and its room and key join the run's secrets.
func newEnv(o Options, pair Pair, client Client, ep Endpoint, dir string, sampler *Sampler) *Env {
	env := &Env{
		Target: o.Target, Pair: pair, Client: client, Endpoint: ep, Load: o.Target.Load(),
		Sampler: sampler, Dir: dir, Logf: o.Logf, Thresholds: o.Thresholds.For(pair.Provider),
	}
	if _, local := o.Target.(*LocalTarget); local {
		env.Delayed = func(ctx context.Context, opt OpenOptions) (Endpoint, func(), error) {
			sub := filepath.Join(dir, "delay-"+opt.BridgeDelay.String())
			if err := os.MkdirAll(sub, artifactsPerm); err != nil {
				return Endpoint{}, nil, fmt.Errorf("artifacts: %w", err)
			}
			late, stop, err := o.Target.Open(ctx, pair, sub, opt)
			if err != nil {
				return Endpoint{}, nil, fmt.Errorf("late server: %w", err)
			}
			o.keep(late.Secrets()...)
			return late, stop, nil
		}
	}
	return env
}

// startClient starts the flavour with the start's log in a cell of its own,
// times the start for S0 and points the helpers the scenarios use at the
// tunnel's SOCKS listener.
func startClient(ctx context.Context, o Options, env *Env) (*Tunnel, error) {
	startLog := o.Capture.Begin()
	start := time.Now()
	tun, err := env.Client.Start(ctx, env.Endpoint)
	// ai-generated: one more try for a client the provider refused.
	// Spec section 11: a provider that will not authenticate is retried once
	// after the same pause a server gets (a WB guest-register answering 502
	// for a minute is the case seen), then the cells fail with the reason.
	if err != nil && providerRefused(err) && ctx.Err() == nil {
		o.Logf("%s/%s: the provider refused the client, one more try in %s: %v",
			env.Pair, env.Client.Name(), openRetryDelay, err)
		if pause(ctx, openRetryDelay) {
			start = time.Now()
			if tun, err = env.Client.Start(ctx, env.Endpoint); err != nil {
				err = fmt.Errorf("tried twice, %s apart: %w", openRetryDelay, err)
			}
		}
	}
	env.handshake = time.Since(start)
	o.writeLog(startLog, env.Dir, env.Client.Name()+"-start.log")
	if err != nil {
		return nil, fmt.Errorf("client: %w", err)
	}
	dial, err := SocksDialer(tun.SocksAddr)
	if err != nil {
		tun.Stop()
		return nil, fmt.Errorf("client: %w", err)
	}
	env.Tunnel, env.Dial = tun, dial
	// A request is bounded by its cell's deadline; this only stops one
	// that outlives the longest cell.
	env.HTTP = HTTPClient(dial, 2*cellDeadline)
	env.UDP = func(ctx context.Context) (*UDPAssoc, error) { return UDPAssociate(ctx, tun.SocksAddr) }
	o.Logf("%s/%s: client ready in %s", env.Pair, env.Client.Name(), env.handshake.Round(time.Millisecond))
	return tun, nil
}

// runCell is one cell: a fresh log, the scenario under its deadline, the log
// scrubbed next to the pair's artifacts (and after S7 the samples), and the
// outcome recorded.
func runCell(ctx context.Context, o Options, env *Env, s Scenario) error {
	id := CellID(o.Target.Platform(), env.Pair, env.Client.Name(), s.ID)
	env.Log = o.Capture.Begin()
	// ai-generated: where the server log stands as the cell begins; a failed
	// cell reads the log from here (the streaming reader).
	at := markLog(env.Endpoint)
	m, failures, took := RunCell(ctx, env, s)
	// ai-generated: the relay's own doing, named next to the cell's failures
	// (olcrtc#26). It judges nothing; it says who ended the session.
	end := time.Now()
	failures = withRelayDrops(failures, env.Endpoint.ServerLog, at, end.Add(-took), end)
	logPath := o.writeLog(env.Log, env.Dir, env.Client.Name()+"-"+s.ID+".log")
	if s.ID == "S7" {
		if err := env.Sampler.WriteCSV(filepath.Join(env.Dir, env.Client.Name()+"-samples.csv")); err != nil {
			o.Logf("%s: %v", id, err)
		}
	}
	return o.finish(id, m, env.Thresholds, failures, logPath, took)
}

// RunCell runs one scenario under its deadline and judges what it measured
// by env.Thresholds. A scenario that panics fails its cell rather than the
// process that holds the report, and one that its deadline or the end of the
// run cut short says so among its failures.
func RunCell(ctx context.Context, env *Env, s Scenario) (Metrics, []string, time.Duration) {
	deadline := cellDeadline
	if s.ID == "S2" || s.ID == "S3" {
		deadline *= 2
	}
	cctx, cancel := context.WithTimeout(ctx, deadline)
	defer cancel()
	start := time.Now()
	m, err := runScenario(cctx, env, s)
	took := time.Since(start)
	if m == nil {
		m = Metrics{}
	}
	var failures []string
	switch {
	case ctx.Err() != nil:
		failures = append(failures, "the run ended during the cell: "+ctx.Err().Error())
	case cctx.Err() != nil:
		failures = append(failures, fmt.Sprintf("cell deadline %s reached", deadline))
	}
	if err != nil {
		failures = append(failures, "scenario error: "+err.Error())
	}
	return m, append(failures, Evaluate(s.ID, m, env.Thresholds)...), took
}

// runScenario runs s and turns a panic in it into an error.
func runScenario(ctx context.Context, env *Env, s Scenario) (Metrics, error) {
	var (
		m   Metrics
		err error
	)
	func() {
		defer func() {
			if r := recover(); r != nil {
				err = fmt.Errorf("%w: %v", ErrScenarioPanic, r)
			}
		}()
		m, err = s.Run(ctx, env)
	}()
	return m, err
}

// failCells fails every cell a client has on a pair with reason, without
// running any: the server or the client never came up. Each still goes
// through run, so it shows in the test output as the cells that ran do, and
// none is a known failure, whatever its id (see Recorder.NotRun).
func failCells(o Options, pair Pair, client string, reason error, run func(string, func() error)) {
	for _, s := range scenariosFor(o.Target, pair, client) {
		id := CellID(o.Target.Platform(), pair, client, s.ID)
		run(cellName(pair, client, s.ID), func() error {
			o.Recorder.NotRun(id, o.Thresholds.For(pair.Provider).Map(), reason.Error())
			return o.recorded(id)
		})
	}
}

// finish records a cell that ran, with the thresholds it was judged by, and
// returns what its run reports (see recorded).
func (o Options) finish(
	id string, m Metrics, t Thresholds, failures []string, logPath string, took time.Duration,
) error {
	o.Recorder.Finish(id, m, t.Map(), failures, logPath, took)
	return o.recorded(id)
}

// recorded saves the report with the cell just recorded and returns what the
// cell's run reports: nil for a pass, ErrCellFailed with the failures as the
// report shows them for a failure, and ErrKnownFailure with it and the issue
// for a known one.
func (o Options) recorded(id string) error {
	o.save()
	c, _ := o.Recorder.Cell(id)
	switch {
	case c.Status != StatusFail:
		return nil
	case c.Known != "": // ai-generated: a known failure names its issue
		return fmt.Errorf("%w, %w (%s): %s", ErrCellFailed, ErrKnownFailure, c.Known, strings.Join(c.Failures, "; "))
	}
	return fmt.Errorf("%w: %s", ErrCellFailed, strings.Join(c.Failures, "; "))
}

// save writes the report as it stands to ReportPath, if the run keeps one:
// a cell that has not finished reads as failed, not run, so a process that
// dies before the next save leaves the cell in flight failed.
func (o Options) save() {
	// ai-generated: the report kept on disk as the run goes.
	if o.ReportPath == "" {
		return
	}
	if err := o.Recorder.Write(o.ReportPath); err != nil {
		o.Logf("report: %v", err)
	}
}

// writeLog stores a log scrubbed of the run's secrets in dir and returns its
// path relative to the artifacts, what the report names. A link target's
// logs are never written: the fleet's rooms are not ours to show (spec
// section 10).
func (o Options) writeLog(l *CellLog, dir, name string) string {
	if o.Target.Name() == targetLink {
		return ""
	}
	path := filepath.Join(dir, name)
	if err := l.WriteScrubbed(path, o.secrets()...); err != nil {
		o.Logf("%s: %v", name, err)
		return ""
	}
	rel, err := filepath.Rel(o.Dir, path)
	if err != nil {
		return path
	}
	return filepath.ToSlash(rel)
}

// scenariosFor is the registered scenarios that apply to a client on a pair,
// in ID order.
func scenariosFor(target Target, pair Pair, client string) []Scenario {
	return slices.DeleteFunc(Scenarios(), func(s Scenario) bool {
		return s.Applies != nil && !s.Applies(target, pair, client)
	})
}

// cellName is a cell's id without its platform, the name of its subtest.
func cellName(pair Pair, client, scenario string) string {
	return pair.Provider + "/" + pair.Transport + "/" + client + "/" + scenario
}
