package gate

import (
	"context"
	"errors"
	"fmt"
	"net"
	"slices"
	"strconv"
	"time"
)

// ai-generated: the whole file (the scenarios S0-S7 of the spec's section 4
// and where each one runs).

var (
	// ErrNoUDP is S5 in a cell whose env has no UDP associate.
	ErrNoUDP = errors.New("no udp associate for the resolver burst")
	// ErrNoDelayedServer is S6 in a cell whose target cannot start a server
	// with a late bridge.
	ErrNoDelayedServer = errors.New("target cannot delay a server's bridge")
	// ErrNoSample is S7 finding no sample where it reads one: a scenario it
	// reads (S1, S2, S4) left no mark, or the sampler was not running.
	ErrNoSample = errors.New("no sample")
)

// quietAfterLoad is S4's idle window; tests shorten it.
var quietAfterLoad = 60 * time.Second //nolint:gochecknoglobals // tests shorten it

const (
	burstConcurrent = 24              // S1: connects at once
	burstSequential = 24              // S1: connects one after another
	pullsInFlight   = 6               // S2: big pulls at once
	pushesInFlight  = 4               // S3: pushes at once
	pushBytes       = 5 << 20         // S0 and S3: one push
	onTopEvery      = 5 * time.Second // S2 and S3: a connect on top of the load
	resolverBurst   = 64              // S5: queries in one burst
	resolverWait    = 5 * time.Second // S5: how long a burst's answers may take
	dnsAnswerBytes  = 4096            // S5: one datagram read, room for any answer
)

// Sampler marks S7 reads: the runner's, before the client starts, and those
// the scenarios leave.
const (
	markBaseline  = "baseline"        // the runner, before the client starts: the memory baseline
	markIdle      = "goroutines_idle" // S1, before its burst: the goroutine baseline
	markLoadStart = "S2-start"        // S2, before its pulls: the memory window opens
	markQuietEnd  = "S4-end"          // S4, after its quiet: 60 s after the load
)

// Engine log lines S4 counts, each written in one place (a test pins them).
// A reconnect logs its reason once and then a line per attempt, so the
// reason line is the one that counts reconnects; attempts alone are one that
// began before the cell (see reconnects).
const (
	logMissedPong       = "control missed pong"
	logReconnect        = "client reconnect reason="
	logReconnectAttempt = "client reconnect attempt="
	logConferenceEnd    = "Client link reported conference end"
)

func init() { //nolint:gochecknoinits // the registry is filled at load
	Register(Scenario{ID: "S0", Name: "connect", Applies: always, Run: runS0})
	Register(Scenario{ID: "S1", Name: "idle burst", Applies: loadPair, Run: runS1})
	Register(Scenario{ID: "S2", Name: "download saturation", Applies: loadPair, Run: runS2})
	Register(Scenario{ID: "S3", Name: "upload saturation", Applies: loadPair, Run: runS3})
	Register(Scenario{ID: "S4", Name: "quiet after load", Applies: loadPair, Run: runS4})
	Register(Scenario{ID: "S5", Name: "resolver burst", Applies: loadPair, Run: runS5})
	Register(Scenario{ID: "S6", Name: "late server bridge", Applies: lateBridge, Run: runS6})
	Register(Scenario{ID: "S7", Name: "phone memory", Applies: loadPair, Run: runS7})
}

// always runs a scenario on every pair with every flavour.
func always(Target, Pair, string) bool { return true }

// loadPair runs S1-S5 and S7: with the mobile flavour, on the link's one pair
// or on one pair per provider of any other target (spec section 4,
// amendment A1).
func loadPair(t Target, p Pair, client string) bool {
	if client != mobileFlavour {
		return false
	}
	if _, link := t.(*LinkTarget); link {
		return true
	}
	return slices.Contains([]Pair{
		{providerJitsi, transportData}, {providerTelemost, transportVP8}, {providerWBStream, transportVP8},
		{providerSaluteJazz, transportData}, {providerVKCalls, transportVP8}, {providerMTSLink, transportVP8},
		{providerMAX, transportData},
	}, p)
}

// lateBridge runs S6: on the local target, the one that can start a server
// with a late bridge, for Jitsi's datachannel, with both flavours.
func lateBridge(t Target, p Pair, _ string) bool {
	_, local := t.(*LocalTarget)
	return local && p == Pair{providerJitsi, transportData}
}

// runS0 is the connect cell: the tunnel came up, the runner timed how long
// that took, and one big pull and one push go through it.
func runS0(ctx context.Context, env *Env) (Metrics, error) {
	m := Metrics{}
	if env.handshake > 0 {
		m[MetricHandshakeMs] = ms(env.handshake)
	}
	_, err := Pull(ctx, env.HTTP, env.Load.Big, env.Load.BigBytes)
	m[MetricPullOK] = step(env, "S0 pull", err)
	m[MetricPushOK] = step(env, "S0 push", Push(ctx, env.HTTP, env.Load.Sink, pushBytes))
	return m, nil
}

// step is a step's flag metric, 1 when it worked. A failure is logged: the
// flag alone says only that it failed.
func step(env *Env, what string, err error) float64 {
	if err != nil {
		env.Logf("%s: %v", what, err)
		return 0
	}
	return 1
}

// runS1 is the idle burst: connects on a tunnel that carries nothing else.
// Its mark is S7's goroutine baseline.
func runS1(ctx context.Context, env *Env) (Metrics, error) {
	env.Sampler.Mark(markIdle)
	out := ConnectBurst(ctx, env.HTTP, env.Load.Small, burstConcurrent, burstSequential)
	return Metrics{
		MetricConnectOK: float64(out.OK), MetricConnectTotal: float64(out.Total), MetricConnectP95Ms: ms(out.P95),
	}, nil
}

// runS2 is download saturation (olcbox#23): big pulls at once, a connect on
// top every onTopEvery. Its mark opens S7's memory window.
func runS2(ctx context.Context, env *Env) (Metrics, error) {
	env.Sampler.Mark(markLoadStart)
	out, top := underLoad(ctx, env, "S2 pull", pullsInFlight, func(ctx context.Context) (int64, error) {
		return Pull(ctx, env.HTTP, env.Load.Big, env.Load.BigBytes)
	})
	m := onTopMetrics(top)
	m[MetricPullOK], m[MetricPullTotal] = float64(out.OK), float64(out.Total)
	m[MetricThroughputDownBps] = bps(out.Bytes, out.Took)
	return m, nil
}

// runS3 is upload saturation (olcbox#15): pushes at once, connects on top as
// in S2.
func runS3(ctx context.Context, env *Env) (Metrics, error) {
	out, top := underLoad(ctx, env, "S3 push", pushesInFlight, func(ctx context.Context) (int64, error) {
		if err := Push(ctx, env.HTTP, env.Load.Sink, pushBytes); err != nil {
			return 0, err
		}
		return pushBytes, nil
	})
	m := onTopMetrics(top)
	m[MetricPushOK], m[MetricPushTotal] = float64(out.OK), float64(out.Total)
	m[MetricThroughputUpBps] = bps(out.Bytes, out.Took)
	return m, nil
}

// underLoad runs n transfers at once with connects on top of them and returns
// what the transfers and the connects came to. A transfer that failed is
// logged as what and its number.
func underLoad(
	ctx context.Context, env *Env, what string, n int, transfer func(ctx context.Context) (int64, error),
) (Outcome, Outcome) {
	stop := OnTop(ctx, env.HTTP, env.Load.Small, onTopEvery)
	out := Parallel(ctx, n, func(ctx context.Context, i int) (int64, error) {
		b, err := transfer(ctx)
		if err != nil {
			env.Logf("%s %d: %v", what, i+1, err)
		}
		return b, err
	})
	return out, stop()
}

// onTopMetrics are the connects on top of a load, S2's and S3's alike.
func onTopMetrics(top Outcome) Metrics {
	return Metrics{MetricOnTopOK: float64(top.OK), MetricOnTopTotal: float64(top.Total), MetricOnTopP95Ms: ms(top.P95)}
}

// runS4 is the quiet after the load (olcbox#25): the tunnel idles for
// quietAfterLoad, must come out of it with no missed pong, no reconnect and
// its conference alive, and must still carry a small pull. The runner begins
// a fresh log for every cell, so everything the log holds is the quiet's.
// Its mark, 60 s after the load, closes S7's memory window.
func runS4(ctx context.Context, env *Env) (Metrics, error) {
	select {
	case <-time.After(quietAfterLoad):
	case <-ctx.Done():
		return nil, fmt.Errorf("S4 quiet: %w", ctx.Err())
	}
	env.Sampler.Mark(markQuietEnd)
	m := Metrics{
		MetricMissedPong: float64(env.Log.Count(logMissedPong)),
		MetricReconnects: float64(reconnects(env.Log)),
		MetricAlive:      1,
	}
	if env.Log.Count(logConferenceEnd) > 0 {
		m[MetricAlive] = 0
	}
	_, err := Pull(ctx, env.HTTP, env.Load.Small, smallBytes)
	m[MetricFinalPullOK] = step(env, "S4 final pull", err)
	return m, nil
}

// reconnects is how many reconnects l holds: one per reason line, or one
// for attempts with no reason line, a reconnect that began in the cell
// before and ran on into this one. A death at the end of the load logs its
// reason in S3's cell, and the liveness fallback's attempts come 30 s later.
func reconnects(l *CellLog) int {
	return max(l.Count(logReconnect), min(1, l.Count(logReconnectAttempt)))
}

// runS5 is the resolver burst (the stream path, aac553b8): resolverBurst
// queries at once to a public resolver through a UDP associate, twice, each
// burst on an association of its own.
func runS5(ctx context.Context, env *Env) (Metrics, error) {
	if env.UDP == nil {
		return nil, ErrNoUDP
	}
	m := Metrics{}
	for i, key := range []string{MetricAnswered1, MetricAnswered2} {
		answered, err := resolverBurstRound(ctx, env)
		if err != nil {
			return m, fmt.Errorf("S5 burst %d: %w", i+1, err)
		}
		m[key] = float64(answered)
	}
	return m, nil
}

// resolverBurstRound sends one burst on a fresh association and counts the
// distinct ids answered within resolverWait.
func resolverBurstRound(ctx context.Context, env *Env) (int, error) {
	assoc, err := env.UDP(ctx)
	if err != nil {
		return 0, fmt.Errorf("udp associate: %w", err)
	}
	defer assoc.Close()
	resolver := &net.UDPAddr{IP: net.IPv4(8, 8, 8, 8), Port: 53}
	for id := uint16(1); id <= resolverBurst; id++ {
		query := BuildDNSQuery(id, "gate"+strconv.Itoa(int(id))+".example.com")
		if err := assoc.Send(resolver, query); err != nil {
			return 0, err
		}
	}
	seen := make(map[uint16]bool, resolverBurst)
	deadline := time.Now().Add(resolverWait)
	buf := make([]byte, dnsAnswerBytes)
	for len(seen) < resolverBurst {
		payload, _, err := assoc.Recv(buf, deadline)
		if errors.Is(err, ErrSocksReply) {
			continue // a header the association cannot read: no answer, skip it
		}
		if err != nil {
			break // the deadline: what came is what was answered
		}
		if id, ok := DNSResponseID(payload); ok && id >= 1 && id <= resolverBurst {
			seen[id] = true
		}
	}
	return len(seen), nil
}

// runS6 is the late server bridge (olcbox#22): a fresh server whose bridge
// opens 3 s and then 8 s late, and how long the client takes to be ready
// against each. The hello resend is what makes it pass; 0 is never ready.
func runS6(ctx context.Context, env *Env) (Metrics, error) {
	if env.Delayed == nil {
		return nil, ErrNoDelayedServer
	}
	return Metrics{
		MetricReady3sMs: readyAfterLateBridge(ctx, env, 3*time.Second),
		MetricReady8sMs: readyAfterLateBridge(ctx, env, 8*time.Second),
	}, nil
}

// readyAfterLateBridge opens a server whose bridge opens delay late and times
// the client's start against it, in milliseconds. It is 0 when either side
// failed, and the log says which.
func readyAfterLateBridge(ctx context.Context, env *Env, delay time.Duration) float64 {
	ep, stopServer, err := env.Delayed(ctx, OpenOptions{BridgeDelay: delay})
	if err != nil {
		env.Logf("S6 server with the bridge %s late: %v", delay, err)
		return 0
	}
	defer stopServer()
	start := time.Now()
	tun, err := env.Client.Start(ctx, ep)
	if err != nil {
		env.Logf("S6 client with the bridge %s late: %v", delay, err)
		return 0
	}
	ready := time.Since(start)
	tun.Stop()
	return ms(ready)
}

// runS7 is the phone's memory (olcbox#24, #26) over what S2-S4 ran: the peak
// heap and RSS from S2's start to S4's end and the baseline read before the
// client started, whose difference the verdict judges, and the goroutines
// at S1's mark against those at S4's end, 60 s after the load. S5 and S6 run
// after S4 and before S7, and what they still hold is not what S7 judges.
// Beside them it counts the heap profiles written over the window, whose
// garbage the peak may carry.
func runS7(_ context.Context, env *Env) (Metrics, error) {
	heap, rss, ok := env.Sampler.PeakBetween(markLoadStart, markQuietEnd)
	if !ok {
		return nil, fmt.Errorf("%w between %s and %s", ErrNoSample, markLoadStart, markQuietEnd)
	}
	// ai-generated: the baseline the memory growth is judged over.
	base, ok := env.Sampler.sampleAt(markBaseline)
	if !ok {
		return nil, fmt.Errorf("%w at %s", ErrNoSample, markBaseline)
	}
	idle, ok := env.Sampler.sampleAt(markIdle)
	if !ok {
		return nil, fmt.Errorf("%w at %s", ErrNoSample, markIdle)
	}
	after, ok := env.Sampler.sampleAt(markQuietEnd)
	if !ok {
		return nil, fmt.Errorf("%w at %s", ErrNoSample, markQuietEnd)
	}
	return Metrics{
		MetricHeapBaselineBytes: float64(base.HeapInuse), MetricRSSBaselineBytes: float64(base.RSS),
		MetricHeapPeakBytes: float64(heap), MetricRSSPeakBytes: float64(rss),
		MetricGoroutinesIdle: float64(idle.Goroutines), MetricGoroutinesAfter: float64(after.Goroutines),
		// ai-generated: the jump profiles whose garbage the peak may carry.
		MetricHeapProfilesInWindow: float64(env.Sampler.ProfilesBetween(markLoadStart, markQuietEnd)),
	}, nil
}
