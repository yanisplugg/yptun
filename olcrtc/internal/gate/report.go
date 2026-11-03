package gate

import (
	"bytes"
	"encoding/json"
	"fmt"
	"maps"
	"math"
	"os"
	"path/filepath"
	"slices"
	"sync"
	"time"
)

// ai-generated: the whole file (the recorder and gate-report.json).

// ReportSchema is the version of gate-report.json this package writes.
const ReportSchema = 1

// reportPerm is gate-report.json's mode: a report, not a secret.
const reportPerm = 0o644

// Cell statuses. A cell is planned until it finishes; a report turns a cell
// still planned into a failure.
const (
	StatusPlanned = "planned"
	StatusPass    = "pass"
	StatusFail    = "fail"
)

const (
	reasonDidNotRun     = "did not run"
	reasonNotPlanned    = "cell was not planned"
	reasonFinishedTwice = "cell finished twice"
)

// Cell is one scenario on one pair with one client, and what it measured.
type Cell struct {
	ID         string             `json:"id"`
	Platform   string             `json:"platform"`
	Provider   string             `json:"provider"`
	Transport  string             `json:"transport"`
	Client     string             `json:"client"`
	Scenario   string             `json:"scenario"`
	Status     string             `json:"status"` // planned, pass or fail
	Metrics    map[string]float64 `json:"metrics"`
	Thresholds map[string]float64 `json:"thresholds"`
	Failures   []string           `json:"failures"`
	Known      string             `json:"known,omitempty"` // the issue of a cell on the known list (known.go)
	Log        string             `json:"log,omitempty"`
	DurationS  float64            `json:"duration_s"`
}

// Report is gate-report.json, schema 1. A cell's known and failed_known came
// later and only add to it: a reader that knows neither still reads every
// failure as one.
type Report struct {
	Schema       int    `json:"schema"`
	EngineCommit string `json:"engine_commit"`
	EngineRef    string `json:"engine_ref"`
	AppVersion   string `json:"app_version"`
	Target       string `json:"target"`
	Runner       string `json:"runner"`
	// DTLSProfile is the dtls.profile the run's servers and clients
	// handshook with; empty is the stock handshake.
	DTLSProfile string  `json:"dtls_profile,omitempty"`
	StartedAt   string  `json:"started_at"`
	DurationS   float64 `json:"duration_s"`
	Planned     int     `json:"planned"`
	Executed    int     `json:"executed"`
	Passed      int     `json:"passed"`
	Failed      int     `json:"failed"`
	FailedKnown int     `json:"failed_known"` // the failed cells that carry an issue; Failed counts them too
	Cells       []Cell  `json:"cells"`
}

// Recorder holds every planned cell to an outcome. It is safe for concurrent
// use, and what it hands out is a copy with no secret of the run in it.
type Recorder struct {
	mu      sync.Mutex
	meta    Report
	started time.Time
	cells   map[string]Cell
	secrets []string
	// writeMu makes writes take turns, so the last one begun is the one
	// left on disk.
	writeMu sync.Mutex
}

// NewRecorder starts a report with the run's metadata filled in. Counts and
// cells in meta are ignored: they come from Plan and Finish alone.
func NewRecorder(meta Report) *Recorder {
	meta.Schema = ReportSchema
	if meta.StartedAt == "" {
		meta.StartedAt = time.Now().UTC().Format(time.RFC3339)
	}
	return &Recorder{meta: meta, started: time.Now(), cells: map[string]Cell{}}
}

// Plan registers the cells that must end pass or fail. A plan carries no
// outcome: a new cell starts planned whatever it says, and a cell already
// known keeps what it has, so planning again never erases a result.
func (r *Recorder) Plan(cells []Cell) {
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, c := range cells {
		if _, known := r.cells[c.ID]; known {
			continue
		}
		r.cells[c.ID] = Cell{
			ID: c.ID, Platform: c.Platform, Provider: c.Provider, Transport: c.Transport,
			Client: c.Client, Scenario: c.Scenario, Status: StatusPlanned,
		}
	}
}

// Finish records a cell's outcome: pass when nothing failed. A cell nobody
// planned, a second outcome for one cell, and a number JSON cannot hold (NaN,
// an infinity) each fail the cell with a reason rather than hide in it: the
// first two are runner bugs, the last would stop the report being written.
// A cell whose id is on the known list carries the issue that tracks it,
// passed or failed. Finish keeps copies, so the caller may reuse what it
// passed.
func (r *Recorder) Finish(
	id string, m Metrics, thresholds map[string]float64, failures []string, logPath string, took time.Duration,
) {
	r.mu.Lock()
	defer r.mu.Unlock()
	c := r.outcome(id, m, thresholds, failures)
	c.Log = logPath
	c.DurationS = took.Seconds()
	if k, ok := knownFailure(id); ok {
		c.Known = k.Issue
	}
	r.cells[id] = c
}

// NotRun fails a cell whose scenario never ran with the reason: its server
// or its client never came up. Such a cell is never a known failure,
// whatever its id: what failed is the gate's world (a relay, a secret, a
// start), not the bug an entry of the list is tracked for.
func (r *Recorder) NotRun(id string, thresholds map[string]float64, reason string) {
	// ai-generated: a cell failed before it ran, never known.
	r.mu.Lock()
	defer r.mu.Unlock()
	r.cells[id] = r.outcome(id, nil, thresholds, []string{reason})
}

// outcome is a cell with what it measured and its outcome, as Finish
// describes it, and no log, time or issue yet. The caller holds the lock.
func (r *Recorder) outcome(id string, m Metrics, thresholds map[string]float64, failures []string) Cell {
	c, known := r.cells[id]
	var reasons []string
	switch {
	case !known:
		c = Cell{ID: id}
		reasons = append(reasons, reasonNotPlanned)
	case c.Status != StatusPlanned:
		reasons = append(reasons, reasonFinishedTwice)
		reasons = append(reasons, c.Failures...)
	}
	metrics, bad := finiteOnly("metric", m)
	reasons = append(reasons, bad...)
	limits, bad := finiteOnly("threshold", thresholds)
	reasons = append(reasons, bad...)
	reasons = append(reasons, failures...)
	c.Metrics, c.Thresholds, c.Failures = metrics, limits, reasons
	c.Log, c.DurationS, c.Known = "", 0, ""
	c.Status = StatusPass
	if len(reasons) > 0 {
		c.Status = StatusFail
	}
	return c
}

// Withhold adds secrets of the run the report must not carry: every failure
// string leaves the recorder scrubbed of them (see Scrub), however long before
// they were withheld it was recorded, because the report is published with a
// release (amendment A9). Empty ones are ignored.
func (r *Recorder) Withhold(secrets ...string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.secrets = append(r.secrets, secrets...)
}

// Cell returns one cell as the report will show it, a cell not finished yet
// still planned, and whether the plan or a Finish made it known.
func (r *Recorder) Cell(id string) (Cell, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	c, ok := r.cells[id]
	if !ok {
		return Cell{}, false
	}
	return r.shown(c), true
}

// Report builds the report: every cell by ID, a cell still planned as a
// failure that did not run, and the counts. Planned counts every cell, one
// finished without a plan included, so it is always Passed plus Failed;
// Executed leaves out the cells that never ran; FailedKnown is the failed
// cells that carry an issue, which a cell that did not run never does.
func (r *Recorder) Report() Report {
	r.mu.Lock()
	defer r.mu.Unlock()
	rep := r.meta
	rep.DurationS = time.Since(r.started).Seconds()
	rep.Planned, rep.Executed, rep.Passed, rep.Failed, rep.FailedKnown = len(r.cells), 0, 0, 0, 0
	rep.Cells = make([]Cell, 0, len(r.cells))
	for _, id := range slices.Sorted(maps.Keys(r.cells)) {
		c := r.shown(r.cells[id])
		switch c.Status {
		case StatusPass:
			rep.Executed++
			rep.Passed++
		case StatusFail:
			rep.Executed++
			rep.Failed++
			if c.Known != "" {
				rep.FailedKnown++
			}
		default:
			c.Status = StatusFail
			c.Failures = []string{reasonDidNotRun}
			c.Known = "" // ai-generated: a cell that did not run is never known
			rep.Failed++
		}
		rep.Cells = append(rep.Cells, c)
	}
	return rep
}

// Write stores the report as indented JSON, whole or not at all: a process
// that dies while it writes leaves the report it wrote before, never a cut
// one (see replaceFile). A scrubbed failure reads <room> and <key> there as
// it does in a log, not HTML-escaped.
func (r *Recorder) Write(path string) error {
	r.writeMu.Lock()
	defer r.writeMu.Unlock()
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	enc.SetIndent("", "  ")
	if err := enc.Encode(r.Report()); err != nil {
		return fmt.Errorf("marshal report: %w", err)
	}
	if err := replaceFile(path, buf.Bytes()); err != nil {
		return fmt.Errorf("write report: %w", err)
	}
	return nil
}

// replaceFile puts data at path in one step: a temporary file next to it,
// renamed over it once written. A temporary file a crash leaves is hidden
// and named apart from what the CI uploads.
func replaceFile(path string, data []byte) error {
	// ai-generated: the atomic write the report is kept on disk with.
	tmp, err := os.CreateTemp(filepath.Dir(path), "."+filepath.Base(path)+"-*")
	if err != nil {
		return fmt.Errorf("create temporary file: %w", err)
	}
	_, err = tmp.Write(data)
	if err == nil {
		err = tmp.Chmod(reportPerm)
	}
	if closeErr := tmp.Close(); err == nil {
		err = closeErr
	}
	if err == nil {
		err = os.Rename(tmp.Name(), path)
	}
	if err != nil {
		_ = os.Remove(tmp.Name())
		return fmt.Errorf("replace %s: %w", filepath.Base(path), err)
	}
	return nil
}

// finiteOnly copies m without the values JSON cannot encode (NaN and the
// infinities) and names each one it left out, in key order.
func finiteOnly(kind string, m map[string]float64) (map[string]float64, []string) {
	out := make(map[string]float64, len(m))
	var dropped []string
	for _, k := range slices.Sorted(maps.Keys(m)) {
		if v := m[k]; math.IsNaN(v) || math.IsInf(v, 0) {
			dropped = append(dropped, fmt.Sprintf("%s %s is %v, not a finite number", kind, k, v))
			continue
		}
		out[k] = m[k]
	}
	return out, dropped
}

// shown is a cell as it leaves the recorder: a snapshot with every failure
// scrubbed of the withheld secrets. The caller holds the lock.
func (r *Recorder) shown(c Cell) Cell {
	out := snapshot(c)
	for i, f := range out.Failures {
		out.Failures[i] = Scrub(f, r.secrets...)
	}
	return out
}

// snapshot copies a cell's maps and list, never nil, so a report shares no
// memory with the recorder and its JSON says {} and [] instead of null.
func snapshot(c Cell) Cell {
	out := c
	out.Metrics = make(map[string]float64, len(c.Metrics))
	maps.Copy(out.Metrics, c.Metrics)
	out.Thresholds = make(map[string]float64, len(c.Thresholds))
	maps.Copy(out.Thresholds, c.Thresholds)
	out.Failures = append(make([]string, 0, len(c.Failures)), c.Failures...)
	return out
}
