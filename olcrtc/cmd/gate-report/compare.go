package main

import (
	"cmp"
	"fmt"
	"slices"
	"strconv"
	"strings"

	"github.com/openlibrecommunity/olcrtc/internal/gate"
)

// ai-generated: the whole file (the deltas between two reports and their
// table).

// Delta is one metric of one cell, then and now.
type Delta struct {
	Cell       string
	Metric     string
	Prev       float64
	Cur        float64
	Change     float64 // a fraction: +0.10 is 10 % higher now
	Regression bool
}

// regressionFraction is how far a tracked metric may move the worse way
// before the move is a regression (spec section 6).
const regressionFraction = 0.25

// metricStatus is the delta of a verdict: 1 was a pass, 0 is a failure.
const metricStatus = "status"

// Severities: what a regression does to compare's exit code.
const (
	severityWarn = "warn"
	severityFail = "fail"
)

// tracked is a metric compare judges and the sign of a change for the worse.
type tracked struct {
	key   string
	worse float64
}

// Compare pairs the cells of two reports by id and judges the metrics that
// track quality: throughput must not fall, memory must not rise, a pass must
// not become a failure. A cell in only one report has nothing to be compared
// with and yields no delta.
func Compare(prev, cur gate.Report) []Delta {
	metrics := []tracked{
		{gate.MetricThroughputDownBps, -1}, {gate.MetricThroughputUpBps, -1},
		{gate.MetricHeapPeakBytes, +1}, {gate.MetricRSSPeakBytes, +1},
	}
	before := make(map[string]gate.Cell, len(prev.Cells))
	for _, c := range prev.Cells {
		before[c.ID] = c
	}
	var out []Delta
	for _, c := range cur.Cells {
		p, ok := before[c.ID]
		if !ok {
			continue
		}
		if p.Status == gate.StatusPass && c.Status != gate.StatusPass {
			out = append(out, Delta{Cell: c.ID, Metric: metricStatus, Prev: 1, Cur: 0, Change: -1, Regression: true})
		}
		for _, m := range metrics {
			out = appendDelta(out, c.ID, m, p.Metrics, c.Metrics)
		}
	}
	slices.SortFunc(out, func(a, b Delta) int {
		return cmp.Or(cmp.Compare(a.Cell, b.Cell), cmp.Compare(a.Metric, b.Metric))
	})
	return out
}

// appendDelta adds a cell's delta of one tracked metric when both reports
// measured it and the previous value is a base a fraction can be taken of.
func appendDelta(out []Delta, cell string, m tracked, prev, cur map[string]float64) []Delta {
	pv, okP := prev[m.key]
	cv, okC := cur[m.key]
	if !okP || !okC || pv == 0 {
		return out
	}
	change := (cv - pv) / pv
	return append(out, Delta{Cell: cell, Metric: m.key, Prev: pv, Cur: cv, Change: change,
		Regression: change*m.worse > regressionFraction})
}

// RenderDeltas writes the comparison as Markdown and says whether any delta
// is a regression. A regression is marked REGRESSION at either severity; the
// line under the heading names the severity, so a reader knows whether the
// regressions fail the run.
func RenderDeltas(deltas []Delta, severity string) (string, bool) {
	effect := "a regression is reported and does not fail the run"
	if severity == severityFail {
		effect = "a regression fails the run"
	}
	var b strings.Builder
	_, _ = fmt.Fprintf(&b, "### Against the previous release\n\nSeverity `%s`: %s.\n\n"+
		"| Cell | Metric | Before | Now | Change |\n| --- | --- | --- | --- | --- |\n", severity, effect)
	regressed := false
	for _, d := range deltas {
		change := ""
		if d.Metric != metricStatus {
			change = fmt.Sprintf("%+.0f%%", d.Change*100)
		}
		if d.Regression {
			regressed = true
			change = strings.TrimSpace("**REGRESSION** " + change)
		}
		_, _ = fmt.Fprintf(&b, "| `%s` | %s | %s | %s | %s |\n",
			d.Cell, d.Metric, value(d.Metric, d.Prev), value(d.Metric, d.Cur), change)
	}
	if len(deltas) == 0 {
		_, _ = b.WriteString("| - | no tracked metric in both reports | | | |\n")
	}
	return b.String(), regressed
}

// value prints a delta's number in its metric's unit.
func value(metric string, v float64) string {
	switch metric {
	case metricStatus:
		if v == 1 {
			return gate.StatusPass
		}
		return gate.StatusFail
	case gate.MetricThroughputDownBps, gate.MetricThroughputUpBps:
		return fmt.Sprintf("%.2f Mbit/s", v/1e6)
	case gate.MetricHeapPeakBytes, gate.MetricRSSPeakBytes:
		return fmt.Sprintf("%.1f MiB", v/(1<<20))
	}
	return strconv.FormatFloat(v, 'f', -1, 64)
}
