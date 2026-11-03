package gate

import (
	"context"
	"fmt"
	"strings"
	"testing"
)

// ai-generated: whole file, unit cover for the scenario registry and the plan.

type fakeTarget struct{ pairs []Pair }

func (fakeTarget) Name() string     { return "fake" }
func (fakeTarget) Platform() string { return "engine-test" }
func (f fakeTarget) Pairs() []Pair  { return f.pairs }
func (fakeTarget) Load() LoadURLs   { return LoadURLs{} }
func (fakeTarget) Open(context.Context, Pair, string, OpenOptions) (Endpoint, func(), error) {
	return Endpoint{}, func() {}, nil
}

// resetRegistryForTest gives a test an empty registry and puts the real one
// back when it ends.
func resetRegistryForTest(t *testing.T) {
	t.Helper()
	registryMu.Lock()
	saved := registry
	registry = nil
	registryMu.Unlock()
	t.Cleanup(func() {
		registryMu.Lock()
		registry = saved
		registryMu.Unlock()
	})
}

func TestPlanCellsEnumeratesEveryApplicableCell(t *testing.T) {
	resetRegistryForTest(t)
	Register(Scenario{ID: "S1", Name: "one", Applies: func(Target, Pair, string) bool { return true }})
	Register(Scenario{ID: "S0", Name: "zero", Applies: func(_ Target, _ Pair, c string) bool { return c == "mobile" }})
	target := fakeTarget{pairs: []Pair{{"jitsi", "datachannel"}, {"telemost", "vp8channel"}}}
	cells := PlanCells(target, []string{"cli", "mobile"})
	want := []string{
		"engine-test/jitsi/datachannel/mobile/S0",
		"engine-test/telemost/vp8channel/mobile/S0",
		"engine-test/jitsi/datachannel/cli/S1",
		"engine-test/jitsi/datachannel/mobile/S1",
		"engine-test/telemost/vp8channel/cli/S1",
		"engine-test/telemost/vp8channel/mobile/S1",
	}
	if len(cells) != len(want) {
		t.Fatalf("PlanCells returned %d cells, want %d: %+v", len(cells), len(want), cells)
	}
	for i, c := range cells {
		if c.ID != want[i] || c.Status != "planned" {
			t.Fatalf("cell %d = %q (%s), want %q (planned)", i, c.ID, c.Status, want[i])
		}
	}
}

func TestScenariosAreSortedByID(t *testing.T) {
	resetRegistryForTest(t)
	Register(Scenario{ID: "S7"})
	Register(Scenario{ID: "S2"})
	if got := Scenarios(); got[0].ID != "S2" || got[1].ID != "S7" {
		t.Fatalf("Scenarios() order = %s, %s", got[0].ID, got[1].ID)
	}
}

func TestRegisterRefusesAnEmptyOrRepeatedID(t *testing.T) {
	resetRegistryForTest(t)
	Register(Scenario{ID: "S0", Name: "kept"})
	for _, s := range []Scenario{{Name: "no id"}, {ID: "S0", Name: "again"}} {
		func() {
			defer func() {
				if recover() == nil {
					t.Fatalf("Register(%q) did not panic", s.ID)
				}
			}()
			Register(s)
		}()
	}
	if got := Scenarios(); len(got) != 1 || got[0].Name != "kept" {
		t.Fatalf("registry = %+v", got)
	}
}

func TestEndpointNeverPrintsRoomOrKey(t *testing.T) {
	ep := Endpoint{Provider: "jitsi", Transport: "datachannel", Room: "https://meet.example.invalid/fake-gate-room",
		Key: strings.Repeat("0f", 32), DNS: "192.0.2.53:53", VP8FPS: 60, VP8Batch: 64}
	values := map[string]any{"endpoint": ep, "env": Env{Endpoint: ep}}
	for _, verb := range []string{"%v", "%+v", "%s", "%#v"} {
		for what, v := range values {
			out := fmt.Sprintf(verb, v)
			if strings.Contains(out, "fake-gate-room") || strings.Contains(out, "0f0f") {
				t.Fatalf("%s of an %s leaks a secret: %s", verb, what, out)
			}
			if !strings.Contains(out, "jitsi/datachannel") || !strings.Contains(out, "<room>") {
				t.Fatalf("%s of an %s lost what is not secret: %s", verb, what, out)
			}
		}
	}
	if out := (Endpoint{Provider: "jitsi", Transport: "datachannel"}).String(); strings.Contains(out, "<room>") {
		t.Fatalf("an endpoint without a room claims one: %s", out)
	}
}
