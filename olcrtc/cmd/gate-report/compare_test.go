package main

import (
	"slices"
	"strings"
	"testing"

	"github.com/openlibrecommunity/olcrtc/internal/gate"
)

// ai-generated: whole file, unit cover for comparing two reports.

const cellS2 = "engine-linux/jitsi/datachannel/mobile/S2"

func TestCompareFlagsRegressions(t *testing.T) {
	prev := sampleReport()
	cur := sampleReport()
	cur.Cells[0].Metrics["throughput_down_bps"] = 3_000_000 // -38 %
	cur.Cells[1].Status = "pass"
	cur.Cells[1].Failures = []string{}
	cur.Cells = append(cur.Cells, gate.Cell{ID: "engine-linux/jitsi/datachannel/mobile/S7", Scenario: "S7", Status: "pass",
		Metrics: map[string]float64{"heap_peak_bytes": 9 << 20}})
	deltas := Compare(prev, cur)
	var regressions []Delta
	for _, d := range deltas {
		if d.Regression {
			regressions = append(regressions, d)
		}
	}
	if len(regressions) != 1 || regressions[0].Metric != "throughput_down_bps" {
		t.Fatalf("regressions = %+v", regressions)
	}
	prev2 := cur
	cur2 := sampleReport()
	cur2.Cells[0].Metrics["throughput_down_bps"] = 4_000_000 // +33 % on 3.0, not a regression
	cur2.Cells[1].Status = "fail"
	found := false
	for _, d := range Compare(prev2, cur2) {
		if d.Cell == "engine-linux/jitsi/datachannel/mobile/S4" && d.Metric == "status" && d.Regression {
			found = true
		}
	}
	if !found {
		t.Fatal("a cell that passed before and fails now must be a regression")
	}
}

// oneCell is a report holding one cell.
func oneCell(status string, m map[string]float64) gate.Report {
	return gate.Report{Schema: 1, Cells: []gate.Cell{{ID: cellS2, Status: status, Metrics: m}}}
}

func TestCompareJudgesAQuarterStrictly(t *testing.T) {
	for _, tc := range []struct {
		name       string
		key        string
		prev, cur  float64
		change     float64
		regression bool
	}{
		{"download down by exactly a quarter", gate.MetricThroughputDownBps, 4_000_000, 3_000_000, -0.25, false},
		{"download down by more", gate.MetricThroughputDownBps, 4_000_000, 2_000_000, -0.5, true},
		{"upload down by more", gate.MetricThroughputUpBps, 2_000_000, 1_000_000, -0.5, true},
		{"throughput up", gate.MetricThroughputDownBps, 2_000_000, 4_000_000, 1, false},
		{"heap up by exactly a quarter", gate.MetricHeapPeakBytes, 8 << 20, 10 << 20, 0.25, false},
		{"heap up by more", gate.MetricHeapPeakBytes, 8 << 20, 12 << 20, 0.5, true},
		{"rss up by more", gate.MetricRSSPeakBytes, 20 << 20, 30 << 20, 0.5, true},
		{"memory down", gate.MetricRSSPeakBytes, 30 << 20, 15 << 20, -0.5, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			got := Compare(oneCell("pass", map[string]float64{tc.key: tc.prev}),
				oneCell("pass", map[string]float64{tc.key: tc.cur}))
			want := Delta{Cell: cellS2, Metric: tc.key, Prev: tc.prev, Cur: tc.cur, Change: tc.change,
				Regression: tc.regression}
			if len(got) != 1 || got[0] != want {
				t.Fatalf("deltas = %+v, want %+v", got, want)
			}
		})
	}
}

func TestCompareStatus(t *testing.T) {
	for _, tc := range []struct {
		prev, cur  string
		regression bool
	}{
		{"pass", "fail", true},
		{"fail", "pass", false},
		{"fail", "fail", false},
		{"pass", "pass", false},
	} {
		got := Compare(oneCell(tc.prev, nil), oneCell(tc.cur, nil))
		want := []Delta{{Cell: cellS2, Metric: "status", Prev: 1, Cur: 0, Change: -1, Regression: true}}
		if !tc.regression {
			want = nil
		}
		if len(got) != len(want) || (len(got) == 1 && got[0] != want[0]) {
			t.Fatalf("%s then %s: deltas = %+v, want %+v", tc.prev, tc.cur, got, want)
		}
	}
}

func TestCompareSkipsACellOnOneSideOnly(t *testing.T) {
	// A cell only the previous report holds (the plan dropped it) or only
	// the current one holds (a new scenario) has nothing to be compared
	// with: no delta and no regression, whatever it measured or its status.
	a := gate.Report{Cells: []gate.Cell{
		{ID: cellS2, Status: "pass", Metrics: map[string]float64{gate.MetricThroughputDownBps: 4_000_000}},
		{ID: "engine-linux/jitsi/datachannel/mobile/S3", Status: "pass",
			Metrics: map[string]float64{gate.MetricThroughputUpBps: 3_000_000}},
	}}
	b := gate.Report{Cells: []gate.Cell{
		{ID: cellS2, Status: "pass", Metrics: map[string]float64{gate.MetricThroughputDownBps: 4_000_000}},
		{ID: "engine-linux/jitsi/datachannel/mobile/S7", Status: "fail",
			Metrics: map[string]float64{gate.MetricHeapPeakBytes: 90 << 20}},
	}}
	for _, pair := range [][2]gate.Report{{a, b}, {b, a}} {
		got := Compare(pair[0], pair[1])
		if len(got) != 1 || got[0].Cell != cellS2 || got[0].Regression {
			t.Fatalf("deltas = %+v, want one for %s and no regression", got, cellS2)
		}
	}
}

func TestCompareOrdersByCellThenMetric(t *testing.T) {
	// The current report holds its cells out of order, and a cell yields its
	// status before its metrics: the deltas still go by cell id, then metric.
	const cellS3 = "engine-linux/jitsi/datachannel/mobile/S3"
	m := map[string]float64{gate.MetricThroughputDownBps: 4_000_000, gate.MetricHeapPeakBytes: 8 << 20}
	prev := gate.Report{Cells: []gate.Cell{
		{ID: cellS2, Status: "pass", Metrics: m}, {ID: cellS3, Status: "pass", Metrics: m},
	}}
	cur := gate.Report{Cells: []gate.Cell{
		{ID: cellS3, Status: "fail", Metrics: m}, {ID: cellS2, Status: "pass", Metrics: m},
	}}
	got := make([]string, 0, 5)
	for _, d := range Compare(prev, cur) {
		got = append(got, d.Cell+" "+d.Metric)
	}
	want := []string{
		cellS2 + " heap_peak_bytes", cellS2 + " throughput_down_bps",
		cellS3 + " heap_peak_bytes", cellS3 + " status", cellS3 + " throughput_down_bps",
	}
	if !slices.Equal(got, want) {
		t.Fatalf("deltas go\n%s\nwant\n%s", strings.Join(got, "\n"), strings.Join(want, "\n"))
	}
}

func TestCompareNeedsTheMetricOnBothSidesAndABase(t *testing.T) {
	prev := gate.Report{Cells: []gate.Cell{
		{ID: cellS2, Status: "pass", Metrics: map[string]float64{
			gate.MetricThroughputDownBps: 0, gate.MetricOnTopP95Ms: 800, gate.MetricHeapPeakBytes: 9 << 20}},
	}}
	cur := gate.Report{Cells: []gate.Cell{
		{ID: cellS2, Status: "pass", Metrics: map[string]float64{
			gate.MetricThroughputDownBps: 4_000_000, gate.MetricOnTopP95Ms: 4000}},
	}}
	if got := Compare(prev, cur); len(got) != 0 {
		t.Fatalf("deltas = %+v, want none: a zero base, a metric on one side, an untracked metric", got)
	}
}

func TestRenderDeltasMarksEveryRegressionAtEitherSeverity(t *testing.T) {
	deltas := []Delta{
		{Cell: "p/a/b/cli/S2", Metric: gate.MetricThroughputDownBps, Prev: 4_812_000, Cur: 3_000_000,
			Change: -0.3765, Regression: true},
		{Cell: "p/a/b/cli/S4", Metric: "status", Prev: 1, Cur: 0, Change: -1, Regression: true},
		{Cell: "p/a/b/cli/S7", Metric: gate.MetricHeapPeakBytes, Prev: 10 << 20, Cur: 11 << 20, Change: 0.1},
	}
	want := strings.Join([]string{
		"### Against the previous release",
		"",
		"Severity `fail`: a regression fails the run.",
		"",
		"| Cell | Metric | Before | Now | Change |",
		"| --- | --- | --- | --- | --- |",
		"| `p/a/b/cli/S2` | throughput_down_bps | 4.81 Mbit/s | 3.00 Mbit/s | **REGRESSION** -38% |",
		"| `p/a/b/cli/S4` | status | pass | fail | **REGRESSION** |",
		"| `p/a/b/cli/S7` | heap_peak_bytes | 10.0 MiB | 11.0 MiB | +10% |",
		"",
	}, "\n")
	md, regressed := RenderDeltas(deltas, "fail")
	if md != want || !regressed {
		t.Fatalf("fail: regressed %v, md =\n%s\nwant\n%s", regressed, md, want)
	}
	md, regressed = RenderDeltas(deltas, "warn")
	if !regressed || strings.Count(md, "**REGRESSION**") != 2 ||
		!strings.Contains(md, "Severity `warn`: a regression is reported and does not fail the run.") {
		t.Fatalf("warn: regressed %v, md =\n%s", regressed, md)
	}
}

func TestRenderDeltasWithNothingToCompare(t *testing.T) {
	md, regressed := RenderDeltas(nil, "fail")
	if regressed || !strings.Contains(md, "| - | no tracked metric in both reports | | | |") {
		t.Fatalf("regressed %v, md =\n%s", regressed, md)
	}
}
