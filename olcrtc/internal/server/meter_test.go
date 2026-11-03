package server

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
)

func TestMeterAttributesPerKeyAndTotals(t *testing.T) {
	m := newMeter()
	m.bind("sess-A", "keyaaa0000000000")
	m.bind("sess-B", "keybbb0000000000")

	m.add("sess-A", 100, 200)
	m.add("sess-A", 10, 20)
	m.add("sess-B", 1, 2)
	m.add("unbound-session", 999, 999) // no bound key → ignored

	snap := m.snapshot()
	if snap.Keys["keyaaa0000000000"] != (StatsDirection{Up: 110, Down: 220}) {
		t.Fatalf("key A = %+v", snap.Keys["keyaaa0000000000"])
	}
	if snap.Keys["keybbb0000000000"] != (StatsDirection{Up: 1, Down: 2}) {
		t.Fatalf("key B = %+v", snap.Keys["keybbb0000000000"])
	}
	if snap.Total != (StatsDirection{Up: 111, Down: 222}) {
		t.Fatalf("total = %+v", snap.Total)
	}
	if len(snap.Keys) != 2 {
		t.Fatalf("unbound traffic leaked a bucket: %d keys", len(snap.Keys))
	}
}

func TestMeterBindIgnoresEmptyKey(t *testing.T) {
	m := newMeter()
	m.bind("sess", "") // legacy/unpaired → not metered
	m.add("sess", 500, 500)
	if len(m.snapshot().Keys) != 0 {
		t.Fatal("empty-key session was metered")
	}
}

func TestStatsHandlerServesContractJSON(t *testing.T) {
	m := newMeter()
	m.bind("s", "deadbeefdeadbeef")
	m.add("s", 7, 9)

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/stats", http.NoBody)
	m.statsHandler(func() LinkState { return LinkUp }).ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status %d", rec.Code)
	}
	var body StatsBody
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("bad json: %v (%s)", err, rec.Body.String())
	}
	if body.Keys["deadbeefdeadbeef"].Up != 7 || body.Keys["deadbeefdeadbeef"].Down != 9 {
		t.Fatalf("body = %s", rec.Body.String())
	}
	if body.Total.Up != 7 || body.Total.Down != 9 {
		t.Fatalf("total = %+v", body.Total)
	}
}

// TestStatsHandlerReportsTheLinkState pins the field an operator's agent
// reads to tell a server a client could pair with from one that is still
// looking for its room - and pins that adding it moved no other field.
func TestStatsHandlerReportsTheLinkState(t *testing.T) {
	for _, state := range []LinkState{LinkConnecting, LinkUp, LinkDown} {
		t.Run(string(state), func(t *testing.T) {
			m := newMeter()
			m.bind("s", "deadbeefdeadbeef")
			m.add("s", 7, 9)

			rec := httptest.NewRecorder()
			req := httptest.NewRequest(http.MethodGet, "/stats", http.NoBody)
			m.statsHandler(func() LinkState { return state }).ServeHTTP(rec, req)

			var body StatsBody
			if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
				t.Fatalf("bad json: %v (%s)", err, rec.Body.String())
			}
			if body.Link != state {
				t.Fatalf("link = %q, want %q (%s)", body.Link, state, rec.Body.String())
			}
			if body.Keys["deadbeefdeadbeef"] != (StatsDirection{Up: 7, Down: 9}) {
				t.Fatalf("the per-key totals moved: %s", rec.Body.String())
			}
			if body.Total != (StatsDirection{Up: 7, Down: 9}) {
				t.Fatalf("the grand total moved: %s", rec.Body.String())
			}

			// A parser written before the field decodes keys and total by
			// name, so the object has to keep carrying them - and carry the
			// new one beside them, not inside them.
			var raw map[string]json.RawMessage
			if err := json.Unmarshal(rec.Body.Bytes(), &raw); err != nil {
				t.Fatalf("bad json: %v (%s)", err, rec.Body.String())
			}
			if len(raw) != 3 || raw["keys"] == nil || raw["total"] == nil {
				t.Fatalf("the /stats object is no longer {link, keys, total}: %s", rec.Body.String())
			}
			if got := string(raw["link"]); got != strconv.Quote(string(state)) {
				t.Fatalf("link = %s, want %s", got, strconv.Quote(string(state)))
			}
		})
	}
}
