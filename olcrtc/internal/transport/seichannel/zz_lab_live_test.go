//go:build seilab && linux

package seichannel

// ai-generated: the whole file (the live lab behind the measurements in
// olcrtc#9 and olcrtc#16).
//
// A bench for seichannel against a real relay: it opens both ends of a
// tunnel in one process, so a run measures the transport rather than the
// tunnel above it, and both ends read the same clock, so a one-way delay is
// a real number. Nothing here runs in a normal build: it needs the seilab
// tag and SEILAB_PROVIDER, and it talks to a relay.
//
//	go test -tags seilab -c -o /tmp/seilab.test ./internal/transport/seichannel
//	SEILAB_PROVIDER=jitsi SEILAB_JITSI_HOST=<instance> SEILAB_MODE=send \
//	  SEILAB_SECS=45 \
//	  /tmp/seilab.test -test.run TestSEILab -test.v -test.timeout 10m
//
//	set -a; . ~/rooms.env; set +a     # your own room, never a shared one
//	SEILAB_PROVIDER=wbstream SEILAB_ROOM="$MY_ROOM" SEILAB_TOKEN="$MY_TOKEN" \
//	  SEILAB_MODE=raw SEILAB_RATES=100,400,1600 SEILAB_DIR=c2s /tmp/seilab.test ...
//
// The modes:
//
//	send      the real Send path, one direction, throughput and latency
//	bidi      send in both directions at once
//	raw       frames queued at a fixed rate, bypassing the ack wait: what
//	          the relay itself forwards, and what it drops
//	reconnect data both ways, a provider reconnect, data both ways again
//
// The knobs: SEILAB_PROVIDER (jitsi, wbstream), SEILAB_ROOM and SEILAB_TOKEN
// (a provider that needs them), SEILAB_JITSI_HOST (a reachable instance,
// see docs/jitsi.instances.yaml), SEILAB_MODE, SEILAB_DIR
// (s2c, c2s), SEILAB_SECS, SEILAB_MSG, SEILAB_WORKERS, SEILAB_RATES (KB/s),
// SEILAB_STEP, SEILAB_FPS, SEILAB_BATCH, SEILAB_FRAG, SEILAB_ACKMS,
// SEILAB_WHO (cli, srv, both), SEILAB_VERBOSE.
//
// A room id and a token are secrets: they come from the environment, they
// are never written here, and everything the run prints has them replaced.
// It is Linux-only because that is how it redirects what it prints.

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"hash/crc32"
	"log"
	"os"
	"slices"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	"github.com/pion/webrtc/v4"

	enginebuiltin "github.com/openlibrecommunity/olcrtc/internal/engine/builtin"
	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
	"github.com/openlibrecommunity/olcrtc/internal/transport/common"
)

func labEnv(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func labInt(k string, def int) int {
	v, err := strconv.Atoi(labEnv(k, ""))
	if err != nil {
		return def
	}
	return v
}

// scrubStd replaces secrets in everything written to fd 1 and 2.
func scrubStd(t *testing.T, secrets []string) func() {
	t.Helper()
	var subs []string
	for _, s := range secrets {
		if s != "" {
			subs = append(subs, s, "<secret>")
		}
	}
	rep := strings.NewReplacer(subs...)
	orig, err := syscall.Dup(2)
	if err != nil {
		t.Fatal(err)
	}
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	_ = syscall.Dup3(int(w.Fd()), 1, 0)
	_ = syscall.Dup3(int(w.Fd()), 2, 0)
	out := os.NewFile(uintptr(orig), "orig-stderr")
	log.SetOutput(os.Stderr)
	done := make(chan struct{})
	go func() {
		defer close(done)
		sc := bufio.NewScanner(r)
		sc.Buffer(make([]byte, 1<<20), 1<<22)
		for sc.Scan() {
			_, _ = out.WriteString(rep.Replace(sc.Text()) + "\n")
		}
	}()
	return func() {}
}

type labStats struct {
	rtpIn     atomic.Uint64
	rtpGaps   atomic.Uint64
	samples   atomic.Uint64
	framesIn  atomic.Uint64
	dataIn    atomic.Uint64
	acksIn    atomic.Uint64
	helloIn   atomic.Uint64
	bytesIn   atomic.Uint64
	msgsIn    atomic.Uint64
	lastSeqIn atomic.Uint64

	mu   sync.Mutex
	lats []time.Duration
	seen map[uint64]bool
}

func (s *labStats) record(seq uint64, lat time.Duration) {
	s.mu.Lock()
	s.lats = append(s.lats, lat)
	if s.seen == nil {
		s.seen = map[uint64]bool{}
	}
	s.seen[seq] = true
	s.mu.Unlock()
}

func (s *labStats) takeLats() []time.Duration {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := s.lats
	s.lats = nil
	return out
}

func pct(l []time.Duration, p float64) time.Duration {
	if len(l) == 0 {
		return 0
	}
	slices.Sort(l)
	i := int(float64(len(l)-1) * p)
	return l[i]
}

type labSide struct {
	name  string
	tr    *streamTransport
	stats *labStats
}

// labTrackHandler is handleRemoteTrack with counters.
func (s *labSide) labTrackHandler(track *webrtc.TrackRemote, _ *webrtc.RTPReceiver) {
	log.Printf("lab[%s]: remote track ssrc=%d codec=%s", s.name, track.SSRC(), track.Codec().MimeType)
	go func() {
		var (
			reader   packetReader
			payloads [][]byte
			last     uint16
		)
		first := true
		for {
			packet, _, err := track.ReadRTP()
			if err != nil {
				log.Printf("lab[%s]: track ended: %v", s.name, err)
				return
			}
			s.stats.rtpIn.Add(1)
			if !first && packet.SequenceNumber != last+1 {
				s.stats.rtpGaps.Add(uint64(packet.SequenceNumber - last - 1))
			}
			first, last = false, packet.SequenceNumber
			payloads = reader.payloads(packet, payloads[:0])
			s.stats.samples.Add(1)
			for _, payload := range payloads {
				frame, err := common.DecodeFrame(payload)
				if err != nil || !s.tr.acceptFrame(frame) {
					continue
				}
				s.stats.framesIn.Add(1)
				switch frame.Type {
				case common.FrameTypeHello:
					s.stats.helloIn.Add(1)
				case common.FrameTypeAck:
					s.stats.acksIn.Add(1)
				case common.FrameTypeData, common.FrameTypeStream:
					s.stats.dataIn.Add(1)
				}
				s.tr.handlePayload(payload)
			}
		}
	}()
}

func labOpen(ctx context.Context, t *testing.T, name, provider, room, channel, deviceID, token string, opts Options) *labSide {
	t.Helper()
	side := &labSide{name: name, stats: &labStats{}}
	cfg := transport.Config{
		Provider:      provider,
		RoomURL:       room,
		ChannelID:     channel,
		DeviceID:      deviceID,
		Name:          "Lab-" + name,
		ProviderToken: token,
		DNSServer:     "8.8.8.8:53",
		Options:       opts,
		OnData: func(b []byte) {
			if len(b) < 16 {
				return
			}
			side.stats.msgsIn.Add(1)
			side.stats.bytesIn.Add(uint64(len(b)))
			seq := binary.BigEndian.Uint64(b[0:8])
			sent := int64(binary.BigEndian.Uint64(b[8:16])) //nolint:gosec // a timestamp this process wrote
			side.stats.lastSeqIn.Store(seq)
			side.stats.record(seq, time.Duration(time.Now().UnixNano()-sent))
		},
	}
	trIface, err := New(ctx, cfg)
	if err != nil {
		t.Fatalf("%s: new: %v", name, err)
	}
	side.tr = trIface.(*streamTransport)
	side.tr.stream.SetTrackHandler(side.labTrackHandler)
	side.tr.SetShouldReconnect(func() bool { return ctx.Err() == nil })
	side.tr.SetReconnectCallback(func() { log.Printf("lab[%s]: provider reconnected", name) })
	side.tr.SetEndedCallback(func(r string) { log.Printf("lab[%s]: ended: %s", name, r) })
	if err := side.tr.Connect(ctx); err != nil {
		t.Fatalf("%s: connect: %v", name, err)
	}
	go side.tr.WatchConnection(ctx)
	return side
}

func labPayload(seq uint64, size int) []byte {
	b := make([]byte, size)
	_, _ = rand.Read(b)
	binary.BigEndian.PutUint64(b[0:8], seq)
	binary.BigEndian.PutUint64(b[8:16], uint64(time.Now().UnixNano()))
	return b
}

// TestSEILab runs the lab. Env: SEILAB_PROVIDER jitsi|wbstream, SEILAB_MODE
// raw|send, SEILAB_DIR s2c|c2s, SEILAB_RATES (KB/s, raw), SEILAB_STEP (s),
// SEILAB_FRAG, SEILAB_FPS, SEILAB_BATCH, SEILAB_MSG (send), SEILAB_SECS (send).
func TestSEILab(t *testing.T) {
	provider := os.Getenv("SEILAB_PROVIDER")
	if provider == "" {
		t.Skip("SEILAB_PROVIDER unset")
	}
	enginebuiltin.RegisterDefaults()
	logger.SetVerbose(os.Getenv("SEILAB_VERBOSE") != "")

	var room, token string
	var secrets []string
	switch provider {
	case "jitsi":
		var b [6]byte
		_, _ = rand.Read(b[:])
		host := os.Getenv("SEILAB_JITSI_HOST")
		if host == "" {
			t.Fatal("jitsi needs SEILAB_JITSI_HOST: a reachable instance, see docs/jitsi.instances.yaml")
		}
		room = "https://" + host + "/sei-lab-" + hex.EncodeToString(b[:])
	default:
		room = os.Getenv("SEILAB_ROOM")
		token = os.Getenv("SEILAB_TOKEN")
		if room == "" {
			t.Fatalf("%s needs SEILAB_ROOM (and SEILAB_TOKEN where the server signs in)", provider)
		}
		secrets = append(secrets, room, token)
	}
	scrubStd(t, secrets)

	var cb [6]byte
	_, _ = rand.Read(cb[:])
	channel := "lab-" + hex.EncodeToString(cb[:])
	opts := Options{
		FPS:          labInt("SEILAB_FPS", 60),
		BatchSize:    labInt("SEILAB_BATCH", 64),
		FragmentSize: labInt("SEILAB_FRAG", 900),
		AckTimeoutMS: labInt("SEILAB_ACKMS", 2000),
	}
	log.Printf("lab: provider=%s opts=%+v", provider, opts)

	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Minute)
	defer cancel()

	srv := labOpen(ctx, t, "srv", provider, room, channel, "", token, opts)
	defer func() { _ = srv.tr.Close() }()
	time.Sleep(2 * time.Second)
	cli := labOpen(ctx, t, "cli", provider, room, channel, "lab-cli", "", opts)
	defer func() { _ = cli.tr.Close() }()

	start := time.Now()
	for !srv.tr.CanSend() || !cli.tr.CanSend() {
		if time.Since(start) > 90*time.Second {
			t.Fatalf("not ready: srv=%v cli=%v", srv.tr.CanSend(), cli.tr.CanSend())
		}
		time.Sleep(100 * time.Millisecond)
	}
	log.Printf("lab: both ready after %v", time.Since(start).Round(time.Millisecond))
	time.Sleep(2 * time.Second)

	from, to := srv, cli
	if labEnv("SEILAB_DIR", "s2c") == "c2s" {
		from, to = cli, srv
	}
	switch labEnv("SEILAB_MODE", "raw") {
	case "raw":
		labRaw(ctx, t, from, to, opts)
	case "send":
		labSend(ctx, t, from, to)
	case "bidi":
		labBidi(ctx, t, srv, cli)
	case "reconnect":
		labReconnect(ctx, t, srv, cli)
	}
}

func labReport(tag string, from, to *labSide, sentFrames, sentBytes uint64, took time.Duration) {
	lats := to.stats.takeLats()
	log.Printf("%s sent=%d frames %.1f KB/s | recv msgs=%d %.1f KB/s rtpIn=%d rtpGaps=%d samples=%d data=%d acks(back at sender)=%d hello=%d | lat p50=%v p90=%v max=%v",
		tag, sentFrames, float64(sentBytes)/took.Seconds()/1024,
		to.stats.msgsIn.Load(), float64(to.stats.bytesIn.Load())/took.Seconds()/1024,
		to.stats.rtpIn.Load(), to.stats.rtpGaps.Load(), to.stats.samples.Load(), to.stats.dataIn.Load(),
		from.stats.acksIn.Load(), to.stats.helloIn.Load(),
		pct(lats, 0.5).Round(time.Millisecond), pct(lats, 0.9).Round(time.Millisecond), pct(lats, 1).Round(time.Millisecond))
}

func resetStats(s *labSide) {
	st := s.stats
	st.rtpIn.Store(0)
	st.rtpGaps.Store(0)
	st.samples.Store(0)
	st.framesIn.Store(0)
	st.dataIn.Store(0)
	st.acksIn.Store(0)
	st.helloIn.Store(0)
	st.bytesIn.Store(0)
	st.msgsIn.Store(0)
	_ = st.takeLats()
}

// labRaw enqueues single-fragment data frames at fixed rates, bypassing the
// sender's ack wait, and reports what arrives.
func labRaw(ctx context.Context, t *testing.T, from, to *labSide, opts Options) {
	t.Helper()
	var rates []int
	for _, r := range strings.Split(labEnv("SEILAB_RATES", "25,50,100,200,400"), ",") {
		v, _ := strconv.Atoi(strings.TrimSpace(r))
		if v > 0 {
			rates = append(rates, v)
		}
	}
	step := time.Duration(labInt("SEILAB_STEP", 15)) * time.Second
	frag := opts.FragmentSize
	seq := uint64(1 << 31)
	for _, rate := range rates {
		resetStats(from)
		resetStats(to)
		fps := float64(rate*1024) / float64(frag)
		interval := time.Duration(float64(time.Second) / fps)
		log.Printf("lab raw: rate=%d KB/s frag=%d -> %.0f frames/s", rate, frag, fps)
		stepStart := time.Now()
		var sentFrames, sentBytes uint64
		next := time.Now()
		secStart := time.Now()
		var secFrames, secBytes uint64
		for time.Since(stepStart) < step && ctx.Err() == nil {
			now := time.Now()
			for !next.After(now) {
				seq++
				payload := labPayload(seq, frag)
				f := common.EncodeData(common.LocalRole(roleDevice(from)), from.tr.bindingToken,
					uint32(seq), crc32.ChecksumIEEE(payload), len(payload), 0, 1, payload)
				_ = from.tr.queue.Enqueue(f, false)
				sentFrames++
				sentBytes += uint64(len(payload))
				secFrames++
				secBytes += uint64(len(payload))
				next = next.Add(interval)
			}
			time.Sleep(time.Millisecond)
			if time.Since(secStart) >= 5*time.Second {
				labReport(fmt.Sprintf("lab raw %dKB/s +%.0fs", rate, time.Since(stepStart).Seconds()), from, to, secFrames, secBytes, time.Since(secStart))
				secStart = time.Now()
				secFrames, secBytes = 0, 0
				resetStats(to)
				from.stats.acksIn.Store(0)
			}
		}
		time.Sleep(time.Second)
	}
}

func roleDevice(s *labSide) string {
	if s.name == "srv" {
		return ""
	}
	return "lab-cli"
}

// labSend pushes messages through the real Send path.
func labSend(ctx context.Context, t *testing.T, from, to *labSide) {
	t.Helper()
	size := labInt("SEILAB_MSG", from.tr.Features().MaxPayloadSize)
	secs := labInt("SEILAB_SECS", 60)
	workers := labInt("SEILAB_WORKERS", 1)
	log.Printf("lab send: msg=%d secs=%d workers=%d", size, secs, workers)
	var seq atomic.Uint64
	var sent atomic.Uint64
	var errs atomic.Uint64
	var mu sync.Mutex
	var sendLats []time.Duration
	deadline := time.Now().Add(time.Duration(secs) * time.Second)
	var wg sync.WaitGroup
	for range workers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for time.Now().Before(deadline) && ctx.Err() == nil {
				p := labPayload(seq.Add(1), size)
				t0 := time.Now()
				if err := from.tr.Send(p); err != nil {
					errs.Add(1)
					log.Printf("lab send: error after %v: %v", time.Since(t0).Round(time.Millisecond), err)
					time.Sleep(100 * time.Millisecond)
					continue
				}
				d := time.Since(t0)
				sent.Add(uint64(size)) //nolint:gosec // a positive size
				mu.Lock()
				sendLats = append(sendLats, d)
				mu.Unlock()
			}
		}()
	}
	tick := time.NewTicker(5 * time.Second)
	defer tick.Stop()
	start := time.Now()
	last := time.Now()
	var lastRecv uint64
	for time.Now().Before(deadline.Add(2 * time.Second)) {
		<-tick.C
		mu.Lock()
		l := sendLats
		sendLats = nil
		mu.Unlock()
		recv := to.stats.bytesIn.Load()
		lats := to.stats.takeLats()
		log.Printf("lab send +%.0fs: recv %.1f KB/s total=%d sendLat p50=%v p90=%v max=%v n=%d | deliverLat p50=%v p90=%v | rtpIn=%d gaps=%d errs=%d",
			time.Since(start).Seconds(), float64(recv-lastRecv)/time.Since(last).Seconds()/1024, recv,
			pct(l, 0.5).Round(time.Millisecond), pct(l, 0.9).Round(time.Millisecond), pct(l, 1).Round(time.Millisecond), len(l),
			pct(lats, 0.5).Round(time.Millisecond), pct(lats, 0.9).Round(time.Millisecond),
			to.stats.rtpIn.Load(), to.stats.rtpGaps.Load(), errs.Load())
		last, lastRecv = time.Now(), recv
	}
	wg.Wait()
	log.Printf("lab send: total sent=%d recv=%d in %v", sent.Load(), to.stats.bytesIn.Load(), time.Since(start).Round(time.Second))
}

// labBidi runs send in both directions at once.
func labBidi(ctx context.Context, t *testing.T, srv, cli *labSide) {
	t.Helper()
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); labSend(ctx, t, srv, cli) }()
	go func() { defer wg.Done(); labSend(ctx, t, cli, srv) }()
	wg.Wait()
}

// labReconnect checks data both ways, rejoins one side (SEILAB_WHO=cli|srv|both)
// and checks again.
func labReconnect(_ context.Context, t *testing.T, srv, cli *labSide) {
	t.Helper()
	probe := func(tag string) {
		for _, d := range [][2]*labSide{{srv, cli}, {cli, srv}} {
			from, to := d[0], d[1]
			before := to.stats.bytesIn.Load()
			rtpBefore := to.stats.rtpIn.Load()
			deadline := time.Now().Add(8 * time.Second)
			n := 0
			for time.Now().Before(deadline) {
				done := make(chan error, 1)
				go func() { done <- from.tr.Send(labPayload(uint64(n), 1000)) }() //nolint:gosec // a small counter
				select {
				case err := <-done:
					if err != nil {
						log.Printf("lab %s %s->%s send err: %v", tag, from.name, to.name, err)
					}
				case <-time.After(3 * time.Second):
					log.Printf("lab %s %s->%s send blocked >3s", tag, from.name, to.name)
				}
				n++
				time.Sleep(200 * time.Millisecond)
			}
			log.Printf("lab %s %s->%s: delivered %d bytes, rtpIn +%d, canSend from=%v to=%v",
				tag, from.name, to.name, to.stats.bytesIn.Load()-before, to.stats.rtpIn.Load()-rtpBefore,
				from.tr.CanSend(), to.tr.CanSend())
		}
	}
	probe("before")
	who := labEnv("SEILAB_WHO", "cli")
	if who == "cli" || who == "both" {
		cli.tr.Reconnect("lab")
	}
	if who == "srv" || who == "both" {
		srv.tr.Reconnect("lab")
	}
	time.Sleep(3 * time.Second)
	start := time.Now()
	for (!srv.tr.CanSend() || !cli.tr.CanSend()) && time.Since(start) < 60*time.Second {
		time.Sleep(200 * time.Millisecond)
	}
	log.Printf("lab: after reconnect canSend srv=%v cli=%v after %v", srv.tr.CanSend(), cli.tr.CanSend(), time.Since(start).Round(time.Millisecond))
	time.Sleep(5 * time.Second)
	probe("after")
	time.Sleep(20 * time.Second)
	probe("after+25s")
}
