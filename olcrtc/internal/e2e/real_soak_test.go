package e2e

import (
	"context"
	"errors"
	"flag"
	"testing"
	"time"

	enginebuiltin "github.com/openlibrecommunity/olcrtc/internal/engine/builtin"
)

// Real-carrier throughput soak: same pump as TestLocalThroughputSoak but
// over a real WebRTC provider (Jitsi, Telemost, WBStream).
//
// Quick start:
//
//	go test -count=1 -v ./internal/e2e \
//	    -run '^TestRealThroughputSoak$' \
//	    -olcrtc.real-e2e \
//	    -olcrtc.real-soak \
//	    -olcrtc.real-soak-carrier=jitsi \
//	    -olcrtc.real-soak-transport=seichannel \
//	    -olcrtc.real-soak-duration=30m \
//	    -timeout=60m

var (
	realSoakEnabled = flag.Bool( //nolint:gochecknoglobals // package-level state intentional
		"olcrtc.real-soak",
		false,
		"run TestRealThroughputSoak (long-running real-carrier throughput pump)",
	)
	realSoakDuration = flag.Duration( //nolint:gochecknoglobals // package-level state intentional
		"olcrtc.real-soak-duration",
		5*time.Minute,
		"how long to pump traffic per carrier×transport case",
	)
	realSoakCarrier = flag.String( //nolint:gochecknoglobals // package-level state intentional
		"olcrtc.real-soak-carrier",
		"jitsi",
		"carrier(s) to use: comma-separated list (e.g. jitsi,telemost,wbstream)",
	)
	realSoakTransport = flag.String( //nolint:gochecknoglobals // package-level state intentional
		"olcrtc.real-soak-transport",
		"seichannel",
		"transport(s): datachannel|videochannel|seichannel|vp8channel or comma-separated",
	)
	realSoakChunk = flag.Int( //nolint:gochecknoglobals // package-level state intentional
		"olcrtc.real-soak-chunk",
		4096,
		"write/read chunk size in bytes",
	)
	realSoakProgress = flag.Duration( //nolint:gochecknoglobals // package-level state intentional
		"olcrtc.real-soak-progress",
		30*time.Second,
		"how often to log throughput progress lines",
	)
	realSoakVerify = flag.Bool( //nolint:gochecknoglobals // package-level state intentional
		"olcrtc.real-soak-verify",
		true,
		"verify echoed bytes match the sent pattern",
	)
)

func TestRealThroughputSoak(t *testing.T) {
	if !*realE2E {
		t.Skip("real e2e disabled; pass -olcrtc.real-e2e to enable")
	}
	if !*realSoakEnabled {
		t.Skip("real soak disabled; pass -olcrtc.real-soak to enable")
	}
	if *realSoakDuration <= 0 {
		t.Skip("real soak duration is zero")
	}

	carriers := splitTestList(*realSoakCarrier)
	if len(carriers) == 0 {
		t.Fatal("no carriers specified in -olcrtc.real-soak-carrier")
	}
	transports, err := resolveLocalSoakTransports(*realSoakTransport)
	if err != nil {
		t.Fatalf("invalid -olcrtc.real-soak-transport=%q: %v", *realSoakTransport, err)
	}

	echoAddr := startEchoServer(t)

	for _, carrierName := range carriers {
		t.Run(carrierName, func(t *testing.T) {
			roomCtx, cancelRoom := context.WithTimeout(context.Background(), *realSoakDuration+2*time.Minute)
			defer cancelRoom()
			roomURL := requireRealRoom(roomCtx, t, carrierName)

			for _, transportName := range transports {
				t.Run(transportName, func(t *testing.T) {
					runRealSoakOnce(t, carrierName, transportName, roomURL, echoAddr)
				})
			}
		})
	}
}

func runRealSoakOnce(t *testing.T, carrierName, transportName, roomURL, echoAddr string) {
	t.Helper()

	const setupBudget = 90 * time.Second

	t.Logf("[soak] carrier=%s transport=%s duration=%s chunk=%d verify=%t progress=%s",
		carrierName, transportName, *realSoakDuration, *realSoakChunk,
		*realSoakVerify, *realSoakProgress)

	expectation := realE2ECaseExpectation(carrierName, transportName)

	ctx, cancel := context.WithTimeout(context.Background(), *realSoakDuration+setupBudget)
	defer cancel()

	rt, err := startRealTunnel(ctx, t, carrierName, transportName, roomURL, testClientDeviceID, testClientDeviceID)
	if err != nil {
		if errors.Is(err, enginebuiltin.ErrAuthFailed) {
			t.Skipf("auth failed (skip): %v", err)
		}
		if expectation == realE2EExpectUnstable || expectation == realE2EExpectFail {
			t.Skipf("start tunnel failed (expected %s): %v", realE2EExpectationLabel(expectation), err)
		}
		t.Fatalf("start tunnel: %v", err)
	}
	_ = rt

	conn, err := connectViaSOCKSWithin(ctx, rt.socksAddr, echoAddr, setupBudget)
	if err != nil {
		t.Fatalf("connect via SOCKS: %v", err)
	}
	defer func() { _ = conn.Close() }()

	pumpCtx, cancelPump := context.WithTimeout(context.Background(), *realSoakDuration)
	defer cancelPump()

	stats := runLocalSoakPump(pumpCtx, t, conn, *realSoakChunk, *realSoakVerify, *realSoakProgress)

	if stats.sent == 0 || stats.recv == 0 {
		t.Fatalf("no traffic moved: sent=%d recv=%d", stats.sent, stats.recv)
	}
	if stats.err != nil && !isExpectedShutdownErr(stats.err) {
		t.Fatalf("pump error: %v", stats.err)
	}

	t.Logf("[soak] DONE carrier=%s transport=%s elapsed=%s sent=%s recv=%s send=%s/s recv=%s/s",
		carrierName, transportName,
		stats.elapsed.Round(time.Second),
		humanBytes(stats.sent),
		humanBytes(stats.recv),
		humanBytes(int64(float64(stats.sent)/stats.elapsed.Seconds())),
		humanBytes(int64(float64(stats.recv)/stats.elapsed.Seconds())),
	)
}
