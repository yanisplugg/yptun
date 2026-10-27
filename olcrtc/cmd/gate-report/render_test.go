package main

import (
	"slices"
	"strings"
	"testing"

	"github.com/openlibrecommunity/olcrtc/internal/gate"
)

// ai-generated: whole file, unit cover for the Markdown table of a report.

func sampleReport() gate.Report {
	return gate.Report{Schema: 1, EngineCommit: "850aa5f9", AppVersion: "1.0.431", Target: "local", Runner: "Linux/ubuntu24",
		Planned: 2, Executed: 2, Passed: 1, Failed: 1, DurationS: 612,
		Cells: []gate.Cell{
			{ID: "engine-linux/jitsi/datachannel/mobile/S2", Scenario: "S2", Status: "pass", DurationS: 95,
				Metrics: map[string]float64{"throughput_down_bps": 4_812_000, "on_top_p95_ms": 830}, Failures: []string{}},
			{ID: "engine-linux/jitsi/datachannel/mobile/S4", Scenario: "S4", Status: "fail", DurationS: 61,
				Metrics: map[string]float64{"missed_pong": 2}, Failures: []string{"missed_pong 2 > 0"}},
		}}
}

func TestRenderIsATableWithVerdicts(t *testing.T) {
	md := Render(sampleReport())
	for _, want := range []string{"| Cell |", "engine-linux/jitsi/datachannel/mobile/S2", "4.8 Mbit/s", "✅", "❌",
		"missed_pong 2 > 0", "1 of 2 cells failed", "850aa5f9"} {
		if !strings.Contains(md, want) {
			t.Fatalf("render lacks %q:\n%s", want, md)
		}
	}
}

func TestRenderGolden(t *testing.T) {
	want := strings.Join([]string{
		"### Gate: local target, engine 850aa5f9, app 1.0.431",
		"",
		"1 of 2 cells failed · Linux/ubuntu24 · 612 s",
		"",
		"| Cell | Verdict | Key metrics | Took |",
		"| --- | --- | --- | --- |",
		"| `engine-linux/jitsi/datachannel/mobile/S2` | ✅ | ↓ 4.8 Mbit/s, on-top p95 830 ms | 95 s |",
		"| `engine-linux/jitsi/datachannel/mobile/S4` | ❌ missed_pong 2 > 0 | missed pongs 2 | 61 s |",
		"",
	}, "\n")
	r := sampleReport()
	slices.Reverse(r.Cells) // rows go by cell id whatever order the report holds
	if got := Render(r); got != want {
		t.Fatalf("render =\n%s\nwant\n%s", got, want)
	}
}

// ai-generated: a known cell names its issue, passed or failed, and the
// line under the table says the known failures fail no gate.
func TestRenderMarksKnownCellsWithTheirIssue(t *testing.T) {
	const issue9, issue15 = "https://github.com/ghostlane-project/olcrtc/issues/9",
		"https://github.com/ghostlane-project/olcrtc/issues/15"
	want := strings.Join([]string{
		"### Gate: local target, engine 850aa5f9, app 1.0.431",
		"",
		"1 of 2 cells failed · Linux/ubuntu24 · 612 s",
		"",
		"| Cell | Verdict | Key metrics | Took |",
		"| --- | --- | --- | --- |",
		"| `engine-linux/jitsi/datachannel/mobile/S2` | ✅ pass (known: [#15](" + issue15 + ")) | " +
			"↓ 4.8 Mbit/s, on-top p95 830 ms | 95 s |",
		"| `engine-linux/jitsi/datachannel/mobile/S4` | ❌ fail (known: [#9](" + issue9 + ")): missed_pong 2 > 0 | " +
			"missed pongs 2 | 61 s |",
		"",
		"1 known failure is tracked by an issue and does not fail the gate.",
		"",
	}, "\n")
	r := sampleReport()
	r.Cells[0].Known, r.Cells[1].Known, r.FailedKnown = issue15, issue9, 1
	if got := Render(r); got != want {
		t.Fatalf("render =\n%s\nwant\n%s", got, want)
	}
	r.Cells[0].Status, r.Cells[0].Failures = "fail", []string{}
	r.Failed, r.FailedKnown = 2, 2
	md := Render(r)
	if !strings.HasSuffix(md, "\n\n2 known failures are tracked by issues and do not fail the gate.\n") ||
		!strings.Contains(md, "| ❌ fail (known: [#15]("+issue15+")) |") {
		t.Fatalf("two known failures, one without a reason:\n%s", md)
	}
}

func TestIssueRefIsTheURLsNumberOrTheTextAsItIs(t *testing.T) {
	for in, want := range map[string]string{
		"https://github.com/ghostlane-project/olcrtc/issues/12": "[#12](https://github.com/ghostlane-project/olcrtc/issues/12)",
		"https://github.com/example/fake/issues/latest":         "https://github.com/example/fake/issues/latest",
		"olcrtc#12":                      "olcrtc#12",
		"http://example.invalid/12":      "http://example.invalid/12",
		"https://example.invalid/a|b/12": `https://example.invalid/a\|b/12`,
		"https://example.invalid/<x>/12": "https://example.invalid/&lt;x>/12",
		"https://example.invalid/x)/12":  "https://example.invalid/x)/12",
	} {
		if got := issueRef(in); got != want {
			t.Errorf("issueRef(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestRenderHeadingAndSummary(t *testing.T) {
	all := sampleReport()
	all.Cells[1].Status, all.Cells[1].Failures = "pass", []string{}
	all.Passed, all.Failed = 2, 0
	empty := gate.Report{Schema: 1, Target: "local"}
	for _, tc := range []struct {
		name  string
		r     gate.Report
		want  string
		never string
	}{
		{"every cell passed", all,
			"### Gate: local target, engine 850aa5f9, app 1.0.431\n\nall 2 cells passed · Linux/ubuntu24 · 612 s\n\n", "❌"},
		// No cells is no pass, and a commit, app or runner the report lacks
		// leaves no gap in either line.
		{"a report with no cells and no metadata", empty,
			"### Gate: local target, engine unknown\n\nno cells were planned · 0 s\n\n", "passed"},
		// A run with a DTLS profile says so in its heading: its table is not
		// the stock handshake's.
		{"a run with a dtls profile", gate.Report{Schema: 1, Target: "local", DTLSProfile: "chrome-linux-138-compat-v1"},
			"### Gate: local target, engine unknown, DTLS chrome-linux-138-compat-v1\n\n", "DTLS off"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			md := Render(tc.r)
			if !strings.HasPrefix(md, tc.want) || strings.Contains(md, tc.never) {
				t.Fatalf("render wants to start with %q and never hold %q:\n%s", tc.want, tc.never, md)
			}
		})
	}
}

func TestRenderKeepsAFailureInsideItsCell(t *testing.T) {
	r := sampleReport()
	// A pipe would end the table cell, a line break the row, and the
	// scrubber's <room> would vanish as an unknown HTML tag.
	r.Cells[1].Failures = []string{"join <room>: 403 | retried", "first line\nsecond line"}
	rows := strings.Split(strings.TrimSuffix(Render(r), "\n"), "\n")
	want := `| ❌ join &lt;room>: 403 \| retried; first line second line |`
	if len(rows) != 8 || !strings.Contains(rows[7], want) {
		t.Fatalf("rows = %q, want 8 with the last holding %q", rows, want)
	}
}

func TestRenderKeyMetricsInUnits(t *testing.T) {
	c := gate.Cell{Metrics: map[string]float64{
		gate.MetricThroughputUpBps: 3_140_000, gate.MetricConnectP95Ms: 412, gate.MetricHandshakeMs: 2100,
		gate.MetricHeapPeakBytes: 9 << 20, gate.MetricRSSPeakBytes: 31.5 * (1 << 20),
		gate.MetricAnswered1: 64, gate.MetricAnswered2: 63, gate.MetricReady3sMs: 4200, gate.MetricReady8sMs: 9100,
		gate.MetricGoroutinesIdle: 12,
	}}
	want := "↑ 3.1 Mbit/s, connect p95 412 ms, handshake 2100 ms, heap peak 9.0 MiB, RSS peak 31.5 MiB, " +
		"burst 1 answered 64/64, burst 2 answered 63/64, ready 4200 ms (bridge 3 s late), " +
		"ready 9100 ms (bridge 8 s late)"
	if got := keyMetrics(c); got != want {
		t.Fatalf("key metrics =\n%s\nwant\n%s", got, want)
	}
}

func TestRenderKeyMetricsNeverReady(t *testing.T) {
	// S6 records 0 for a client that never got ready; the verdict takes
	// anything not above 0 so.
	c := gate.Cell{Metrics: map[string]float64{gate.MetricReady3sMs: 0, gate.MetricReady8sMs: -1}}
	want := "never ready (bridge 3 s late), never ready (bridge 8 s late)"
	if got := keyMetrics(c); got != want {
		t.Fatalf("key metrics = %q, want %q", got, want)
	}
}
