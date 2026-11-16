package script

import (
	"testing"
	"time"

	"github.com/pion/sctp"
	"github.com/pion/webrtc/v4"
)

// pion/webrtc asks for an MTU of 1191 in every SCTP association it builds, so a setting that only changed the
// default would never be seen: the override has to reach the association of a real connection, on both ends.
func TestSCTPOutboundMTUOverrideReachesTheAssociation(t *testing.T) {
	if err := sctp.SetOutboundMTU(1360); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = sctp.SetOutboundMTU(0) })

	var se webrtc.SettingEngine
	se.SetIncludeLoopbackCandidate(true)
	se.SetNetworkTypes([]webrtc.NetworkType{webrtc.NetworkTypeUDP4})
	api := webrtc.NewAPI(webrtc.WithSettingEngine(se))
	a, err := api.NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		t.Fatal(err)
	}
	defer a.Close()
	b, err := api.NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()

	opened := make(chan struct{})
	dc, err := a.CreateDataChannel("x", nil)
	if err != nil {
		t.Fatal(err)
	}
	dc.OnOpen(func() { close(opened) })

	offer, err := a.CreateOffer(nil)
	if err != nil {
		t.Fatal(err)
	}
	gatheredA := webrtc.GatheringCompletePromise(a)
	if err := a.SetLocalDescription(offer); err != nil {
		t.Fatal(err)
	}
	<-gatheredA
	if err := b.SetRemoteDescription(*a.LocalDescription()); err != nil {
		t.Fatal(err)
	}
	answer, err := b.CreateAnswer(nil)
	if err != nil {
		t.Fatal(err)
	}
	gatheredB := webrtc.GatheringCompletePromise(b)
	if err := b.SetLocalDescription(answer); err != nil {
		t.Fatal(err)
	}
	<-gatheredB
	if err := a.SetRemoteDescription(*b.LocalDescription()); err != nil {
		t.Fatal(err)
	}
	select {
	case <-opened:
	case <-time.After(15 * time.Second):
		t.Fatal("the data channel did not open")
	}

	for name, pc := range map[string]*webrtc.PeerConnection{"offerer": a, "answerer": b} {
		if mtu := pc.SCTP().Stats().MTU; mtu != 1360 {
			t.Fatalf("%s: SCTP MTU %d, want 1360", name, mtu)
		}
	}
}
