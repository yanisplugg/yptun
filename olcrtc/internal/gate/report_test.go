package gate

import (
	"bytes"
	"encoding/json"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"runtime"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"
)

// ai-generated: whole file, unit cover for the recorder and the report writer.

func TestRecorderCountsAndMarksUnrunCellsFailed(t *testing.T) {
	r := NewRecorder(Report{EngineCommit: "abc", Target: "local"})
	r.Plan([]Cell{{ID: "p/a/b/cli/S0"}, {ID: "p/a/b/cli/S1"}, {ID: "p/a/b/cli/S2"}})
	r.Finish("p/a/b/cli/S0", Metrics{"pull_ok": 1}, map[string]float64{}, nil, "", 2*time.Second)
	r.Finish("p/a/b/cli/S1", Metrics{"connect_ok": 3}, map[string]float64{"connect_p95_ms": 5000},
		[]string{"connect_p95_ms 6100 > 5000"}, "logs/x.log", time.Second)
	rep := r.Report()
	if rep.Planned != 3 || rep.Executed != 2 || rep.Passed != 1 || rep.Failed != 2 {
		t.Fatalf("counts = planned %d executed %d passed %d failed %d",
			rep.Planned, rep.Executed, rep.Passed, rep.Failed)
	}
	byID := map[string]Cell{}
	for _, c := range rep.Cells {
		byID[c.ID] = c
	}
	if byID["p/a/b/cli/S2"].Status != "fail" || byID["p/a/b/cli/S2"].Failures[0] != "did not run" {
		t.Fatalf("unrun cell = %+v", byID["p/a/b/cli/S2"])
	}
	if byID["p/a/b/cli/S1"].Status != "fail" || byID["p/a/b/cli/S1"].DurationS != 1 {
		t.Fatalf("failed cell = %+v", byID["p/a/b/cli/S1"])
	}
	if byID["p/a/b/cli/S0"].Status != "pass" || byID["p/a/b/cli/S0"].Metrics["pull_ok"] != 1 {
		t.Fatalf("passed cell = %+v", byID["p/a/b/cli/S0"])
	}
}

func TestRecorderFinishUnknownCellIsRecordedAsFailure(t *testing.T) {
	r := NewRecorder(Report{})
	r.Plan(nil)
	r.Finish("p/x/y/cli/S9", nil, nil, nil, "", 0)
	rep := r.Report()
	if rep.Failed != 1 || rep.Cells[0].Failures[0] != "cell was not planned" {
		t.Fatalf("unplanned cell = %+v", rep.Cells)
	}
}

func TestWriteProducesSchemaOne(t *testing.T) {
	r := NewRecorder(Report{EngineCommit: "abc", Target: "local", Runner: "ubuntu"})
	r.Plan([]Cell{{ID: "p/a/b/cli/S0"}})
	r.Finish("p/a/b/cli/S0", Metrics{"pull_ok": 1}, nil, nil, "", time.Second)
	path := filepath.Join(t.TempDir(), "gate-report.json")
	if err := r.Write(path); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var back Report
	if err := json.Unmarshal(raw, &back); err != nil {
		t.Fatal(err)
	}
	if back.Schema != 1 || back.EngineCommit != "abc" || back.Passed != 1 || len(back.Cells) != 1 {
		t.Fatalf("written report = %+v", back)
	}
}

// ai-generated: the report is replaced in one step, so a reader, or a crash,
// never sees half of it, and no temporary file is left beside it.
func TestWriteReplacesTheReportInOneStep(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "gate-report.json")
	r := NewRecorder(Report{})
	r.Plan([]Cell{{ID: "p/a/b/cli/S0"}, {ID: "p/a/b/cli/S1"}})
	if err := r.Write(path); err != nil {
		t.Fatal(err)
	}
	r.Finish("p/a/b/cli/S0", Metrics{"pull_ok": 1}, nil, nil, "", time.Second)
	if err := r.Write(path); err != nil {
		t.Fatal(err)
	}
	if back := readReport(t, path); back.Passed != 1 || back.Failed != 1 || back.Cells[1].Failures[0] != reasonDidNotRun {
		t.Fatalf("the report on disk = %+v, want the second write's", back)
	}
	if entries, _ := os.ReadDir(dir); len(entries) != 1 {
		t.Fatalf("the report's directory holds %v, want the report alone", entries)
	}
	if info, err := os.Stat(path); err != nil || (runtime.GOOS != "windows" && info.Mode().Perm() != reportPerm) {
		t.Fatalf("report mode = %v, %v; want %v", info.Mode(), err, os.FileMode(reportPerm))
	}
	if err := r.Write(filepath.Join(dir, "missing", "gate-report.json")); err == nil {
		t.Fatal("a report into a missing directory was written")
	}
}

func TestRecorderKeepsAnEarlierFailureWhenACellFinishesTwice(t *testing.T) {
	r := NewRecorder(Report{})
	r.Plan([]Cell{{ID: "p/a/b/cli/S0"}})
	r.Finish("p/a/b/cli/S0", nil, nil, []string{"pull_ok 0 < 1"}, "", time.Second)
	r.Finish("p/a/b/cli/S0", Metrics{"pull_ok": 1}, nil, nil, "", time.Second)
	rep := r.Report()
	c := rep.Cells[0]
	if c.Status != "fail" || !slices.Equal(c.Failures, []string{"cell finished twice", "pull_ok 0 < 1"}) {
		t.Fatalf("twice-finished cell = %+v", c)
	}
	if rep.Planned != 1 || rep.Executed != 1 || rep.Failed != 1 {
		t.Fatalf("counts = planned %d executed %d failed %d", rep.Planned, rep.Executed, rep.Failed)
	}
}

func TestPlanNeitherPassesNorResetsACell(t *testing.T) {
	r := NewRecorder(Report{})
	r.Plan([]Cell{{ID: "p/a/b/cli/S0", Status: "pass", Metrics: map[string]float64{"pull_ok": 1}}, {ID: "p/a/b/cli/S1"}})
	r.Finish("p/a/b/cli/S1", Metrics{"pull_ok": 1}, nil, nil, "", time.Second)
	r.Plan([]Cell{{ID: "p/a/b/cli/S1"}})
	rep := r.Report()
	s0, s1 := rep.Cells[0], rep.Cells[1]
	if s0.Status != "fail" || s0.Failures[0] != "did not run" || len(s0.Metrics) != 0 {
		t.Fatalf("a cell planned as passed = %+v", s0)
	}
	if s1.Status != "pass" || rep.Passed != 1 || rep.Failed != 1 {
		t.Fatalf("a re-planned finished cell = %+v (passed %d failed %d)", s1, rep.Passed, rep.Failed)
	}
}

func TestANonFiniteMetricFailsItsCellAndTheReportStillWrites(t *testing.T) {
	r := NewRecorder(Report{})
	r.Plan([]Cell{{ID: "p/a/b/cli/S2"}})
	r.Finish("p/a/b/cli/S2", Metrics{"throughput_down_bps": math.Inf(1), "on_top_p95_ms": math.NaN(), "pull_ok": 6},
		map[string]float64{"throughput_down_bps": 2e6, "on_top_p95_ms": math.Inf(1)}, nil, "", time.Second)
	c := r.Report().Cells[0]
	want := []string{
		"metric on_top_p95_ms is NaN, not a finite number",
		"metric throughput_down_bps is +Inf, not a finite number",
		"threshold on_top_p95_ms is +Inf, not a finite number",
	}
	if c.Status != "fail" || !slices.Equal(c.Failures, want) {
		t.Fatalf("failures = %q, want %q", c.Failures, want)
	}
	if len(c.Metrics) != 1 || c.Metrics["pull_ok"] != 6 || len(c.Thresholds) != 1 {
		t.Fatalf("kept metrics %v thresholds %v", c.Metrics, c.Thresholds)
	}
	if err := r.Write(filepath.Join(t.TempDir(), "gate-report.json")); err != nil {
		t.Fatalf("Write: %v", err)
	}
}

func TestReportSharesNothingWithTheCaller(t *testing.T) {
	r := NewRecorder(Report{})
	r.Plan([]Cell{{ID: "p/a/b/cli/S0"}})
	m, th, failures := Metrics{"pull_ok": 0}, map[string]float64{"handshake_ms": 15000}, []string{"pull_ok 0 < 1"}
	r.Finish("p/a/b/cli/S0", m, th, failures, "", time.Second)
	m["pull_ok"], th["handshake_ms"], failures[0] = 1, 1, "edited by the caller"
	first := r.Report().Cells[0]
	first.Metrics["pull_ok"], first.Thresholds["handshake_ms"], first.Failures[0] = 2, 2, "edited by a reader"
	again := r.Report().Cells[0]
	if again.Metrics["pull_ok"] != 0 || again.Thresholds["handshake_ms"] != 15000 || again.Failures[0] != "pull_ok 0 < 1" {
		t.Fatalf("recorded cell changed from outside: %+v", again)
	}
}

func TestReportJSONHasNoNull(t *testing.T) {
	r := NewRecorder(Report{})
	r.Plan([]Cell{{ID: "p/a/b/cli/S0"}, {ID: "p/a/b/cli/S1"}})
	r.Finish("p/a/b/cli/S1", nil, nil, nil, "", time.Second)
	path := filepath.Join(t.TempDir(), "gate-report.json")
	if err := r.Write(path); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(raw, []byte("null")) {
		t.Fatalf("report carries null:\n%s", raw)
	}
}

func TestRecorderTakesConcurrentFinishes(t *testing.T) {
	r := NewRecorder(Report{})
	cells := make([]Cell, 32)
	for i := range cells {
		cells[i] = Cell{ID: fmt.Sprintf("p/a/b/cli/S%02d", i)}
	}
	r.Plan(cells)
	var wg sync.WaitGroup
	for _, c := range cells {
		wg.Go(func() { r.Finish(c.ID, Metrics{"pull_ok": 1}, nil, nil, "", time.Millisecond) })
		wg.Go(func() { _ = r.Report() })
	}
	wg.Wait()
	if rep := r.Report(); rep.Passed != len(cells) || rep.Failed != 0 {
		t.Fatalf("passed %d failed %d of %d", rep.Passed, rep.Failed, len(cells))
	}
}

// TestRecorderWithholdsTheRunsSecretsFromFailures records a failure string
// carrying the run's own room and key, and withholds one secret only after
// the failure is in: the report, a written report and a single cell all come
// out clean, because the report is published with a release (amendment A9).
func TestRecorderWithholdsTheRunsSecretsFromFailures(t *testing.T) {
	room, key := "https://meet.example.invalid/gate-fakeroom0002", strings.Repeat("6b", 32)
	r := NewRecorder(Report{})
	r.Plan([]Cell{{ID: "p/a/b/cli/S0"}})
	r.Withhold(room, "", "gate-fakeroom0002")
	r.Finish("p/a/b/cli/S0", nil, nil, []string{"client: joining " + room + " with " + key + ": refused"}, "", 0)
	r.Withhold(key)
	want := "client: joining <room> with <key>: refused"
	if got := r.Report().Cells[0].Failures; !slices.Equal(got, []string{want}) {
		t.Fatalf("report failures = %q, want %q", got, want)
	}
	if c, ok := r.Cell("p/a/b/cli/S0"); !ok || c.Status != StatusFail || !slices.Equal(c.Failures, []string{want}) {
		t.Fatalf("Cell = %+v, %t", c, ok)
	}
	path := filepath.Join(t.TempDir(), "gate-report.json")
	if err := r.Write(path); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(raw, []byte("gate-fakeroom0002")) || bytes.Contains(raw, []byte(key)) {
		t.Fatalf("written report keeps a secret:\n%s", raw)
	}
}

func TestRecorderCellShowsOneCellAsItStands(t *testing.T) {
	r := NewRecorder(Report{})
	r.Plan([]Cell{{ID: "p/a/b/cli/S0"}, {ID: "p/a/b/cli/S1"}})
	r.Finish("p/a/b/cli/S0", Metrics{"pull_ok": 1}, nil, nil, "x.log", time.Second)
	c, ok := r.Cell("p/a/b/cli/S0")
	if !ok || c.Status != StatusPass || c.Metrics["pull_ok"] != 1 || c.Log != "x.log" {
		t.Fatalf("finished cell = %+v, %t", c, ok)
	}
	c.Metrics["pull_ok"] = 0
	if again, _ := r.Cell("p/a/b/cli/S0"); again.Metrics["pull_ok"] != 1 {
		t.Fatal("a cell handed out shares its metrics with the recorder")
	}
	if c, ok := r.Cell("p/a/b/cli/S1"); !ok || c.Status != StatusPlanned {
		t.Fatalf("unfinished cell = %+v, %t; want it planned", c, ok)
	}
	if _, ok := r.Cell("p/a/b/cli/S9"); ok {
		t.Fatal("Cell knows a cell nobody planned or finished")
	}
}
