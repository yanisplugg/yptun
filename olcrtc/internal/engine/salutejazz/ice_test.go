package salutejazz

import (
	"testing"
	"time"

	"github.com/pion/webrtc/v4"
)

// TestEveryTrickledCandidateCarriesItsUfrag pins what the capture shows: all
// 21 client rtc:ice frames carry a usernameFragment, and pion's
// ICECandidate.ToJSON does not fill one in - it renders the candidate line,
// the mid and the m-line index and nothing else. The engine has to read the
// fragment off the transport that gathered the candidate, and it has to be
// that transport's: a subscriber candidate under the publisher's fragment
// names an ICE session the SFU is not running.
func TestEveryTrickledCandidateCarriesItsUfrag(t *testing.T) {
	url, fake := newFakeConnector(t)
	sess := connectSession(t, url, "ufrag")

	waitFor(t, 10*time.Second, "candidates for both peer connections", func() bool {
		seen := map[string]bool{}
		for _, cand := range fake.clientICE() {
			seen[cand.target] = true
		}
		return seen[targetSubscriber] && seen[targetPublisher]
	})

	gen := sess.current()
	want := map[string]string{
		targetSubscriber: localICEUfrag(gen.subPC.Load()),
		targetPublisher:  localICEUfrag(gen.pubPC.Load()),
	}
	for target, ufrag := range want {
		if ufrag == "" {
			t.Fatalf("the %s peer connection reports no local ICE fragment", target)
		}
	}
	for _, cand := range fake.clientICE() {
		if cand.ufrag == "" {
			t.Fatalf("a %s candidate reached the connector with no usernameFragment", cand.target)
		}
		if cand.ufrag != want[cand.target] {
			t.Fatalf("a %s candidate carried the fragment %q, want the one that peer connection gathered under",
				cand.target, cand.ufrag)
		}
	}
	if failure := fake.lastError(); failure != "" {
		t.Fatalf("fake SFU error: %s", failure)
	}
}

// TestTheUfragIsReadOffTheLocalDescription covers the accessor on its own: a
// peer connection that has put a description on the wire answers with the
// fragment that description carries, and one that has not - or none at all -
// answers with nothing rather than failing a candidate.
func TestTheUfragIsReadOffTheLocalDescription(t *testing.T) {
	if got := localICEUfrag(nil); got != "" {
		t.Fatalf("localICEUfrag(nil) = %q, want empty", got)
	}
	api, err := newWebRTCAPI(nil, "")
	if err != nil {
		t.Fatal(err)
	}
	pc, err := api.NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = pc.Close() })
	if got := localICEUfrag(pc); got != "" {
		t.Fatalf("a peer connection with no description reports the fragment %q", got)
	}

	ordered := true
	if _, err = pc.CreateDataChannel(labelReliable, &webrtc.DataChannelInit{Ordered: &ordered}); err != nil {
		t.Fatal(err)
	}
	offer, err := pc.CreateOffer(nil)
	if err != nil {
		t.Fatal(err)
	}
	if err = pc.SetLocalDescription(offer); err != nil {
		t.Fatal(err)
	}
	ufrag := localICEUfrag(pc)
	if ufrag == "" {
		t.Fatal("a peer connection that has offered reports no ICE fragment")
	}
	if want := ufragFromSDP(offer.SDP); ufrag != want {
		t.Fatalf("fragment %q, want the one the offer carried (%q)", ufrag, want)
	}

	// The attribute is read out of the description and nothing else is.
	for sdp, want := range map[string]string{
		"v=0\r\na=ice-ufrag:abcd\r\na=ice-pwd:passw0rd\r\n": "abcd",
		"v=0\r\na=ice-pwd:passw0rd\r\n":                     "",
		"":                                                  "",
	} {
		if got := ufragFromSDP(sdp); got != want {
			t.Fatalf("ufragFromSDP(%q) = %q, want %q", sdp, got, want)
		}
	}
}
