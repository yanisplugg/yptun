package gate

import "time"

// ai-generated: the whole file (every number the verdicts judge by).

// Thresholds are the numbers a target's verdicts judge by. They live in this
// file and nowhere else (spec section 6): change them here.
type Thresholds struct {
	// ConnectP95 bounds the 95th percentile of a connect through the tunnel,
	// idle or with bulk transfer in flight (the ack deadline is the same order).
	ConnectP95 time.Duration
	// ThroughputDownBps and ThroughputUpBps are aggregate floors for the
	// saturation scenarios: half of what the harness measured, so runner
	// variance does not fail a good build.
	ThroughputDownBps float64
	ThroughputUpBps   float64
	// HeapGrowthBytes and RSSGrowthBytes bound how far the live heap and
	// the RSS rise over S2-S4, with the mobile flavour under the phone's
	// memory limit and GC settings, above the baseline read before the
	// client started: what the client adds. The test process holds the
	// harness too, and what earlier pairs left, so its size is no measure
	// of the client's.
	HeapGrowthBytes float64
	RSSGrowthBytes  float64
	// GoroutineGrowth bounds goroutines 60 s after load versus idle.
	GoroutineGrowth int
	// ResolverAnswered is how many of 64 burst queries must be answered.
	ResolverAnswered int
}

// handshakeBudget bounds each of S6's handshakes on top of the delay its
// server's bridge opens with. It equals the engine's reply deadline
// (handshake.DefaultTimeout) but is written out, so a change to the engine
// cannot loosen the gate.
const handshakeBudget = 15 * time.Second

// ai-generated: connectBudget and its reasoning.
// connectBudget bounds S0's handshake_ms, which runs from the client's start
// to a working tunnel: MUC join or room auth, ICE, the bridge, then the hello.
// The engine's 15 s reply deadline starts only at the first hello, so it is
// not a bound on that span. What a user waits for is the app's ready wait,
// and the tightest is Android's (MOBILE_READY_TIMEOUT_MS, 25 s; iOS waits
// 35 s). On GitHub runners a public Jitsi relay put a healthy S0 at 15.8 s.
const connectBudget = 25 * time.Second

// The spec's memory bounds are for a process that runs the client alone: a
// peak of 16 MiB of live heap and 45 MiB of RSS. Such a process holds
// clientAloneHeap and clientAloneRSS before its client starts (a lean build
// that links mobile, after a GC, measured on 2026-09-18), so what a client
// may add is each bound less that.
const (
	clientAlonePeakHeap = 16 << 20
	clientAlonePeakRSS  = 45 << 20
	clientAloneHeap     = 7 << 19 // 3.5 MiB
	clientAloneRSS      = 26 << 20
)

// Local is the target with the server in the runner: Jitsi's relay carries
// ~5 Mbit/s, WB Stream and Telemost a few Mbit/s.
var Local = Thresholds{ //nolint:gochecknoglobals // the one place these numbers live
	ConnectP95:        5 * time.Second,
	ThroughputDownBps: 2_000_000,
	ThroughputUpBps:   2_000_000,
	HeapGrowthBytes:   clientAlonePeakHeap - clientAloneHeap,
	RSSGrowthBytes:    clientAlonePeakRSS - clientAloneRSS,
	GoroutineGrowth:   20,
	ResolverAnswered:  63,
}

// Link is the fleet's DE node through Telemost, measured at 1.4-1.6 Mbit/s
// down and ~3 Mbit/s up from a datacenter.
var Link = Thresholds{ //nolint:gochecknoglobals // the one place these numbers live
	ConnectP95:        5 * time.Second,
	ThroughputDownBps: 800_000,
	ThroughputUpBps:   1_500_000,
	HeapGrowthBytes:   clientAlonePeakHeap - clientAloneHeap,
	RSSGrowthBytes:    clientAlonePeakRSS - clientAloneRSS,
	GoroutineGrowth:   20,
	ResolverAnswered:  63,
}

// providerFloors are the throughput floors of a provider whose relay carries
// less than a target's floors allow for, set by the same rule: half of what
// was measured. SaluteJazz: every byte crosses Sber's TURN relay (Cloud.ru),
// measured at 2.79 Mbit/s down and 3.23 up from a datacenter by the carrier's
// phase-0 spike (2026-09-22).
var providerFloors = map[string]throughputFloors{ //nolint:gochecknoglobals // the one place these numbers live
	providerSaluteJazz: {Down: 1_400_000, Up: 1_600_000},
}

// throughputFloors are a provider's S2 and S3 floors, in bits per second.
type throughputFloors struct{ Down, Up float64 }

// For is what a pair of provider is judged by: t, with the provider's own
// throughput floors where they are lower. A provider's floor never raises a
// target's, whose own floors are its own measurement.
func (t Thresholds) For(provider string) Thresholds {
	if f, ok := providerFloors[provider]; ok {
		t.ThroughputDownBps = min(t.ThroughputDownBps, f.Down)
		t.ThroughputUpBps = min(t.ThroughputUpBps, f.Up)
	}
	return t
}

// Names in Map of the knobs that bound no single metric: a growth bounds the
// difference of two, and resolver_answered each burst's count. The others
// are named after the metric they bound, so a cell shows a bound next to its
// value.
const (
	thresholdHeapGrowth       = "heap_growth_bytes"
	thresholdRSSGrowth        = "rss_growth_bytes"
	thresholdGoroutineGrowth  = "goroutine_growth"
	thresholdResolverAnswered = "resolver_answered"
)

// Map is what a cell records next to its metrics, times in milliseconds as
// the metrics carry them.
func (t Thresholds) Map() map[string]float64 {
	return map[string]float64{
		MetricConnectP95Ms:        ms(t.ConnectP95),
		MetricThroughputDownBps:   t.ThroughputDownBps,
		MetricThroughputUpBps:     t.ThroughputUpBps,
		thresholdHeapGrowth:       t.HeapGrowthBytes,
		thresholdRSSGrowth:        t.RSSGrowthBytes,
		thresholdGoroutineGrowth:  float64(t.GoroutineGrowth),
		thresholdResolverAnswered: float64(t.ResolverAnswered),
	}
}

// ms is a duration in milliseconds, the unit the metrics carry time in.
func ms(d time.Duration) float64 { return float64(d.Milliseconds()) }
