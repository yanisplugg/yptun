package gate

import (
	"context"
	"errors"
	"math"
	"net/url"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
)

// ai-generated: whole file, unit cover for rooms, keys, channel ids and the
// forms pool entries take for their provider. Every room and host here is
// made up.

func allLowerHex(s string) bool { return strings.Trim(s, "0123456789abcdef") == "" }

func TestJitsiRoomPicksTheFirstReachableHost(t *testing.T) {
	hosts := []string{"down.example.invalid", "up.example.invalid", "other.example.invalid"}
	var probed []string
	probe := func(_ context.Context, host string) bool {
		probed = append(probed, host)
		return host == "up.example.invalid"
	}
	room, err := JitsiRoom(context.Background(), hosts, probe)
	if err != nil {
		t.Fatal(err)
	}
	const prefix = "https://up.example.invalid/gate-"
	if !strings.HasPrefix(room, prefix) || len(room) != len(prefix)+12 || !allLowerHex(room[len(prefix):]) {
		t.Fatalf("room = %q", room)
	}
	if !slices.Equal(probed, hosts[:2]) {
		t.Fatalf("probed %v, want the hosts up to the first that answers", probed)
	}
	again, err := JitsiRoom(context.Background(), hosts, probe)
	if err != nil || again == room {
		t.Fatalf("a second room = %q %v, want a fresh one", again, err)
	}
	none := func(context.Context, string) bool { return false }
	if _, err := JitsiRoom(context.Background(), hosts, none); !errors.Is(err, ErrNoJitsiHost) {
		t.Fatalf("no reachable host: err = %v", err)
	}
	if _, err := JitsiRoom(context.Background(), nil, probe); !errors.Is(err, ErrNoJitsiHost) {
		t.Fatalf("no host at all: err = %v", err)
	}
}

func TestJitsiRoomNamesACancelledRunNotAnUnreachableHost(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	probe := func(ctx context.Context, _ string) bool { return ctx.Err() == nil }
	_, err := JitsiRoom(ctx, []string{"up.example.invalid"}, probe)
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("err = %v, want the cancellation", err)
	}
}

func TestJitsiHostsComeFromTheOverrideOrTheInstanceList(t *testing.T) {
	dir := t.TempDir()
	list := filepath.Join(dir, "instances.yaml")
	body := "# made-up hosts\ninstances:\n  - one.example.invalid\n  - ' two.example.invalid '\n  - ''\n"
	if err := os.WriteFile(list, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	hosts, err := JitsiHosts(list, nil)
	if err != nil || !slices.Equal(hosts, []string{"one.example.invalid", "two.example.invalid"}) {
		t.Fatalf("instance list: %v %v", hosts, err)
	}
	override := []string{"https://three.example.invalid/", " four.example.invalid ", "", "HTTP://five.example.invalid/x"}
	want := []string{"three.example.invalid", "four.example.invalid", "five.example.invalid"}
	if hosts, err := JitsiHosts(list, override); err != nil || !slices.Equal(hosts, want) {
		t.Fatalf("override: %v %v, want %v", hosts, err, want)
	}
	missing := filepath.Join(dir, "missing.yaml")
	if hosts, err := JitsiHosts(missing, override[:1]); err != nil || !slices.Equal(hosts, want[:1]) {
		t.Fatalf("an override must not read the list: %v %v", hosts, err)
	}
	if _, err := JitsiHosts(missing, []string{" ", ""}); err == nil {
		t.Fatal("a blank override fell through to a missing list without an error")
	}
	empty := filepath.Join(dir, "empty.yaml")
	if err := os.WriteFile(empty, []byte("instances: []\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := JitsiHosts(empty, nil); !errors.Is(err, ErrNoJitsiHost) {
		t.Fatalf("an empty list: err = %v", err)
	}
}

// ai-generated: an override host is withheld as the room URL carries it and
// bare, as a resolver's error, the Jitsi config and a JID carry it.
func TestHostFormsAreEveryFormALogWrites(t *testing.T) {
	for _, c := range []struct {
		host string
		want []string
	}{
		{"meet.example.invalid", []string{"meet.example.invalid"}},
		{"jitsi.example.invalid:8443", []string{"jitsi.example.invalid:8443", "jitsi.example.invalid"}},
		{"Jitsi.Example.Invalid:8443", []string{"Jitsi.Example.Invalid:8443", "jitsi.example.invalid:8443",
			"Jitsi.Example.Invalid", "jitsi.example.invalid"}},
		{"[2001:db8::1]:8443", []string{"[2001:db8::1]:8443", "2001:db8::1"}},
	} {
		if got := hostForms([]string{c.host}); !slices.Equal(got, c.want) {
			t.Errorf("hostForms(%q) = %q, want %q", c.host, got, c.want)
		}
	}
	if got := hostForms([]string{"a.example.invalid", "A.example.invalid"}); len(got) != 2 {
		t.Fatalf("hostForms repeats a form: %q", got)
	}
}

func TestPoolRoomTakesOneEntryByRunNumber(t *testing.T) {
	pool := []string{"a", " b ", "c"}
	for run, want := range map[int]string{0: "a", 7: "b", 2: "c", -1: "c", math.MinInt: "b"} {
		if got, err := PoolRoom(pool, run); err != nil || got != want {
			t.Fatalf("PoolRoom(%d) = %q %v, want %q", run, got, err, want)
		}
	}
	if _, err := PoolRoom(nil, 1); !errors.Is(err, ErrPoolRoom) {
		t.Fatalf("empty pool: err = %v", err)
	}
	if _, err := PoolRoom([]string{"a", " "}, 1); !errors.Is(err, ErrPoolRoom) {
		t.Fatalf("blank entry: err = %v", err)
	}
}

func TestKeysAndChannelsAreFreshHex(t *testing.T) {
	k1, err1 := NewKey()
	k2, err2 := NewKey()
	if err1 != nil || err2 != nil || len(k1) != 64 || !allLowerHex(k1) || k1 == k2 {
		t.Fatalf("NewKey = %q %v, %q %v", k1, err1, k2, err2)
	}
	c1, err1 := NewChannel()
	c2, err2 := NewChannel()
	id, ok := strings.CutPrefix(c1, "gate-")
	if err1 != nil || err2 != nil || !ok || len(id) != 12 || !allLowerHex(id) || c1 == c2 {
		t.Fatalf("NewChannel = %q %v, %q %v", c1, err1, c2, err2)
	}
}

func TestTelemostURLIsWhatTheProviderJoinsBy(t *testing.T) {
	const joined = "https://telemost.yandex.ru/j/fake-telemost-1"
	for _, c := range []struct{ in, want string }{
		{"fake-telemost-1", joined},
		{" fake-telemost-1 ", joined},
		{joined, joined},
		{"http://telemost.yandex.ru/j/fake-telemost-1", joined},
		{"HTTPS://telemost.yandex.ru/j/fake-telemost-1", joined},
		{"telemost.yandex.ru/j/fake-telemost-1", joined},
		{"https://x.example.invalid/y", "https://x.example.invalid/y"},
		{"", ""},
	} {
		if got := TelemostURL(c.in); got != c.want {
			t.Fatalf("TelemostURL(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

func TestWBStreamRoomIDIsTheBareID(t *testing.T) {
	const id = "fake-wb-room-1"
	for _, c := range []struct{ in, want string }{
		{id, id},
		{" fake-wb-room-1 ", id},
		{"https://stream.wb.ru/room/fake-wb-room-1", id},
		{"https://stream.wb.ru/room/fake-wb-room-1/", id},
		{"https://stream.wb.ru/room/fake-wb-room-1?from=x#y", id},
		{"http://stream.wb.ru/room/fake%2Dwb%2Droom%2D1", id},
		{"stream.wb.ru/room/fake-wb-room-1", id},
		{"https://stream.wb.ru", ""},
		{"https://stream.wb.ru/", ""},
		{"", ""},
	} {
		if got := WBStreamRoomID(c.in); got != c.want {
			t.Fatalf("WBStreamRoomID(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

func TestEndpointSecretsNameEverythingALogMustLose(t *testing.T) {
	key := strings.Repeat("ab", 32)
	ep := Endpoint{Provider: "jitsi", Transport: "datachannel", Room: "https://meet.example.invalid/gate-000000000001",
		Key: key, Channel: "gate-00000000000a", DNS: "192.0.2.53:53"}
	want := []string{ep.Room, "gate-000000000001", key, "gate-00000000000a"}
	if got := ep.Secrets(); !slices.Equal(got, want) {
		t.Fatalf("Secrets = %v, want %v", got, want)
	}
	bare := Endpoint{Room: "fake-wb-room-1", Key: key}
	if got := bare.Secrets(); !slices.Equal(got, []string{"fake-wb-room-1", key}) {
		t.Fatalf("Secrets of a bare room = %v", got)
	}
	if got := (Endpoint{Provider: "jitsi"}).Secrets(); len(got) != 0 {
		t.Fatalf("an endpoint without secrets lists %v", got)
	}
}

// ai-generated: a SaluteJazz room is "<code>:<password>", and a request or a
// log may carry either half alone, so each is withheld on its own too. Only
// that provider's room is cut: a URL's scheme is no password.
func TestEndpointSecretsWithholdEachHalfOfASaluteJazzRoom(t *testing.T) {
	key := strings.Repeat("ab", 32)
	ep := Endpoint{Provider: "salutejazz", Transport: "datachannel", Room: "fakecode1:fakepass1", Key: key,
		Channel: "gate-00000000000b"}
	want := []string{"fakecode1:fakepass1", "fakecode1", "fakepass1", key, "gate-00000000000b"}
	if got := ep.Secrets(); !slices.Equal(got, want) {
		t.Fatalf("Secrets = %v, want %v", got, want)
	}
	line := `POST /room/fakecode1/preconnect {"password":"fakepass1"}`
	if out := Scrub(line, ep.Secrets()...); strings.Contains(out, "fakecode1") || strings.Contains(out, "fakepass1") {
		t.Fatalf("a log line keeps half a room: %s", out)
	}
	tm := Endpoint{Provider: "telemost", Transport: "vp8channel", Room: "https://telemost.yandex.ru/j/fake-telemost-9"}
	if got := tm.Secrets(); !slices.Equal(got, []string{tm.Room, "fake-telemost-9"}) {
		t.Fatalf("Secrets of a telemost URL = %v, want the URL and its id alone", got)
	}
}

// ai-generated: a pool room URL with a query or a fragment is withheld as its
// bare id too. The telemost provider query-escapes the whole URL into its API
// path, so a request error carries the id bare, the query escaped after it.
func TestEndpointSecretsCutAQueryOffTheRoomID(t *testing.T) {
	for _, room := range []string{
		"https://telemost.yandex.ru/j/fake-telemost-7?utm_source=x",
		"https://telemost.yandex.ru/j/fake-telemost-7#join",
	} {
		ep := Endpoint{Provider: "telemost", Transport: "vp8channel", Room: room, Key: strings.Repeat("ab", 32)}
		if got := ep.Secrets(); !slices.Contains(got, "fake-telemost-7") {
			t.Fatalf("Secrets of %q = %q, want the bare id among them", room, got)
		}
		line := `Get "https://cloud-api.example.invalid/conferences/` + url.QueryEscape(room) +
			`/connection": context deadline exceeded`
		if out := Scrub(line, ep.Secrets()...); strings.Contains(out, "fake-telemost-7") {
			t.Fatalf("a request error keeps the room id: %s", out)
		}
	}
}
