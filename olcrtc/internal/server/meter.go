// Per-key traffic metering.
//
// The server pairs each peer to one ring key (see muxconn pinning). This
// meter attributes per-stream byte counts to the paired key's stable id and
// exposes the totals on a loopback /stats endpoint an operator's agent polls.
// Only key ids (hex(sha256(key))[:16]) are ever exposed - never the key
// material.
//
// ai-generated: per-key meter behind the crypto.keys ring.
package server

import (
	"encoding/json"
	"net/http"
	"sync"
)

// keyCounters holds one key's cumulative byte totals since srv start.
// up = client→internet, down = internet→client.
type keyCounters struct {
	up   uint64
	down uint64
}

// meter accumulates per-key traffic and maps sessions to their paired key.
// Safe for concurrent use: OnTraffic fires from many stream goroutines while
// the stats handler reads snapshots.
type meter struct {
	mu         sync.Mutex
	perKey     map[string]*keyCounters
	sessionKey map[string]string // sessionID -> keyID
}

func newMeter() *meter {
	return &meter{
		perKey:     make(map[string]*keyCounters),
		sessionKey: make(map[string]string),
	}
}

// bind records which key a session was paired to. keyID "" (single key
// without an id, or unpaired) is ignored - such traffic stays uncounted
// rather than polluting a bucket. A nil meter (a Server built by hand in
// tests) meters nothing.
func (m *meter) bind(sessionID, keyID string) {
	if m == nil || keyID == "" || sessionID == "" {
		return
	}
	m.mu.Lock()
	m.sessionKey[sessionID] = keyID
	if _, ok := m.perKey[keyID]; !ok {
		m.perKey[keyID] = &keyCounters{}
	}
	m.mu.Unlock()
}

// add attributes a finished stream's byte counts to the session's key. A
// session with no bound key is silently skipped.
func (m *meter) add(sessionID string, up, down uint64) {
	if m == nil {
		return
	}
	m.mu.Lock()
	keyID, ok := m.sessionKey[sessionID]
	if ok {
		c := m.perKey[keyID]
		if c == nil {
			c = &keyCounters{}
			m.perKey[keyID] = c
		}
		c.up += up
		c.down += down
	}
	m.mu.Unlock()
}

// StatsDirection is one {up,down} pair in the /stats JSON. Exported so the
// agent (and integration tests) can decode the endpoint without redefining it.
type StatsDirection struct {
	Up   uint64 `json:"up"`
	Down uint64 `json:"down"`
}

// StatsBody is the /stats response: what the carrier session is doing, then
// per-key totals plus a grand total.
type StatsBody struct {
	// Link says whether the server is joined to its room (see LinkState).
	// It was added beside the totals rather than inside them so a parser
	// that only knows the totals keeps working.
	Link  LinkState                 `json:"link"`
	Keys  map[string]StatsDirection `json:"keys"`
	Total StatsDirection            `json:"total"`
}

// snapshot copies the current counters for serialization.
func (m *meter) snapshot() StatsBody {
	m.mu.Lock()
	defer m.mu.Unlock()
	resp := StatsBody{Keys: make(map[string]StatsDirection, len(m.perKey))}
	for keyID, c := range m.perKey {
		resp.Keys[keyID] = StatsDirection{Up: c.up, Down: c.down}
		resp.Total.Up += c.up
		resp.Total.Down += c.down
	}
	return resp
}

// statsHandler serves GET /stats with the current snapshot as JSON. link is
// read per request - the carrier state at the moment the agent asked, not at
// the moment the listener was bound - and must not be nil.
//
// ai-generated: the link parameter and the StatsBody field it fills.
func (m *meter) statsHandler(link func() LinkState) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/stats", func(w http.ResponseWriter, _ *http.Request) {
		body := m.snapshot()
		body.Link = link()
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(body)
	})
	return mux
}
