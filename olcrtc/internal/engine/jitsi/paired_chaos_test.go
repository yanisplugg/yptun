// Paired-instance chaos stress for the jitsi engine.
//
// Why paired: a single instance never receives session-initiate from
// Jicofo because of min-participants=2 (jicofo/.../reference.conf).
// Without a peer the bridge never opens and most of the engine's
// reconnect logic — peer-epoch latch, bridgeKeepalive, RTCP keepalive —
// is never exercised. The single-client tests proved that xmppKeepalive
// holds the BOSH session for a single endpoint, but the production
// failure mode the user actually observes (DTLS CloseNotify → cascading
// reconnects) is a property of the *paired* path.
//
// What this test does:
//
//  1. Spawn TWO Session instances against the same real Jitsi host and
//     room, with shared bytes flowing between them.
//  2. Continuously pump small data through the bridge in both directions.
//  3. Periodically introduce chaos:
//        - Force a teardownPC + requestReconnect on one side.
//        - Long idle pauses (>60s) so both Prosody BOSH idle and JVB
//          inactivityTimeout fire if any keepalive is broken.
//        - Random side selection so both directions get exercised.
//  4. Track per-cycle outcomes and fail the test if either side
//     permanently wedges (no Send for >2x the chaos cycle).
//
// Configuration via env (no flags so opt-in is one variable):
//
//	OLCRTC_JITSI_PAIRED_HOST          required, e.g. meet.handyweb.org
//	OLCRTC_JITSI_PAIRED_ROOM          optional, defaults to a unique name
//	OLCRTC_JITSI_PAIRED_DURATION      default 30m, "0"/"infinite" runs forever
//	OLCRTC_JITSI_PAIRED_IDLE          default 75s
//	OLCRTC_JITSI_PAIRED_CHAOS_INTERVAL default 60s — how often to cause chaos
//	OLCRTC_JITSI_PAIRED_VERBOSE       default off
//
// Quick run:
//
//	OLCRTC_JITSI_PAIRED_HOST=meet.handyweb.org \
//	  go test -count=1 -v -timeout 35m \
//	    -run '^TestJitsiPairedChaosStress$' ./internal/engine/jitsi/...
//
// Forever (Ctrl-C to stop, summary printed):
//
//	OLCRTC_JITSI_PAIRED_HOST=meet.handyweb.org \
//	OLCRTC_JITSI_PAIRED_DURATION=0 \
//	  go test -count=1 -v -timeout 0 \
//	    -run '^TestJitsiPairedChaosStress$' ./internal/engine/jitsi/...

package jitsi

import (
	"context"
	"errors"
	"fmt"
	"math/rand"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
)

const (
	envPairedHost          = "OLCRTC_JITSI_PAIRED_HOST"
	envPairedRoom          = "OLCRTC_JITSI_PAIRED_ROOM"
	envPairedDuration      = "OLCRTC_JITSI_PAIRED_DURATION"
	envPairedIdle          = "OLCRTC_JITSI_PAIRED_IDLE"
	envPairedChaosInterval = "OLCRTC_JITSI_PAIRED_CHAOS_INTERVAL"
	envPairedVerbose       = "OLCRTC_JITSI_PAIRED_VERBOSE"

	nameAlice = "alice"
	nameBob   = "bob"
)

var (
	errCastFailed       = errors.New("cast to *Session failed")
	errBridgeTimeout    = errors.New("bridge not ready before deadline")
	errRoundtripTimeout = errors.New("roundtrip timeout")
	errReceiveTimeout   = errors.New("receive timeout")
)

type pairedConfig struct {
	host, room    string
	duration      time.Duration
	idle          time.Duration
	chaosInterval time.Duration
	verbose       bool
}

func (c *pairedConfig) durationLabel() string {
	if c.duration == 0 {
		return "infinite"
	}
	return c.duration.String()
}

func readPairedConfig(t *testing.T) *pairedConfig { //nolint:cyclop // config parsing is naturally branchy
	t.Helper()
	host := strings.TrimSpace(os.Getenv(envPairedHost))
	if host == "" {
		t.Skipf("set %s to a real Jitsi host (e.g. meet.handyweb.org) to enable", envPairedHost)
	}
	cfg := &pairedConfig{
		host:          host,
		room:          fmt.Sprintf("olcrtc-paired-%d", time.Now().UnixNano()),
		duration:      30 * time.Minute,
		idle:          75 * time.Second,
		chaosInterval: 60 * time.Second,
	}
	if v := strings.TrimSpace(os.Getenv(envPairedRoom)); v != "" {
		cfg.room = v
	}
	if v := strings.TrimSpace(os.Getenv(envPairedDuration)); v != "" {
		switch strings.ToLower(v) {
		case "0", "infinite", "forever":
			cfg.duration = 0
		default:
			d, err := time.ParseDuration(v)
			if err != nil {
				t.Fatalf("%s=%q: %v", envPairedDuration, v, err)
			}
			cfg.duration = d
		}
	}
	if v := strings.TrimSpace(os.Getenv(envPairedIdle)); v != "" {
		d, err := time.ParseDuration(v)
		if err != nil {
			t.Fatalf("%s=%q: %v", envPairedIdle, v, err)
		}
		cfg.idle = d
	}
	if v := strings.TrimSpace(os.Getenv(envPairedChaosInterval)); v != "" {
		d, err := time.ParseDuration(v)
		if err != nil {
			t.Fatalf("%s=%q: %v", envPairedChaosInterval, v, err)
		}
		cfg.chaosInterval = d
	}
	if v := strings.TrimSpace(os.Getenv(envPairedVerbose)); v != "" {
		cfg.verbose = v != "0" && strings.ToLower(v) != "false"
	}
	return cfg
}

// pairedInstance wraps one half of the test pair and tracks rolling stats
// that the chaos loop uses to decide when to declare a wedge.
type pairedInstance struct {
	name string
	js   *Session

	mu              sync.Mutex
	receivedFromOther int64
	lastReceiveAt   time.Time
}

func (p *pairedInstance) note(b []byte) {
	if len(b) == 0 {
		return
	}
	p.mu.Lock()
	p.receivedFromOther++
	p.lastReceiveAt = time.Now()
	p.mu.Unlock()
}

func (p *pairedInstance) snapshot() int64 {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.receivedFromOther
}

// startInstance spins up one Session at a time so the second one is
// guaranteed to see the first as a peer (Jicofo session-initiate fires
// only when min-participants is reached).
func startInstance(ctx context.Context, t *testing.T, cfg *pairedConfig, name string) (*pairedInstance, error) {
	t.Helper()
	inst := &pairedInstance{name: name}

	sess, err := New(ctx, engine.Config{
		URL:    cfg.host,
		Extra:  map[string]string{credentialKeyRoom: cfg.room},
		Name:   name,
		OnData: inst.note,
	})
	if err != nil {
		return nil, fmt.Errorf("new %s: %w", name, err)
	}
	js, ok := sess.(*Session)
	if !ok {
		_ = sess.Close()
		return nil, fmt.Errorf("%s: cast to *Session failed: %w", name, errCastFailed)
	}
	js.SetShouldReconnect(func() bool { return ctx.Err() == nil })
	inst.js = js

	if err := sess.Connect(ctx); err != nil {
		_ = sess.Close()
		return nil, fmt.Errorf("connect %s: %w", name, err)
	}
	go js.WatchConnection(ctx)
	return inst, nil
}

// waitForBridge polls until the bridge is open on `inst` or the deadline
// passes. The bridge only opens after Jicofo issues session-initiate,
// which requires both participants to be in the room.
func waitForBridge(ctx context.Context, inst *pairedInstance, deadline time.Time) error {
	for time.Now().Before(deadline) {
		if inst.js.bridgeReady.Load() {
			return nil
		}
		select {
		case <-ctx.Done():
			return fmt.Errorf("%s: wait for bridge: %w", inst.name, ctx.Err())
		case <-time.After(time.Second):
		}
	}
	return fmt.Errorf("%s: bridge not ready before deadline: %w", inst.name, errBridgeTimeout)
}

// pumpLoop sends a small heartbeat payload from `from` to the other
// side every interval. The receive side uses inst.note to record arrival.
// We intentionally use the engine's Send (not SendTo) to exercise the
// peer-latch path.
func pumpLoop(ctx context.Context, t *testing.T, from *pairedInstance, interval time.Duration, payload []byte) {
	t.Helper()
	tick := time.NewTicker(interval)
	defer tick.Stop()
	seq := uint64(0)
	for {
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		}
		if !from.js.CanSend() {
			continue
		}
		seq++
		buf := append([]byte(nil), payload...)
		if len(buf) >= 8 {
			for i := range 8 {
				buf[i] = byte(seq >> (8 * i)) //nolint:gosec // intentional truncation to byte
			}
		}
		if err := from.js.Send(buf); err != nil {
			// Send may legitimately fail mid-reconnect; the chaos
			// supervisor will catch a permanent wedge.
			continue
		}
	}
}

type pairedStats struct {
	cycles            int64
	chaosKicks        int64
	wedgesPair        int64 // periods where neither side received any data
	startedAt         time.Time
	lastChaosAt       time.Time
	bothSidesReceived bool
}

// TestJitsiPairedChaosStress is the real chaos validator. It joins the
// same room with two engine instances, pumps data both ways, and then
// loops:
//
//   - idle wait > Prosody BOSH timeout (60s) and JVB inactivityTimeout
//   - forced teardownPC + requestReconnect on a randomly-chosen side
//   - confirm both sides recover (CanSend == true and we see a fresh
//     receive within a bounded window)
//
// Failure modes guarded:
//
//   - One side wedges (CanSend stuck false) past idle + chaosInterval.
//   - No application bytes flow across a chaos cycle (the recovery
//     never re-establishes the bridge frame path).
//   - Either side hits ErrSessionClosed at the engine level
//     (the closed flag is the canonical "we gave up" signal).
//
//nolint:cyclop,gocognit // chaos cycle structure naturally branches on phase + side
func TestJitsiPairedChaosStress(t *testing.T) {
	cfg := readPairedConfig(t)
	infinite := cfg.duration == 0

	t.Logf("[paired] host=%s room=%s duration=%s idle=%s chaos-interval=%s verbose=%v",
		cfg.host, cfg.room, cfg.durationLabel(), cfg.idle, cfg.chaosInterval, cfg.verbose)

	var (
		ctx    context.Context
		cancel context.CancelFunc
	)
	if infinite {
		ctx, cancel = context.WithCancel(context.Background())
	} else {
		ctx, cancel = context.WithTimeout(context.Background(), cfg.duration+5*time.Minute)
	}
	defer cancel()

	// Spin up Alice first so she's already in the room when Bob arrives —
	// this guarantees min-participants triggers session-initiate.
	alice, err := startInstance(ctx, t, cfg, nameAlice)
	if err != nil {
		t.Fatalf("alice: %v", err)
	}
	defer func() { _ = alice.js.Close() }()

	// Brief settle so Alice is fully in the MUC before Bob joins.
	time.Sleep(2 * time.Second)

	bob, err := startInstance(ctx, t, cfg, nameBob)
	if err != nil {
		t.Fatalf("bob: %v", err)
	}
	defer func() { _ = bob.js.Close() }()

	// Now Jicofo should issue session-initiate to both. Give it some time
	// to actually open the bridge on each side.
	bridgeBudget := time.Now().Add(90 * time.Second)
	if err := waitForBridge(ctx, alice, bridgeBudget); err != nil {
		t.Fatalf("alice bridge: %v", err)
	}
	if err := waitForBridge(ctx, bob, bridgeBudget); err != nil {
		t.Fatalf("bob bridge: %v", err)
	}
	t.Log("[paired] both bridges ready, starting pumps")

	// Background pumps: each side sends a heartbeat every 2s. The other
	// side records arrivals via OnData. This is the actual end-to-end
	// liveness signal — if it stops flowing, the bridge is dead.
	pumpCtx, pumpCancel := context.WithCancel(ctx)
	defer pumpCancel()
	payload := []byte("0123456789abcdef-paired-keepalive-stress-payload")
	go pumpLoop(pumpCtx, t, alice, 2*time.Second, payload)
	go pumpLoop(pumpCtx, t, bob, 2*time.Second, payload)

	// Wait for the first roundtrip in each direction so we know the
	// pumps are functional before chaos starts.
	if err := waitFirstReceive(ctx, alice, bob, 60*time.Second); err != nil {
		t.Fatalf("first roundtrip: %v", err)
	}
	t.Log("[paired] first bidirectional roundtrip OK, entering chaos loop")

	stats := pairedStats{startedAt: time.Now()}
	defer func() { reportPairedStats(t, &stats, cfg) }()
	stats.bothSidesReceived = true

	rng := rand.New(rand.NewSource(time.Now().UnixNano())) //nolint:gosec // test randomness only

	deadline := time.Time{}
	if !infinite {
		deadline = stats.startedAt.Add(cfg.duration)
	}
	chaosTick := time.NewTicker(cfg.chaosInterval)
	defer chaosTick.Stop()

	for {
		stats.cycles++

		if !infinite && time.Now().After(deadline) {
			t.Logf("[paired] cycle=%d budget exhausted, ending", stats.cycles)
			return
		}
		if ctx.Err() != nil {
			t.Logf("[paired] cycle=%d ctx ended (%v), ending", stats.cycles, ctx.Err())
			return
		}
		if alice.js.closed.Load() || bob.js.closed.Load() {
			t.Fatalf("session closed mid-stress: alice.closed=%v bob.closed=%v",
				alice.js.closed.Load(), bob.js.closed.Load())
		}

		// === Phase A: idle while pumps continue ===
		// Tests that neither BOSH nor JVB inactivityTimeout fires
		// while we're pumping at 2s intervals.
		if cfg.verbose {
			t.Logf("[paired][%d] idle observation %s", stats.cycles, cfg.idle)
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(cfg.idle):
		}

		// === Phase B: pick a victim and chaos ===
		victim := alice
		victimName := nameAlice
		if rng.Intn(2) == 1 {
			victim = bob
			victimName = nameBob
		}
		if cfg.verbose {
			t.Logf("[paired][%d] CHAOS victim=%s — teardownPC + requestReconnect", stats.cycles, victimName)
		}
		victim.js.teardownPC()
		victim.js.requestReconnect(fmt.Sprintf("paired chaos cycle=%d victim=%s", stats.cycles, victimName))
		stats.chaosKicks++
		stats.lastChaosAt = time.Now()

		// === Phase C: recovery deadline ===
		// Bound the recovery window. If the victim does not produce
		// a fresh receive on the survivor within recoveryBudget, the
		// engine has wedged.
		recoveryBudget := cfg.idle + 60*time.Second
		survivor := alice
		survivorName := nameAlice
		if victim == alice {
			survivor = bob
			survivorName = nameBob
		}
		if err := waitFreshReceive(ctx, survivor, recoveryBudget); err != nil {
			stats.wedgesPair++
			t.Errorf("[paired][%d] WEDGE survivor=%s did not receive after %s chaos on %s: %v",
				stats.cycles, survivorName, recoveryBudget, victimName, err)
			// Try to keep going; production behaviour is what we're
			// trying to capture, not a single hard failure.
			continue
		}
		if cfg.verbose {
			t.Logf("[paired][%d] recovered, survivor=%s saw fresh receive", stats.cycles, survivorName)
		}

		// Honour the chaos tick so we don't burn through cycles
		// faster than the configured cadence.
		select {
		case <-ctx.Done():
			return
		case <-chaosTick.C:
		}
	}
}

// waitFirstReceive blocks until each side has seen at least one OnData
// invocation. Returns ctx.Err() or a deadline error.
func waitFirstReceive(ctx context.Context, a, b *pairedInstance, budget time.Duration) error {
	deadline := time.Now().Add(budget)
	for time.Now().Before(deadline) {
		ac := a.snapshot()
		bc := b.snapshot()
		if ac > 0 && bc > 0 {
			return nil
		}
		select {
		case <-ctx.Done():
			return fmt.Errorf("wait first receive: %w", ctx.Err())
		case <-time.After(500 * time.Millisecond):
		}
	}
	ac := a.snapshot()
	bc := b.snapshot()
	return fmt.Errorf("did not see bidirectional roundtrip in %s (alice=%d bob=%d): %w",
		budget, ac, bc, errRoundtripTimeout)
}

// waitFreshReceive blocks until target sees a NEW receive after this call,
// i.e. the count strictly increases. This is how we observe that the
// bridge fully recovered: bytes are arriving from the (forced-to-reconnect)
// peer.
func waitFreshReceive(ctx context.Context, target *pairedInstance, budget time.Duration) error {
	startCount := target.snapshot()
	deadline := time.Now().Add(budget)
	for time.Now().Before(deadline) {
		c := target.snapshot()
		if c > startCount {
			return nil
		}
		select {
		case <-ctx.Done():
			return fmt.Errorf("wait fresh receive: %w", ctx.Err())
		case <-time.After(500 * time.Millisecond):
		}
	}
	return fmt.Errorf("no new receive in %s (count stuck at %d): %w", budget, startCount, errReceiveTimeout)
}

func reportPairedStats(t *testing.T, s *pairedStats, cfg *pairedConfig) {
	t.Helper()
	elapsed := time.Since(s.startedAt).Round(time.Second)
	t.Logf(
		"[paired] DONE elapsed=%s cycles=%d chaosKicks=%d wedges=%d duration=%s idle=%s",
		elapsed, s.cycles, s.chaosKicks, s.wedgesPair, cfg.durationLabel(), cfg.idle,
	)
	if s.wedgesPair > 0 {
		t.Errorf("observed %d pair wedges (recovery never produced new bytes)", s.wedgesPair)
	}
}
