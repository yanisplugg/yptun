package gate

import (
	"maps"
	"math"
	"reflect"
	"slices"
	"strings"
	"testing"
	"time"
)

// ai-generated: whole file, unit cover for the thresholds and the verdict.

func TestEvaluateEachRuleWithAPassAndAFail(t *testing.T) {
	th := Thresholds{ConnectP95: 5 * time.Second, ThroughputDownBps: 2e6, ThroughputUpBps: 2e6,
		HeapGrowthBytes: 12 << 20, RSSGrowthBytes: 19 << 20, GoroutineGrowth: 20, ResolverAnswered: 63}
	// ai-generated: S7 judges what the client added over the baseline, so a
	// process 47 MiB big that grew 17 MiB passes and one that grew more fails.
	s7 := func(heapPeak, rssPeak, goroutinesAfter float64) Metrics {
		return Metrics{"heap_baseline_bytes": 4 << 20, "heap_peak_bytes": heapPeak, "rss_baseline_bytes": 30 << 20,
			"rss_peak_bytes": rssPeak, "goroutines_idle": 80, "goroutines_after": goroutinesAfter}
	}
	cases := []struct {
		scenario string
		pass     Metrics
		fail     Metrics
		reason   string
	}{
		{"S0", Metrics{"handshake_ms": 900, "pull_ok": 1, "push_ok": 1},
			Metrics{"handshake_ms": 26000, "pull_ok": 1, "push_ok": 1}, "handshake_ms"},
		{"S0", Metrics{"handshake_ms": 900, "pull_ok": 1, "push_ok": 1},
			Metrics{"handshake_ms": 900, "pull_ok": 0, "push_ok": 1}, "pull_ok"},
		{"S1", Metrics{"connect_ok": 48, "connect_total": 48, "connect_p95_ms": 700},
			Metrics{"connect_ok": 47, "connect_total": 48, "connect_p95_ms": 700}, "connect_ok"},
		{"S1", Metrics{"connect_ok": 48, "connect_total": 48, "connect_p95_ms": 700},
			Metrics{"connect_ok": 48, "connect_total": 48, "connect_p95_ms": 5001}, "connect_p95_ms"},
		{"S2", Metrics{"pull_ok": 6, "pull_total": 6, "throughput_down_bps": 4e6, "on_top_ok": 20, "on_top_total": 20, "on_top_p95_ms": 900},
			Metrics{"pull_ok": 6, "pull_total": 6, "throughput_down_bps": 1e6, "on_top_ok": 20, "on_top_total": 20, "on_top_p95_ms": 900}, "throughput_down_bps"},
		{"S3", Metrics{"push_ok": 4, "push_total": 4, "throughput_up_bps": 3e6, "on_top_ok": 10, "on_top_total": 10, "on_top_p95_ms": 900},
			Metrics{"push_ok": 4, "push_total": 4, "throughput_up_bps": 3e6, "on_top_ok": 9, "on_top_total": 10, "on_top_p95_ms": 900}, "on_top_ok"},
		{"S4", Metrics{"alive": 1, "missed_pong": 0, "reconnects": 0, "final_pull_ok": 1},
			Metrics{"alive": 1, "missed_pong": 2, "reconnects": 0, "final_pull_ok": 1}, "missed_pong"},
		{"S5", Metrics{"answered_1": 64, "answered_2": 63},
			Metrics{"answered_1": 64, "answered_2": 60}, "answered_2"},
		{"S6", Metrics{"ready_3s_ms": 4100, "ready_8s_ms": 9200},
			Metrics{"ready_3s_ms": 4100, "ready_8s_ms": 0}, "ready_8s_ms"},
		{"S7", s7(9<<20, 47<<20, 90), s7(9<<20, 47<<20, 140), "goroutines"},
		{"S7", s7(9<<20, 47<<20, 90), s7(9<<20, 50<<20, 90), "rss grew"},
		{"S7", s7(9<<20, 47<<20, 90), s7(17<<20, 47<<20, 90), "heap grew"},
	}
	for _, c := range cases {
		if got := Evaluate(c.scenario, c.pass, th); len(got) != 0 {
			t.Errorf("%s pass sample failed: %v", c.scenario, got)
		}
		got := Evaluate(c.scenario, c.fail, th)
		if len(got) == 0 || !strings.Contains(strings.Join(got, ";"), c.reason) {
			t.Errorf("%s fail sample: failures = %v, want one naming %s", c.scenario, got, c.reason)
		}
	}
}

func TestEvaluateUnknownScenarioFails(t *testing.T) {
	if got := Evaluate("S9", Metrics{}, Local); len(got) != 1 || !strings.Contains(got[0], "no rules") {
		t.Fatalf("unknown scenario verdict = %v", got)
	}
}

func TestThresholdMapHasEveryKnob(t *testing.T) {
	m := Link.Map()
	for _, k := range []string{"connect_p95_ms", "throughput_down_bps", "throughput_up_bps",
		"heap_growth_bytes", "rss_growth_bytes", "goroutine_growth", "resolver_answered"} {
		if _, ok := m[k]; !ok {
			t.Fatalf("Map() lacks %s", k)
		}
	}
}

// testThresholds are fixed numbers for the verdict's tests, apart from Local
// and Link, which get tuned. The floors differ, as Link's do, so a verdict
// that judges S2 by the up floor or S3 by the down one fails a test.
func testThresholds() Thresholds {
	return Thresholds{ConnectP95: 5 * time.Second, ThroughputDownBps: 2e6, ThroughputUpBps: 1.5e6,
		HeapGrowthBytes: 12 << 20, RSSGrowthBytes: 19 << 20, GoroutineGrowth: 20, ResolverAnswered: 63}
}

// passing holds one sample per scenario that meets every rule under
// testThresholds, and carries only the metrics those rules read.
func passing() map[string]Metrics {
	return map[string]Metrics{
		"S0": {"handshake_ms": 900, "pull_ok": 1, "push_ok": 1},
		"S1": {"connect_ok": 48, "connect_total": 48, "connect_p95_ms": 700},
		"S2": {"pull_ok": 6, "pull_total": 6, "throughput_down_bps": 4e6,
			"on_top_ok": 20, "on_top_total": 20, "on_top_p95_ms": 900},
		"S3": {"push_ok": 4, "push_total": 4, "throughput_up_bps": 3e6,
			"on_top_ok": 10, "on_top_total": 10, "on_top_p95_ms": 900},
		"S4": {"alive": 1, "missed_pong": 0, "reconnects": 0, "final_pull_ok": 1},
		"S5": {"answered_1": 64, "answered_2": 63},
		"S6": {"ready_3s_ms": 4100, "ready_8s_ms": 9200},
		"S7": {"heap_baseline_bytes": 4 << 20, "heap_peak_bytes": 9 << 20, "rss_baseline_bytes": 30 << 20,
			"rss_peak_bytes": 47 << 20, "goroutines_idle": 80, "goroutines_after": 90},
	}
}

// A metric a rule reads may not be absent or NaN and still pass: read as
// zero, an unrecorded upper bound (missed_pong, heap_peak_bytes) would pass,
// and NaN compares false with any bound. Each fails the cell, named.
func TestEvaluateFailsAMetricItCannotRead(t *testing.T) {
	th := testThresholds()
	for scenario, sample := range passing() {
		if got := Evaluate(scenario, sample, th); len(got) != 0 {
			t.Fatalf("%s passing sample failed: %v", scenario, got)
		}
		for key := range sample {
			absent := maps.Clone(sample)
			delete(absent, key)
			nan := maps.Clone(sample)
			nan[key] = math.NaN()
			for what, m := range map[string]Metrics{"without": absent, "with NaN as": nan} {
				if got := Evaluate(scenario, m, th); len(got) != 1 || !strings.Contains(got[0], key) {
					t.Errorf("%s %s %s: failures = %q, want one naming %s", scenario, what, key, got, key)
				}
			}
		}
	}
}

// A failure reads as the metric, what it measured and the bound, in digits
// a person reads (2000000, not 2e+06), in the order the rules are listed.
func TestEvaluateWordsEveryFailure(t *testing.T) {
	cases := []struct {
		scenario string
		m        Metrics
		want     []string
	}{
		{"S0", Metrics{"pull_ok": 1}, []string{"handshake_ms not measured", "push_ok 0 != 1"}},
		{"S0", Metrics{"handshake_ms": 900, "pull_ok": 2, "push_ok": 1}, []string{"pull_ok 2 != 1"}}, // a flag, not a count
		{"S2", Metrics{"pull_ok": 5, "pull_total": 6, "throughput_down_bps": 1_250_000.5,
			"on_top_ok": 0, "on_top_total": 0, "on_top_p95_ms": 5001}, []string{
			"pull_ok 5 of pull_total 6", "throughput_down_bps 1250000.5 < 2000000",
			"on_top_ok 0 of on_top_total 0", "on_top_p95_ms 5001 > 5000",
		}},
		{"S4", Metrics{"alive": 0, "reconnects": 1}, []string{
			"alive 0 != 1", "missed_pong not measured", "reconnects 1 > 0", "final_pull_ok 0 != 1",
		}},
		{"S5", Metrics{"answered_1": 62}, []string{"answered_1 62 < 63", "answered_2 0 < 63"}},
		{"S6", Metrics{"ready_8s_ms": -1}, []string{"ready_3s_ms 0: never ready", "ready_8s_ms -1: never ready"}},
		// ai-generated: a hook that no longer holds the bridge back: ready in
		// the 4.3 s an undelayed handshake takes, which passes the 3 s case
		// alone. Without the floor the cell would pass having tested nothing.
		{"S6", Metrics{"ready_3s_ms": 4300, "ready_8s_ms": 4300}, []string{
			"ready_8s_ms 4300 < 8000: the bridge was not late",
		}},
		{"S7", Metrics{"heap_baseline_bytes": 1 << 20, "heap_peak_bytes": 14 << 20, "rss_baseline_bytes": 26 << 20,
			"rss_peak_bytes": 46 << 20, "goroutines_idle": 80, "goroutines_after": 101},
			[]string{
				"heap grew by 13631488 > 12582912 (heap_baseline_bytes 1048576, heap_peak_bytes 14680064)",
				"rss grew by 20971520 > 19922944 (rss_baseline_bytes 27262976, rss_peak_bytes 48234496)",
				"goroutines grew by 21 > 20 (goroutines_idle 80, goroutines_after 101)",
			}},
		// A missing idle count read as zero would pass this: growth 10 <= 20.
		{"S7", Metrics{"heap_baseline_bytes": 4 << 20, "heap_peak_bytes": 9 << 20, "rss_baseline_bytes": 30 << 20,
			"rss_peak_bytes": 40 << 20, "goroutines_after": 10}, []string{"goroutines_idle not measured"}},
		// ai-generated: nor is a memory baseline read as zero: the peak alone
		// would weigh the harness as the client.
		{"S7", Metrics{"heap_peak_bytes": 9 << 20, "rss_baseline_bytes": 30 << 20, "rss_peak_bytes": 40 << 20,
			"goroutines_idle": 80, "goroutines_after": 90}, []string{"heap_baseline_bytes not measured"}},
	}
	for _, c := range cases {
		if got := Evaluate(c.scenario, c.m, testThresholds()); !slices.Equal(got, c.want) {
			t.Errorf("%s failures = %q, want %q", c.scenario, got, c.want)
		}
	}
}

// Bounds are inclusive, as the spec words them: p95 at most 5 s, at least
// 63 of 64 answered, the handshake within its budget. One past fails. S3 and
// S6 step two bounds each: unstepped, a wrong S3 floor or ready_3s_ms judged
// by the 8 s limit would pass.
func TestEvaluateBoundsAreInclusive(t *testing.T) {
	hs := float64(handshakeBudget.Milliseconds())
	s3 := Metrics{"push_ok": 4, "push_total": 4, "throughput_up_bps": 1.5e6,
		"on_top_ok": 1, "on_top_total": 1, "on_top_p95_ms": 5000}
	// The bound is the delay, the reply window and readySlack (see verdict.go).
	slack := float64(readySlack / time.Millisecond)
	s6 := Metrics{"ready_3s_ms": 3000 + hs + slack, "ready_8s_ms": 8000 + hs + slack}
	s7 := Metrics{"heap_baseline_bytes": 4 << 20, "heap_peak_bytes": 16 << 20, "rss_baseline_bytes": 26 << 20,
		"rss_peak_bytes": 45 << 20, "goroutines_idle": 80, "goroutines_after": 100}
	cases := []struct {
		scenario string
		at       Metrics
		key      string  // the metric then stepped one past its bound
		step     float64 // +1 past a ceiling, -1 past a floor
	}{
		{"S0", Metrics{"handshake_ms": float64(connectBudget.Milliseconds()), "pull_ok": 1, "push_ok": 1}, "handshake_ms", 1},
		{"S1", Metrics{"connect_ok": 48, "connect_total": 48, "connect_p95_ms": 5000}, "connect_p95_ms", 1},
		{"S2", Metrics{"pull_ok": 6, "pull_total": 6, "throughput_down_bps": 2e6,
			"on_top_ok": 1, "on_top_total": 1, "on_top_p95_ms": 5000}, "throughput_down_bps", -1},
		{"S3", s3, "throughput_up_bps", -1},
		{"S3", s3, "on_top_p95_ms", 1},
		{"S4", Metrics{"alive": 1, "missed_pong": 0, "reconnects": 0, "final_pull_ok": 1}, "missed_pong", 1},
		{"S5", Metrics{"answered_1": 63, "answered_2": 63}, "answered_2", -1},
		{"S6", s6, "ready_3s_ms", 1},
		{"S6", s6, "ready_8s_ms", 1},
		{"S6", Metrics{"ready_3s_ms": 3000, "ready_8s_ms": 8000}, "ready_3s_ms", -1},
		{"S6", Metrics{"ready_3s_ms": 3000, "ready_8s_ms": 8000}, "ready_8s_ms", -1},
		{"S7", s7, "goroutines_after", 1},
		{"S7", s7, "heap_peak_bytes", 1},
		{"S7", s7, "rss_peak_bytes", 1},
		{"S7", s7, "rss_baseline_bytes", -1},
	}
	for _, c := range cases {
		if got := Evaluate(c.scenario, c.at, testThresholds()); len(got) != 0 {
			t.Errorf("%s at its bounds failed: %v", c.scenario, got)
		}
		past := maps.Clone(c.at)
		past[c.key] += c.step
		if got := Evaluate(c.scenario, past, testThresholds()); len(got) != 1 {
			t.Errorf("%s one past its bound on %s: failures = %v", c.scenario, c.key, got)
		}
	}
}

// Map carries every knob, each under its own name: a swapped pair (down for
// up) would put the wrong bound next to a cell's metrics.
func TestThresholdMapCarriesEachKnobsValue(t *testing.T) {
	th := Thresholds{ConnectP95: 1500 * time.Millisecond, ThroughputDownBps: 2, ThroughputUpBps: 3,
		HeapGrowthBytes: 4, RSSGrowthBytes: 5, GoroutineGrowth: 6, ResolverAnswered: 7}
	want := map[string]float64{"connect_p95_ms": 1500, "throughput_down_bps": 2, "throughput_up_bps": 3,
		"heap_growth_bytes": 4, "rss_growth_bytes": 5, "goroutine_growth": 6, "resolver_answered": 7}
	got := th.Map()
	if !maps.Equal(got, want) {
		t.Fatalf("Map() = %v, want %v", got, want)
	}
	if fields := reflect.TypeFor[Thresholds]().NumField(); len(got) != fields {
		t.Fatalf("Map() has %d entries for %d knobs", len(got), fields)
	}
}

// A knob left at zero turns its rule off (a floor of 0 passes anything) or
// fails every cell (a ceiling of 0); both targets set every one.
func TestLocalAndLinkSetEveryKnob(t *testing.T) {
	for name, th := range map[string]Thresholds{"Local": Local, "Link": Link} {
		for knob, v := range th.Map() {
			if v <= 0 {
				t.Errorf("%s leaves %s at %v", name, knob, v)
			}
		}
	}
}
