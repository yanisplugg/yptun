package gate

import (
	"context"
	"errors"
	"slices"
	"testing"
)

const fakeMTSLink = "https://my.mts-link.ru/j/100200300/400500600700"

// mtslink carries the video transport alone: its SFU relays no data between
// guests and forwards VP9 and H264, which vp8channel dresses for and the
// other video transports do not.
func TestMTSLinkCarriesVP8ChannelAlone(t *testing.T) {
	lt, err := NewLocalTarget(LocalOptions{
		WorkDir: t.TempDir(), Providers: []string{"mtslink"}, Transports: []string{"vp8channel"},
		MTSLinkRooms: []string{fakeMTSLink},
	})
	if err != nil {
		t.Fatal(err)
	}
	if got := lt.Pairs(); !slices.Equal(got, []Pair{{"mtslink", "vp8channel"}}) {
		t.Fatalf("Pairs = %v, want mtslink/vp8channel", got)
	}
	for _, transport := range []string{"datachannel", "videochannel", "seichannel"} {
		_, err := NewLocalTarget(LocalOptions{
			WorkDir: t.TempDir(), Providers: []string{"mtslink"}, Transports: []string{transport},
		})
		if !errors.Is(err, ErrLocalOptions) {
			t.Errorf("mtslink with %s: err = %v, want a plan refused", transport, err)
		}
	}
}

func TestMTSLinkRoomIsThePoolsJoinLink(t *testing.T) {
	second := "https://my.mts-link.ru/j/100200300/400500600701"
	lt, err := NewLocalTarget(LocalOptions{
		WorkDir: t.TempDir(), Providers: []string{"mtslink"}, Transports: []string{"vp8channel"},
		MTSLinkRooms: []string{fakeMTSLink, second}, RunNumber: 3,
	})
	if err != nil {
		t.Fatal(err)
	}
	if r, err := lt.room(context.Background(), "mtslink"); err != nil || r != second {
		t.Fatalf("mtslink room = %q %v, want the pool entry of the run, as given", r, err)
	}
	lt.opts.MTSLinkRooms = nil
	if _, err := lt.room(context.Background(), "mtslink"); !errors.Is(err, ErrPoolRoom) {
		t.Fatalf("an empty pool: err = %v, want %v", err, ErrPoolRoom)
	}
}

// The default walk is what a release is judged by. WB Stream closed its beta
// to its own staff on 2026-10-10 and answers every join 401, Telemost's relay
// cuts every published track to a trickle (#58), and the MAX account the
// gate signs in with is locked (2026-10-19), so each is walked only when
// named; MTS Link took WB Stream's place on the fleet and in the walk.
func TestTheDefaultWalkHasMTSLinkAndLeavesWBStreamToBeNamed(t *testing.T) {
	walk := localProviders()
	if !slices.Contains(walk, "mtslink") {
		t.Fatalf("default walk %v lacks mtslink", walk)
	}
	for _, out := range []string{"wbstream", "telemost", "max"} {
		if slices.Contains(walk, out) {
			t.Fatalf("default walk %v still has %s, whose cells can only fail today", walk, out)
		}
	}
	for _, pair := range []Pair{{"telemost", "vp8channel"}, {"max", "datachannel"}} {
		named, err := NewLocalTarget(LocalOptions{
			WorkDir: t.TempDir(), Providers: []string{pair.Provider}, Transports: []string{pair.Transport},
		})
		if err != nil || !slices.Equal(named.Pairs(), []Pair{pair}) {
			t.Fatalf("%s named: pairs = %v, err = %v; it is still carried", pair.Provider, named.Pairs(), err)
		}
	}
	named, err := NewLocalTarget(LocalOptions{
		WorkDir: t.TempDir(), Providers: []string{"wbstream"}, Transports: []string{"vp8channel"},
	})
	if err != nil || !slices.Equal(named.Pairs(), []Pair{{"wbstream", "vp8channel"}}) {
		t.Fatalf("wbstream named: pairs = %v, err = %v; it is still carried", named.Pairs(), err)
	}
}

func TestMTSLinkIsALoadPair(t *testing.T) {
	lt, err := NewLocalTarget(LocalOptions{
		WorkDir: t.TempDir(), Providers: []string{"mtslink"}, Transports: []string{"vp8channel"},
		MTSLinkRooms: []string{fakeMTSLink},
	})
	if err != nil {
		t.Fatal(err)
	}
	want := make([]string, 0, 8)
	want = append(want, "mtslink/vp8channel/cli/S0", "mtslink/vp8channel/mobile/S0")
	for _, id := range []string{"S1", "S2", "S3", "S4", "S5", "S7"} {
		want = append(want, "mtslink/vp8channel/mobile/"+id)
	}
	if got := planned(lt, "cli", "mobile"); !slices.Equal(got, want) {
		t.Fatalf("plan:\n%q\nwant\n%q", got, want)
	}
}

// The join link names the account that owns the room and the event: a log
// loses both, each on its own too.
func TestEndpointSecretsWithholdBothIDsOfAnMTSLink(t *testing.T) {
	ep := Endpoint{Provider: "mtslink", Transport: "vp8channel", Room: fakeMTSLink, Key: "k"}
	got := ep.Secrets()
	for _, want := range []string{fakeMTSLink, "400500600700", "100200300", "k"} {
		if !slices.Contains(got, want) {
			t.Errorf("Secrets = %v, lacks %q", got, want)
		}
	}
	other := Endpoint{Provider: "telemost", Room: "https://telemost.yandex.ru/j/123456", Key: "k"}
	if slices.Contains(other.Secrets(), "j") {
		t.Error("another provider's room lost a path segment that is no id")
	}
}
