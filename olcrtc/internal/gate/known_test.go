package gate

import (
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"
)

// ai-generated: whole file, unit cover for the known failures: the pattern a
// cell id is matched by, the list itself, and the recorder that marks known
// cells and counts their failures.

// fakeIssue and fakeIssue2 are made-up issues of the lists these tests swap in.
const (
	fakeIssue  = "https://github.com/example/fake-engine/issues/1"
	fakeIssue2 = "https://github.com/example/fake-engine/issues/2"
)

// withKnown puts list in place of the known failures for the test.
func withKnown(t *testing.T, list ...KnownFailure) {
	t.Helper()
	saved := knownFailures
	knownFailures = list
	t.Cleanup(func() { knownFailures = saved })
}

func TestCellMatchesAStarAsOneWholeSegment(t *testing.T) {
	const id, sei = "engine-linux/jitsi/seichannel/cli/S0", "engine-linux/jitsi/seichannel/*/S0"
	for _, tc := range []struct {
		pattern, id string
		want        bool
	}{
		{id, id, true},
		{sei, id, true},
		{sei, "engine-linux/jitsi/seichannel/mobile/S0", true},
		{"*/*/*/*/*", id, true},
		// Only the segment under the star is free.
		{sei, "engine-linux/jitsi/seichannel/cli/S1", false},
		{sei, "engine-linux/wbstream/seichannel/cli/S0", false},
		// A star is no wildcard inside a segment, and a segment is matched
		// whole, never by its prefix.
		{"engine-linux/jitsi/sei*/cli/S0", id, false},
		{"engine-linux/jitsi/*channel/cli/S0", id, false},
		{"engine-linux/jitsi/seichannel/cli/S", id, false},
		{id, id + "1", false},
		// A star never spans segments, nor stands for none or an empty one.
		{"engine-linux/*/S0", id, false},
		{"engine-linux/jitsi/*/S0", id, false},
		{sei, "engine-linux/jitsi/seichannel/S0", false},
		{sei, "engine-linux/jitsi/seichannel//S0", false},
		{sei, id + "/S0", false},
		{"engine-linux/jitsi/seichannel/*", id, false},
	} {
		if got := cellMatches(tc.pattern, tc.id); got != tc.want {
			t.Errorf("cellMatches(%q, %q) = %t, want %t", tc.pattern, tc.id, got, tc.want)
		}
	}
}

func TestKnownFailureIsTheFirstEntryACellMatches(t *testing.T) {
	withKnown(t,
		KnownFailure{Cell: "p/jitsi/datachannel/*/S2", Issue: fakeIssue, Why: "fake: first"},
		KnownFailure{Cell: "p/jitsi/*/mobile/S2", Issue: fakeIssue2, Why: "fake: second"},
	)
	for id, want := range map[string]string{
		"p/jitsi/datachannel/mobile/S2": fakeIssue,
		"p/jitsi/datachannel/cli/S2":    fakeIssue,
		"p/jitsi/vp8channel/mobile/S2":  fakeIssue2,
		"p/jitsi/vp8channel/cli/S2":     "",
		"p/jitsi/datachannel/mobile/S3": "",
	} {
		k, ok := knownFailure(id)
		if k.Issue != want || ok != (want != "") {
			t.Errorf("knownFailure(%q) = %+v, %t; want the issue %q", id, k, ok, want)
		}
	}
}

// TestTheKnownListNamesPlannedCellsAndOpenIssues holds the list to its own
// rules: each entry a well-formed pattern that matches cells of the plan and
// names its provider, an issue URL and a reason, and no cell matched by two
// entries, which would leave one of them unread.
func TestTheKnownListNamesPlannedCellsAndOpenIssues(t *testing.T) {
	lt, err := NewLocalTarget(LocalOptions{WorkDir: t.TempDir(), JitsiHosts: []string{"meet.example.invalid"},
		Providers: localProviders(), Transports: splitList(allTransports)})
	if err != nil {
		t.Fatal(err)
	}
	plan := PlanCells(lt, []string{cliFlavour, mobileFlavour})
	issue := regexp.MustCompile(`^https://github\.com/[\w.-]+/[\w.-]+/issues/[1-9]\d*$`)
	matchedBy := map[string]string{}
	for _, k := range knownFailures {
		segments := strings.Split(k.Cell, "/")
		if len(segments) != 5 || strings.Contains("/"+k.Cell+"/", "//") {
			t.Errorf("%q is not platform/provider/transport/client/scenario", k.Cell)
			continue
		}
		if segments[1] == "*" {
			t.Errorf("%q names no provider: an entry is about one provider's bug", k.Cell)
		}
		if !issue.MatchString(k.Issue) || strings.TrimSpace(k.Why) == "" {
			t.Errorf("%q needs an issue URL and a reason: %+v", k.Cell, k)
		}
		matched := 0
		for _, c := range plan {
			if !cellMatches(k.Cell, c.ID) {
				continue
			}
			matched++
			if other, taken := matchedBy[c.ID]; taken {
				t.Errorf("%s is matched by %q and by %q", c.ID, other, k.Cell)
			}
			matchedBy[c.ID] = k.Cell
		}
		if matched == 0 {
			t.Errorf("%q matches no cell of the plan", k.Cell)
		}
	}
}

// TestRecorderMarksKnownCellsAndCountsTheirFailures records a known failure,
// a known cell that passed, a failure the list does not know, a known cell
// whose server never came up and one that never ran: only the first two
// carry the issue, and only the first counts in FailedKnown.
// ai-generated: the wbstream case of the rule that a cell that never ran is
// never known.
// TestAWBCellWithoutItsServerIsNeverKnown pins why a wbstream entry is safe:
// a missing token fails the pair before any cell runs, and such a cell stays
// a blocking failure even though the list names it.
func TestAWBCellWithoutItsServerIsNeverKnown(t *testing.T) {
	withKnown(t, KnownFailure{Cell: "engine-linux/wbstream/seichannel/*/S0", Issue: fakeIssue, Why: "fake: slow"})
	r := NewRecorder(Report{})
	r.Plan([]Cell{{ID: "engine-linux/wbstream/seichannel/cli/S0"}})
	r.NotRun("engine-linux/wbstream/seichannel/cli/S0", nil,
		"server: wbstream: OLCRTC_GATE_WBSTREAM_TOKEN is not set")
	rep := r.Report()
	if rep.Failed != 1 || rep.FailedKnown != 0 {
		t.Fatalf("failed %d, failed_known %d; want 1 and 0", rep.Failed, rep.FailedKnown)
	}
	if c := cellsByID(rep)["engine-linux/wbstream/seichannel/cli/S0"]; c.Known != "" {
		t.Fatalf("a cell that never ran is known: %q", c.Known)
	}
	if gateFailure(rep) == "" {
		t.Fatal("a WB cell without its server did not fail the gate")
	}
}

func TestRecorderMarksKnownCellsAndCountsTheirFailures(t *testing.T) {
	withKnown(t,
		KnownFailure{Cell: "p/a/*/cli/S0", Issue: fakeIssue, Why: "fake: pulls stall"},
		KnownFailure{Cell: "p/a/b/cli/S1", Issue: fakeIssue2, Why: "fake: never run here"},
	)
	r := NewRecorder(Report{})
	r.Plan([]Cell{{ID: "p/a/b/cli/S0"}, {ID: "p/a/c/cli/S0"}, {ID: "p/a/d/cli/S0"}, {ID: "p/a/b/cli/S1"},
		{ID: "p/a/b/cli/S2"}})
	r.Finish("p/a/b/cli/S0", Metrics{MetricPullOK: 0}, nil, []string{"pull_ok 0 != 1"}, "", time.Second)
	r.Finish("p/a/c/cli/S0", Metrics{MetricPullOK: 1}, nil, nil, "", time.Second)
	r.NotRun("p/a/d/cli/S0", map[string]float64{"handshake_ms": 15000}, "server: fake: status 403")
	r.Finish("p/a/b/cli/S2", nil, nil, []string{"pull_ok 0 of pull_total 6"}, "", time.Second)
	rep := r.Report()
	if rep.Planned != 5 || rep.Executed != 4 || rep.Passed != 1 || rep.Failed != 4 || rep.FailedKnown != 1 {
		t.Fatalf("counts = planned %d executed %d passed %d failed %d failed_known %d",
			rep.Planned, rep.Executed, rep.Passed, rep.Failed, rep.FailedKnown)
	}
	cells := cellsByID(rep)
	for id, want := range map[string]string{
		"p/a/b/cli/S0": fakeIssue, // failed and on the list
		"p/a/c/cli/S0": fakeIssue, // passed and on the list
		"p/a/d/cli/S0": "",        // on the list, but its server never came up
		"p/a/b/cli/S1": "",        // on the list, but it never ran
		"p/a/b/cli/S2": "",        // failed, not on the list
	} {
		if c := cells[id]; c.Known != want {
			t.Errorf("%s = %+v, want known %q", id, c, want)
		}
	}
	if c := cells["p/a/d/cli/S0"]; c.Status != StatusFail || c.Failures[0] != "server: fake: status 403" ||
		c.Thresholds["handshake_ms"] != 15000 {
		t.Fatalf("a cell whose server never came up = %+v", c)
	}
	if c := cells["p/a/b/cli/S1"]; c.Status != StatusFail || c.Failures[0] != reasonDidNotRun {
		t.Fatalf("a known cell that never ran = %+v", c)
	}
}

func TestAWrittenReportCarriesTheIssueAndTheKnownCount(t *testing.T) {
	withKnown(t, KnownFailure{Cell: "p/a/b/*/S0", Issue: fakeIssue, Why: "fake"})
	r := NewRecorder(Report{})
	r.Plan([]Cell{{ID: "p/a/b/cli/S0"}, {ID: "p/a/b/cli/S1"}})
	r.Finish("p/a/b/cli/S0", nil, nil, []string{"pull_ok 0 != 1"}, "", time.Second)
	r.Finish("p/a/b/cli/S1", nil, nil, nil, "", time.Second)
	path := filepath.Join(t.TempDir(), reportName)
	if err := r.Write(path); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var back struct {
		Schema      int              `json:"schema"`
		FailedKnown *int             `json:"failed_known"`
		Cells       []map[string]any `json:"cells"`
	}
	if err := json.Unmarshal(raw, &back); err != nil {
		t.Fatal(err)
	}
	if back.Schema != ReportSchema || back.FailedKnown == nil || *back.FailedKnown != 1 || len(back.Cells) != 2 {
		t.Fatalf("written report:\n%s", raw)
	}
	if back.Cells[0]["known"] != fakeIssue {
		t.Fatalf("the known cell reads known %v:\n%s", back.Cells[0]["known"], raw)
	}
	if _, ok := back.Cells[1]["known"]; ok {
		t.Fatalf("a cell off the list carries known:\n%s", raw)
	}
}

// TestSaluteJazzIsKnownOnlyForItsBulkCells pins the list for salutejazz
// once the relay window is in (olcrtc#49): S2 and S3, download and upload
// saturation, of either flavour stay known, for what a slow Sber leg still
// costs them, and every other salutejazz cell (connect, burst, quiet,
// resolver, memory) blocks again.
// ai-generated: this test (the narrowed known list).
func TestSaluteJazzIsKnownOnlyForItsBulkCells(t *testing.T) {
	lt, err := NewLocalTarget(LocalOptions{WorkDir: t.TempDir(), JitsiHosts: []string{"meet.example.invalid"},
		Providers: localProviders(), Transports: splitList(allTransports)})
	if err != nil {
		t.Fatal(err)
	}
	bulk := map[string]int{}
	for _, c := range PlanCells(lt, []string{cliFlavour, mobileFlavour}) {
		if c.Provider != providerSaluteJazz {
			continue
		}
		k, known := knownFailure(c.ID)
		want := c.Scenario == "S2" || c.Scenario == "S3"
		switch {
		case known != want:
			t.Errorf("%s: known %t, want %t", c.ID, known, want)
		case !known:
		case k.Issue != issues+"49":
			t.Errorf("%s is known under %s, want olcrtc#49", c.ID, k.Issue)
		case !strings.Contains(k.Why, "throughput") || !strings.Contains(k.Why, "on-top latency") ||
			!strings.Contains(k.Why, "slow Sber leg"):
			t.Errorf("%s is known for %q, want the throughput and on-top latency of a slow Sber leg", c.ID, k.Why)
		default:
			bulk[c.Scenario]++
		}
	}
	if bulk["S2"] == 0 || bulk["S3"] == 0 {
		t.Fatalf("known bulk cells %v: the plan has no salutejazz S2 or S3 to hold the list to", bulk)
	}
}
