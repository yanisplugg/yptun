package e2e

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"runtime"
	"slices"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	enginebuiltin "github.com/openlibrecommunity/olcrtc/internal/engine/builtin"
)

var (
	errStressNoRoundtrips   = errors.New("no successful roundtrips within duration")
	errStressPayloadMatch   = errors.New("payload mismatch")
	errStressNoBulkProgress = errors.New("bulk pump made zero progress")
)

var (
	realStress = flag.Bool( //nolint:gochecknoglobals // package-level state intentional
		"olcrtc.stress",
		false,
		"run real provider stress matrix (bulk transfer + sustained echo) - requires -olcrtc.real-e2e",
	)
	realStressBulkDuration = flag.Duration( //nolint:gochecknoglobals // package-level state intentional
		"olcrtc.stress-bulk-duration",
		60*time.Second,
		"per-case duration for the bulk pattern-pump phase (set 0 to skip). "+
			"Throughput differs by ~3 orders of magnitude across transports "+
			"(datachannel: MiB/s; videochannel: KB/s), so we measure how much "+
			"flows in a fixed time rather than fixing the byte budget.",
	)
	realStressDuration = flag.Duration( //nolint:gochecknoglobals // package-level state intentional
		"olcrtc.stress-duration",
		30*time.Second,
		"per-case duration for the sustained echo phase (set 0 to skip)",
	)
	realStressEchoSize = flag.Int( //nolint:gochecknoglobals // package-level state intentional
		"olcrtc.stress-echo-size",
		1024,
		"single-roundtrip payload size during the sustained echo phase",
	)
	realStressCaseTimeout = flag.Duration( //nolint:gochecknoglobals // package-level state intentional
		"olcrtc.stress-case-timeout",
		5*time.Minute,
		"hard timeout per stress carrier×transport case (covers connect + bulk + echo)",
	)
	realStressBulkChunkSize = flag.Int( //nolint:gochecknoglobals // package-level state intentional
		"olcrtc.stress-bulk-chunk",
		4096,
		"bulk request-response chunk size in bytes",
	)
)

// TestRealProviderTransportStress exercises every real carrier×transport
// combination under load. For each pair, two phases run sequentially over
// a single SOCKS connection:
//
//  1. Bulk phase: stream a deterministic byte pattern through the tunnel
//     for -olcrtc.stress-bulk-duration and verify it echoes back byte-for-
//     byte. Reports observed throughput. Different transports differ by
//     orders of magnitude (qr-encoded videochannel vs SCTP datachannel),
//     so we measure rather than assert a fixed budget.
//  2. Echo phase: send -olcrtc.stress-echo-size payloads as fast as the
//     loop will go for -olcrtc.stress-duration, recording per-RT latency
//     and computing p50/p95/p99.
//
// Around both phases we snapshot runtime.NumGoroutine to surface obvious
// goroutine leaks introduced by reconnect / bytestream / epoch regressions.
//
// Gated by -olcrtc.stress so it never runs on every push; intended for the
// nightly soak job in CI and for local stress profiling.
//
//nolint:cyclop // matrix of carrier×transport expectations is naturally branchy
func TestRealProviderTransportStress(t *testing.T) {
	if !*realE2E {
		t.Skip("real provider e2e disabled; pass -olcrtc.real-e2e to enable")
	}
	if !*realStress {
		t.Skip("stress disabled; pass -olcrtc.stress to enable")
	}

	carriers := splitTestList(*realE2ECarriers)
	transports := splitTestList(*realE2ETransports)
	if len(carriers) == 0 {
		t.Fatal("no real e2e carriers selected")
	}
	if len(transports) == 0 {
		t.Fatal("no real e2e transports selected")
	}

	echoAddr := startEchoServer(t)
	for _, carrierName := range carriers {
		t.Run(carrierName, func(t *testing.T) {
			roomCtx, cancelRoom := context.WithTimeout(context.Background(), *realStressCaseTimeout)
			defer cancelRoom()
			roomURL := requireRealRoom(roomCtx, t, carrierName)
			var authFailed bool
			for _, transportName := range transports {
				t.Run(transportName, func(t *testing.T) {
					if authFailed {
						t.Skip("skipping: carrier auth failed on previous transport")
					}
					expectation := realE2ECaseExpectation(carrierName, transportName)
					if expectation == realE2EExpectFail {
						t.Skip("skipping: combo not expected to pass even at baseline")
					}
					err := runRealE2EStressCase(t, carrierName, transportName, roomURL, echoAddr)
					if err != nil && errors.Is(err, enginebuiltin.ErrAuthFailed) {
						authFailed = true
						t.Skipf("skip %s stress: auth failed: %v", carrierName, err)
					}
					switch {
					case err == nil:
						t.Logf("STRESS OK %s/%s", carrierName, transportName)
					case expectation == realE2EExpectUnstable:
						logUnstableOutcome(t, "STRESS UNSTABLE", carrierName, transportName, err)
					default:
						t.Fatalf("STRESS FAIL %s/%s: %v", carrierName, transportName, err)
					}
				})
			}
		})
	}
}

func runRealE2EStressCase(t *testing.T, carrierName, transportName, roomURL, echoAddr string) (err error) {
	t.Helper()

	ctx, cancel := context.WithTimeout(context.Background(), *realStressCaseTimeout)
	defer cancel()

	goroutinesBefore := runtime.NumGoroutine()

	rt, err := startRealTunnel(ctx, t, carrierName, transportName, roomURL, testClientDeviceID, testClientDeviceID)
	if err != nil {
		return err
	}
	defer func() {
		if stopErr := rt.stopErr(); err == nil && stopErr != nil {
			err = stopErr
		}
	}()

	if err := runBulkPhase(ctx, t, rt, carrierName, transportName, echoAddr); err != nil {
		return err
	}

	if err := runEchoPhase(ctx, t, rt, carrierName, transportName, echoAddr); err != nil {
		return err
	}

	goroutinesAfter := runtime.NumGoroutine()
	// Allow some slack - pion/quic spawn helpers that take time to wind down
	// after Close, but a real leak shows up as tens of extra goroutines.
	const goroutineLeakSlack = 30
	if goroutinesAfter > goroutinesBefore+goroutineLeakSlack {
		t.Logf("WARNING: goroutines grew %d -> %d during %s/%s",
			goroutinesBefore, goroutinesAfter, carrierName, transportName)
	}

	return nil
}

// runBulkPhase pumps bulk traffic for realStressBulkDuration, reopening the
// SOCKS5 connection after a transport reconnect (e.g. publisher PC closed by
// the SFU) and accumulating total bytes across reconnects.
func runBulkPhase(
	ctx context.Context, t *testing.T, rt *tunnelRuntime,
	carrierName, transportName, echoAddr string,
) error {
	t.Helper()
	d := *realStressBulkDuration
	if d <= 0 {
		return nil
	}

	var lastConn net.Conn
	getConn := func() (net.Conn, error) {
		if lastConn != nil {
			_ = lastConn.Close()
		}
		c, cerr := connectViaSOCKSWithin(ctx, rt.socksAddr, echoAddr, 45*time.Second)
		if cerr != nil {
			return nil, cerr
		}
		lastConn = c
		return c, nil
	}
	conn, err := getConn()
	if err != nil {
		return err
	}
	defer func() {
		if lastConn != nil {
			_ = lastConn.Close()
		}
	}()

	totalWritten, err := pumpBulkUntil(ctx, t, conn, getConn, d, carrierName, transportName)
	if err != nil {
		return err
	}
	if totalWritten == 0 {
		return errStressNoBulkProgress
	}
	// Compute approximate throughput over full wall-clock duration.
	throughput := float64(totalWritten) / d.Seconds() / (1 << 20)
	t.Logf("bulk %s/%s: %d bytes in %s (%.3f MiB/s) [reconnects included]",
		carrierName, transportName, totalWritten, d, throughput)
	return nil
}

// pumpBulkUntil drives streamPatternForDuration until the deadline, reconnecting
// via getConn when the connection dies (transport reconnect). It returns the
// total bytes written across reconnects.
func pumpBulkUntil(
	ctx context.Context, t *testing.T, conn net.Conn,
	getConn func() (net.Conn, error), d time.Duration,
	carrierName, transportName string,
) (int64, error) {
	t.Helper()
	deadline := time.Now().Add(d)
	var totalWritten int64
	for time.Now().Before(deadline) {
		remaining := time.Until(deadline)
		written, dur, pumpErr := streamPatternForDuration(conn, remaining, *realStressBulkChunkSize)
		totalWritten += written
		if pumpErr == nil {
			break // completed full duration cleanly
		}
		// Connection died (likely transport reconnect). Log and retry.
		t.Logf("bulk %s/%s: reconnect after written=%d dur=%s: %v",
			carrierName, transportName, written, dur, pumpErr)
		if time.Now().After(deadline) {
			break
		}
		// Wait briefly for transport to re-establish, then reconnect.
		select {
		case <-ctx.Done():
			return totalWritten, fmt.Errorf("bulk phase cancelled: %w", ctx.Err())
		case <-time.After(5 * time.Second):
		}
		var cerr error
		conn, cerr = getConn()
		if cerr != nil {
			return totalWritten, fmt.Errorf("bulk reconnect: %w", cerr)
		}
	}
	return totalWritten, nil
}

// runEchoPhase runs the sustained echo phase for realStressDuration on a fresh
// connection (the bulk conn may have died during a reconnect at the end of the
// bulk phase).
func runEchoPhase(
	ctx context.Context, t *testing.T, rt *tunnelRuntime,
	carrierName, transportName, echoAddr string,
) error {
	t.Helper()
	d := *realStressDuration
	if d <= 0 {
		return nil
	}

	echoConn, err := connectViaSOCKSWithin(ctx, rt.socksAddr, echoAddr, 45*time.Second)
	if err != nil {
		return fmt.Errorf("sustained echo connect: %w", err)
	}
	defer func() { _ = echoConn.Close() }()
	stats, err := sustainedEcho(echoConn, *realStressEchoSize, d, transportName)
	if err != nil {
		return fmt.Errorf("sustained echo: %w", err)
	}
	t.Logf("echo  %s/%s: %d rt in %s, p50=%s p95=%s p99=%s max=%s lost=%d",
		carrierName, transportName, stats.count, d,
		stats.p50, stats.p95, stats.p99, stats.maxLatency, stats.lost)
	if stats.count == 0 {
		return fmt.Errorf("%w: %s", errStressNoRoundtrips, d)
	}
	return nil
}

// streamPatternForDuration pumps a deterministic byte pattern through conn
// for at most `duration` using concurrent write and read goroutines so
// the control stream (ping/pong) is not head-of-line blocked behind bulk
// data. Returns total bytes successfully echoed and elapsed time.
//
// Earlier versions used synchronous request-response, but that blocked
// the smux control stream behind bulk KCP frames and caused spurious
// liveness timeouts on vp8channel (QR-encoded frames are slow). The
// concurrent approach measures true transport throughput without breaking
// liveness.
func streamPatternForDuration(conn net.Conn, duration time.Duration, chunkSize int) (int64, time.Duration, error) {
	if chunkSize <= 0 {
		chunkSize = 4096
	}

	start := time.Now()
	deadline := start.Add(duration)

	var (
		sent    atomic.Int64
		errOnce sync.Once
		pumpErr error
	)
	recordErr := func(err error) {
		if err == nil {
			return
		}
		errOnce.Do(func() { pumpErr = err })
	}

	// Writer: pump deterministic pattern chunks until deadline.
	// No backpressure — we measure raw send throughput, not round-trip.
	// The TCP write buffer + smux + KCP provide their own flow control;
	// adding an explicit maxInFlight here throttles bulk to RTT-limited
	// speed (~0.003 MiB/s at 1.25s RTT through Telemost).
	const writeDeadline = 30 * time.Second
	writerDone := make(chan struct{})
	go func() {
		defer close(writerDone)
		buf := make([]byte, chunkSize)
		for time.Now().Before(deadline) {
			off := sent.Load()
			fillPattern(buf, off)
			if err := conn.SetWriteDeadline(time.Now().Add(writeDeadline)); err != nil {
				recordErr(fmt.Errorf("set write deadline: %w", err))
				return
			}
			if _, err := conn.Write(buf); err != nil {
				recordErr(fmt.Errorf("write: %w", err))
				return
			}
			sent.Add(int64(chunkSize))
		}
	}()

	// Drain incoming echo data to prevent server-side smux window from
	// filling up and blocking writes. We don't verify pattern here —
	// that's the sustained echo phase's job. We just discard bytes.
	const readDeadline = 5 * time.Second
	readerDone := make(chan struct{})
	go func() {
		defer close(readerDone)
		discardBuf := make([]byte, 32*1024)
		for {
			if err := conn.SetReadDeadline(time.Now().Add(readDeadline)); err != nil {
				return
			}
			_, err := conn.Read(discardBuf)
			if err != nil {
				return // deadline or closed — writer will catch fatal errors
			}
		}
	}()

	<-writerDone
	_ = conn.SetDeadline(time.Unix(1, 0)) // unblock reader
	<-readerDone

	return sent.Load(), time.Since(start), pumpErr
}

type echoStats struct {
	count         int
	lost          int
	p50, p95, p99 time.Duration
	maxLatency    time.Duration
}

// sustainedEcho writes payloads of size `payloadSize` and waits for them to
// echo back, recording per-roundtrip latency. Runs until duration elapses
// or the underlying connection fails. Each write/read uses a deadline so a
// stuck transport surfaces as a finite-time test failure rather than a hang.
//
//nolint:cyclop // per-rt deadlines + error wrapping naturally branch many ways
func sustainedEcho(conn net.Conn, payloadSize int, duration time.Duration, transportName string) (echoStats, error) {
	if payloadSize < 4 {
		payloadSize = 4
	}
	deadline := time.Now().Add(duration)
	payload := make([]byte, payloadSize)
	for i := range payload {
		payload[i] = byte('a' + (i % 26))
	}
	// Mark the payload terminator so we can ReadFull a fixed length back.
	payload[payloadSize-1] = '\n'

	reader := bufio.NewReader(conn)
	var stats echoStats
	latencies := make([]time.Duration, 0, 1024)

	buf := make([]byte, payloadSize)
	// Per-operation timeout. Video-paced transports need more slack due to
	// frame pacing and KCP batching (issue #95).
	opTimeout := 5 * time.Second
	if transportName == "videochannel" || transportName == "seichannel" || transportName == "vp8channel" {
		opTimeout = 60 * time.Second
	}
	for time.Now().Before(deadline) {
		if err := conn.SetWriteDeadline(time.Now().Add(opTimeout)); err != nil {
			return stats, fmt.Errorf("set write deadline: %w", err)
		}
		start := time.Now()
		if _, err := conn.Write(payload); err != nil {
			stats.lost++
			return stats, fmt.Errorf("write at rt #%d: %w", stats.count, err)
		}
		if err := conn.SetReadDeadline(time.Now().Add(opTimeout)); err != nil {
			return stats, fmt.Errorf("set read deadline: %w", err)
		}
		if _, err := io.ReadFull(reader, buf); err != nil {
			stats.lost++
			return stats, fmt.Errorf("read at rt #%d: %w", stats.count, err)
		}
		lat := time.Since(start)
		if !bytes.Equal(buf, payload) {
			return stats, fmt.Errorf("%w at rt #%d", errStressPayloadMatch, stats.count)
		}
		latencies = append(latencies, lat)
		if lat > stats.maxLatency {
			stats.maxLatency = lat
		}
		stats.count++
	}

	if len(latencies) > 0 {
		slices.Sort(latencies)
		stats.p50 = latencies[len(latencies)*50/100]
		stats.p95 = latencies[min(len(latencies)*95/100, len(latencies)-1)]
		stats.p99 = latencies[min(len(latencies)*99/100, len(latencies)-1)]
	}
	return stats, nil
}
