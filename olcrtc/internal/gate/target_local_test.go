package gate

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/app/session"
	"github.com/openlibrecommunity/olcrtc/internal/config"
)

// ai-generated: whole file, unit cover for the local target: its plan, its
// rooms, the server config it writes and the child it runs. A shell script
// stands in for cmd/olcrtc; every room, key and token here is made up.

func TestRenderServerConfigGolden(t *testing.T) {
	key := strings.Repeat("ab", 32)
	ep := Endpoint{Provider: "telemost", Transport: "vp8channel", Room: "https://telemost.yandex.ru/j/1",
		Key: key, Channel: "gate-000000000001", DNS: "8.8.8.8:53", VP8FPS: 60, VP8Batch: 64}
	want := `mode: srv
auth: { provider: telemost }
room: { id: "https://telemost.yandex.ru/j/1", channel: "gate-000000000001" }
crypto: { key: "` + key + `" }
net: { transport: vp8channel, dns: "8.8.8.8:53" }
udp: { enabled: true }
vp8: { fps: 60, batch_size: 64 }
debug: true
`
	if got := RenderServerConfig(ep, ""); got != want {
		t.Fatalf("config:\n%s\nwant:\n%s", got, want)
	}
	sei := RenderServerConfig(Endpoint{Provider: "jitsi", Transport: "seichannel", Room: "r", Key: key, DNS: "1.1.1.1:53"}, "")
	if !strings.Contains(sei, "sei: { fps: 60, batch_size: 64, fragment_size: 900, ack_timeout_ms: 2000 }") ||
		strings.Contains(sei, "vp8:") || strings.Contains(sei, "channel:") {
		t.Fatalf("sei config:\n%s", sei)
	}
	wb := RenderServerConfig(Endpoint{Provider: "wbstream", Transport: "vp8channel", Room: "fake-wb-room-1", Key: key,
		DNS: "8.8.8.8:53"}, "fake-wb-token")
	if !strings.Contains(wb, `auth: { provider: wbstream, token: "fake-wb-token" }`) ||
		!strings.Contains(wb, "vp8: { fps: 60, batch_size: 64 }") {
		t.Fatalf("wbstream config:\n%s", wb)
	}
	// A DTLS profile reaches the server as the engine's dtls block, before
	// debug; none renders no block, which the engine reads as off.
	dtls := RenderServerConfig(Endpoint{Provider: "jitsi", Transport: "datachannel", Room: "r", Key: key,
		DNS: "8.8.8.8:53", DTLSProfile: "chrome-linux-138-compat-v1"}, "")
	if !strings.HasSuffix(dtls, "udp: { enabled: true }\ndtls: { profile: \"chrome-linux-138-compat-v1\" }\ndebug: true\n") {
		t.Fatalf("dtls config:\n%s", dtls)
	}
	if strings.Contains(sei, "dtls:") {
		t.Fatalf("a config with no profile names one:\n%s", sei)
	}
	// ai-generated: a SaluteJazz room reference is one quoted scalar, colon
	// and all, and the server joins it anonymously: no token.
	sj := RenderServerConfig(Endpoint{Provider: "salutejazz", Transport: "datachannel", Room: "fakecode1:fakepass1",
		Key: key, Channel: "gate-000000000004", DNS: "8.8.8.8:53"}, "")
	if !strings.Contains(sj, "auth: { provider: salutejazz }\n") ||
		!strings.Contains(sj, `room: { id: "fakecode1:fakepass1", channel: "gate-000000000004" }`) ||
		!strings.Contains(sj, "net: { transport: datachannel, ") || strings.Contains(sj, "vp8:") ||
		strings.Contains(sj, "sei:") {
		t.Fatalf("salutejazz config:\n%s", sj)
	}
}

// TestRenderedConfigsPassTheEnginesOwnLoader loads each rendered config the
// way cmd/olcrtc does, strict loader and validation included, so a key the
// schema does not know or a value it refuses fails here, not in a live run.
func TestRenderedConfigsPassTheEnginesOwnLoader(t *testing.T) {
	session.RegisterDefaults()
	key := strings.Repeat("cd", 32)
	for _, ep := range []Endpoint{
		{Provider: "jitsi", Transport: "datachannel", Room: "https://meet.example.invalid/gate-000000000001",
			Channel: "gate-000000000002", DNS: "8.8.8.8:53"},
		{Provider: "wbstream", Transport: "vp8channel", Room: "fake-wb-room-1", Channel: "gate-000000000003",
			DNS: "8.8.8.8:53", VP8FPS: 30, VP8Batch: 16},
		{Provider: "wbstream", Transport: "seichannel", Room: "fake-wb-room-1", DNS: "8.8.8.8:53"},
		{Provider: "salutejazz", Transport: "datachannel", Room: "fakecode1:fakepass1", Channel: "gate-000000000005",
			DNS: "8.8.8.8:53"},
	} {
		ep.Key = key
		token := ""
		if ep.Provider == "wbstream" {
			token = "fake-wb-token"
		}
		path := filepath.Join(t.TempDir(), "srv.yaml")
		if err := os.WriteFile(path, []byte(RenderServerConfig(ep, token)), 0o600); err != nil {
			t.Fatal(err)
		}
		file, err := config.Load(path)
		if err != nil {
			t.Fatalf("%s: %v", ep, err)
		}
		cfg := session.ApplyDefaults(config.Apply(file))
		if err := session.Validate(cfg); err != nil {
			t.Fatalf("%s: %v", ep, err)
		}
		if cfg.Mode != "srv" || cfg.Provider != ep.Provider || cfg.Transport != ep.Transport || cfg.RoomID != ep.Room ||
			cfg.ChannelID != ep.Channel || cfg.KeyHex != key || cfg.DNSServer != ep.DNS || cfg.ProviderToken != token ||
			cfg.UDPDisabled || !file.Debug {
			t.Fatalf("%s: loaded %+v", ep, cfg)
		}
		if ep.Transport == "vp8channel" && (cfg.VP8.FPS != ep.VP8FPS || cfg.VP8.BatchSize != ep.VP8Batch) {
			t.Fatalf("%s: vp8 %+v", ep, cfg.VP8)
		}
		if ep.Transport == "seichannel" && (cfg.SEI.FragmentSize != 900 || cfg.SEI.AckTimeoutMS != 2000) {
			t.Fatalf("%s: sei %+v", ep, cfg.SEI)
		}
	}
}

func TestWaitForLine(t *testing.T) {
	p := filepath.Join(t.TempDir(), "srv.log")
	if err := os.WriteFile(p, []byte("starting\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	go func() {
		time.Sleep(100 * time.Millisecond)
		f, err := os.OpenFile(p, os.O_APPEND|os.O_WRONLY, 0o600)
		if err != nil {
			return
		}
		_, _ = f.WriteString("2026/09/15 Link connected\n")
		_ = f.Close()
	}()
	if err := WaitForLine(p, "Link connected", 3*time.Second); err != nil {
		t.Fatal(err)
	}
	if err := WaitForLine(p, "never", 200*time.Millisecond); !errors.Is(err, ErrLineNotSeen) {
		t.Fatalf("WaitForLine returned %v without the line", err)
	}
}

func TestLocalTargetPairsFollowTheSupportTable(t *testing.T) {
	lt, err := NewLocalTarget(LocalOptions{
		WorkDir: t.TempDir(), JitsiHosts: []string{"meet.example.invalid"},
		Providers:  []string{"jitsi", "telemost", "wbstream", "salutejazz"},
		Transports: []string{"datachannel", "videochannel", "seichannel", "vp8channel"},
	})
	if err != nil {
		t.Fatal(err)
	}
	want := []Pair{
		{"jitsi", "datachannel"}, {"jitsi", "videochannel"}, {"jitsi", "seichannel"}, {"jitsi", "vp8channel"},
		{"telemost", "videochannel"}, {"telemost", "vp8channel"},
		{"wbstream", "videochannel"}, {"wbstream", "seichannel"}, {"wbstream", "vp8channel"},
		{"salutejazz", "datachannel"},
	}
	if got := lt.Pairs(); !slices.Equal(got, want) {
		t.Fatalf("Pairs = %v\nwant %v", got, want)
	}
	if lt.Platform() != "engine-linux" || lt.Name() != "local" || lt.Load() != (LoadURLs{}) {
		t.Fatal("names or load")
	}
	two, err := NewLocalTarget(LocalOptions{WorkDir: t.TempDir(), JitsiHosts: []string{"meet.example.invalid"},
		Providers: []string{"wbstream", "jitsi"}, Transports: []string{"vp8channel", "datachannel"}})
	if err != nil {
		t.Fatal(err)
	}
	if got := two.Pairs(); !slices.Equal(got, []Pair{{"wbstream", "vp8channel"}, {"jitsi", "vp8channel"},
		{"jitsi", "datachannel"}}) {
		t.Fatalf("Pairs = %v, want the given order less wbstream/datachannel", got)
	}
}

func TestLocalTargetRefusesAPlanItCannotRun(t *testing.T) {
	hosts := []string{"meet.example.invalid"}
	noHosts := filepath.Join(t.TempDir(), "instances.yaml")
	if err := os.WriteFile(noHosts, []byte("instances: []\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	for name, opts := range map[string]LocalOptions{
		"only an unsupported pair":    {Providers: []string{"telemost"}, Transports: []string{"datachannel"}},
		"a provider left with none":   {Providers: []string{"jitsi", "telemost"}, Transports: []string{"datachannel"}},
		"a transport nobody carries":  {Providers: []string{"wbstream"}, Transports: []string{"datachannel", "vp8channel"}},
		"salutejazz carries no media": {Providers: []string{"salutejazz"}, Transports: []string{"vp8channel"}},
		"an unknown provider":         {Providers: []string{"skype"}, Transports: []string{"vp8channel"}},
		"an unknown transport":        {Providers: []string{"jitsi"}, Transports: []string{"smokechannel"}},
		"a provider twice":            {Providers: []string{"jitsi", "jitsi"}, Transports: []string{"vp8channel"}},
		"no provider":                 {Transports: []string{"vp8channel"}},
		"no transport":                {Providers: []string{"jitsi"}},
		"no jitsi host":               {Providers: []string{"jitsi"}, Transports: []string{"datachannel"}, JitsiHosts: []string{" "}},
		"no work directory":           {Providers: []string{"telemost"}, Transports: []string{"vp8channel"}},
		"an unknown dtls profile": {Providers: []string{"telemost"}, Transports: []string{"vp8channel"},
			DTLSProfile: "chrome-linux-999"},
	} {
		if name != "no work directory" {
			opts.WorkDir = t.TempDir()
		}
		want := ErrLocalOptions
		if name == "no jitsi host" { // a blank override and a list that names none
			opts.Instances, want = noHosts, ErrNoJitsiHost
		} else {
			opts.JitsiHosts = hosts
		}
		if _, err := NewLocalTarget(opts); !errors.Is(err, want) {
			t.Fatalf("%s: err = %v, want %v", name, err, want)
		}
	}
}

func TestLocalTargetRoomsFollowTheProvider(t *testing.T) {
	lt, err := NewLocalTarget(LocalOptions{
		WorkDir: t.TempDir(), Providers: []string{"jitsi", "telemost", "wbstream"}, Transports: []string{"vp8channel"},
		JitsiHosts:    []string{"down.example.invalid", "up.example.invalid"},
		TelemostRooms: []string{"fake-telemost-1", "https://telemost.yandex.ru/j/fake-telemost-2"},
		WBStreamRooms: []string{"https://stream.wb.ru/room/fake-wb-room-1", "fake-wb-room-2"},
		WBStreamToken: "fake-wb-token", RunNumber: 3,
	})
	if err != nil {
		t.Fatal(err)
	}
	lt.probe = func(_ context.Context, host string) bool { return host == "up.example.invalid" }
	ctx := context.Background()
	if r, err := lt.room(ctx, "jitsi"); err != nil || !strings.HasPrefix(r, "https://up.example.invalid/gate-") {
		t.Fatalf("jitsi room = %q %v", r, err)
	}
	if r, err := lt.room(ctx, "telemost"); err != nil || r != "https://telemost.yandex.ru/j/fake-telemost-2" {
		t.Fatalf("telemost room = %q %v", r, err)
	}
	if r, err := lt.room(ctx, "wbstream"); err != nil || r != "fake-wb-room-2" {
		t.Fatalf("wbstream room = %q %v", r, err)
	}
	lt.opts.RunNumber = 4
	if r, err := lt.room(ctx, "telemost"); err != nil || r != "https://telemost.yandex.ru/j/fake-telemost-1" {
		t.Fatalf("telemost room = %q %v", r, err)
	}
	if r, err := lt.room(ctx, "wbstream"); err != nil || r != "fake-wb-room-1" {
		t.Fatalf("wbstream room = %q %v", r, err)
	}
	lt.opts.TelemostRooms = nil
	if _, err := lt.room(ctx, "telemost"); !errors.Is(err, ErrPoolRoom) {
		t.Fatalf("an empty pool: err = %v", err)
	}
}

// TestLocalTargetEndpointsAreFreshPerPair asks twice for each pair: every
// endpoint carries its own key and channel (spec section 2, A4), so a server
// left over from an earlier pair or run can never handshake. A Jitsi pair
// gets a fresh room too; a pool room is the run's, the same each time.
func TestLocalTargetEndpointsAreFreshPerPair(t *testing.T) {
	lt, err := NewLocalTarget(LocalOptions{
		WorkDir: t.TempDir(), Providers: []string{"jitsi", "telemost", "wbstream"}, Transports: []string{"vp8channel"},
		JitsiHosts: []string{"up.example.invalid"}, TelemostRooms: []string{"fake-telemost-1"},
		WBStreamRooms: []string{"fake-wb-room-1"}, WBStreamToken: "fake-wb-token",
	})
	if err != nil {
		t.Fatal(err)
	}
	lt.probe = func(context.Context, string) bool { return true }
	for _, p := range []Pair{{"jitsi", "vp8channel"}, {"telemost", "vp8channel"}, {"wbstream", "vp8channel"}} {
		first, err1 := lt.endpoint(context.Background(), p)
		second, err2 := lt.endpoint(context.Background(), p)
		if err1 != nil || err2 != nil {
			t.Fatalf("%s: %v, %v", p, err1, err2)
		}
		if first.Key == second.Key || first.Channel == second.Channel {
			t.Fatalf("%s: same key %t, same channel %t, want both fresh per pair", p,
				first.Key == second.Key, first.Channel == second.Channel)
		}
		if fresh := first.Room != second.Room; fresh != (p.Provider == "jitsi") {
			t.Fatalf("%s: fresh room %t, want a fresh one on jitsi alone", p, fresh)
		}
	}
}

// ai-generated: SaluteJazz has no pool. Every pair gets a room of its own from
// the provider's anonymous create call, as a Jitsi pair does, and a create
// that fails is a server that did not come up: named, and tried once more,
// as a provider's refusal is.
func TestLocalTargetMintsAFreshSaluteJazzRoomPerPair(t *testing.T) {
	lt, err := NewLocalTarget(LocalOptions{WorkDir: t.TempDir(), Providers: []string{"salutejazz"},
		Transports: []string{"datachannel"}})
	if err != nil {
		t.Fatal(err)
	}
	if lt.saluteJazzRoom == nil {
		t.Fatal("no room maker: a salutejazz pair would have no room")
	}
	minted := 0
	lt.saluteJazzRoom = func(context.Context) (string, error) {
		minted++
		return fmt.Sprintf("fakecode%d:fakepass%d", minted, minted), nil
	}
	p := Pair{"salutejazz", "datachannel"}
	first, err1 := lt.endpoint(context.Background(), p)
	second, err2 := lt.endpoint(context.Background(), p)
	if err1 != nil || err2 != nil {
		t.Fatalf("%v, %v", err1, err2)
	}
	if first.Room != "fakecode1:fakepass1" || second.Room != "fakecode2:fakepass2" || minted != 2 {
		t.Fatalf("rooms %q and %q after %d creates, want one fresh room per pair", first.Room, second.Room, minted)
	}
	down := errors.New("create meeting: status 503")
	lt.saluteJazzRoom = func(context.Context) (string, error) { return "", down }
	_, err = lt.room(context.Background(), "salutejazz")
	if !errors.Is(err, down) || !strings.HasPrefix(err.Error(), "salutejazz: ") || !retryable(err) {
		t.Fatalf("a failed create: err = %v, want it named, wrapped and tried once more", err)
	}
}

// writeFakeServer stands in for cmd/olcrtc: a script that prints its config
// (room, key, channel, token) into its log and then runs body.
func writeFakeServer(t *testing.T, body string) string {
	t.Helper()
	if runtime.GOOS == "windows" {
		t.Skip("the fake server is a shell script")
	}
	p := filepath.Join(t.TempDir(), "olcrtc")
	script := "#!/bin/sh\ncat \"$1\"\n" + body + "\n"
	if err := os.WriteFile(p, []byte(script), 0o700); err != nil { //nolint:gosec // the fake server must run
		t.Fatal(err)
	}
	return p
}

// fakeLocal is a local target with one pair whose server is the fake.
func fakeLocal(t *testing.T, work string, opts LocalOptions, body string) *LocalTarget {
	t.Helper()
	opts.WorkDir = work
	lt, err := NewLocalTarget(opts)
	if err != nil {
		t.Fatal(err)
	}
	lt.binary = writeFakeServer(t, body)
	return lt
}

func readTargetFile(t *testing.T, path string) string {
	t.Helper()
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return string(raw)
}

func TestLocalTargetRunsTheServerAndLeavesOnlyAScrubbedLog(t *testing.T) {
	work, dir := t.TempDir(), t.TempDir()
	// The fake's loop ends by itself after about 30 s, so a test binary that
	// dies before its cleanups run leaves nothing looping behind.
	lt := fakeLocal(t, work, LocalOptions{
		Providers: []string{"wbstream"}, Transports: []string{"vp8channel"},
		WBStreamRooms: []string{"https://stream.wb.ru/room/fake-wb-room-1"}, WBStreamToken: "fake-wb-token-0001",
	}, `trap 'echo "leaving fake-wb-room-1"; exit 0' TERM
echo "bridge delay $OLCRTC_TEST_BRIDGE_DELAY"; echo "private targets $OLCRTC_TEST_ALLOW_PRIVATE_TARGETS"
echo "joining fake-wb-room-1"; echo "Link connected"
i=0; while [ "$i" -lt 600 ]; do sleep 0.05; i=$((i+1)); done`)
	ep, stop, err := lt.Open(context.Background(), Pair{"wbstream", "vp8channel"}, dir, OpenOptions{BridgeDelay: 1500 * time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(stop) // a check that fails before the stop below still stops the server
	if ep.Room != "fake-wb-room-1" || len(ep.Key) != 64 || !strings.HasPrefix(ep.Channel, "gate-") ||
		ep.DNS != "8.8.8.8:53" || ep.VP8FPS != 60 || ep.VP8Batch != 64 {
		t.Fatalf("endpoint = %+v channel %q", ep, ep.Channel)
	}
	configs, _ := filepath.Glob(filepath.Join(work, "*", "srv.yaml"))
	if len(configs) != 1 || !strings.Contains(readTargetFile(t, configs[0]), `token: "fake-wb-token-0001"`) {
		t.Fatalf("private configs = %v", configs)
	}
	if _, err := os.Stat(filepath.Join(dir, "srv.yaml")); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("the config reached the artifact directory: %v", err)
	}
	stopped := time.Now()
	stop()
	stop()
	if took := time.Since(stopped); took > stopGrace/2 {
		t.Fatalf("stop took %s: the server was killed, not asked to leave", took)
	}
	log := readTargetFile(t, filepath.Join(dir, "srv.log"))
	for _, secret := range []string{"fake-wb-room-1", ep.Key, ep.Channel, "fake-wb-token-0001"} {
		if strings.Contains(log, secret) {
			t.Fatalf("scrubbed log keeps %q:\n%s", secret, log)
		}
	}
	// ai-generated: "private targets 1", the loopback origin's hook (egress hardening).
	for _, kept := range []string{
		"Link connected", "bridge delay 1.5s", "private targets 1", "joining <room>", "<key>", "leaving <room>",
	} {
		if !strings.Contains(log, kept) {
			t.Fatalf("scrubbed log lost %q:\n%s", kept, log)
		}
	}
	if left, _ := os.ReadDir(work); len(left) != 0 {
		t.Fatalf("private files left after stop: %v", left)
	}
	if entries, _ := os.ReadDir(dir); len(entries) != 1 || entries[0].Name() != "srv.log" {
		t.Fatalf("artifact directory holds %v", entries)
	}
}

func TestLocalTargetReportsAServerThatDiesBeforeTheLink(t *testing.T) {
	dir := t.TempDir()
	lt := fakeLocal(t, t.TempDir(), LocalOptions{
		Providers: []string{"telemost"}, Transports: []string{"vp8channel"}, TelemostRooms: []string{"fake-telemost-1"},
	}, `echo "get connection info: status 404: no conference `+strings.Repeat("x", 340)+
		` fake-telemost-1 and what follows the cut"; exit 1`)
	start := time.Now()
	_, _, err := lt.Open(context.Background(), Pair{"telemost", "vp8channel"}, dir, OpenOptions{})
	if !errors.Is(err, ErrServerExited) || time.Since(start) > 10*time.Second {
		t.Fatalf("err = %v after %s", err, time.Since(start))
	}
	// The room straddles the point the reason is cut at: scrubbed first, it
	// survives as <room>; cut first, its head would leak.
	if !strings.Contains(err.Error(), "status 404: no conference xxx") || !strings.Contains(err.Error(), "<room>") ||
		strings.Contains(err.Error(), "fake-tel") || strings.Contains(err.Error(), "what follows") {
		t.Fatalf("the reason must carry the provider's answer, clipped, and no room: %v", err)
	}
	if log := readTargetFile(t, filepath.Join(dir, "srv.log")); strings.Contains(log, "fake-telemost-1") ||
		!strings.Contains(log, "status 404") {
		t.Fatalf("scrubbed log:\n%s", log)
	}
}

func TestLocalTargetStopsAServerThatNeverLinks(t *testing.T) {
	dir := t.TempDir()
	lt := fakeLocal(t, t.TempDir(), LocalOptions{
		Providers: []string{"jitsi"}, Transports: []string{"datachannel"}, JitsiHosts: []string{"up.example.invalid"},
	}, `echo "jitsi: joining MUC up.example.invalid/x"; exec sleep 30`)
	lt.probe = func(context.Context, string) bool { return true }
	lt.linkWait = 300 * time.Millisecond
	start := time.Now()
	_, stop, err := lt.Open(context.Background(), Pair{"jitsi", "datachannel"}, dir, OpenOptions{})
	if stop != nil {
		t.Cleanup(stop)
	}
	// The host of an override comes from a secret (GATE_JITSI_HOSTS): the
	// line the error quotes and the log keep it only as <room>.
	if !errors.Is(err, ErrLineNotSeen) || !strings.Contains(err.Error(), "jitsi: joining MUC <room>/x") ||
		strings.Contains(err.Error(), "up.example.invalid") {
		t.Fatalf("err = %v", err)
	}
	if took := time.Since(start); took > 10*time.Second {
		t.Fatalf("a server that never links held Open for %s", took)
	}
	if log := readTargetFile(t, filepath.Join(dir, "srv.log")); strings.Contains(log, "gate-") ||
		strings.Contains(log, "up.example.invalid") {
		t.Fatalf("scrubbed log keeps the room, the channel or the host:\n%s", log)
	}
}

// ai-generated: an override host given with a port (docker-jitsi-meet serves
// on 8443) reaches the server's log bare, in a resolver's error and in the
// Jitsi config the server fetches, and lowercased: neither the line the
// error quotes nor the log keeps it.
func TestLocalTargetWithholdsAnOverrideHostWithoutItsPort(t *testing.T) {
	dir := t.TempDir()
	lt := fakeLocal(t, t.TempDir(), LocalOptions{
		Providers: []string{"jitsi"}, Transports: []string{"datachannel"}, JitsiHosts: []string{"Up.Example.Invalid:8443"},
	}, `echo "<service host='up.example.invalid' port='443' type='turns'/>"
echo "jitsi: dial: lookup up.example.invalid on 8.8.8.8:53: no such host"; exec sleep 30`)
	lt.probe = func(context.Context, string) bool { return true }
	lt.linkWait = 300 * time.Millisecond
	_, stop, err := lt.Open(context.Background(), Pair{"jitsi", "datachannel"}, dir, OpenOptions{})
	if stop != nil {
		t.Cleanup(stop)
	}
	if !errors.Is(err, ErrLineNotSeen) || !strings.Contains(err.Error(), "lookup <room> on 8.8.8.8:53") ||
		strings.Contains(strings.ToLower(err.Error()), "up.example.invalid") {
		t.Fatalf("err = %v", err)
	}
	log := readTargetFile(t, filepath.Join(dir, "srv.log"))
	if strings.Contains(strings.ToLower(log), "up.example.invalid") || !strings.Contains(log, "<service host='<room>'") {
		t.Fatalf("scrubbed log keeps the host:\n%s", log)
	}
}

func TestLocalTargetNamesACancelledRunNotADeadServer(t *testing.T) {
	lt := fakeLocal(t, t.TempDir(), LocalOptions{
		Providers: []string{"telemost"}, Transports: []string{"vp8channel"}, TelemostRooms: []string{"fake-telemost-1"},
	}, `echo "telemost: joining"; exec sleep 30`)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	time.AfterFunc(300*time.Millisecond, cancel)
	start := time.Now()
	_, stop, err := lt.Open(ctx, Pair{"telemost", "vp8channel"}, t.TempDir(), OpenOptions{})
	if stop != nil {
		t.Cleanup(stop)
	}
	if !errors.Is(err, context.Canceled) || time.Since(start) > 10*time.Second {
		t.Fatalf("err = %v after %s, want the cancellation", err, time.Since(start))
	}
}

func TestLocalTargetFailsWBStreamWithoutAToken(t *testing.T) {
	lt, err := NewLocalTarget(LocalOptions{WorkDir: t.TempDir(), Providers: []string{"wbstream"},
		Transports: []string{"vp8channel"}, WBStreamRooms: []string{"fake-wb-room-1"}})
	if err != nil {
		t.Fatal(err)
	}
	_, _, err = lt.Open(context.Background(), Pair{"wbstream", "vp8channel"}, t.TempDir(), OpenOptions{})
	want := "wbstream: OLCRTC_GATE_WBSTREAM_TOKEN is not set (WB refuses a guest as the first participant of an idle room)"
	if !errors.Is(err, ErrNoWBStreamToken) || err.Error() != want {
		t.Fatalf("err = %v", err)
	}
}

func TestLocalTargetRefusesAPairItDoesNotCarry(t *testing.T) {
	lt, err := NewLocalTarget(LocalOptions{WorkDir: t.TempDir(), Providers: []string{"telemost"},
		Transports: []string{"vp8channel"}, TelemostRooms: []string{"fake-telemost-1"}})
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := lt.Open(context.Background(), Pair{"telemost", "datachannel"}, t.TempDir(), OpenOptions{}); !errors.Is(err, ErrPairNotCarried) {
		t.Fatalf("err = %v", err)
	}
}

// ai-generated: the rest of the file (olcrtc#26). What it holds: a failed
// cell says whether the relay ended the server's session under it, with the
// reason the relay gave, and nothing about a cell that passed.

const relayDropLog = `2026/09/20 10:28:21 Connecting transport=vp8channel provider=wbstream ...
2026/09/20 10:28:22 Link connected
2026/09/20 10:28:24 vp8channel: authenticated peer epoch=0x5634ae87
2026/09/20 10:29:01 livekit: disconnected from the room, reason=PARTICIPANT_REMOVED
2026/09/20 10:29:02 server reconnect reason=provider - tearing down smux session
not a log line at all
`

func relayLogFile(t *testing.T, body string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "srv.log")
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatalf("write log: %v", err)
	}
	return path
}

func stamp(t *testing.T, value string) time.Time {
	t.Helper()
	at, err := time.ParseInLocation(logTimeLayout, value, time.Local)
	if err != nil {
		t.Fatalf("parse %q: %v", value, err)
	}
	return at
}

func TestRelayDropFailuresNamesTheReasonAndWhen(t *testing.T) {
	path := relayLogFile(t, relayDropLog)

	got := RelayDropFailures(path, LogMark{}, stamp(t, "2026/09/20 10:28:35"), stamp(t, "2026/09/20 10:29:10"))
	if len(got) != 1 {
		t.Fatalf("RelayDropFailures() = %q, want one line", got)
	}
	for _, want := range []string{"PARTICIPANT_REMOVED", "26s into the cell", "39s after it joined"} {
		if !strings.Contains(got[0], want) {
			t.Fatalf("RelayDropFailures() = %q, want it to name %q", got[0], want)
		}
	}
}

func TestRelayDropFailuresKeepsToTheCellWindow(t *testing.T) {
	path := relayLogFile(t, relayDropLog)

	before := RelayDropFailures(path, LogMark{}, stamp(t, "2026/09/20 10:29:02"), stamp(t, "2026/09/20 10:29:30"))
	if len(before) != 0 {
		t.Fatalf("a drop before the cell was reported: %q", before)
	}
	after := RelayDropFailures(path, LogMark{}, stamp(t, "2026/09/20 10:28:00"), stamp(t, "2026/09/20 10:28:59"))
	if len(after) != 0 {
		t.Fatalf("a drop after the cell was reported: %q", after)
	}
	if got := RelayDropFailures(filepath.Join(t.TempDir(), "absent.log"), LogMark{},
		stamp(t, "2026/09/20 10:28:00"), stamp(t, "2026/09/20 10:30:00")); got != nil {
		t.Fatalf("a log that cannot be read reported %q", got)
	}
}

func TestRelayDropFailuresWithoutAJoinOrAReason(t *testing.T) {
	path := relayLogFile(t, "2026/09/20 10:29:01 livekit: disconnected from the room, reason=\n")

	got := RelayDropFailures(path, LogMark{}, stamp(t, "2026/09/20 10:28:00"), stamp(t, "2026/09/20 10:30:00"))
	if len(got) != 1 {
		t.Fatalf("RelayDropFailures() = %q, want one line", got)
	}
	if !strings.Contains(got[0], "UNKNOWN_REASON") {
		t.Fatalf("RelayDropFailures() = %q, want an unnamed reason", got[0])
	}
	if strings.Contains(got[0], "after it joined") {
		t.Fatalf("RelayDropFailures() = %q, want no join it never saw", got[0])
	}
}

func TestWithRelayDropsLeavesAPassingCellAlone(t *testing.T) {
	path := relayLogFile(t, relayDropLog)
	from, to := stamp(t, "2026/09/20 10:28:35"), stamp(t, "2026/09/20 10:29:10")

	if got := withRelayDrops(nil, path, LogMark{}, from, to); got != nil {
		t.Fatalf("a cell that passed was given %q", got)
	}
	got := withRelayDrops([]string{"pull_ok 0 != 6"}, path, LogMark{}, from, to)
	if len(got) != 2 || got[0] != "pull_ok 0 != 6" {
		t.Fatalf("withRelayDrops() = %q, want the cell's own failure and the relay's line", got)
	}
	if !strings.Contains(got[1], "PARTICIPANT_REMOVED") {
		t.Fatalf("withRelayDrops() = %q, want the relay's reason", got)
	}
}

// ai-generated: the rest of the file (the streaming reader: a failed cell
// reads its own part of the server log, from where the log stood when the
// cell began, one bounded line at a time).

// wholeFileRelayDrops is RelayDropFailures as it was before it streamed: the
// whole log read and copied into a string, split on newlines. The streaming
// reader must say what it said.
func wholeFileRelayDrops(logPath string, from, to time.Time) []string {
	data, err := os.ReadFile(logPath)
	if err != nil {
		return nil
	}
	var (
		out    []string
		joined time.Time
	)
	for line := range strings.SplitSeq(string(data), "\n") {
		stamp, ok := logTime([]byte(line))
		if !ok {
			continue
		}
		switch {
		case strings.Contains(line, linkedLine):
			joined = stamp
		case strings.Contains(line, relayDropMarker):
			if stamp.Before(from) || stamp.After(to) {
				continue
			}
			out = append(out, relayDropLine(line, stamp, joined, from))
		}
	}
	return out
}

// bigRelayLog writes a server log of 32 MiB and returns it with the mark a
// cell began at, 16 MiB in, and the cell's window. Before the mark: the
// join, and a drop of an earlier cell. After it: a line past 64 KiB (a
// bufio.Scanner's default limit, which would end the read there) with a drop
// behind it, a drop whose own line runs past 64 KiB, a drop stamped after
// the cell, and a last drop the log ends on without a newline, the way a log
// still being written does.
func bigRelayLog(t *testing.T) (string, LogMark, time.Time, time.Time) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "srv.log")
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	w := bufio.NewWriterSize(f, 1<<20)
	n := 0
	put := func(line string) {
		k, err := w.WriteString(line)
		if err != nil {
			t.Fatal(err)
		}
		n += k
	}
	// fill writes frame lines up to size bytes, stamped from first to last.
	fill := func(size int, first, last time.Time) {
		start, span := n, last.Sub(first)
		for n < size {
			at := first.Add(time.Duration(float64(span) * float64(n-start) / float64(size-start)))
			put(fmt.Sprintf("%s datachannel: frame sid=3 len=16384 queued=%d\n", at.Format(logTimeLayout), n))
		}
	}
	const last = "2026/09/20 10:19:59 livekit: disconnected from the room, reason=JOIN_FAILURE"
	put("2026/09/20 10:00:00 Connecting transport=datachannel provider=salutejazz ...\n")
	put("2026/09/20 10:00:01 Link connected\n")
	fill(8<<20, stamp(t, "2026/09/20 10:00:02"), stamp(t, "2026/09/20 10:04:59"))
	put("2026/09/20 10:05:00 livekit: disconnected from the room, reason=DUPLICATE_IDENTITY\n")
	fill(16<<20, stamp(t, "2026/09/20 10:05:01"), stamp(t, "2026/09/20 10:09:59"))
	mark := LogMark{Offset: int64(n), Joined: stamp(t, "2026/09/20 10:00:01")}
	fill(20<<20, stamp(t, "2026/09/20 10:10:00"), stamp(t, "2026/09/20 10:11:59"))
	put("2026/09/20 10:12:00 jitsi: stanza <iq>" + strings.Repeat("x", 100<<10) + "</iq>\n")
	put("2026/09/20 10:13:07 livekit: disconnected from the room, reason=PARTICIPANT_REMOVED\n")
	fill(26<<20, stamp(t, "2026/09/20 10:13:08"), stamp(t, "2026/09/20 10:14:59"))
	put("2026/09/20 10:15:00 livekit: disconnected from the room, reason=ROOM_DELETED " +
		strings.Repeat("y", 70<<10) + "\n")
	put("2026/09/20 10:21:00 livekit: disconnected from the room, reason=SERVER_SHUTDOWN\n")
	fill(32<<20-len(last), stamp(t, "2026/09/20 10:15:01"), stamp(t, "2026/09/20 10:19:58"))
	put(last)
	if err := w.Flush(); err != nil {
		t.Fatal(err)
	}
	if err := f.Close(); err != nil {
		t.Fatal(err)
	}
	return path, mark, stamp(t, "2026/09/20 10:10:00"), stamp(t, "2026/09/20 10:20:00")
}

// TestRelayDropFailuresStreamsABigLogFromTheCellsMark holds the reader to
// the words the whole-file read had and to a bound on what it allocates: a
// failed cell late in a pair used to copy the whole log, 20 MB and more, into
// the process S7 weighs.
func TestRelayDropFailuresStreamsABigLogFromTheCellsMark(t *testing.T) {
	path, mark, from, to := bigRelayLog(t)
	want := []string{
		"the relay ended the server's session (PARTICIPANT_REMOVED) 3m7s into the cell, 13m6s after it joined",
		"the relay ended the server's session (ROOM_DELETED) 5m0s into the cell, 14m59s after it joined",
		"the relay ended the server's session (JOIN_FAILURE) 9m59s into the cell, 19m58s after it joined",
	}
	if old := wholeFileRelayDrops(path, from, to); !slices.Equal(old, want) {
		t.Fatalf("the whole-file read says %q, want %q: the fixture is off", old, want)
	}
	var before, after runtime.MemStats
	runtime.ReadMemStats(&before)
	got := RelayDropFailures(path, mark, from, to)
	runtime.ReadMemStats(&after)
	if !slices.Equal(got, want) {
		t.Fatalf("RelayDropFailures() = %q, want %q", got, want)
	}
	alloc := after.TotalAlloc - before.TotalAlloc
	if alloc >= 2<<20 {
		t.Fatalf("reading a 32 MiB log allocated %d KiB, want under 2 MiB", alloc>>10)
	}
	t.Logf("a 32 MiB log read from its 16 MiB mark allocated %d KiB", alloc>>10)
}

// TestRelayDropFailuresReadsFromTheCellsMark starts the read where the log
// stood when the cell began: what was written before is not the cell's, and
// the join it names comes from the mark until the log shows a later one.
func TestRelayDropFailuresReadsFromTheCellsMark(t *testing.T) {
	head := "2026/09/20 10:28:22 Link connected\n" +
		"2026/09/20 10:29:01 livekit: disconnected from the room, reason=PARTICIPANT_REMOVED\n"
	path := relayLogFile(t, head+
		"2026/09/20 10:29:05 livekit: disconnected from the room, reason=ROOM_DELETED\n"+
		"2026/09/20 10:29:06 Link connected\n"+
		"2026/09/20 10:29:08 livekit: disconnected from the room, reason=SERVER_SHUTDOWN\n")
	mark := LogMark{Offset: int64(len(head)), Joined: stamp(t, "2026/09/20 10:28:22")}

	got := RelayDropFailures(path, mark, stamp(t, "2026/09/20 10:28:35"), stamp(t, "2026/09/20 10:29:10"))
	want := []string{
		"the relay ended the server's session (ROOM_DELETED) 30s into the cell, 43s after it joined",
		"the relay ended the server's session (SERVER_SHUTDOWN) 33s into the cell, 2s after it joined",
	}
	if !slices.Equal(got, want) {
		t.Fatalf("RelayDropFailures() = %q, want %q", got, want)
	}
	past := LogMark{Offset: 1 << 20, Joined: mark.Joined}
	if got := RelayDropFailures(path, past, stamp(t, "2026/09/20 10:28:00"), stamp(t, "2026/09/20 10:30:00")); got != nil {
		t.Fatalf("a mark past the log's end reported %q", got)
	}
}

// TestMarkLogIsWhereTheLogStandsAndTheJoin is the mark a cell takes as it
// begins: the log's size and the join its target noted; a log that is not
// there marks its start.
func TestMarkLogIsWhereTheLogStandsAndTheJoin(t *testing.T) {
	path := relayLogFile(t, relayDropLog)
	joined := stamp(t, "2026/09/20 10:28:22")
	if got := markLog(Endpoint{ServerLog: path, ServerJoined: joined}); got.Offset != int64(len(relayDropLog)) ||
		!got.Joined.Equal(joined) {
		t.Fatalf("markLog() = %+v, want offset %d and the join", got, len(relayDropLog))
	}
	if got := markLog(Endpoint{}); got != (LogMark{}) {
		t.Fatalf("markLog() without a server log = %+v, want the zero mark", got)
	}
}

// TestLocalTargetNotesWhenItsServerJoined is where a mark's join comes from:
// the stamp of the server's join line, zero when the line has none.
func TestLocalTargetNotesWhenItsServerJoined(t *testing.T) {
	for _, tc := range []struct {
		name, line string
		want       time.Time
	}{
		{"stamped", "2026/09/20 10:28:22 Link connected", stamp(t, "2026/09/20 10:28:22")},
		{"bare", "Link connected", time.Time{}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			lt := fakeLocal(t, t.TempDir(), LocalOptions{
				Providers: []string{"telemost"}, Transports: []string{"vp8channel"}, TelemostRooms: []string{"fake-telemost-1"},
			}, `trap 'exit 0' TERM
echo "2026/09/20 10:28:21 Connecting"; echo "`+tc.line+`"
i=0; while [ "$i" -lt 600 ]; do sleep 0.05; i=$((i+1)); done`)
			ep, stop, err := lt.Open(context.Background(), Pair{"telemost", "vp8channel"}, t.TempDir(), OpenOptions{})
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(stop)
			if !ep.ServerJoined.Equal(tc.want) {
				t.Fatalf("ServerJoined = %v, want %v", ep.ServerJoined, tc.want)
			}
		})
	}
}

// TestEachLogLineCutsALongLineToItsHead holds the reader's bound: a line past
// logLineMax is read as its head, and what follows the cut is skipped, not
// read as lines of its own, whatever it looks like.
func TestEachLogLineCutsALongLineToItsHead(t *testing.T) {
	head := "2026/09/20 10:29:00 jitsi: stanza " + strings.Repeat("x", logLineMax)
	head = head[:logLineMax]
	path := relayLogFile(t, head+"2026/09/20 10:29:01 livekit: disconnected from the room, reason=CUT\n"+
		"2026/09/20 10:29:02 next\n"+"2026/09/20 10:29:03 last, no newline")
	var got []string
	if err := eachLogLine(path, 0, func(line []byte) bool {
		got = append(got, string(line))
		return true
	}); err != nil {
		t.Fatal(err)
	}
	if want := []string{head, "2026/09/20 10:29:02 next", "2026/09/20 10:29:03 last, no newline"}; !slices.Equal(got, want) {
		t.Fatalf("eachLogLine() read %d lines, want the long line's head, the next and the last", len(got))
	}
	if drops := RelayDropFailures(path, LogMark{}, stamp(t, "2026/09/20 10:28:00"), stamp(t, "2026/09/20 10:30:00")); drops != nil {
		t.Fatalf("a drop past the cut was read as a line of its own: %q", drops)
	}
	if err := eachLogLine(filepath.Join(t.TempDir(), "absent.log"), 0, func([]byte) bool { return true }); err == nil {
		t.Fatal("eachLogLine() of a log that is not there returned nil")
	}
}
